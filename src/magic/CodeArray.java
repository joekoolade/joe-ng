/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-22
 */
package magic;

/**
 * An array of code addresses (see {@link CodeAddress}), held as their {@code long} bits.
 *
 * <p>UNBOXED. Its operations lower exactly as {@link AddressArray}'s -- {@link #create} as {@code newarray long},
 * {@link #get} as {@code laload}, {@link #set} as {@code lastore}, {@link #length} as {@code arraylength} -- but the
 * array {@link #create} allocates is retyped to {@code vm.CodeElem[]}, an array class whose Type marks its elements
 * as code addresses ({@code objectmodel.ObjectModel.ARRAY_CODE_ELEMS}). A precise collector then records each element
 * in the code bitmap and marks nothing on the heap (frames/GC arc S6b-3). Only writer-compiled VM code may create one.
 *
 * <p>The bodies here only throw: nothing runs them on the seed JVM, so a field of this type must not be given an
 * initializer the writer could run on the host.
 */
public final class CodeArray
{
    private CodeArray()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic.CodeArray is unboxed -- lowered by the compiler, never executed");
    }

    /** A new array of {@code length} zero addresses. */
    public static CodeArray create(int length)
    {
        throw intrinsic();
    }

    /** Element {@code index} ({@code laload}: null and bounds checked like any array access). */
    public long get(int index)
    {
        throw intrinsic();
    }

    /** Set element {@code index} to {@code value} ({@code lastore}). */
    public void set(int index, long value)
    {
        throw intrinsic();
    }

    /** The number of elements ({@code arraylength}). */
    public int length()
    {
        throw intrinsic();
    }
}
