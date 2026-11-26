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

import classfile.ClassFile;
import classfile.ClassReader;
import compiler.Baseline;
import compiler.BaselineCompiler;
import compiler.BaselineCompiler.CompiledMethod;
import compiler.opt.A64Select;
import compiler.opt.OptCompiler;
import compiler.opt.SsaBuilder;
import util.Vec;

import java.util.HashSet;
import java.util.Set;

/**
 * The writer's side of the optimizing tier (PLAN.md "The optimizing compiler"): which methods it is asked to
 * compile, and the counts the build prints. A method is selected by {@code --opt all}, by a {@code --opt} list
 * entry (an exact key {@code owner.name+desc}, {@code owner.*} for a class's methods, {@code pkg/**} for a package
 * tree), or by carrying {@code @magic.Optimize}. Each selected method's IR is built and verified; from O1-2b the
 * AArch64 back end generates code for the ones it covers, which is installed in place of Baseline's.
 */
final class OptTier
{
    /** The {@code --opt} spec, or null when the flag was not given. */
    static String spec;

    /** A differential check, installed by a test (writer.OptDiff): both tiers' code for each installed method. */
    interface Diff
    {
        void compare(String key, ClassFile cf, ClassFile.Method m, CompiledMethod baseline, CompiledMethod opt,
                     OptCompiler oc, long base);
    }

    static Diff diff;

    private static final Set<String> seen = new HashSet<>();
    private static int accepted;
    private static int rejected;
    private static int malformed;
    private static final int[] byReason = new int[SsaBuilder.REASONS];
    private static final int REPORT_LIMIT = 20;
    private static int installed;
    private static int backMalformed;
    private static int symbolFailures;
    private static final int[] byBack = new int[A64Select.REASONS];
    /** JOENG_OPT_NOEMIT: build and verify only (O1-1's behaviour), install nothing -- a bisection switch. */
    private static final boolean NO_EMIT = System.getenv("JOENG_OPT_NOEMIT") != null;
    /** JOENG_OPT_LIST: name every method whose opt code is installed. */
    private static final boolean LIST = System.getenv("JOENG_OPT_LIST") != null;

    private OptTier()
    {
    }

