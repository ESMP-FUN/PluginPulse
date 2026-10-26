package io.github.darkstarworks.ppcompanion.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.engine.ConfigView;
import io.github.darkstarworks.pluginpulse.engine.SourceConfig;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import io.github.darkstarworks.pluginpulse.velocity.JulBridge;
import io.github.darkstarworks.pluginpulse.velocity.VelocityUpdater;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The companion for Velocity proxies. Reads {@code config.yml} and drives a
 * {@link VelocityUpdater} for each configured and installed plugin, without
 * any change to those plugins.
 */
public final class CompanionVelocity {

    static final String PERMISSION = "pluginpulse.admin";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    /** id -> live updater for each managed, installed target. */
    private final Map<String, VelocityUpdater> updaters = new LinkedHashMap<>();
    /** name -> why a configured plugin isn't being managed. */
    private final Map<String, String> skipped = new LinkedHashMap<>();

    @Inject
    public CompanionVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        // Not /pluginpulse: the proxy would catch it before it reaches the
        // Paper companion on the servers behind it.
        CommandMeta meta = proxy.getCommandManager().metaBuilder("pulseproxy")
                .aliases("ppp").plugin(this).build();
        proxy.getCommandManager().register(meta, new PulseCommand());
        // Every plugin has loaded by the time the proxy initialises, so the
        // targets can be looked up straight away.
        startAll();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        stopAll(true);
    }

    private void startAll() {
        ConfigView config = loadConfig();
        String contact = contact(config);
        long intervalHours = Math.max(1L, config.number("check-interval-hours", 6L));
        boolean holdNew = config.bool("hold-new-updates", false);
        long holdHours = Math.max(1L, config.number("hold-new-updates-hours", 18L));

        Map<?, ?> plugins = config.get("plugins") instanceof Map<?, ?> m ? m : Map.of();
        if (plugins.isEmpty()) {
            logger.info("No plugins configured yet: edit config.yml and run /pulseproxy reload.");
            return;
        }
        for (Map.Entry<?, ?> e : plugins.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> entry)) {
                skipped.put(name, "this entry is not set up right in config.yml");
                continue;
            }
            startTarget(name, ConfigView.of(entry), new Defaults(contact, intervalHours, holdNew, holdHours));
        }
        logger.info("Managing {} plugin(s); {} skipped.", updaters.size(), skipped.size());
    }

    /** The top-level config values every entry starts from before its own overrides. */
    private record Defaults(String contact, long intervalHours, boolean holdNew, long holdHours) {
    }

    private void startTarget(String name, ConfigView entry, Defaults defaults) {
        PluginContainer target = findPlugin(name).orElse(null);
        if (target == null) {
            skipped.put(name, "not installed (use the id or name shown by /velocity plugins)");
            return;
        }
        String id = target.getDescription().getId();
        if (target.getDescription().getSource().isEmpty()) {
            skipped.put(name, "built into the proxy, so it can't be updated");
            return;
        }

        List<UpdateSource> sources = SourceConfig.sources(entry, SourceConfig.Platform.VELOCITY,
                JulBridge.wrap(logger), name, "for this entry");
        if (sources.isEmpty()) {
            skipped.put(name, "no modrinth/github/hangar/jenkins source configured");
            return;
        }
        UpdateMode mode = SourceConfig.parseMode(entry.string("mode", "notify"));
        if (mode == null) {
            skipped.put(name, "mode: off");
            return;
        }
        boolean holdNew = entry.bool("hold-new-updates", defaults.holdNew());
        long holdHours = Math.max(1L, entry.number("hold-new-updates-hours", defaults.holdHours()));

        try {
            VelocityUpdater.Builder builder = VelocityUpdater.builder(proxy, this, target)
                    .logger(logger)
                    .mode(mode)
                    .checkInterval(Duration.ofHours(defaults.intervalHours()))
                    .minimumReleaseAge(holdNew ? Duration.ofHours(holdHours) : Duration.ZERO)
                    // The companion owns the /pulseproxy command, and one
                    // permission covers every managed plugin.
                    .selfRegisterCommand(false)
                    .permission(PERMISSION)
                    .prefix("<gold>[" + name + "]</gold>");
            builder.source(sources.get(0));
            for (int i = 1; i < sources.size(); i++) builder.fallbackSource(sources.get(i));
            if (!defaults.contact().isEmpty()) builder.userAgentContact(defaults.contact());
            String track = entry.string("track");
            if (track != null) builder.track(track);
            if (entry.has("require-hash")) builder.requireHash(entry.bool("require-hash", true));
            if (entry.bool("hot-reload", false)) {
                logger.info("{}: Velocity can't swap a plugin while it runs, so its updates are installed "
                        + "when the proxy shuts down.", name);
            }

            VelocityUpdater updater = builder.build();
            updater.start();
            updaters.put(id, updater);
        } catch (Exception e) {
            skipped.put(name, "failed to start: " + e.getMessage());
            logger.warn("Could not manage {}: {}", name, e.getMessage());
        }
    }

    /** By id first (what Velocity uses), then by display name, ignoring case. */
    private Optional<PluginContainer> findPlugin(String key) {
        Optional<PluginContainer> byId = proxy.getPluginManager().getPlugin(key.toLowerCase(Locale.ROOT));
        if (byId.isPresent()) return byId;
        return proxy.getPluginManager().getPlugins().stream()
                .filter(p -> p.getDescription().getName().map(key::equalsIgnoreCase).orElse(false))
                .findFirst();
    }

    /** @param applyStaged true while the proxy stops, so downloaded updates go in place */
    private void stopAll(boolean applyStaged) {
        updaters.values().forEach(u -> u.shutdown(applyStaged));
        updaters.clear();
        skipped.clear();
    }

    // ==== Config ====

    private ConfigView loadConfig() {
        Path file = dataDirectory.resolve("config.yml");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Object parsed = new Yaml().load(reader);
                return ConfigView.of(parsed instanceof Map<?, ?> m ? m : Map.of());
            }
        } catch (Exception e) {
            logger.warn("Could not read config.yml, so no plugins are managed: {}", e.getMessage());
            return ConfigView.of(Map.of());
        }
    }

    // ==== Command ====

    private final class PulseCommand implements SimpleCommand {

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission(PERMISSION);
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource sender = invocation.source();
            String[] args = invocation.arguments();
            String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "list", "status" -> sendStatus(sender);
                case "reload" -> {
                    stopAll(false);
                    startAll();
                    say(sender, "PluginPulse Companion reloaded: managing " + updaters.size() + " plugin(s).");
                }
                case "check", "download", "install", "restore", "apply" -> dispatch(sender, action, args);
                default -> say(sender, "Usage: /" + invocation.alias()
                        + " [list|check|download|restore|reload] [plugin|all]");
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length <= 1) {
                return List.of("list", "check", "download", "restore", "reload");
            }
            if (args.length == 2) {
                List<String> names = new ArrayList<>(updaters.keySet());
                names.add("all");
                return names;
            }
            return List.of();
        }
    }

    /** Run an update action against one named target, or "all" (default). */
    private void dispatch(CommandSource sender, String action, String[] args) {
        String targetArg = args.length >= 2 ? args[1] : "all";
        if (updaters.isEmpty()) {
            say(sender, "No plugins are currently being managed.");
            return;
        }
        if ("all".equalsIgnoreCase(targetArg)) {
            updaters.forEach((id, updater) -> {
                say(sender, "[" + id + "]");
                updater.handle(sender, new String[]{action});
            });
            return;
        }
        VelocityUpdater updater = resolve(targetArg);
        if (updater == null) {
            say(sender, "Not a managed plugin: " + targetArg + ". Managed: " + String.join(", ", updaters.keySet()));
            return;
        }
        updater.handle(sender, new String[]{action});
    }

    private VelocityUpdater resolve(String name) {
        VelocityUpdater exact = updaters.get(name.toLowerCase(Locale.ROOT));
        if (exact != null) return exact;
        return findPlugin(name).map(p -> updaters.get(p.getDescription().getId())).orElse(null);
    }

    private void sendStatus(CommandSource sender) {
        say(sender, "PluginPulse Companion: managing " + updaters.size() + " plugin(s):");
        updaters.forEach((id, updater) -> say(sender, " - " + id + ": " + updater.statusLine()));
        if (!skipped.isEmpty()) {
            say(sender, "Skipped:");
            skipped.forEach((name, why) -> say(sender, " - " + name + ": " + why));
        }
    }

    private static void say(CommandSource sender, String message) {
        sender.sendMessage(Component.text(message));
    }

    /** The contact to send, or empty while the shipped placeholder is still in place. */
    static String contact(ConfigView config) {
        String contact = config.string("user-agent-contact", "");
        return contact.startsWith("PASTE-") ? "" : contact;
    }

    static Map<?, ?> parse(String yaml) {
        Object parsed = new Yaml().load(yaml);
        return parsed instanceof Map<?, ?> m ? m : Map.of();
    }
}
