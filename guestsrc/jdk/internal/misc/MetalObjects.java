/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-19
 */
package jdk.internal.misc;

/**
 * The body of the native {@code Object.clone()} ({@code Loader.nativeBufAt} binds it here; the receiver arrives as
 * the one argument). What HotSpot's {@code JVM_Clone} does: an object whose class does not implement
 * {@link Cloneable} is a {@link CloneNotSupportedException} carrying the class's name, and anything else is a
 * shallow copy -- same class, every field copied as it is. An array never gets here: {@code [T.clone()} is a
 * compiler intrinsic, and every array is cloneable.
 */
public final class MetalObjects
{
    private MetalObjects()
    {
    }

    static Object clone(Object o) throws CloneNotSupportedException
    {
        if (!(o instanceof Cloneable))
        {
            throw new CloneNotSupportedException(o.getClass().getName());
        }
        return copy(o);
    }

    /** VM native ({@code Loader.nativeBufAt} -> {@code VMNatives.arrayClone}): the block copied, header and all. */
    private static native Object copy(Object o);
}
