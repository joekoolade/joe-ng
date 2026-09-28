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
package java.lang;

import jdk.internal.misc.CDS;

/**
 * A JDK-free, minimal {@code java/lang/Short} overlay -- it shadows the stock class entirely (overlay wins by
 * name), so a stock member it does not declare CEASES TO EXIST. {@code valueOf} interns per JLS 5.1.7 from the
 * nested {@code ShortCache} below, which is stock's own shape: an eagerly filled array that adopts a
 * writer-baked one.
 *
 * <p>A CORRECTION TO THIS COMMENT'S OWN EARLIER CLAIM, recorded rather than quietly replaced. It used to say
 * the stock {@code <clinit>} setting {@code TYPE = Class.getPrimitiveClass("short")} gets the class blocked,
 * "leaving the cache null (NPE)". That does not follow: {@code ShortCache} is a separate class with its own
 * {@code <clinit>}, and JVMS 5.5 makes reading {@code ShortCache.cache} an active use of {@code ShortCache}
 * rather than of {@code Short} -- {@code Short$ShortCache} is not on {@code Loader.clinitBlocked}. The
 * blocked {@code Short.<clinit>} only costs {@code TYPE}, which the VM seeds. So this overlay exists because
 * it is JDK-free and minimal, and the interning gap was a member it had DROPPED.
 */
public final class Short extends Number implements Comparable<Short>
{

    /**
     * {@code short.class}. javac compiles a primitive class literal to {@code getstatic Short.TYPE}, so this field
     * is what makes it work -- and a name-winning overlay silently drops it unless it is declared here.
     *
     * <p>Deliberately NOT {@code final} and deliberately UNINITIALIZED: the VM fills it in
     * ({@code Loader.seedPrimitiveTypes}) because the writer cannot bake it -- the seed JVM's value is a host
     * {@code java.lang.Class} with no image representation. An initializer would also run in {@code <clinit>}
     * AFTER the seeding and null it back out.
     */
    public static Class<Short> TYPE;
    public static final short MIN_VALUE = -32768;
    public static final short MAX_VALUE = 32767;
    public static final int SIZE = 16;
    public static final int BYTES = SIZE / Byte.SIZE;

    private final short value;

    public Short(short v)
    {
        this.value = v;
    }

    /**
     * The JLS 5.1.7 cache, in STOCK'S OWN SHAPE: a nested holder whose {@code <clinit>} fills the array
     * eagerly and ADOPTS a writer-baked one when there is one. JLS 5.1.7 requires two boxing conversions of a
     * {@code short} in [-128, 127] to yield the SAME reference, and autoboxing goes through {@code valueOf},
     * so a missing cache is a silent wrong answer in a language feature.
     *
     * <p>THIS REPLACES A LAZY FILL, AND THE THREE FACTS THAT FORCED THE LAZY ONE WERE EACH TRUE OF THE SHAPE
     * IT CHOSE RATHER THAN OF THE PROBLEM -- which is why stock nests this class instead of putting a field on
     * {@code Short}:
     * <p>(1) "{@code java/lang/Short} is {@code clinitBlocked}, so an initializer here would be SKIPPED".
     *     True of THIS class, and this initializer is not on it: {@code Short$ShortCache} is a different
     *     class with its own {@code <clinit>}, and it is not on that list.
     * <p>(2) "a baked class with a {@code <clinit>} is scheduled into {@code VM.initClasses}, so the fill
     *     would also run in the BAKED world -- a second writer". MEASURED, and the answer is that it runs in
     *     NEITHER: with {@code archivedCache} baked 0 (a control with only the writer's three
     *     {@code ARCHIVED_SUBGRAPHS} entries removed) the boxes still come back at the BAKED array's own
     *     element addresses, where an initializer finding {@code archivedCache} null would have taken the
     *     BUILD arm and handed out heap boxes. So this array is baked complete and simply read, and the
     *     writer entries are insurance for the day rule 2 makes the initializer run -- at which point a null
     *     {@code archivedCache} would silently replace the image's array. I predicted the opposite and the
     *     control said so; see the note at {@code ImageBuilder.ARCHIVED_SUBGRAPHS}.
     * <p>(3) "{@code StaticSnapshot} reflects the HOST's class, which has no such field". True of a flat
     *     {@code Short.cache}; the real JDK {@code Short$ShortCache} declares {@code cache} AND
     *     {@code archivedCache}, so the snapshot has real fields to read.
     *
     * <p>SO THE SMP WINDOW THE LAZY FILL STATED IS CLOSED rather than narrowed: the array is complete before
     * any reader sees it, and no box is allocated on metal at all -- where the lazy form allocated up to 256
     * per launch that first touched this class.
     *
     * <p>Two departures from the stock text, both stated rather than silent. The {@code @Stable} annotation is
     * dropped: it is a JIT hint for a constant-folding optimiser this VM does not have. And stock's closing
     * {@code assert cache.length == size} is dropped because {@code assert} compiles to a read of the
     * synthetic {@code $assertionsDisabled} static -- which nothing here snapshots -- and it re-checks a
     * length the two lines above it just established.
     */
    private static final class ShortCache
    {
        private ShortCache()
        {
        }

        static final Short[] cache;
        static Short[] archivedCache;

        static
        {
            int size = -(-128) + 127 + 1;

            // Load and use the archived cache if it exists
            CDS.initializeFromArchive(ShortCache.class);
            if (archivedCache == null)
            {
                Short[] c = new Short[size];
                short value = -128;
                for (int i = 0; i < size; i++)
                {
                    c[i] = new Short(value++);
                }
                archivedCache = c;
            }
            cache = archivedCache;
        }
    }

    public static Short valueOf(short s)
    {
        final int offset = 128;
        int sAsInt = s;
        if (sAsInt >= -128 && sAsInt <= 127)
        {
            return ShortCache.cache[sAsInt + offset];
        }
        return new Short(s);
    }

    public short shortValue()
    {
        return value;
    }

    public int intValue()
    {
        return value;
    }

    public long longValue()
    {
        return value;
    }

    public float floatValue()
    {
        return value;
    }

    public double doubleValue()
    {
        return value;
    }

    public static int compare(short x, short y)
    {
        return x - y;
    }

    public int compareTo(Short other)
    {
        return compare(this.value, other.value);
    }

    public boolean equals(Object o)
    {
        return o instanceof Short && ((Short) o).value == value;
    }

    public int hashCode()
    {
        return value;
    }

    public String toString()
    {
        return Integer.toString(value);
    }

    /** The string-parsing statics; see {@link Byte#decode} for the accepted {@code decode} prefixes. */
    public static short parseShort(String s)
    {
        return parseShort(s, 10);
    }

    public static short parseShort(String s, int radix)
    {
        int v = Integer.parseInt(s, radix);
        if (v < MIN_VALUE || v > MAX_VALUE)
        {
            throw new NumberFormatException("Value out of range. Value:\"" + s + "\" Radix:" + radix);
        }
        return (short) v;
    }

    public static Short valueOf(String s)
    {
        return valueOf(parseShort(s, 10));
    }

    public static Short valueOf(String s, int radix)
    {
        return valueOf(parseShort(s, radix));
    }

    public static Short decode(String nm)
    {
        int v = Integer.decode(nm).intValue();
        if (v < MIN_VALUE || v > MAX_VALUE)
        {
            throw new NumberFormatException("Value " + v + " out of range from input " + nm);
        }
        return valueOf((short) v);
    }
}
