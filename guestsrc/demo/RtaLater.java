/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-31
 */
package demo;

/** Implements {@link RtaLate}. Both are reached only from a reflectively-compiled body. */
public class RtaLater implements RtaLate
{
    public String late()
    {
        return "late-iface";
    }
}
