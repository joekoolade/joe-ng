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

import harness.T;

import java.util.Random;

/**
 * The target-neutral back half (O1-2a): {@link PhiElim}, {@link Constraints}, {@link LinearScan} and
 * {@link AllocCheck}, run against TWO register files -- AArch64's and a test-only x86-64-shaped one (16 registers,
 * two-address arithmetic, a divide pinned to rax/rdx, the return address on the stack). The second one exists so
 * the seam is exercised by more than one target before an ARM assumption hardens into the shared code (PLAN.md
 * "Targets").
 *
 * <p>Correctness is checked twice over: the allocation checker, and an interpreter that runs each program on
 * virtual registers BEFORE allocation and on physical registers and spill slots AFTER it -- with a call filling
 * every register it destroys with garbage -- and requires the same answer. Programs are random (seeded) and
 * hand-made.
 *
 * <p>Run: {@code java compiler.opt.AllocTest}
 */
public final class AllocTest
{
    // the test ISA's opcodes (a target's own opcodes are >= 0, their meaning the target's)
    static final int CONST = 0;
    static final int ADD = 1;
    static final int SUB = 2;
    static final int MUL = 3;
    static final int DIV = 4;
    static final int BLT = 5;
    static final int RET = 6;
    static final int CALL = 7;
    static final int PARAM = 8;
    static final int TOFP = 9;
    static final int FADD = 10;
    static final int FROMFP = 11;

    /** An x86-64-shaped register file. Never encoded -- it is the allocator's second customer. */
    static final class X86Shape extends OptTarget
    {
        static final int RAX = 0;
        static final int RCX = 1;
        static final int RDX = 2;
        static final int RSP = 4;
        static final int RBP = 5;
        static final int[] ARGS = { 7, 6, 2, 1, 8, 9 };     // rdi rsi rdx rcx r8 r9
        private static final RegFile REGS = new RegFile(
                new String[] { "rax", "rcx", "rdx", "rbx", "rsp", "rbp", "rsi", "rdi", "r8", "r9", "r10", "r11",
                               "r12", "r13", "r14", "r15" },
                RegFile.range(0, 15) & ~RegFile.mask(RSP, RBP, 3, 12, 13, 14, 15),   // caller-saved only, as O1
                RegFile.mask(0, 1, 2, 6, 7, 8, 9, 10, 11),
                names("xmm", 16), RegFile.range(0, 15), RegFile.range(0, 15));

        public String name()
        {
            return "x86-64-shaped";
        }

        public RegFile regs()
        {
            return REGS;
        }

        public int wordBytes()
        {
            return 8;
        }

        public boolean returnAddressOnStack()
        {
            return true;
        }

        public boolean pure(MInstr x)
        {
            return false;
        }

        public int stackAlign()
        {
            return 16;
        }
    }

    static String[] names(String p, int n)
    {
        String[] s = new String[n];
        for (int i = 0; i < n; i++)
        {
            s[i] = p + i;
        }
        return s;
    }

    private static final int[] A64_ARGS = { 0, 1, 2, 3, 4, 5, 6, 7 };

    public static void main(String[] args)
    {
        OptTarget[] targets = { new A64Target(), new X86Shape() };
        for (OptTarget t : targets)
        {
            handMade(t);
            int ok = 0;
            int spilledPrograms = 0;
            for (int seed = 1; seed <= 400; seed++)
            {
                Program p = new Program(t, seed);
                String why = p.check();
                if (why == null)
                {
                    ok += 1;
                    if (p.f.nSlots > 0)
                    {
                        spilledPrograms += 1;
                    }
                }
                else
                {
                    T.check(t.name() + " seed " + seed + ": " + why, false);
                }
            }
            System.out.println(t.name() + ": " + ok + "/400 random programs agree, " + spilledPrograms + " of them spill");
            T.eq(t.name() + ": random programs agree before/after allocation", 400, ok);
            T.check(t.name() + ": some random programs spill (" + spilledPrograms + ")", spilledPrograms > 20);
        }
        checkerCatches();
        T.summary("AllocTest");
    }

