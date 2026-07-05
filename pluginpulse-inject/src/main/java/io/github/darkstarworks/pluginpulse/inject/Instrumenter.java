package io.github.darkstarworks.pluginpulse.inject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;

/**
 * In-place instrumentation for {@code final} main classes that can't be
 * subclassed. Appends {@code PluginPulse.bootstrap(this)} to every exit of
 * {@code onEnable}, prepends {@code PluginPulse.shutdown(this)} to
 * {@code onDisable}, and synthesises either method if the class doesn't declare
 * it (delegating to super, then bootstrap/shutdown).
 *
 * <p>Requires ASM to parse the class, so it only runs on versions ASM supports
 * (the inspector routes newer/unreadable classes to {@link Strategy#WRAPPER}).</p>
 */
final class Instrumenter {

    private static final String JAVA_PLUGIN = "org/bukkit/plugin/java/JavaPlugin";

    private Instrumenter() {
    }

    static byte[] instrument(byte[] mainClass, String relocatedBaseInternal) {
        ClassReader reader = new ClassReader(mainClass);
        String pulse = relocatedBaseInternal + "/PluginPulse";
        String updaterDesc = "L" + relocatedBaseInternal + "/Updater;";
        // COMPUTE_MAXS (not FRAMES): we only add branch-free instructions at
        // method boundaries, so existing stack map frames stay valid and we
        // never have to classload Bukkit to recompute them.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        Visitor visitor = new Visitor(writer, pulse, updaterDesc);
        // AdviceAdapter (a LocalVariablesSorter) requires expanded frames.
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class Visitor extends ClassVisitor {
        private final String pulse;
        private final String updaterDesc;
        private String owner;
        private String superName;
        private boolean hasEnable;
        private boolean hasDisable;

        Visitor(ClassVisitor cv, String pulse, String updaterDesc) {
            super(ASM9, cv);
            this.pulse = pulse;
            this.updaterDesc = updaterDesc;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.owner = name;
            this.superName = superName;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("()V".equals(descriptor) && name.equals("onEnable")) {
                hasEnable = true;
                return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
                    @Override
                    protected void onMethodExit(int opcode) {
                        // Append bootstrap(this) at each normal/throw exit.
                        loadThis();
                        visitMethodInsn(INVOKESTATIC, pulse, "bootstrap",
                                "(L" + JAVA_PLUGIN + ";)" + updaterDesc, false);
                        pop();
                    }
                };
            }
            if ("()V".equals(descriptor) && name.equals("onDisable")) {
                hasDisable = true;
                return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadThis();
                        visitMethodInsn(INVOKESTATIC, pulse, "shutdown",
                                "(L" + JAVA_PLUGIN + ";)V", false);
                    }
                };
            }
            return mv;
        }

        @Override
        public void visitEnd() {
            if (!hasEnable) synthesiseEnable();
            if (!hasDisable) synthesiseDisable();
            super.visitEnd();
        }

        private void synthesiseEnable() {
            MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "onEnable", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, superName, "onEnable", "()V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, pulse, "bootstrap", "(L" + JAVA_PLUGIN + ";)" + updaterDesc, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void synthesiseDisable() {
            MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "onDisable", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, pulse, "shutdown", "(L" + JAVA_PLUGIN + ";)V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, superName, "onDisable", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }
}
