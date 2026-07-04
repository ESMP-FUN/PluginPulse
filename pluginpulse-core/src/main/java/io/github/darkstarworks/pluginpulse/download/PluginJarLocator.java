package io.github.darkstarworks.pluginpulse.download;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.function.Supplier;

/**
 * Finds the jar file the plugin is currently running from. The staged update
 * must be written under this exact filename or the server's update-folder
 * mechanism will ignore it.
 *
 * <p>Resolution order: host-supplied supplier (reflection-free, survives
 * ProGuard), then the protected {@code JavaPlugin#getFile()} via reflection,
 * then the class's {@link CodeSource} location.</p>
 */
public final class PluginJarLocator {

    private PluginJarLocator() {
    }

    public static Path locate(JavaPlugin plugin, Supplier<File> override) {
        if (override != null) {
            File file = override.get();
            if (file != null) return file.toPath();
        }
        try {
            Method getFile = JavaPlugin.class.getDeclaredMethod("getFile");
            getFile.setAccessible(true);
            return ((File) getFile.invoke(plugin)).toPath();
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // fall through
        }
        try {
            CodeSource source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                URI uri = source.getLocation().toURI();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    return Path.of(uri);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        throw new IllegalStateException(
                "Cannot locate the running plugin jar; supply it via Updater.Builder#jarFileSupplier");
    }
}
