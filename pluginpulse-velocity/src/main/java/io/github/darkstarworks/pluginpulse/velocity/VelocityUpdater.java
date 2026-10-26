package io.github.darkstarworks.pluginpulse.velocity;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.darkstarworks.pluginpulse.UpdateCheckResult;
import io.github.darkstarworks.pluginpulse.UpdateInfo;
import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.engine.EngineSettings;
import io.github.darkstarworks.pluginpulse.engine.UpdateEngine;
import io.github.darkstarworks.pluginpulse.notify.NoticeTemplates;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Entry point on Velocity. Build once in your {@code ProxyInitializeEvent}
 * handler and call {@link #start()}:
 *
 * <pre>{@code
 * VelocityUpdater updater = VelocityUpdater.builder(proxy, this)
 *     .source(new ModrinthSource("myplugin", List.of("velocity"), List.of()))
 *     .permission("myplugin.admin")
 *     .userAgentContact("me@example.com")
 *     .build();
 * updater.start();
 * }</pre>
 *
 * <p>Velocity can't swap a plugin while it runs and has no update folder, so a
 * downloaded update waits in the plugin's data folder and is put in place when
 * the proxy shuts down. The updater listens for the shutdown itself.</p>
 */
public final class VelocityUpdater {

    private final ProxyServer proxy;
    private final Object owner;
    private final Path jar;
    private final Path dataDirectory;
    private final Logger logger;
    private final boolean notifyOnJoin;
    private final boolean selfRegisterCommand;
    private final NoticeTemplates templates;
    private final VelocityEngine engine;
    private EventHandler<ServerPostConnectEvent> joinHandler;
    private EventHandler<ProxyShutdownEvent> shutdownHandler;
    private CommandMeta commandMeta;
    private volatile boolean shutDown;

    private VelocityUpdater(Builder b) {
        this.proxy = b.proxy;
        this.owner = b.owner;
        this.jar = b.jar;
        this.dataDirectory = b.dataDirectory;
        this.logger = JulBridge.wrap(b.logger);
        this.notifyOnJoin = b.mode.atLeast(UpdateMode.NOTIFY);
        this.selfRegisterCommand = b.selfRegisterCommand;
        this.templates = new NoticeTemplates(b.prefix, b.commandRoot,
                Map.of(NoticeTemplates.KEY_STAGED_RESTART, "<prefix> <green>Update <latest> downloaded.</green> "
                        + "<gray>It is put in place when the proxy shuts down, so restart the proxy to apply it.</gray>"),
                b.messageOverrides);
        EngineSettings settings = new EngineSettings(b.name, b.currentVersion, b.sources, b.mode,
                b.checkInterval, b.minimumReleaseAge, b.permission, b.commandRoot, b.track, b.changelogUrl,
                b.userAgentContact, b.requireHash, b.backupRetention, null);
        this.engine = new VelocityEngine(settings);
    }

    // ==== Lifecycle ====

    /** Begin periodic checks, show notices on join and listen for the proxy shutting down. */
    public void start() {
        engine.start();
        shutdownHandler = event -> shutdown();
        proxy.getEventManager().register(owner, ProxyShutdownEvent.class, shutdownHandler);
        if (notifyOnJoin) {
            // On first connect rather than at login: a message sent during login
            // arrives before the client can show chat.
            joinHandler = event -> {
                if (event.getPreviousServer() != null) return;
                UpdateInfo pending = engine.pendingUpdate();
                Player player = event.getPlayer();
                if (pending != null && player.hasPermission(engine.permission())) {
                    sendNotice(player, templates.playerNotice(engine.currentVersion(), pending));
                }
            };
            proxy.getEventManager().register(owner, ServerPostConnectEvent.class, joinHandler);
        }
        if (engine.commandRoot() != null && selfRegisterCommand) {
            registerCommand();
        }
    }

    /**
     * Stop checking and put a downloaded update in place. Velocity only turns
     * plugins off when the proxy stops, so that is when this runs; calling it
     * again does nothing.
     */
    public void shutdown() {
        shutdown(true);
    }

    /**
     * Stop checking. With {@code applyStaged} false a downloaded update stays
     * where it is and is put in place at the next shutdown (used when the
     * companion reloads its config while the proxy keeps running).
     */
    public void shutdown(boolean applyStaged) {
        if (shutDown) return;
        shutDown = true;
        engine.stop();
        if (joinHandler != null) {
            proxy.getEventManager().unregister(owner, joinHandler);
            joinHandler = null;
        }
        if (shutdownHandler != null && !applyStaged) {
            proxy.getEventManager().unregister(owner, shutdownHandler);
        }
        shutdownHandler = null;
        if (commandMeta != null) {
            proxy.getCommandManager().unregister(commandMeta);
            commandMeta = null;
        }
        if (applyStaged) applyStaged();
    }

    /** Move the staged jar over the live one. Runs while the proxy is stopping. */
    private void applyStaged() {
        Path staged = engine.stagedJar();
        if (!Files.exists(staged)) return;
        try {
            try {
                Files.move(staged, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(staged, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Installed the downloaded update of " + engine.pluginName()
                    + ". It runs from the next start of the proxy.");
        } catch (IOException e) {
            logger.warning("Could not put the downloaded update of " + engine.pluginName() + " in place: "
                    + e.getMessage() + ". With the proxy stopped, move " + staged + " over " + jar
                    + " by hand to install it.");
        }
    }

    private void registerCommand() {
        String label = normalize(engine.commandRoot());
        if (label.isEmpty()) return;
        if (proxy.getCommandManager().hasCommand(label)) {
            // Name already owned (e.g. the host registers its own command).
            // Leave it alone: the host wires update delegation itself.
            return;
        }
        CommandMeta meta = proxy.getCommandManager().metaBuilder(label).plugin(owner).build();
        proxy.getCommandManager().register(meta, new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                if (!handle(invocation.source(), tail(invocation.arguments()))) {
                    invocation.source().sendMessage(Component.text(
                            "You don't have permission to manage " + engine.pluginName() + " updates."));
                }
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                return tabComplete(tail(invocation.arguments()));
            }
        });
        commandMeta = meta;
    }

    /** Strip a leading slash and any arguments, lower-case the bare command name. */
    static String normalize(String commandRoot) {
        String s = commandRoot.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        if (space >= 0) s = s.substring(0, space);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** Accept both the button form {@code /<root> update check} and the bare {@code /<root> check}. */
    static String[] tail(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("update")) {
            return Arrays.copyOfRange(args, 1, args.length);
        }
        return args;
    }

    private void sendNotice(CommandSource target, String miniMessage) {
        target.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    // ==== Actions ====

    /**
     * Run the arguments after {@code update}: check (default), download, apply,
     * ignore, unignore, restore, status.
     *
     * @return false when the sender lacks the update permission
     */
    public boolean handle(CommandSource sender, String[] args) {
        return engine.handle(sender, args);
    }

    /** Suggestions for the first argument after {@code update}. */
    public List<String> tabComplete(String[] args) {
        return args.length <= 1 ? UpdateEngine.ACTIONS : List.of();
    }

    public void checkNow(CommandSource sender) {
        engine.checkNow(sender);
    }

    public void downloadAndStage(CommandSource sender) {
        engine.downloadAndStage(sender);
    }

    public void restoreBackup(CommandSource sender) {
        engine.restoreBackup(sender);
    }

    // ==== Accessors ====

    public UpdateInfo pendingUpdate() {
        return engine.pendingUpdate();
    }

    public UpdateInfo heldUpdate() {
        return engine.heldUpdate();
    }

    public UpdateCheckResult lastResult() {
        return engine.lastResult();
    }

    public String holdNotice() {
        return engine.holdNotice();
    }

    public String currentVersion() {
        return engine.currentVersion();
    }

    public String statusLine() {
        return engine.statusLine();
    }

    public String permission() {
        return engine.permission();
    }

    public void ignoreVersion(String version) {
        engine.ignoreVersion(version);
    }

    public void unignoreVersion(String version) {
        engine.unignoreVersion(version);
    }

    /** The shared update logic, wired to the proxy. */
    private final class VelocityEngine extends UpdateEngine<CommandSource> {

        VelocityEngine(EngineSettings settings) {
            super(settings, dataDirectory, logger);
        }

        @Override
        protected Logger logger() {
            return logger;
        }

        @Override
        protected boolean pluginEnabled() {
            return !shutDown;
        }

        @Override
        protected Path currentJar() {
            return jar;
        }

        @Override
        protected Path stagingFolder() {
            return dataDirectory.resolve("pluginpulse").resolve("update");
        }

        @Override
        protected void runAsync(Runnable task) {
            proxy.getScheduler().buildTask(owner, task).schedule();
        }

        @Override
        protected Runnable runAsyncRepeating(Runnable task, Duration delay, Duration period) {
            ScheduledTask handle = proxy.getScheduler().buildTask(owner, task)
                    .delay(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .repeat(period.toMillis(), TimeUnit.MILLISECONDS)
                    .schedule();
            return handle::cancel;
        }

        @Override
        protected void runGlobal(Runnable task) {
            // The proxy has no main thread; messaging is safe from any thread.
            task.run();
        }

        @Override
        protected boolean hasPermission(CommandSource sender, String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        protected void sendPlain(CommandSource sender, String message) {
            sender.sendMessage(Component.text(message));
        }

        @Override
        protected CommandSource console() {
            return proxy.getConsoleCommandSource();
        }

        @Override
        protected void forEachAdmin(Consumer<CommandSource> action) {
            for (Player player : proxy.getAllPlayers()) {
                if (player.hasPermission(permission())) action.accept(player);
            }
        }

        @Override
        protected void noticeAvailable(CommandSource sender, UpdateInfo info) {
            sendNotice(sender, templates.playerNotice(currentVersion(), info));
        }

        @Override
        protected void noticeAvailableConsole(UpdateInfo info) {
            CommandSource console = console();
            sendNotice(console, templates.render(NoticeTemplates.KEY_CONSOLE, currentVersion(), info));
            for (String line : info.changelog().split("\n")) {
                if (!line.isBlank()) sendNotice(console, line);
            }
        }

        @Override
        protected void noticeStaged(CommandSource sender, UpdateInfo info, boolean canReload) {
            sendNotice(sender, templates.render(templates.stagedKey(canReload), "", info));
        }

        @Override
        protected void noticeStagedConsole(UpdateInfo info, boolean canReload) {
            noticeStaged(console(), info, canReload);
        }

        @Override
        protected String restartPhrase() {
            return "restart the proxy";
        }

        @Override
        protected String stagedNotAppliedHint() {
            return "The proxy puts a downloaded update in place when it shuts down. If the proxy was killed"
                    + " instead, the next normal shutdown does it. If it keeps failing, stop the proxy and move "
                    + stagedJar() + " over " + jar + " by hand.";
        }
    }

    // ==== Builder ====

    /** An updater for {@code plugin}, the instance of your {@code @Plugin} class. */
    public static Builder builder(ProxyServer proxy, Object plugin) {
        PluginContainer container = proxy.getPluginManager().fromInstance(plugin)
                .orElseThrow(() -> new IllegalArgumentException(plugin + " is not a Velocity plugin instance"));
        return new Builder(proxy, plugin, container);
    }

    /**
     * An updater for another installed plugin, {@code target}, with its tasks,
     * listeners and command owned by {@code owner}. This is how the companion
     * plugin manages plugins it doesn't contain.
     */
    public static Builder builder(ProxyServer proxy, Object owner, PluginContainer target) {
        return new Builder(proxy, owner, target);
    }

    public static final class Builder {
        private final ProxyServer proxy;
        private final Object owner;
        private final String id;
        private final String name;
        private String currentVersion;
        private Path jar;
        private Path dataDirectory;
        private org.slf4j.Logger logger;
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
        private boolean requireHash = true;
        private int backupRetention = 3;
        private final Map<String, String> messageOverrides = new HashMap<>();

        private Builder(ProxyServer proxy, Object owner, PluginContainer target) {
            this.proxy = proxy;
            this.owner = owner;
            PluginDescription d = target.getDescription();
            this.id = d.getId();
            this.name = d.getName().orElse(id);
            this.currentVersion = d.getVersion().orElse("0");
            this.jar = d.getSource().orElse(null);
            if (jar == null) jar = codeSource(target.getInstance().<Object>map(o -> o).orElse(owner));
            this.logger = org.slf4j.LoggerFactory.getLogger(name);
            this.permission = id + ".update";
            this.prefix = "<gold>[" + name + "]</gold>";
        }

        private static Path codeSource(Object instance) {
            try {
                CodeSource source = instance.getClass().getProtectionDomain().getCodeSource();
                if (source != null && source.getLocation() != null) {
                    URI uri = source.getLocation().toURI();
                    if ("file".equalsIgnoreCase(uri.getScheme())) return Path.of(uri);
                }
            } catch (Exception ignored) {
                // fall through
            }
            return null;
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

        /** Ignore a release until it has been publicly available for this long (default zero). */
        public Builder minimumReleaseAge(Duration age) {
            this.minimumReleaseAge = age == null ? Duration.ZERO : age;
            return this;
        }

        /** Permission required to see update notices and use the command. Default: {@code <plugin id>.update}. */
        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        /** MiniMessage prefix for all notices. Default: {@code <gold>[PluginName]</gold>}. */
        public Builder prefix(String miniMessagePrefix) {
            this.prefix = miniMessagePrefix;
            return this;
        }

        /** Root command (e.g. {@code "/myplugin"}) that enables clickable action buttons. */
        public Builder commandRoot(String commandRoot) {
            this.commandRoot = commandRoot;
            return this;
        }

        /** Whether to register {@code command-root} as a proxy command when its name is free (default true). */
        public Builder selfRegisterCommand(boolean selfRegister) {
            this.selfRegisterCommand = selfRegister;
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

        /** Contact info embedded in the User-Agent; Modrinth requires one. */
        public Builder userAgentContact(String contact) {
            this.userAgentContact = contact;
            return this;
        }

        /** Whether staging refuses downloads that publish no checksum (default true). */
        public Builder requireHash(boolean requireHash) {
            this.requireHash = requireHash;
            return this;
        }

        /** How many jar backups to keep (default 3, minimum 1). */
        public Builder backupRetention(int count) {
            this.backupRetention = count;
            return this;
        }

        /** The jar the plugin runs from, when Velocity can't tell. */
        public Builder jarFile(Path jar) {
            this.jar = jar;
            return this;
        }

        /** The plugin's data folder (your {@code @DataDirectory}). Default: {@code plugins/<plugin id>}. */
        public Builder dataDirectory(Path dataDirectory) {
            this.dataDirectory = dataDirectory;
            return this;
        }

        /** The plugin's injected logger. Default: one named after the plugin. */
        public Builder logger(org.slf4j.Logger logger) {
            this.logger = logger;
            return this;
        }

        /** Override a notice template, see {@link NoticeTemplates} for the keys. */
        public Builder message(String key, String miniMessageTemplate) {
            this.messageOverrides.put(key, miniMessageTemplate);
            return this;
        }

        public VelocityUpdater build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one update source is required");
            }
            if (jar == null) {
                throw new IllegalStateException("Cannot locate the plugin's jar; supply it via jarFile(...)");
            }
            // Velocity's own convention for @DataDirectory: plugins/<id>.
            if (dataDirectory == null) dataDirectory = jar.toAbsolutePath().getParent().resolve(id);
            return new VelocityUpdater(this);
        }
    }
}
