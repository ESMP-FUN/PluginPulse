package io.github.darkstarworks.pluginpulse.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.engine.ConfigView;
import io.github.darkstarworks.pluginpulse.engine.SourceConfig;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import net.kyori.adventure.text.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-call entry point on Velocity. Put a {@code pluginpulse.yml} in your
 * plugin's resources (the same file as on Paper), then:
 *
 * <pre>{@code
 * @Subscribe
 * public void onInit(ProxyInitializeEvent e) { VelocityPulse.bootstrap(proxy, this); }
 * }</pre>
 *
 * <p>Server owners can override {@code mode}, {@code check-interval-hours},
 * {@code hold-new-updates} and {@code hold-new-updates-hours} in an
 * {@code update:} section of {@code config.yml} in the plugin's data folder.
 * Every method fails soft and never throws into the host plugin.</p>
 */
public final class VelocityPulse {

    private static final Map<Object, VelocityUpdater> UPDATERS = new ConcurrentHashMap<>();

    private VelocityPulse() {
    }

    /** @return the started updater, or {@code null} if disabled or misconfigured */
    public static VelocityUpdater bootstrap(ProxyServer proxy, Object plugin) {
        return bootstrap(proxy, plugin, null);
    }

    /**
     * @param dataDirectory your {@code @DataDirectory}, or null for {@code plugins/<plugin id>}
     * @return the started updater, or {@code null} if disabled or misconfigured
     */
    public static VelocityUpdater bootstrap(ProxyServer proxy, Object plugin, Path dataDirectory) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(plugin.getClass());
        try {
            PluginContainer container = proxy.getPluginManager().fromInstance(plugin).orElse(null);
            if (container == null) {
                log.warn("PluginPulse: {} is not a Velocity plugin instance, updater disabled.", plugin);
                return null;
            }
            String name = container.getDescription().getName().orElse(container.getDescription().getId());
            log = org.slf4j.LoggerFactory.getLogger(name);
            Map<?, ?> raw;
            try (InputStream in = plugin.getClass().getResourceAsStream("/pluginpulse.yml")) {
                if (in == null) {
                    log.warn("PluginPulse: no pluginpulse.yml found in the jar, updater disabled.");
                    return null;
                }
                raw = load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            ConfigView cfg = ConfigView.of(raw);

            List<UpdateSource> sources = SourceConfig.sources(cfg, SourceConfig.Platform.VELOCITY,
                    JulBridge.wrap(log), "PluginPulse", "in pluginpulse.yml");
            if (sources.isEmpty()) {
                log.warn("PluginPulse: pluginpulse.yml has no modrinth/github/hangar/jenkins source, updater disabled.");
                return null;
            }

            VelocityUpdater.Builder builder = VelocityUpdater.builder(proxy, plugin).logger(log);
            if (dataDirectory != null) builder.dataDirectory(dataDirectory);

            String modeName = cfg.string("mode", "notify");
            long intervalHours = cfg.number("check-interval-hours", 6L);
            boolean holdNew = cfg.bool("hold-new-updates", false);
            long holdHours = cfg.number("hold-new-updates-hours", 18L);
            Path dir = dataDirectory;
            if (dir == null) {
                dir = container.getDescription().getSource()
                        .map(jar -> jar.toAbsolutePath().getParent().resolve(container.getDescription().getId()))
                        .orElse(null);
            }
            ConfigView update = dir != null ? hostOverrides(dir.resolve("config.yml")) : null;
            if (update != null) {
                modeName = update.string("mode", modeName);
                intervalHours = update.number("check-interval-hours", intervalHours);
                holdNew = update.bool("hold-new-updates", holdNew);
                holdHours = update.number("hold-new-updates-hours", holdHours);
            }
            UpdateMode mode = SourceConfig.parseMode(modeName);
            if (mode == null) return null;

            builder.mode(mode)
                    .checkInterval(Duration.ofHours(Math.max(1L, intervalHours)))
                    .minimumReleaseAge(holdNew ? Duration.ofHours(Math.max(1L, holdHours)) : Duration.ZERO);
            builder.source(sources.get(0));
            for (int i = 1; i < sources.size(); i++) builder.fallbackSource(sources.get(i));

            String permission = cfg.string("permission");
            if (permission != null) builder.permission(permission);
            String commandRoot = cfg.string("command-root");
            if (commandRoot != null) builder.commandRoot(commandRoot);
            builder.selfRegisterCommand(cfg.bool("self-register-command", true));
            String contact = cfg.string("user-agent-contact");
            if (contact != null) builder.userAgentContact(contact);
            String prefix = cfg.string("prefix");
            if (prefix != null) builder.prefix(prefix);
            // "track" is left out on purpose: it follows the backend's Minecraft
            // version, and one proxy serves every version.
            if (cfg.has("require-hash")) builder.requireHash(cfg.bool("require-hash", true));
            if (cfg.bool("hot-reload", false)) {
                log.info("PluginPulse: Velocity can't swap a plugin while it runs, so updates are installed "
                        + "when the proxy shuts down instead of with hot-reload.");
            }

            VelocityUpdater updater = builder.build();
            updater.start();
            UPDATERS.put(plugin, updater);
            return updater;
        } catch (Throwable t) {
            log.warn("PluginPulse: the updater could not start, so update checks are off.", t);
            return null;
        }
    }

    /** The updater started for this plugin, or {@code null}. */
    public static VelocityUpdater get(Object plugin) {
        return UPDATERS.get(plugin);
    }

    /**
     * Delegate a {@code /<plugin> update ...} subcommand to the bundled handler.
     *
     * @return {@code false} if no updater is active or the sender lacks permission
     */
    public static boolean handleUpdateCommand(Object plugin, CommandSource sender, String[] argsAfterUpdate) {
        VelocityUpdater updater = UPDATERS.get(plugin);
        if (updater == null) {
            sender.sendMessage(Component.text("The updater is not active."));
            return false;
        }
        return updater.handle(sender, argsAfterUpdate);
    }

    /** Stop this plugin's updater and install a downloaded update. Safe if bootstrap returned null. */
    public static void shutdown(Object plugin) {
        VelocityUpdater updater = UPDATERS.remove(plugin);
        if (updater != null) updater.shutdown();
    }

    static Map<?, ?> load(String yaml) {
        Object parsed = new Yaml().load(yaml);
        return parsed instanceof Map<?, ?> m ? m : Map.of();
    }

    /** The {@code update:} section of the host's config.yml, or null. */
    private static ConfigView hostOverrides(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object parsed = new Yaml().load(reader);
            return parsed instanceof Map<?, ?> m ? ConfigView.of(m).section("update") : null;
        } catch (Exception e) {
            return null;
        }
    }
}