    static boolean selected(String key, ClassFile.Method m)
    {
        if (m.optimize)
        {
            return true;
        }
        if (spec == null)
        {
            return false;
        }
        for (String part : spec.split(","))
        {
            if (matches(part.trim(), key))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pat, String key)
    {
        if (pat.equals("all"))
        {
            return true;
        }
        int dot = key.indexOf('.');
        String owner = dot < 0 ? key : key.substring(0, dot);
        if (pat.endsWith("/**"))
        {
            return owner.startsWith(pat.substring(0, pat.length() - 2));
        }
        if (pat.endsWith(".*"))
        {
            return owner.equals(pat.substring(0, pat.length() - 2));
        }
        return key.equals(pat);
    }

    /**
     * Opt-compile {@code m} at {@code base}: build and verify the IR, then (O1-2b) generate AArch64 code. Answers
     * the code to install, or null to fall back to Baseline. Counted once per key -- the writer compiles a method
     * more than once (a sizing pass, then at its address) and the code is position-independent, so every call
     * gives the same answer.
     */
    static CompiledMethod compile(String key, ClassFile cf, ClassFile.Method m, long base, boolean isEntry,
                                  BaselineCompiler.ClassResolver resolver)
    {
        if (m.code == null)
        {
            return null;
        }
        boolean first = seen.add(key);
        ClassFile.ExceptionEntry[] ex = m.exceptions;
        int n = ex.length;
        int[] es = new int[n];
        int[] ee = new int[n];
        int[] eh = new int[n];
        int[] ec = new int[n];
        for (int i = 0; i < n; i++)
        {
            es[i] = ex[i].startPc();
            ee[i] = ex[i].endPc();
            eh[i] = ex[i].handlerPc();
            ec[i] = ex[i].catchType();
        }
        OptCompiler oc = new OptCompiler();
        int r;
        try
        {
            r = oc.compile(cf.bytes(), cf.cpOff(), cf.cpTag(), m.code, m.descOff, m.isStatic, m.isSynchronized,
                           m.maxLocals, es, ee, eh, ec, n);
            if (first)
            {
                countFront(key, oc, r);
            }
            if (r == OptCompiler.ACCEPTED && !isEntry && !NO_EMIT)
            {
                compiler.Symbols syms = BaselineCompiler.writerSymbols(cf, resolver);
                int gen;
                try
                {
                    gen = oc.generateA64(syms, uninterruptible(cf), base, m.code.length);
                }
                catch (RuntimeException e)
                {
                    // A writer symbol query threw (a class the writer cannot resolve): Baseline's compile of this
                    // method throws the same way, and compileOrStub then bakes a resolve stub. Decline, and let it.
                    if (first)
                    {
                        symbolFailures += 1;
                        if (System.getenv("JOENG_OPT_REJECTS") != null)
                        {
                            System.out.println("  opt: declined " + key + ": " + e.getMessage());
                        }
                    }
                    return null;
                }
                if (first)
                {
                    countBack(key, oc, gen);
                    if (gen == OptCompiler.REJECTED && oc.backReason() == A64Select.R_INTRINSIC
                        && System.getenv("JOENG_OPT_BLOCKERS") != null)
                    {
                        noteIntrinsics(oc, syms);
                    }
                }
                if (gen == OptCompiler.ACCEPTED && first && diff != null)
                {
                    CompiledMethod bl = new BaselineCompiler(cf, resolver).yieldPolls(true)
                            .compileMethod(m, base, false);
                    diff.compare(key, cf, m, bl, new CompiledMethod(oc.words(), BaselineCompiler.relocationsOf(syms),
                                 oc.frameSize(), oc.gcMapWord(), new Vec<>(), oc.bcToWord()), oc, base);
                }
                if (gen == OptCompiler.ACCEPTED)
                {
                    if (first)
                    {
                        installed += 1;
                        if (LIST)
                        {
                            System.out.println("  opt: installed " + key + " (" + oc.words().length + " words)");
                        }
                    }
                    return new CompiledMethod(oc.words(), BaselineCompiler.relocationsOf(syms), oc.frameSize(),
                                              oc.gcMapWord(), new Vec<>(), oc.bcToWord());
                }
            }
        }
        catch (RuntimeException e)
        {
            if (first)
            {
                malformed += 1;
                if (malformed <= REPORT_LIMIT)
                {
                    System.out.println("  opt: CRASH " + key + ": " + e);
                    e.printStackTrace(System.out);
                }
            }
            return null;
        }
        return null;
    }

    private static void countFront(String key, OptCompiler oc, int r)
    {
        if (r == OptCompiler.ACCEPTED)
        {
            accepted += 1;
        }
        else if (r == OptCompiler.REJECTED)
        {
            rejected += 1;
            byReason[oc.reason()] += 1;
            if (System.getenv("JOENG_OPT_REJECTS") != null)
            {
                System.out.println("  opt: rejected " + key + " @" + oc.reasonBci() + ": "
                                   + SsaBuilder.reasonName(oc.reason()));
            }
        }
        else
        {
            malformed += 1;
            if (malformed <= REPORT_LIMIT)
            {
                System.out.println("  opt: MALFORMED " + key + ": " + oc.error());
                if (System.getenv("JOENG_OPT_DUMP") != null)
                {
                    System.out.print(oc.graph().dump());
                }
            }
        }
    }

    /** JOENG_OPT_BLOCKERS: per intrinsic id, the methods declined for intrinsics that use it (-1 = a special call that
     *  is not a Magic/unboxed intrinsic: Math.multiplyHigh, getCallerClass, a MethodHandle call). */
    private static final java.util.TreeMap<Integer, Integer> intrinsicUse = new java.util.TreeMap<>();

    private static void noteIntrinsics(OptCompiler oc, compiler.Symbols syms)
    {
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        compiler.opt.Graph g = oc.graph();
        for (int b = 0; b < g.nBlocks; b++)
        {
            compiler.opt.Block blk = g.blocks[b];
            for (int i = 0; i < blk.nCode; i++)
            {
                compiler.opt.Instr x = blk.code[i];
                if (x.op == compiler.opt.Op.INVOKE && x.aux2 != 0xBA)
                {
                    int cp = (int) x.aux;
                    if (syms.isIntrinsicCall(cp))
                    {
                        ids.add(syms.intrinsicId(cp));
                    }
                    else if (syms.mathIntrinsicId(cp) >= 0 || syms.isGetCallerClass(cp))
                    {
                        ids.add(-1);
                    }
                }
            }
        }
        for (int id : ids)
        {
            intrinsicUse.merge(id, 1, Integer::sum);
        }
    }

    private static void countBack(String key, OptCompiler oc, int gen)
    {
        if (gen == OptCompiler.REJECTED)
        {
            byBack[oc.backReason()] += 1;
        }
        else if (gen == OptCompiler.MALFORMED)
        {
            backMalformed += 1;
            if (backMalformed <= REPORT_LIMIT)
            {
                System.out.println("  opt: BAD ALLOCATION " + key + ": " + oc.error());
            }
        }
    }

    /** As Baseline decides it: the class implements {@code magic.Uninterruptible} directly (no yieldpoints). */
    private static boolean uninterruptible(ClassFile cf)
    {
        int afterCp = ClassReader.afterCp(cf.bytes(), cf.cpOff(), cf.cpTag());
        return ClassReader.implementsDirectly(cf.bytes(), cf.cpOff(), afterCp, Baseline.UNINTERRUPTIBLE_HASH);
    }

    /** The build's summary line, when the tier was asked for anything. */
    static void report()
    {
        if (seen.isEmpty())
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("opt: built=").append(accepted + malformed).append(" verified=").append(accepted)
          .append(" malformed=").append(malformed).append(" rejected=").append(rejected)
          .append(" installed=").append(installed).append(" badAlloc=").append(backMalformed)
          .append(" symbolQueryFailed=").append(symbolFailures);
        for (int i = 1; i < byReason.length; i++)
        {
            if (byReason[i] > 0)
            {
                sb.append(" [").append(SsaBuilder.reasonName(i)).append('=').append(byReason[i]).append(']');
            }
        }
        sb.append(" | back end declined:");
        for (int i = 1; i < byBack.length; i++)
        {
            if (byBack[i] > 0)
            {
                sb.append(" [").append(A64Select.reasonName(i)).append('=').append(byBack[i]).append(']');
            }
        }
        System.out.println(sb);
        if (System.getenv("JOENG_OPT_BLOCKERS") != null)
        {
            StringBuilder bl = new StringBuilder("opt: first blocking op:");
            for (int i = 0; i < A64Select.BLOCKED_BY.length; i++)
            {
                if (A64Select.BLOCKED_BY[i] > 0)
                {
                    bl.append(' ').append(compiler.opt.Op.name(i)).append('=').append(A64Select.BLOCKED_BY[i]);
                }
            }
            System.out.println(bl);
            StringBuilder iu = new StringBuilder("opt: intrinsic ids in methods declined for intrinsics (id=methods):");
            for (var e : intrinsicUse.entrySet())
            {
                iu.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            System.out.println(iu);
        }
    }
}
