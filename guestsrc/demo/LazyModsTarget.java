/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-28
 */
package demo;

/**
 * The target of {@code LazyModsProbe}. It sits in package {@code demo} ON PURPOSE: the access check it
 * exercises only bites when the reflecting caller is in a DIFFERENT package, and the probe is in the
 * default one. A target in the same package would pass whatever modifiers the registry recorded.
 */
public final class LazyModsTarget
{
    /** public static, and CALLED DIRECTLY by the probe before being reflected on -- which is the condition. */
    public static int pub()
    {
        return 42;
    }

    /** public instance, same treatment. */
    public int inst()
    {
        return 7;
    }

    /** Genuinely package-private: the check must still REFUSE this one, or "fixed" would mean "allows all". */
    static int pkg()
    {
        return 1;
    }
}
