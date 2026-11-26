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
package asm;

import harness.T;

import java.util.Random;

/**
 * The emulator against Java's own arithmetic: each instruction class writer.OptDiff relies on, encoded by
 * {@link A64Enc} (whose encodings are checked bit-for-bit in A64Test), run on random operands. The emulator is the
 * substrate BOTH tiers run on in the differential test, so a decode error there could hide a real mismatch by
 * mis-running both alike -- this is what rules that out.
 *
 * <p>Run: {@code java asm.A64EmuTest}
 */
public final class A64EmuTest
{
    private static int bad;
    private static int runs;

    public static void main(String[] args)
    {
        Random r = new Random(7);
        for (int t = 0; t < 2000; t++)
        {
            long a = pick(r);
            long b = pick(r);
            long c = pick(r);
            bin("add", A64Enc.addReg(2, 0, 1), a, b, a + b);
            bin("sub", A64Enc.subReg(2, 0, 1), a, b, a - b);
            bin("mul", A64Enc.mulReg(2, 0, 1), a, b, a * b);
            bin("sdiv", A64Enc.sdivReg(2, 0, 1), a, b, b == 0 ? 0 : (a == Long.MIN_VALUE && b == -1 ? a : a / b));
            bin("and", A64Enc.andReg(2, 0, 1), a, b, a & b);
            bin("orr", A64Enc.orrReg(2, 0, 1), a, b, a | b);
            bin("eor", A64Enc.eorReg(2, 0, 1), a, b, a ^ b);
            bin("lslv", A64Enc.lslv(2, 0, 1), a, b, a << (b & 63));
            bin("lsrv", A64Enc.lsrv(2, 0, 1), a, b, a >>> (b & 63));
            bin("asrv", A64Enc.asrv(2, 0, 1), a, b, a >> (b & 63));
            bin("addlsl3", A64Enc.addRegLsl(2, 0, 1, 3), a, b, a + (b << 3));
            un("sxtw", A64Enc.sxtw(2, 0), a, (int) a);
            un("sxtb", A64Enc.sxtb(2, 0), a, (byte) a);
            un("sxth", A64Enc.sxth(2, 0), a, (short) a);
            un("uxth", A64Enc.uxth(2, 0), a, a & 0xFFFF);
            un("uxtw", A64Enc.uxtw(2, 0), a, a & 0xFFFF_FFFFL);
            un("andLow5", A64Enc.andLowBits(2, 0, 5), a, a & 31);
            un("neg", A64Enc.subReg(2, A64Enc.XZR, 0), a, -a);
            un("mov", A64Enc.movReg(2, 0), a, a);
            un("addImm", A64Enc.addImm(2, 0, 24), a, a + 24);
            un("subImm", A64Enc.subImm(2, 0, 4095), a, a - 4095);
            check("msub", seq(new int[] { A64Enc.msub(3, 0, 1, 2) }, a, b, c), c - a * b);
            check("lcmp", seq(new int[] { A64Enc.cmpReg(0, 1), A64Enc.cset(2, A64Enc.GT),
                                          A64Enc.csinv(2, 2, A64Enc.XZR, A64Enc.GE) }, a, b, 0, 2),
                  Long.compare(a, b));
            check("imm64", seq(A64Enc.loadImm64(2, a), 0, 0, 0, 2), a);
            for (int cc = 0; cc < 14; cc++)
            {
                check("cond " + cc, seq(new int[] { A64Enc.cmpReg(0, 1), A64Enc.cset(2, cc) }, a, b, 0, 2),
                      holds(cc, a, b) ? 1 : 0);
                check("cmpImm0 cond " + cc, seq(new int[] { A64Enc.cmpImm(0, 0), A64Enc.cset(2, cc) }, a, b, 0, 2),
                      holds(cc, a, 0) ? 1 : 0);
            }
            check("bcond lt", seq(new int[] { A64Enc.cmpReg(0, 1), A64Enc.bcond(A64Enc.LT, 3), A64Enc.movz(2, 7, 0),
                                              A64Enc.ret(), A64Enc.movz(2, 9, 0) }, a, b, 0, 2),
                  a < b ? 9 : 7);
            check("cbz", seq(new int[] { A64Enc.cbz(0, 3), A64Enc.movz(2, 7, 0), A64Enc.ret(), A64Enc.movz(2, 9, 0) },
                             a & 3, 0, 0, 2), (a & 3) == 0 ? 9 : 7);
            check("cbnz", seq(new int[] { A64Enc.cbnz(0, 3), A64Enc.movz(2, 7, 0), A64Enc.ret(),
                                          A64Enc.movz(2, 9, 0) }, a & 3, 0, 0, 2), (a & 3) != 0 ? 9 : 7);
            check("b", seq(new int[] { A64Enc.b(2), A64Enc.movz(2, 7, 0), A64Enc.movz(2, 9, 0) }, 0, 0, 0, 2), 9);
            memory(a, b);
        }
        T.check("emulator: " + runs + " runs, " + bad + " wrong", bad == 0 && runs > 50000);
        T.summary("A64EmuTest");
    }

