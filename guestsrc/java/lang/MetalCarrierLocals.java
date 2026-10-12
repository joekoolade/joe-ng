/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-12
 */
package java.lang;

import jdk.internal.misc.CarrierThreadLocal;

/**
 * The bridge from {@code MetalJavaLangAccess} to {@link ThreadLocal}'s package-private carrier methods. Stock's
 * JavaLangAccess is an anonymous class inside {@code System}, so it calls them directly; ours lives in
 * {@code jdk.internal.access} and cannot. Reached by stock {@code sun.nio.ch.Util}'s temporary direct-buffer
 * cache, i.e. by every socket write.
 */
public final class MetalCarrierLocals
{
    private MetalCarrierLocals()
    {
    }

    public static <T> T get(CarrierThreadLocal<T> local)
    {
        return ((ThreadLocal<T>) local).getCarrierThreadLocal();
    }

    public static <T> void set(CarrierThreadLocal<T> local, T value)
    {
        ((ThreadLocal<T>) local).setCarrierThreadLocal(value);
    }

    public static void remove(CarrierThreadLocal<?> local)
    {
        ((ThreadLocal<?>) local).removeCarrierThreadLocal();
    }
}
