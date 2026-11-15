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
 * A machine address.
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
public final class Address
{
    private Address()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic.Address is unboxed -- lowered by the compiler, never executed");
    }

    /** The address 0. */
    public static Address zero()
    {
        throw intrinsic();
    }

    /** The all-ones address. */
    public static Address max()
    {
        throw intrinsic();
    }

    /** The address whose bits are {@code address}. */
    public static Address fromLong(long address)
    {
        throw intrinsic();
    }

    /** The address {@code address}, sign-extended. */
    public static Address fromIntSignExtend(int address)
    {
        throw intrinsic();
    }

    /** The address {@code address}, zero-extended. */
    public static Address fromIntZeroExtend(int address)
    {
        throw intrinsic();
    }

    /** The address of {@code o} (a reference IS its object's address here). */
    public static Address fromObject(Object o)
    {
        throw intrinsic();
    }

    /** The object at this address (the inverse of {@link #fromObject}). */
    public Object toObject()
    {
        throw intrinsic();
    }

    /** The address as a {@code long}. */
    public long toLong()
    {
        throw intrinsic();
    }

    /** The low 32 bits, as an {@code int}. */
    public int toInt()
    {
        throw intrinsic();
    }

    /** The address as a word. */
    public Word toWord()
    {
        throw intrinsic();
    }

    /** Whether this is the address 0. */
    public boolean isZero()
    {
        throw intrinsic();
    }

    /** Whether this is the all-ones address. */
    public boolean isMax()
    {
        throw intrinsic();
    }

    /** This address plus {@code bytes} (sign-extended). */
    public Address plus(int bytes)
    {
        throw intrinsic();
    }

    /** This address plus {@code bytes} -- a byte count held in a {@code long} (joe-ng addition). */
    public Address plus(long bytes)
    {
        throw intrinsic();
    }

    /** This address plus {@code offset}. */
    public Address plus(Offset offset)
    {
        throw intrinsic();
    }

    /** This address plus {@code extent}. */
    public Address plus(Extent extent)
    {
        throw intrinsic();
    }

    /** This address minus {@code bytes}. */
    public Address minus(int bytes)
    {
        throw intrinsic();
    }

    /** This address minus {@code bytes} (joe-ng addition). */
    public Address minus(long bytes)
    {
        throw intrinsic();
    }

    /** This address minus {@code offset}. */
    public Address minus(Offset offset)
    {
        throw intrinsic();
    }

    /** This address minus {@code extent}. */
    public Address minus(Extent extent)
    {
        throw intrinsic();
    }

    /** This address minus {@code other}, as an offset. */
    public Offset diff(Address other)
    {
        throw intrinsic();
    }

    /** Unsigned this address &lt; {@code other}. */
    public boolean LT(Address other)
    {
        throw intrinsic();
    }

    /** Unsigned this address &lt;= {@code other}. */
    public boolean LE(Address other)
    {
        throw intrinsic();
    }

    /** Unsigned this address &gt; {@code other}. */
    public boolean GT(Address other)
    {
        throw intrinsic();
    }

    /** Unsigned this address &gt;= {@code other}. */
    public boolean GE(Address other)
    {
        throw intrinsic();
    }

    /** this address == {@code other}. */
    public boolean EQ(Address other)
    {
        throw intrinsic();
    }

    /** this address != {@code other}. */
    public boolean NE(Address other)
    {
        throw intrinsic();
    }

    /** The signed byte at this address. */
    public byte loadByte()
    {
        throw intrinsic();
    }

    /** The signed byte at this address plus {@code offset}. */
    public byte loadByte(Offset offset)
    {
        throw intrinsic();
    }

    /** The byte at this address, zero-extended -- {@code Magic.load8} (joe-ng addition). */
    public int loadUnsignedByte()
    {
        throw intrinsic();
    }

    /** The byte at this address plus {@code offset}, zero-extended (joe-ng addition). */
    public int loadUnsignedByte(Offset offset)
    {
        throw intrinsic();
    }

    /** The 16-bit char at this address. */
    public char loadChar()
    {
        throw intrinsic();
    }

    /** The 16-bit char at this address plus {@code offset}. */
    public char loadChar(Offset offset)
    {
        throw intrinsic();
    }

    /** The signed 16-bit value at this address. */
    public short loadShort()
    {
        throw intrinsic();
    }

    /** The signed 16-bit value at this address plus {@code offset}. */
    public short loadShort(Offset offset)
    {
        throw intrinsic();
    }

    /** The signed 32-bit value at this address -- {@code Magic.load32}. */
    public int loadInt()
    {
        throw intrinsic();
    }

    /** The signed 32-bit value at this address plus {@code offset}. */
    public int loadInt(Offset offset)
    {
        throw intrinsic();
    }

    /** The 64-bit value at this address -- {@code Magic.load64}. */
    public long loadLong()
    {
        throw intrinsic();
    }

    /** The 64-bit value at this address plus {@code offset}. */
    public long loadLong(Offset offset)
    {
        throw intrinsic();
    }

    /** The address stored at this address. */
    public Address loadAddress()
    {
        throw intrinsic();
    }

    /** The address stored at this address plus {@code offset}. */
    public Address loadAddress(Offset offset)
    {
        throw intrinsic();
    }

    /** The word stored at this address. */
    public Word loadWord()
    {
        throw intrinsic();
    }

    /** The word stored at this address plus {@code offset}. */
    public Word loadWord(Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} as one byte (0 or 1) at this address. */
    public void store(boolean value)
    {
        throw intrinsic();
    }

    /** Store {@code value} as one byte at this address plus {@code offset}. */
    public void store(boolean value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store the low 8 bits of {@code value} at this address. */
    public void store(byte value)
    {
        throw intrinsic();
    }

    /** Store the low 8 bits of {@code value} at this address plus {@code offset}. */
    public void store(byte value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 16 bits at this address. */
    public void store(char value)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 16 bits at this address plus {@code offset}. */
    public void store(char value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 16 bits at this address. */
    public void store(short value)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 16 bits at this address plus {@code offset}. */
    public void store(short value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 32 bits at this address -- {@code Magic.store32}. */
    public void store(int value)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 32 bits at this address plus {@code offset}. */
    public void store(int value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 64 bits at this address -- {@code Magic.store64}. */
    public void store(long value)
    {
        throw intrinsic();
    }

    /** Store {@code value} as 64 bits at this address plus {@code offset}. */
    public void store(long value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at this address. */
    public void store(Address value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at this address plus {@code offset}. */
    public void store(Address value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at this address. */
    public void store(Word value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at this address plus {@code offset}. */
    public void store(Word value, Offset offset)
    {
        throw intrinsic();
    }

    /** Atomically replace the 64 bits here with {@code value} if they equal {@code old} -- {@code Magic.cas64}. */
    public boolean attempt(long old, long value)
    {
        throw intrinsic();
    }

    /** Atomically replace the word here with {@code value} if it equals {@code old}. */
    public boolean attempt(Word old, Word value)
    {
        throw intrinsic();
    }

    /** Atomically replace the address here with {@code value} if it equals {@code old}. */
    public boolean attempt(Address old, Address value)
    {
        throw intrinsic();
    }
}
