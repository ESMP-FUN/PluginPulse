package io.github.darkstarworks.pluginpulse.inject;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a plugin jar and reports what the injector needs to decide a strategy:
 * the Bukkit descriptor (Paper preferred over legacy) and the Velocity one,
 * the main class's access flags, whether it's already been injected, and
 * whether the jar is signed. A jar may carry both descriptors.
 */
final class JarInspector {

    /**
     * Result of inspecting a jar without modifying it.
     *
     * @param descriptor the Bukkit descriptor, or null for a Velocity-only jar
     * @param velocity   the Velocity descriptor, or null
     * @param strategy   how the Bukkit side is wired, or {@link Strategy#VELOCITY}
     *                   when there is no Bukkit side
     */
    record Inspection(Descriptor descriptor, Descriptor velocity, boolean finalMain, boolean alreadyInjected,
                      boolean signed, Strategy strategy) {

        /** The descriptor whose package the library is relocated under. */
        Descriptor primary() {
            return descriptor != null ? descriptor : velocity;
        }
    }

    private JarInspector() {
    }

    static Inspection inspect(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Descriptor descriptor = readDescriptor(zip);
            Descriptor velocity = parse(zip, Descriptor.Kind.VELOCITY);
            if (descriptor == null && velocity == null) {
                throw new IOException("No plugin.yml, paper-plugin.yml or velocity-plugin.json with a main class "
                        + "found in " + jar.getFileName());
            }

            boolean alreadyInjected = zip.stream()
                    .anyMatch(e -> e.getName().endsWith("/pluginpulse/PluginPulse.class")
                            || e.getName().endsWith("/pluginpulse/velocity/VelocityPulse.class")
                            || e.getName().equals("io/github/darkstarworks/pluginpulse/PluginPulse.class"));
            boolean signed = zip.stream().anyMatch(e -> {
                String n = e.getName().toUpperCase();
                return n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".EC"));
            });

            if (descriptor == null) {
                return new Inspection(null, velocity, false, alreadyInjected, signed, Strategy.VELOCITY);
            }
            byte[] mainBytes = readClass(zip, descriptor.main());
            // If we can't read the main class bytes (missing, or an unsupported
            // version we still can't structurally parse) assume non-final and
            // use WRAPPER: the produced wrapper links to the main by name at
            // runtime regardless of its class version.
            boolean finalMain = mainBytes != null && ClassAccess.isFinal(mainBytes);
            Strategy strategy = finalMain ? Strategy.INSTRUMENT : Strategy.WRAPPER;

            return new Inspection(descriptor, velocity, finalMain, alreadyInjected, signed, strategy);
        }
    }

    /** Raw bytes of the named class ({@code a.b.Main}), or null if absent. */
    static byte[] readClass(ZipFile zip, String fqn) throws IOException {
        String entryName = fqn.replace('.', '/') + ".class";
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) return null;
        try (var in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** Prefer paper-plugin.yml (Paper's newer format) over legacy plugin.yml. */
    private static Descriptor readDescriptor(ZipFile zip) throws IOException {
        Descriptor paper = parse(zip, Descriptor.Kind.PAPER);
        if (paper != null) return paper;
        return parse(zip, Descriptor.Kind.LEGACY);
    }

    private static Descriptor parse(ZipFile zip, Descriptor.Kind kind) throws IOException {
        ZipEntry entry = zip.getEntry(kind.entry);
        if (entry == null) return null;
        String text;
        try (var in = zip.getInputStream(entry)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Object> map = kind == Descriptor.Kind.VELOCITY ? parseJson(text) : new Yaml().load(text);
        if (map == null) return null;
        Object main = map.get("main");
        if (main == null) return null;
        String name = String.valueOf(map.getOrDefault("name", map.getOrDefault("id", "unknown")));
        String version = String.valueOf(map.getOrDefault("version", "unknown"));
        return new Descriptor(kind, kind.entry, name, String.valueOf(main), version, text);
    }

    /**
     * velocity-plugin.json is flat JSON. SnakeYAML reads most of it as YAML,
     * but not tab-indented files, so the string fields are picked out by hand then.
     */
    private static Map<String, Object> parseJson(String text) {
        try {
            Object parsed = new Yaml().load(text);
            if (parsed instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            }
        } catch (RuntimeException ignored) {
            // fall through to the plain scan
        }
        Map<String, Object> map = new java.util.HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(id|name|version|main)\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        while (m.find()) map.putIfAbsent(m.group(1), m.group(2));
        return map;
    }
}
