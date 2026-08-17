package writer;

import java.lang.reflect.Field;

/**
 * M8 full bootstrap (path 1), static state: read a stock java.base class's initialized static
 * field values off the <em>seed JVM</em>. A {@code bakeNoClinit} class's {@code <clinit>} cannot
 * run at build time under our own compiler (class literals, natives — e.g.
 * {@code StringUTF16.<clinit>} calls the native {@code isBigEndian()}), but the writer itself runs
 * on a seed JVM where that class is already initialized — so the writer snapshots the resulting
 * values into the image's statics area instead of running the initializer.
 *
 * <p>First increment: PRIMITIVE statics only. Object statics (arrays, Strings) need the referenced
 * object baked into the image heap too (a deep snapshot) and are left zero for now.
 *
 * <p>Needs {@code --add-opens java.base/java.lang=ALL-UNNAMED} on the writer's JVM: the snapshotted
 * fields are private members of java.base classes.
 */
final class StaticSnapshot
{
    private StaticSnapshot()
    {
    }

    /**
     * The seed JVM's value of the static field {@code fieldKey} ("owner/Class.name") as raw 64-bit
     * slot bits (booleans as 0/1, floats/doubles as their IEEE bits), or {@code null} for a
     * reference-typed field (baked separately — see {@link #reference}). Loading the owner triggers
     * its {@code <clinit>} on the seed JVM. Any reflection failure fails the build: a silently
     * missing snapshot would surface on the metal as an invisibly wrong static value.
     */
    static Long primitiveBits(String fieldKey)
    {
        try
        {
            Field f = field(fieldKey);
            if (!f.getType().isPrimitive())
            {
                return null;
            }
            return valueBits(f, null);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("static snapshot failed for " + fieldKey
                    + " (writer needs --add-opens java.base/java.lang=ALL-UNNAMED)", e);
        }
    }

    /**
     * The seed JVM's value of the REFERENCE-typed static field {@code fieldKey} — the host object the
     * writer bakes into the image — or {@code null} when the field is primitive-typed (that's
     * {@link #primitiveBits}'s half) or actually holds null (slot 0 IS the null reference).
     */
    static Object reference(String fieldKey)
    {
        try
        {
            Field f = field(fieldKey);
            if (f.getType().isPrimitive())
            {
                return null;
            }
            return f.get(null);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("static snapshot failed for " + fieldKey
                    + " (writer needs --add-opens java.base/java.lang=ALL-UNNAMED)", e);
        }
    }

    /** The seed JVM's value of {@code o}'s PRIMITIVE instance field {@code name} as raw 64-bit slot
     *  bits — the shape a compiled putfield would have stored into the 8-byte field slot. */
    static long instanceBits(Object o, String name)
    {
        try
        {
            return valueBits(findField(o.getClass(), name), o);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("instance snapshot failed for "
                    + o.getClass().getName() + "." + name, e);
        }
    }

    /** The seed JVM's value of {@code o}'s REFERENCE-typed instance field {@code name}. */
    static Object instanceRef(Object o, String name)
    {
        try
        {
            return findField(o.getClass(), name).get(o);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("instance snapshot failed for "
                    + o.getClass().getName() + "." + name, e);
        }
    }

    /** {@code target}'s value of the primitive field {@code f} as raw 64-bit slot bits (booleans as
     *  0/1, chars zero-extended, other integrals sign-extended, floats/doubles as their IEEE bits). */
    private static long valueBits(Field f, Object target) throws ReflectiveOperationException
    {
        Class<?> t = f.getType();
        if (t == int.class)
        {
            return (long) f.getInt(target);
        }
        if (t == long.class)
        {
            return f.getLong(target);
        }
        if (t == boolean.class)
        {
            return f.getBoolean(target) ? 1L : 0L;
        }
        if (t == byte.class)
        {
            return (long) f.getByte(target);
        }
        if (t == char.class)
        {
            return (long) f.getChar(target);
        }
        if (t == short.class)
        {
            return (long) f.getShort(target);
        }
        if (t == float.class)
        {
            return (long) Float.floatToRawIntBits(f.getFloat(target));
        }
        return Double.doubleToRawLongBits(f.getDouble(target));
    }

    /** Resolve "owner/Class.name" to its accessible {@link Field}, initializing the owner. */
    private static Field field(String fieldKey) throws ReflectiveOperationException
    {
        int dot = fieldKey.lastIndexOf('.');
        String owner = fieldKey.substring(0, dot).replace('/', '.');
        String name = fieldKey.substring(dot + 1);
        Field f = Class.forName(owner).getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Find instance field {@code name} on {@code c} or a superclass, made accessible. */
    private static Field findField(Class<?> c, String name) throws ReflectiveOperationException
    {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
        {
            try
            {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            }
            catch (NoSuchFieldException e)
            {
                continue;
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }
}
