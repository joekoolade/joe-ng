/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-19
 */
package vm;

/**
 * Implements {@link Greeter} with {@code greet()} as its <em>first</em> virtual
 * method, so greet lands at vtable slot 0. Contrast {@link Beta}, where a filler
 * method pushes greet to slot 1 — the two disagree on the slot, so dispatching an
 * interface call by vtable slot would be wrong for one of them.
 */
public class Alpha implements Greeter
{
    @Override
    public int greet()
    {
        return 20;               // vtable slot 0
    }
}
