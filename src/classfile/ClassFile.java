package classfile;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A minimal Java classfile parser (JVMS §4) — used by the boot-image writer now
 * and by the runtime class loader later (same code, two contexts; PLAN.md §2,
 * §5). It reads the constant pool, methods, and their Code attributes; it grows
 * as the compiler needs more (it currently ignores fields, most attributes, and
 * exception tables — the boot path has none).
 */
public final class ClassFile {

    /** A resolved symbolic reference to a member: owner/name/descriptor. */
    public record MemberRef(String owner, String name, String descriptor) {}

    /** A method with its bytecode. */
    public static final class Method {
        public final String name, descriptor;
        public final int maxStack, maxLocals;
        public final byte[] code;
        Method(String name, String descriptor, int maxStack, int maxLocals, byte[] code) {
            this.name = name; this.descriptor = descriptor;
            this.maxStack = maxStack; this.maxLocals = maxLocals; this.code = code;
        }
    }

    // Constant-pool tags (JVMS Table 4.4-A).
    private static final int UTF8=1, INTEGER=3, FLOAT=4, LONG=5, DOUBLE=6, CLASS=7,
            STRING=8, FIELDREF=9, METHODREF=10, IFACEMETHODREF=11, NAMEANDTYPE=12,
            METHODHANDLE=15, METHODTYPE=16, DYNAMIC=17, INVOKEDYNAMIC=18,
            MODULE=19, PACKAGE=20;

    private final int[] tag;
    private final int[] ref1;     // first index-ish operand (or int value / utf8 slot)
    private final int[] ref2;     // second index-ish operand
    private final long[] longVal; // for LONG/DOUBLE
    private final String[] utf8;

    private final String thisClass;
    private final Method[] methods;

    public String thisClassName() { return thisClass; }
    public Method[] methods()     { return methods; }

    public Method method(String name, String descriptor) {
        for (Method m : methods)
            if (m.name.equals(name) && m.descriptor.equals(descriptor)) return m;
        throw new IllegalArgumentException("no method " + name + descriptor + " in " + thisClass);
    }

    // ----- constant-pool accessors -----------------------------------------
    public String utf8(int i)    { require(i, UTF8);    return utf8[i]; }
    public int intAt(int i)      { require(i, INTEGER); return ref1[i]; }
    public long longAt(int i)    { require(i, LONG);    return longVal[i]; }
    public String stringAt(int i){ require(i, STRING);  return utf8(ref1[i]); }
    public String classAt(int i) { require(i, CLASS);   return utf8(ref1[i]); }

    /** Resolve a Methodref/InterfaceMethodref/Fieldref index to owner/name/descriptor. */
    public MemberRef memberRef(int i) {
        int t = tag[i];
        if (t != METHODREF && t != IFACEMETHODREF && t != FIELDREF)
            throw new IllegalStateException("cp#" + i + " is not a member ref (tag " + t + ")");
        String owner = classAt(ref1[i]);
        int nat = ref2[i];
        return new MemberRef(owner, utf8(ref1[nat]), utf8(ref2[nat]));
    }

    private void require(int i, int expected) {
        if (i <= 0 || i >= tag.length || tag[i] != expected)
            throw new IllegalStateException("cp#" + i + " expected tag " + expected + " got "
                    + (i < tag.length ? tag[i] : "oob"));
    }

    // ----- parsing ---------------------------------------------------------
    public static ClassFile parse(Path classFile) throws IOException {
        return new ClassFile(Files.readAllBytes(classFile));
    }

    public ClassFile(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("bad classfile magic");
        in.readUnsignedShort(); in.readUnsignedShort();        // minor, major

        int cpCount = in.readUnsignedShort();
        tag = new int[cpCount]; ref1 = new int[cpCount]; ref2 = new int[cpCount];
        longVal = new long[cpCount]; utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int t = in.readUnsignedByte();
            tag[i] = t;
            switch (t) {
                case UTF8 -> utf8[i] = in.readUTF();
                case INTEGER, FLOAT -> ref1[i] = in.readInt();
                case LONG, DOUBLE -> { longVal[i] = in.readLong(); i++; } // takes two slots
                case CLASS, STRING, METHODTYPE, MODULE, PACKAGE -> ref1[i] = in.readUnsignedShort();
                case FIELDREF, METHODREF, IFACEMETHODREF, NAMEANDTYPE, DYNAMIC, INVOKEDYNAMIC -> {
                    ref1[i] = in.readUnsignedShort(); ref2[i] = in.readUnsignedShort();
                }
                case METHODHANDLE -> { ref1[i] = in.readUnsignedByte(); ref2[i] = in.readUnsignedShort(); }
                default -> throw new IOException("unknown constant-pool tag " + t + " at #" + i);
            }
        }

        in.readUnsignedShort();                                // access_flags
        thisClass = classAt(in.readUnsignedShort());
        in.readUnsignedShort();                                // super_class
        int ifaces = in.readUnsignedShort();
        for (int i = 0; i < ifaces; i++) in.readUnsignedShort();

        skipMembers(in);                                       // fields
        methods = readMethods(in);
        // class attributes ignored
    }

    private void skipMembers(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort(); // flags,name,desc
            skipAttributes(in);
        }
    }

    private Method[] readMethods(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        Method[] ms = new Method[n];
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();                            // access_flags
            String name = utf8(in.readUnsignedShort());
            String desc = utf8(in.readUnsignedShort());
            int attrs = in.readUnsignedShort();
            int maxStack = 0, maxLocals = 0;
            byte[] code = null;
            for (int a = 0; a < attrs; a++) {
                String an = utf8(in.readUnsignedShort());
                int len = in.readInt();
                if (an.equals("Code")) {
                    maxStack = in.readUnsignedShort();
                    maxLocals = in.readUnsignedShort();
                    int codeLen = in.readInt();
                    code = new byte[codeLen];
                    in.readFully(code);
                    int exc = in.readUnsignedShort();
                    in.skipBytes(exc * 8);
                    skipAttributes(in);
                } else {
                    in.skipBytes(len);
                }
            }
            ms[i] = new Method(name, desc, maxStack, maxLocals, code);
        }
        return ms;
    }

    private void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();                            // name index
            int len = in.readInt();
            in.skipBytes(len);
        }
    }
}
