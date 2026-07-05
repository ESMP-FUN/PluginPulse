package io.github.darkstarworks.pluginpulse.inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared helpers for building fake plugin jars from test-classpath fixtures. */
final class TestJars {

    private TestJars() {
    }

    static byte[] classBytes(Class<?> c) throws IOException {
        String path = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing class bytes for " + c.getName());
            return in.readAllBytes();
        }
    }

    /** Build a jar containing the given main class plus a legacy plugin.yml. */
    static Path legacyPluginJar(Path dir, Class<?> main) throws IOException {
        String yml = "name: SamplePlugin\nversion: '1.0.0'\nmain: " + main.getName() + "\napi-version: '1.20'\n";
        return build(dir, main, "plugin.yml", yml);
    }

    /** Build a jar containing the given main class plus a paper-plugin.yml. */
    static Path paperPluginJar(Path dir, Class<?> main) throws IOException {
        String yml = "name: SamplePlugin\nversion: '1.0.0'\nmain: " + main.getName()
                + "\napi-version: '1.20'\nbootstrapper: some.Bootstrap\n";
        return build(dir, main, "paper-plugin.yml", yml);
    }

    private static Path build(Path dir, Class<?> main, String descriptorName, String descriptor) throws IOException {
        Path jar = dir.resolve("input-" + main.getSimpleName() + ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            String classPath = main.getName().replace('.', '/') + ".class";
            out.putNextEntry(new ZipEntry(classPath));
            out.write(classBytes(main));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(descriptorName));
            out.write(descriptor.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
