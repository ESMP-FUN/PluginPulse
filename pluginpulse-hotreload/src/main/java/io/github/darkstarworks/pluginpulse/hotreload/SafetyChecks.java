package io.github.darkstarworks.pluginpulse.hotreload;

import io.github.darkstarworks.pluginpulse.download.PluginJarLocator;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;

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
        // A native library can only be loaded into ONE classloader per JVM. Swapping
        // the plugin's classloader would make the new instance fail to re-load it
        // (UnsatisfiedLinkError: "Native Library already loaded in another
        // classloader"), so a bundled native lib forces a restart-install. This is
        // what protects, e.g., a plugin that ships the SQLite JDBC native driver.
        String nativeLib = bundledNativeLibrary(target);
        if (nativeLib != null) {
            return "the plugin bundles a native library (" + nativeLib + ") that cannot be reloaded "
                    + "into a new classloader; restart the server to apply the update.";
        }
        return null;
    }

    /**
     * @return the file name of a bundled native library (.so/.dll/.dylib/.jnilib)
     *         in the target's jar, or null when none is present or the jar can't
     *         be read. Conservative: any bundled native lib blocks hot reload.
     */
    static String bundledNativeLibrary(Plugin target) {
        if (!(target instanceof JavaPlugin jp)) return null;
        try {
            Path jar = PluginJarLocator.locate(jp, null);
            try (JarFile jf = new JarFile(jar.toFile())) {
                return jf.stream()
                        .map(e -> e.getName().toLowerCase(Locale.ROOT))
                        .filter(n -> n.endsWith(".so") || n.endsWith(".dll")
                                || n.endsWith(".dylib") || n.endsWith(".jnilib"))
                        .map(n -> n.substring(n.lastIndexOf('/') + 1))
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            return null; // can't inspect → don't block on this check alone
        }
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
