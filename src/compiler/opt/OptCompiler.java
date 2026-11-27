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
package compiler.opt;

import compiler.Symbols;

/**
 * The optimizing tier's front door (PLAN.md "The optimizing compiler"). O1-1: build a method's SSA graph and
 * verify it; nothing is emitted yet. The result says which of three things happened -- built and verified, not
 * accepted (a shape the tier does not cover: {@link #reason}), or built but malformed ({@link #error}: a builder
 * bug).
 */
public final class OptCompiler
{
    public static final int ACCEPTED = 0;
    public static final int REJECTED = 1;
    public static final int MALFORMED = 2;

    private Graph graph;
    private int reason;
    private int backReason;
    private int[] words;
    private int frameSize;
    private int[] bcToWord;
    private int gcMapWord;
    private int[] dynWord;
    private int[] dynCp;
    private int nDyn;
    private int nHandlers;
    private int nHomes;
    private int[] hStart;
    private int[] hEnd;
    private int[] hHandler;
    private int[] hEntry;
    private int reasonBci;
    private String error;

    public int compile(byte[] cls, int[] cpOff, int[] cpTag, byte[] code, int descOff, boolean isStatic,
                       boolean isSynchronized, int maxLocals, int[] exStart, int[] exEnd, int[] exHandler,
                       int[] exCatch, int exCount)
    {
        SsaBuilder b = new SsaBuilder(cls, cpOff, cpTag, code, descOff, isStatic, isSynchronized, maxLocals,
                                      exStart, exEnd, exHandler, exCatch, exCount);
        graph = b.build();
        if (graph == null)
        {
            reason = b.reason();
            reasonBci = b.reasonBci();
            return REJECTED;
        }
        error = Verifier.verify(graph);
        if (error != null)
        {
            return MALFORMED;
        }
        return ACCEPTED;
    }

    /**
     * O1-2b: after {@link #compile} accepted the method, generate AArch64 code for it at {@code base}. ACCEPTED with
     * {@link #words}, {@link #frameSize} and {@link #bcToWord}; REJECTED when the back end does not cover it yet
     * ({@link #backReason}, an {@link A64Select} reason); MALFORMED when the allocation fails its check (a back-end
     * bug -- the method falls back to Baseline either way).
     */
    public int generateA64(Symbols syms, boolean uninterruptible, long base, int codeLength)
    {
        A64Select sel = new A64Select(graph, syms, uninterruptible);
        MFunc f = sel.select();
        if (f == null)
        {
            backReason = sel.reason;
            return REJECTED;
        }
        PhiElim.run(f);
        Constraints.run(f);
        A64Target t = new A64Target();
        LinearScan.run(f, t.regs());
        error = AllocCheck.check(f, t.regs());
        if (error != null)
        {
            return MALFORMED;
        }
        A64Emit e = new A64Emit(f, syms, sel.nonLeaf);
        words = e.emit(base, codeLength);
        if (words == null)
        {
            backReason = A64Select.R_OP;
            return REJECTED;
        }
        frameSize = e.frameSize;
        bcToWord = e.bcToWord;
        gcMapWord = e.gcMapWord;
        dynWord = e.dynWord;
        dynCp = e.dynCp;
        nDyn = e.nDyn;
        nHandlers = e.nHandlers;
        nHomes = e.nHomes;
        hStart = e.hStart;
        hEnd = e.hEnd;
        hHandler = e.hHandler;
        hEntry = e.hEntry;
        return ACCEPTED;
    }

    /** The machine-PC handler ranges, in the order the unwinder must try them: word range [start, end), the
     *  handler's word, and the exception-table entry it stands for (whose catch type it carries). */
    /** The home slots: GMAP "locals" [0, homeCount()). */
    public int homeCount()
    {
        return nHomes;
    }

    public int handlerCount()
    {
        return nHandlers;
    }

    public int handlerStartWord(int i)
    {
        return hStart[i];
    }

    public int handlerEndWord(int i)
    {
        return hEnd[i];
    }

    public int handlerWord(int i)
    {
        return hHandler[i];
    }

    public int handlerEntry(int i)
    {
        return hEntry[i];
    }

    public int backReason()
    {
        return backReason;
    }

    public int[] words()
    {
        return words;
    }

    public int frameSize()
    {
        return frameSize;
    }

    public int[] bcToWord()
    {
        return bcToWord;
    }

    /** The cp index of the dispatch (BLR) at word {@code w}, or -1 -- for writer.OptDiff. */
    public int dispatchCpAt(int w)
    {
        for (int i = 0; i < nDyn; i++)
        {
            if (dynWord[i] == w)
            {
                return dynCp[i];
            }
        }
        return -1;
    }

    /** Word offset of the GC map after the code (0 = none: a leaf). */
    public int gcMapWord()
    {
        return gcMapWord;
    }

    public Graph graph()
    {
        return graph;
    }

    public int reason()
    {
        return reason;
    }

    public int reasonBci()
    {
        return reasonBci;
    }

    public String error()
    {
        return error;
    }
}
