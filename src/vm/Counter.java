/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-18
 */
package vm;

/**
 * A minimal class with mutable static state, exercising {@code getstatic}/
 * {@code putstatic} against the image's statics area. {@code count} defaults to 0
 * (the statics area is zero-initialized, matching JVM semantics) — no static
 * initializer, so no {@code <clinit>} is needed yet.
 */
public final class Counter
{
    static int count;

    public static void bump()
    {
        count = count + 1;
    }

    public static int get()
    {
        return count;
    }
}
