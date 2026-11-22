/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-21
 */
package magic;

/**
 * An array of data references ({@link DataReference}: the payload addresses of raw {@code allocData} blocks).
 *
 * <p>UNBOXED. Its operations lower exactly as {@link AddressArray}'s -- {@link #create} as {@code newarray long},
 * {@link #get} as {@code laload}, {@link #set} as {@code lastore}, {@link #length} as {@code arraylength} -- but the
 * array {@link #create} allocates is retyped to {@code vm.DataRefElem[]}, an array class whose Type marks its elements
 * as data references ({@code objectmodel.ObjectModel.ARRAY_DATA_ELEMS}). A precise collector then keeps each
 * element's block alive (one header below the payload) and never mistakes an element for an object reference
 * (frames/GC arc S6b). Only writer-compiled VM code may create one.
 *
 * <p>The bodies here only throw: nothing runs them on the seed JVM, so a field of this type must not be given an
 * initializer the writer could run on the host.
 */
public final class DataReferenceArray
{
    private DataReferenceArray()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException(
                "magic.DataReferenceArray is unboxed -- lowered by the compiler, never executed");
    }

    /** A new array of {@code length} zero references. */
    public static DataReferenceArray create(int length)
    {
        throw intrinsic();
    }

    /** Element {@code index} ({@code laload}: null and bounds checked like any array access). */
    public DataReference get(int index)
    {
        throw intrinsic();
    }

    /** Set element {@code index} to {@code value} ({@code lastore}). */
    public void set(int index, DataReference value)
    {
        throw intrinsic();
    }

    /** The number of elements ({@code arraylength}). */
    public int length()
    {
        throw intrinsic();
    }
}
