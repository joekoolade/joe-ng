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

import java.util.Objects;
import magic.Magic;

/**
 * Runs the UNMODIFIED JDK {@code java.util.Objects} on metal: equals/hashCode dispatch through the mini
 * {@link Object} root's vtable slots into a String key's real overrides (content equals + hashCode); isNull/
 * nonNull are pure; requireNonNull returns its arg or throws a real {@code NullPointerException} (caught here
 * via cross-method unwind). Exercises the object side of the surface, not just numerics.
 */
public class ObjectsDemo
{
    public static void main(String[] args)
    {
        showBool("equals(\"ab\",\"ab\")", Objects.equals("ab", "ab"));      // 1 (content equals)
        showBool("equals(\"ab\",\"cd\")", Objects.equals("ab", "cd"));      // 0
        showBool("equals(null,null)", Objects.equals(null, null));          // 1
        showBool("equals(\"ab\",null)", Objects.equals("ab", null));        // 0
        showInt("hashCode(\"hello\")", Objects.hashCode("hello"));          // 99162322
        showInt("hashCode(null)", Objects.hashCode(null));                  // 0
        showBool("isNull(null)", Objects.isNull(null));                     // 1
        showBool("nonNull(\"x\")", Objects.nonNull("x"));                   // 1

        String kept = (String) Objects.requireNonNull("kept");
        showBool("requireNonNull(\"kept\") ok", kept != null);              // 1
        int caught = 0;
        try
        {
            Objects.requireNonNull(null);                                   // -> NullPointerException
        }
        catch (Exception e)
        {
            caught = 1;
        }
        showInt("requireNonNull(null) throws", caught);                     // 1
    }

    private static void showBool(String label, boolean b)
    {
        showInt(label, b ? 1 : 0);
    }

    private static void showInt(String label, int v)
    {
        Magic.printStr("  Objects.");
        Magic.printStr(label);
        Magic.printStr(" = ");
        Magic.printStr(Integer.toString(v));
        Magic.printStr("\n");
    }
}
