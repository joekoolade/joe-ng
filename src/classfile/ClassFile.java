package classfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The writer-side classfile model (JVMS §4): the constant pool, fields, methods
 * and their Code attributes, decoded into Strings, records and arrays for the
 * boot-image writer to work with.
 *
 * <p>It no longer walks the format itself. The structure — constant-pool layout,
 * section navigation, attribute skipping — is read by {@link ClassReader}, the
 * shared JDK-free parser that is also compiled into the image and used by the
 * on-metal loader (M5). So the classfile format is understood in exactly one
 * place, and what remains here is only the rich host-side model built on top of
 * it, none of which could exist on the metal.
 */
public final class ClassFile
{

    /** A resolved symbolic reference to a member: owner/name/descriptor. */
    public record MemberRef(String owner, String name, String descriptor) {}

    /** An instance/static field declaration. */
    public record FieldInfo(String name, String descriptor, boolean isStatic) {}

    private static final int ACC_STATIC = 0x0008;

    /** A try/catch entry: bytecode range [startPc,endPc), handler, and catch-type cp index (0 = any). */
    public record ExceptionEntry(int startPc, int endPc, int handlerPc, int catchType) {}

    /** A method with its bytecode. */
    public static final class Method
    {
        public final String name;
        public final String descriptor;
        public final int descOff;      // Utf8 body offset of the descriptor, for the shared ClassReader
        public final int maxStack;
        public final int maxLocals;
        public final boolean isStatic;
        public final byte[] code;
        public final ExceptionEntry[] exceptions;
        Method(String name, String descriptor, int descOff, boolean isStatic, int maxStack, int maxLocals,
               byte[] code, ExceptionEntry[] exceptions)
        {
            this.name = name;
            this.descriptor = descriptor;
            this.descOff = descOff;
            this.isStatic = isStatic;
            this.maxStack = maxStack;
            this.maxLocals = maxLocals;
            this.code = code;
            this.exceptions = exceptions;
        }
    }

    /** A class name we don't compile (JDK {@code java/*}) — treated as a root, like Object. */
    public static boolean isRoot(String cls)
    {
        return cls == null || cls.startsWith("java/");
    }

    // Constant-pool tags (JVMS Table 4.4-A).
    private static final int UTF8 = 1;
    private static final int INTEGER = 3;
    private static final int FLOAT = 4;
    private static final int LONG = 5;
    private static final int DOUBLE = 6;
    private static final int CLASS = 7;
    private static final int STRING = 8;
    private static final int FIELDREF = 9;
    private static final int METHODREF = 10;
    private static final int IFACEMETHODREF = 11;
    private static final int NAMEANDTYPE = 12;
    private static final int METHODHANDLE = 15;
    private static final int METHODTYPE = 16;
    private static final int DYNAMIC = 17;
    private static final int INVOKEDYNAMIC = 18;
    private static final int MODULE = 19;
    private static final int PACKAGE = 20;

    private final byte[] bytes;   // the raw classfile, retained for the shared ClassReader view
    private final int[] cpOff;    // byte offset of each constant-pool entry body
    private final int[] tag;
    private final int[] ref1;     // first index-ish operand (or int value / utf8 slot)
    private final int[] ref2;     // second index-ish operand
    private final long[] longVal; // for LONG/DOUBLE
    private final String[] utf8;

    private final String thisClass;
    private final String superClass;   // null only for java/lang/Object
    private final String[] interfaces; // directly-implemented interfaces
    private final Method[] methods;
    private final FieldInfo[] fields;

    /** The raw classfile bytes, for reading through the shared {@link ClassReader}. */
    public byte[] bytes()
    {
        return bytes;
    }
    /** Byte offset of each constant-pool entry body (index by cp index). */
    public int[] cpOff()
    {
        return cpOff;
    }
    /** Tag of each constant-pool entry (index by cp index). */
    public int[] cpTag()
    {
        return tag;
    }

    public String thisClassName()
    {
        return thisClass;
    }
    public String superClassName()
    {
        return superClass;
    }
    public String[] interfaceNames()
    {
        return interfaces;
    }
    public Method[] methods()
    {
        return methods;
    }
    public FieldInfo[] fields()
    {
        return fields;
    }

    /** Number of instance (non-static) fields — the object's field-slot count. */
    public int instanceFieldCount()
    {
        int n = 0;
        for (FieldInfo f : fields)
        {
            if (!f.isStatic())
            {
                n++;
            }
        }
        return n;
    }

