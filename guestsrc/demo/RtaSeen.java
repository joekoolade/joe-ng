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

/** Called from a statically reachable method, so RTA walks the call and pulls this class. */
public class RtaSeen
{
    public static String tag()
    {
        return "seen";
    }

    /**
     * Named only from a reflectively-reached method, but on a class the direct arm already pulled. This is
     * the CONTROL, not the bug: once a class is registered every one of its methods gets a deferral stub, so
     * the site resolves at patch time and never needs late linking. It is here to keep the boundary honest —
     * the gap is an unpulled CLASS, not an unmarked method.
     */
    public static String hidden()
    {
        return "seen-hidden";
    }
}
