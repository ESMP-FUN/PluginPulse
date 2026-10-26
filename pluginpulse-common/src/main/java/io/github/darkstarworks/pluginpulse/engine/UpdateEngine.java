package io.github.darkstarworks.pluginpulse.engine;

import io.github.darkstarworks.pluginpulse.UpdateCheckResult;
import io.github.darkstarworks.pluginpulse.UpdateInfo;
import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.download.BackupManager;
import io.github.darkstarworks.pluginpulse.download.DownloadPipeline;
import io.github.darkstarworks.pluginpulse.source.HttpSupport;
import io.github.darkstarworks.pluginpulse.source.SourceContext;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import io.github.darkstarworks.pluginpulse.state.IgnoreStore;
import io.github.darkstarworks.pluginpulse.state.PendingUpdateStore;
import io.github.darkstarworks.pluginpulse.version.Version;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The platform-neutral update logic: checking, holding, staging, restoring and
 * the update subcommand. Each server platform subclasses it with its own
 * scheduler, messaging and file locations.
 *
 * @param <S> the platform's command sender type
 */
public abstract class UpdateEngine<S> {

    /** Actions the update subcommand understands, for tab completion. */
    public static final List<String> ACTIONS =
            List.of("check", "download", "apply", "ignore", "unignore", "restore", "status");

    private final String pluginName;
    private final String currentVersion;
    private final List<UpdateSource> sources;
    private final UpdateMode mode;
    private final Duration checkInterval;
    private final Duration minimumReleaseAge;
    private final String permission;
    private final String commandRoot;
    private final String track;
    private final String changelogUrl;
    private final String serverVersion;
    private final boolean requireHash;
    private final int backupRetention;
    private final HttpSupport http;
    private final Path stateDir;
    private final IgnoreStore state;
    private final PendingUpdateStore pendingStore;

    private volatile UpdateInfo pendingUpdate;
    /** Found, but still settling in, deliberately kept out of {@link #pendingUpdate}. */
    private volatile UpdateInfo heldUpdate;
    private volatile long heldUntilEpochMs;
    private volatile String heldLoggedVersion;
    private volatile UpdateCheckResult lastResult;
    private volatile Map<String, String> downloadHeaders = Map.of();
    private volatile String stagedVersion;
    private volatile boolean stopped;
    private Runnable cancelPeriodic;

    protected UpdateEngine(EngineSettings s, Path dataDirectory, Logger logger) {
        this.pluginName = s.pluginName();
        this.currentVersion = s.currentVersion();
        this.sources = s.sources();
        this.mode = s.mode();
        this.checkInterval = s.checkInterval();
        this.minimumReleaseAge = s.minimumReleaseAge();
        this.permission = s.permission();
        this.commandRoot = s.commandRoot();
        this.track = s.track();
        this.changelogUrl = s.changelogUrl();
        this.serverVersion = s.serverVersion();
        this.requireHash = s.requireHash();
        this.backupRetention = s.backupRetention();
        String contact = s.userAgentContact() != null ? " (" + s.userAgentContact() + ")" : "";
        this.http = new HttpSupport("pluginpulse/" + s.pluginName() + "/" + s.currentVersion() + contact);
        this.stateDir = dataDirectory.resolve("pluginpulse");
        this.state = new IgnoreStore(stateDir.resolve("state.json"), logger);
        this.pendingStore = new PendingUpdateStore(stateDir.resolve("pending-update.json"), logger);
    }

    // ==== Platform hooks ====

    protected abstract Logger logger();

    /** False once the server has turned the plugin off. */
    protected abstract boolean pluginEnabled();

    /** The jar the plugin is running from. */
    protected abstract Path currentJar();

    /** Where a downloaded jar waits to be put in place, under the live jar's filename. */
    protected abstract Path stagingFolder();

    protected abstract void runAsync(Runnable task);

    /** @return a handle that cancels the task */
    protected abstract Runnable runAsyncRepeating(Runnable task, Duration delay, Duration period);

    /** Run where the platform's sender and player API is safe to use. */
    protected abstract void runGlobal(Runnable task);

    protected abstract boolean hasPermission(S sender, String permission);

    protected abstract void sendPlain(S sender, String message);

    protected abstract S console();

    /** Run {@code action} for every online player holding the update permission. */
    protected abstract void forEachAdmin(Consumer<S> action);

    protected abstract void noticeAvailable(S sender, UpdateInfo info);

    protected abstract void noticeAvailableConsole(UpdateInfo info);

    protected abstract void noticeStaged(S sender, UpdateInfo info, boolean canReload);

