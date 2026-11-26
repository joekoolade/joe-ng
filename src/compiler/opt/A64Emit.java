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
import compiler.Symbols;
import objectmodel.ObjectModel;

/**
 * AArch64 emission (O1-2b): allocated machine IR into instruction words through {@code asm.A64Enc}, blocks in
 * layout order, a jump to the next block dropped, branches patched once every block's first word is known.
 *
 * <p>The frame. A method that calls or polls (O1-3) saves the link register at {@code [sp]} -- where every stack
 * walk reads it -- with spill slot k at {@code [sp + 8 + 8k]}; a leaf saves none, slot k at {@code [sp + 8k]}, and
 * with no spill has no frame at all. {@code sub sp} on entry, {@code add sp} before {@code ret}.
 *
 * <p>The GC map (a {@code compiler.RefMaps} GMAP, after the last instruction): a site at every {@code BL}, keyed by
 * its return word, whose "locals" are the spill slots -- each typed by the spilled value's kind where it is live
 * across that call, dead elsewhere. Nothing else can hold a reference at a call: every allocatable register is
 * caller-saved, so the allocator spills whatever is live across one (PLAN.md: spill across calls).
 */
public final class A64Emit
{
    private static final int SCRATCH = 16;            // IP0: never allocated

    private final MFunc f;
    private final Symbols syms;
    private final boolean saveLR;
    private int slotBase;
    private CodeBuffer cb;
    // call sites: the BL's word, and the live spill slots after it (one word of bits per 32 slots)
    private int[] siteWord = new int[8];
    private int[][] siteLive = new int[8][];
    private int nSites;
    private long[] liveOutSlots;                       // scratch: slots live after the instruction being emitted
    public int gcMapWord;
    /** Dispatch sites (a BLR): its word and the Methodref / InterfaceMethodref it calls -- for writer.OptDiff. */
    public int[] dynWord = new int[8];
    public int[] dynCp = new int[8];
    public int nDyn;
    private int[] blockWord;
    private int[] fixWord;
    private int[] fixBlock;
    private int nFix;
    public int frameSize;
    public int[] bcToWord;
    /** Set when a branch did not reach (the method falls back to Baseline). */
    public boolean failed;

    public A64Emit(MFunc f, Symbols syms, boolean saveLR)
    {
        this.f = f;
        this.syms = syms;
        this.saveLR = saveLR;
    }

