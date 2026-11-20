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

import magic.Uninterruptible;

/** The same loops as {@link MapFixture}, in a {@link Uninterruptible} class: no yieldpoints (frames/GC arc S3). */
final class NoPollFixture implements Uninterruptible
{
    private NoPollFixture()
    {
    }

    static Object sink(Object o)
    {
        return o;
    }

    static void loop(Object o, int n)
    {
        for (int i = 0; i < n; i++)
        {
            sink(o);
        }
    }

    static int count(int n)
    {
        int s = 0;
        for (int i = 0; i < n; i++)
        {
            s += i;
        }
        return s;
    }
}
