/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-11
 */
package java.lang.reflect;

import magic.Magic;

/**
 * {@code java.lang.reflect.Field} for joe-ng: carries the declaring {@link Class}, field name, access flags,
 * and the field's type as the first char of its JVM descriptor ('I','J','Z','B','C','S','F','D','L','['), and
 * supports reflective {@link #get}/{@link #set} (plus typed {@code getInt}/{@code setInt}/...). The field's
 * byte offset is resolved at call time from the DECLARING class ({@link #fieldOffsetOfClass0}, the lookup
 * {@code Unsafe.objectFieldOffset} uses).
 * Every accessor enforces member access, the receiver, the field's type and stock's final-field rule.
 */
public final class Field extends AccessibleObject implements Member
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
        this.nameBytes = jdk.internal.misc.MetalNames.utf8(name);
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

    /**
     * VM native ({@code Loader.nativeBuf} -> {@code VMNatives.unsafeFieldOffset}, the one {@code Unsafe} uses): the
     * byte offset of instance field {@code fname} declared by {@code c}, or -1. Keyed on the DECLARING class, not
     * on the receiver. The receiver-keyed lookup walked up from the object's own class, so a subclass declaring
     * a field of the same name had ITS field read and written instead (FieldSemanticsProbe).
     */
    private static native long fieldOffsetOfClass0(Class<?> c, byte[] fname);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.staticCell}): the ADDRESS of a STATIC field's
     *  cell, or 0. Statics are not in the loader's field registry -- that maps a field to an OFFSET within an
     *  object -- so they resolve through the statics table instead. */
    private static native long staticCell0(Class<?> c, byte[] fname);

    /**
     * The address of this field's slot for {@code obj}, after stock's receiver checks. A static ignores
     * {@code obj}, as stock does. An instance field needs a receiver that IS an instance of the declaring
     * class: null is {@code NullPointerException}, anything else {@code IllegalArgumentException}.
     *
     * <p>The instance check is not a formality. The offset used to be found by NAME in the receiver's class, so
     * an object of an unrelated class with a same-named field had that field read and written.
     *
     * <p>Messages are the bare field name, with no concatenation: concatenation lowers to invokedynamic and would
     * pull the string-concat machinery into java.base's bake closure from a path that must stay cold.
     */
    private long addr(Object obj)
    {
        if (Modifier.isStatic(modifiers))
        {
            long cell = staticCell0(clazz, nameBytes);
            if (cell == 0L)
            {
                throw new IllegalArgumentException(name);
            }
            return cell;
        }
        if (obj == null)
        {
            throw new NullPointerException(name);
        }
        if (!clazz.isInstance(obj))
        {
            throw new IllegalArgumentException(name);
        }
        long off = fieldOffsetOfClass0(clazz, nameBytes);
        if (off < 0L)
        {
            // A MISS MUST NOT BE ADDED TO THE OBJECT: obj-1 is inside the header, and a set would write there.
            throw new IllegalArgumentException(name);
        }
        return Magic.addrOf(obj) + off;
    }

    /**
     * As {@link #addr}, for a WRITE, with stock's rule for a {@code final} field: writable only when
     * {@code setAccessible(true)} was called, the field is an instance field, and the class is not a record.
     * (Stock also refuses a hidden class; this VM makes none.) A static final is never writable -- a
     * {@code static final} String constant could otherwise be rewritten without even a {@code setAccessible}.
     */
    private long writableAddr(Object obj) throws IllegalAccessException
    {
        long a = addr(obj);
        if (Modifier.isFinal(modifiers) && (!override || Modifier.isStatic(modifiers) || clazz.isRecord()))
        {
            throw new IllegalAccessException(name);
        }
        return a;
    }

    /**
     * JLS 5.1.2: may a value of primitive type {@code from} be stored as, or read as, {@code to}? Identity or a
     * widening; {@code boolean} converts to nothing else and {@code char} only upwards from {@code int}. Both are
     * descriptor chars; a reference type ('L', '[') converts to no primitive at all.
     */
    private static boolean widens(int from, int to)
    {
        if (from == to)
        {
            return from != 'L' && from != '[';
        }
        if (from == 'B')
        {
            return to == 'S' || to == 'I' || to == 'J' || to == 'F' || to == 'D';
        }
        if (from == 'S' || from == 'C')
        {
            return to == 'I' || to == 'J' || to == 'F' || to == 'D';
        }
        if (from == 'I')
        {
            return to == 'J' || to == 'F' || to == 'D';
        }
        if (from == 'J')
        {
            return to == 'F' || to == 'D';
        }
        if (from == 'F')
        {
            return to == 'D';
        }
        return false;
    }

    /**
     * An integral or boolean field's value, in its own range. The slot holds it extended to 64 bits (see
     * {@link #slot}); the narrowing cast is what restores sign or zero extension for the field's type.
     */
    private long integral(long a)
    {
        long bits = Magic.load64(a);
        if (typeChar == 'B')
        {
            return (byte) bits;
        }
        if (typeChar == 'S')
        {
            return (short) bits;
        }
        if (typeChar == 'C')
        {
            return (char) bits;
        }
        if (typeChar == 'I')
        {
            return (int) bits;
        }
        if (typeChar == 'Z')
        {
            return ((int) bits) != 0 ? 1L : 0L;
        }
        return bits;
    }

    /** Refuse a typed read as {@code as} unless this field's type widens to it. */
    private void readableAs(int as)
    {
        if (!widens(typeChar, as))
        {
            throw new IllegalArgumentException(name);
        }
    }

    /** Store {@code v}, a value of integral type {@code from}, after checking it widens to this field's type. */
    private void putIntegral(long a, int from, long v)
    {
        if (!widens(from, typeChar))
        {
            throw new IllegalArgumentException(name);
        }
        if (typeChar == 'F')
        {
            floatSlot(a, (float) v);
            return;
        }
        if (typeChar == 'D')
        {
            Magic.store64(a, Double.doubleToRawLongBits((double) v));
            return;
        }
        slot(a, v);
    }

    // ---- reflective get/set. Every public accessor checks access against ITS caller first (Magic.readLR is
    // ---- the caller's return address only in the accessor itself), then the receiver, then the type.

    public Object get(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        if (typeChar == 'I')
        {
            return Integer.valueOf((int) integral(a));
        }
        if (typeChar == 'J')
        {
            return Long.valueOf(integral(a));
        }
        if (typeChar == 'Z')
        {
            return Boolean.valueOf(integral(a) != 0L);
        }
        if (typeChar == 'B')
        {
            return Byte.valueOf((byte) integral(a));
        }
        if (typeChar == 'C')
        {
            return Character.valueOf((char) integral(a));
        }
        if (typeChar == 'S')
        {
            return Short.valueOf((short) integral(a));
        }
        if (typeChar == 'F')
        {
            return Float.valueOf(Float.intBitsToFloat(Magic.load32(a)));
        }
        if (typeChar == 'D')
        {
            return Double.valueOf(Double.longBitsToDouble(Magic.load64(a)));
        }
        return Magic.fromAddr(Magic.load64(a));
    }

    /**
     * Stock's {@code set}: a reference field takes null or an instance of its type -- an {@code Integer} into a
     * {@code String} field used to be stored, leaving a non-String where compiled code trusts the type -- and a
     * primitive field takes a box whose primitive widens to it. Anything else, null for a primitive included,
     * is {@code IllegalArgumentException}, never the {@code ClassCastException} of a cast.
     */
    public void set(Object obj, Object value) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = writableAddr(obj);
        if (typeChar == 'L' || typeChar == '[')
        {
            Class<?> ft = getType();
            if (value != null && ft != null && !ft.isInstance(value))
            {
                throw new IllegalArgumentException(name);
            }
            Magic.store64(a, value == null ? 0L : Magic.addrOf(value));
            return;
        }
        if (value instanceof Integer v)
        {
            putIntegral(a, 'I', v.intValue());
        }
        else if (value instanceof Long v)
        {
            putIntegral(a, 'J', v.longValue());
        }
        else if (value instanceof Short v)
        {
            putIntegral(a, 'S', v.shortValue());
        }
        else if (value instanceof Byte v)
        {
            putIntegral(a, 'B', v.byteValue());
        }
        else if (value instanceof Character v)
        {
            putIntegral(a, 'C', v.charValue());
        }
        else if (value instanceof Boolean v)
        {
            putIntegral(a, 'Z', v.booleanValue() ? 1L : 0L);
        }
        else if (value instanceof Float v)
        {
            putFloat(a, v.floatValue());
        }
        else if (value instanceof Double v)
        {
            putDouble(a, v.doubleValue());
        }
        else
        {
            throw new IllegalArgumentException(name);
        }
    }

    /**
     * A narrow value into its field's WHOLE 8-byte slot, extended the way compiled {@code putfield} leaves it --
     * sign-extended for int/short/byte, zero-extended for char/boolean (the caller's Java widening does the
     * extending). Compiled {@code getfield} reads all 64 bits, so a 32-bit store over a negative value left its
     * {@code 0xFFFFFFFF} high half behind: MEASURED, {@code setInt(p, 5)} over {@code -1} read back as a value that
     * passed {@code i == 5} (a 32-bit compare) and gave {@code i / 5 = -858993458} (FieldPrimProbe).
     */
    private static void slot(long a, long extended)
    {
        Magic.store64(a, extended);
    }

    /** A float's raw bits, zero-extended into the slot -- what a compiled {@code putfield} of an {@code F} leaves. */
    private static void floatSlot(long a, float v)
    {
        Magic.store64(a, Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    /** A float into a float or double field; anything else refuses. */
    private void putFloat(long a, float v)
    {
        if (typeChar == 'F')
        {
            floatSlot(a, v);
            return;
        }
        if (typeChar == 'D')
        {
            Magic.store64(a, Double.doubleToRawLongBits((double) v));
            return;
        }
        throw new IllegalArgumentException(name);
    }

    /** A double into a double field only. */
    private void putDouble(long a, double v)
    {
        if (typeChar != 'D')
        {
            throw new IllegalArgumentException(name);
        }
        Magic.store64(a, Double.doubleToRawLongBits(v));
    }

    /**
     * The typed accessors, with stock's widening: a getter reads any field whose type widens to its own
     * ({@code getLong} on an {@code int}), a setter writes any field its own type widens to ({@code setInt} on a
     * {@code long}). Each used to read or write the slot whatever the field's type -- {@code getInt} on a
     * {@code long} answered its low half, and {@code setInt} on a REFERENCE field wrote an int where the
     * collector expects a pointer.
     */
    public boolean getBoolean(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('Z');
        return integral(a) != 0L;
    }

    public byte getByte(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('B');
        return (byte) integral(a);
    }

    public char getChar(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('C');
        return (char) integral(a);
    }

    public short getShort(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('S');
        return (short) integral(a);
    }

    public int getInt(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('I');
        return (int) integral(a);
    }

    public long getLong(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('J');
        return integral(a);
    }

    public float getFloat(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('F');
        if (typeChar == 'F')
        {
            return Float.intBitsToFloat(Magic.load32(a));
        }
        return (float) integral(a);
    }

    public double getDouble(Object obj) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        long a = addr(obj);
        readableAs('D');
        if (typeChar == 'D')
        {
            return Double.longBitsToDouble(Magic.load64(a));
        }
        if (typeChar == 'F')
        {
            return (double) Float.intBitsToFloat(Magic.load32(a));
        }
        return (double) integral(a);
    }

    public void setBoolean(Object obj, boolean v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putIntegral(writableAddr(obj), 'Z', v ? 1L : 0L);
    }

    public void setByte(Object obj, byte v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putIntegral(writableAddr(obj), 'B', v);
    }

    public void setChar(Object obj, char v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putIntegral(writableAddr(obj), 'C', v);
    }

    public void setShort(Object obj, short v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putIntegral(writableAddr(obj), 'S', v);
    }

    public void setInt(Object obj, int v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putIntegral(writableAddr(obj), 'I', v);
    }

    public void setLong(Object obj, long v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putIntegral(writableAddr(obj), 'J', v);
    }

    public void setFloat(Object obj, float v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putFloat(writableAddr(obj), v);
    }

    public void setDouble(Object obj, double v) throws IllegalAccessException
    {
        checkAccess(clazz, modifiers, Magic.readLR());
        putDouble(writableAddr(obj), v);
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

    /**
     * The annotation of type {@code anno} DECLARED on this field, or null.
     *
     * <p>For a FIELD "declared" and "present" coincide exactly, so this is exact rather than approximate:
     * {@code @Inherited} has effect on CLASS declarations alone (JLS 9.6.4.3), and a field is not inherited
     * in a way that carries annotations. So this shares {@link #getAnnotation}'s path and diverges from stock
     * nowhere -- the same argument recorded for {@code Method}, checked rather than assumed from symmetry.
     *
     * <p>The BOUND is load-bearing for the same reason as on {@link #getAnnotation}: it erases the return to
     * {@code Ljava/lang/annotation/Annotation;}, which is the descriptor JUnit's
     * {@code AnnotationUtils.findAnnotation} references. Reached by LATE dispatch, since {@code Field} does
     * not declare {@code AnnotatedElement}.
     *
     * <p>NOT ADDED HERE, and stated rather than left to be discovered: the PLURAL pair
     * {@code getDeclaredAnnotations()}/{@code getAnnotations()}, which {@code findAnnotation} uses to walk
     * META-annotations. Those need a field-level enumeration native ({@code classAnnotationsAll} and
     * {@code methodAnnotationsAll} exist; the field twin does not), which is its own increment. If the
     * launcher stops on one of them next, that is why.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getDeclaredAnnotation(Class<T> anno)
    {
        return getAnnotation(anno);
    }

    /**
     * Every annotation DECLARED on this field. Never null; empty when there are none.
     *
     * <p>{@code AnnotationUtils.findAnnotation} walks this to reach META-ANNOTATIONS -- an annotation carried
     * by another annotation -- and walks it UNGUARDED, so answering null would NPE inside JUnit while
     * answering empty silently reports "not annotated". It enumerates.
     *
     * <p>A fresh array each call, because stock specifies the caller may modify the returned one.
     */
    public java.lang.annotation.Annotation[] getDeclaredAnnotations()
    {
        java.lang.annotation.Annotation[] a = fieldAnnoAll0(clazz, nameBytes);
        return a == null ? new java.lang.annotation.Annotation[0] : a;
    }

    /**
     * As {@link #getDeclaredAnnotations}. For a FIELD the two coincide exactly -- {@code @Inherited} has
     * effect on class declarations alone (JLS 9.6.4.3) -- so this shares the declared path and diverges from
     * stock nowhere.
     */
    public java.lang.annotation.Annotation[] getAnnotations()
    {
        return getDeclaredAnnotations();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.fieldAnnoAll}). */
    private static native java.lang.annotation.Annotation[] fieldAnnoAll0(Class<?> c, byte[] fieldName);

    @Override
    public boolean isAnnotationPresent(Class<? extends java.lang.annotation.Annotation> anno)
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

    /**
     * Stock's: the type as this field's {@code Signature} declares it -- {@code List<String>}, a type variable,
     * a generic array -- or the erased {@link #getType} when it has none, which is stock's answer for a
     * non-generic field. It answered the ERASURE always while no signature was read: for {@code List<String>}
     * a caller asking {@code instanceof ParameterizedType} took its raw path and lost the element type.
     */
    public java.lang.reflect.Type getGenericType()
    {
        String sig = signature0(clazz, nameBytes);
        if (sig == null)
        {
            return getType();
        }
        Class<?> c = getDeclaringClass();
        return sun.reflect.generics.repository.FieldRepository.make(sig,
                sun.reflect.generics.factory.CoreReflectionFactory.make(c,
                        sun.reflect.generics.scope.ClassScope.make(c))).getGenericType();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.fieldSignature}): mirror + name -> Signature. */
    private static native String signature0(Class<?> c, byte[] fieldName);

    /** Stock's: same declaring class, name and type (see {@link Method#equals} for why it is needed). */
    public boolean equals(Object obj)
    {
        if (obj instanceof Field other)
        {
            return (getDeclaringClass() == other.getDeclaringClass())
                && (getName().equals(other.getName()))
                && (getType() == other.getType());
        }
        return false;
    }

    /** Stock's. */
    public int hashCode()
    {
        return getDeclaringClass().getName().hashCode() ^ getName().hashCode();
    }

    /**
     * {@code "public java.lang.String com.x.Foo.bar"} -- modifiers, type, declaring class, name.
     *
     * <p>Built directly rather than through {@code Modifier.toString}, to avoid pulling the reflection
     * modifier machinery for a string. The modifier ORDER is the one the JLS specifies and stock follows, so
     * the output matches for every field joe-ng can describe; the erasure is used for the type, matching
     * {@link #getGenericType()}.
     *
     * <p>picocli calls it while building an {@code ArgSpec} -- an option's own description -- so a missing
     * one stops option construction entirely rather than merely degrading a message.
     */
    public String toGenericString()
    {
        StringBuilder b = new StringBuilder();
        int m = modifiers;
        if ((m & 0x0001) != 0) { b.append("public "); }
        if ((m & 0x0002) != 0) { b.append("private "); }
        if ((m & 0x0004) != 0) { b.append("protected "); }
        if ((m & 0x0008) != 0) { b.append("static "); }
        if ((m & 0x0010) != 0) { b.append("final "); }
        if ((m & 0x0040) != 0) { b.append("volatile "); }
        if ((m & 0x0080) != 0) { b.append("transient "); }
        Class<?> t = getType();
        b.append(t == null ? "java.lang.Object" : t.getTypeName());
        b.append(' ');
        b.append(clazz == null ? "?" : clazz.getName());
        b.append('.');
        b.append(name);
        return b.toString();
    }

    @Override
    public String toString()
    {
        return toGenericString();
    }

    /** ACC_SYNTHETIC -- a compiler-generated field (an outer-instance {@code this$0}, a switch map). */
    public boolean isSynthetic()
    {
        return (modifiers & 0x1000) != 0;
    }

    public boolean isEnumConstant()
    {
        return (modifiers & 0x4000) != 0;               // ACC_ENUM
    }
}
