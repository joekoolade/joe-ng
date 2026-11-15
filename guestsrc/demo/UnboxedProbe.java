/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-15
 */
package demo;

import magic.Address;
import magic.Extent;
import magic.Magic;
import magic.Offset;
import magic.Word;

/**
 * The unboxed types on the METAL JIT (MetalSymbols -> Loader.unboxedOp -> compiler.Unboxed), where guest code is
 * compiled: every kind of operation, each checked against the same computation done with longs and Magic. Memory
 * operations run on a long[] (its elements start 24 bytes in). The host's compiler.UnboxedTest covers the seed
 * writer; this covers the other world.
 */
public class UnboxedProbe
{
    private static int failures;

    private static int checks;

    public static void main(String[] args)
    {
        long[] mem = new long[8];
        long base = Magic.addrOf(mem) + 24L;
        Address a = Address.fromObject(mem).plus(24);
        check("fromObject + plus(int)", a.toLong() == base);
        check("plus(long)", a.plus(16L).toLong() == base + 16L);
        check("minus", a.plus(40).minus(8).toLong() == base + 32L);
        check("diff", a.plus(40).diff(a).toLong() == 40L);
        check("plus(Offset)", a.plus(Offset.fromLong(8L)).toLong() == base + 8L);
        check("plus(Extent)", a.plus(Extent.fromIntZeroExtend(16)).toLong() == base + 16L);
        check("zero/isZero", Address.zero().isZero() && !a.isZero());
        check("max/isMax", Address.max().isMax() && Address.max().toLong() == -1L);
        check("fromIntZeroExtend", Address.fromIntZeroExtend(-1).toLong() == 0xFFFF_FFFFL);
        check("fromIntSignExtend", Address.fromIntSignExtend(-1).toLong() == -1L);
        check("toInt", Address.fromLong(0x1_8000_0001L).toInt() == 0x8000_0001);

        a.store(0x1122_3344_5566_7788L);
        check("store/loadLong", a.loadLong() == 0x1122_3344_5566_7788L && mem[0] == 0x1122_3344_5566_7788L);
        check("loadInt sign-extends", a.loadInt() == 0x5566_7788 && a.plus(4).loadInt() == 0x1122_3344);
        a.plus(8).store(-2);
        check("store(int)/loadInt", a.plus(8).loadInt() == -2 && Magic.load32(base + 8L) == -2);
        check("loadUnsignedByte", a.plus(8).loadUnsignedByte() == 0xFE);
        check("loadByte", a.plus(8).loadByte() == (byte) -2);
        a.plus(16).store((short) -3);
        check("store(short)/loadShort", a.plus(16).loadShort() == -3);
        check("loadChar", a.plus(16).loadChar() == (char) 0xFFFD);
        a.plus(16).store('Z');
        check("store(char)", a.plus(16).loadChar() == 'Z');
        a.plus(24).store((byte) 0x7F);
        check("store(byte)", Magic.load8(base + 24L) == 0x7F);
        a.plus(25).store(true);
        check("store(boolean)", a.plus(25).loadUnsignedByte() == 1);

        Offset o = Offset.fromIntSignExtend(32);
        a.store(Word.fromLong(0xABCDL), o);
        check("store(Word, Offset)", mem[4] == 0xABCDL && a.loadWord(o).toLong() == 0xABCDL);
        a.store(a, o.plus(8));
        check("store(Address, Offset)/loadAddress", a.loadAddress(o.plus(8)).EQ(a));
        a.store(-1, o.plus(16));
        check("store(int, Offset)/loadInt(Offset)", a.loadInt(o.plus(16)) == -1);
        a.store((short) 0x1234, o.plus(20));
        check("store(short, Offset)/loadShort(Offset)", a.loadShort(o.plus(20)) == 0x1234);
        a.store((byte) -5, o.plus(22));
        check("store(byte, Offset)/loadByte(Offset)", a.loadByte(o.plus(22)) == -5
                && a.loadUnsignedByte(o.plus(22)) == 0xFB);
        a.store('q', o.plus(20));
        check("store(char, Offset)/loadChar(Offset)", a.loadChar(o.plus(20)) == 'q');
        a.store(7L, o);
        check("store(long, Offset)/loadLong(Offset)", a.loadLong(o) == 7L);

        Address slot = a.plus(48);
        slot.store(5L);
        check("attempt(long) wins", slot.attempt(5L, 6L) && slot.loadLong() == 6L);
        check("attempt(long) loses", !slot.attempt(5L, 9L) && slot.loadLong() == 6L);
        check("attempt(Word)", slot.attempt(Word.fromLong(6L), Word.fromLong(10L)) && slot.loadLong() == 10L);
        check("attempt(Address)", slot.attempt(Address.fromLong(10L), a) && slot.loadAddress().EQ(a));

        Address lo = Address.fromLong(0x1000L);
        Address hi = Address.fromLong(-0x1000L);           // unsigned: far above lo
        check("unsigned LT/GT", lo.LT(hi) && hi.GT(lo) && !hi.LT(lo));
        check("unsigned LE/GE", lo.LE(lo) && hi.GE(lo) && !lo.GE(hi));
        check("EQ/NE", lo.EQ(Address.fromLong(0x1000L)) && lo.NE(hi));
        Offset neg = Offset.fromLong(-8L);
        Offset pos = Offset.fromLong(8L);
        check("signed sLT/sGT", neg.sLT(pos) && pos.sGT(neg) && !pos.sLT(neg));
        check("signed sLE/sGE", neg.sLE(neg) && pos.sGE(neg));
        check("Offset.max", Offset.max().toLong() == Long.MAX_VALUE && Offset.max().isMax());
        check("Offset zero/minus", Offset.zero().isZero() && pos.minus(pos).isZero() && pos.minus(16).EQ(neg));

        Word w = Word.fromLong(0xF0F0L);
        check("and/or/xor", w.and(Word.fromLong(0xFFL)).toLong() == 0xF0L
                && w.or(Word.one()).toLong() == 0xF0F1L && w.xor(w).isZero());
        check("not", Word.zero().not().isMax() && w.not().toLong() == ~0xF0F0L);
        check("lsh/rshl/rsha", Word.one().lsh(63).toLong() == Long.MIN_VALUE
                && Word.max().rshl(60).toLong() == 0xFL && Word.max().rsha(60).toLong() == -1L);
        check("Word plus/minus", Word.one().plus(Word.one()).toLong() == 2L && Word.zero().minus(Word.one()).isMax());
        check("Word conversions", w.toAddress().toWord().toOffset().toWord().toExtent().toWord().EQ(w));
        check("Extent", Extent.fromLong(8L).plus(8).plus(Extent.one()).toLong() == 17L
                && Extent.zero().LT(Extent.one()) && Extent.max().GT(Extent.zero()));
        check("toObject", a.minus(24).toObject() == mem);

        System.out.println("UnboxedProbe " + checks + " checks, " + failures + " failures");
        System.out.println(failures == 0 ? "UnboxedProbe PASS" : "UnboxedProbe FAIL");
    }

    private static void check(String what, boolean ok)
    {
        checks = checks + 1;
        if (!ok)
        {
            failures = failures + 1;
            System.out.println("FAIL " + what);
        }
    }
}