    /** Index of instance field {@code name} in declaration order (its slot in the object). */
    public int instanceFieldIndex(String name)
    {
        int i = 0;
        for (FieldInfo f : fields)
        {
            if (f.isStatic())
            {
                continue;
            }
            if (f.name().equals(name))
            {
                return i;
            }
            i++;
        }
        throw new IllegalArgumentException("no instance field " + name + " in " + thisClass);
    }

    /** This class's own virtual methods (non-static, non-constructor), in declaration order. */
    public java.util.List<Method> virtualMethods()
    {
        java.util.List<Method> vs = new java.util.ArrayList<>();
        for (Method m : methods)
        {
            if (!m.isStatic && !m.name.equals("<init>"))
            {
                vs.add(m);
            }
        }
        return vs;
    }

    /** One vtable slot: the class providing the implementation, plus name/descriptor. */
    public record VSlot(String owner, String name, String descriptor) {}

    /**
     * The flattened vtable for {@code cls}: superclass slots first (so a subclass
     * shares its parent's slot indices), with overrides replacing the inherited
     * slot in place and new methods appended. {@code resolve} loads each class.
     */
    public static java.util.List<VSlot> vtable(String cls, java.util.function.Function<String, ClassFile> resolve)
    {
        ClassFile cf = resolve.apply(cls);
        String sup = cf.superClass;
        java.util.List<VSlot> slots = isRoot(sup)
                                      ? new java.util.ArrayList<>() : new java.util.ArrayList<>(vtable(sup, resolve));
        for (Method m : cf.virtualMethods())
        {
            VSlot s = new VSlot(cls, m.name, m.descriptor);
            int idx = -1;
            for (int i = 0; i < slots.size(); i++)
            {
                if (slots.get(i).name().equals(m.name) && slots.get(i).descriptor().equals(m.descriptor))
                {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0)
            {
                slots.set(idx, s);
            }
            else
            {
                slots.add(s);    // override in place, or append
            }
        }
        return slots;
    }

    /** Vtable slot index of {@code name+descriptor} in {@code cls}'s flattened vtable. */
    public static int vtableSlot(String cls, String name, String descriptor,
                                 java.util.function.Function<String, ClassFile> resolve)
    {
        java.util.List<VSlot> slots = vtable(cls, resolve);
        for (int i = 0; i < slots.size(); i++)
        {
            if (slots.get(i).name().equals(name) && slots.get(i).descriptor().equals(descriptor))
            {
                return i;
            }
        }
        throw new IllegalArgumentException("no virtual method " + name + descriptor + " in " + cls);
    }

    // ----- interfaces ------------------------------------------------------
    /** An interface's methods (its abstract members), in declaration order = itable slots. */
    public java.util.List<Method> interfaceMethods()
    {
        java.util.List<Method> ms = new java.util.ArrayList<>();
        for (Method m : methods)
        {
            if (!m.isStatic && !m.name.equals("<init>") && !m.name.equals("<clinit>"))
            {
                ms.add(m);
            }
        }
        return ms;
    }

    /** itable slot of interface method {@code name+descriptor}. */
    public int interfaceSlot(String name, String descriptor)
    {
        java.util.List<Method> ms = interfaceMethods();
        for (int i = 0; i < ms.size(); i++)
        {
            if (ms.get(i).name.equals(name) && ms.get(i).descriptor.equals(descriptor))
            {
                return i;
            }
        }
        throw new IllegalArgumentException("no interface method " + name + descriptor + " in " + thisClass);
    }

    /** All interfaces {@code cls} implements, directly or via superclasses (no super-interfaces yet). */
    public static java.util.Set<String> allInterfaces(String cls, java.util.function.Function<String, ClassFile> resolve)
    {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String c = cls; !isRoot(c); c = resolve.apply(c).superClass)
        {
            for (String i : resolve.apply(c).interfaces)
            {
                out.add(i);
            }
        }
        return out;
    }

    /** The class providing {@code cls}'s implementation of {@code name+descriptor} (walk supers). */
    public static String findImpl(String cls, String name, String descriptor, java.util.function.Function<String, ClassFile> resolve)
    {
        for (String c = cls; !isRoot(c); c = resolve.apply(c).superClass)
        {
            for (Method m : resolve.apply(c).methods)
            {
                if (!m.isStatic && m.code != null && m.name.equals(name) && m.descriptor.equals(descriptor))
                {
                    return c;
                }
            }
        }
        throw new IllegalArgumentException("no implementation of " + name + descriptor + " in " + cls);
    }

