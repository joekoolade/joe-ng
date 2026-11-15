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
 * A machine word: 64 bits with no meaning of their own, for masks, flags and bit fields.
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
public final class Word
{
    private Word()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic.Word is unboxed -- lowered by the compiler, never executed");
    }

    /** The word 0. */
    public static Word zero()
    {
        throw intrinsic();
    }

    /** The word 1. */
    public static Word one()
    {
        throw intrinsic();
    }

    /** The all-ones word. */
    public static Word max()
    {
        throw intrinsic();
    }

    /** The word whose bits are {@code value}. */
    public static Word fromLong(long value)
    {
        throw intrinsic();
    }

    /** The word {@code value}, sign-extended. */
    public static Word fromIntSignExtend(int value)
    {
        throw intrinsic();
    }

    /** The word {@code value}, zero-extended. */
    public static Word fromIntZeroExtend(int value)
    {
        throw intrinsic();
    }

    /** The word as a {@code long}. */
    public long toLong()
    {
        throw intrinsic();
    }

    /** The low 32 bits, as an {@code int}. */
    public int toInt()
    {
        throw intrinsic();
    }

    /** The word as an address. */
    public Address toAddress()
    {
        throw intrinsic();
    }

    /** The word as an offset. */
    public Offset toOffset()
    {
        throw intrinsic();
    }

    /** The word as an extent. */
    public Extent toExtent()
    {
        throw intrinsic();
    }

    /** This word plus {@code other}. */
    public Word plus(Word other)
    {
        throw intrinsic();
    }

    /** This word plus {@code other}. */
    public Word plus(Offset other)
    {
        throw intrinsic();
    }

    /** This word plus {@code other}. */
    public Word plus(Extent other)
    {
        throw intrinsic();
    }

    /** This word minus {@code other}. */
    public Word minus(Word other)
    {
        throw intrinsic();
    }

    /** This word minus {@code other}. */
    public Word minus(Offset other)
    {
        throw intrinsic();
    }

    /** This word minus {@code other}. */
    public Word minus(Extent other)
    {
        throw intrinsic();
    }

    /** Whether this word is 0. */
    public boolean isZero()
    {
        throw intrinsic();
    }

    /** Whether this word is all ones. */
    public boolean isMax()
    {
        throw intrinsic();
    }

    /** Unsigned this word &lt; {@code other}. */
    public boolean LT(Word other)
    {
        throw intrinsic();
    }

    /** Unsigned this word &lt;= {@code other}. */
    public boolean LE(Word other)
    {
        throw intrinsic();
    }

    /** Unsigned this word &gt; {@code other}. */
    public boolean GT(Word other)
    {
        throw intrinsic();
    }

    /** Unsigned this word &gt;= {@code other}. */
    public boolean GE(Word other)
    {
        throw intrinsic();
    }

    /** this word == {@code other}. */
    public boolean EQ(Word other)
    {
        throw intrinsic();
    }

    /** this word != {@code other}. */
    public boolean NE(Word other)
    {
        throw intrinsic();
    }

    /** Bitwise and. */
    public Word and(Word other)
    {
        throw intrinsic();
    }

    /** Bitwise or. */
    public Word or(Word other)
    {
        throw intrinsic();
    }

    /** Bitwise exclusive or. */
    public Word xor(Word other)
    {
        throw intrinsic();
    }

    /** Bitwise complement. */
    public Word not()
    {
        throw intrinsic();
    }

    /** Shift left by {@code amount} (0..63). */
    public Word lsh(int amount)
    {
        throw intrinsic();
    }

    /** Logical shift right by {@code amount} (0..63). */
    public Word rshl(int amount)
    {
        throw intrinsic();
    }

    /** Arithmetic shift right by {@code amount} (0..63). */
    public Word rsha(int amount)
    {
        throw intrinsic();
    }
}