    public int[] emit(long base, int codeLength)
    {
        cb = new CodeBuffer(base);
        bcToWord = new int[codeLength];
        for (int i = 0; i < codeLength; i++)
        {
            bcToWord[i] = -1;
        }
        slotBase = saveLR ? 8 : 0;
        int words = f.nSlots + (saveLR ? 1 : 0);
        frameSize = words == 0 ? 0 : A64Enc.align16(words * 8);
        SlotLiveness sl = new SlotLiveness(f);
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
        if (saveLR)
        {
            cb.emit(A64Enc.strx(30, 31, 0));
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            MBlock next = b + 1 < f.nBlocks ? f.blocks[b + 1] : null;
            blockWord[blk.id] = cb.wordCount();
            long[][] after = sl.afterEach(blk);
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                if (x.bci >= 0 && x.bci < codeLength && bcToWord[x.bci] < 0)
                {
                    bcToWord[x.bci] = cb.wordCount();
                }
                liveOutSlots = after[i];
                one(x, next);
            }
        }
        patch();
        monotone();
        if (failed)
        {
            return null;
        }
        gcMapWord = 0;
        if (nSites > 0)
        {
            emitMap();
        }
        return cb.toWords();
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
            cb.emit(A64Enc.strx(a, 31, slotBase + x.aux * 8));
        }
        else if (op == MInstr.RELOAD)
        {
            cb.emit(A64Enc.ldrx(d, 31, slotBase + x.aux * 8));
        }
        else if (op == A64Select.CALL)
        {
            syms.call(cb, x.aux, x.imm != 0);              // BL + its relocation, through the shared seam
            site(cb.wordCount() - 1);
        }
        else if (op == A64Select.HCALL)
        {
            syms.callHelper(cb, x.aux);
            site(cb.wordCount() - 1);
        }
        else if (op == A64Select.STATICADDR)
        {
            syms.staticField(cb, d, x.aux);                // a relocated address load, as Baseline's getstatic
        }
        else if (op == A64Select.LDCSTR)
        {
            syms.string(cb, d, x.aux);
        }
        else if (op == A64Select.LDCCLASS)
        {
            syms.classLiteral(cb, d, x.aux);
        }
        else if (op == A64Select.STRX)
        {
            cb.emit(A64Enc.strx(b, a, (int) x.imm));
        }
        else if (op == A64Select.STRB)
        {
            cb.emit(A64Enc.strb(b, a, 0));
        }
        else if (op == A64Select.STRH)
        {
            cb.emit(A64Enc.strh(b, a, 0));
        }
        else if (op == A64Select.STRW)
        {
            cb.emit(A64Enc.strw(b, a, 0));
        }
        else if (op == A64Select.DMB)
        {
            cb.emit(A64Enc.dmbIsh());
        }
        else if (op == A64Select.BARRIER)
        {
            compiler.Baseline.emitWriteBarrier(cb, a, b, x.aux == 1);
        }
        else if (op == A64Select.CSET)
        {
            cb.emit(x.uses.length == 2 ? A64Enc.cmpReg(a, b) : A64Enc.cmpImm(a, 0));
            cb.emit(A64Enc.cset(d, x.aux));
        }
        else if (op == A64Select.UBNOT)
        {
            cb.emit(A64Enc.movn(SCRATCH, 0, 0));
            cb.emit(A64Enc.eorReg(d, a, SCRATCH));
        }
        else if (op == A64Select.LDRB)
        {
            cb.emit(A64Enc.ldrb(d, a, 0));
        }
        else if (op == A64Select.SMULH)
        {
            cb.emit(A64Enc.smulh(d, a, b));
        }
        else if (op == A64Select.UMULH)
        {
            cb.emit(A64Enc.umulh(d, a, b));
        }
        else if (op == A64Select.FENCE)
        {
            cb.emit(x.aux == 0 ? A64Enc.dsb() : A64Enc.isb());
        }
        else if (op == A64Select.MRS)
        {
            cb.emit(A64Enc.mrs(d, x.aux));
        }
        else if (op == A64Select.VCALL)
        {
            // Baseline's vtable dispatch, writer form (no guards): the receiver is in x0
            cb.emit(A64Enc.ldrx(SCRATCH, 0, ObjectModel.TIB_OFFSET));
            cb.emit(A64Enc.ldrx(SCRATCH, SCRATCH, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot((int) x.imm))));
            dynamic(x.aux);
        }
        else if (op == A64Select.ICALL)
        {
            // Baseline's itableDispatch, writer form: x16 = the interface's Type, x17 walks the receiver's itable
            // directory (x18 the entry's interface), then the found table's slot is the target
            syms.interfaceType(cb, SCRATCH, x.aux);
            cb.emit(A64Enc.ldrx(17, 0, ObjectModel.TIB_OFFSET));
            cb.emit(A64Enc.ldrx(17, 17, ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT)));
            cb.emit(A64Enc.ldrx(17, 17, ObjectModel.TYPE_ITABLE_DIR_OFFSET));
            int search = cb.wordCount();
            cb.emit(A64Enc.ldrx(18, 17, ObjectModel.ITABLE_ENTRY_IFACE_OFFSET));
            cb.emit(A64Enc.cmpReg(18, SCRATCH));
            int beq = cb.emit(A64Enc.bcond(A64Enc.EQ, 0));
            cb.emit(A64Enc.addImm(17, 17, ObjectModel.ITABLE_ENTRY_SIZE));
            cb.emit(A64Enc.b(search - cb.wordCount()));
            cb.set(beq, A64Enc.bcond(A64Enc.EQ, cb.wordCount() - beq));
            cb.emit(A64Enc.ldrx(17, 17, ObjectModel.ITABLE_ENTRY_TABLE_OFFSET));
            cb.emit(A64Enc.ldrx(SCRATCH, 17, (int) x.imm * ObjectModel.WORD));
            dynamic(x.aux);
        }
        else if (op == A64Select.YIELD)
        {
            // Baseline's poll (emitYieldPoll): load the request word; only when any core's byte is set, call the
            // helper, which takes this core's request -- a task switch or a park for a collection -- and returns.
            cb.emitAll(A64Enc.loadImm64(SCRATCH, syms.yieldCellAddr()));
            cb.emit(A64Enc.ldrx(SCRATCH, SCRATCH, 0));
            int over = cb.emit(A64Enc.cbz(SCRATCH, 0));
            syms.callHelper(cb, Symbols.YIELDPOINT);
            site(cb.wordCount() - 1);
            cb.set(over, A64Enc.cbz(SCRATCH, cb.wordCount() - over));
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
            if (saveLR)
            {
                cb.emit(A64Enc.ldrx(30, 31, 0));
            }
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

    /** {@code blr x16}, its GC map site, and the dispatch-site record. */
    private void dynamic(int cp)
    {
        int w = cb.emit(A64Enc.blr(SCRATCH));
        site(w);
        if (nDyn == dynWord.length)
        {
            int[] a = new int[nDyn * 2];
            int[] b = new int[nDyn * 2];
            for (int i = 0; i < nDyn; i++)
            {
                a[i] = dynWord[i];
                b[i] = dynCp[i];
            }
            dynWord = a;
            dynCp = b;
        }
        dynWord[nDyn] = w;
        dynCp[nDyn] = cp;
        nDyn += 1;
    }

    /** Record a GC map site at the BL in word {@code w}: the spill slots live after it. */
    private void site(int w)
    {
        if (nSites == siteWord.length)
        {
            int[] sw = new int[nSites * 2];
            int[][] sv = new int[nSites * 2][];
            for (int i = 0; i < nSites; i++)
            {
                sw[i] = siteWord[i];
                sv[i] = siteLive[i];
            }
            siteWord = sw;
            siteLive = sv;
        }
        int bitWords = (2 * f.nSlots + 31) / 32;
        int[] bits = new int[bitWords];
        for (int s = 0; s < f.nSlots; s++)
        {
            if ((liveOutSlots[s >> 6] & (1L << (s & 63))) != 0)
            {
                int t = f.vgc[f.slotVreg[s]];
                int bit = 2 * s;
                bits[bit >> 5] |= t << (bit & 31);
            }
        }
        siteWord[nSites] = w;
        siteLive[nSites] = bits;
        nSites += 1;
    }

    /** The GMAP map in {@code compiler.RefMaps}'s format: header, sites by return word, then the unique maps. The
     *  spill slots are its "locals" (based at {@link #slotBase}); there are no operand slots. */
    private void emitMap()
    {
        int bitWords = (2 * f.nSlots + 31) / 32;
        int[] uniq = new int[nSites];
        int nUnique = 0;
        for (int i = 0; i < nSites; i++)
        {
            int u = 0;
            while (u < nUnique && !same(siteLive[uniq[u]], siteLive[i]))
            {
                u += 1;
            }
            if (u == nUnique)
            {
                uniq[nUnique] = i;
                nUnique += 1;
            }
            siteWord[i] = (siteWord[i] + 1) | (u << 20);   // return word | its map index, unpacked below
        }
        gcMapWord = cb.emit(0x50414D47);                   // RefMaps.MAGIC, "GMAP"
        cb.emit(nSites);
        cb.emit(f.nSlots);                                 // "locals": the spill slots
        cb.emit(0);                                        // no operand slots
        cb.emit(slotBase);
        cb.emit(slotBase);
        cb.emit(bitWords);
        cb.emit(nUnique);
        cb.emit(0);                                        // no out-args area
        cb.emit(0);
        for (int i = 0; i < nSites; i++)
        {
            cb.emit(siteWord[i] & 0xFFFFF);
            cb.emit(siteWord[i] >>> 20);                   // map index | live depth 0 | entering depth 0
        }
        for (int u = 0; u < nUnique; u++)
        {
            int[] bits = siteLive[uniq[u]];
            for (int k = 0; k < bitWords; k++)
            {
                cb.emit(bits[k]);
            }
        }
    }

    private static boolean same(int[] a, int[] b)
    {
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i])
            {
                return false;
            }
        }
        return true;
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
