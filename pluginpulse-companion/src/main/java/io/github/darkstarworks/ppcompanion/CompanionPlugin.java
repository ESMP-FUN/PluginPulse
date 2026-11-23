package io.github.darkstarworks.ppcompanion;

import io.github.darkstarworks.pluginpulse.UpdateInfo;
import io.github.darkstarworks.pluginpulse.UpdateMode;
import io.github.darkstarworks.pluginpulse.UpdateSubcommand;
import io.github.darkstarworks.pluginpulse.Updater;
import io.github.darkstarworks.pluginpulse.engine.SourceConfig;
import io.github.darkstarworks.pluginpulse.notify.UpdateNotifier;
import io.github.darkstarworks.pluginpulse.platform.SchedulerAdapter;
import io.github.darkstarworks.pluginpulse.source.UpdateSource;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A drop-in updater plugin for server owners. Reads {@code config.yml}, and for
 * each configured &amp; installed target plugin drives a {@link Updater} on its
 * behalf: no changes to the target plugin, no code.
 *
 * <p>Everything is done through the public builder API: the companion never
 * touches a target's internals, only its jar (via PluginPulse's normal
 * update-folder staging) and Bukkit's plugin manager.</p>
 */
public final class CompanionPlugin extends JavaPlugin {

    private static final List<String> ACTIONS =
            List.of("list", "check", "download", "apply", "ignore", "unignore", "restore", "reload");

