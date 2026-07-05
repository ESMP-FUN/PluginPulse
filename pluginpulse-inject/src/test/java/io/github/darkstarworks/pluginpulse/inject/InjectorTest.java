package io.github.darkstarworks.pluginpulse.inject;

import io.github.darkstarworks.pluginpulse.inject.fixture.FinalSamplePlugin;
import io.github.darkstarworks.pluginpulse.inject.fixture.SamplePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InjectorTest {

    private static InjectOptions opts() {
        return new InjectOptions("sample-slug", null, null, "sample.admin", "/sample",
                "notify", "me@example.com", null, 6, false);
    }

    @Test
    void wrapperInjectionRewritesMainAndRelocates(@TempDir Path dir) throws Exception {
        Path input = TestJars.legacyPluginJar(dir, SamplePlugin.class);
        Path output = dir.resolve("out.jar");

        Injector.Result result = new Injector().inject(input, output, opts());
        assertEquals(Strategy.WRAPPER, result.strategy());

        Map<String, String> entries = readAll(output);
        String pkg = "io/github/darkstarworks/pluginpulse/inject/fixture";

        // Wrapper class present next to the original main.
        assertTrue(entries.containsKey(pkg + "/SamplePlugin__Pulse.class"), "wrapper class present");
        assertTrue(entries.containsKey(pkg + "/SamplePlugin.class"), "original main kept as superclass");

        // Descriptor main re-pointed to the wrapper.
        assertTrue(entries.get("plugin.yml")
                .contains("main: io.github.darkstarworks.pluginpulse.inject.fixture.SamplePlugin__Pulse"));

        // pluginpulse.yml written from options.
        String pulseYml = entries.get("pluginpulse.yml");
        assertTrue(pulseYml.contains("modrinth: sample-slug"));
        assertTrue(pulseYml.contains("command-root: /sample"));

        // Core relocated under the target's package; no un-relocated core class leaked.
        String relocated = pkg + "/pluginpulse/PluginPulse.class";
        assertTrue(entries.containsKey(relocated), "relocated PluginPulse present");
        assertFalse(entries.keySet().stream()
                        .anyMatch(n -> n.startsWith("io/github/darkstarworks/pluginpulse/")
                                && !n.startsWith("io/github/darkstarworks/pluginpulse/inject/")),
                "no core class left under the original package");
    }

    @Test
    void instrumentInjectionKeepsMainAndDoesNotAddWrapper(@TempDir Path dir) throws Exception {
        Path input = TestJars.legacyPluginJar(dir, FinalSamplePlugin.class);
        Path output = dir.resolve("out-final.jar");

        Injector.Result result = new Injector().inject(input, output, opts());
        assertEquals(Strategy.INSTRUMENT, result.strategy());

        Map<String, String> entries = readAll(output);
        String pkg = "io/github/darkstarworks/pluginpulse/inject/fixture";
        assertTrue(entries.containsKey(pkg + "/FinalSamplePlugin.class"));
        assertFalse(entries.containsKey(pkg + "/FinalSamplePlugin__Pulse.class"), "no wrapper for final main");
        // main: unchanged for INSTRUMENT.
        assertTrue(entries.get("plugin.yml")
                .contains("main: io.github.darkstarworks.pluginpulse.inject.fixture.FinalSamplePlugin"));
    }

    @Test
    void paperDescriptorPreferredAndRewritten(@TempDir Path dir) throws Exception {
        Path input = TestJars.paperPluginJar(dir, SamplePlugin.class);
        Path output = dir.resolve("out-paper.jar");

        new Injector().inject(input, output, opts());
        Map<String, String> entries = readAll(output);
        assertTrue(entries.containsKey("paper-plugin.yml"));
        String paper = entries.get("paper-plugin.yml");
        assertTrue(paper.contains("main: io.github.darkstarworks.pluginpulse.inject.fixture.SamplePlugin__Pulse"));
        // bootstrapper untouched.
        assertTrue(paper.contains("bootstrapper: some.Bootstrap"));
    }

    @Test
    void refusesAlreadyInjectedWithoutUpgrade(@TempDir Path dir) throws Exception {
        Path input = TestJars.legacyPluginJar(dir, SamplePlugin.class);
        Path once = dir.resolve("once.jar");
        new Injector().inject(input, once, opts());

        // Injecting the output again should be refused (it now contains PluginPulse).
        Path twice = dir.resolve("twice.jar");
        try {
            new Injector().inject(once, twice, opts());
            org.junit.jupiter.api.Assertions.fail("expected refusal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already contains PluginPulse"));
        }
    }

    private static Map<String, String> readAll(Path jar) throws IOException {
        Map<String, String> map = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (e.isDirectory()) continue;
                try (var in = zip.getInputStream(e)) {
                    map.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return map;
    }
}
