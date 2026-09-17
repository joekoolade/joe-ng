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

/** One implementer of {@link Speaker}. */
public final class Robot implements Speaker
{
    /** {@code new Robot(); s.speak()} via a Speaker ref — the metal writer's invokeinterface target. */
    public static int probe()
    {
        Speaker s = new Robot();
        return s.speak();
    }

    @Override
    public int speak()
    {
        return 0x52;   // 'R'
    }
}