    private static long pick(Random r)
    {
        long[] edge = { 0, 1, -1, 2, 31, 32, 63, 64, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, 0xFFFF_FFFFL, 0x8000_0000L };
        return r.nextInt(3) == 0 ? edge[r.nextInt(edge.length)] : r.nextLong();
    }

    private static boolean holds(int cc, long a, long b)
    {
        if (cc == A64Enc.EQ)
        {
            return a == b;
        }
        if (cc == A64Enc.NE)
        {
            return a != b;
        }
        if (cc == A64Enc.HS)
        {
            return Long.compareUnsigned(a, b) >= 0;
        }
        if (cc == A64Enc.LO)
        {
            return Long.compareUnsigned(a, b) < 0;
        }
        if (cc == A64Enc.MI)
        {
            return a - b < 0;
        }
        if (cc == A64Enc.PL)
        {
            return a - b >= 0;
        }
        boolean ovf = ((a ^ b) & (a ^ (a - b))) < 0;
        if (cc == A64Enc.VS)
        {
            return ovf;
        }
        if (cc == A64Enc.VC)
        {
            return !ovf;
        }
        if (cc == A64Enc.HI)
        {
            return Long.compareUnsigned(a, b) > 0;
        }
        if (cc == A64Enc.LS)
        {
            return Long.compareUnsigned(a, b) <= 0;
        }
        if (cc == A64Enc.GE)
        {
            return a >= b;
        }
        if (cc == A64Enc.LT)
        {
            return a < b;
        }
        if (cc == A64Enc.GT)
        {
            return a > b;
        }
        return a <= b;
    }

    /** Loads and stores at a base in x0, with the widths and extensions the compilers use. */
    private static void memory(long v, long off)
    {
        long base = 0x4000_0000L + ((off & 0xFFFF) << 3);
        int[] code = {
            A64Enc.strx(1, 0, 8), A64Enc.ldrx(2, 0, 8), A64Enc.ldrsw(3, 0, 8), A64Enc.ldrsh(4, 0, 8),
            A64Enc.ldrsb(5, 0, 8), A64Enc.ldrh(6, 0, 8), A64Enc.ldrw(7, 0, 8), A64Enc.ldrb(8, 0, 8), A64Enc.ret(),
        };
        A64Emu e = new A64Emu();
        e.x[0] = base;
        e.x[1] = v;
        boolean ok = e.run(code, 0x1000, 100);
        runs += 1;
        if (!ok || e.x[2] != v || e.x[3] != (int) v || e.x[4] != (short) v || e.x[5] != (byte) v
            || e.x[6] != (v & 0xFFFF) || e.x[7] != (v & 0xFFFF_FFFFL) || e.x[8] != (v & 0xFF))
        {
            fail("memory", v);
        }
    }

    private static void bin(String name, int w, long a, long b, long want)
    {
        check(name, seq(new int[] { w }, a, b, 0, 2), want);
    }

    private static void un(String name, int w, long a, long want)
    {
        check(name, seq(new int[] { w }, a, 0, 0, 2), want);
    }

    private static long seq(int[] body, long a, long b, long c)
    {
        return seq(body, a, b, c, 3);
    }

    /** Run {@code body; ret} with x0..x2 = a, b, c; answer x{@code out}. */
    private static long seq(int[] body, long a, long b, long c, int out)
    {
        int[] code = new int[body.length + 1];
        System.arraycopy(body, 0, code, 0, body.length);
        code[body.length] = A64Enc.ret();
        A64Emu e = new A64Emu();
        e.x[0] = a;
        e.x[1] = b;
        e.x[2] = c;
        runs += 1;
        if (!e.run(code, 0x1000, 100))
        {
            bad += 1;
            System.out.println("FAIL emulator did not finish: " + e.unsupported);
            return Long.MIN_VALUE + 12345;
        }
        return e.x[out];
    }

    private static void check(String name, long got, long want)
    {
        if (got != want)
        {
            fail(name, got);
            System.out.println("    want 0x" + Long.toHexString(want));
        }
    }

    private static void fail(String name, long got)
    {
        bad += 1;
        if (bad <= 20)
        {
            System.out.println("FAIL emu " + name + ": got 0x" + Long.toHexString(got));
        }
    }
}
