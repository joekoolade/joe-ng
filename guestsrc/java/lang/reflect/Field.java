package java.lang.reflect;

import magic.Magic;

/**
 * {@code java.lang.reflect.Field} for joe-ng: carries the declaring {@link Class}, field name, access flags,
 * and the field's type as the first char of its JVM descriptor ('I','J','Z','B','C','S','F','D','L','['), and
 * supports reflective {@link #get}/{@link #set} (plus typed {@code getInt}/{@code setInt}/...). The field's
 * byte offset within a target object is resolved at call time from the object's class ({@link #fieldOffset0} ->
 * {@code VM.vhFieldOffset}, the same loader field registry the VarHandle/atomic-updater overlays use).
 * Access enforcement ({@code setAccessible} + the member-access rules) lands in reflection arc M2.
 */
public final class Field extends AccessibleObject
{
    private final Class<?> clazz;
    private final String name;
    private final byte[] nameBytes;
    private final int modifiers;
    private final int typeChar;

    public Field(Class<?> clazz, String name, int modifiers, int typeChar)
    {
        this.clazz = clazz;
        this.name = name;
        this.nameBytes = name.getBytes();
        this.modifiers = modifiers;
        this.typeChar = typeChar;
    }

    public String getName()
    {
        return name;
    }

    public int getModifiers()
    {
        return modifiers;
    }

    public Class<?> getDeclaringClass()
    {
        return clazz;
    }

    /** First char of the field's JVM type descriptor: 'I' int, 'J' long, 'Z' boolean, 'L'/'[' reference. */
    public int getTypeChar()
    {
        return typeChar;
    }

    /** Byte offset of {@code nameBytes} within {@code obj}'s class -> {@code VM.vhFieldOffset} (loader field registry). */
    private static native long fieldOffset0(byte[] fname, Object obj);

    /** Absolute address of this field's slot in {@code obj}. */
    private long addr(Object obj)
    {
        return Magic.addrOf(obj) + fieldOffset0(nameBytes, obj);
    }

    // ---- reflective get/set: the field's value, boxed (get) / unboxed (set) per the declared type ----

    public Object get(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        if (typeChar == 'I') { return Integer.valueOf(Magic.load32(a)); }
        if (typeChar == 'J') { return Long.valueOf(Magic.load64(a)); }
        if (typeChar == 'Z') { return Boolean.valueOf(Magic.load32(a) != 0); }
        if (typeChar == 'B') { return Byte.valueOf((byte) Magic.load32(a)); }
        if (typeChar == 'C') { return Character.valueOf((char) Magic.load32(a)); }
        if (typeChar == 'S') { return Short.valueOf((short) Magic.load32(a)); }
        return Magic.fromAddr(Magic.load64(a));                              // 'L' / '[' reference
    }

