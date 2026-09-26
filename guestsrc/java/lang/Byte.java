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

/**
 * A JDK-free, minimal {@code java/lang/Byte} overlay -- like {@link Short}, the stock {@code valueOf} reads a
 * nested {@code ByteCache} that never initializes on metal (the wrapper's {@code <clinit>} sets a native TYPE
 * and is blocked). {@code valueOf} interns per JLS 5.1.7 from a LAZILY filled array -- this overlay has no
 * {@code <clinit>} (MIN/MAX inlined), and could not have one, which is what makes the fill lazy rather than
 * eager like stock's; see the cache field's javadoc.
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
     * The JLS 5.1.7 cache. THIS OVERLAY USED TO HAVE NO CACHE ("valueOf just boxes"), so
     * {@code valueOf((byte) 5) == valueOf((byte) 5)} answered FALSE where the specification says it must answer
     * true -- a silent wrong answer in a language feature, since autoboxing goes through here. JLS 5.1.7
     * requires two boxing conversions of ANY {@code byte} (every value is in [-128, 127]) to yield the SAME reference.
     *
     * <p>FILLED LAZILY, WHICH IS A DIVERGENCE FROM STOCK'S SHAPE, AND THE REASON IS MEASURED RATHER THAN
     * stylistic. Stock nests a {@code ByteCache} class whose {@code <clinit>} fills the whole array eagerly
     * (consulting {@code archivedCache} for CDS). joe-ng cannot copy that here, three ways:
     * <p>(1) {@code java/lang/Byte} is on {@code Loader.clinitBlocked}, so an initializer added to THIS class
     *     would be SKIPPED and the array would read null -- trading a mild divergence for an NPE, which is
     *     the "a skipped initializer is a silent wrong answer" failure this VM records more than any other.
     * <p>(2) a baked class that HAS a {@code <clinit>} is scheduled into {@code VM.initClasses} by
     *     {@code ImageBuilder.use}, so the fill would ALSO run at boot in the BAKED world -- a second writer
     *     for a static cell the loader adopts, which is exactly the {@code ImmutableCollections.EMPTY}
     *     hazard.
     * <p>(3) the snapshot route cannot substitute: {@code StaticSnapshot} reflects the HOST's class, and
     *     java.base is a NAMED module, so the writer sees the REAL JDK {@code Byte}, which has no such
     *     field at all.
     *
     * <p>STATED DIVERGENCE, not glossed: a lazy fill has an SMP window. Two cores that both miss can each
     * hand out a box for one value, and {@code ==} is then false for it. That is strictly NARROWER than the
     * behaviour it replaces (always false) rather than a new failure mode, and nothing in the tree boxes a
     * byte on two cores. The race-free form is stock's eager initializer, which needs the three wrappers
     * un-blocked and the baked-world question above answered -- its own increment, with its own gate.
     */
    private static Byte[] cache;

    public static Byte valueOf(byte b)
    {
        Byte[] k = cache;
        if (k == null)
        {
            k = new Byte[256];
            cache = k;
        }
        int i = b + 128;
        Byte r = k[i];
        if (r == null)
        {
            r = new Byte(b);
            k[i] = r;
        }
        return r;
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
