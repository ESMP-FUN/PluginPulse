package io.github.darkstarworks.pluginpulse.download;

import com.google.inject.Guice;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.darkstarworks.pluginpulse.inject.InjectOptions;
import io.github.darkstarworks.pluginpulse.inject.Injector;
import io.github.darkstarworks.pluginpulse.inject.fixture.FinalSamplePlugin;
import io.github.darkstarworks.pluginpulse.inject.fixture.SamplePlugin;
import io.github.darkstarworks.pluginpulse.inject.fixture.SampleVelocityPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarryOverTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String PKG = "io/github/darkstarworks/pluginpulse/inject/fixture";

    private static InjectOptions opts() {
        return new InjectOptions("sample-slug", null, null, "sample.admin", null,
                "auto-stage", "me@example.com", null, 6, false, null, false, null);
    }

    @Test
    void paperJarKeepsTheWrapperAcrossAnUpdate(@TempDir Path dir) throws Exception {
        Path injected = inject(dir, jar(dir, "old.jar", SamplePlugin.class, "plugin.yml",
                yml(SamplePlugin.class, "1.0.0")));
        Path fresh = jar(dir, "new.jar", SamplePlugin.class, "plugin.yml", yml(SamplePlugin.class, "2.0.0"));

        assertTrue(CarryOver.apply(fresh, injected, LOG));
        Map<String, byte[]> out = readAll(fresh);
        String desc = new String(out.get("plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(desc.contains("main: " + SamplePlugin.class.getName() + "__Pulse"), desc);
        assertTrue(desc.contains("version: '2.0.0'"), "the new version's own descriptor is kept");
        assertTrue(out.containsKey(PKG + "/SamplePlugin__Pulse.class"));
        assertTrue(out.containsKey(PKG + "/pluginpulse/PluginPulse.class"));
        assertTrue(new String(out.get("pluginpulse.yml"), StandardCharsets.UTF_8).contains("modrinth: sample-slug"));

        assertTrue(CarryOver.apply(fresh, fresh, LOG) == false, "a jar that already has PluginPulse is left alone");
    }

    @Test
    void velocityJarKeepsTheHookAndStillLoads(@TempDir Path dir) throws Exception {
        Path injected = inject(dir, jar(dir, "old.jar", SampleVelocityPlugin.class, "velocity-plugin.json",
                json(SampleVelocityPlugin.class, "1.0.0")));
        Path fresh = jar(dir, "new.jar", SampleVelocityPlugin.class, "velocity-plugin.json",
                json(SampleVelocityPlugin.class, "2.0.0"));

        assertTrue(CarryOver.apply(fresh, injected, LOG));
        Map<String, byte[]> out = readAll(fresh);
        assertTrue(new String(out.get("velocity-plugin.json"), StandardCharsets.UTF_8).contains("\"2.0.0\""));
        assertTrue(out.containsKey(PKG + "/pluginpulse/velocity/VelocityPulse.class"));

        ClassLoader loader = new JarFirstLoader(out, getClass().getClassLoader());
        Class<?> main = Class.forName(SampleVelocityPlugin.class.getName(), true, loader);
        ProxyServer proxy = (ProxyServer) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ProxyServer.class}, (p, m, a) -> null);
        Object plugin = Guice.createInjector(b -> b.bind(ProxyServer.class).toInstance(proxy)).getInstance(main);
        Field field = main.getDeclaredField(ClassFiles.VELOCITY_FIELD);
        field.setAccessible(true);
        assertSame(proxy, field.get(plugin));
        main.getMethod("pluginpulse$init", ProxyInitializeEvent.class).invoke(plugin, (Object) null);
    }

    @Test
    void javaHookMatchesTheInjectorsHook(@TempDir Path dir) throws Exception {
        byte[] plain = classBytes(SampleVelocityPlugin.class);
        byte[] hooked = ClassFiles.addVelocityHook(plain, PKG + "/pluginpulse");
        assertArrayEquals(hooked, ClassFiles.addVelocityHook(hooked, PKG + "/pluginpulse"), "added only once");
        ClassReader reader = new ClassReader(hooked);
        Set<String> members = new java.util.HashSet<>();
        reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                members.add(n + d);
                return null;
            }

            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                members.add(n + d);
                return null;
            }
        }, 0);
        assertTrue(members.contains("pluginpulse$proxyLcom/velocitypowered/api/proxy/ProxyServer;"));
        assertTrue(members.contains("pluginpulse$init(Lcom/velocitypowered/api/event/proxy/ProxyInitializeEvent;)V"));
        assertTrue(members.contains("onInit(Lcom/velocitypowered/api/event/proxy/ProxyInitializeEvent;)V"));
    }

    @Test
    void definalizeClearsTheFinalFlag() throws Exception {
        byte[] bytes = classBytes(FinalSamplePlugin.class);
        assertTrue((new ClassReader(bytes).getAccess() & Opcodes.ACC_FINAL) != 0);
        byte[] cleared = ClassFiles.definalize(bytes, Set.of("onEnable", "onDisable"));
        assertEquals(0, new ClassReader(cleared).getAccess() & Opcodes.ACC_FINAL);
        assertEquals(bytes.length, cleared.length);
    }

    @Test
    void plainOrAuthorShadedJarsAreLeftAlone(@TempDir Path dir) throws Exception {
        Path plain = jar(dir, "plain.jar", SamplePlugin.class, "plugin.yml", yml(SamplePlugin.class, "1.0.0"));
        Path fresh = jar(dir, "new.jar", SamplePlugin.class, "plugin.yml", yml(SamplePlugin.class, "2.0.0"));
        byte[] before = Files.readAllBytes(fresh);
        assertFalse(CarryOver.apply(fresh, plain, LOG));
        assertArrayEquals(before, Files.readAllBytes(fresh));
    }

    // ==== Helpers ====

    private static Path inject(Path dir, Path input) throws IOException {
        Path out = dir.resolve("injected-" + input.getFileName());
        new Injector().inject(input, out, opts());
        return out;
    }

    private static String yml(Class<?> main, String version) {
        return "name: Sample\nversion: '" + version + "'\nmain: " + main.getName() + "\n";
    }

    private static String json(Class<?> main, String version) {
        return "{\"id\":\"sample\",\"version\":\"" + version + "\",\"main\":\"" + main.getName() + "\"}";
    }

    private static Path jar(Path dir, String name, Class<?> main, String descriptor, String text) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(main.getName().replace('.', '/') + ".class"));
            out.write(classBytes(main));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(descriptor));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static byte[] classBytes(Class<?> c) throws IOException {
        try (InputStream in = c.getClassLoader().getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    private static Map<String, byte[]> readAll(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                out.put(e.getName(), zip.getInputStream(e).readAllBytes());
            }
        }
        return out;
    }

    /** Loads classes from the jar before the test classpath, which holds the untouched fixture. */
    private static final class JarFirstLoader extends ClassLoader {
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
    }
}
