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

    public void setBoolean(Object obj, boolean v) throws IllegalAccessException
    {
        Magic.store32(addr(obj), v ? 1 : 0);
    }
}
