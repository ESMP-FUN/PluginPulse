package io.github.darkstarworks.pluginpulse.version;

import org.bukkit.Bukkit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Minecraft version the server runs, e.g. {@code 1.21.11} or {@code 26.3}. */
public final class ServerVersion {

    private static final Pattern LEADING_VERSION = Pattern.compile("^\\d+(?:\\.\\d+)*");

    private ServerVersion() {
    }

    /** The running Minecraft version, or null when it can't be told. */
    public static String detect() {
        try {
            String v = fromBukkitVersion(Bukkit.getMinecraftVersion());
            if (v != null) return v;
        } catch (LinkageError | RuntimeException ignored) {
            // Spigot has no getMinecraftVersion()
        }
        try {
            return fromBukkitVersion(Bukkit.getBukkitVersion());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The leading dotted number of a server version string. Paper 1.21 reports
     * {@code 1.21.11-R0.1-SNAPSHOT} and Paper 26 reports {@code 26.3.build.35-alpha}.
     */
    public static String fromBukkitVersion(String raw) {
        if (raw == null) return null;
        Matcher m = LEADING_VERSION.matcher(raw.trim());
        return m.find() ? m.group() : null;
    }

    /** True for the Minecraft 26 and later numbering, false for 1.x. */
    public static boolean isModernNumbering(String version) {
        return version != null && !version.startsWith("1.");
    }
}
