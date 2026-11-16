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
 * An array of machine addresses.
 *
 * <p>UNBOXED, like {@link Address}, but the value IS a real object: at run time an AddressArray is a {@code long[]}.
 * Baseline lowers {@link #create} exactly as {@code newarray long}, {@link #get} as {@code laload}, {@link #set} as
 * {@code lastore} and {@link #length} as {@code arraylength} -- the same instructions, the same null and bounds
 * checks, the same leaf decision -- so a table typed AddressArray compiles to the code its {@code long[]} did, while
 * its elements read and write as {@link Address}. The collector, the image writer and the object model see a
 * {@code long[]}: nothing about them changes. An {@code Address[]} is not the same thing (an object array, with
 * covariant store checks on elements that are not objects), which is why this type exists.
 *
 * <p>The bodies here only throw: nothing runs them on the seed JVM. So a field of this type must not be given an
 * initializer that the writer could run on the host -- create the array where the {@code long[]} was created.
 *
 * <p>The API follows Jikes RVM's {@code org.vmmagic.unboxed.AddressArray} by name; joe-ng's own code, written from
 * the concept.
 */
public final class AddressArray
{
    private AddressArray()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException(
                "magic.AddressArray is unboxed -- lowered by the compiler, never executed");
    }

    /** A new array of {@code length} zero addresses ({@code new long[length]}). */
    public static AddressArray create(int length)
    {
        throw intrinsic();
    }

    /** Element {@code index} ({@code laload}: null and bounds checked like any array access). */
    public Address get(int index)
    {
        throw intrinsic();
    }

    /** Set element {@code index} to {@code value} ({@code lastore}). */
    public void set(int index, Address value)
    {
        throw intrinsic();
    }

    /** The number of elements ({@code arraylength}). */
    public int length()
    {
        throw intrinsic();
    }
}
