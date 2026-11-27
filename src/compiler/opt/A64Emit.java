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
    /** Spill slots [0, nHomes) are the homes (writer.OptDiff checks what the map says they hold). */
    public int nHomes;
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
        nHomes = f.nHomes;
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
        for (int s = 0; s < f.nHomes; s++)
        {
            if (f.homeGc[s] != MFunc.GC_RAW)
            {
                cb.emit(A64Enc.strx(A64Enc.XZR, 31, slotBase + s * 8));   // a reference home starts null
            }
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
        int end = cb.wordCount();
        patch();
        monotone();
        handlerRanges(end);
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
            int[] k = { A64Enc.dsb(), A64Enc.isb(), A64Enc.wfe(), A64Enc.sev(), A64Enc.icIallu(), A64Enc.tlbiVmalle1() };
            cb.emit(k[x.aux]);
        }
        else if (op == A64Select.FPOP)
        {
            fpop(x.aux, d, a, b);
        }
        else if (op == A64Select.MSR)
        {
            cb.emit(A64Enc.msr(x.aux, a));
        }
        else if (op == A64Select.CACHEOP)
        {
            int[] k = { A64Enc.dcCvau(a), A64Enc.dcCvac(a), A64Enc.icIvau(a), A64Enc.dcCivac(a) };
            cb.emit(k[x.aux]);
        }
        else if (op == A64Select.RCALL)
        {
            int target;
            if (x.aux == compiler.Intrinsics.CALL_N)
            {
                // Baseline's callN: x16 = the target, x17 = the argument buffer, then x0..x15 from the buffer
                cb.emit(A64Enc.movReg(SCRATCH, 0));
                cb.emit(A64Enc.movReg(17, 1));
                for (int i = 0; i < 16; i++)
                {
                    cb.emit(A64Enc.ldrx(i, 17, i * 8));
                }
                target = SCRATCH;
            }
            else
            {
                target = r(x.uses[x.uses.length - 1]);
            }
            raw(target, (int) x.imm);
        }
        else if (op == A64Select.MRS)
        {
            cb.emit(A64Enc.mrs(d, x.aux));
        }
        else if (op == A64Select.TIBSTORE)
        {
            syms.tib(cb, SCRATCH, x.aux);                  // x16 = the class's TIB (relocated)
            cb.emit(A64Enc.strx(SCRATCH, a, ObjectModel.TIB_OFFSET));
        }
        else if (op == A64Select.TAGARRAY)
        {
            syms.tagArray(cb, a, x.aux, x.imm != 0);
        }
        else if (op == A64Select.TAGUNBOXED)
        {
            syms.tagUnboxedArray(cb, a, x.aux);
        }
        else if (op == A64Select.TYPEADDR)
        {
            syms.type(cb, d, x.aux);
        }
        else if (op == A64Select.DESCADDR)
        {
            syms.arrayDescAddr(cb, d, x.aux);
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
        else if (op == MInstr.JUMP || op == A64Select.DELIVER)
        {
            jumpTo(x.targets[0], next);                      // DELIVER: its exception is already in x9
        }
        else if (op == A64Select.HSTORE)
        {
            cb.emit(A64Enc.strx(a, 31, slotBase + x.aux * 8));
        }
        else if (op == A64Select.HLOAD)
        {
            cb.emit(A64Enc.ldrx(d, 31, slotBase + x.aux * 8));
        }
        else if (op == A64Select.CATCHX)
        {
            return;                                          // the exception arrives in its pinned register
        }
        else if (op == A64Select.CODEPC)
        {
            syms.codePc(cb, d, cb.wordCount());              // a pc in this method, inside the thrower's ranges
        }
        else if (op == A64Select.SPVAL)
        {
            cb.emit(A64Enc.movFromSp(d));
        }
        else if (op == A64Select.HALT)
        {
            int h = cb.emit(A64Enc.wfe());
            cb.emit(A64Enc.b(h - cb.wordCount()));          // unwind never returns: spin, as Baseline's emitHalt
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

    /** Handler ranges, in exception-table order: per entry, a range for every run of blocks (in layout order)
     *  whose code its try range covers. Several ranges for one entry keep its place in the search, since the
     *  unwinder tries the table front to back. */
    public int nHandlers;
    public int[] hStart = new int[4];
    public int[] hEnd = new int[4];
    public int[] hHandler = new int[4];
    public int[] hEntry = new int[4];

    private void handlerRanges(int end)
    {
        for (int e = 0; e < f.handlerOf.length; e++)
        {
            MBlock h = handlerBlock(e);
            int start = -1;
            for (int b = 0; b <= f.nBlocks; b++)
            {
                boolean in = b < f.nBlocks && f.blocks[b].cover != null && f.blocks[b].cover[e];
                int w = b < f.nBlocks ? blockWord[f.blocks[b].id] : end;
                if (in && start < 0)
                {
                    start = w;
                }
                else if (!in && start >= 0)
                {
                    if (w > start)
                    {
                        if (h == null)
                        {
                            failed = true;                   // covered code with no handler to enter
                            return;
                        }
                        addRange(start, w, blockWord[h.id], e);
                    }
                    start = -1;
                }
            }
        }
    }

    /** The handler entry an exception-table entry names: recorded on the block by the selector. */
    private MBlock handlerBlock(int e)
    {
        return e < f.handlerOf.length ? f.handlerOf[e] : null;
    }

    private void addRange(int s, int e, int h, int entry)
    {
        if (nHandlers == hStart.length)
        {
            hStart = grow(hStart);
            hEnd = grow(hEnd);
            hHandler = grow(hHandler);
            hEntry = grow(hEntry);
        }
        hStart[nHandlers] = s;
        hEnd[nHandlers] = e;
        hHandler[nHandlers] = h;
        hEntry[nHandlers] = entry;
        nHandlers += 1;
    }

    private static int[] grow(int[] a)
    {
        int[] b = new int[a.length * 2];
        for (int i = 0; i < a.length; i++)
        {
            b[i] = a[i];
        }
        return b;
    }

    /** {@code blr x16}, its GC map site, and the dispatch-site record. */
    /** Baseline's float/double sequences (its fbinop, fneg, fcmp and conversions): the bits move to v0/v1, the
     *  result back to {@code d}. */
    private void fpop(int code, int d, int a, int b)
    {
        boolean dbl = (code & 1) != 0;
        if (code >= 0x62 && code <= 0x6F)
        {
            int kind = (code - 0x62) >> 2;                   // 0 add, 1 sub, 2 mul, 3 div
            if (dbl)
            {
                cb.emit(A64Enc.fmovXtoD(0, a));
                cb.emit(A64Enc.fmovXtoD(1, b));
                cb.emit(kind == 0 ? A64Enc.faddd(0, 0, 1) : kind == 1 ? A64Enc.fsubd(0, 0, 1)
                        : kind == 2 ? A64Enc.fmuld(0, 0, 1) : A64Enc.fdivd(0, 0, 1));
                cb.emit(A64Enc.fmovDtoX(d, 0));
            }
            else
            {
                cb.emit(A64Enc.fmovWtoS(0, a));
                cb.emit(A64Enc.fmovWtoS(1, b));
                cb.emit(kind == 0 ? A64Enc.fadds(0, 0, 1) : kind == 1 ? A64Enc.fsubs(0, 0, 1)
                        : kind == 2 ? A64Enc.fmuls(0, 0, 1) : A64Enc.fdivs(0, 0, 1));
                cb.emit(A64Enc.fmovStoW(d, 0));
            }
        }
        else if (code == 0x76 || code == 0x77)
        {
            if (dbl)
            {
                cb.emit(A64Enc.fmovXtoD(0, a));
                cb.emit(A64Enc.fnegd(0, 0));
                cb.emit(A64Enc.fmovDtoX(d, 0));
            }
            else
            {
                cb.emit(A64Enc.fmovWtoS(0, a));
                cb.emit(A64Enc.fnegs(0, 0));
                cb.emit(A64Enc.fmovStoW(d, 0));
            }
        }
        else if (code >= 0x95 && code <= 0x98)
        {
            if (code >= 0x97)
            {
                cb.emit(A64Enc.fmovXtoD(0, a));
                cb.emit(A64Enc.fmovXtoD(1, b));
                cb.emit(A64Enc.fcmpd(0, 1));
            }
            else
            {
                cb.emit(A64Enc.fmovWtoS(0, a));
                cb.emit(A64Enc.fmovWtoS(1, b));
                cb.emit(A64Enc.fcmps(0, 1));
            }
            if (code == 0x96 || code == 0x98)                // ...g: NaN (unordered, HI) -> +1
            {
                cb.emit(A64Enc.cset(d, A64Enc.HI));
                cb.emit(A64Enc.csinv(d, d, A64Enc.XZR, A64Enc.PL));
            }
            else                                             // ...l: NaN -> -1
            {
                cb.emit(A64Enc.cset(d, A64Enc.GT));
                cb.emit(A64Enc.csinv(d, d, A64Enc.XZR, A64Enc.GE));
            }
        }
        else if (code == 0x86)
        {
            cb.emit(A64Enc.scvtfSW(0, a));
            cb.emit(A64Enc.fmovStoW(d, 0));
        }
        else if (code == 0x87)
        {
            cb.emit(A64Enc.scvtfDW(0, a));
            cb.emit(A64Enc.fmovDtoX(d, 0));
        }
        else if (code == 0x89)
        {
            cb.emit(A64Enc.scvtfSX(0, a));
            cb.emit(A64Enc.fmovStoW(d, 0));
        }
        else if (code == 0x8A)
        {
            cb.emit(A64Enc.scvtfDX(0, a));
            cb.emit(A64Enc.fmovDtoX(d, 0));
        }
        else if (code == 0x8B)
        {
            cb.emit(A64Enc.fmovWtoS(0, a));
            cb.emit(A64Enc.fcvtzsWS(d, 0));
            cb.emit(A64Enc.sxtw(d, d));
        }
        else if (code == 0x8C)
        {
            cb.emit(A64Enc.fmovWtoS(0, a));
            cb.emit(A64Enc.fcvtzsXS(d, 0));
        }
        else if (code == 0x8D)
        {
            cb.emit(A64Enc.fmovWtoS(0, a));
            cb.emit(A64Enc.fcvtDS(0, 0));
            cb.emit(A64Enc.fmovDtoX(d, 0));
        }
        else if (code == 0x8E)
        {
            cb.emit(A64Enc.fmovXtoD(0, a));
            cb.emit(A64Enc.fcvtzsWD(d, 0));
            cb.emit(A64Enc.sxtw(d, d));
        }
        else if (code == 0x8F)
        {
            cb.emit(A64Enc.fmovXtoD(0, a));
            cb.emit(A64Enc.fcvtzsXD(d, 0));
        }
        else if (code == 0x90)
        {
            cb.emit(A64Enc.fmovXtoD(0, a));
            cb.emit(A64Enc.fcvtSD(0, 0));
            cb.emit(A64Enc.fmovStoW(d, 0));
        }
        else
        {
            throw new IllegalStateException("FPOP " + Integer.toHexString(code));
        }
    }

    /** A Magic raw call: the BLR, its map site, and its cp in the dispatch table (writer.OptDiff names it). */
    private void raw(int target, int cp)
    {
        int w = cb.emit(A64Enc.blr(target));
        site(w);
        noteDynamic(w, cp);
    }

    private void dynamic(int cp)
    {
        int w = cb.emit(A64Enc.blr(SCRATCH));
        site(w);
        noteDynamic(w, cp);
    }

    /** Record the BLR at word {@code w} as a call of Methodref {@code cp} (for writer.OptDiff). */
    private void noteDynamic(int w, int cp)
    {
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
        for (int s = 0; s < f.nHomes; s++)
        {
            int t = f.homeGc[s] == MFunc.GC_RAW ? 0 : f.homeGc[s];   // a raw home is never needed by the collector
            int bit = 2 * s;
            bits[bit >> 5] |= t << (bit & 31);
        }
        for (int s = f.nHomes; s < f.nSlots; s++)
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
