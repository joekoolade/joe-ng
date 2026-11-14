/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package hello;

/** Calls back into {@link Main}: the reference that points the other way. */
final class Util
{
    private Util()
    {
    }

    static int twice(int v)
    {
        Main.count();
        return v * 2;
    }

    static void fail(String why)
    {
        throw new IllegalStateException("thrown in Util: " + why);
    }
}
