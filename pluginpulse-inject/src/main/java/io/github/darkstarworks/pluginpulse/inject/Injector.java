package io.github.darkstarworks.pluginpulse.inject;

import me.lucko.jarrelocator.JarRelocator;
import me.lucko.jarrelocator.Relocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Wires PluginPulse into a compiled plugin jar without its source:
 * relocate the library under the target's own package, add a wrapper subclass
 * (or instrument a final main in place) and re-point {@code main:}, add the
 * Velocity hook when the jar is a Velocity plugin, and drop in a
 * {@code pluginpulse.yml}. The reference engine the in-browser JS is golden-tested against.
 */
public final class Injector {

    private static final Pattern MAIN_LINE = Pattern.compile("(?m)^(\\s*main:\\s*).*$");
    private static final String CORE_PACKAGE = "io.github.darkstarworks.pluginpulse";
    private static final String BUKKIT_PAYLOAD = "/payload/pluginpulse-core.jar.payload";
    private static final String VELOCITY_PAYLOAD = "/payload/pluginpulse-velocity.jar.payload";

    /** What an injection did: surfaced by the CLI. */
    public record Result(Strategy strategy, String main, String wrapperOrMain,
                         String relocatedPackage, boolean strippedSignature, String velocityMain) {
    }

    public Injector() {
    }

    public JarInspector.Inspection inspect(Path input) throws IOException {
        return JarInspector.inspect(input);
    }

