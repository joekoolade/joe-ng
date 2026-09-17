/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-27
 */
package demo;

/**
 * Named ONLY from a method that is itself reached only through {@code Method.invoke}. Nothing statically
 * reachable mentions this class, so RTA never marks it and the demand-load closure never contains it — the
 * call site naming {@link #tag()} is the one late link resolution has to close.
 */
public class RtaUnseen
{
    public static String tag()
    {
        return "unseen";
    }
}