    // ----- hand-made shapes ------------------------------------------------------------------------------------

    private static void handMade(OptTarget t)
    {
        // a loop whose header phis swap two values each iteration: the parallel copy is a cycle
        Builder b = new Builder(t);
        MBlock entry = b.f.newBlock();
        MBlock head = b.f.newBlock();
        MBlock body = b.f.newBlock();
        MBlock exit = b.f.newBlock();
        int x = b.param(entry, 0);
        int y = b.param(entry, 1);
        int n = b.param(entry, 2);
        int zero = b.konst(entry, 0);
        b.jump(entry, head);
        int px = b.f.newVreg(RegFile.INT);
        int py = b.f.newVreg(RegFile.INT);
        int pi = b.f.newVreg(RegFile.INT);
        int one = b.f.newVreg(RegFile.INT);
        int ni = b.f.newVreg(RegFile.INT);
        head.add(b.phi(px, x, py));
        head.add(b.phi(py, y, px));
        head.add(b.phi(pi, zero, ni));
        b.blt(head, pi, n, body, exit);
        b.konstInto(body, one, 1);
        b.arith(body, ADD, ni, pi, one);
        b.jump(body, head);
        int r = b.f.newVreg(RegFile.INT);
        int ten = b.konst(exit, 10);
        int scaled = b.f.newVreg(RegFile.INT);
        b.arith(exit, MUL, scaled, px, ten);
        b.arith(exit, ADD, r, scaled, py);
        b.ret(exit, r);
        Program p = new Program(t, b.f, new long[] { 3, 4, 5 });
        T.check(t.name() + ": swap cycle (" + p.check() + ")", p.check() == null && p.expected == 43);

        // a value live across a call: every allocatable register is caller-saved in O1, so it must spill
        b = new Builder(t);
        entry = b.f.newBlock();
        int a = b.param(entry, 0);
        int c = b.call(entry, new int[] { a }, 5);
        int s = b.f.newVreg(RegFile.INT);
        b.arith(entry, ADD, s, a, c);
        b.ret(entry, s);
        p = new Program(t, b.f, new long[] { 7 });
        T.check(t.name() + ": live across a call (" + p.check() + ")", p.check() == null);
        T.check(t.name() + ": ... spills", p.f.nSlots > 0);

        // divide: pinned to rax/rdx on the x86 shape
        b = new Builder(t);
        entry = b.f.newBlock();
        a = b.param(entry, 0);
        int d = b.param(entry, 1);
        int q = b.f.newVreg(RegFile.INT);
        b.div(entry, q, a, d);
        int q2 = b.f.newVreg(RegFile.INT);
        b.div(entry, q2, d, q);
        b.ret(entry, q2);
        p = new Program(t, b.f, new long[] { 100, 7 });
        T.check(t.name() + ": divide (" + p.check() + ")", p.check() == null && p.expected == 0);
    }

