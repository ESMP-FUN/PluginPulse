package io.github.darkstarworks.pluginpulse.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Keeps the updater in a plugin that the browser tool or the injector wired up.
 * The update it downloads is the author's own jar, which has no PluginPulse in
 * it, so this repeats the wiring on that jar before it is staged: the relocated
 * library, {@code pluginpulse.yml}, the {@code __Pulse} wrapper and the
 * Velocity hook are copied over from the running jar.
 *
 * <p>Only jars wired the way the injectors do it are touched (library at
 * {@code <main package>/pluginpulse}); a plugin whose author bundles PluginPulse,
 * and any download that already contains it, is left as it is.</p>
 */
public final class CarryOver {

    private static final String WRAPPER_SUFFIX = "__Pulse";
    private static final Pattern MAIN_LINE = Pattern.compile("(?m)^(\\s*main:\\s*)(.*)$");
    private static final Pattern JSON_MAIN = Pattern.compile("\"main\"\\s*:\\s*\"([^\"]*)\"");

    private CarryOver() {
    }

    /**
     * Rewire {@code downloaded} in place when {@code currentJar} was wired by an injector.
     *
     * @return true when the jar was changed
     */
    public static boolean apply(Path downloaded, Path currentJar, Logger logger) throws IOException {
        Map<String, byte[]> current = readAll(currentJar);
        Wiring wiring = Wiring.of(current);
        if (wiring == null) {
            if (instrumentedInPlace(current)) {
                logger.warning("This plugin was changed with the command-line injector, which can't be repeated "
                        + "on the new version, so its update checks stop after this update. Run the new jar "
                        + "through the PluginPulse web tool to keep them.");
            }
            return false;
        }

        Map<String, byte[]> fresh = readAll(downloaded);
        if (fresh.keySet().stream().anyMatch(n -> n.endsWith("/PluginPulse.class") || n.endsWith("/VelocityPulse.class"))) {
            return false; // the new release ships PluginPulse itself
        }

        Map<String, byte[]> replaced = new LinkedHashMap<>();
        if (wiring.bukkitMain != null) {
            String entry = bukkitDescriptor(fresh);
            String text = entry != null ? new String(fresh.get(entry), StandardCharsets.UTF_8) : null;
            Matcher m = text != null ? MAIN_LINE.matcher(text) : null;
            String main = m != null && m.find() ? unquote(m.group(2)) : null;
            if (!wiring.bukkitMain.equals(main)) {
                logger.warning("The new version moved its main class, so its update checks stop here. "
                        + "Run the new jar through the PluginPulse web tool again to keep them.");
                return false;
            }
            String mainInternal = main.replace('.', '/');
            replaced.put(entry, MAIN_LINE.matcher(text)
                    .replaceFirst(Matcher.quoteReplacement(m.group(1) + main + WRAPPER_SUFFIX))
                    .getBytes(StandardCharsets.UTF_8));
            byte[] mainBytes = fresh.get(mainInternal + ".class");
            if (mainBytes != null) {
                replaced.put(mainInternal + ".class",
                        ClassFiles.definalize(mainBytes, Set.of("onEnable", "onDisable")));
            }
            String wrapper = mainInternal + WRAPPER_SUFFIX + ".class";
            replaced.put(wrapper, current.get(wrapper));
        }
        if (wiring.velocityMain != null) {
            byte[] json = fresh.get("velocity-plugin.json");
            Matcher m = json != null ? JSON_MAIN.matcher(new String(json, StandardCharsets.UTF_8)) : null;
            if (m == null || !m.find() || !wiring.velocityMain.equals(m.group(1))) {
                logger.warning("The new version moved its main class, so its update checks stop here. "
                        + "Run the new jar through the PluginPulse web tool again to keep them.");
                return false;
            }
            String entry = wiring.velocityMain.replace('.', '/') + ".class";
            byte[] mainBytes = replaced.containsKey(entry) ? replaced.get(entry) : fresh.get(entry);
            if (mainBytes == null) return false;
            replaced.put(entry, ClassFiles.addVelocityHook(mainBytes, wiring.libraryInternal));
        }
        replaced.put("pluginpulse.yml", current.get("pluginpulse.yml"));
        current.forEach((name, data) -> {
            if (name.startsWith(wiring.libraryInternal + "/")) replaced.put(name, data);
        });

        Path out = Files.createTempFile(downloaded.toAbsolutePath().getParent(), "carry", ".part");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                for (Map.Entry<String, byte[]> e : fresh.entrySet()) {
                    if (replaced.containsKey(e.getKey()) || isSignature(e.getKey())) continue;
                    write(zip, e.getKey(), e.getValue());
                }
                for (Map.Entry<String, byte[]> e : replaced.entrySet()) {
                    if (e.getValue() != null) write(zip, e.getKey(), e.getValue());
                }
            }
            Files.move(out, downloaded, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(out);
        }
        logger.info("Kept PluginPulse in the new version, so it keeps checking for updates.");
        return true;
    }

    /** How the running jar was wired, or null when no injector wired it. */
    private record Wiring(String bukkitMain, String velocityMain, String libraryInternal) {

        static Wiring of(Map<String, byte[]> jar) {
            if (!jar.containsKey("pluginpulse.yml")) return null;
            String bukkitMain = null;
            String velocityMain = null;
            String library = null;

            String entry = bukkitDescriptor(jar);
            if (entry != null) {
                Matcher m = MAIN_LINE.matcher(new String(jar.get(entry), StandardCharsets.UTF_8));
                String main = m.find() ? unquote(m.group(2)) : "";
                if (main.endsWith(WRAPPER_SUFFIX)) {
                    String original = main.substring(0, main.length() - WRAPPER_SUFFIX.length());
                    String lib = libraryFor(original);
                    if (jar.containsKey(lib + "/PluginPulse.class")
                            && jar.containsKey(main.replace('.', '/') + ".class")) {
                        bukkitMain = original;
                        library = lib;
                    }
                }
            }
            byte[] json = jar.get("velocity-plugin.json");
            if (json != null) {
                Matcher m = JSON_MAIN.matcher(new String(json, StandardCharsets.UTF_8));
                if (m.find()) {
                    String main = m.group(1);
                    String lib = libraryFor(main);
                    byte[] bytes = jar.get(main.replace('.', '/') + ".class");
                    if (bytes != null && jar.containsKey(lib + "/velocity/VelocityPulse.class")
                            && ClassFiles.hasUtf8(bytes, ClassFiles.VELOCITY_FIELD)
                            && (library == null || library.equals(lib))) {
                        velocityMain = main;
                        library = lib;
                    }
                }
            }
            return library == null ? null : new Wiring(bukkitMain, velocityMain, library);
        }
    }

    /** A final main the CLI rewrote in place: the library sits where the injector puts it, with no wrapper. */
    private static boolean instrumentedInPlace(Map<String, byte[]> jar) {
        String entry = bukkitDescriptor(jar);
        if (entry == null || !jar.containsKey("pluginpulse.yml")) return false;
        Matcher m = MAIN_LINE.matcher(new String(jar.get(entry), StandardCharsets.UTF_8));
        if (!m.find()) return false;
        String main = unquote(m.group(2));
        byte[] bytes = jar.get(main.replace('.', '/') + ".class");
        String lib = libraryFor(main);
        return bytes != null && jar.containsKey(lib + "/PluginPulse.class")
                && ClassFiles.hasUtf8(bytes, lib + "/PluginPulse");
    }

    /** Where the injectors relocate the library for a main class. */
    private static String libraryFor(String mainFqn) {
        String internal = mainFqn.replace('.', '/');
        int slash = internal.lastIndexOf('/');
        return slash >= 0 ? internal.substring(0, slash) + "/pluginpulse" : "pluginpulse";
    }

    /** paper-plugin.yml first, as the server reads it first. */
    private static String bukkitDescriptor(Map<String, byte[]> jar) {
        for (String name : new String[]{"paper-plugin.yml", "plugin.yml"}) {
            if (jar.containsKey(name)) return name;
        }
        return null;
    }

    private static String unquote(String s) {
        return s.trim().replaceAll("^['\"]|['\"]$", "");
    }

    private static boolean isSignature(String name) {
        String u = name.toUpperCase(Locale.ROOT);
        return u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".EC") || u.endsWith(".DSA"));
    }

    private static Map<String, byte[]> readAll(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream raw = Files.newInputStream(jar); ZipInputStream in = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isDirectory()) entries.putIfAbsent(e.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static void write(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
