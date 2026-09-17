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

/** A second implementer of {@link Speaker}, to show interface dispatch selects the right one. */
public final class Phone implements Speaker
{
    @Override
    public int speak()
    {
        return 0x50;   // 'P'
    }
}