    /**
     * Deliberately broken allocations. A change is not automatically a bug -- moving a value to a register free for
     * its whole life is legal -- so the interpreter is the judge: every corruption that changes the answer must be
     * reported, and the collisions made here (an operand put in the register of the instruction's other operand)
     * must all be.
     */
    private static void checkerCatches()
    {
        OptTarget t = new X86Shape();
        int collisions = 0;
        int collisionsCaught = 0;
        int wrong = 0;
        int wrongCaught = 0;
        for (int seed = 1; seed <= 80; seed++)
        {
            Program p = new Program(t, seed);
            if (p.check() != null)
            {
                continue;
            }
            MFunc f = p.f;
            for (int bi = 0; bi < f.nBlocks; bi++)
            {
                MBlock blk = f.blocks[bi];
                for (int i = 0; i < blk.n; i++)
                {
                    MInstr x = blk.code[i];
                    if (x.uses.length < 2 || f.fixed[x.uses[0]] >= 0 || x.tied >= 0)
                    {
                        continue;
                    }
                    int v = x.uses[0];
                    int w = x.uses[1];
                    if (v == w || f.vclass[v] != f.vclass[w] || f.origin[v] == f.origin[w] || f.loc[v] == f.loc[w])
                    {
                        continue;
                    }
                    int old = f.loc[v];
                    f.loc[v] = f.loc[w];
                    collisions += 1;
                    boolean caught = AllocCheck.check(f, t.regs()) != null;
                    if (caught)
                    {
                        collisionsCaught += 1;
                    }
                    Interp after = new Interp(f, t.regs());
                    long got = after.run(p.params);
                    if (after.error != null || got != p.expected)
                    {
                        wrong += 1;
                        if (caught)
                        {
                            wrongCaught += 1;
                        }
                    }
                    f.loc[v] = old;
                }
            }
            for (int bi = 0; bi < f.nBlocks; bi++)
            {
                MBlock blk = f.blocks[bi];
                for (int i = 0; i < blk.n; i++)
                {
                    if (blk.code[i].op != MInstr.RELOAD)
                    {
                        continue;
                    }
                    MInstr keep = blk.code[i];
                    for (int j = i; j < blk.n - 1; j++)
                    {
                        blk.code[j] = blk.code[j + 1];
                    }
                    blk.n -= 1;
                    boolean caught = AllocCheck.check(f, t.regs()) != null;
                    Interp after = new Interp(f, t.regs());
                    long got = after.run(p.params);
                    if (after.error != null || got != p.expected)
                    {
                        wrong += 1;
                        if (caught)
                        {
                            wrongCaught += 1;
                        }
                    }
                    blk.insert(i, keep);
                }
            }
        }
        System.out.println("checker: " + collisionsCaught + "/" + collisions + " collisions caught, " + wrongCaught + "/"
                           + wrong + " answer-changing corruptions caught");
        T.check("checker: collisions tried (" + collisions + ")", collisions > 40);
        T.eq("checker: every collision caught", collisions, collisionsCaught);
        T.check("checker: corruptions that change the answer (" + wrong + ")", wrong > 40);
        T.eq("checker: every one of them caught", wrong, wrongCaught);
    }

    // ----- building programs -----------------------------------------------------------------------------------

    /** Emits test-ISA instructions with the constraints the target imposes. */
    static final class Builder
    {
        final OptTarget t;
        final boolean x86;
        final MFunc f = new MFunc();

        Builder(OptTarget t)
        {
            this.t = t;
            this.x86 = t instanceof X86Shape;
        }

        int[] argRegs()
        {
            return x86 ? X86Shape.ARGS : A64_ARGS;
        }

        int param(MBlock b, int i)
        {
            int v = f.newVreg(RegFile.INT);
            MInstr x = new MInstr(PARAM, new int[] { v }, null);
            x.aux = i;
            x.fixedDefs = new int[] { argRegs()[i] };
            b.add(x);
            return v;
        }

        int konst(MBlock b, long c)
        {
            int v = f.newVreg(RegFile.INT);
            konstInto(b, v, c);
            return v;
        }

        void konstInto(MBlock b, int v, long c)
        {
            MInstr x = new MInstr(CONST, new int[] { v }, null);
            x.imm = c;
            b.add(x);
        }

        void arith(MBlock b, int op, int d, int l, int r)
        {
            MInstr x = new MInstr(op, new int[] { d }, new int[] { l, r });
            if (x86)
            {
                x.tied = 0;                                  // add/sub/imul r, r/m: the result replaces operand 0
            }
            b.add(x);
        }

        void div(MBlock b, int q, int l, int r)
        {
            int rem = f.newVreg(RegFile.INT);
            MInstr x = new MInstr(DIV, new int[] { q, rem }, new int[] { l, r });
            if (x86)
            {
                x.fixedUses = new int[] { X86Shape.RAX, -1 };
                x.fixedDefs = new int[] { X86Shape.RAX, X86Shape.RDX };   // idiv: rdx:rax / r -> rax, rdx
            }
            b.add(x);
        }

