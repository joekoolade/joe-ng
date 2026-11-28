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
    private long dynCalls;
    private long thrownRuns;
    private long caught;
    /** O2-0: the speed measures -- per method (compared runs only), so a few long loops do not decide them. */
    private double logRatio;
    private int ratioMethods;
    private int slower;
    private long baseWords;
    private long optWords;
    private final java.util.ArrayList<String> perMethod = new java.util.ArrayList<>();

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
        int shapes = 0;
        int shapesIn = 0;
        classfile.ClassFile sc = classfile.ClassFile.parse(classes.resolve("writer/OptShapes.class"));
        for (classfile.ClassFile.Method m : sc.methods())
        {
            if (m.name.startsWith("shape"))
            {
                shapes += 1;
                String key = sc.thisClassName() + "." + m.name + m.descriptor;
                if (OptTier.compile(key, sc, m, 0x0200_0000L, false, OptTier.lastResolver) != null)
                {
                    shapesIn += 1;
                }
                else
                {
                    System.err.println("  SHAPE NOT INSTALLED " + key);
                }
            }
        }
        System.out.println("OptDiff: shapes installed " + shapesIn + " of " + shapes);
        System.out.println("OptDiff: methods=" + d.methods + " runs=" + d.compared + " mismatches=" + d.mismatches
                           + " skipped=" + d.skipped + " calls=" + d.calls + " caught=" + d.caught + " thrownOut=" + d.thrownRuns + " dispatches=" + d.dynCalls + " movedRefs=" + d.moved
                           + " instructions: baseline=" + d.baseSteps + " opt=" + d.optSteps);
        System.out.println(String.format("OptDiff: speed: geomean opt/baseline instructions=%.4f over %d methods "
                                         + "(%d slower); code words baseline=%d opt=%d (%.4f)",
                                         Math.exp(d.logRatio / Math.max(1, d.ratioMethods)), d.ratioMethods, d.slower,
                                         d.baseWords, d.optWords, (double) d.optWords / Math.max(1, d.baseWords)));
        String top = System.getenv("OPTDIFF_TOP");
        if (top != null)
        {
            d.perMethod.sort(null);
            int n = Integer.parseInt(top);
            for (int i = 0; i < n && i < d.perMethod.size(); i++)
            {
                System.out.println("  WORST " + d.perMethod.get(d.perMethod.size() - 1 - i).substring(9));
            }
        }
        if (d.mismatches > 0 || d.methods == 0 || shapesIn != shapes)
        {
            System.exit(1);
        }
    }

    public void compare(String key, classfile.ClassFile cf, classfile.ClassFile.Method method,
                        CompiledMethod baseline, CompiledMethod opt, compiler.opt.OptCompiler oc, long base)
    {
        String desc = method.descriptor;
        boolean isStatic = method.isStatic;
        if ((System.getenv("OPTDIFF_HANDLERS") != null && method.exceptions.length == 0)
            || (System.getenv("OPTDIFF_SHAPESONLY") != null && !key.startsWith("writer/OptShapes.")))
        {
            return;                                          // only the methods with an exception table
        }
        methods += 1;
        if (System.getenv("OPTDIFF_SHAPES") != null)
        {
            java.util.HashSet<Integer> hs = new java.util.HashSet<>();
            int typed = 0;
            for (var x : method.exceptions)
            {
                hs.add(x.handlerPc());
                typed += x.catchType() != 0 ? 1 : 0;
            }
            int athrows = 0;
            for (int i = 0; i < method.code.length; i++)
            {
                athrows += (method.code[i] & 0xFF) == 0xBF ? 1 : 0;   // a rough count (operand bytes too)
            }
            System.err.println("  SHAPE " + key + " entries=" + method.exceptions.length + " handlers=" + hs.size()
                               + " typed=" + typed + " ranges=" + opt.handlers().size());
            for (int i = 0; i < opt.handlers().size(); i++)
            {
                var hr = opt.handlers().get(i);
                int last = opt.words()[hr.endWord() - 1];
                System.err.println("    range [" + hr.startWord() + "," + hr.endWord() + ") -> " + hr.handlerWord()
                                   + " " + hr.catchClass() + " last=" + Integer.toHexString(last)
                                   + ((last & 0xFC000000) == 0x94000000 ? " (BL)" : ""));
            }
        }
        String rel = sameRelocations(baseline, opt);
        if (rel != null)
        {
            mismatches += 1;
            System.err.println("  RELOCATIONS " + key + ": " + rel);
            return;
        }
        Random r = new Random(key.hashCode());
        long mb = 0;
        long mo = 0;
        for (int t = 0; t < trials; t++)
        {
            long[] a = args(desc, isStatic, r);
            long seed = r.nextLong();
            Callee cb = new Callee(baseline, seed, 1, cf, method, null, base);
            Callee co = new Callee(opt, seed, 2, cf, method, oc, base);
            A64Emu eb = cb.emu;
            A64Emu eo = co.emu;
            for (int i = 0; i < a.length; i++)
            {
                eb.x[i] = a[i];
                eo.x[i] = a[i];
            }
            eb.x[31] = 0x7000_0000L;
            eo.x[31] = 0x7000_0000L;
            for (int i = 0; i < 10; i++)
            {
                long v = r.nextLong();                       // the caller's callee-saved values (O2-3)
                eb.x[19 + i] = v;
                eo.x[19 + i] = v;
                cb.entryRegs[i] = v;
                co.entryRegs[i] = v;
            }
            boolean okB = eb.run(patched(baseline), base, 200_000);
            boolean okO = eo.run(patched(opt), base, 200_000);
            if (co.error != null || cb.error != null)
            {
                mismatches += 1;
                System.err.println("  CALL CHECK " + key + ": baseline " + cb.error + " / opt " + co.error);
                return;
            }
            if (!okB || !okO)
            {
                skipped += 1;
                if (skipped <= 5 || System.getenv("OPTDIFF_SKIPS") != null)
                {
                    System.err.println("  skip " + key + ": baseline " + why(eb) + " / opt " + why(eo));
                }
                continue;                                    // e.g. a loop that never ends for this input, in both
            }
            compared += 1;
            for (int i = 0; i < 10 && !eo.thrown; i++)
            {
                if (eo.x[19 + i] != co.entryRegs[i] && co.calleeError == null)
                {
                    co.calleeError = "x" + (19 + i) + " at return is 0x" + Long.toHexString(eo.x[19 + i]);
                }
            }
            if (co.calleeError != null || cb.calleeError != null)
            {
                mismatches += 1;
                System.err.println("  CALLEE-SAVED " + key + ": opt " + co.calleeError + " / baseline " + cb.calleeError);
                return;
            }
            if (eb.thrown)
            {
                thrownRuns += 1;
            }
            caught += co.caught;
            baseSteps += eb.steps;
            optSteps += eo.steps;
            mb += eb.steps;
            mo += eo.steps;
            calls += co.calls;
            moved += co.moved;
            dynCalls += co.dispatches;
            String memDiff = memory(eb, eo);
            if (memDiff != null || cb.calls != co.calls || cb.trace != co.trace || eb.thrown != eo.thrown
                || cb.thrownExc != co.thrownExc || cb.caught != co.caught
                || (!eb.thrown && eb.x[0] != eo.x[0] && !desc.endsWith(")V")))
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
                                       + co.calls + " trace " + (cb.trace == co.trace ? "same" : "differs")
                                       + " thrown " + eb.thrown + "/" + eo.thrown + " caught " + cb.caught + "/"
                                       + co.caught
                                       + (memDiff == null ? "" : " memory: " + memDiff));
                }
                return;
            }
        }
        baseWords += baseline.words().length;
        optWords += opt.words().length;
        if (mb > 0 && mo > 0)
        {
            double lr = Math.log((double) mo / mb);
            logRatio += lr;
            ratioMethods += 1;
            if (mo > mb)
            {
                slower += 1;
            }
            perMethod.add(String.format("%09d", Math.round(1e6 + lr * 1e5)) + String.format("%.3f", (double) mo / mb)
                          + " " + key + " baseline=" + mb + " opt=" + mo + " words " + baseline.words().length + "/"
                          + opt.words().length);
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
        int dispatches;
        long trace = 17;                                   // every call's name and arguments, in order
        int caught;                                        // exceptions the simulated unwinder delivered here
        long thrownExc;                                    // the exception that left the method, or 0
        final long base;
        final boolean throwing;

        final classfile.ClassFile cf;
        final classfile.ClassFile.Method method;
        final compiler.opt.OptCompiler oc;

        Callee(CompiledMethod cm, long seed, int tier, classfile.ClassFile cf, classfile.ClassFile.Method method,
               compiler.opt.OptCompiler oc, long base)
        {
            this.cm = cm;
            this.base = base;
            this.throwing = method.exceptions.length > 0;
            this.cf = cf;
            this.method = method;
            this.oc = oc;
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
            boolean dispatch = false;
            if (key == null)
            {
                key = dispatchKey(word);
                dispatch = true;
            }
            if (key == null)
            {
                error = "call at word " + word + " has no relocation and is no dispatch site";
                e.unsupported = error;
                return;
            }
            if (dispatch)
            {
                key = key + "@" + Long.toHexString(e.lastTarget);   // the target both tiers loaded
                dispatches += 1;
            }
            calls += 1;
            int raw = rawArgs(key.substring(0, key.indexOf('@') < 0 ? key.length() : key.indexOf('@')));
            if (raw >= 0)
            {
                // a Magic raw call: the callee sees x0.. and the target (in the key); Baseline maps no site here,
                // so neither tier's frame is collected across it
                long h = key.hashCode();
                for (int i = 0; i < raw; i++)
                {
                    h = h * 0x100000001B3L ^ e.x[i];
                }
                trace = trace * 31 + h;
                clobber(e);
                e.x[0] = result('J', h);
                return;
            }
            if (!collect(e, word + 1))
            {
                e.unsupported = error;
                return;
            }
            if (key.startsWith("vm/VMUnwind.unwind("))
            {
                // athrow found no local handler: the unwinder searches this frame's ranges at the pc it was
                // handed (Baseline's and the opt tier's differ, so neither the pc nor the sp is traced) ...
                long exc = e.x[0];
                trace = trace * 31 + exc;
                unwind(e, exc, (int) ((e.x[1] - base) >> 2), e.x[2]);
                return;
            }
            if (key.startsWith("vm/VM.instanceOf("))
            {
                trace = trace * 31 + e.x[0] * 7 + e.x[1];
                long exc = e.x[0];
                long ty = e.x[1];
                clobber(e);
                e.x[0] = instanceOfAnswer(exc, ty);
                return;
            }
            int paren = key.indexOf('(');
            String d = key.substring(paren, dispatch ? key.indexOf('@') : key.length());
            int n = paramCount(d) + (dispatch || !isStatic(key) ? 1 : 0);
            long h = key.hashCode();
            for (int i = 0; i < n && i < 16; i++)
            {
                h = h * 0x100000001B3L ^ e.x[i];
            }
            trace = trace * 31 + h;
            if (throwing && !HELPERS.contains(key) && ((h >>> 23) & 7) == 0)
            {
                // ... and a callee throws: the unwinder searches this frame at the call (return address - 4)
                long exc = 0x4800_0000L + 8L * ((h >>> 3) & 0xFFFFF);
                trace = trace * 31 + exc;
                unwind(e, exc, word, e.x[31]);
                return;
            }
            clobber(e);
            e.x[0] = result(d.charAt(d.indexOf(')') + 1), h);
        }

        /** The registers a Magic raw call hands its callee (x0..), or -1: not one. */
        private static int rawArgs(String key)
        {
            if (key.equals("magic/Magic.call0(J)J"))
            {
                return 0;
            }
            if (key.equals("magic/Magic.call2(JJJ)J"))
            {
                return 2;
            }
            if (key.equals("magic/Magic.call3(JJJJ)J"))
            {
                return 3;
            }
            return key.equals("magic/Magic.callN(JJ)J") ? 16 : -1;
        }

        private void clobber(A64Emu e)
        {
            for (int i = 0; i <= 18; i++)
            {
                e.x[i] = garbage.nextLong();
            }
        }

        /** VMUnwind's search of one frame: the ranges in table order, a catch-all or a matching type wins, and
         *  the handler is entered with the frame's sp and the exception in x9 -- every other register garbage. */
        private void unwind(A64Emu e, long exc, int pcWord, long sp)
        {
            for (int i = 0; i < cm.handlers().size(); i++)
            {
                var hr = cm.handlers().get(i);
                if (pcWord < hr.startWord() || pcWord >= hr.endWord())
                {
                    continue;
                }
                if (hr.catchClass() == null || instanceOfAnswer(exc, fakeAddress("type:" + hr.catchClass())) != 0)
                {
                    clobber(e);
                    e.x[9] = exc;
                    e.x[31] = sp;
                    e.redirect = base + 4L * hr.handlerWord();
                    caught += 1;
                    return;
                }
            }
            thrownExc = exc;
            e.redirect = A64Emu.THROWN;
            // ... and VMUnwind pops this frame: x19..x28 become what it saved of them (the map's mask, the frame's
            // top words) or what they hold now -- which must be the caller's, as on entry (O2-3)
            int[] w = cm.words();
            int m = cm.gcMapWord();
            int mask = m == 0 ? 0 : (w[m + 9] >>> 16) & 0x3FF;
            if (mask != 0 && !savedAt(w, m, pcWord))
            {
                mask = 0;                                    // outside the saved region: as VMUnwind.savedAt
            }
            int n = Integer.bitCount(mask);
            int k = 0;
            for (int r = 0; r < 10; r++)
            {
                long v = e.x[19 + r];
                if ((mask & (1 << r)) != 0)
                {
                    v = e.load(sp + cm.frameSize() - 8L * n + 8L * k, 8);
                    k += 1;
                }
                if (v != entryRegs[r] && calleeError == null)
                {
                    calleeError = "x" + (19 + r) + " after the unwind pops the frame is 0x" + Long.toHexString(v);
                }
            }
        }

        /** VMUnwind.savedAt over the words: the saved bit of the first site whose return word is past pcWord. */
        private boolean savedAt(int[] w, int m, int pcWord)
        {
            int nSites = w[m + 1];
            int bitWords = w[m + 6];
            int nUnique = w[m + 7];
            for (int i = 0; i < nSites; i++)
            {
                if (w[m + 10 + 2 * i] > pcWord)
                {
                    int bits = w[m + 10 + 2 * nSites + nUnique * bitWords + (i >> 5)];
                    return ((bits >>> (i & 31)) & 1) != 0;
                }
            }
            return false;
        }

        /** x19..x28 on entry (O2-3: the callee-saved contract), and the first breach seen. */
        long[] entryRegs = new long[10];
        String calleeError;

        /**
         * The Methodref a dispatch (BLR) at {@code word} calls: the opt tier records its sites; for Baseline, the
         * bytecode whose machine range contains the word (bcToWord is complete there), which must be an invoke.
         */
        private String dispatchKey(int word)
        {
            int cp;
            if (oc != null)
            {
                cp = oc.dispatchCpAt(word);
            }
            else
            {
                int[] b2w = cm.bcToWord();
                int bci = -1;
                for (int i = 0; i < b2w.length; i++)
                {
                    if (b2w[i] >= 0 && b2w[i] <= word)
                    {
                        bci = i;
                    }
                }
                int op = bci < 0 ? -1 : method.code[bci] & 0xFF;
                if (op != 0xB6 && op != 0xB9 && op != 0xB8)
                {
                    return null;
                }
                cp = ((method.code[bci + 1] & 0xFF) << 8) | (method.code[bci + 2] & 0xFF);
                if (op == 0xB8 && rawArgs(memberKey(cp)) < 0)
                {
                    return null;                             // an invokestatic BLRs only as a Magic raw call
                }
            }
            if (cp < 0)
            {
                return null;
            }
            return memberKey(cp);
        }

        private String memberKey(int cp)
        {
            var r = cf.memberRef(cp);
            return r.owner() + "." + r.name() + r.descriptor();
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
                if (oc != null && s < oc.homeCount() && !e.stored().containsKey(a))
                {
                    // the real collector would follow it: a home the map types as a reference that this run never
                    // wrote holds whatever the stack held before (what it holds once written may be anything a
                    // simulated field load answered, so that is not checked)
                    error = "home " + s + " is mapped as a reference but was never written: 0x" + Long.toHexString(v)
                            + " (return word " + retWord + ")";
                    return false;
                }
                if (v != 0)
                {
                    e.store(a, 8, v + MOVE);
                    moved += 1;
                }
            }
            return true;
        }
    }

    /** The simulated VM.instanceOf: a function of the object and the Type, so the in-method catch tests and the
     *  unwinder's agree; about half answer no, so both the matched and the unmatched path run. */
    private static long instanceOfAnswer(long obj, long type)
    {
        long h = (obj * 0x9E3779B97F4A7C15L) ^ (type * 0xC2B2AE3D27D4EB4FL);
        return (h >>> 41) & 1;
    }

    /** The VM helpers' callee keys (they never throw here). */
    private static final java.util.Set<String> HELPERS = helperKeys();

    private static java.util.Set<String> helperKeys()
    {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        try
        {
            java.lang.reflect.Field fl = Class.forName("compiler.WriterSymbols").getDeclaredField("HELPER_KEY");
            fl.setAccessible(true);
            for (String k : (String[]) fl.get(null))
            {
                if (k != null)
                {
                    out.add(k);
                }
            }
        }
        catch (ReflectiveOperationException ex)
        {
            throw new IllegalStateException(ex);
        }
        return out;
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

    /**
     * The code with every relocated address load (a TIB, a Type, a static's cell, a string) patched to a fake
     * address derived from its KEY, so a tier that names the wrong class or field computes a different value and the
     * runs diverge. Interface Types stay 0: the itable walk compares against them and must still end at a zero
     * entry; {@link #sameRelocations} checks their keys instead.
     */
    private static int[] patched(CompiledMethod cm)
    {
        int[] w = cm.words().clone();
        var rl = cm.relocs();
        for (var t : list(rl.tibRefs()))
        {
            patch(w, t.wordIndex(), t.reg(), "tib:" + t.className());
        }
        for (var t : list(rl.typeRefs()))
        {
            patch(w, t.wordIndex(), t.reg(), "type:" + t.className());
        }
        for (var t : list(rl.staticRefs()))
        {
            patch(w, t.wordIndex(), t.reg(), "static:" + t.fieldKey());
        }
        for (var t : list(rl.strRefs()))
        {
            patch(w, t.wordIndex(), t.reg(), "str:" + new String(t.text(), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        for (var t : list(rl.stringObjs()))
        {
            patch(w, t.wordIndex(), t.reg(), "strobj:" + new String(t.text(), java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        return w;
    }

    /** As CodeBuffer.patchAddr: movz rd, lo16 ; movk rd, hi16, lsl 16 -- an 8-aligned fake address below 4 GiB. */
    private static long fakeAddress(String key)
    {
        return 0x1000_0000L + ((key.hashCode() & 0x0FFF_FFFFL) << 3) % 0x2000_0000L;
    }

    private static void patch(int[] w, int at, int rd, String key)
    {
        long addr = fakeAddress(key);
        w[at] = asm.A64Enc.movz(rd, (int) (addr & 0xFFFF), 0);
        w[at + 1] = asm.A64Enc.movk(rd, (int) ((addr >>> 16) & 0xFFFF), 1);
    }

    private static <T> java.util.List<T> list(util.Vec<T> v)
    {
        java.util.ArrayList<T> out = new java.util.ArrayList<>();
        for (int i = 0; i < v.size(); i++)
        {
            out.add(v.get(i));
        }
        return out;
    }

    /** Both tiers must name the same symbols: every relocation kind's keys, as multisets. */
    private static String sameRelocations(CompiledMethod a, CompiledMethod b)
    {
        String[] kinds = { "calls", "tibs", "types", "interfaces", "statics", "strings", "stringObjs" };
        for (String k : kinds)
        {
            java.util.List<String> x = keys(a.relocs(), k);
            java.util.List<String> y = keys(b.relocs(), k);
            if (!x.equals(y))
            {
                return k + " baseline " + x + " opt " + y;
            }
        }
        return null;
    }

    private static java.util.List<String> keys(compiler.BaselineCompiler.Relocations rl, String kind)
    {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (kind.equals("calls"))
        {
            for (var c : list(rl.callSites()))
            {
                out.add(c.calleeKey());
            }
        }
        else if (kind.equals("tibs"))
        {
            for (var c : list(rl.tibRefs()))
            {
                out.add(c.className());
            }
        }
        else if (kind.equals("types"))
        {
            for (var c : list(rl.typeRefs()))
            {
                out.add(c.className());
            }
        }
        else if (kind.equals("interfaces"))
        {
            for (var c : list(rl.interfaceRefs()))
            {
                out.add(c.className());
            }
        }
        else if (kind.equals("statics"))
        {
            for (var c : list(rl.staticRefs()))
            {
                out.add(c.fieldKey());
            }
        }
        else
        {
            for (var c : list(kind.equals("strings") ? rl.strRefs() : rl.stringObjs()))
            {
                out.add(new String(c.text(), java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** The two runs' stores outside the stack must agree word for word (the frames differ by design). */
    private static String memory(A64Emu eb, A64Emu eo)
    {
        java.util.TreeMap<Long, Long> b = heapStores(eb);
        java.util.TreeMap<Long, Long> o = heapStores(eo);
        if (b.equals(o))
        {
            return null;
        }
        for (Long a : b.keySet())
        {
            if (!b.get(a).equals(o.get(a)))
            {
                return "0x" + Long.toHexString(a) + " baseline 0x" + Long.toHexString(b.get(a)) + " opt "
                       + (o.get(a) == null ? "unwritten" : "0x" + Long.toHexString(o.get(a)));
            }
        }
        for (Long a : o.keySet())
        {
            if (!b.containsKey(a))
            {
                return "0x" + Long.toHexString(a) + " written by opt only";
            }
        }
        return "differs";
    }

    private static java.util.TreeMap<Long, Long> heapStores(A64Emu e)
    {
        java.util.TreeMap<Long, Long> r = new java.util.TreeMap<>();
        for (var en : e.stored().entrySet())
        {
            long a = en.getKey();
            if (a < STACK_LOW || a >= STACK_TOP)
            {
                r.put(a, en.getValue());
            }
        }
        return r;
    }

    private static final long STACK_TOP = 0x7000_0000L;
    private static final long STACK_LOW = STACK_TOP - (1L << 20);

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
            int i = r.nextInt(3) == 0 ? edge[r.nextInt(edge.length)] : r.nextInt();
            if (r.nextInt(8) == 0)
            {
                // junk above bit 31, as an int can arrive (a W-form load, a zero-extended field): both tiers must
                // answer from the low 32 bits alone -- what lets the opt tier skip an extension it can PROVE (O2-1)
                return (i & 0xFFFF_FFFFL) | ((long) r.nextInt() << 32);
            }
            return i;
        }
        if (c == 'J')
        {
            long[] edge = { 0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 63, 64, 0xFFFF_FFFFL, 1L << 32 };
            return r.nextInt(3) == 0 ? edge[r.nextInt(edge.length)] : r.nextLong();
        }
        if (type.equals("Lmagic/ObjectReference;"))
        {
            return ref(r);                                   // a traced word: a reference
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