    protected abstract void noticeStagedConsole(UpdateInfo info, boolean canReload);

    /** "restart the server" or "restart the proxy", lower case. */
    protected abstract String restartPhrase();

    /** What to check when a staged update never got put in place. */
    protected abstract String stagedNotAppliedHint();

    /** Null when an in-place reload could run now, otherwise why not. */
    protected String reloadRefusal() {
        return "this platform can't swap a plugin while it runs.";
    }

    /** Whether a reload engine is configured at all. */
    protected boolean hasReloadEngine() {
        return false;
    }

    /** Swap the running plugin for {@code stagedJar}. Called via {@link #runGlobal}. */
    protected void reload(Path stagedJar, Path backupJar) throws Exception {
        throw new UnsupportedOperationException("reload");
    }

    // ==== Lifecycle ====

    /** Reconcile last boot's staged update and begin periodic checks. */
    public void start() {
        processPendingMarker();
        // Small startup delay + jitter so fleets of servers don't check in lockstep.
        Duration delay = Duration.ofMillis(5_000L + ThreadLocalRandom.current().nextLong(0, 10_000L));
        cancelPeriodic = runAsyncRepeating(() -> guarded(() -> runCheck(true, null)), delay, checkInterval);
    }

    /** Stop checking and abort any request in flight. */
    public void stop() {
        stopped = true;
        http.shutdownNow();
        if (cancelPeriodic != null) {
            cancelPeriodic.run();
            cancelPeriodic = null;
        }
    }

    public boolean isStopped() {
        return stopped;
    }

