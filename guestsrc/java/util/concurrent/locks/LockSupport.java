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
package java.util.concurrent.locks;

import magic.Magic;

/**
 * A JDK-free, minimal {@code java.util.concurrent.locks.LockSupport}: permit-based park/unpark backed by the
 * VM scheduler. {@link #park()} blocks the current thread until a permit is available; {@link #unpark} makes
 * one available (and wakes a parked thread). Enough for the JoinWithDuration test's non-terminating threads.
 */
public class LockSupport
{
    private LockSupport()
    {
    }

    public static void park()
    {
        Magic.park();
    }

    public static void unpark(Thread thread)
    {
        if (thread != null)
        {
            Magic.unpark(thread);
        }
    }
}
