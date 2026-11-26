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

import asm.A64Enc;
import asm.CodeBuffer;

/**
 * AArch64 emission (O1-2b): allocated machine IR into instruction words through {@code asm.A64Enc}, blocks in
 * layout order, a jump to the next block dropped, branches patched once every block's first word is known.
 *
 * <p>The frame, when the allocator spilled anything: {@code sub sp, sp, #frameSize} on entry, spill slot k at
 * {@code [sp + 8k]}, {@code add sp} before {@code ret}. A leaf saves no link register (it makes no call), so the
 * frame is the slots and nothing else; with no spill there is no frame at all.
 */
public final class A64Emit
{
    private static final int SCRATCH = 16;            // IP0: never allocated

    private final MFunc f;
    private CodeBuffer cb;
    private int[] blockWord;
    private int[] fixWord;
    private int[] fixBlock;
    private int nFix;
    public int frameSize;
    public int[] bcToWord;
    /** Set when a branch did not reach (the method falls back to Baseline). */
    public boolean failed;

    public A64Emit(MFunc f)
    {
        this.f = f;
    }

    public int[] emit(long base, int codeLength)
    {
        cb = new CodeBuffer(base);
        bcToWord = new int[codeLength];
        for (int i = 0; i < codeLength; i++)
        {
            bcToWord[i] = -1;
        }
        frameSize = f.nSlots == 0 ? 0 : A64Enc.align16(f.nSlots * 8);
        if (frameSize >= 4096)
        {
            failed = true;
            return null;
        }
        int maxId = 0;
        for (int b = 0; b < f.nBlocks; b++)
        {
            if (f.blocks[b].id > maxId)
            {
                maxId = f.blocks[b].id;
            }
        }
        blockWord = new int[maxId + 1];
        fixWord = new int[16];
        fixBlock = new int[16];
        if (frameSize > 0)
        {
            cb.emit(A64Enc.subImm(31, 31, frameSize));
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            MBlock next = b + 1 < f.nBlocks ? f.blocks[b + 1] : null;
            blockWord[blk.id] = cb.wordCount();
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                if (x.bci >= 0 && x.bci < codeLength && bcToWord[x.bci] < 0)
                {
                    bcToWord[x.bci] = cb.wordCount();
                }
                one(x, next);
            }
        }
        patch();
        monotone();
        return failed ? null : cb.toWords();
    }

    private int r(int v)
    {
        return f.loc[v];
    }

    private void one(MInstr x, MBlock next)
    {
        int op = x.op;
        int d = x.defs.length > 0 ? r(x.defs[0]) : -1;
        int a = x.uses.length > 0 ? r(x.uses[0]) : -1;
        int b = x.uses.length > 1 ? r(x.uses[1]) : -1;
        if (op == MInstr.MOVE)
        {
            if (d != a)
            {
                cb.emit(A64Enc.movReg(d, a));
            }
        }
        else if (op == MInstr.SPILL)
        {
            cb.emit(A64Enc.strx(a, 31, x.aux * 8));
        }
        else if (op == MInstr.RELOAD)
        {
            cb.emit(A64Enc.ldrx(d, 31, x.aux * 8));
        }
        else if (op == MInstr.JUMP)
        {
            jumpTo(x.targets[0], next);
        }
        else if (op == A64Select.PARAMS)
        {
            return;                                          // the arguments are already where they are pinned
        }
        else if (op == A64Select.LIMM)
        {
            cb.emitAll(A64Enc.loadImm64(d, x.imm));
        }
        else if (op <= A64Select.ASRV && op >= A64Select.ADD)
        {
            cb.emit(binary(op, d, a, b));
        }
        else if (op == A64Select.MSUB)
        {
            cb.emit(A64Enc.msub(d, a, b, r(x.uses[2])));
        }
        else if (op == A64Select.NEG)
        {
            cb.emit(A64Enc.subReg(d, A64Enc.XZR, a));
        }
        else if (op >= A64Select.SXTW && op <= A64Select.UXTW)
        {
            cb.emit(op == A64Select.SXTW ? A64Enc.sxtw(d, a) : (op == A64Select.SXTB ? A64Enc.sxtb(d, a)
                    : (op == A64Select.SXTH ? A64Enc.sxth(d, a) : (op == A64Select.UXTH ? A64Enc.uxth(d, a)
                    : A64Enc.uxtw(d, a)))));
        }
        else if (op == A64Select.ANDLOW)
        {
            cb.emit(A64Enc.andLowBits(d, a, (int) x.imm));
        }
        else if (op == A64Select.LCMP)
        {
            cb.emit(A64Enc.cmpReg(a, b));
            cb.emit(A64Enc.cset(d, A64Enc.GT));             // 1 if a > b, else 0
            cb.emit(A64Enc.csinv(d, d, A64Enc.XZR, A64Enc.GE)); // ... and -1 if a < b
        }
        else if (op == A64Select.ADDI)
        {
            cb.emit(A64Enc.addImm(d, a, (int) x.imm));
        }
        else if (op == A64Select.ADDLSL)
        {
            cb.emit(A64Enc.addRegLsl(d, a, b, (int) x.imm));
        }
        else if (op == A64Select.LDRX)
        {
            cb.emit(A64Enc.ldrx(d, a, (int) x.imm));
            if (x.aux == 1)
            {
                cb.emit(A64Enc.dmbIsh());                    // a volatile load (JSR-133 on ARMv8, as Baseline)
            }
        }
        else if (op >= A64Select.LDRSB && op <= A64Select.LDRSW)
        {
            cb.emit(op == A64Select.LDRSB ? A64Enc.ldrsb(d, a, 0) : (op == A64Select.LDRH ? A64Enc.ldrh(d, a, 0)
                    : (op == A64Select.LDRSH ? A64Enc.ldrsh(d, a, 0) : A64Enc.ldrsw(d, a, 0))));
        }
        else if (op == A64Select.BCMP)
        {
            cb.emit(x.uses.length == 2 ? A64Enc.cmpReg(a, b) : A64Enc.cmpImm(a, 0));
            fixup(A64Enc.bcond(x.aux, 0), x.targets[0]);
            jumpTo(x.targets[1], next);
        }
        else if (op == A64Select.CBZ || op == A64Select.CBNZ)
        {
            fixup(op == A64Select.CBZ ? A64Enc.cbz(a, 0) : A64Enc.cbnz(a, 0), x.targets[0]);
            jumpTo(x.targets[1], next);
        }
        else if (op == A64Select.SWITCH)
        {
            for (int k = 0; k < x.keys.length; k++)
            {
                int key = x.keys[k];
                if (key >= 0 && key < 4096)
                {
                    cb.emit(A64Enc.cmpImm(a, key));
                }
                else
                {
                    cb.emitAll(A64Enc.loadImm64(SCRATCH, key));
                    cb.emit(A64Enc.cmpReg(a, SCRATCH));
                }
                fixup(A64Enc.bcond(A64Enc.EQ, 0), x.targets[k + 1]);
            }
            jumpTo(x.targets[0], next);
        }
        else if (op == A64Select.RET)
        {
            if (frameSize > 0)
            {
                cb.emit(A64Enc.addImm(31, 31, frameSize));
            }
            cb.emit(A64Enc.ret());
        }
        else
        {
            failed = true;
        }
    }

    private static int binary(int op, int d, int a, int b)
    {
        if (op == A64Select.ADD)
        {
            return A64Enc.addReg(d, a, b);
        }
        if (op == A64Select.SUB)
        {
            return A64Enc.subReg(d, a, b);
        }
        if (op == A64Select.MUL)
        {
            return A64Enc.mulReg(d, a, b);
        }
        if (op == A64Select.SDIV)
        {
            return A64Enc.sdivReg(d, a, b);
        }
        if (op == A64Select.AND)
        {
            return A64Enc.andReg(d, a, b);
        }
        if (op == A64Select.ORR)
        {
            return A64Enc.orrReg(d, a, b);
        }
        if (op == A64Select.EOR)
        {
            return A64Enc.eorReg(d, a, b);
        }
        if (op == A64Select.LSLV)
        {
            return A64Enc.lslv(d, a, b);
        }
        if (op == A64Select.LSRV)
        {
            return A64Enc.lsrv(d, a, b);
        }
        return A64Enc.asrv(d, a, b);
    }

    private void jumpTo(MBlock t, MBlock next)
    {
        if (t != next)
        {
            fixup(A64Enc.b(0), t);
        }
    }

    /** Emit {@code word} (a branch with offset 0) and remember to point it at {@code t}. */
    private void fixup(int word, MBlock t)
    {
        if (nFix == fixWord.length)
        {
            int[] w = new int[nFix * 2];
            int[] bl = new int[nFix * 2];
            for (int i = 0; i < nFix; i++)
            {
                w[i] = fixWord[i];
                bl[i] = fixBlock[i];
            }
            fixWord = w;
            fixBlock = bl;
        }
        fixWord[nFix] = cb.emit(word);
        fixBlock[nFix] = t.id;
        nFix += 1;
    }

    private void patch()
    {
        for (int i = 0; i < nFix; i++)
        {
            int at = fixWord[i];
            int off = blockWord[fixBlock[i]] - at;
            int w = cb.get(at);
            if ((w & 0xFC00_0000) == 0x1400_0000)
            {
                cb.set(at, A64Enc.b(off));
            }
            else if (off < -(1 << 18) || off >= (1 << 18))
            {
                failed = true;
                return;
            }
            else if ((w & 0xFF00_0010) == 0x5400_0000)
            {
                cb.set(at, A64Enc.bcond(w & 0xF, off));
            }
            else if ((w & 0x7F00_0000) == 0x3400_0000)
            {
                cb.set(at, A64Enc.cbz(w & 0x1F, off));
            }
            else
            {
                cb.set(at, A64Enc.cbnz(w & 0x1F, off));
            }
        }
    }

    /** Keep only bci entries whose word does not go backwards: the line table reads the map in bci order. */
    private void monotone()
    {
        int last = -1;
        for (int i = 0; i < bcToWord.length; i++)
        {
            if (bcToWord[i] < 0)
            {
                continue;
            }
            if (bcToWord[i] < last)
            {
                bcToWord[i] = -1;
            }
            else
            {
                last = bcToWord[i];
            }
        }
    }
}
