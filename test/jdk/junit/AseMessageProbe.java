/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-22
 *
 * SPDX-License-Identifier: EPL-2.0
 */

/**
 * The message of an {@code ArrayStoreException} from a covariant {@code aastore}: stock gives the stored
 * value's class name exactly as {@code getClass().getName()} spells it -- dotted for a class, descriptor form for
 * an array, {@code $} for a nested class. The VM used to give no message at all. A lambda value is left out: its
 * class name is a JVM implementation detail. The last case stores a legal value, so nothing is thrown.
 */
public class AseMessageProbe
{
    enum Color
    {
        RED
    }

    static void store(String what, Object[] arr, Object v)
    {
        try
        {
            arr[0] = v;
            System.out.println(what + ": stored");
        }
        catch (ArrayStoreException e)
        {
            System.out.println(what + ": " + e.getMessage());
        }
    }

    public static void main(String[] args)
    {
        store("String into Integer[]", new Integer[1], "s");
        store("int[] into String[]", new String[1], new int[1]);
        store("String[][] into String[]", new String[1], new String[1][1]);
        store("nested enum into Number[]", new Number[1], Color.RED);
        store("Object into Runnable[]", new Runnable[1], new Object());
        store("Object into Object[][]", new Object[1][], new Object());
        store("Integer into Number[]", new Number[1], 7);
        System.out.println("AseMessageProbe done");
    }
}
