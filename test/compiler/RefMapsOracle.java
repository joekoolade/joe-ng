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
package compiler;

/**
 * {@link RefMaps} as a test oracle for the optimizing tier's SSA builder (compiler.opt.OptCorpus): the types
 * entering any bci, by replaying its block from the solved head state. Test-side, so nothing of it is baked into
 * the image (the image compiles every method of the compiler's classes).
 */
public final class RefMapsOracle
{
    private final RefMaps rm;

    public RefMapsOracle(byte[] cls, int[] cpOff, int[] cpTag, byte[] code, int nLoc, int nStk, int[] exStart,
                         int[] exEnd, int[] exHandler, int exCount)
    {
        rm = new RefMaps(cls, cpOff, cpTag, code, nLoc, nStk, exStart, exEnd, exHandler, exCount);
    }

    public boolean solve(int descOff, boolean isStatic)
    {
        return rm.solve(descOff, isStatic);
    }

    /** The locals into {@code outLoc} and the stack into {@code outStk} entering {@code pc}; the depth, or -1 when
     *  {@code pc} is never reached. A long or double is ONE stack entry here. */
    public int stateAt(int pc, byte[] outLoc, byte[] outStk)
    {
        int h = pc;
        while (h > 0 && rm.headOf[h] < 0)
        {
            h -= 1;
        }
        int head = rm.headOf[h];
        if (head < 0 || rm.headDepth[head] < 0)
        {
            return -1;
        }
        rm.load(head);
        int at = rm.headPc[head];
        while (at < pc && !rm.failed)
        {
            int op = rm.code[at] & 0xFF;
            rm.exec(at, op);
            at += Baseline.opLen(op, rm.code, at);
        }
        if (at != pc || rm.failed)
        {
            return -1;
        }
        for (int i = 0; i < rm.nLoc; i++)
        {
            outLoc[i] = rm.loc[i];
        }
        for (int i = 0; i < rm.dep; i++)
        {
            outStk[i] = rm.stk[i];
        }
        return rm.dep;
    }
}
