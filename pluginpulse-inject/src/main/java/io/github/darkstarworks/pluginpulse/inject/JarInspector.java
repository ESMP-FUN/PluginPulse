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
 * the descriptor (Paper preferred over legacy), the main class's access flags,
 * whether it's already been injected, and whether the jar is signed.
 */
final class JarInspector {

    /** Result of inspecting a jar without modifying it. */
    record Inspection(Descriptor descriptor, boolean finalMain, boolean alreadyInjected,
                      boolean signed, Strategy strategy) {
    }

    private JarInspector() {
    }

    static Inspection inspect(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Descriptor descriptor = readDescriptor(zip);
            if (descriptor == null) {
                throw new IOException("No plugin.yml or paper-plugin.yml with a 'main:' entry found in " + jar.getFileName());
            }

            boolean alreadyInjected = zip.stream()
                    .anyMatch(e -> e.getName().endsWith("/pluginpulse/PluginPulse.class")
                            || e.getName().equals("io/github/darkstarworks/pluginpulse/PluginPulse.class"));
            boolean signed = zip.stream().anyMatch(e -> {
                String n = e.getName().toUpperCase();
                return n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".EC"));
            });

            byte[] mainBytes = readClass(zip, descriptor.main());
            // If we can't read the main class bytes (missing, or an unsupported
            // version we still can't structurally parse) assume non-final and
            // use WRAPPER — the produced wrapper links to the main by name at
            // runtime regardless of its class version.
            boolean finalMain = mainBytes != null && ClassAccess.isFinal(mainBytes);
            Strategy strategy = finalMain ? Strategy.INSTRUMENT : Strategy.WRAPPER;

            return new Inspection(descriptor, finalMain, alreadyInjected, signed, strategy);
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
        Map<String, Object> map = new Yaml().load(text);
        if (map == null) return null;
        Object main = map.get("main");
        if (main == null) return null;
        String name = String.valueOf(map.getOrDefault("name", "unknown"));
        String version = String.valueOf(map.getOrDefault("version", "unknown"));
        return new Descriptor(kind, kind.entry, name, String.valueOf(main), version, text);
    }
}
