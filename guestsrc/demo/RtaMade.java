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
 * Instantiated only from a reflectively-reached method, so nothing pulls it: the {@code new} naming it is
 * unresolvable when that method is compiled, and has to be resolved when the site is actually reached.
 */
public class RtaMade
{
    public String tag()
    {
        return "made";
    }
}
