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

/** Base class of a small hierarchy — {@code sound()} is a virtual method (vtable slot 0). */
public class Animal
{
    /** {@code new Dog().sound()} — the metal writer's cross-class new + virtual-dispatch target. */
    public static int dogSound()
    {
        return new Dog().sound();
    }

    public int sound()
    {
        return 0x3F;   // '?'
    }
}
