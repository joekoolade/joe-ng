/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-27
 */
package demo;

import magic.Magic;

/**
 * Loads the UNMODIFIED JDK {@code java/lang/Integer} through the normal closure/loadAll path (not the
 * isolated single-method compile) and calls {@code Integer.parseInt} — proving loadAll compiles only the
 * methods reachable from this entry. If it compiled every Integer method, its unreachable ones (toString,
 * the String.format paths, ...) would drag in unbuilt deps and choke; reachability prunes them.
 */
public class ParseAllDemo
{
    public static void main(String[] args)
    {
        show("42", Integer.parseInt("42"));
        show("12345", Integer.parseInt("12345"));
        show("-7", Integer.parseInt("-7"));
        show("2147483647", Integer.parseInt("2147483647"));
    }

    private static void show(String label, int v)
    {
        Magic.printStr("  parseInt(\"" + label + "\") = " + v + "\n");
    }
}
