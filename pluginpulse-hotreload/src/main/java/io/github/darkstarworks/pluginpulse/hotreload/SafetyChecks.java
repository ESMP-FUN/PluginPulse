package io.github.darkstarworks.pluginpulse.hotreload;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-flight gates for a hot reload. Each returns a human-readable refusal
 * reason, or null when the reload may proceed.
 */
final class SafetyChecks {

    private SafetyChecks() {
    }

    static String refusalReason(Plugin target) {
        if (isFolia()) {
            return "hot reload is disabled on Folia (regionized schedulers cannot be safely torn down).";
        }
        List<String> dependents = enabledDependents(target);
        if (!dependents.isEmpty()) {
            return "other enabled plugins depend on " + target.getName() + ": "
                    + String.join(", ", dependents) + ".";
        }
        if (!(target.getClass().getClassLoader() instanceof java.io.Closeable)) {
            return "the plugin's classloader is not closable on this server implementation.";
        }
        return null;
    }

    /** Enabled plugins declaring a hard or soft dependency on {@code target}. */
    static List<String> enabledDependents(Plugin target) {
        List<String> dependents = new ArrayList<>();
        String name = target.getName();
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other == target || !other.isEnabled()) continue;
            if (other.getDescription().getDepend().contains(name)
                    || other.getDescription().getSoftDepend().contains(name)) {
                dependents.add(other.getName());
            }
        }
        return dependents;
    }

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
