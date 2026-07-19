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
        public final int maxStack;
        public final int maxLocals;
        public final boolean isStatic;
        public final byte[] code;
        public final ExceptionEntry[] exceptions;
        Method(String name, String descriptor, boolean isStatic, int maxStack, int maxLocals,
               byte[] code, ExceptionEntry[] exceptions)
        {
            this.name = name;
            this.descriptor = descriptor;
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
    public static ClassFile parse(Path classFile) throws IOException
    {
        return new ClassFile(Files.readAllBytes(classFile));
    }

    public ClassFile(byte[] bytes) throws IOException
    {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE)
        {
            throw new IOException("bad classfile magic");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();        // minor, major

        int cpCount = in.readUnsignedShort();
        tag = new int[cpCount];
        ref1 = new int[cpCount];
        ref2 = new int[cpCount];
        longVal = new long[cpCount];
        utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++)
        {
            int t = in.readUnsignedByte();
            tag[i] = t;
            switch (t)
            {
            case UTF8 -> utf8[i] = in.readUTF();
            case INTEGER, FLOAT -> ref1[i] = in.readInt();
            case LONG, DOUBLE ->
            {
                longVal[i] = in.readLong();
                i++;
            }  // takes two slots
                case CLASS, STRING, METHODTYPE, MODULE, PACKAGE -> ref1[i] = in.readUnsignedShort();
            case FIELDREF, METHODREF, IFACEMETHODREF, NAMEANDTYPE, DYNAMIC, INVOKEDYNAMIC ->
            {
                ref1[i] = in.readUnsignedShort();
                ref2[i] = in.readUnsignedShort();
            }
            case METHODHANDLE ->
            {
                ref1[i] = in.readUnsignedByte();
                ref2[i] = in.readUnsignedShort();
            }
                    default -> throw new IOException("unknown constant-pool tag " + t + " at #" + i);
            }
        }

        in.readUnsignedShort();                                // access_flags
        thisClass = classAt(in.readUnsignedShort());
        int sup = in.readUnsignedShort();
        superClass = sup == 0 ? null : classAt(sup);
        int ifaceCount = in.readUnsignedShort();
        interfaces = new String[ifaceCount];
        for (int i = 0; i < ifaceCount; i++)
        {
            interfaces[i] = classAt(in.readUnsignedShort());
        }

        fields = readFields(in);
        methods = readMethods(in);
        // class attributes ignored
    }

    private FieldInfo[] readFields(DataInputStream in) throws IOException
    {
        int n = in.readUnsignedShort();
        FieldInfo[] fs = new FieldInfo[n];
        for (int i = 0; i < n; i++)
        {
            int access = in.readUnsignedShort();
            String name = utf8(in.readUnsignedShort());
            String desc = utf8(in.readUnsignedShort());
            skipAttributes(in);
            fs[i] = new FieldInfo(name, desc, (access & ACC_STATIC) != 0);
        }
        return fs;
    }

    private Method[] readMethods(DataInputStream in) throws IOException
    {
        int n = in.readUnsignedShort();
        Method[] ms = new Method[n];
        for (int i = 0; i < n; i++)
        {
            int access = in.readUnsignedShort();
            String name = utf8(in.readUnsignedShort());
            String desc = utf8(in.readUnsignedShort());
            int attrs = in.readUnsignedShort();
            int maxStack = 0;
            int maxLocals = 0;
            byte[] code = null;
            ExceptionEntry[] exceptions = new ExceptionEntry[0];
            for (int a = 0; a < attrs; a++)
            {
                String an = utf8(in.readUnsignedShort());
                int len = in.readInt();
                if (an.equals("Code"))
                {
                    maxStack = in.readUnsignedShort();
                    maxLocals = in.readUnsignedShort();
                    int codeLen = in.readInt();
                    code = new byte[codeLen];
                    in.readFully(code);
                    int exc = in.readUnsignedShort();
                    exceptions = new ExceptionEntry[exc];
                    for (int e = 0; e < exc; e++)
                    {
                        exceptions[e] = new ExceptionEntry(in.readUnsignedShort(), in.readUnsignedShort(),
                                                           in.readUnsignedShort(), in.readUnsignedShort());
                    }
                    skipAttributes(in);
                }
                else
                {
                    in.skipBytes(len);
                }
            }
            ms[i] = new Method(name, desc, (access & ACC_STATIC) != 0, maxStack, maxLocals, code, exceptions);
        }
        return ms;
    }

    private void skipAttributes(DataInputStream in) throws IOException
    {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++)
        {
            in.readUnsignedShort();                            // name index
            int len = in.readInt();
            in.skipBytes(len);
        }
    }
}