    public void set(Object obj, Object value) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        if (typeChar == 'I') { Magic.store32(a, ((Integer) value).intValue()); return; }
        if (typeChar == 'J') { Magic.store64(a, ((Long) value).longValue()); return; }
        if (typeChar == 'Z') { Magic.store32(a, ((Boolean) value).booleanValue() ? 1 : 0); return; }
        if (typeChar == 'B') { Magic.store32(a, ((Byte) value).byteValue()); return; }
        if (typeChar == 'C') { Magic.store32(a, ((Character) value).charValue()); return; }
        if (typeChar == 'S') { Magic.store32(a, ((Short) value).shortValue()); return; }
        Magic.store64(a, value == null ? 0L : Magic.addrOf(value));         // 'L' / '[' reference
    }

    public int getInt(Object obj) throws IllegalAccessException
    {
        return Magic.load32(addr(obj));
    }

    public void setInt(Object obj, int v) throws IllegalAccessException
    {
        Magic.store32(addr(obj), v);
    }

    public long getLong(Object obj) throws IllegalAccessException
    {
        return Magic.load64(addr(obj));
    }

    public void setLong(Object obj, long v) throws IllegalAccessException
    {
        Magic.store64(addr(obj), v);
    }

    public boolean getBoolean(Object obj) throws IllegalAccessException
    {
        return Magic.load32(addr(obj)) != 0;
    }

    /**
     * The NARROW primitive reads/writes. A byte/short/char field occupies a full 8-byte slot in joe-ng's
     * object layout, so the value is read as a word and narrowed by the cast -- the cast is what applies the
     * sign extension for byte/short and the zero extension for char, which is exactly the stock contract.
     *
     * <p>{@code float}/{@code double} are deliberately absent: their values arrive in the FP registers, which
     * joe-ng's reflective marshalling does not carry, so a plausible-looking {@code getFloat} would return a
     * wrong number rather than fail. Left in the overlay backlog instead -- see {@code make overlaycheck}.
     */
    public byte getByte(Object obj) throws IllegalAccessException
    {
        return (byte) Magic.load32(addr(obj));
    }

    public void setByte(Object obj, byte v) throws IllegalAccessException
    {
        Magic.store32(addr(obj), v);
    }

    public short getShort(Object obj) throws IllegalAccessException
    {
        return (short) Magic.load32(addr(obj));
    }

    public void setShort(Object obj, short v) throws IllegalAccessException
    {
        Magic.store32(addr(obj), v);
    }

    public char getChar(Object obj) throws IllegalAccessException
    {
        return (char) Magic.load32(addr(obj));
    }

    public void setChar(Object obj, char v) throws IllegalAccessException
    {
        Magic.store32(addr(obj), v);
    }

    /**
     * The annotation INSTANCE on this field, or null.
     *
     * <p>Field annotations are how libraries declare CONFIGURATION -- picocli's {@code @Option} and
     * {@code @Parameters} live on fields -- so without this a command spec has no options at all: JUnit's
     * launcher printed "Unknown options" for every argument and a usage block listing none.
     *
     * <p>The BOUND is load-bearing: {@code <T extends Annotation>} erases the return to
     * {@code Ljava/lang/annotation/Annotation;}, the descriptor stock callers reference. A plain {@code <T>}
     * erases to {@code Object} and is a DIFFERENT METHOD that resolves nowhere.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) fieldAnnoGet0(clazz, nameBytes, descriptorOf(anno));
    }

    @Override
    public boolean isAnnotationPresent(Class<?> anno)
    {
        return anno != null && fieldAnnoGet0(clazz, nameBytes, descriptorOf(anno)) != null;
    }

    /** {@code com.x.Foo} -> the bytes of {@code Lcom/x/Foo;}, the form the classfile stores. */
    private static byte[] descriptorOf(Class<?> anno)
    {
        String n = anno.getName();
        byte[] out = new byte[n.length() + 2];
        out[0] = (byte) 'L';
        for (int i = 0; i < n.length(); i++)
        {
            char c = n.charAt(i);
            out[i + 1] = (byte) (c == '.' ? '/' : c);
        }
        out[out.length - 1] = (byte) ';';
        return out;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.fieldAnnoGet}): mirror + field name + descriptor. */
    private static native Object fieldAnnoGet0(Class<?> c, byte[] fieldName, byte[] descriptor);

    /**
     * The field's declared type. Resolved from the classfile DESCRIPTOR rather than from the stored type CHAR,
     * which cannot name a reference type -- picocli reads it to decide how to convert an option's argument, so
     * answering {@code Object} for every reference field would be worse than not answering at all.
     */
    public Class<?> getType()
    {
        return (Class<?>) fieldType0(clazz, nameBytes);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.fieldType}): mirror + field name -> Class. */
    private static native Object fieldType0(Class<?> c, byte[] fieldName);

    /** ACC_SYNTHETIC -- a compiler-generated field (an outer-instance {@code this$0}, a switch map). */
    public boolean isSynthetic()
    {
        return (modifiers & 0x1000) != 0;
    }

    public boolean isEnumConstant()
    {
        return (modifiers & 0x4000) != 0;               // ACC_ENUM
    }

    public void setBoolean(Object obj, boolean v) throws IllegalAccessException
    {
        Magic.store32(addr(obj), v ? 1 : 0);
    }
}
