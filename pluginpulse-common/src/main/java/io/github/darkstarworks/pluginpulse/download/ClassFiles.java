package io.github.darkstarworks.pluginpulse.download;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The few class-file edits the injectors make, without a bytecode library, so
 * the updater can repeat them on a downloaded jar. Mirrors
 * {@code docs/engine/constant-pool.js}; only access flags and appended members
 * change, so any class-file version works.
 */
final class ClassFiles {

    private static final int FINAL = 0x0010;
    static final String VELOCITY_FIELD = "pluginpulse$proxy";

    private ClassFiles() {
    }

    /** Offsets into a parsed class file. */
    private static final class Layout {
        final List<String> utf8 = new ArrayList<>(); // by constant-pool index, null for non-Utf8
        int cpEnd;
        int nextIndex;
        int fieldsCountAt;
        int fieldsEnd;
        int methodsCountAt;
        int methodsEnd;

        Layout(byte[] bytes) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            if (b.getInt(0) != 0xCAFEBABE) throw new IllegalArgumentException("Not a Java class file");
            int count = b.getShort(8) & 0xFFFF;
            utf8.add(null);
            int p = 10;
            for (int i = 1; i < count; i++) {
                int tag = bytes[p] & 0xFF;
                p++;
                if (tag == 1) {
                    int len = b.getShort(p) & 0xFFFF;
                    utf8.add(new String(bytes, p + 2, len, StandardCharsets.UTF_8));
                    p += 2 + len;
                    continue;
                }
                utf8.add(null);
                p += switch (tag) {
                    case 7, 8, 16, 19, 20 -> 2;
                    case 15 -> 3;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> 4;
                    case 5, 6 -> 8;
                    default -> throw new IllegalArgumentException("Unknown constant pool tag " + tag);
                };
                if (tag == 5 || tag == 6) {
                    utf8.add(null);
                    i++;
                }
            }
            cpEnd = p;
            nextIndex = count;
            p += 6; // access, this, super
            p += 2 + (b.getShort(p) & 0xFFFF) * 2;
            fieldsCountAt = p;
            p = skipMembers(b, p);
            fieldsEnd = p;
            methodsCountAt = p;
            methodsEnd = skipMembers(b, p);
        }

        private static int skipMembers(ByteBuffer b, int p) {
            int count = b.getShort(p) & 0xFFFF;
            p += 2;
            for (int i = 0; i < count; i++) {
                int attrs = b.getShort(p + 6) & 0xFFFF;
                p += 8;
                for (int a = 0; a < attrs; a++) p += 6 + b.getInt(p + 2);
            }
            return p;
        }

