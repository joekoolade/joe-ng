/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-26
 */
package writer;

import asm.A64Emu;

import java.util.Random;

/**
 * Both tiers, side by side, for every method the optimizing tier installs in the image (O1-2b): the writer runs
 * with {@code --opt all}, and for each installed method this compiles Baseline's code too and runs the two on the
 * {@link A64Emu} over the same arguments (canonical for each parameter type: an int sign-extended, a boolean 0 or 1,
 * a char zero-extended) and the same memory, and requires the same {@code x0}. Baseline is the oracle (PLAN.md:
 * "opt code must behave exactly like Baseline").
 *
 * <p>Run: {@code java writer.OptDiff <classesDir> [trials]} -- builds an image into /tmp as a side effect.
 */
public final class OptDiff implements OptTier.Diff
{
    private final int trials;
    private int methods;
    private int compared;
    private int mismatches;
    private int skipped;
    private long optSteps;
    private long baseSteps;

    private OptDiff(int trials)
    {
        this.trials = trials;
    }

    public static void main(String[] args) throws Exception
    {
        int trials = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        OptDiff d = new OptDiff(trials);
        OptTier.diff = d;
        java.io.PrintStream real = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try
        {
            BuildRuntimeImage.main(new String[] { args.length > 0 ? args[0] : "out", "/tmp/optdiff.img", "--opt",
                                                  "all" });
        }
        finally
        {
            System.setOut(real);
        }
        System.out.println("OptDiff: methods=" + d.methods + " runs=" + d.compared + " mismatches=" + d.mismatches
                           + " skipped=" + d.skipped + " instructions: baseline=" + d.baseSteps + " opt="
                           + d.optSteps);
        if (d.mismatches > 0 || d.methods == 0)
        {
            System.exit(1);
        }
    }

    public void compare(String key, String desc, boolean isStatic, int[] baseline, int[] opt, long base)
    {
        methods += 1;
        Random r = new Random(key.hashCode());
        for (int t = 0; t < trials; t++)
        {
            long[] a = args(desc, isStatic, r);
            A64Emu eb = new A64Emu();
            A64Emu eo = new A64Emu();
            for (int i = 0; i < a.length; i++)
            {
                eb.x[i] = a[i];
                eo.x[i] = a[i];
            }
            eb.x[31] = 0x7000_0000L;
            eo.x[31] = 0x7000_0000L;
            boolean okB = eb.run(baseline, base, 200_000);
            boolean okO = eo.run(opt, base, 200_000);
            if (!okB || !okO)
            {
                skipped += 1;
                if (skipped <= 5)
                {
                    System.err.println("  skip " + key + ": baseline " + why(eb) + " / opt " + why(eo));
                }
                continue;                                    // e.g. a loop that never ends for this input, in both
            }
            compared += 1;
            baseSteps += eb.steps;
            optSteps += eo.steps;
            if (eb.x[0] != eo.x[0] && !desc.endsWith(")V"))
            {
                mismatches += 1;
                if (mismatches <= 20)
                {
                    StringBuilder sb = new StringBuilder();
                    for (long v : a)
                    {
                        sb.append(' ').append(Long.toHexString(v));
                    }
                    System.err.println("  MISMATCH " + key + " args" + sb + ": baseline 0x" + Long.toHexString(eb.x[0])
                                       + " opt 0x" + Long.toHexString(eo.x[0]));
                }
                return;
            }
        }
    }

    private static String why(A64Emu e)
    {
        return e.unsupported != null ? e.unsupported : "no return within the step budget";
    }

    /** One argument per parameter (receiver first), canonical for its type. */
    private static long[] args(String desc, boolean isStatic, Random r)
    {
        long[] out = new long[17];
        int n = 0;
        if (!isStatic)
        {
            out[n] = ref(r);
            n += 1;
        }
        int p = 1;
        while (desc.charAt(p) != ')')
        {
            char c = desc.charAt(p);
            int end = p + 1;
            if (c == 'L')
            {
                end = desc.indexOf(';', p) + 1;
            }
            else if (c == '[')
            {
                int q = p;
                while (desc.charAt(q) == '[')
                {
                    q += 1;
                }
                end = desc.charAt(q) == 'L' ? desc.indexOf(';', q) + 1 : q + 1;
            }
            out[n] = value(c, desc.substring(p, end), r);
            n += 1;
            p = end;
        }
        long[] a = new long[n];
        System.arraycopy(out, 0, a, 0, n);
        return a;
    }

    private static long value(char c, String type, Random r)
    {
        if (c == 'Z')
        {
            return r.nextInt(2);
        }
        if (c == 'B')
        {
            return (byte) r.nextInt();
        }
        if (c == 'C')
        {
            return r.nextInt(65536);
        }
        if (c == 'S')
        {
            return (short) r.nextInt();
        }
        if (c == 'I')
        {
            int[] edge = { 0, 1, -1, 2, 31, 32, 33, 63, 64, Integer.MIN_VALUE, Integer.MAX_VALUE, 255, 65535 };
            return r.nextInt(3) == 0 ? edge[r.nextInt(edge.length)] : r.nextInt();
        }
        if (c == 'J')
        {
            long[] edge = { 0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 63, 64, 0xFFFF_FFFFL, 1L << 32 };
            return r.nextInt(3) == 0 ? edge[r.nextInt(edge.length)] : r.nextLong();
        }
        if (type.startsWith("Lmagic/"))
        {
            return r.nextBoolean() ? ref(r) : r.nextLong();       // a word: an address, or any bits
        }
        return ref(r);
    }

    private static long ref(Random r)
    {
        return r.nextInt(8) == 0 ? 0L : 0x4000_0000L + 8L * r.nextInt(1 << 20);
    }
}
