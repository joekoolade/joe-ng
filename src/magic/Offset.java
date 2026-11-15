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
 * A signed distance in bytes between two addresses, or from an address to a field.
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
public final class Offset
{
    private Offset()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic.Offset is unboxed -- lowered by the compiler, never executed");
    }

    /** The offset 0. */
    public static Offset zero()
    {
        throw intrinsic();
    }

    /** The largest positive offset. */
    public static Offset max()
    {
        throw intrinsic();
    }

    /** The offset of {@code bytes}. */
    public static Offset fromLong(long bytes)
    {
        throw intrinsic();
    }

    /** The offset of {@code bytes}, sign-extended. */
    public static Offset fromIntSignExtend(int bytes)
    {
        throw intrinsic();
    }

    /** The offset of {@code bytes}, zero-extended. */
    public static Offset fromIntZeroExtend(int bytes)
    {
        throw intrinsic();
    }

    /** The offset as a {@code long}. */
    public long toLong()
    {
        throw intrinsic();
    }

    /** The low 32 bits, as an {@code int}. */
    public int toInt()
    {
        throw intrinsic();
    }

    /** The offset as a word. */
    public Word toWord()
    {
        throw intrinsic();
    }

    /** This offset plus {@code bytes}. */
    public Offset plus(int bytes)
    {
        throw intrinsic();
    }

    /** This offset plus {@code other}. */
    public Offset plus(Offset other)
    {
        throw intrinsic();
    }

    /** This offset minus {@code bytes}. */
    public Offset minus(int bytes)
    {
        throw intrinsic();
    }

    /** This offset minus {@code other}. */
    public Offset minus(Offset other)
    {
        throw intrinsic();
    }

    /** This offset == {@code other}. */
    public boolean EQ(Offset other)
    {
        throw intrinsic();
    }

    /** This offset != {@code other}. */
    public boolean NE(Offset other)
    {
        throw intrinsic();
    }

    /** Signed this offset &lt; {@code other}. */
    public boolean sLT(Offset other)
    {
        throw intrinsic();
    }

    /** Signed this offset &lt;= {@code other}. */
    public boolean sLE(Offset other)
    {
        throw intrinsic();
    }

    /** Signed this offset &gt; {@code other}. */
    public boolean sGT(Offset other)
    {
        throw intrinsic();
    }

    /** Signed this offset &gt;= {@code other}. */
    public boolean sGE(Offset other)
    {
        throw intrinsic();
    }

    /** Whether this offset is 0. */
    public boolean isZero()
    {
        throw intrinsic();
    }

    /** Whether this is the largest positive offset. */
    public boolean isMax()
    {
        throw intrinsic();
    }
}