        int call(MBlock b, int[] args, int aux)
        {
            int v = f.newVreg(RegFile.INT);
            MInstr x = new MInstr(CALL, new int[] { v }, args.clone());
            x.aux = aux;
            x.fixedUses = new int[args.length];
            for (int i = 0; i < args.length; i++)
            {
                x.fixedUses[i] = argRegs()[i];
            }
            x.fixedDefs = new int[] { 0 };                   // x0 / rax
            x.clobbers = new long[] { t.regs().callerSaved(RegFile.INT), t.regs().callerSaved(RegFile.FP) };
            b.add(x);
            return v;
        }

        MInstr phi(int d, int... in)
        {
            return new MInstr(MInstr.PHI, new int[] { d }, in);
        }

        void blt(MBlock b, int l, int r, MBlock yes, MBlock no)
        {
            MInstr x = new MInstr(BLT, null, new int[] { l, r });
            x.term = true;
            x.targets = new MBlock[] { yes, no };
            b.add(x);
            b.link(yes);
            b.link(no);
        }

        void jump(MBlock b, MBlock to)
        {
            b.add(MInstr.jump(to));
            b.link(to);
        }

        void ret(MBlock b, int v)
        {
            MInstr x = new MInstr(RET, null, new int[] { v });
            x.term = true;
            x.fixedUses = new int[] { 0 };
            b.add(x);
        }

        int unary(MBlock b, int op, int cls, int src)
        {
            int v = f.newVreg(cls);
            b.add(new MInstr(op, new int[] { v }, new int[] { src }));
            return v;
        }
    }

    /** A program, its expected answer from the virtual-register run, and the check after allocation. */
    static final class Program
    {
        final OptTarget t;
        final MFunc f;
        final long[] params;
        long expected;

        Program(OptTarget t, MFunc f, long[] params)
        {
            this.t = t;
            this.f = f;
            this.params = params;
        }

