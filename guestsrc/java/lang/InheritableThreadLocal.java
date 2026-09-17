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

/**
 * A JDK-free {@code java.lang.InheritableThreadLocal}: a {@link ThreadLocal} whose value is passed from a
 * parent thread to a child at the child's creation (Thread's 5-arg constructor with inheritThreadLocals),
 * transformed by {@link #childValue}. Subclasses override {@link #initialValue}/{@link #childValue}.
 */
public class InheritableThreadLocal<T> extends ThreadLocal<T>
{
    /** The child's value, derived from the parent's. Default = the parent's value unchanged. */
    protected T childValue(T parentValue)
    {
        return parentValue;
    }

    @Override
    boolean inheritable()
    {
        return true;
    }
}
