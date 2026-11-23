/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-23
 */
package jdk.internal.misc;

/**
 * Test hooks into this VM's collector, for probes (frames/GC arc S7-1). Metal-only: on another JVM the native is
 * unbound and the first call throws {@code UnsatisfiedLinkError}.
 */
public final class MetalGcTest
{
    private MetalGcTest()
    {
    }

    /**
     * What a copying collector does to one object: a copy in a fresh block that carries the original's identity hash
     * (the VM's {@code IdentityHash.move}). Nothing else is updated -- the original stays a separate live object, with
     * no hash of its own until one is taken again.
     */
    public static Object move(Object o)
    {
        return move0(o);
    }

    /** VM native ({@code VMNatives.testMove}). */
    private static native Object move0(Object o);
}