        /** A random program: params, a counted loop whose header carries several values (some swapped), a body
         *  of random arithmetic with a diamond, a call and a divide in it, under pressure set by the seed. */
        Program(OptTarget t, int seed)
        {
            this.t = t;
            Random r = new Random(seed);
            Builder b = new Builder(t);
            f = b.f;
            int nParams = 3;
            params = new long[] { r.nextInt(50), r.nextInt(50), 1 + r.nextInt(6) };
            MBlock entry = f.newBlock();
            MBlock head = f.newBlock();
            MBlock body = f.newBlock();
            MBlock left = f.newBlock();
            MBlock right = f.newBlock();
            MBlock join = f.newBlock();
            MBlock exit = f.newBlock();
            int[] p = new int[nParams];
            for (int i = 0; i < nParams; i++)
            {
                p[i] = b.param(entry, i);
            }
            int width = 2 + r.nextInt(r.nextBoolean() ? 6 : 24);   // values carried round the loop
            int[] init = new int[width];
            for (int i = 0; i < width; i++)
            {
                init[i] = b.konst(entry, r.nextInt(100) - 50);
            }
            int zero = b.konst(entry, 0);
            b.jump(entry, head);
            int[] cur = new int[width];
            int[] next = new int[width];
            for (int i = 0; i < width; i++)
            {
                cur[i] = f.newVreg(RegFile.INT);
                next[i] = f.newVreg(RegFile.INT);
            }
            int ctr = f.newVreg(RegFile.INT);
            int ctrNext = f.newVreg(RegFile.INT);
            boolean swap = r.nextBoolean();
            for (int i = 0; i < width; i++)
            {
                int back = next[i];
                if (swap && i < 2)
                {
                    back = next[1 - i];                       // header phis exchange two values: a copy cycle
                }
                head.add(b.phi(cur[i], init[i], back));
            }
            head.add(b.phi(ctr, zero, ctrNext));
            b.blt(head, ctr, p[2], body, exit);

            // body: random ops over a pool of live values
            int[] pool = new int[width + nParams + 64];
            int np = 0;
            for (int i = 0; i < width; i++)
            {
                pool[np] = cur[i];
                np += 1;
            }
            for (int i = 0; i < nParams; i++)
            {
                pool[np] = p[i];
                np += 1;
            }
            int ops = 4 + r.nextInt(20);
            for (int k = 0; k < ops; k++)
            {
                int a = pool[r.nextInt(np)];
                int c = pool[r.nextInt(np)];
                int d = f.newVreg(RegFile.INT);
                int kind = r.nextInt(10);
                if (kind < 6)
                {
                    b.arith(body, ADD + r.nextInt(3), d, a, c);
                }
                else if (kind < 7)
                {
                    int sq = f.newVreg(RegFile.INT);
                    b.arith(body, MUL, sq, c, c);
                    int one = b.konst(body, 1);
                    int den = f.newVreg(RegFile.INT);
                    b.arith(body, ADD, den, sq, one);            // c*c+1 is never 0 mod 2^64
                    b.div(body, d, a, den);
                }
                else if (kind < 8)
                {
                    d = b.call(body, new int[] { a, c }, k);
                }
                else
                {
                    int fa = b.unary(body, TOFP, RegFile.FP, a);
                    int fc = b.unary(body, TOFP, RegFile.FP, c);
                    int fs = f.newVreg(RegFile.FP);
                    body.add(new MInstr(FADD, new int[] { fs }, new int[] { fa, fc }));
                    d = b.unary(body, FROMFP, RegFile.INT, fs);
                }
                pool[np] = d;
                np += 1;
            }
            b.blt(body, pool[np - 1], pool[r.nextInt(np)], left, right);
            int lv = f.newVreg(RegFile.INT);
            b.arith(left, ADD, lv, pool[r.nextInt(np)], pool[r.nextInt(np)]);
            b.jump(left, join);
            int rv = f.newVreg(RegFile.INT);
            b.arith(right, SUB, rv, pool[r.nextInt(np)], pool[r.nextInt(np)]);
            b.jump(right, join);
            int m = f.newVreg(RegFile.INT);
            join.add(b.phi(m, lv, rv));
            for (int i = 0; i < width; i++)
            {
                int src = r.nextInt(3) == 0 ? m : pool[r.nextInt(np)];
                int one = b.konst(join, i + 1);
                b.arith(join, ADD, next[i], src, one);
            }
            int one = b.konst(join, 1);
            b.arith(join, ADD, ctrNext, ctr, one);
            b.jump(join, head);

            int acc = cur[0];
            for (int i = 1; i < width; i++)
            {
                int s = f.newVreg(RegFile.INT);
                b.arith(exit, i % 2 == 0 ? ADD : SUB, s, acc, cur[i]);
                acc = s;
            }
            b.ret(exit, acc);
        }

        /** Run before, allocate, check, run after; null when all agree. */
        String check()
        {
            Interp before = new Interp(f, null);
            expected = before.run(params);
            if (before.error != null)
            {
                return "before: " + before.error;
            }
            PhiElim.run(f);
            Constraints.run(f);
            try
            {
                LinearScan.run(f, t.regs());
            }
            catch (IllegalStateException e)
            {
                return "allocator: " + e.getMessage();
            }
            String why = AllocCheck.check(f, t.regs());
            if (why != null)
            {
                return "checker: " + why;
            }
            Interp after = new Interp(f, t.regs());
            long got = after.run(params);
            if (after.error != null)
            {
                return "after: " + after.error;
            }
            return got == expected ? null : "answer " + got + " != " + expected;
        }
    }

    /** The test ISA, on virtual registers (rf null) or on the allocation's physical registers and slots. */
    static final class Interp
    {
        final MFunc f;
        final RegFile rf;
        final long[] env;
        final long[][] regs = new long[RegFile.CLASSES][64];
        long[] slots;
        final Random garbage = new Random(99);
        String error;

