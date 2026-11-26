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

import java.util.HashMap;

/**
 * A small AArch64 user-mode emulator for host tests: the integer instructions the compilers emit in a LEAF body
 * (no calls), decoded from the ARM ARM encodings (A64 base instructions; DecodeBitMasks for logical immediates;
 * the bitfield moves). Memory is sparse: a word never stored reads as a hash of its address (zero a quarter of the
 * time), so two runs over the same addresses see the same contents. An encoding it does not know stops the run with {@link #unsupported}
 * set, never a guess.
 *
 * <p>Used by writer.OptDiff to run Baseline's code and the optimizing tier's for the same method on the same
 * arguments and compare.
 */
public final class A64Emu
{
    public final long[] x = new long[32];        // x31 here is SP; XZR is handled by the decoder
    private boolean n;
    private boolean z;
    private boolean c;
    private boolean v;
    private final HashMap<Long, Long> mem = new HashMap<>();
    public String unsupported;
    public long steps;
    /** What a {@code BL} does: the callee, simulated. Null: a BL is unsupported. */
    public CallHook calls;
    /** The target register's value at the last BLR (what a dispatch loaded). */
    public long lastTarget;
    private long codeBase;

    /** A simulated callee: {@code word} is the BL's word index in the code, x30 already holds the return address. */
    public interface CallHook
    {
        void call(A64Emu e, int word);
    }
    private static final long RETURN_SENTINEL = 0x5EED_0000_0000L;

    /** Run {@code code} (loaded at {@code base}) until it returns; false if it did not (budget, unknown op). */
    public boolean run(int[] code, long base, long maxSteps)
    {
        x[30] = RETURN_SENTINEL;
        codeBase = base;
        long pc = base;
        while (steps < maxSteps)
        {
            if (pc == RETURN_SENTINEL)
            {
                return true;
            }
            long idx = (pc - base) >> 2;
            if (idx < 0 || idx >= code.length)
            {
                unsupported = "pc left the code: 0x" + Long.toHexString(pc);
                return false;
            }
            steps += 1;
            long next = step(code[(int) idx], pc);
            if (unsupported != null)
            {
                return false;
            }
            pc = next;
        }
        return false;
    }

    // ----- memory ----------------------------------------------------------------------------------------------