    /** Immutable snapshots swapped on start/reload/stop so Folia and async reads never race. */
    private volatile Map<String, Updater> updaters = Map.of();
    private volatile Map<String, UpdateSubcommand> subcommands = Map.of();
    private volatile Map<String, String> skipped = Map.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info(ServerSupport.detect().startupLine());
        // Each target's updater registers tasks and a listener in the target's
        // name, which the server refuses until that target is enabled. Plugins
        // enable in an order we don't control, so wait for the first tick, by
        // which time every plugin has had its turn.
        SchedulerAdapter.create(this).runGlobal(this::startAll);
    }

    private synchronized void startAll() {
        String contact = contact(getConfig().getString("user-agent-contact", ""));
        long intervalHours = Math.max(1L, getConfig().getLong("check-interval-hours", 6L));
        // Server-wide settle-in wait; each entry may override both keys.
        boolean holdNew = getConfig().getBoolean("hold-new-updates", false);
        long holdHours = Math.max(1L, getConfig().getLong("hold-new-updates-hours", 18L));

        Map<String, Updater> newUpdaters = new LinkedHashMap<>();
        Map<String, UpdateSubcommand> newSubcommands = new LinkedHashMap<>();
        Map<String, String> newSkipped = new LinkedHashMap<>();

        ConfigurationSection plugins = getConfig().getConfigurationSection("plugins");
        if (plugins == null || plugins.getKeys(false).isEmpty()) {
            this.updaters = Map.of();
            this.subcommands = Map.of();
            this.skipped = Map.of();
            getLogger().info("No plugins configured yet: edit config.yml and run /pluginpulse reload.");
            return;
        }
        Defaults defaults = new Defaults(contact, intervalHours, holdNew, holdHours);
        for (String name : plugins.getKeys(false)) {
            ConfigurationSection entry = plugins.getConfigurationSection(name);
            if (entry == null) {
                newSkipped.put(name, "malformed config entry");
                continue;
            }
            startTarget(name, entry, defaults, newUpdaters, newSubcommands, newSkipped);
        }
        this.updaters = Collections.unmodifiableMap(newUpdaters);
        this.subcommands = Collections.unmodifiableMap(newSubcommands);
        this.skipped = Collections.unmodifiableMap(newSkipped);
        getLogger().info("Managing " + newUpdaters.size() + " plugin(s); "
                + newSkipped.size() + " skipped.");
    }

    @Override
    public void onDisable() {
        stopAll();
    }

    /** The top-level config values every entry starts from before its own overrides. */
    private record Defaults(String contact, long intervalHours, boolean holdNew, long holdHours) {
    }

    private void startTarget(String name, ConfigurationSection entry, Defaults defaults,
                             Map<String, Updater> newUpdaters,
                             Map<String, UpdateSubcommand> newSubcommands,
                             Map<String, String> newSkipped) {
        Plugin target = getServer().getPluginManager().getPlugin(name);
        if (target == null) {
            newSkipped.put(name, "not installed");
            return;
        }
        if (!target.isEnabled()) {
            newSkipped.put(name, "installed but not running (it failed to start or was turned off)");
            return;
        }
        if (!(target instanceof JavaPlugin jp)) {
            newSkipped.put(name, "not a JavaPlugin (cannot be updated)");
            return;
        }

        List<UpdateSource> sources = SourceConfig.sources(entry::get, SourceConfig.Platform.BUKKIT,
                getLogger(), name, "for this entry");
        if (sources.isEmpty()) {
            newSkipped.put(name, "no modrinth/github/hangar/builtbybit/jenkins source configured");
            return;
        }

        UpdateMode mode = parseMode(entry.getString("mode", "notify"));
        if (mode == null) {
            newSkipped.put(name, "mode: off");
            return;
        }

        // Wait out a brand-new release before acting on it, unless this entry says
        // otherwise. Both keys fall back to the top-level values.
        boolean holdNew = entry.getBoolean("hold-new-updates", defaults.holdNew());
        long holdHours = Math.max(1L, entry.getLong("hold-new-updates-hours", defaults.holdHours()));

        try {
            String safeName = name.replaceAll("[^A-Za-z0-9._+\\-]", "");
            Updater.Builder builder = Updater.builder(jp)
                    .mode(mode)
                    .checkInterval(Duration.ofHours(defaults.intervalHours()))
                    .minimumReleaseAge(holdNew ? Duration.ofHours(holdHours) : Duration.ZERO)
                    // The companion owns the /pluginpulse command; don't have each
                    // target self-register one of its own.
                    .selfRegisterCommand(false)
                    // Notifications and command use are gated by OUR permission so
                    // one grant covers every managed plugin.
                    .permission("pluginpulse.admin");
            if (!safeName.isEmpty()) {
                builder.message(UpdateNotifier.KEY_PLAYER,
                        "<prefix> <gray>You are using version <yellow><current></yellow>, "
                                + "latest version is <green><latest></green>."
                                + "<newline><click:open_url:'<page>'><hover:show_text:'<gray>Open the download page'>"
                                + "<aqua>[Download Latest Version]</aqua></hover></click>"
                                + " <click:run_command:'/pluginpulse download " + safeName + "'>"
                                + "<hover:show_text:'<gray>Download it now, in the background'>"
                                + "<green>[Download & Install]</green></hover></click>"
                                + " <click:run_command:'/pluginpulse ignore " + safeName + " <latest>'>"
                                + "<hover:show_text:'<gray>Hide notifications for this version'>"
                                + "<gray>[Ignore]</gray></hover></click>");
            }
            // First declared source primary, rest fallbacks.
            builder.source(sources.get(0));
            for (int i = 1; i < sources.size(); i++) builder.fallbackSource(sources.get(i));
            if (!defaults.contact().isEmpty()) builder.userAgentContact(defaults.contact());
            String track = trimToNull(entry.getString("track"));
            if (track != null) builder.track(track);
            builder.matchServerVersion(entry.getBoolean("match-server-version", true));
            if (entry.contains("require-hash")) {
                builder.requireHash(entry.getBoolean("require-hash", true));
            }
            // Opt-in no-restart installs for this target. The engine still refuses
            // at runtime when unsafe (Folia, dependents, a bundled native library).
            if (entry.getBoolean("hot-reload", false)) {
                io.github.darkstarworks.pluginpulse.ReloadEngine engine =
                        io.github.darkstarworks.pluginpulse.ReloadEngines.tryLoad();
                if (engine != null) builder.reloadEngine(engine);
            }

            Updater updater = builder.build();
            updater.start();
            newUpdaters.put(name, updater);
            newSubcommands.put(name, new UpdateSubcommand(updater));
        } catch (Exception e) {
            newSkipped.put(name, "failed to start: " + e.getMessage());
            getLogger().warning("Could not manage " + name + ": " + e.getMessage());
        }
    }

    private synchronized void stopAll() {
        Map<String, Updater> current = this.updaters;
        this.updaters = Map.of();
        this.subcommands = Map.of();
        this.skipped = Map.of();
        current.values().forEach(Updater::shutdown);
    }

    // ==== Command ====

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pluginpulse.admin")) {
            sender.sendMessage("You don't have permission to manage updates.");
            return true;
        }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list", "status" -> sendStatus(sender);
            case "reload" -> {
                synchronized (this) {
                    stopAll();
                    reloadConfig();
                    startAll();
                }
                sender.sendMessage("PluginPulse Companion reloaded: managing "
                        + updaters.size() + " plugin(s).");
            }
            case "check", "download", "install", "restore", "apply" -> dispatch(sender, action, args);
            case "ignore", "unignore" -> dispatchVersionAction(sender, action, label, args);
            default -> sender.sendMessage("Usage: /" + label
                    + " [list|check|download|apply|ignore|unignore|restore|reload] [plugin|all]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("pluginpulse.admin")) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            return filterPrefix(ACTIONS, prefix);
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        Map<String, Updater> live = this.updaters;
        if (args.length == 2) {
            return switch (action) {
                case "check", "download", "install", "apply", "restore" -> {
                    List<String> options = new ArrayList<>(live.keySet());
                    options.add("all");
                    yield filterPrefix(options, args[1]);
                }
                case "ignore", "unignore" -> filterPrefix(new ArrayList<>(live.keySet()), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && "ignore".equals(action)) {
            Updater target = resolveUpdater(live, args[1]);
            if (target != null) {
                UpdateInfo info = target.pendingUpdate() != null ? target.pendingUpdate() : target.heldUpdate();
                if (info != null) {
                    return filterPrefix(List.of(info.version()), args[2]);
                }
            }
        }
        return List.of();
    }

    /** Run an update action against one named target, or "all" (default). */
    private void dispatch(CommandSender sender, String action, String[] args) {
        Map<String, UpdateSubcommand> live = this.subcommands;
        String targetArg = args.length >= 2 ? args[1] : "all";
        if (live.isEmpty()) {
            sender.sendMessage("No plugins are currently being managed.");
            return;
        }
        if ("all".equalsIgnoreCase(targetArg)) {
            live.forEach((name, sub) -> {
                sender.sendMessage("[" + name + "]");
                sub.handle(sender, new String[]{action});
            });
            return;
        }
        UpdateSubcommand sub = resolve(live, targetArg);
        if (sub == null) {
            sender.sendMessage("Not a managed plugin: " + targetArg
                    + ". Managed: " + String.join(", ", live.keySet()));
            return;
        }
        sub.handle(sender, new String[]{action});
    }

    /** Run ignore/unignore against one named target and a version. */
    private void dispatchVersionAction(CommandSender sender, String action, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("Usage: /" + label + " " + action + " <plugin> <version>");
            return;
        }
        Map<String, UpdateSubcommand> live = this.subcommands;
        UpdateSubcommand sub = resolve(live, args[1]);
        if (sub == null) {
            sender.sendMessage("Not a managed plugin: " + args[1]
                    + (live.isEmpty() ? "." : ". Managed: " + String.join(", ", live.keySet())));
            return;
        }
        sub.handle(sender, new String[]{action, args[2]});
    }

    /** Case-insensitive lookup of a managed target's command delegate. */
    private static UpdateSubcommand resolve(Map<String, UpdateSubcommand> live, String name) {
        UpdateSubcommand exact = live.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, UpdateSubcommand> e : live.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static Updater resolveUpdater(Map<String, Updater> live, String name) {
        Updater exact = live.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, Updater> e : live.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private void sendStatus(CommandSender sender) {
        Map<String, Updater> live = this.updaters;
        Map<String, String> skippedNow = this.skipped;
        sender.sendMessage("PluginPulse Companion: managing " + live.size() + " plugin(s):");
        live.forEach((name, updater) -> {
            sender.sendMessage(" - " + name + ": " + updater.statusLine());
        });
        if (!skippedNow.isEmpty()) {
            sender.sendMessage("Skipped:");
            skippedNow.forEach((name, why) -> sender.sendMessage(" - " + name + ": " + why));
        }
    }

    // ==== Helpers ====

    /** @return the mode, or null when the entry is explicitly "off". */
    static UpdateMode parseMode(String name) {
        return SourceConfig.parseMode(name);
    }

    /** The contact to send, or empty while a shipped placeholder is still in place. */
    static String contact(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()
                || "you@example.com".equalsIgnoreCase(trimmed)
                || trimmed.startsWith("PASTE-")) {
            return "";
        }
        return trimmed;
    }

    static List<String> filterPrefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) return options;
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(opt);
            }
        }
        return matches;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
