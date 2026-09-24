/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-17
 */
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
     * The seed {@code jdk.internal.misc.CDS.getRandomSeedForDumping()} answers for THIS build.
     *
     * <p>Stock uses that hook for exactly this problem, and its javadoc says so: "the VM will supply a
     * 'random' seed that's derived from the JVM build/version, so can we generate the exact same CDS
     * archive for the same JDK build." A joe-ng image IS a dumped archive, so the writer is the dumper
     * and supplies the seed. HotSpot's {@code JVM_GetRandomSeedForDumping} hashes the version strings
     * and guards the result against zero (zero means "not dumping"); this mirrors both halves.
     *
     * <p>BUMP {@link #BUILD_ID} to reshuffle immutable-set/map iteration order. That is the discipline the
     * randomisation exists for -- code must not depend on that order -- and per-BUILD is the strongest
     * form available here, because the value is baked: within one image it is constant however it is
     * chosen, so a per-run draw could never have served the purpose anyway.
     */
    private static final String BUILD_ID = "joe-ng";

    static final long CDS_DUMP_SEED = dumpSeed();

    private static long dumpSeed()
    {
        long seed = BUILD_ID.hashCode();
        if (seed == 0L)
        {
            seed = 0x87654321L;            // HotSpot's own guard: never hand back "not dumping"
        }
        return seed;
    }

    /**
     * {@code java/util/ImmutableCollections.SALT32L} as that class's own initializer would compute it
     * from {@link #CDS_DUMP_SEED} -- stock's formula, copied rather than approximated.
     *
     * <p>WHY THE WRITER COMPUTES IT INSTEAD OF READING IT. The snapshot reads the HOST's already-
     * initialized class, and on the host {@code getRandomSeedForDumping()} is the REAL native, which
     * answers 0 because the host is not dumping -- so the host's initializer fell through to
     * {@code System.nanoTime()} and the writer baked a CLOCK READING. That is what made two builds of an
     * identical tree differ. The field is {@code private static final} on an already-initialized class,
     * so nothing can rewrite it after the fact; computing the value the seed implies is the only way to
     * bake what the metal initializer will independently arrive at.
     */
    private static long salt32L()
    {
        long color = 0x243F_6A88_85A3_08D3L;                       // slice of pi, stock's constant
        return (int) ((color * CDS_DUMP_SEED) >> 16) & 0xFFFF_FFFFL;
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
        // The two cells the host cannot answer deterministically -- see salt32L(). Substituted BEFORE the
        // reflection, so the host's clock-derived value is never even read.
        if (fieldKey.equals("java/util/ImmutableCollections.SALT32L"))
        {
            return salt32L();
        }
        if (fieldKey.equals("java/util/ImmutableCollections.REVERSE"))
        {
            return (salt32L() & 1L) == 0L ? 1L : 0L;     // stock: REVERSE = (SALT32L & 1) == 0
        }
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
