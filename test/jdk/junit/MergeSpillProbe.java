/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-23
 */

/**
 * A branch into a merge point of a DEEP-stack method (operand depth past the compiler's 7-register window) must not
 * run the fall-through edge's register flush. The compiler bound the label BEFORE that flush, so
 * {@code x = c ? new Holder() : null} compiled to {@code ...; bl Holder.<init>; b L2; L1: mov x9, #0; L2: str x9,
 * [slot0]} -- and the taken {@code goto} stored whatever x9 held after the constructor over the new object. Found in
 * frames/GC arc S7-2, when the write barrier's scratch use of x9 inside {@code ArrayList.<init>} turned
 * SwitchBootstraps' enum-label list into null. {@link Holder}'s constructor ends by loading an int, so x9 is not
 * {@code this} when it returns, barrier or no barrier.
 */
public class MergeSpillProbe
{
    static final class Holder
    {
        static int sink;

        int n;

        Holder(int n)
        {
            this.n = n;
            sink = 12345;                         // the constructor's last operand: x9 = 12345 at its return
        }

        @Override
        public String toString()
        {
            return "Holder(" + n + ")";
        }
    }

    static int sum8(int a, int b, int c, int d, int e, int f, int g, int h)
    {
        return a + b + c + d + e + f + g + h;
    }

    /** Deep: eight operands live at the sum8 call. Then the ternary whose taken edge is a goto into a merge. */
    static Object shape(boolean c, int k)
    {
        int s = sum8(k, k + 1, k + 2, k + 3, k + 4, k + 5, k + 6, k + 7);
        Holder h = c ? new Holder(s) : null;
        Holder g = !c ? new Holder(-s) : null;
        return String.valueOf(h) + " " + String.valueOf(g);
    }

    public static void main(String[] args)
    {
        System.out.println("true  -> " + shape(true, 1));
        System.out.println("false -> " + shape(false, 2));
        int ok = 0;
        int i = 0;
        while (i < 200)
        {
            Object r = shape((i & 1) == 0, i);
            if (r.toString().startsWith("Holder(") || r.toString().startsWith("null Holder("))
            {
                ok += 1;
            }
            i += 1;
        }
        System.out.println("200 rounds ok=" + ok);
    }
}
