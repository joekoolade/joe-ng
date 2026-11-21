/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-20
 */
package magic;

/**
 * The address of a raw data block's payload: what {@code Heap.allocData} returns, and how the VM holds its own
 * metadata -- class-file blobs, {@code Type} records, TIBs, tables -- and its scratch buffers.
 *
 * <p>UNBOXED, exactly like {@link Address} (the same lowerings, chosen by {@code compiler.Unboxed}), with one
 * difference that is the reason it exists: a stack slot of this type is a REFERENCE to the collector (frames/GC
 * arc S4). The reference maps give it its own code, and a value in such a slot keeps the block whose payload it
 * points at alive, the way an {@code Object} slot keeps an object alive. An {@code Address} slot keeps nothing
 * alive, so a payload must never cross a call held only as an {@code Address} or a {@code long}.
 *
 * <p>Holds 0, a payload address in the heap, or an address outside the heap (the boot image's baked metadata,
 * which is never collected). Arithmetic answers an {@link Address}: a derived pointer is not a data reference,
 * and stays valid only while the reference it came from is held. The bodies here only throw: nothing runs them
 * on the seed JVM.
 */
public final class DataReference
{
    private DataReference()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic.DataReference is unboxed -- lowered by the compiler, never executed");
    }

    /** The null data reference. */
    public static DataReference zero()
    {
        throw intrinsic();
    }

    /** The data reference whose bits are {@code address}. */
    public static DataReference fromLong(long address)
    {
        throw intrinsic();
    }

    /** The data reference at {@code address} (which must be a payload, 0, or outside the heap). */
    public static DataReference fromAddress(Address address)
    {
        throw intrinsic();
    }

    /** The payload's address. */
    public Address toAddress()
    {
        throw intrinsic();
    }

    /** The bits as a {@code long}. */
    public long toLong()
    {
        throw intrinsic();
    }

    /** True if this is the null data reference. */
    public boolean isZero()
    {
        throw intrinsic();
    }

    /** The address {@code bytes} past the payload start (a derived pointer). */
    public Address plus(int bytes)
    {
        throw intrinsic();
    }

    /** The address {@code bytes} past the payload start (a derived pointer). */
    public Address plus(long bytes)
    {
        throw intrinsic();
    }

    /** The address {@code bytes} past the payload start (a derived pointer). */
    public Address plus(Offset bytes)
    {
        throw intrinsic();
    }

    /** The address {@code bytes} past the payload start (a derived pointer). */
    public Address plus(Extent bytes)
    {
        throw intrinsic();
    }

    /** The address {@code bytes} before the payload start (a derived pointer). */
    public Address minus(int bytes)
    {
        throw intrinsic();
    }

    /** The address {@code bytes} before the payload start (a derived pointer). */
    public Address minus(long bytes)
    {
        throw intrinsic();
    }

    /** The address {@code bytes} before the payload start (a derived pointer). */
    public Address minus(Offset bytes)
    {
        throw intrinsic();
    }

    /** The address {@code bytes} before the payload start (a derived pointer). */
    public Address minus(Extent bytes)
    {
        throw intrinsic();
    }

    /** Unsigned compare: LT. */
    public boolean LT(DataReference other)
    {
        throw intrinsic();
    }

    /** Unsigned compare: LE. */
    public boolean LE(DataReference other)
    {
        throw intrinsic();
    }

    /** Unsigned compare: GT. */
    public boolean GT(DataReference other)
    {
        throw intrinsic();
    }

    /** Unsigned compare: GE. */
    public boolean GE(DataReference other)
    {
        throw intrinsic();
    }

    /** Unsigned compare: EQ. */
    public boolean EQ(DataReference other)
    {
        throw intrinsic();
    }

    /** Unsigned compare: NE. */
    public boolean NE(DataReference other)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public byte loadByte()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public byte loadByte(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public int loadUnsignedByte()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public int loadUnsignedByte(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public char loadChar()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public char loadChar(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public short loadShort()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public short loadShort(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public int loadInt()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public int loadInt(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public long loadLong()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public long loadLong(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public Address loadAddress()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public Address loadAddress(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public ObjectReference loadObjectReference()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public ObjectReference loadObjectReference(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public Word loadWord()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public Word loadWord(Offset offset)
    {
        throw intrinsic();
    }

    /** The value at the payload start. */
    public DataReference loadDataReference()
    {
        throw intrinsic();
    }

    /** The value at {@code offset} into the payload. */
    public DataReference loadDataReference(Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(boolean value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(boolean value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(byte value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(byte value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(char value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(char value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(short value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(short value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(int value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(int value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(long value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(long value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(Address value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(Address value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(ObjectReference value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(ObjectReference value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(Word value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(Word value, Offset offset)
    {
        throw intrinsic();
    }

    /** Store {@code value} at the payload start. */
    public void store(DataReference value)
    {
        throw intrinsic();
    }

    /** Store {@code value} at {@code offset} into the payload. */
    public void store(DataReference value, Offset offset)
    {
        throw intrinsic();
    }
}