    /**
     * Run a background step. Once the plugin is shutting down the server has
     * closed its jar, so a step that was already running can fail to load a
     * class or schedule a follow-up; that is expected then and dropped.
     */
    private void guarded(Runnable step) {
        if (stopped) return;
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            if (isActive()) throw e;
        }
    }

    private boolean isActive() {
        return !stopped && pluginEnabled();
    }

    private void reply(S sender, String message) {
        sendPlain(sender, pluginName + ": " + message);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ==== Subcommand ====

    /**
     * Handle the arguments after {@code update}: check (default), download,
     * apply, ignore, unignore, restore, status.
     *
     * @return false when the sender lacks the update permission
     */
    public boolean handle(S sender, String[] args) {
        if (!hasPermission(sender, permission)) {
            return false;
        }
        String action = args.length == 0 ? "check" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "check" -> checkNow(sender);
            case "download", "install" -> downloadAndStage(sender);
            case "ignore" -> {
                if (args.length < 2) {
                    sendPlain(sender, "Usage: update ignore <version>");
                } else {
                    ignoreVersion(args[1]);
                    sendPlain(sender, "Ignoring version " + args[1] + " (until a newer release).");
                }
            }
            case "unignore" -> {
                if (args.length < 2) {
                    sendPlain(sender, "Usage: update unignore <version>");
                } else {
                    unignoreVersion(args[1]);
                    sendPlain(sender, "No longer ignoring version " + args[1] + ".");
                }
            }
            case "restore" -> restoreBackup(sender);
            case "apply", "reload" -> applyNow(sender);
            case "status" -> sendPlain(sender, "Status: " + statusLine());
            default -> sendPlain(sender,
                    "Unknown update action. Try: check, download, apply, ignore <version>, unignore <version>, restore, status");
        }
        return true;
    }

    // ==== Check ====

    /** Manual check (e.g. from a command). Reports the outcome to {@code sender}. */
    public void checkNow(S sender) {
        if (stopped) return;
        runAsync(() -> guarded(() -> runCheck(false, result -> runGlobal(() -> {
            switch (result.status()) {
                case UPDATE_AVAILABLE -> noticeAvailable(sender, result.info());
                case HELD -> reply(sender, holdNotice());
                case IGNORED -> reply(sender, "the latest version, " + result.info().version()
                        + ", is on your ignore list. You have " + currentVersion + ".");
                case UP_TO_DATE -> sendPlain(sender, pluginName + " is up to date (" + currentVersion + ").");
                case FAILED -> reply(sender, "the update check failed: "
                        + (result.error() != null ? result.error().getMessage() : "unknown error"));
            }
        }))));
    }

    /**
     * Reconcile the staged-update marker on boot: if we're now running the
     * staged version the update applied; otherwise the staged jar never got
     * swapped in (or keeps failing) and we warn rather than re-stage forever.
     */
    private void processPendingMarker() {
        PendingUpdateStore.Pending pending = pendingStore.load();
        if (pending == null) return;
        if (Version.parse(pending.stagedVersion(), track).compareTo(Version.parse(currentVersion, track)) == 0) {
            logger().info("Update to " + pending.stagedVersion() + " applied successfully."
                    + (pending.backupPath() != null ? " Previous version backed up at " + pending.backupPath() : ""));
            pendingStore.clear();
            return;
        }
        int attempts = pending.attempts() + 1;
        pendingStore.save(new PendingUpdateStore.Pending(pending.stagedVersion(), pending.backupPath(), attempts));
        stagedVersion = pending.stagedVersion();
        logger().warning("A staged update to " + pending.stagedVersion() + " has not applied after "
                + attempts + " boot(s); still running " + currentVersion + ". " + stagedNotAppliedHint());
    }

    private void runCheck(boolean notify, Consumer<UpdateCheckResult> callback) {
        UpdateCheckResult result = doCheck();
        lastResult = result;
        if (result.status() == UpdateCheckResult.Status.HELD) {
            // Newer, but still settling in: no notice, no auto-stage. Kept aside so
            // /status can explain the wait and an admin can still force the install.
            UpdateInfo info = result.info();
            pendingUpdate = null;
            heldUpdate = info;
            if (!info.version().equalsIgnoreCase(heldLoggedVersion)) {
                heldLoggedVersion = info.version();
                logger().info("Version " + info.version() + " is out, but it is being left alone "
                        + "for now: new releases have to be available for " + minimumReleaseAge.toHours()
                        + " hour(s) before this server takes them.");
            }
            if (callback != null) callback.accept(result);
            return;
        }
        heldUpdate = null;
        if (result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE) {
            UpdateInfo info = result.info();
            pendingUpdate = info;
            if (mode == UpdateMode.AUTO_STAGE) {
                // Auto-stage owns its own messaging (and, when safe, the install),
                // so it replaces the generic "update available" notice.
                if (!info.version().equalsIgnoreCase(stagedVersion)) {
                    logger().info("Auto-staging update " + info.version() + "...");
                    logger().info(stage(info));
                    if (info.version().equalsIgnoreCase(stagedVersion)) {
                        if (canHotReloadNow()) {
                            applyNow(console()); // true hands-off: no restart
                        } else {
                            announceStaged(info);
                        }
                    }
                }
            } else if (notify && mode.atLeast(UpdateMode.NOTIFY)) {
                noticeAvailableConsole(info);
                forEachAdmin(admin -> noticeAvailable(admin, info));
            }
        } else {
            pendingUpdate = null;
        }
        if (callback != null) callback.accept(result);
    }

    private UpdateCheckResult doCheck() {
        Exception lastError = null;
        SourceContext ctx = new SourceContext(http, track, logger(), serverVersion);
        for (UpdateSource source : sources) {
            if (stopped) break;
            try {
                UpdateInfo info = source.fetchLatest(ctx);
                // Authenticated sources carry their headers over to the download
                // (a private GitHub repo's Bearer token, a store's licence key, ...).
                downloadHeaders = source.downloadHeaders();
                state.recordCheck(info.version());
                Version latest = Version.parse(info.version(), track);
                Version current = Version.parse(currentVersion, track);
                if (!latest.isNewerThan(current)) {
                    return UpdateCheckResult.upToDate();
                }
                if (state.isIgnored(info.version())) {
                    return UpdateCheckResult.ignored(info);
                }
                long readyAt = readyAtEpochMs(info);
                if (readyAt > System.currentTimeMillis()) {
                    heldUntilEpochMs = readyAt;
                    return UpdateCheckResult.held(info);
                }
                return UpdateCheckResult.available(withChangelogOverride(ctx, info));
            } catch (Exception e) {
                lastError = e;
                logger().log(Level.FINE, "Update source '" + source.name() + "' failed: " + e.getMessage());
            }
        }
        if (lastError != null && isActive()) {
            logger().warning("Update check failed (all sources): " + lastError.getMessage());
        }
        return UpdateCheckResult.failed(lastError);
    }

    /**
     * The moment this release may be acted on, per the optional settle-in time.
     * The publisher's release timestamp is the clock when a source provides one;
     * otherwise we fall back to when this server first saw the version, which
     * can only ever make the wait longer, never shorter.
     */
    private long readyAtEpochMs(UpdateInfo info) {
        if (minimumReleaseAge == null || minimumReleaseAge.isZero() || minimumReleaseAge.isNegative()) {
            return 0L;
        }
        long releasedAt = info.publishedEpochMs() > 0 ? info.publishedEpochMs() : state.firstSeenEpochMs();
        if (releasedAt <= 0) releasedAt = System.currentTimeMillis();
        return releasedAt + minimumReleaseAge.toMillis();
    }

    private UpdateInfo withChangelogOverride(SourceContext ctx, UpdateInfo info) {
        if (changelogUrl == null) return info;
        try {
            String text = ctx.http().get(changelogUrl).trim();
            return new UpdateInfo(info.version(), text, info.downloadUrl(), info.fileName(),
                    info.hashes(), info.sizeBytes(), info.restartRequired(), info.releasePageUrl(),
                    info.publishedEpochMs());
        } catch (Exception e) {
            ctx.logger().fine("Changelog fetch failed: " + e.getMessage());
            return info;
        }
    }

    // ==== Download & stage ====

    /**
     * Download the pending update, verify it, back up the running jar and
     * stage the new one for install on the next restart. Reports progress to
     * {@code sender}. Requires a prior check to have found an update.
     */
    public void downloadAndStage(S sender) {
        // An explicit, admin-initiated download is allowed from NOTIFY up: notify
        // mode offers a "download in the background" button, and clicking it is a
        // clear opt-in. Only silent CHECK_ONLY (and off) refuse.
        if (!mode.atLeast(UpdateMode.NOTIFY)) {
            reply(sender, "downloads are disabled (mode " + mode + ").");
            return;
        }
        // An admin who types the download command outrides the settle-in wait,
        // they asked for this version by hand, so give it to them.
        UpdateInfo info = pendingUpdate != null ? pendingUpdate : heldUpdate;
        if (info == null) {
            reply(sender, "no update has been found yet. Run a check first.");
            return;
        }
        if (info.version().equalsIgnoreCase(stagedVersion)) {
            reply(sender, info.version() + " is already downloaded. To finish, " + applyHint() + ".");
            return;
        }
        reply(sender, "downloading " + info.version() + " in the background...");
        runAsync(() -> guarded(() -> {
            String message = stage(info);
            runGlobal(() -> {
                reply(sender, message);
                // Follow-up "ready to install" notice to every admin (not just the
                // clicker), telling them whether it hot-reloads or needs a restart.
                if (info.version().equalsIgnoreCase(stagedVersion)) announceStaged(info);
            });
        }));
    }

    /** Whether the staged update can be applied right now without a restart. */
    public boolean canHotReloadNow() {
        return hasReloadEngine() && reloadRefusal() == null;
    }

    private String applyHint() {
        return canHotReloadNow()
                ? "apply it now with " + (commandRoot != null ? commandRoot + " update apply" : "the update apply command")
                : restartPhrase() + " to apply it";
    }

    private void announceStaged(UpdateInfo info) {
        boolean canReload = canHotReloadNow();
        noticeStagedConsole(info, canReload);
        forEachAdmin(admin -> noticeStaged(admin, info, canReload));
    }

    /** Stage the given update. Returns a human-readable outcome line. */
    private String stage(UpdateInfo info) {
        try {
            DownloadPipeline.StagingResult result =
                    buildPipeline().downloadAndStage(info, downloadHeaders, currentJar(), currentVersion);
            pendingStore.save(new PendingUpdateStore.Pending(
                    info.version(), result.backupFile().toString(), 0));
            stagedVersion = info.version();
            logger().info("Staged update " + info.version() + " -> " + result.stagedFile());
            return "downloaded " + info.version() + ". To finish, " + applyHint() + ".";
        } catch (Exception e) {
            logger().log(Level.WARNING, "Update staging failed", e);
            return "update failed: " + e.getMessage();
        }
    }

    /**
     * Stage the most recent backup jar for install on the next restart,
     * the supported one-command recovery path after a bad update.
     */
    public void restoreBackup(S sender) {
        if (stopped) return;
        runAsync(() -> guarded(() -> {
            String message;
            try {
                Path latest = buildBackupManager().latestBackup();
                if (latest == null) {
                    message = "no backups found.";
                } else {
                    buildPipeline().stageLocal(latest, currentJar());
                    pendingStore.clear();
                    stagedVersion = null;
                    message = "the previous version (" + latest.getFileName() + ") is ready. "
                            + capitalize(restartPhrase()) + " to go back to it.";
                }
            } catch (Exception e) {
                logger().log(Level.WARNING, "Backup restore failed", e);
                message = "restore failed: " + e.getMessage();
            }
            String finalMessage = message;
            runGlobal(() -> reply(sender, finalMessage));
        }));
    }

    /**
     * Apply a staged update immediately via the platform's reload engine, with
     * no restart. Refused when there is none, nothing is staged, or its safety
     * checks fail. On success the running plugin instance is replaced.
     */
    public void applyNow(S sender) {
        if (!hasReloadEngine()) {
            reply(sender, "installing without a restart isn't available here. "
                    + capitalize(restartPhrase()) + " to apply the update.");
            return;
        }
        String staged = stagedVersion;
        if (staged == null) {
            reply(sender, "nothing has been downloaded yet. Run the download command first.");
            return;
        }
        String refusal = reloadRefusal();
        if (refusal != null) {
            reply(sender, "hot reload refused: " + refusal + " "
                    + capitalize(restartPhrase()) + " to apply the staged update.");
            return;
        }
        runGlobal(() -> {
            try {
                Path stagedJar = stagedJar();
                if (!Files.exists(stagedJar)) {
                    reply(sender, "the downloaded update is missing. Run the download command again.");
                    return;
                }
                PendingUpdateStore.Pending pending = pendingStore.load();
                Path backup = pending != null && pending.backupPath() != null
                        ? Path.of(pending.backupPath()) : null;
                reply(sender, "hot-reloading to " + staged + ": watch the console for the outcome.");
                logger().warning("Hot reload starting: " + currentVersion + " -> " + staged);
                // The marker is reconciled by the NEW instance's start().
                reload(stagedJar, backup);
            } catch (Exception e) {
                logger().log(Level.SEVERE, "Hot reload failed", e);
                reply(sender, "hot reload failed: " + e.getMessage() + ". A restart may be needed.");
            }
        });
    }

    /** The staged jar for the live one, whether or not it exists yet. */
    public Path stagedJar() {
        return stagingFolder().resolve(currentJar().getFileName().toString());
    }

    private DownloadPipeline buildPipeline() {
        return new DownloadPipeline(http, stateDir.resolve("tmp"), stagingFolder(),
                buildBackupManager(), requireHash, logger());
    }

    private BackupManager buildBackupManager() {
        return new BackupManager(stateDir.resolve("backups"), backupRetention, logger());
    }

    // ==== Accessors ====

    public String pluginName() {
        return pluginName;
    }

    /** The update found by the most recent check, or null when up to date/ignored. */
    public UpdateInfo pendingUpdate() {
        return pendingUpdate;
    }

    public UpdateCheckResult lastResult() {
        return lastResult;
    }

    /**
     * A newer version that was found but is still inside its settle-in window,
     * or null. Nothing is announced, downloaded or staged for it until the
     * wait is over.
     */
    public UpdateInfo heldUpdate() {
        return heldUpdate;
    }

    /** Plain-English explanation of the settle-in wait, or null when nothing is held back. */
    public String holdNotice() {
        UpdateInfo held = heldUpdate;
        if (held == null) return null;
        long remainingMs = heldUntilEpochMs - System.currentTimeMillis();
        long hours = Math.max(1L, Math.round(remainingMs / 3_600_000.0));
        return "version " + held.version() + " is out (you have " + currentVersion + "), but it is brand new."
                + " It will be offered in about " + hours + " hour(s). New releases have to be available for "
                + minimumReleaseAge.toHours() + " hour(s) first, so a quick fix-up release can land before"
                + " this server takes one. Use the download command to install it right away anyway.";
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** One plain-English line on where things stand, for status commands. */
    public String statusLine() {
        UpdateInfo pending = pendingUpdate;
        if (pending != null) {
            return "update available: " + pending.version() + " (you have " + currentVersion + ").";
        }
        String held = holdNotice();
        if (held != null) return "waiting: " + held;
        UpdateCheckResult last = lastResult;
        if (last == null) {
            return "no update check has run yet (you have " + currentVersion + "). Run the check command to check now.";
        }
        return switch (last.status()) {
            case FAILED -> "the last update check failed"
                    + (last.error() != null ? " (" + last.error().getMessage() + ")" : "")
                    + ". You have " + currentVersion + ".";
            case IGNORED -> "the latest version, " + last.info().version() + ", is on your ignore list. You have "
                    + currentVersion + ".";
            default -> "up to date (" + currentVersion + ") as of the last check.";
        };
    }

    public String permission() {
        return permission;
    }

    public String commandRoot() {
        return commandRoot;
    }

    public UpdateMode mode() {
        return mode;
    }

    /** Suppress notifications for a version until a newer one is released. */
    public void ignoreVersion(String version) {
        state.ignore(version);
        UpdateInfo pending = pendingUpdate;
        if (pending != null && pending.version().equalsIgnoreCase(version)) {
            pendingUpdate = null;
        }
    }

    public void unignoreVersion(String version) {
        state.unignore(version);
    }
}
