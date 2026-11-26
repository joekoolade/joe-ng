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
import compiler.BaselineCompiler.CompiledMethod;

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
    private long calls;
    private long moved;

    private OptDiff(int trials)
    {
        this.trials = trials;
    }

    public static void main(String[] args) throws Exception
    {
        int trials = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        OptDiff d = new OptDiff(trials);
        classes = java.nio.file.Path.of(args.length > 0 ? args[0] : "out");
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
                           + " skipped=" + d.skipped + " calls=" + d.calls + " movedRefs=" + d.moved
                           + " instructions: baseline=" + d.baseSteps + " opt=" + d.optSteps);
        if (d.mismatches > 0 || d.methods == 0)
        {
            System.exit(1);
        }
    }

    public void compare(String key, String desc, boolean isStatic, CompiledMethod baseline, CompiledMethod opt,
                        long base)
    {
        methods += 1;
        Random r = new Random(key.hashCode());
        for (int t = 0; t < trials; t++)
        {
            long[] a = args(desc, isStatic, r);
            long seed = r.nextLong();
            Callee cb = new Callee(baseline, seed, 1);
            Callee co = new Callee(opt, seed, 2);
            A64Emu eb = cb.emu;
            A64Emu eo = co.emu;
            for (int i = 0; i < a.length; i++)
            {
                eb.x[i] = a[i];
                eo.x[i] = a[i];
            }
            eb.x[31] = 0x7000_0000L;
            eo.x[31] = 0x7000_0000L;
            boolean okB = eb.run(baseline.words(), base, 200_000);
            boolean okO = eo.run(opt.words(), base, 200_000);
            if (co.error != null || cb.error != null)
            {
                mismatches += 1;
                System.err.println("  CALL CHECK " + key + ": baseline " + cb.error + " / opt " + co.error);
                return;
            }
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
            calls += co.calls;
            moved += co.moved;
            if (cb.calls != co.calls || cb.trace != co.trace || (eb.x[0] != eo.x[0] && !desc.endsWith(")V")))
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
                                       + " opt 0x" + Long.toHexString(eo.x[0]) + " calls " + cb.calls + "/"
                                       + co.calls + " trace " + (cb.trace == co.trace ? "same" : "differs"));
                }
                return;
            }
        }
    }

    /**
     * A tier's callees, simulated: each BL is looked up in that tier's relocations; the callee's answer is a hash
     * of its name and arguments (so both tiers get the same answer for the same call), it leaves garbage in every
     * register a call may destroy, and DURING it a moving collector runs -- every frame slot the tier's GC map
     * marks as a reference is moved to a new address. A reference the map missed then keeps its old address, and
     * the run diverges from Baseline's, whose maps are the measured-correct ones (frames/GC arc S4-S6).
     */
    private final class Callee implements A64Emu.CallHook
    {
        final A64Emu emu = new A64Emu();
        final CompiledMethod cm;
        final Random garbage;
        final java.util.HashMap<Integer, String> byWord = new java.util.HashMap<>();
        String error;
        int calls;
        int moved;
        long trace = 17;                                   // every call's name and arguments, in order

        Callee(CompiledMethod cm, long seed, int tier)
        {
            this.cm = cm;
            this.garbage = new Random(seed * 31 + tier);
            for (int i = 0; i < cm.relocs().callSites().size(); i++)
            {
                var c = cm.relocs().callSites().get(i);
                byWord.put(c.wordIndex(), c.calleeKey());
            }
            emu.calls = this;
        }

        public void call(A64Emu e, int word)
        {
            String key = byWord.get(word);
            if (key == null)
            {
                error = "BL at word " + word + " has no relocation";
                e.unsupported = error;
                return;
            }
            calls += 1;
            if (!collect(e, word + 1))
            {
                e.unsupported = error;
                return;
            }
            int paren = key.indexOf('(');
            String d = key.substring(paren);
            int n = paramCount(d) + (isStatic(key) ? 0 : 1);
            long h = key.hashCode();
            for (int i = 0; i < n && i < 16; i++)
            {
                h = h * 0x100000001B3L ^ e.x[i];
            }
            trace = trace * 31 + h;
            for (int i = 0; i <= 18; i++)
            {
                e.x[i] = garbage.nextLong();
            }
            e.x[0] = result(d.charAt(d.indexOf(')') + 1), h);
        }

        /** The simulated collection: move every reference the map says the frame at sp holds at this call. */
        private boolean collect(A64Emu e, int retWord)
        {
            int[] w = cm.words();
            int m = cm.gcMapWord();
            if (m == 0 || w[m] != 0x50414D47)
            {
                error = "a call with no GC map (return word " + retWord + ")";
                return false;
            }
            int nSites = w[m + 1];
            int nLoc = w[m + 2];
            int nStk = w[m + 3];
            int locBase = w[m + 4];
            int opBase = w[m + 5];
            int bitWords = w[m + 6];
            int site = -1;
            for (int i = 0; i < nSites; i++)
            {
                if (w[m + 10 + 2 * i] == retWord)
                {
                    site = i;
                }
            }
            if (site < 0)
            {
                error = "no GC map site for return word " + retWord;
                return false;
            }
            int sw = w[m + 10 + 2 * site + 1];
            int bits = m + 10 + 2 * nSites + (sw & 0xFFFF) * bitWords;
            int live = Math.min((sw >>> 16) & 0xFF, nStk);
            long sp = e.x[31];
            for (int s = 0; s < nLoc + live; s++)
            {
                int t = (w[bits + (2 * s >> 5)] >>> (2 * s & 31)) & 3;
                if (t != 1)
                {
                    continue;
                }
                long a = s < nLoc ? sp + locBase + 8L * s : sp + opBase + 8L * (s - nLoc);
                long v = e.load(a, 8);
                if (v != 0)
                {
                    e.store(a, 8, v + MOVE);
                    moved += 1;
                }
            }
            return true;
        }
    }

    /** How far the simulated collector moves an object. */
    private static final long MOVE = 0x1000_0000L;

    private static long result(char k, long h)
    {
        if (k == 'I')
        {
            return (int) h;
        }
        if (k == 'Z')
        {
            return h & 1;
        }
        if (k == 'B')
        {
            return (byte) h;
        }
        if (k == 'C')
        {
            return h & 0xFFFF;
        }
        if (k == 'S')
        {
            return (short) h;
        }
        if (k == 'L' || k == '[')
        {
            return (h & 7) == 0 ? 0L : 0x4000_0000L + 8L * (h & 0xFFFFF);
        }
        return h;
    }

    private static int paramCount(String d)
    {
        int n = 0;
        int p = 1;
        while (d.charAt(p) != ')')
        {
            while (d.charAt(p) == '[')
            {
                p += 1;
            }
            if (d.charAt(p) == 'L')
            {
                p = d.indexOf(';', p);
            }
            p += 1;
            n += 1;
        }
        return n;
    }

    private static final java.util.HashMap<String, Boolean> STATIC = new java.util.HashMap<>();
    private static java.nio.file.Path classes = java.nio.file.Path.of("out");

    /** Whether the callee named by {@code key} is static (so takes no receiver), from its classfile. */
    private static boolean isStatic(String key)
    {
        Boolean b = STATIC.get(key);
        if (b != null)
        {
            return b;
        }
        int dot = key.indexOf('.');
        int paren = key.indexOf('(');
        String owner = key.substring(0, dot);
        String name = key.substring(dot + 1, paren);
        String desc = key.substring(paren);
        boolean st = false;
        try
        {
            java.nio.file.Path p = classes.resolve(owner + ".class");
            byte[] bytes;
            if (java.nio.file.Files.exists(p))
            {
                bytes = java.nio.file.Files.readAllBytes(p);
            }
            else
            {
                java.nio.file.FileSystem jrt = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"));
                bytes = java.nio.file.Files.readAllBytes(jrt.getPath("/modules/java.base/" + owner + ".class"));
            }
            classfile.ClassFile cf = new classfile.ClassFile(bytes);
            for (classfile.ClassFile.Method m : cf.methods())
            {
                if (m.name.equals(name) && m.descriptor.equals(desc))
                {
                    st = m.isStatic;
                }
            }
        }
        catch (java.io.IOException e)
        {
            st = false;
        }
        STATIC.put(key, st);
        return st;
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
