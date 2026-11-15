/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package demo;

/**
 * The loops whose stop-poll cost {@code bench.Main} measures (runsrc/bench). This copy is in the image, so the metal
 * JIT compiles it WITHOUT stop polls; the Makefile's {@code runprogs} makes {@code bench/PollLoops} from this very
 * file with only the package line changed, and that copy -- defined by a {@code /run} program -- gets a poll at
 * every loop header. The two are therefore the same bytecode apart from their names: edit this file, not a copy.
 */
public final class PollLoops
{
    private PollLoops()
    {
    }

    /** n passes of the barest loop: one header, an add, an increment. */
    public static long count(int n)
    {
        long s = 0L;
        for (int i = 0; i < n; i++)
        {
            s += i;
        }
        return s;
    }

    /** reps walks over a: a load and a bounds check per pass. */
    public static long array(int[] a, int reps)
    {
        long s = 0L;
        for (int r = 0; r < reps; r++)
        {
            for (int i = 0; i < a.length; i++)
            {
                s += a[i];
            }
        }
        return s;
    }

    /** n passes, each calling a small static method. */
    public static long calls(int n)
    {
        long s = 0L;
        for (int i = 0; i < n; i++)
        {
            s += step(i);
        }
        return s;
    }

    private static long step(int i)
    {
        return (i ^ 0x5A) & 0xFF;
    }

    /** outer x inner passes of a nested loop: two headers. */
    public static long nested(int outer, int inner)
    {
        long s = 0L;
        for (int o = 0; o < outer; o++)
        {
            for (int i = 0; i < inner; i++)
            {
                s += o ^ i;
            }
        }
        return s;
    }
}
