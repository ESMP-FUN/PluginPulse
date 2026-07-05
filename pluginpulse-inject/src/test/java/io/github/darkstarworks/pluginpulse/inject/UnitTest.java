package io.github.darkstarworks.pluginpulse.inject;

import io.github.darkstarworks.pluginpulse.inject.fixture.FinalSamplePlugin;
import io.github.darkstarworks.pluginpulse.inject.fixture.SamplePlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure pieces: main-rewrite, access flags, wrapper structure. */
class UnitTest {

    @Test
    void rewriteMainLegacy() {
        String yml = "name: Foo\nmain: com.example.Foo\nversion: '1.0'\n";
        String out = Injector.rewriteMain(yml, "com.example.Foo__Pulse");
        assertTrue(out.contains("main: com.example.Foo__Pulse"));
        assertFalse(out.contains("main: com.example.Foo\n"));
        assertTrue(out.contains("name: Foo"), "other keys preserved");
    }

    @Test
    void rewriteMainPreservesIndentAndOtherLines() {
        String yml = "name: Bar\nmain:    a.b.C\nauthor: me\n";
        String out = Injector.rewriteMain(yml, "a.b.C__Pulse");
        assertTrue(out.contains("main:    a.b.C__Pulse"), "indentation kept: " + out);
        assertTrue(out.contains("author: me"));
    }

    @Test
    void detectsFinalMain() throws Exception {
        assertTrue(ClassAccess.isFinal(TestJars.classBytes(FinalSamplePlugin.class)));
        assertFalse(ClassAccess.isFinal(TestJars.classBytes(SamplePlugin.class)));
    }

    @Test
    void wrapperIsLoadableAndExtendsMain() throws Exception {
        byte[] bytes = WrapperGenerator.generate(SamplePlugin.class.getName(),
                "io/github/darkstarworks/pluginpulse");
        String wrapperName = WrapperGenerator.wrapperName(SamplePlugin.class.getName());

        // Define the wrapper with the test classloader as parent so SamplePlugin
        // and JavaPlugin resolve; the (non-relocated) PluginPulse refs are only
        // linked when the methods run, which we don't do here.
        Class<?> wrapper = new DefiningLoader(getClass().getClassLoader()).define(wrapperName, bytes);

        assertEquals(SamplePlugin.class, wrapper.getSuperclass());
        assertNotNull(wrapper.getDeclaredMethod("onEnable"));
        assertNotNull(wrapper.getDeclaredMethod("onDisable"));
    }

    /** Minimal loader that can turn bytes into a Class for structural assertions. */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
