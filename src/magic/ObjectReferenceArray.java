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
 * An array of object references.
 *
 * <p>UNBOXED, like {@link AddressArray} -- and the same thing at run time: a {@code long[]}. Baseline lowers
 * {@link #create} exactly as {@code newarray long}, {@link #get} as {@code laload}, {@link #set} as {@code lastore}
 * and {@link #length} as {@code arraylength} (the same intrinsic ids as AddressArray), so a table typed
 * ObjectReferenceArray compiles to the code its {@code long[]} did, while its elements read and write as
 * {@link ObjectReference}. The collector, the image writer and the object model see a {@code long[]} -- which
 * the collector already scans like an {@code Object[]} (VMGc.scanArray decides by element SIZE alone, so every
 * word-wide element is probed as a candidate reference): its elements keep their objects alive, conservatively,
 * exactly as the {@code long[]} tables it replaces did. An {@code Object[]} is still a different thing (covariant
 * store checks, a TIB of its own).
 *
 * <p>The bodies here only throw: nothing runs them on the seed JVM. So a field of this type must not be given an
 * initializer that the writer could run on the host -- create the array where the {@code long[]} was created.
 *
 * <p>The API follows Jikes RVM's {@code org.vmmagic.unboxed.ObjectReferenceArray} by name; joe-ng's own code,
 * written from the concept.
 */
public final class ObjectReferenceArray
{
    private ObjectReferenceArray()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException(
                "magic.ObjectReferenceArray is unboxed -- lowered by the compiler, never executed");
    }

    /** A new array of {@code length} null references ({@code new long[length]}). */
    public static ObjectReferenceArray create(int length)
    {
        throw intrinsic();
    }

    /** Element {@code index} ({@code laload}: null and bounds checked like any array access). */
    public ObjectReference get(int index)
    {
        throw intrinsic();
    }

    /** Set element {@code index} to {@code value} ({@code lastore}). */
    public void set(int index, ObjectReference value)
    {
        throw intrinsic();
    }

    /** The number of elements ({@code arraylength}). */
    public int length()
    {
        throw intrinsic();
    }
}
