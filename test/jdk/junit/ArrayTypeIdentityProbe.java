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
import java.lang.reflect.Array;

/**
 * An array class is ONE object however many distinct array types the program has asked for. The VM's
 * reference-array cache used to stop at 64 entries and silently stop caching, so every array type asked for
 * after that was minted afresh -- two {@code Object[][]} Types, two mirrors -- and {@code Object[][].class}
 * answered false against an erased parameter type once a batch of probes had filled it. This builds a chain
 * 100 deep (100 distinct array types, each the element of the next), rebuilds it, and compares the two
 * chains link by link, then checks the literal and {@code getClass()} routes against the rebuilt classes.
 */
public class ArrayTypeIdentityProbe
{
    private static final int DEPTH = 100;

    public static void main(String[] args)
    {
        Class<?>[] first = chain();
        Class<?>[] again = chain();
        int same = 0;
        int k = 0;
        while (k < DEPTH)
        {
            if (first[k] == again[k])
            {
                same += 1;
            }
            k += 1;
        }
        System.out.println("chain depth=" + DEPTH + " identical=" + same);
        System.out.println("deepest name ok=" + first[DEPTH - 1].getName().equals("[".repeat(DEPTH) + "Ljava.lang.Object;"));
        System.out.println("Object[][].class==chain[1] " + (Object[][].class == again[1]));
        System.out.println("new Object[1][1].getClass()==Object[][].class " + (new Object[1][1].getClass() == Object[][].class));
        System.out.println("String[][][].class==newInstance " + (String[][][].class == Array.newInstance(String[][].class, 0).getClass()));
        System.out.println("component of chain[2]==chain[1] " + (again[2].getComponentType() == first[1]));
        System.out.println("ArrayTypeIdentityProbe done");
    }

    /** {@code Object[]}, {@code Object[][]}, ... {@code DEPTH} levels, each built from the previous. */
    private static Class<?>[] chain()
    {
        Class<?>[] out = new Class<?>[DEPTH];
        Class<?> c = Object.class;
        int k = 0;
        while (k < DEPTH)
        {
            c = Array.newInstance(c, 0).getClass();
            out[k] = c;
            k += 1;
        }
        return out;
    }
}
