package io.github.darkstarworks.pluginpulse.inject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;

/**
 * Wires a Velocity plugin's main class in place by adding two members:
 *
 * <pre>{@code
 * @Inject private ProxyServer pluginpulse$proxy;
 *
 * @Subscribe public void pluginpulse$init(ProxyInitializeEvent e) {
 *     VelocityPulse.bootstrap(pluginpulse$proxy, this);
 * }
 * }</pre>
 *
 * <p>Velocity builds the main class with Guice, which fills {@code @Inject}
 * fields, and calls {@code @Subscribe} methods on it, so neither the
 * constructor nor {@code velocity-plugin.json} has to change. The updater
 * installs downloads at proxy shutdown on its own. The new method is
 * branch-free, so existing stack map frames stay valid.</p>
 */
final class VelocityHook {

    static final String FIELD = "pluginpulse$proxy";
    static final String METHOD = "pluginpulse$init";
    static final String PROXY_DESC = "Lcom/velocitypowered/api/proxy/ProxyServer;";
    static final String INIT_DESC = "(Lcom/velocitypowered/api/event/proxy/ProxyInitializeEvent;)V";
    static final String INJECT = "Lcom/google/inject/Inject;";
    static final String SUBSCRIBE = "Lcom/velocitypowered/api/event/Subscribe;";

    private VelocityHook() {
    }

    /**
     * @param relocatedBaseInternal internal name of the relocated PluginPulse
     *                              package (e.g. {@code a/b/pluginpulse})
     */
    static byte[] addHook(byte[] mainClass, String relocatedBaseInternal) {
        ClassReader reader = new ClassReader(mainClass);
        ClassWriter writer = new ClassWriter(reader, 0);
        String pulse = relocatedBaseInternal + "/velocity/VelocityPulse";
        String bootstrapDesc = "(" + PROXY_DESC + "Ljava/lang/Object;)L"
                + relocatedBaseInternal + "/velocity/VelocityUpdater;";

        reader.accept(new ClassVisitor(ASM9, writer) {
            private String owner;
            private boolean present;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                owner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                // Re-injecting an upgraded jar: the hook is already there.
                if (FIELD.equals(name)) present = true;
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public void visitEnd() {
                if (!present) {
                    FieldVisitor fv = super.visitField(ACC_PRIVATE, FIELD, PROXY_DESC, null, null);
                    fv.visitAnnotation(INJECT, true).visitEnd();
                    fv.visitEnd();

                    MethodVisitor mv = super.visitMethod(ACC_PUBLIC, METHOD, INIT_DESC, null, null);
                    mv.visitAnnotation(SUBSCRIBE, true).visitEnd();
                    mv.visitCode();
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, owner, FIELD, PROXY_DESC);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKESTATIC, pulse, "bootstrap", bootstrapDesc, false);
                    mv.visitInsn(POP);
                    mv.visitInsn(RETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }
}
