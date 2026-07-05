package io.github.darkstarworks.pluginpulse.inject;

import me.lucko.jarrelocator.JarRelocator;
import me.lucko.jarrelocator.Relocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
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
 * (or instrument a final main in place), re-point {@code main:}, and drop in a
 * {@code pluginpulse.yml}. The reference engine the in-browser JS is golden-tested against.
 */
public final class Injector {

    private static final Pattern MAIN_LINE = Pattern.compile("(?m)^(\\s*main:\\s*).*$");
    private static final String CORE_PACKAGE = "io.github.darkstarworks.pluginpulse";

    /** What an injection did — surfaced by the CLI. */
    public record Result(Strategy strategy, String main, String wrapperOrMain,
                         String relocatedPackage, boolean strippedSignature) {
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
        Descriptor descriptor = insp.descriptor();
        String main = descriptor.main();
        String mainInternal = main.replace('.', '/');
        int slash = mainInternal.lastIndexOf('/');
        String pkgInternal = slash >= 0 ? mainInternal.substring(0, slash) : "";
        String relocatedBaseInternal = pkgInternal.isEmpty() ? "pluginpulse" : pkgInternal + "/pluginpulse";
        String relocatedPackage = relocatedBaseInternal.replace('/', '.');

        Path relocatedCore = relocateCore(relocatedPackage);
        try {
            return switch (insp.strategy()) {
                case WRAPPER -> injectWrapper(input, output, insp, relocatedCore,
                        relocatedBaseInternal, relocatedPackage, options);
                case INSTRUMENT -> injectInstrument(input, output, insp, relocatedCore,
                        relocatedBaseInternal, relocatedPackage, options);
            };
        } finally {
            Files.deleteIfExists(relocatedCore);
        }
    }

    // ==== Strategies ====

    private Result injectWrapper(Path input, Path output, JarInspector.Inspection insp, Path relocatedCore,
                                 String relocatedBaseInternal, String relocatedPackage, InjectOptions o)
            throws IOException {
        Descriptor d = insp.descriptor();
        String wrapperFqn = WrapperGenerator.wrapperName(d.main());
        byte[] wrapperBytes = WrapperGenerator.generate(d.main(), relocatedBaseInternal);
        String newDescriptor = rewriteMain(d.rawText(), wrapperFqn);

        Set<String> skip = new HashSet<>();
        skip.add(d.entry());
        skip.add("pluginpulse.yml");

        Set<String> written = new HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            copyInput(input, out, skip, insp.signed(), written);
            addCoreClasses(relocatedCore, out, written);
            put(out, wrapperFqn.replace('.', '/') + ".class", wrapperBytes, written);
            put(out, d.entry(), newDescriptor.getBytes(StandardCharsets.UTF_8), written);
            put(out, "pluginpulse.yml", PulseYaml.build(o).getBytes(StandardCharsets.UTF_8), written);
        }
        return new Result(Strategy.WRAPPER, d.main(), wrapperFqn, relocatedPackage, insp.signed());
    }

    private Result injectInstrument(Path input, Path output, JarInspector.Inspection insp, Path relocatedCore,
                                    String relocatedBaseInternal, String relocatedPackage, InjectOptions o)
            throws IOException {
        Descriptor d = insp.descriptor();
        String mainEntry = d.main().replace('.', '/') + ".class";
        byte[] mainBytes;
        try (ZipFile zip = new ZipFile(input.toFile())) {
            mainBytes = JarInspector.readClass(zip, d.main());
        }
        if (mainBytes == null) {
            throw new IOException("Final main class " + d.main() + " could not be read for instrumentation.");
        }
        byte[] instrumented;
        try {
            instrumented = Instrumenter.instrument(mainBytes, relocatedBaseInternal);
        } catch (RuntimeException e) {
            throw new IOException("Cannot instrument final main " + d.main()
                    + " (likely an unsupported class-file version); no wrapper fallback is possible "
                    + "for a final class. " + e.getMessage(), e);
        }

        Set<String> skip = new HashSet<>();
        skip.add(mainEntry);
        skip.add("pluginpulse.yml");

        Set<String> written = new HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            copyInput(input, out, skip, insp.signed(), written);
            addCoreClasses(relocatedCore, out, written);
            put(out, mainEntry, instrumented, written);
            put(out, "pluginpulse.yml", PulseYaml.build(o).getBytes(StandardCharsets.UTF_8), written);
            // main: is unchanged for INSTRUMENT — descriptor copied verbatim above.
        }
        return new Result(Strategy.INSTRUMENT, d.main(), d.main(), relocatedPackage, insp.signed());
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

    private Path relocateCore(String relocatedPackage) throws IOException {
        Path payload = extractPayload();
        Path relocated = Files.createTempFile("pp-core-reloc", ".jar");
        try {
            List<Relocation> rules = List.of(new Relocation(CORE_PACKAGE, relocatedPackage));
            new JarRelocator(payload.toFile(), relocated.toFile(), rules).run();
        } finally {
            Files.deleteIfExists(payload);
        }
        return relocated;
    }

    /** The bundled pluginpulse-core jar, extracted to a temp file for jar-relocator. */
    private Path extractPayload() throws IOException {
        Path tmp = Files.createTempFile("pp-core", ".jar");
        try (InputStream in = Injector.class.getResourceAsStream("/payload/pluginpulse-core.jar.payload")) {
            if (in == null) {
                throw new IOException("Bundled pluginpulse-core payload missing from the tool jar.");
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
        String u = name.toUpperCase();
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
