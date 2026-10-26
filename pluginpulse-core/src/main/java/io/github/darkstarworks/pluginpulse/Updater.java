package io.github.darkstarworks.pluginpulse;

import io.github.darkstarworks.pluginpulse.download.PluginJarLocator;
import io.github.darkstarworks.pluginpulse.engine.EngineSettings;
import io.github.darkstarworks.pluginpulse.engine.UpdateEngine;
import io.github.darkstarworks.pluginpulse.notify.JoinNotifyListener;
import io.github.darkstarworks.pluginpulse.notify.UpdateNotifier;
import io.github.darkstarworks.pluginpulse.platform.SchedulerAdapter;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import io.github.darkstarworks.pluginpulse.version.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Entry point. Build once in {@code onEnable}, call {@link #start()}, and call
 * {@link #shutdown()} in {@code onDisable}.
 *
 * <pre>{@code
 * Updater updater = Updater.builder(this)
 *     .source(new ModrinthSource("myplugin"))
 *     .fallbackSource(new GitHubReleasesSource("me/myplugin"))
 *     .mode(UpdateMode.NOTIFY)
 *     .permission("myplugin.admin")
 *     .userAgentContact("me@example.com")
 *     .build();
 * updater.start();
 * }</pre>
 */
public final class Updater {

    private final JavaPlugin plugin;
    private final boolean notifyOnJoin;
    private final boolean selfRegisterCommand;
    private final SchedulerAdapter scheduler;
    private final UpdateNotifier notifier;
    private final Supplier<File> jarFileSupplier;
    private final ReloadEngine reloadEngine;
    private final BukkitEngine engine;
    private JoinNotifyListener joinListener;
    private CommandRegistration commandRegistration;

    private Updater(Builder b) {
        this.plugin = b.plugin;
        this.notifyOnJoin = b.mode.atLeast(UpdateMode.NOTIFY);
        this.selfRegisterCommand = b.selfRegisterCommand;
        this.scheduler = b.scheduler != null ? b.scheduler : SchedulerAdapter.create(b.plugin);
        this.notifier = new UpdateNotifier(b.prefix, b.commandRoot, b.messageOverrides);
        this.jarFileSupplier = b.jarFileSupplier;
        this.reloadEngine = b.reloadEngine;
        EngineSettings settings = new EngineSettings(plugin.getName(), b.currentVersion, b.sources, b.mode,
                b.checkInterval, b.minimumReleaseAge, b.permission, b.commandRoot, b.track, b.changelogUrl,
                b.userAgentContact, b.requireHash, b.backupRetention,
                b.matchServerVersion ? ServerVersion.detect() : null);
        this.engine = new BukkitEngine(settings);
    }

    // ==== Lifecycle ====

    /** Register the join listener (mode >= NOTIFY) and begin periodic checks. */
    public void start() {
        engine.start();
        if (notifyOnJoin) {
            joinListener = new JoinNotifyListener(this);
            Bukkit.getPluginManager().registerEvents(joinListener, plugin);
        }
        // Self-register the root command when one is configured and its name is
        // free. Adopters that declare their own command in plugin.yml keep it
        // (the name is taken -> skipped); injected/one-file jars gain a working
        // /<root> update command they otherwise couldn't wire.
        if (engine.commandRoot() != null && selfRegisterCommand) {
            commandRegistration = new CommandRegistration(
                    plugin, engine.commandRoot(), new UpdateSubcommand(this), engine.permission());
            scheduler.runGlobal(commandRegistration::register);
        }
    }

    /**
     * Stop checking, drop the listener and command, and abort any request in
     * flight. Work that was already running finishes quietly instead of
     * reaching back into a plugin whose jar the server has closed.
     */
    public void shutdown() {
        engine.stop();
        if (joinListener != null) {
            HandlerList.unregisterAll(joinListener);
            joinListener = null;
        }
        if (commandRegistration != null) {
            commandRegistration.unregister();
            commandRegistration = null;
        }
    }

    /** Manual check (e.g. from a command). Reports the outcome to {@code sender}. */
    public void checkNow(CommandSender sender) {
        engine.checkNow(sender);
    }

    /**
     * Download the pending update, verify it, back up the running jar and
     * stage the new one for install on the next restart. Reports progress to
     * {@code sender}. Requires a prior check to have found an update.
     */
    public void downloadAndStage(CommandSender sender) {
        engine.downloadAndStage(sender);
    }

    /** Whether the staged update can be applied right now without a restart. */
    boolean canHotReloadNow() {
        return engine.canHotReloadNow();
    }

    /**
     * Stage the most recent backup jar for install on the next restart,
     * the supported one-command recovery path after a bad update.
     */
    public void restoreBackup(CommandSender sender) {
        engine.restoreBackup(sender);
    }

    /**
     * Apply a staged update immediately via the configured {@link ReloadEngine},
     * with no restart. Refused when no engine is configured, nothing is staged,
     * or the engine's safety checks fail. On success the running plugin
     * instance (including this updater) is replaced.
     */
    public void applyNow(CommandSender sender) {
        engine.applyNow(sender);
    }

    /** Run an update subcommand; false when the sender lacks the permission. */
    boolean handle(CommandSender sender, String[] args) {
        return engine.handle(sender, args);
    }

    // ==== Accessors ====

    /** The update found by the most recent check, or null when up to date/ignored. */
    public UpdateInfo pendingUpdate() {
        return engine.pendingUpdate();
    }

    public UpdateCheckResult lastResult() {
        return engine.lastResult();
    }

    /**
     * A newer version that was found but is still inside its settle-in window,
     * or null. It is deliberately not offered as a {@link #pendingUpdate()}:
     * nothing is announced, downloaded or staged for it until the wait is over.
     */
    public UpdateInfo heldUpdate() {
        return engine.heldUpdate();
    }

    /**
     * Plain-English explanation of the settle-in wait for {@link #heldUpdate()},
     * or null when nothing is being held back.
     */
    public String holdNotice() {
        return engine.holdNotice();
    }

    public String currentVersion() {
        return engine.currentVersion();
    }

    /** One plain-English line on where things stand, for status commands. */
    public String statusLine() {
        return engine.statusLine();
    }

    public String permission() {
        return engine.permission();
    }

    public UpdateNotifier notifier() {
        return notifier;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    /** Suppress notifications for a version until a newer one is released. */
    public void ignoreVersion(String version) {
        engine.ignoreVersion(version);
    }

    public void unignoreVersion(String version) {
        engine.unignoreVersion(version);
    }

    /** The shared update logic, wired to Bukkit's scheduler, players and update folder. */
    private final class BukkitEngine extends UpdateEngine<CommandSender> {

        BukkitEngine(EngineSettings settings) {
            super(settings, plugin.getDataFolder().toPath(), plugin.getLogger());
        }

        @Override
        protected Logger logger() {
            return plugin.getLogger();
        }

        @Override
        protected boolean pluginEnabled() {
            return plugin.isEnabled();
        }

        @Override
        protected Path currentJar() {
            return PluginJarLocator.locate(plugin, jarFileSupplier);
        }

        @Override
        protected Path stagingFolder() {
            return Bukkit.getUpdateFolderFile().toPath();
        }

        @Override
        protected void runAsync(Runnable task) {
            scheduler.runAsync(task);
        }

        @Override
        protected Runnable runAsyncRepeating(Runnable task, Duration delay, Duration period) {
            SchedulerAdapter.TaskHandle handle =
                    scheduler.runAsyncRepeating(task, delay.toMillis() / 50L, period.toMillis() / 50L);
            return handle::cancel;
        }

        @Override
        protected void runGlobal(Runnable task) {
            scheduler.runGlobal(task);
        }

        @Override
        protected boolean hasPermission(CommandSender sender, String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        protected void sendPlain(CommandSender sender, String message) {
            sender.sendMessage(message);
        }

        @Override
        protected CommandSender console() {
            return Bukkit.getConsoleSender();
        }

        @Override
        protected void forEachAdmin(Consumer<CommandSender> action) {
            scheduler.runGlobal(() -> Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission(permission()))
                    .forEach(p -> scheduler.runAtEntity(p, () -> action.accept(p))));
        }

        @Override
        protected void noticeAvailable(CommandSender sender, UpdateInfo info) {
            notifier.notifySender(sender, currentVersion(), info);
        }

        @Override
        protected void noticeAvailableConsole(UpdateInfo info) {
            notifier.notifyConsole(plugin.getName(), currentVersion(), info);
        }

        @Override
        protected void noticeStaged(CommandSender sender, UpdateInfo info, boolean canReload) {
            notifier.notifyStaged(sender, info, canReload);
        }

        @Override
        protected void noticeStagedConsole(UpdateInfo info, boolean canReload) {
            notifier.notifyStagedConsole(plugin.getName(), info, canReload);
        }

        @Override
        protected String restartPhrase() {
            return "restart the server";
        }

        @Override
        protected String stagedNotAppliedHint() {
            return "Check the server's plugin update folder, or restore the previous jar with the update restore command.";
        }

        @Override
        protected boolean hasReloadEngine() {
            return reloadEngine != null;
        }

        @Override
        protected String reloadRefusal() {
            return reloadEngine == null ? super.reloadRefusal() : reloadEngine.refusalReason(plugin);
        }

        @Override
        protected void reload(Path stagedJar, Path backupJar) throws Exception {
            reloadEngine.reload(plugin, stagedJar, backupJar);
        }
    }

    // ==== Builder ====

    public static Builder builder(JavaPlugin plugin) {
        return new Builder(plugin);
    }

    public static final class Builder {
        private final JavaPlugin plugin;
        private String currentVersion;
        private final List<UpdateSource> sources = new ArrayList<>();
        private UpdateMode mode = UpdateMode.NOTIFY;
        private Duration checkInterval = Duration.ofHours(6);
        private Duration minimumReleaseAge = Duration.ZERO;
        private String permission;
        private String prefix;
        private String commandRoot;
        private boolean selfRegisterCommand = true;
        private String track;
        private String changelogUrl;
        private String userAgentContact;
        private SchedulerAdapter scheduler;
        private boolean requireHash = true;
        private int backupRetention = 3;
        private Supplier<File> jarFileSupplier;
        private ReloadEngine reloadEngine;
        private boolean matchServerVersion = true;
        private final Map<String, String> messageOverrides = new HashMap<>();

        @SuppressWarnings("deprecation") // getDescription() works on both Paper and Spigot;
        // getPluginMeta() is Paper-only and would NoSuchMethodError on Spigot.
        private Builder(JavaPlugin plugin) {
            this.plugin = plugin;
            this.currentVersion = plugin.getDescription().getVersion();
            this.permission = plugin.getName().toLowerCase(java.util.Locale.ROOT) + ".update";
            this.prefix = "<gold>[" + plugin.getName() + "]</gold>";
        }

        /** Primary source, tried first. */
        public Builder source(UpdateSource source) {
            sources.add(0, source);
            return this;
        }

        /** Fallback sources, tried in the order added when earlier ones fail. */
        public Builder fallbackSource(UpdateSource source) {
            sources.add(source);
            return this;
        }

        public Builder currentVersion(String version) {
            this.currentVersion = version;
            return this;
        }

        public Builder mode(UpdateMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder checkInterval(Duration interval) {
            if (interval.toMinutes() < 15) {
                throw new IllegalArgumentException("Check interval must be at least 15 minutes");
            }
            this.checkInterval = interval;
            return this;
        }

        /**
         * Ignore a release until it has been publicly available for this long
         * (default {@link Duration#ZERO}: act on updates as soon as they appear).
         *
         * <p>Guards against following a publisher's own mistakes: when a broken
         * release is pulled or hotfixed within hours, a server that waits never
         * installs it. The clock is the publisher's release timestamp where the
         * source provides one (Modrinth, GitHub, Hangar, Jenkins all do), else
         * the first time this server saw the version. It delays notices,
         * downloads and auto-staging alike; an admin running the download
         * command by hand still gets the update immediately.</p>
         */
        public Builder minimumReleaseAge(Duration age) {
            this.minimumReleaseAge = age == null ? Duration.ZERO : age;
            return this;
        }

        /** Permission required to see update notifications. Default: {@code <plugin>.update}. */
        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        /** MiniMessage prefix for all notices. Default: {@code <gold>[PluginName]</gold>}. */
        public Builder prefix(String miniMessagePrefix) {
            this.prefix = miniMessagePrefix;
            return this;
        }

        /** Root command (e.g. {@code "/tcp"}) that enables clickable action buttons. */
        public Builder commandRoot(String commandRoot) {
            this.commandRoot = commandRoot;
            return this;
        }

        /**
         * Whether to self-register {@code command-root} as a server command when
         * its name is free (default true). A plugin that declares its command in
         * {@code plugin.yml} keeps that command untouched (the name is taken, so
         * registration is skipped); injected or one-file adopters rely on this to
         * get a working {@code /<root> update} command. Set false to suppress it
         * entirely and wire update delegation through your own command.
         */
        public Builder selfRegisterCommand(boolean selfRegister) {
            this.selfRegisterCommand = selfRegister;
            return this;
        }

        /**
         * Prefer releases the source lists for the server's own Minecraft
         * version (default true). Modrinth and Hangar tag every release with the
         * game versions it supports, so a plugin that ships separate jars for
         * 1.21, 26.1 and 26.3 gets the right one without a track. Sources that
         * know nothing about game versions ignore this.
         */
        public Builder matchServerVersion(boolean match) {
            this.matchServerVersion = match;
            return this;
        }

        /** Distribution track suffix (e.g. {@code "mc26"}) for dual-track releases. */
        public Builder track(String track) {
            this.track = track;
            return this;
        }

        /** Optional plain-text/MiniMessage changelog URL that overrides the source changelog. */
        public Builder changelogUrl(String url) {
            this.changelogUrl = url;
            return this;
        }

        /**
         * Contact info embedded in the User-Agent. Modrinth requires a
         * uniquely identifying User-Agent; supply an email or project URL.
         */
        public Builder userAgentContact(String contact) {
            this.userAgentContact = contact;
            return this;
        }

        /** Supply the host plugin's own scheduler abstraction instead of auto-detection. */
        public Builder scheduler(SchedulerAdapter scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Whether staging refuses downloads that publish no checksum.
         * Default true; consider false for GitHub repos without digest support.
         */
        public Builder requireHash(boolean requireHash) {
            this.requireHash = requireHash;
            return this;
        }

        /** How many jar backups to keep (default 3, minimum 1). */
        public Builder backupRetention(int count) {
            this.backupRetention = count;
            return this;
        }

        /**
         * Reflection-free way to hand over the running jar (call the protected
         * {@code getFile()} from your plugin subclass). Recommended for
         * ProGuard-processed plugins where reflection may break.
         */
        public Builder jarFileSupplier(Supplier<File> supplier) {
            this.jarFileSupplier = supplier;
            return this;
        }

        /**
         * Enable no-restart updates by supplying a {@link ReloadEngine}
         * (from the separate {@code pluginpulse-hotreload} artifact).
         * Off by default; restart-install is the recommended path.
         */
        public Builder reloadEngine(ReloadEngine engine) {
            this.reloadEngine = engine;
            return this;
        }

        /** Override a notifier message template (see {@link UpdateNotifier} keys). */
        public Builder message(String key, String miniMessageTemplate) {
            this.messageOverrides.put(key, miniMessageTemplate);
            return this;
        }

        public Updater build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one update source is required");
            }
            return new Updater(this);
        }
    }
}