    private static long hash(long a)
    {
        long h = a * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 32);
    }

    /** A word never stored: a hash of its address -- and zero a quarter of the time, so the scans the compilers
     *  emit (a string to its terminator, an itable directory to its matching entry) end, as they do over real
     *  memory. Both runs of a comparison read the same contents either way. */
    private long word(long a)
    {
        Long w = mem.get(a);
        if (w != null)
        {
            return w;
        }
        long h = hash(a);
        return (h >>> 62) == 0 ? 0L : h;
    }

    /** Little-endian load of {@code size} bytes (1, 2, 4, 8) at {@code a}, zero-extended. */
    public long load(long a, int size)
    {
        long r = 0;
        for (int i = 0; i < size; i++)
        {
            long b = a + i;
            long w = word(b & ~7L);
            r |= ((w >>> ((b & 7) * 8)) & 0xFF) << (i * 8);
        }
        return r;
    }

    public void store(long a, int size, long val)
    {
        for (int i = 0; i < size; i++)
        {
            long b = a + i;
            long wa = b & ~7L;
            long w = word(wa);
            int sh = (int) ((b & 7) * 8);
            w = (w & ~(0xFFL << sh)) | (((val >>> (i * 8)) & 0xFF) << sh);
            mem.put(wa, w);
        }
    }

    // ----- registers -------------------------------------------------------------------------------------------

    /** Register r as an operand where 31 is XZR. */
    private long rz(int r, boolean sf)
    {
        long val = r == 31 ? 0 : x[r];
        return sf ? val : val & 0xFFFFFFFFL;
    }

    /** Register r as an operand where 31 is SP. */
    private long rsp(int r, boolean sf)
    {
        long val = x[r];
        return sf ? val : val & 0xFFFFFFFFL;
    }

    private void setZ(int r, long val, boolean sf)
    {
        if (r != 31)
        {
            x[r] = sf ? val : val & 0xFFFFFFFFL;
        }
    }

    private void setSp(int r, long val, boolean sf)
    {
        x[r] = sf ? val : val & 0xFFFFFFFFL;
    }

    private boolean cond(int cc)
    {
        boolean r;
        int base = cc >> 1;
        if (base == 0)
        {
            r = z;
        }
        else if (base == 1)
        {
            r = c;
        }
        else if (base == 2)
        {
            r = n;
        }
        else if (base == 3)
        {
            r = v;
        }
        else if (base == 4)
        {
            r = c && !z;
        }
        else if (base == 5)
        {
            r = n == v;
        }
        else if (base == 6)
        {
            r = n == v && !z;
        }
        else
        {
            r = true;
        }
        if ((cc & 1) == 1 && cc != 15)
        {
            r = !r;
        }
        return r;
    }

    /** a + b + carry with flags (the ARM ARM's AddWithCarry), at 64 or 32 bits. */
    private long addFlags(long a, long b, boolean carry, boolean sf, boolean setFlags)
    {
        long r;
        if (sf)
        {
            r = a + b + (carry ? 1 : 0);
            if (setFlags)
            {
                n = r < 0;
                z = r == 0;
                c = Long.compareUnsigned(r, a) < 0 || (carry && r == a);
                v = ((a ^ r) & (b ^ r)) < 0;
            }
            return r;
        }
        long ua = a & 0xFFFFFFFFL;
        long ub = b & 0xFFFFFFFFL;
        long full = ua + ub + (carry ? 1 : 0);
        int r32 = (int) full;
        if (setFlags)
        {
            n = r32 < 0;
            z = r32 == 0;
            c = (full >>> 32) != 0;
            v = (((int) a ^ r32) & ((int) b ^ r32)) < 0;
        }
        return r32 & 0xFFFFFFFFL;
    }

    private static long shift(long val, int type, int amt, boolean sf)
    {
        int width = sf ? 64 : 32;
        amt = amt % width;
        if (!sf)
        {
            val &= 0xFFFFFFFFL;
        }
        long r;
        if (type == 0)
        {
            r = val << amt;
        }
        else if (type == 1)
        {
            r = val >>> amt;
        }
        else if (type == 2)
        {
            r = sf ? val >> amt : ((long) ((int) val >> amt));
        }
        else
        {
            r = sf ? Long.rotateRight(val, amt) : (Integer.rotateRight((int) val, amt) & 0xFFFFFFFFL);
        }
        return sf ? r : r & 0xFFFFFFFFL;
    }

    private static long ones(int k)
    {
        return k >= 64 ? -1L : (1L << k) - 1;
    }

    private static long ror(long val, int r, int width)
    {
        if (r == 0)
        {
            return val;
        }
        long m = ones(width);
        val &= m;
        return ((val >>> r) | (val << (width - r))) & m;
    }

    private static long replicate(long e, int esize, int width)
    {
        long r = 0;
        for (int i = 0; i < width; i += esize)
        {
            r |= e << i;
        }
        return r;
    }

    /** DecodeBitMasks (ARM ARM shared/functions): {wmask, tmask}, or null if reserved. */
    private static long[] bitMasks(int nBit, int imms, int immr, boolean immediate, int width)
    {
        int combined = (nBit << 6) | (~imms & 0x3F);
        int len = 31 - Integer.numberOfLeadingZeros(combined);
        if (len < 1)
        {
            return null;
        }
        int levels = (int) ones(len);
        if (immediate && (imms & levels) == levels)
        {
            return null;
        }
        int s = imms & levels;
        int r = immr & levels;
        int diff = (s - r) & levels;
        int esize = 1 << len;
        long welem = ones(s + 1);
        long telem = ones(diff + 1);
        long wmask = replicate(ror(welem, r, esize), esize, width);
        long tmask = replicate(telem, esize, width);
        return new long[] { wmask & ones(width), tmask & ones(width) };
    }

    // ----- decode + execute ------------------------------------------------------------------------------------

    private long step(int w, long pc)
    {
        boolean sf = (w >>> 31) == 1;
        int rd = w & 31;
        int rn = (w >>> 5) & 31;
        int rm = (w >>> 16) & 31;
        long next = pc + 4;

        if ((w & 0x1F80_0000) == 0x1280_0000)                       // move wide (MOVN/MOVZ/MOVK)
        {
            int opc = (w >>> 29) & 3;
            int hw = (w >>> 21) & 3;
            long imm = ((w >>> 5) & 0xFFFFL) << (hw * 16);
            if (opc == 0)
            {
                setZ(rd, ~imm, sf);
            }
            else if (opc == 2)
            {
                setZ(rd, imm, sf);
            }
            else if (opc == 3)
            {
                long old = rz(rd, sf);
                setZ(rd, (old & ~(0xFFFFL << (hw * 16))) | imm, sf);
            }
            else
            {
                return bad(w);
            }
            return next;
        }
        if ((w & 0x1F00_0000) == 0x1100_0000)                       // add/sub immediate
        {
            boolean sub = ((w >>> 30) & 1) == 1;
            boolean s = ((w >>> 29) & 1) == 1;
            long imm = (w >>> 10) & 0xFFF;
            if (((w >>> 22) & 1) == 1)
            {
                imm <<= 12;
            }
            long a = rsp(rn, sf);
            long r = sub ? addFlags(a, ~imm, true, sf, s) : addFlags(a, imm, false, sf, s);
            if (s)
            {
                setZ(rd, r, sf);
            }
            else
            {
                setSp(rd, r, sf);
            }
            return next;
        }
        if ((w & 0x1F20_0000) == 0x0B00_0000)                       // add/sub shifted register
        {
            boolean sub = ((w >>> 30) & 1) == 1;
            boolean s = ((w >>> 29) & 1) == 1;
            long b = shift(rz(rm, sf), (w >>> 22) & 3, (w >>> 10) & 0x3F, sf);
            long a = rz(rn, sf);
            long r = sub ? addFlags(a, ~b, true, sf, s) : addFlags(a, b, false, sf, s);
            setZ(rd, r, sf);
            return next;
        }
        if ((w & 0x1F00_0000) == 0x0A00_0000)                       // logical shifted register
        {
            int opc = (w >>> 29) & 3;
            long b = shift(rz(rm, sf), (w >>> 22) & 3, (w >>> 10) & 0x3F, sf);
            if (((w >>> 21) & 1) == 1)
            {
                b = ~b;
            }
            return logical(opc, rz(rn, sf), b, rd, sf, false, next);
        }
        if ((w & 0x1F80_0000) == 0x1200_0000)                       // logical immediate
        {
            int opc = (w >>> 29) & 3;
            long[] m = bitMasks((w >>> 22) & 1, (w >>> 10) & 0x3F, (w >>> 16) & 0x3F, true, sf ? 64 : 32);
            if (m == null)
            {
                return bad(w);
            }
            return logical(opc, rz(rn, sf), m[0], rd, sf, opc != 3, next);
        }
        if ((w & 0x1F80_0000) == 0x1300_0000)                       // bitfield (SBFM/BFM/UBFM)
        {
            return bitfield(w, rd, rn, sf, next);
        }
        if ((w & 0x7FE0_0000) == 0x1AC0_0000)                       // data-processing (2 source)
        {
            int opc = (w >>> 10) & 0x3F;
            long a = rz(rn, sf);
            long b = rz(rm, sf);
            long r;
            if (opc == 3)
            {
                if (b == 0)
                {
                    r = 0;
                }
                else if (sf)
                {
                    r = (a == Long.MIN_VALUE && b == -1) ? a : a / b;
                }
                else
                {
                    int ia = (int) a;
                    int ib = (int) b;
                    r = (ia == Integer.MIN_VALUE && ib == -1) ? ia : ia / ib;
                }
            }
            else if (opc == 2)
            {
                r = b == 0 ? 0 : (sf ? Long.divideUnsigned(a, b) : Long.divideUnsigned(a, b));
            }
            else if (opc >= 8 && opc <= 11)
            {
                r = shift(a, opc - 8, (int) (b & (sf ? 63 : 31)), sf);
            }
            else
            {
                return bad(w);
            }
            setZ(rd, r, sf);
            return next;
        }
        if ((w & 0x1F00_0000) == 0x1B00_0000)                       // data-processing (3 source)
        {
            int op31 = (w >>> 21) & 7;
            boolean o0 = ((w >>> 15) & 1) == 1;
            int ra = (w >>> 10) & 31;
            if (op31 != 0)
            {
                return bad(w);
            }
            long p = rz(rn, sf) * rz(rm, sf);
            setZ(rd, o0 ? rz(ra, sf) - p : rz(ra, sf) + p, sf);
            return next;
        }
        if ((w & 0x1FE0_0000) == 0x1A80_0000)                       // conditional select
        {
            boolean op = ((w >>> 30) & 1) == 1;
            boolean o2 = ((w >>> 10) & 1) == 1;
            int cc = (w >>> 12) & 15;
            long r;
            if (cond(cc))
            {
                r = rz(rn, sf);
            }
            else
            {
                long b = rz(rm, sf);
                r = !op ? (o2 ? b + 1 : b) : (o2 ? -b : ~b);
            }
            setZ(rd, r, sf);
            return next;
        }
        if ((w & 0xFC00_0000) == 0x9400_0000 && calls != null)     // BL: the hook is the callee
        {
            x[30] = next;
            calls.call(this, (int) ((pc - codeBase) >> 2));
            return next;
        }
        if ((w & 0xFC00_0000) == 0x1400_0000)                       // B
        {
            return pc + (((long) (w << 6)) >> 4);
        }
        if ((w & 0xFF00_0010) == 0x5400_0000)                       // B.cond
        {
            return cond(w & 15) ? pc + (((long) (w << 8) >> 13) << 2) : next;
        }
        if ((w & 0x7E00_0000) == 0x3400_0000)                       // CBZ/CBNZ
        {
            boolean nz = ((w >>> 24) & 1) == 1;
            boolean zero = rz(rd, sf) == 0;
            return zero != nz ? pc + (((long) (w << 8) >> 13) << 2) : next;
        }
        if ((w & 0x7E00_0000) == 0x3600_0000)                       // TBZ/TBNZ
        {
            int bit = ((w >>> 31) << 5) | ((w >>> 19) & 31);
            boolean nz = ((w >>> 24) & 1) == 1;
            boolean set = ((x[rd] >>> bit) & 1) == 1 && rd != 31;
            return set == nz ? pc + (((long) (w << 13) >> 18) << 2) : next;
        }
        if ((w & 0xFFFF_FC1F) == 0xD63F_0000 && calls != null)     // BLR: the hook is the callee
        {
            lastTarget = x[rn];
            x[30] = next;
            calls.call(this, (int) ((pc - codeBase) >> 2));
            return next;
        }
        if ((w & 0xFFFF_FC1F) == 0xD65F_0000)                       // RET
        {
            return x[rn];
        }
        if ((w & 0xFFFF_FC1F) == 0xD61F_0000)                       // BR
        {
            return x[rn];
        }
        if ((w & 0xFFFF_F01F) == 0xD503_301F || (w & 0xFFFF_F01F) == 0xD503_201F)   // barriers, hints
        {
            return next;
        }
        if ((w & 0x9F00_0000) == 0x1000_0000)                       // ADR
        {
            long imm = ((long) (((w >>> 5) & 0x7FFFF) << 2 | ((w >>> 29) & 3)) << 43) >> 43;
            setZ(rd, pc + imm, true);
            return next;
        }
        if ((w & 0x3F00_0000) == 0x3900_0000)                       // load/store, unsigned immediate (V=0)
        {
            int size = (w >>> 30) & 3;
            int opc = (w >>> 22) & 3;
            long a = x[rn] + (((w >>> 10) & 0xFFFL) << size);
            return loadStore(size, opc, a, rd, next);
        }
        if ((w & 0x3F20_0C00) == 0x3820_0800)                       // load/store, register offset (V=0)
        {
            int size = (w >>> 30) & 3;
            int opc = (w >>> 22) & 3;
            int option = (w >>> 13) & 7;
            boolean scaled = ((w >>> 12) & 1) == 1;
            long off = x[rm];
            if (rm == 31)
            {
                off = 0;
            }
            if (option == 2)
            {
                off &= 0xFFFFFFFFL;
            }
            else if (option == 6)
            {
                off = (int) off;
            }
            long a = x[rn] + (scaled ? off << size : off);
            return loadStore(size, opc, a, rd, next);
        }
        if ((w & 0x3E00_0000) == 0x2800_0000)                       // LDP/STP (integer forms)
        {
            return pair(w, rd, rn, next);
        }
        return bad(w);
    }

    private long logical(int opc, long a, long b, int rd, boolean sf, boolean toSp, long next)
    {
        long r = opc == 0 || opc == 3 ? a & b : (opc == 1 ? a | b : a ^ b);
        if (!sf)
        {
            r &= 0xFFFFFFFFL;
        }
        if (opc == 3)
        {
            n = sf ? r < 0 : (int) r < 0;
            z = r == 0;
            c = false;
            v = false;
        }
        if (toSp)
        {
            setSp(rd, r, sf);
        }
        else
        {
            setZ(rd, r, sf);
        }
        return next;
    }

    /** SBFM/BFM/UBFM (ARM ARM C6.2: the bitfield-move pseudocode). */
    private long bitfield(int w, int rd, int rn, boolean sf, long next)
    {
        int opc = (w >>> 29) & 3;
        int width = sf ? 64 : 32;
        int immr = (w >>> 16) & 0x3F;
        int imms = (w >>> 10) & 0x3F;
        long[] m = bitMasks((w >>> 22) & 1, imms, immr, false, width);
        if (m == null || opc == 3)
        {
            return bad(w);
        }
        long src = rz(rn, sf);
        long dst = opc == 1 ? rz(rd, sf) : 0;
        long bot = (dst & ~m[0]) | (ror(src, immr, width) & m[0]);
        long top;
        if (opc == 0)
        {
            top = ((src >>> imms) & 1) == 1 ? ones(width) : 0;
        }
        else
        {
            top = dst;
        }
        long r = (top & ~m[1]) | (bot & m[1]);
        setZ(rd, r & ones(width), sf);
        return next;
    }

    private long loadStore(int size, int opc, long a, int rt, long next)
    {
        int bytes = 1 << size;
        if (opc == 0)
        {
            store(a, bytes, rt == 31 ? 0 : x[rt]);
        }
        else if (opc == 1)
        {
            setZ(rt, load(a, bytes), true);
        }
        else
        {
            long raw = load(a, bytes);
            int bits = bytes * 8;
            long sx = (raw << (64 - bits)) >> (64 - bits);
            if (opc == 3 && size != 3)
            {
                setZ(rt, sx & 0xFFFFFFFFL, true);                 // sign-extend to 32 bits
            }
            else if (opc == 2 && size != 3)
            {
                setZ(rt, sx, true);
            }
            else
            {
                return bad(0);
            }
        }
        return next;
    }

    private long pair(int w, int rt, int rn, long next)
    {
        int opc = (w >>> 30) & 3;
        if (opc != 2)
        {
            return bad(w);
        }
        int kind = (w >>> 23) & 3;                                  // 1 post-index, 2 offset, 3 pre-index
        boolean load = ((w >>> 22) & 1) == 1;
        long imm = ((long) (((w >>> 15) & 0x7F) << 25) >> 25) << 3;
        int rt2 = (w >>> 10) & 31;
        long a = x[rn] + (kind == 1 ? 0 : imm);
        if (load)
        {
            setZ(rt, load(a, 8), true);
            setZ(rt2, load(a + 8, 8), true);
        }
        else
        {
            store(a, 8, rt == 31 ? 0 : x[rt]);
            store(a + 8, 8, rt2 == 31 ? 0 : x[rt2]);
        }
        if (kind == 1 || kind == 3)
        {
            x[rn] = x[rn] + imm;
        }
        return next;
    }

    private long bad(int w)
    {
        unsupported = String.format("unsupported encoding 0x%08X", w);
        return 0;
    }
}
