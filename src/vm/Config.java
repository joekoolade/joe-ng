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
 * Exercises a static initializer ({@code <clinit>}): {@code mark} is set by a
 * static block rather than defaulting to 0, so it only reads as {@code '7'} if
 * the writer's eager-init sequence ran the class's {@code <clinit>} at boot.
 */
public final class Config
{
    static int mark;

    static
    {
        mark = 0x37;   // '7'
    }
}
