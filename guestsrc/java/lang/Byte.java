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
 * A JDK-free, minimal {@code java/lang/Byte} overlay -- it shadows the stock class entirely (overlay wins by
 * name), so a stock member it does not declare CEASES TO EXIST. {@code valueOf} interns per JLS 5.1.7 from the
 * nested {@code ByteCache} below, which is stock's own shape: an eagerly filled array that adopts a
 * writer-baked one.
 *
 * <p>A CORRECTION TO THIS COMMENT'S OWN EARLIER CLAIM, recorded rather than quietly replaced. It used to say
 * the stock {@code valueOf} "reads a nested {@code ByteCache} that never initializes on metal (the wrapper's
 * {@code <clinit>} sets a native TYPE and is blocked)". That does not follow: a nested class has its OWN
 * {@code <clinit>}, and JVMS 5.5 makes reading {@code ByteCache.cache} an active use of {@code ByteCache},
 * not of {@code Byte} -- {@code Byte$ByteCache} is not on {@code Loader.clinitBlocked}, so stock's cache
 * would have initialized. The blocked {@code Byte.<clinit>} only costs {@code TYPE}, which the VM seeds.
 * So this overlay exists because it is JDK-free and minimal, and the interning gap was simply a member it had
 * DROPPED -- not something the stock class could not have done here.
 */
public final class Byte extends Number implements Comparable<Byte>
{

    /**
     * {@code byte.class}. javac compiles a primitive class literal to {@code getstatic Byte.TYPE}, so this field
     * is what makes it work -- and a name-winning overlay silently drops it unless it is declared here.
     *
     * <p>Deliberately NOT {@code final} and deliberately UNINITIALIZED: the VM fills it in
     * ({@code Loader.seedPrimitiveTypes}) because the writer cannot bake it -- the seed JVM's value is a host
     * {@code java.lang.Class} with no image representation. An initializer would also run in {@code <clinit>}
     * AFTER the seeding and null it back out.
     */
    public static Class<Byte> TYPE;
    public static final byte MIN_VALUE = -128;
    public static final byte MAX_VALUE = 127;
    public static final int SIZE = 8;
    public static final int BYTES = SIZE / Byte.SIZE;

    private final byte value;

    public Byte(byte v)
    {
        this.value = v;
    }

    /**
     * The JLS 5.1.7 cache, in STOCK'S OWN SHAPE: a nested holder whose {@code <clinit>} fills the array
     * eagerly and ADOPTS a writer-baked one when there is one. JLS 5.1.7 requires two boxing conversions of
     * ANY {@code byte} (every value is in [-128, 127]) to yield the SAME reference, and autoboxing goes
     * through {@code valueOf}, so a missing cache is a silent wrong answer in a language feature.
     *
     * <p>THIS REPLACES A LAZY FILL, AND THE THREE FACTS THAT FORCED THE LAZY ONE WERE EACH TRUE OF THE SHAPE
     * IT CHOSE RATHER THAN OF THE PROBLEM -- which is why stock nests this class instead of putting a field on
     * {@code Byte}:
     * <p>(1) "{@code java/lang/Byte} is {@code clinitBlocked}, so an initializer here would be SKIPPED".
     *     True of THIS class, and this initializer is not on it: {@code Byte$ByteCache} is a different
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
     *     {@code Byte.cache}; the real JDK {@code Byte$ByteCache} declares {@code cache} AND
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
    private static final class ByteCache
    {
        private ByteCache()
        {
        }

        static final Byte[] cache;
        static Byte[] archivedCache;

        static
        {
            final int size = -(-128) + 127 + 1;

            // Load and use the archived cache if it exists
            CDS.initializeFromArchive(ByteCache.class);
            if (archivedCache == null)
            {
                Byte[] c = new Byte[size];
                byte value = (byte) -128;
                for (int i = 0; i < size; i++)
                {
                    c[i] = new Byte(value++);
                }
                archivedCache = c;
            }
            cache = archivedCache;
        }
    }

    public static Byte valueOf(byte b)
    {
        final int offset = 128;
        return ByteCache.cache[(int) b + offset];
    }

    public byte byteValue()
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

    public static int compare(byte x, byte y)
    {
        return x - y;
    }

    /** Zero-extend a byte to an int (0..255). Reached by {@code String.hashCode} of a length>=2 string via
     *  {@code ArraysSupport.unsignedHashCode} (the vectorized-hash leaf) — stock {@code Byte} has it, so the
     *  JDK-free overlay must too or that path traps. */
    public static int toUnsignedInt(byte x)
    {
        return ((int) x) & 0xff;
    }

    /** Zero-extend a byte to a long (0..255). */
    public static long toUnsignedLong(byte x)
    {
        return ((long) x) & 0xffL;
    }

    public int compareTo(Byte other)
    {
        return compare(this.value, other.value);
    }

    public boolean equals(Object o)
    {
        return o instanceof Byte && ((Byte) o).value == value;
    }

    public int hashCode()
    {
        return value;
    }

    public String toString()
    {
        return Integer.toString(value);
    }

    /**
     * The string-parsing statics. {@code decode} accepts the stock prefixes -- {@code 0x}/{@code 0X}/{@code #}
     * for hex, a leading {@code 0} for octal, otherwise decimal -- with an optional sign, and delegates the
     * digits to {@link Integer}. Range is checked so an out-of-range value throws rather than wrapping
     * silently, which is the whole point of the narrow wrappers.
     *
     * <p>Declared because a name-winning overlay silently drops what it does not declare; listed by
     * {@code make overlaycheck} as referenced from JUnit's string-to-number conversion and picocli's converters.
     */
    public static byte parseByte(String s)
    {
        return parseByte(s, 10);
    }

    public static byte parseByte(String s, int radix)
    {
        int v = Integer.parseInt(s, radix);
        if (v < MIN_VALUE || v > MAX_VALUE)
        {
            throw new NumberFormatException("Value out of range. Value:\"" + s + "\" Radix:" + radix);
        }
        return (byte) v;
    }

    public static Byte valueOf(String s)
    {
        return valueOf(parseByte(s, 10));
    }

    public static Byte valueOf(String s, int radix)
    {
        return valueOf(parseByte(s, radix));
    }

    public static Byte decode(String nm)
    {
        int v = Integer.decode(nm).intValue();
        if (v < MIN_VALUE || v > MAX_VALUE)
        {
            throw new NumberFormatException("Value " + v + " out of range from input " + nm);
        }
        return valueOf((byte) v);
    }
}
