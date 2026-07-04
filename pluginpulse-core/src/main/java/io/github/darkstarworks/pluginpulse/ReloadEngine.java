package io.github.darkstarworks.pluginpulse;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/**
 * Seam for the opt-in hot-reload implementation ({@code pluginpulse-hotreload}
 * artifact). Core never depends on it; hosts that want no-restart updates pass
 * an instance via {@link Updater.Builder#reloadEngine(ReloadEngine)}.
 *
 * <p>Implementations swap the plugin's jar and reload it in-place. This is
 * inherently risky (classloader leaks, static state, cross-plugin references)
 * — restart-install remains the recommended default.</p>
 */
public interface ReloadEngine {

    /**
     * Unload {@code plugin}, replace its jar with {@code newJar}, and load +
     * enable the replacement. Must be called on the main thread.
     *
     * @param plugin    the currently running plugin instance
     * @param newJar    the verified replacement jar (staged by the download
     *                  pipeline)
     * @param backupJar backup of the current jar for rollback if the new
     *                  version fails to load; may be null
     * @throws Exception when the reload could not be completed; the engine
     *                   attempts rollback to {@code backupJar} first
     */
    void reload(JavaPlugin plugin, Path newJar, Path backupJar) throws Exception;

    /**
     * Cheap pre-check: null when a reload is currently possible, otherwise a
     * human-readable reason it would be refused (Folia, dependent plugins, ...).
     */
    String refusalReason(JavaPlugin plugin);
}
