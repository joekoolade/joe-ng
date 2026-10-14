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

/** JDK-free {@code AtomicLongFieldUpdater} -- like {@link AtomicIntegerFieldUpdater} with 64-bit accesses. */
public class AtomicLongFieldUpdater<T>
{
    private final byte[] fname;

    protected AtomicLongFieldUpdater(String fieldName)
    {
        this.fname = jdk.internal.misc.MetalNames.utf8(fieldName);
    }

    public static <U> AtomicLongFieldUpdater<U> newUpdater(Class tclass, String fieldName)
    {
        long callerPc = Magic.readLR();                    // FIRST: return address into the caller (getCallerClass)
        FieldUpdaterCheck.validate(tclass, fieldName, callerPc, 'J');
        return new AtomicLongFieldUpdater<U>(fieldName);
    }

    private static native long fieldOffset0(byte[] fname, Object obj);

    public void set(T obj, long newValue)
    {
        Magic.store64(Magic.addrOf(obj) + fieldOffset0(fname, obj), newValue);
    }

    public void lazySet(T obj, long newValue)
    {
        set(obj, newValue);
    }

    public long get(T obj)
    {
        return Magic.load64(Magic.addrOf(obj) + fieldOffset0(fname, obj));
    }

    /**
     * ONE {@code cas64} on the field's 8-byte slot. It was {@code load; compare; store} under the overlay's note
     * "Single core, so the access is a plain load/store" -- a premise that expired with SMP scheduling (#180):
     * two cores could both read {@code expect} and both "succeed", a lost update in exactly the code that exists
     * to prevent one.
     */
    public boolean compareAndSet(T obj, long expect, long update)
    {
        return Magic.cas64(Magic.addrOf(obj) + fieldOffset0(fname, obj), expect, update);
    }
}
