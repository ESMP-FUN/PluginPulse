package io.github.darkstarworks.pluginpulse.hotreload;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fully unloads a plugin: disable, unregister every registration Bukkit knows
 * about, remove the plugin manager bookkeeping, and close the classloader so
 * the jar file lock is released (mandatory on Windows before the jar can be
 * replaced).
 */
final class PluginUnloader {

    private PluginUnloader() {
    }

    static void unload(Plugin plugin, Logger logger) throws Exception {
        String name = plugin.getName();

        Bukkit.getPluginManager().disablePlugin(plugin);
        // disablePlugin already cancels tasks/unregisters listeners & services,
        // but plugins that misbehave in onDisable can leave stragglers — sweep again.
        HandlerList.unregisterAll(plugin);
        Bukkit.getScheduler().cancelTasks(plugin);
        Bukkit.getServicesManager().unregisterAll(plugin);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);

        removeCommands(plugin);
        PluginManagerAccess.removeFromManager(Bukkit.getPluginManager(), plugin);

        // Closing the PluginClassLoader releases the jar's file handle.
        // Classes already loaded keep working (the current call stack included);
        // classes not yet loaded from this jar will fail from here on.
        ClassLoader loader = plugin.getClass().getClassLoader();
        if (loader instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not close classloader of " + name
                        + " — the old jar may stay locked until restart", e);
            }
        }
        logger.info("Unloaded plugin " + name + ".");
    }

    /** Remove the plugin's commands (and their aliases) from the command map. */
    private static void removeCommands(Plugin plugin) {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
        while (it.hasNext()) {
            Command command = it.next().getValue();
            if (command instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == plugin) {
                command.unregister(Bukkit.getCommandMap());
                it.remove();
            }
        }
    }
}
