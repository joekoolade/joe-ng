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
 * A JDK-free, minimal {@code java/lang/Short} overlay. The stock class's {@code valueOf} reads a nested
 * {@code ShortCache} array, and its {@code <clinit>} sets {@code TYPE = Class.getPrimitiveClass("short")} (a
 * native) so the loader blocks it -- leaving the cache null (NPE). This overlay keeps a cache of its own and
 * fills it LAZILY, because it has no {@code <clinit>} (MIN/MAX are inlined constants) and could not have
 * one; {@code valueOf} interns per JLS 5.1.7. See the cache field's javadoc for why lazy rather than eager.
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
     * The JLS 5.1.7 cache. THIS OVERLAY USED TO HAVE NO CACHE ("valueOf just boxes"), so
     * {@code valueOf((short) 5) == valueOf((short) 5)} answered FALSE where the specification says it must answer
     * true -- a silent wrong answer in a language feature, since autoboxing goes through here. JLS 5.1.7
     * requires two boxing conversions of a {@code short} in [-128, 127] to yield the SAME reference.
     *
     * <p>FILLED LAZILY, WHICH IS A DIVERGENCE FROM STOCK'S SHAPE, AND THE REASON IS MEASURED RATHER THAN
     * stylistic. Stock nests a {@code ShortCache} class whose {@code <clinit>} fills the whole array eagerly
     * (consulting {@code archivedCache} for CDS). joe-ng cannot copy that here, three ways:
     * <p>(1) {@code java/lang/Short} is on {@code Loader.clinitBlocked}, so an initializer added to THIS class
     *     would be SKIPPED and the array would read null -- trading a mild divergence for an NPE, which is
     *     the "a skipped initializer is a silent wrong answer" failure this VM records more than any other.
     * <p>(2) a baked class that HAS a {@code <clinit>} is scheduled into {@code VM.initClasses} by
     *     {@code ImageBuilder.use}, so the fill would ALSO run at boot in the BAKED world -- a second writer
     *     for a static cell the loader adopts, which is exactly the {@code ImmutableCollections.EMPTY}
     *     hazard.
     * <p>(3) the snapshot route cannot substitute: {@code StaticSnapshot} reflects the HOST's class, and
     *     java.base is a NAMED module, so the writer sees the REAL JDK {@code Short}, which has no such
     *     field at all.
     *
     * <p>STATED DIVERGENCE, not glossed: a lazy fill has an SMP window. Two cores that both miss can each
     * hand out a box for one value, and {@code ==} is then false for it. That is strictly NARROWER than the
     * behaviour it replaces (always false) rather than a new failure mode, and nothing in the tree boxes a
     * short on two cores. The race-free form is stock's eager initializer, which needs the three wrappers
     * un-blocked and the baked-world question above answered -- its own increment, with its own gate.
     */
    private static Short[] cache;

    public static Short valueOf(short s)
    {
        if (s < -128 || s > 127)
        {
            return new Short(s);
        }
        Short[] k = cache;
        if (k == null)
        {
            k = new Short[256];
            cache = k;
        }
        int i = s + 128;
        Short r = k[i];
        if (r == null)
        {
            r = new Short(s);
            k[i] = r;
        }
        return r;
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
