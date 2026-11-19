package io.github.darkstarworks.ppcompanion;

import io.github.darkstarworks.pluginpulse.version.ServerVersion;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;

/**
 * Which server internals this server has, for the startup log. One jar runs on every
 * supported line, and the library finds these by name at runtime; this only says
 * up front which layout it will meet, so an owner can tell what to expect.
 */
enum ServerSupport {
    FOLIA("Folia", false),
    PAPER("Paper 1.20.5 and newer", true),
    SPIGOT("Spigot", true),
    UNKNOWN("an unknown server type", false);

    private final String label;
    private final boolean hotReload;

    ServerSupport(String label, boolean hotReload) {
        this.label = label;
        this.hotReload = hotReload;
    }

    static ServerSupport detect() {
        try {
            if (classExists("io.papermc.paper.threadedregions.RegionizedServer")) return FOLIA;
            Object manager = Bukkit.getPluginManager();
            if (findField(manager.getClass(), "paperPluginManager") != null) return PAPER;
            if (findField(manager.getClass(), "plugins") != null
                    && findField(manager.getClass(), "lookupNames") != null) return SPIGOT;
        } catch (RuntimeException | LinkageError ignored) {
            // fall through: tell the owner rather than fail the start
        }
        return UNKNOWN;
    }

    /** The startup line, e.g. "Running on Minecraft 26.3, using the support for Paper 1.20.5 and newer." */
    String startupLine() {
        String version = ServerVersion.detect();
        String line = "Running on Minecraft " + (version == null ? "(version unknown)" : version)
                + ", using the support for " + label + ".";
        if (hotReload) {
            return line + " Plugins set to hot-reload: true can be updated without a restart where it is safe.";
        }
        if (this == FOLIA) {
            return line + " Updates are installed on the next restart (Folia can't swap a running plugin).";
        }
        return line + " Updates are installed on the next restart.";
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        return null;
    }
}
