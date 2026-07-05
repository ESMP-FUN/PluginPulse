package io.github.darkstarworks.pluginpulse.inject;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

/**
 * Generates a wrapper subclass:
 *
 * <pre>{@code
 * public class <Main>__Pulse extends <Main> {
 *     public void onEnable()  { super.onEnable();  PluginPulse.bootstrap(this); }
 *     public void onDisable() { PluginPulse.shutdown(this); super.onDisable(); }
 * }
 * }</pre>
 *
 * <p>Super calls use {@code INVOKESPECIAL} (not virtual — a virtual self-call
 * would recurse infinitely). Bodies are branch-free void methods, so no stack
 * map frames are needed and {@code COMPUTE_MAXS} suffices — we never have to
 * classload Bukkit to compute frames.</p>
 */
final class WrapperGenerator {

    static final String SUFFIX = "__Pulse";
    private static final String JAVA_PLUGIN = "org/bukkit/plugin/java/JavaPlugin";

    private WrapperGenerator() {
    }

    /** @return the wrapper's binary name (e.g. {@code a.b.Main__Pulse}). */
    static String wrapperName(String mainFqn) {
        return mainFqn + SUFFIX;
    }

    /**
     * @param mainFqn               the original main class FQN
     * @param relocatedBaseInternal internal name of the relocated PluginPulse package
     *                              (e.g. {@code a/b/pluginpulse})
     * @return the wrapper class bytes
     */
    static byte[] generate(String mainFqn, String relocatedBaseInternal) {
        String superInternal = mainFqn.replace('.', '/');
        String wrapperInternal = superInternal + SUFFIX;
        String pulse = relocatedBaseInternal + "/PluginPulse";
        String updaterDesc = "L" + relocatedBaseInternal + "/Updater;";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, wrapperInternal, null, superInternal, null);

        // Default constructor delegating to super.
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, superInternal, "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // onEnable: super.onEnable(); PluginPulse.bootstrap(this) (drop return).
        MethodVisitor onEnable = cw.visitMethod(ACC_PUBLIC, "onEnable", "()V", null, null);
        onEnable.visitCode();
        onEnable.visitVarInsn(ALOAD, 0);
        onEnable.visitMethodInsn(INVOKESPECIAL, superInternal, "onEnable", "()V", false);
        onEnable.visitVarInsn(ALOAD, 0);
        onEnable.visitMethodInsn(INVOKESTATIC, pulse, "bootstrap",
                "(L" + JAVA_PLUGIN + ";)" + updaterDesc, false);
        onEnable.visitInsn(POP);
        onEnable.visitInsn(RETURN);
        onEnable.visitMaxs(0, 0);
        onEnable.visitEnd();

        // onDisable: PluginPulse.shutdown(this); super.onDisable().
        MethodVisitor onDisable = cw.visitMethod(ACC_PUBLIC, "onDisable", "()V", null, null);
        onDisable.visitCode();
        onDisable.visitVarInsn(ALOAD, 0);
        onDisable.visitMethodInsn(INVOKESTATIC, pulse, "shutdown",
                "(L" + JAVA_PLUGIN + ";)V", false);
        onDisable.visitVarInsn(ALOAD, 0);
        onDisable.visitMethodInsn(INVOKESPECIAL, superInternal, "onDisable", "()V", false);
        onDisable.visitInsn(RETURN);
        onDisable.visitMaxs(0, 0);
        onDisable.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
