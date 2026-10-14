/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-11
 */
package java.util.concurrent.atomic;

import magic.Magic;

/**
 * JDK-free {@code AtomicIntegerFieldUpdater}. Instead of the stock Unsafe field-offset reflection, it carries
 * the field NAME and resolves its byte offset from the TARGET object's class at call time -- the exact
 * mechanism the {@link java.lang.invoke.VarHandle} overlay uses ({@link #fieldOffset0} -> {@code
 * VM.vhFieldOffset}). Reads are plain loads; writes take the whole 8-byte slot and the CAS is one {@code cas64}
 * (see {@link #compareAndSet}). {@code newUpdater} takes a raw
 * {@code Class} (joe-ng's {@code Class} overlay is non-generic) but still infers the updater's type parameter.
 */
public class AtomicIntegerFieldUpdater<T>
{
    private final byte[] fname;

    protected AtomicIntegerFieldUpdater(String fieldName)
    {
        this.fname = jdk.internal.misc.MetalNames.utf8(fieldName);
    }

    public static <U> AtomicIntegerFieldUpdater<U> newUpdater(Class tclass, String fieldName)
    {
        long callerPc = Magic.readLR();                    // FIRST: return address into the caller (getCallerClass)
        FieldUpdaterCheck.validate(tclass, fieldName, callerPc, 'I');
        return new AtomicIntegerFieldUpdater<U>(fieldName);
    }

    /** Byte offset of {@code fname} within {@code obj}'s class -> {@code VM.vhFieldOffset} (loader field registry). */
    private static native long fieldOffset0(byte[] fname, Object obj);

    /** The whole slot, sign-extended -- what compiled {@code putfield} leaves (see Field.slot for the cost of a
     *  32-bit store over a negative value). */
    public void set(T obj, int newValue)
    {
        Magic.store64(Magic.addrOf(obj) + fieldOffset0(fname, obj), newValue);
    }

    public void lazySet(T obj, int newValue)
    {
        set(obj, newValue);
    }

    public int get(T obj)
    {
        return Magic.load32(Magic.addrOf(obj) + fieldOffset0(fname, obj));
    }

    /**
     * ONE {@code cas64} on the field's 8-byte slot. It was {@code load; compare; store} under the overlay's note
     * "Single core, so the access is a plain load/store" -- a premise that expired with SMP scheduling (#180):
     * two cores could both read {@code expect} and both "succeed", a lost update in exactly the code that exists
     * to prevent one. An int field's slot holds the value SIGN-EXTENDED, so comparing and swapping the whole slot is
     * exact -- Unsafe.compareAndSetInt does the same for a field.
     */
    public boolean compareAndSet(T obj, int expect, int update)
    {
        return Magic.cas64(Magic.addrOf(obj) + fieldOffset0(fname, obj), expect, update);
    }
}
