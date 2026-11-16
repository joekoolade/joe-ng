/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-16
 */
package magic;

/**
 * A reference to a Java object, held as a raw word.
 *
 * <p>UNBOXED, like {@link Address}: a value of this type is the object's 64-bit address, never a box. Baseline lowers
 * every method to register moves and compares ({@code compiler.Unboxed} classifies them in both worlds), with no
 * allocation, no call and no receiver null check -- {@code ObjectReference.nullReference().isNull()} is true, not a
 * NullPointerException. The bodies here only throw: nothing runs them on the seed JVM.
 *
 * <p>The difference from {@link Address} is what the word POINTS AT. An Address is any byte of memory: a buffer, a
 * TIB, a Type, a field. An ObjectReference is the start of a Java object -- what a {@code long} holding a reference
 * meant in VM code that cannot hold a typed Java reference (a native's argument, a slot read off the heap). Moving
 * between the two is a free {@link #toAddress} / {@link Address#toObjectReference}; field access goes through the
 * Address. A FIELD of this type is an {@code L} field, so the precise tracer follows it like any reference field (it
 * keeps its object alive); like Address it must not be given an initializer, which the writer would run on the host.
 *
 * <p>Deliberately absent: {@code equals} and {@code hashCode}. Inherited from Object they would be real virtual
 * calls on a receiver that is not an object; a call to either has no lowering and fails the compile loudly. Compare
 * with {@link #EQ} / {@link #NE}.
 *
 * <p>The API follows Jikes RVM's {@code org.vmmagic.unboxed.ObjectReference} by name; joe-ng's own code, written from
 * the concept.
 */
public final class ObjectReference
{
    private ObjectReference()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException(
                "magic.ObjectReference is unboxed -- lowered by the compiler, never executed");
    }

    /** The null reference. */
    public static ObjectReference nullReference()
    {
        throw intrinsic();
    }

    /** The reference to {@code o}. */
    public static ObjectReference fromObject(Object o)
    {
        throw intrinsic();
    }

    /**
     * The reference whose bits are {@code ref} -- joe-ng addition, for the descriptor ABI: natives and
     * descriptor-named helpers receive references as {@code long}.
     */
    public static ObjectReference fromLong(long ref)
    {
        throw intrinsic();
    }

    /** The object this refers to (the inverse of {@link #fromObject}). */
    public Object toObject()
    {
        throw intrinsic();
    }

    /** The object's address: where its header starts. */
    public Address toAddress()
    {
        throw intrinsic();
    }

    /** The reference as a {@code long} (joe-ng addition; the inverse of {@link #fromLong}). */
    public long toLong()
    {
        throw intrinsic();
    }

    /** Whether this is the null reference. */
    public boolean isNull()
    {
        throw intrinsic();
    }

    /** Whether this and {@code other} refer to the same object (all 64 bits). */
    public boolean EQ(ObjectReference other)
    {
        throw intrinsic();
    }

    /** Whether this and {@code other} refer to different objects. */
    public boolean NE(ObjectReference other)
    {
        throw intrinsic();
    }
}
