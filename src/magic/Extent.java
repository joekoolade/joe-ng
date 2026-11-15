/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-15
 */
package magic;

/**
 * An unsigned size in bytes.
 *
 * <p>UNBOXED: a value of this type is a raw 64-bit word, never an object. Baseline lowers every method to machine
 * instructions on the operand registers ({@code compiler.Unboxed} classifies them, in both worlds: the seed writer and
 * the metal JIT), with no allocation, no call and no receiver null check -- {@code Address.zero().isZero()} is true,
 * not a NullPointerException. The bodies here only throw: nothing runs them on the seed JVM, and a host that tried
 * would fail loudly rather than answer wrongly. A field of this type is traced like the {@code long} it replaces
 * (Loader.mayHoldPointer counts both; the collector validates every candidate), so arrays of it are not used.
 *
 * <p>The API follows Jikes RVM's {@code org.vmmagic.unboxed} by name; joe-ng's own code, written from the concept.
 */
public final class Extent
{
    private Extent()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic.Extent is unboxed -- lowered by the compiler, never executed");
    }

    /** The extent 0. */
    public static Extent zero()
    {
        throw intrinsic();
    }

    /** The extent 1. */
    public static Extent one()
    {
        throw intrinsic();
    }

    /** The largest extent (all ones). */
    public static Extent max()
    {
        throw intrinsic();
    }

    /** The extent of {@code bytes}. */
    public static Extent fromLong(long bytes)
    {
        throw intrinsic();
    }

    /** The extent of {@code bytes}, sign-extended. */
    public static Extent fromIntSignExtend(int bytes)
    {
        throw intrinsic();
    }

    /** The extent of {@code bytes}, zero-extended. */
    public static Extent fromIntZeroExtend(int bytes)
    {
        throw intrinsic();
    }

    /** The extent as a {@code long}. */
    public long toLong()
    {
        throw intrinsic();
    }

    /** The low 32 bits, as an {@code int}. */
    public int toInt()
    {
        throw intrinsic();
    }

    /** The extent as a word. */
    public Word toWord()
    {
        throw intrinsic();
    }

    /** This extent plus {@code bytes}. */
    public Extent plus(int bytes)
    {
        throw intrinsic();
    }

    /** This extent plus {@code bytes}. */
    public Extent plus(long bytes)
    {
        throw intrinsic();
    }

    /** This extent plus {@code other}. */
    public Extent plus(Extent other)
    {
        throw intrinsic();
    }

    /** This extent minus {@code bytes}. */
    public Extent minus(int bytes)
    {
        throw intrinsic();
    }

    /** This extent minus {@code bytes}. */
    public Extent minus(long bytes)
    {
        throw intrinsic();
    }

    /** This extent minus {@code other}. */
    public Extent minus(Extent other)
    {
        throw intrinsic();
    }

    /** Unsigned this extent &lt; {@code other}. */
    public boolean LT(Extent other)
    {
        throw intrinsic();
    }

    /** Unsigned this extent &lt;= {@code other}. */
    public boolean LE(Extent other)
    {
        throw intrinsic();
    }

    /** Unsigned this extent &gt; {@code other}. */
    public boolean GT(Extent other)
    {
        throw intrinsic();
    }

    /** Unsigned this extent &gt;= {@code other}. */
    public boolean GE(Extent other)
    {
        throw intrinsic();
    }

    /** this extent == {@code other}. */
    public boolean EQ(Extent other)
    {
        throw intrinsic();
    }

    /** this extent != {@code other}. */
    public boolean NE(Extent other)
    {
        throw intrinsic();
    }
}