        Interp(MFunc f, RegFile rf)
        {
            this.f = f;
            this.rf = rf;
            this.env = new long[f.nV + 1];
        }

        long get(int v)
        {
            return rf == null ? env[v] : regs[f.vclass[v]][f.loc[v]];
        }

        void set(int v, long x)
        {
            if (rf == null)
            {
                env[v] = x;
            }
            else
            {
                regs[f.vclass[v]][f.loc[v]] = x;
            }
        }

        long run(long[] params)
        {
            slots = new long[f.nSlots + 1];
            for (int c = 0; c < RegFile.CLASSES; c++)
            {
                for (int p = 0; p < 64; p++)
                {
                    regs[c][p] = garbage.nextLong();
                }
            }
            MBlock b = f.blocks[0];
            MBlock from = null;
            int steps = 0;
            while (steps < 1000000)
            {
                int i = 0;
                long[] phiVals = new long[b.n];
                int k = from == null ? -1 : b.predIndex(from);
                while (i < b.n && b.code[i].op == MInstr.PHI)
                {
                    phiVals[i] = get(b.code[i].uses[k]);
                    i += 1;
                }
                for (int j = 0; j < i; j++)
                {
                    set(b.code[j].defs[0], phiVals[j]);
                }
                for (; i < b.n; i++)
                {
                    steps += 1;
                    MInstr x = b.code[i];
                    if (x.op == RET)
                    {
                        return get(x.uses[0]);
                    }
                    if (x.term)
                    {
                        MBlock next = x.targets[0];
                        if (x.op == BLT)
                        {
                            next = get(x.uses[0]) < get(x.uses[1]) ? x.targets[0] : x.targets[1];
                        }
                        from = b;
                        b = next;
                        break;
                    }
                    exec(x, params);
                }
            }
            error = "did not finish";
            return 0;
        }

        void exec(MInstr x, long[] params)
        {
            int op = x.op;
            if (op == MInstr.MOVE)
            {
                set(x.defs[0], get(x.uses[0]));
            }
            else if (op == MInstr.SPILL)
            {
                slots[x.aux] = get(x.uses[0]);
            }
            else if (op == MInstr.RELOAD)
            {
                set(x.defs[0], slots[x.aux]);
            }
            else if (op == CONST)
            {
                set(x.defs[0], x.imm);
            }
            else if (op == PARAM)
            {
                set(x.defs[0], params[x.aux]);
            }
            else if (op == ADD)
            {
                set(x.defs[0], get(x.uses[0]) + get(x.uses[1]));
            }
            else if (op == SUB)
            {
                set(x.defs[0], get(x.uses[0]) - get(x.uses[1]));
            }
            else if (op == MUL)
            {
                set(x.defs[0], get(x.uses[0]) * get(x.uses[1]));
            }
            else if (op == DIV)
            {
                long a = get(x.uses[0]);
                long d = get(x.uses[1]);
                set(x.defs[0], a / d);
                set(x.defs[1], a % d);
            }
            else if (op == CALL)
            {
                long s = x.aux;
                for (int k = 0; k < x.uses.length; k++)
                {
                    s = s * 31 + get(x.uses[k]);
                }
                if (rf != null)
                {
                    for (int c = 0; c < RegFile.CLASSES; c++)
                    {
                        for (int p = 0; p < 64; p++)
                        {
                            if ((x.clobbers(c) & (1L << p)) != 0)
                            {
                                regs[c][p] = garbage.nextLong();
                            }
                        }
                    }
                }
                set(x.defs[0], s);
            }
            else if (op == TOFP || op == FROMFP)
            {
                set(x.defs[0], get(x.uses[0]));
            }
            else if (op == FADD)
            {
                set(x.defs[0], get(x.uses[0]) ^ (get(x.uses[1]) << 1));
            }
            else
            {
                error = "unknown op " + op;
            }
        }
    }
}
