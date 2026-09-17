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

/** Overrides {@code Animal.sound()} — takes the same vtable slot (0) as the parent. */
public final class Dog extends Animal
{
    @Override
    public int sound()
    {
        return 0x57;   // 'W'
    }
}