    public Result inject(Path input, Path output, InjectOptions options) throws IOException {
        if (!options.hasSource()) {
            throw new IllegalArgumentException("At least one of --modrinth/--github/--hangar is required.");
        }
        JarInspector.Inspection insp = JarInspector.inspect(input);
        if (insp.alreadyInjected() && !options.upgrade()) {
            throw new IllegalStateException("Jar already contains PluginPulse; pass --upgrade to re-inject.");
        }
        Descriptor primary = insp.primary();
        String mainInternal = primary.main().replace('.', '/');
        int slash = mainInternal.lastIndexOf('/');
        String pkgInternal = slash >= 0 ? mainInternal.substring(0, slash) : "";
        String relocatedBaseInternal = pkgInternal.isEmpty() ? "pluginpulse" : pkgInternal + "/pluginpulse";
        String relocatedPackage = relocatedBaseInternal.replace('/', '.');

        List<Path> payloads = new ArrayList<>();
        try {
            if (insp.descriptor() != null) payloads.add(relocatePayload(BUKKIT_PAYLOAD, relocatedPackage));
            if (insp.velocity() != null) payloads.add(relocatePayload(VELOCITY_PAYLOAD, relocatedPackage));

            // Entries written in place of the input's own.
            Map<String, byte[]> replaced = new LinkedHashMap<>();
            String wrapperOrMain = primary.main();
            if (insp.descriptor() != null) {
                wrapperOrMain = wireBukkit(input, insp, relocatedBaseInternal, replaced);
            }
            Descriptor v = insp.velocity();
            if (v != null) {
                String entry = v.main().replace('.', '/') + ".class";
                byte[] bytes = replaced.get(entry);
                if (bytes == null) {
                    try (ZipFile zip = new ZipFile(input.toFile())) {
                        bytes = JarInspector.readClass(zip, v.main());
                    }
                }
                if (bytes == null) {
                    throw new IOException("Velocity main class " + v.main() + " is missing from the jar.");
                }
                try {
                    replaced.put(entry, VelocityHook.addHook(bytes, relocatedBaseInternal));
                } catch (RuntimeException e) {
                    throw new IOException("Cannot add the Velocity hook to " + v.main()
                            + " (likely an unsupported class-file version). " + e.getMessage(), e);
                }
            }
            replaced.put("pluginpulse.yml", PulseYaml.build(options).getBytes(StandardCharsets.UTF_8));

            Set<String> written = new HashSet<>();
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
                copyInput(input, out, replaced.keySet(), insp.signed(), written);
                for (Path payload : payloads) addCoreClasses(payload, out, written);
                for (Map.Entry<String, byte[]> e : replaced.entrySet()) put(out, e.getKey(), e.getValue(), written);
            }
            return new Result(insp.strategy(), primary.main(), wrapperOrMain, relocatedPackage, insp.signed(),
                    v != null ? v.main() : null);
        } finally {
            for (Path payload : payloads) Files.deleteIfExists(payload);
        }
    }

    // ==== Strategies ====

    /** Add the Bukkit wiring to {@code replaced}; returns the class the descriptor now points at. */
    private String wireBukkit(Path input, JarInspector.Inspection insp, String relocatedBaseInternal,
                              Map<String, byte[]> replaced) throws IOException {
        Descriptor d = insp.descriptor();
        if (insp.strategy() == Strategy.WRAPPER) {
            String wrapperFqn = WrapperGenerator.wrapperName(d.main());
            replaced.put(wrapperFqn.replace('.', '/') + ".class",
                    WrapperGenerator.generate(d.main(), relocatedBaseInternal));
            replaced.put(d.entry(), rewriteMain(d.rawText(), wrapperFqn).getBytes(StandardCharsets.UTF_8));
            return wrapperFqn;
        }
        String mainEntry = d.main().replace('.', '/') + ".class";
        byte[] mainBytes;
        try (ZipFile zip = new ZipFile(input.toFile())) {
            mainBytes = JarInspector.readClass(zip, d.main());
        }
        if (mainBytes == null) {
            throw new IOException("Final main class " + d.main() + " could not be read for instrumentation.");
        }
        try {
            // main: is unchanged for INSTRUMENT: the descriptor is copied verbatim.
            replaced.put(mainEntry, Instrumenter.instrument(mainBytes, relocatedBaseInternal));
        } catch (RuntimeException e) {
            throw new IOException("Cannot instrument final main " + d.main()
                    + " (likely an unsupported class-file version); no wrapper fallback is possible "
                    + "for a final class. " + e.getMessage(), e);
        }
        return d.main();
    }

    // ==== Zip plumbing ====

    /** Copy every input entry except {@code skip}; when signed, drop signature files and trim the manifest. */
    private void copyInput(Path input, ZipOutputStream out, Set<String> skip, boolean signed, Set<String> written)
            throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(input))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || skip.contains(name) || written.contains(name)) continue;
                if (signed && isSignatureFile(name)) continue;
                byte[] data = in.readAllBytes();
                if (signed && name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    data = mainManifestSection(data);
                }
                writeEntry(out, name, data);
                written.add(name);
            }
        }
    }

    /** Add relocated core classes/resources, skipping META-INF and module-info. */
    private void addCoreClasses(Path relocatedCore, ZipOutputStream out, Set<String> written) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(relocatedCore))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.startsWith("META-INF/") || name.equals("module-info.class")) continue;
                byte[] data = in.readAllBytes();
                put(out, name, data, written);
            }
        }
    }

    private void put(ZipOutputStream out, String name, byte[] data, Set<String> written) throws IOException {
        if (!written.add(name)) return;
        writeEntry(out, name, data);
    }

    private void writeEntry(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    // ==== Relocation ====

    private Path relocatePayload(String resource, String relocatedPackage) throws IOException {
        Path payload = extractPayload(resource);
        Path relocated = Files.createTempFile("pp-core-reloc", ".jar");
        try {
            List<Relocation> rules = List.of(new Relocation(CORE_PACKAGE, relocatedPackage));
            new JarRelocator(payload.toFile(), relocated.toFile(), rules).run();
        } finally {
            Files.deleteIfExists(payload);
        }
        return relocated;
    }

    /** A bundled library jar, extracted to a temp file for jar-relocator. */
    private Path extractPayload(String resource) throws IOException {
        Path tmp = Files.createTempFile("pp-core", ".jar");
        try (InputStream in = Injector.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Bundled payload " + resource + " missing from the tool jar.");
            }
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp;
    }

    // ==== Text helpers ====

    static String rewriteMain(String descriptorText, String newMain) {
        Function<MatchResult, String> repl = mr -> Matcher.quoteReplacement(mr.group(1) + newMain);
        return MAIN_LINE.matcher(descriptorText).replaceFirst(repl);
    }

    private static boolean isSignatureFile(String name) {
        String u = name.toUpperCase(java.util.Locale.ROOT);
        return u.startsWith("META-INF/") && (u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".EC") || u.endsWith(".DSA"));
    }

    /** Keep only the main manifest section (up to the first blank line) so signed-jar digests don't linger. */
    private static byte[] mainManifestSection(byte[] manifest) {
        String text = new String(manifest, StandardCharsets.UTF_8);
        int blank = text.indexOf("\r\n\r\n");
        if (blank < 0) blank = text.indexOf("\n\n");
        String head = blank < 0 ? text : text.substring(0, blank) + "\n";
        return head.getBytes(StandardCharsets.UTF_8);
    }
}
