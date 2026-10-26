package io.github.darkstarworks.pluginpulse.inject;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.darkstarworks.pluginpulse.inject.fixture.SamplePlugin;
import io.github.darkstarworks.pluginpulse.inject.fixture.SampleVelocityPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityInjectTest {

    private static final String PKG = "io/github/darkstarworks/pluginpulse/inject/fixture";

    private static InjectOptions opts() {
        return new InjectOptions("sample-slug", null, null, "sample.admin", null,
                "notify", "me@example.com", null, 6, false, null, false, null);
    }

    @Test
    void velocityJarGetsTheHookAndKeepsItsDescriptor(@TempDir Path dir) throws Exception {
        String json = TestJars.velocityJson(SampleVelocityPlugin.class);
        Path input = TestJars.velocityPluginJar(dir, SampleVelocityPlugin.class, json);
        Path output = dir.resolve("out.jar");

        Injector.Result result = new Injector().inject(input, output, opts());
        assertEquals(Strategy.VELOCITY, result.strategy());
        assertEquals(SampleVelocityPlugin.class.getName(), result.velocityMain());

        Map<String, byte[]> entries = readAll(output);
        assertEquals(json, new String(entries.get("velocity-plugin.json"), StandardCharsets.UTF_8));
        assertTrue(entries.containsKey(PKG + "/pluginpulse/velocity/VelocityPulse.class"));
        assertTrue(entries.containsKey(PKG + "/pluginpulse/engine/UpdateEngine.class"));
        assertFalse(entries.containsKey(PKG + "/pluginpulse/PluginPulse.class"), "no Bukkit library in a proxy jar");
        assertTrue(new String(entries.get("pluginpulse.yml"), StandardCharsets.UTF_8).contains("modrinth: sample-slug"));
    }

    @Test
    void hookedMainLoadsUnderGuiceAndBootstrapsFailSoft(@TempDir Path dir) throws Exception {
        Path input = TestJars.velocityPluginJar(dir, SampleVelocityPlugin.class,
                TestJars.velocityJson(SampleVelocityPlugin.class));
        Path output = dir.resolve("out.jar");
        new Injector().inject(input, output, opts());

        try (JarFirstLoader loader = new JarFirstLoader(readAll(output), getClass().getClassLoader())) {
            // Initialising the class makes the JVM verify the added bytecode.
            Class<?> main = Class.forName(SampleVelocityPlugin.class.getName(), true, loader);
            ProxyServer proxy = (ProxyServer) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{ProxyServer.class}, (p, m, a) -> null);
            Object plugin = Guice.createInjector(b -> b.bind(ProxyServer.class).toInstance(proxy))
                    .getInstance(main);

            Field field = main.getDeclaredField(VelocityHook.FIELD);
            assertTrue(field.isAnnotationPresent(Inject.class));
            field.setAccessible(true);
            assertSame(proxy, field.get(plugin), "Guice filled the injected field");

            Method init = main.getMethod(VelocityHook.METHOD, ProxyInitializeEvent.class);
            assertTrue(init.isAnnotationPresent(Subscribe.class));
            // The stub proxy knows no plugins, so bootstrap gives up quietly
            // after resolving the relocated library call.
            init.invoke(plugin, (Object) null);
            assertNotNull(main.getMethod("onInit", ProxyInitializeEvent.class), "own handler kept");
        }
    }

    @Test
    void dualPlatformJarGetsBothWirings(@TempDir Path dir) throws Exception {
        Path input = TestJars.dualPluginJar(dir, SamplePlugin.class, SampleVelocityPlugin.class);
        Path output = dir.resolve("out.jar");

        Injector.Result result = new Injector().inject(input, output, opts());
        assertEquals(Strategy.WRAPPER, result.strategy());
        assertEquals(SampleVelocityPlugin.class.getName(), result.velocityMain());

        Map<String, byte[]> entries = readAll(output);
        assertTrue(new String(entries.get("plugin.yml"), StandardCharsets.UTF_8).contains("SamplePlugin__Pulse"));
        assertTrue(entries.containsKey(PKG + "/pluginpulse/PluginPulse.class"));
        assertTrue(entries.containsKey(PKG + "/pluginpulse/velocity/VelocityPulse.class"));
    }

    @Test
    void tabIndentedDescriptorIsRead(@TempDir Path dir) throws Exception {
        String json = "{\n\t\"id\": \"sample\",\n\t\"version\": \"2.0\",\n\t\"main\": \""
                + SampleVelocityPlugin.class.getName() + "\"\n}";
        Path input = TestJars.velocityPluginJar(dir, SampleVelocityPlugin.class, json);
        JarInspector.Inspection insp = JarInspector.inspect(input);
        assertEquals(SampleVelocityPlugin.class.getName(), insp.velocity().main());
        assertEquals("2.0", insp.velocity().version());
    }

    @Test
    void reinjectingDoesNotAddTheHookTwice(@TempDir Path dir) throws Exception {
        byte[] once = VelocityHook.addHook(TestJars.classBytes(SampleVelocityPlugin.class), PKG + "/pluginpulse");
        byte[] twice = VelocityHook.addHook(once, PKG + "/pluginpulse");
        assertEquals(once.length, twice.length);
    }

    private static Map<String, byte[]> readAll(Path jar) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                out.put(e.getName(), zip.getInputStream(e).readAllBytes());
            }
        }
        return out;
    }

    /** Loads classes from the injected jar before the test classpath, which holds the untouched fixture. */
    private static final class JarFirstLoader extends ClassLoader implements AutoCloseable {
        private final Map<String, byte[]> entries;

        JarFirstLoader(Map<String, byte[]> entries, ClassLoader parent) {
            super(parent);
            this.entries = entries;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    byte[] bytes = entries.get(name.replace('.', '/') + ".class");
                    c = bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, false);
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }

        @Override
        public void close() {
        }
    }
}
