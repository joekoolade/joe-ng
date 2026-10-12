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
package java.lang;

import jdk.internal.misc.TerminatingThreadLocal;

/**
 * A JDK-free {@code java.lang.ThreadLocal}: per-thread storage kept in a small map on the current Thread
 * (see {@code Thread.tl*}). {@link #get()} lazily initialises the entry via {@link #initialValue()}.
 * {@link InheritableThreadLocal} extends this so a child thread inherits the parent's value at creation.
 */
public class ThreadLocal<T>
{
    /** The value for a thread that has no entry yet (default null; subclasses override). */
    protected T initialValue()
    {
        return null;
    }

    /** Inheritance hook: the child's value derived from the parent's (InheritableThreadLocal overrides). */
    T childValue(T parentValue)
    {
        return parentValue;
    }

    /** Whether a child thread inherits this thread-local (only InheritableThreadLocal does). */
    boolean inheritable()
    {
        return false;
    }

    public T get()
    {
        return get(Thread.currentThread());
    }

    public void set(T value)
    {
        Thread.currentThread().tlPut(this, value);
    }

    public void remove()
    {
        Thread.currentThread().tlRemove(this);
    }

    /*
     * The CARRIER variants, reached from CarrierThreadLocal through JavaLangAccess (MetalCarrierLocals). Stock
     * runs them against Thread.currentCarrierThread(); this VM has no virtual threads, so every thread is its own
     * carrier and the carrier is Thread.currentThread(). They exist because CarrierThreadLocal OVERRIDES
     * get/set/remove to route here -- calling local.get() instead would recurse.
     */
    T getCarrierThreadLocal()
    {
        return get(Thread.currentThread());
    }

    void setCarrierThreadLocal(T value)
    {
        Thread.currentThread().tlPut(this, value);
    }

    void removeCarrierThreadLocal()
    {
        Thread.currentThread().tlRemove(this);
    }

    @SuppressWarnings("unchecked")
    private T get(Thread t)
    {
        int i = t.tlIndex(this);
        if (i >= 0)
        {
            return (T) t.tlValueAt(i);
        }
        return setInitialValue(t);
    }

    /** Stock's setInitialValue: a TerminatingThreadLocal that gets its value this way is registered, as set() would. */
    private T setInitialValue(Thread t)
    {
        T v = initialValue();
        t.tlPut(this, v);
        if (this instanceof TerminatingThreadLocal<?> ttl)
        {
            TerminatingThreadLocal.register(ttl);
        }
        return v;
    }

    /**
     * {@code ThreadLocal.withInitial(supplier)} -- the factory form, implemented as an anonymous subclass
     * overriding {@code initialValue()}, exactly as stock's {@code SuppliedThreadLocal} does.
     */
    public static <S> ThreadLocal<S> withInitial(java.util.function.Supplier<? extends S> supplier)
    {
        return new ThreadLocal<S>()
        {
            @Override
            protected S initialValue()
            {
                return supplier.get();
            }
        };
    }
}