    public Method method(String name, String descriptor)
    {
        for (Method m : methods)
        {
            if (m.name.equals(name) && m.descriptor.equals(descriptor))
            {
                return m;
            }
        }
        throw new IllegalArgumentException("no method " + name + descriptor + " in " + thisClass);
    }

    /** True if this class has a static initializer {@code <clinit>()V}. */
    public boolean hasClinit()
    {
        for (Method m : methods)
        {
            if (m.name.equals("<clinit>") && m.descriptor.equals("()V"))
            {
                return true;
            }
        }
        return false;
    }

    // ----- constant-pool accessors -----------------------------------------
    public String utf8(int i)
    {
        require(i, UTF8);
        return utf8[i];
    }
    public int intAt(int i)
    {
        require(i, INTEGER);
        return ref1[i];
    }
    public long longAt(int i)
    {
        require(i, LONG);
        return longVal[i];
    }
    public String stringAt(int i)
    {
        require(i, STRING);
        return utf8(ref1[i]);
    }
    public boolean isIntConst(int i)
    {
        return tag[i] == INTEGER;
    }
    public boolean isStringConst(int i)
    {
        return tag[i] == STRING;
    }
    public String classAt(int i)
    {
        require(i, CLASS);
        return utf8(ref1[i]);
    }

    /** Resolve a Methodref/InterfaceMethodref/Fieldref index to owner/name/descriptor. */
    public MemberRef memberRef(int i)
    {
        int t = tag[i];
        if (t != METHODREF && t != IFACEMETHODREF && t != FIELDREF)
        {
            throw new IllegalStateException("cp#" + i + " is not a member ref (tag " + t + ")");
        }
        String owner = classAt(ref1[i]);
        int nat = ref2[i];
        return new MemberRef(owner, utf8(ref1[nat]), utf8(ref2[nat]));
    }

    private void require(int i, int expected)
    {
        if (i <= 0 || i >= tag.length || tag[i] != expected)
        {
            throw new IllegalStateException("cp#" + i + " expected tag " + expected + " got "
                                            + (i < tag.length ? tag[i] : "oob"));
        }
    }

    // ----- parsing ---------------------------------------------------------
    // The classfile *structure* is walked by the shared, JDK-free ClassReader —
    // the same code compiled into the image and used by the on-metal loader (M5),
    // so the format is understood in exactly one place. What stays here is the
    // writer-side model built on top of it: decoded Strings, records and arrays,
    // none of which exist on the metal.

    public static ClassFile parse(Path classFile) throws IOException
    {
        return new ClassFile(Files.readAllBytes(classFile));
    }

    public ClassFile(byte[] b) throws IOException
    {
        if (ClassReader.u4(b, 0) != 0xCAFEBABE)
        {
            throw new IOException("bad classfile magic");
        }
        int n = ClassReader.cpCount(b);
        bytes = b;
        cpOff = new int[n];
        tag = new int[n];
        int afterCp = ClassReader.constantPool(b, cpOff, tag);

        ref1 = new int[n];
        ref2 = new int[n];
        longVal = new long[n];
        utf8 = new String[n];
        for (int i = 1; i < n; i++)
        {
            decodeEntry(b, i, cpOff[i]);
        }

        thisClass = classAt(ClassReader.u2(b, afterCp + 2));    // after access_flags
        int sup = ClassReader.u2(b, afterCp + 4);
        superClass = sup == 0 ? null : classAt(sup);
        int ifaceCount = ClassReader.u2(b, afterCp + 6);
        interfaces = new String[ifaceCount];
        for (int i = 0; i < ifaceCount; i++)
        {
            interfaces[i] = classAt(ClassReader.u2(b, afterCp + 8 + i * 2));
        }

        fields = readFields(b, ClassReader.fieldsStart(b, afterCp));
        methods = readMethods(b, ClassReader.methodsStart(b, afterCp));
        // class attributes ignored
    }

    /** Decode one constant-pool entry whose body starts at {@code o}. */
    private void decodeEntry(byte[] b, int i, int o) throws IOException
    {
        switch (tag[i])
        {
        case 0 -> { }                                           // second half of a Long/Double
        case UTF8 -> utf8[i] = utf8At(b, o);
        case INTEGER, FLOAT -> ref1[i] = ClassReader.u4(b, o);
        case LONG, DOUBLE ->
            longVal[i] = ((long) ClassReader.u4(b, o) << 32) | (ClassReader.u4(b, o + 4) & 0xFFFFFFFFL);
        case CLASS, STRING, METHODTYPE, MODULE, PACKAGE -> ref1[i] = ClassReader.u2(b, o);
        case FIELDREF, METHODREF, IFACEMETHODREF, NAMEANDTYPE, DYNAMIC, INVOKEDYNAMIC ->
        {
            ref1[i] = ClassReader.u2(b, o);
            ref2[i] = ClassReader.u2(b, o + 2);
        }
        case METHODHANDLE ->
        {
            ref1[i] = ClassReader.u1(b, o);
            ref2[i] = ClassReader.u2(b, o + 1);
        }
        default -> throw new IOException("unknown constant-pool tag " + tag[i] + " at #" + i);
        }
    }