        String utf8At(int index) {
            return index > 0 && index < utf8.size() ? utf8.get(index) : null;
        }
    }

    /** Whether the constant pool holds this exact string. */
    static boolean hasUtf8(byte[] classBytes, String value) {
        return new Layout(classBytes).utf8.contains(value);
    }

    /** Clear ACC_FINAL on the class and on the named methods, so a wrapper can subclass it. */
    static byte[] definalize(byte[] classBytes, Set<String> methodNames) {
        Layout l = new Layout(classBytes);
        byte[] out = classBytes.clone();
        ByteBuffer b = ByteBuffer.wrap(out);
        b.putShort(l.cpEnd, (short) (b.getShort(l.cpEnd) & ~FINAL));
        int p = l.methodsCountAt;
        int count = b.getShort(p) & 0xFFFF;
        p += 2;
        for (int i = 0; i < count; i++) {
            int access = b.getShort(p) & 0xFFFF;
            if ((access & FINAL) != 0 && methodNames.contains(l.utf8At(b.getShort(p + 2) & 0xFFFF))) {
                b.putShort(p, (short) (access & ~FINAL));
            }
            int attrs = b.getShort(p + 6) & 0xFFFF;
            p += 8;
            for (int a = 0; a < attrs; a++) p += 6 + b.getInt(p + 2);
        }
        return out;
    }

    /**
     * Append the Velocity hook the injectors add: an {@code @Inject} proxy
     * field and an {@code @Subscribe} init handler that starts the updater.
     * A class that already has the field is returned unchanged.
     */
    static byte[] addVelocityHook(byte[] classBytes, String relocInternal) {
        Layout l = new Layout(classBytes);
        ByteBuffer in = ByteBuffer.wrap(classBytes);
        int p = l.fieldsCountAt + 2;
        int fieldCount = in.getShort(l.fieldsCountAt) & 0xFFFF;
        for (int i = 0; i < fieldCount; i++) {
            if (VELOCITY_FIELD.equals(l.utf8At(in.getShort(p + 2) & 0xFFFF))) return classBytes;
            int attrs = in.getShort(p + 6) & 0xFFFF;
            p += 8;
            for (int a = 0; a < attrs; a++) p += 6 + in.getInt(p + 2);
        }

        Pool pool = new Pool(l.nextIndex);
        int thisClass = in.getShort(l.cpEnd + 2) & 0xFFFF;
        String proxyDesc = "Lcom/velocitypowered/api/proxy/ProxyServer;";
        int fieldName = pool.utf8(VELOCITY_FIELD);
        int fieldDesc = pool.utf8(proxyDesc);
        int rva = pool.utf8("RuntimeVisibleAnnotations");
        int injectType = pool.utf8("Lcom/google/inject/Inject;");
        int subscribeType = pool.utf8("Lcom/velocitypowered/api/event/Subscribe;");
        int codeName = pool.utf8("Code");
        int methodName = pool.utf8("pluginpulse$init");
        int methodDesc = pool.utf8("(Lcom/velocitypowered/api/event/proxy/ProxyInitializeEvent;)V");
        int fieldRef = pool.ref(9, thisClass, pool.ref(12, fieldName, fieldDesc));
        int pulseClass = pool.ref(7, pool.utf8(relocInternal + "/velocity/VelocityPulse"), -1);
        int bootstrap = pool.ref(10, pulseClass, pool.ref(12, pool.utf8("bootstrap"),
                pool.utf8("(" + proxyDesc + "Ljava/lang/Object;)L" + relocInternal + "/velocity/VelocityUpdater;")));

        ByteArrayOutputStream field = new ByteArrayOutputStream();
        u2(field, 0x0002);
        u2(field, fieldName);
        u2(field, fieldDesc);
        u2(field, 1);
        annotation(field, rva, injectType);

        byte[] code = {
                0x2a,                                                   // aload_0
                (byte) 0xb4, (byte) (fieldRef >> 8), (byte) fieldRef,   // getfield
                0x2a,                                                   // aload_0
                (byte) 0xb8, (byte) (bootstrap >> 8), (byte) bootstrap, // invokestatic
                0x57,                                                   // pop
                (byte) 0xb1,                                            // return
        };
        ByteArrayOutputStream method = new ByteArrayOutputStream();
        u2(method, 0x0001);
        u2(method, methodName);
        u2(method, methodDesc);
        u2(method, 2);
        u2(method, codeName);
        u4(method, 12 + code.length);
        u2(method, 2);
        u2(method, 2);
        u4(method, code.length);
        method.writeBytes(code);
        u2(method, 0);
        u2(method, 0);
        annotation(method, rva, subscribeType);

        ByteArrayOutputStream out = new ByteArrayOutputStream(classBytes.length + 512);
        out.write(classBytes, 0, 8);
        u2(out, pool.next);
        out.write(classBytes, 10, l.cpEnd - 10);
        out.writeBytes(pool.bytes.toByteArray());
        int fieldsCountAt = out.size() + (l.fieldsCountAt - l.cpEnd);
        out.write(classBytes, l.cpEnd, l.fieldsEnd - l.cpEnd);
        out.writeBytes(field.toByteArray());
        int methodsCountAt = out.size();
        out.write(classBytes, l.fieldsEnd, l.methodsEnd - l.fieldsEnd);
        out.writeBytes(method.toByteArray());
        out.write(classBytes, l.methodsEnd, classBytes.length - l.methodsEnd);

        byte[] result = out.toByteArray();
        ByteBuffer r = ByteBuffer.wrap(result);
        r.putShort(fieldsCountAt, (short) (fieldCount + 1));
        r.putShort(methodsCountAt, (short) ((r.getShort(methodsCountAt) & 0xFFFF) + 1));
        return result;
    }

    private static void annotation(ByteArrayOutputStream out, int rva, int type) {
        u2(out, rva);
        u4(out, 6);
        u2(out, 1);
        u2(out, type);
        u2(out, 0);
    }

    private static void u2(ByteArrayOutputStream out, int v) {
        out.write(v >> 8);
        out.write(v);
    }

    private static void u4(ByteArrayOutputStream out, int v) {
        u2(out, v >>> 16);
        u2(out, v);
    }

    /** New constant-pool entries, numbered on from the existing pool. */
    private static final class Pool {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;

        Pool(int next) {
            this.next = next;
        }

        int utf8(String s) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            bytes.write(1);
            u2(bytes, data.length);
            bytes.writeBytes(data);
            return next++;
        }

        /** A two-u2 entry, or a one-u2 entry when {@code b} is -1. */
        int ref(int tag, int a, int b) {
            bytes.write(tag);
            u2(bytes, a);
            if (b >= 0) u2(bytes, b);
            return next++;
        }
    }
}
