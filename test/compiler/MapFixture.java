/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-20
 */
package compiler;

import magic.Address;

/**
 * Shapes for the reference maps (frames/GC arc S2, {@link RefMaps}); CompilerTest decodes their maps. Each call
 * is a map site, and the sites are numbered in source order.
 */
final class MapFixture
{
    private MapFixture()
    {
    }

    static Object sink(Object o)
    {
        return o;
    }

    static int sinkI(int i)
    {
        return i;
    }

    static Object two(Object a, Object b)
    {
        return b;
    }

    static long word(long v)
    {
        return v;
    }

    /** Slot 2 is a reference on one path and an int on the other: TOP at the join (site 2). */
    static void join(boolean c, Object o)
    {
        if (c)
        {
            Object t = o;
            sink(t);
        }
        else
        {
            int t = 3;
            sinkI(t);
        }
        sink(o);
    }

    /** {@code o} waits on the operand stack across the inner call (site 0): operand slot 0 is a live reference. */
    static Object live(Object o)
    {
        return two(o, sink(o));
    }

    /** A long (two slots) and an Address beside a reference (site 0): only the reference is one. */
    static void raw(Object o, long n)
    {
        Address a = Address.fromLong(n);
        long m = n + 1L;
        sink(o);
        word(a.toLong() + m);
    }
}