    /**
     * Decode a Utf8 entry to a String. Classfiles use <em>modified</em> UTF-8, so
     * this is the same 1/2/3-byte decoding {@code DataInputStream.readUTF} did —
     * supplementary characters arrive as a surrogate pair of three-byte forms and
     * decode correctly as two chars.
     */
    private static String utf8At(byte[] b, int off)
    {
        int len = ClassReader.u2(b, off);
        StringBuilder sb = new StringBuilder(len);
        int i = off + 2;
        int end = i + len;
        while (i < end)
        {
            int c = ClassReader.u1(b, i);
            if (c < 0x80)
            {
                sb.append((char) c);
                i += 1;
            }
            else if ((c & 0xE0) == 0xC0)
            {
                sb.append((char) (((c & 0x1F) << 6) | (ClassReader.u1(b, i + 1) & 0x3F)));
                i += 2;
            }
            else
            {
                sb.append((char) (((c & 0x0F) << 12)
                                  | ((ClassReader.u1(b, i + 1) & 0x3F) << 6)
                                  | (ClassReader.u1(b, i + 2) & 0x3F)));
                i += 3;
            }
        }
        return sb.toString();
    }

    /** Read the fields table; {@code p} points at {@code fields_count}. */
    private FieldInfo[] readFields(byte[] b, int p)
    {
        int n = ClassReader.u2(b, p);
        p += 2;
        FieldInfo[] fs = new FieldInfo[n];
        for (int i = 0; i < n; i++)
        {
            int access = ClassReader.u2(b, p);
            String name = utf8(ClassReader.u2(b, p + 2));
            String desc = utf8(ClassReader.u2(b, p + 4));
            p = ClassReader.skipAttributes(b, p + 6);
            fs[i] = new FieldInfo(name, desc, (access & ACC_STATIC) != 0);
        }
        return fs;
    }

    /** Read the methods table; {@code p} points at {@code methods_count}. */
    private Method[] readMethods(byte[] b, int p)
    {
        int n = ClassReader.u2(b, p);
        p += 2;
        Method[] ms = new Method[n];
        for (int i = 0; i < n; i++)
        {
            int access = ClassReader.u2(b, p);
            String name = utf8(ClassReader.u2(b, p + 2));
            int descIndex = ClassReader.u2(b, p + 4);
            String desc = utf8(descIndex);
            int descOff = cpOff[descIndex];                 // Utf8 body offset, for the core's prologue
            int attrs = ClassReader.u2(b, p + 6);
            p += 8;
            int maxStack = 0;
            int maxLocals = 0;
            byte[] code = null;
            ExceptionEntry[] exceptions = new ExceptionEntry[0];
            for (int a = 0; a < attrs; a++)
            {
                int body = p + 6;                               // name(2) + length(4)
                if (utf8(ClassReader.u2(b, p)).equals("Code"))
                {
                    maxStack = ClassReader.u2(b, body);
                    maxLocals = ClassReader.u2(b, body + 2);
                    int codeLen = ClassReader.u4(b, body + 4);
                    code = Arrays.copyOfRange(b, body + 8, body + 8 + codeLen);
                    exceptions = readExceptions(b, body + 8 + codeLen);
                }
                p = body + ClassReader.u4(b, p + 2);            // next attribute
            }
            ms[i] = new Method(name, desc, descOff, (access & ACC_STATIC) != 0, maxStack, maxLocals, code, exceptions);
        }
        return ms;
    }

    /** Read a Code attribute's exception table; {@code p} points at its length. */
    private static ExceptionEntry[] readExceptions(byte[] b, int p)
    {
        int n = ClassReader.u2(b, p);
        ExceptionEntry[] es = new ExceptionEntry[n];
        for (int e = 0; e < n; e++)
        {
            int q = p + 2 + e * 8;
            es[e] = new ExceptionEntry(ClassReader.u2(b, q), ClassReader.u2(b, q + 2),
                                       ClassReader.u2(b, q + 4), ClassReader.u2(b, q + 6));
        }
        return es;
    }
}
