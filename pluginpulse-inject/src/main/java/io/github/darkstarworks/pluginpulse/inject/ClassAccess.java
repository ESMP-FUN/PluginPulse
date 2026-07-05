package io.github.darkstarworks.pluginpulse.inject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a class file's {@code access_flags} without a full bytecode library, by
 * walking the constant pool structurally. Version-agnostic: it never depends on
 * the class-file major version, so it works on Java 25 / class v69 (mc26) jars
 * that ASM 9.7 refuses to parse.
 *
 * <p>This is the same constant-pool walk the in-browser JS engine reimplements;
 * keeping a reference copy in Java lets the two be cross-checked.</p>
 */
final class ClassAccess {

    static final int ACC_FINAL = 0x0010;

    private ClassAccess() {
    }

    /** @return the class {@code access_flags}, or -1 if the bytes aren't a class file. */
    static int readAccessFlags(byte[] classBytes) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) return -1;
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            skipConstantPool(in, cpCount);
            return in.readUnsignedShort(); // access_flags
        } catch (IOException e) {
            return -1;
        }
    }

    static boolean isFinal(byte[] classBytes) {
        int flags = readAccessFlags(classBytes);
        return flags >= 0 && (flags & ACC_FINAL) != 0;
    }

    /**
     * Advance past {@code cpCount - 1} constant-pool entries. Long and Double
     * entries occupy two slots (JVMS §4.4.5), so the index steps by two for them.
     */
    private static void skipConstantPool(DataInputStream in, int cpCount) throws IOException {
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);            // Class, String, MethodType, Module, Package
                case 15 -> in.skipBytes(3);                          // MethodHandle
                case 9, 10, 11, 3, 4, 12, 17, 18 ->                  // Fieldref/Methodref/InterfaceMethodref/Integer/Float/NameAndType/Dynamic/InvokeDynamic
                        in.skipBytes(4);
                case 5, 6 -> {                                       // Long, Double
                    in.skipBytes(8);
                    i++;                                             // takes two slots
                }
                case 1 -> {                                          // Utf8
                    int len = in.readUnsignedShort();
                    in.skipBytes(len);
                }
                default -> throw new IOException("Unknown constant pool tag " + tag);
            }
        }
    }
}
