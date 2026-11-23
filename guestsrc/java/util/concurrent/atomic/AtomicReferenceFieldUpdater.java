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

import magic.Address;
import magic.Magic;

/**
 * JDK-free {@code AtomicReferenceFieldUpdater} -- like {@link AtomicIntegerFieldUpdater} for a reference field:
 * it stores the referent's address into the target's field slot. {@code get} is unsupported (there is no
 * long-&gt;reference intrinsic on metal; reading such a field is done directly, not through the updater).
 */
public class AtomicReferenceFieldUpdater<T, V>
{
    private final byte[] fname;

    protected AtomicReferenceFieldUpdater(String fieldName)
    {
        this.fname = jdk.internal.misc.MetalNames.utf8(fieldName);
    }

    public static <U, W> AtomicReferenceFieldUpdater<U, W> newUpdater(Class tclass, Class vclass, String fieldName)
    {
        long callerPc = Magic.readLR();                    // FIRST: return address into the caller (getCallerClass)
        FieldUpdaterCheck.validate(tclass, fieldName, callerPc, 'L');
        return new AtomicReferenceFieldUpdater<U, W>(fieldName);
    }

    private static native long fieldOffset0(byte[] fname, Object obj);

    public void set(T obj, V newValue)
    {
        // The offset FIRST: fieldOffset0 is a call (it may compile, so allocate, so run a minor collection that
        // moves young objects -- frames/GC arc S7-4); every address is taken after it, with nothing in between.
        long off = fieldOffset0(fname, obj);
        Address a = Address.fromObject(obj).plus(off);
        a.store((newValue == null) ? 0L : Magic.addrOf(newValue));
        jdk.internal.misc.MetalBarrier.log(obj);           // a reference store the compiler cannot see (S7-2)
    }

    public void lazySet(T obj, V newValue)
    {
        set(obj, newValue);
    }

    /**
     * ONE {@code cas64} on the field's 8-byte slot. It was {@code load; compare; store} under the overlay's note
     * "Single core, so the access is a plain load/store" -- a premise that expired with SMP scheduling (#180):
     * two cores could both read {@code expect} and both "succeed", a lost update in exactly the code that exists
     * to prevent one.
     */
    public boolean compareAndSet(T obj, V expect, V update)
    {
        // The offset FIRST: fieldOffset0 is a call (it may compile, so allocate, so run a minor collection that
        // moves young objects -- frames/GC arc S7-4); every address is taken after it, with nothing in between.
        long off = fieldOffset0(fname, obj);
        long e = (expect == null) ? 0L : Magic.addrOf(expect);
        long u = (update == null) ? 0L : Magic.addrOf(update);
        boolean ok = Magic.cas64(Magic.addrOf(obj) + off, e, u);
        jdk.internal.misc.MetalBarrier.log(obj);
        return ok;
    }
}
