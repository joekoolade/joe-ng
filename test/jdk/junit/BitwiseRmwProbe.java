/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-17
 */
import jdk.internal.misc.Unsafe;

/**
 * {@code Unsafe.getAndBitwise{Or,And,Xor}{Int,Long}} and their Acquire/Release variants -- all eighteen.
 *
 * <p>WHY THIS EXISTS: the overlay shipped {@code getAndBitwiseOrLong} and not its Int sibling, so the Int
 * form CEASED TO EXIST -- an overlay wins the name, so a member it omits resolves nowhere and surfaces as a
 * {@code DENYLIST TRAP} blaming a list this class is not on. The caller is stock
 * {@code ForkJoinTask.setDone}, reached through {@code BigDecimal.<clinit>}; only
 * {@code make overlaycheck-deep} sees it, because that caller is stock java.base rather than anything we ship.
 *
 * <p>THE WHOLE FAMILY IS COVERED, because half a family is how this trap has cost eleven sessions. Adding
 * the eighteen was blocked for an arc by a loader defect with nothing to do with these methods -- a `<init>`
 * got no phase-A cell, so the tier every constructor short-circuits into returned 0 -- and these members
 * were only ever the perturbation that moved batch composition.
 *
 * <p>THE ARMS ARE CHOSEN SO A PLAUSIBLE WRONG IMPLEMENTATION FAILS, not so a working one passes:
 * <ul>
 *   <li><b>Every arm prints the RETURN VALUE and the FIELD.</b> These are {@code getAnd*}: the return is the
 *       value BEFORE the update. An implementation returning the new value is correct in the field and wrong
 *       in the result, and an arm checking only the field cannot tell.</li>
 *   <li><b>Or/And/Xor are given a start and mask where all three answers differ</b> ({@code 0b1100} with
 *       {@code 0b1010} gives 14 / 8 / 6). A shared start of 0, or a mask of 0, makes two of the three agree
 *       and tests nothing about which operator ran. This is what catches the copy-paste slip these eighteen
 *       near-identical bodies invite -- an {@code Xor} form whose loop was pasted with {@code |}.</li>
 *   <li><b>Acquire and Release are given the SAME treatment as plain, not a smoke test.</b> They delegate,
 *       so the failure they can have is delegating to the WRONG SIBLING -- {@code XorIntAcquire} calling
 *       {@code getAndBitwiseOrInt} is one character and passes any arm that only checks non-zero.</li>
 *   <li><b>The long arms use a mask above 2^32</b> ({@code 3L << 40} against {@code 1L << 40}), so a long
 *       path that truncated to 32 bits is visible -- and the three operators differ in BOTH halves.</li>
 *   <li><b>The sign-bit arm does a SECOND op on the same field</b>, and that is the joe-ng-specific one. An
 *       int lives in an 8-byte slot kept sign-extended (Baseline.canonInt); if a store left the slot
 *       non-canonical the value still reads back correctly through one {@code (int)} cast, and the NEXT
 *       CAS's expected word no longer matches what is stored -- so the retry loop spins for ever. That
 *       failure is a HANG, not a wrong number, which is exactly why one op is not enough to find it.</li>
 * </ul>
 *
 * <p>THE EXPECTED VALUE IS COMPUTED WITH JAVA'S OWN OPERATOR, not transcribed. {@code want} for the OR arm
 * is {@code START | MASK} evaluated by the compiler, which is an oracle independent of this overlay -- a
 * transcribed constant can be copied wrong as easily as the body it checks. Run the HOST CONTROL and diff
 * anyway; it needs {@code --add-exports java.base/jdk.internal.misc=ALL-UNNAMED}.
 */
public class BitwiseRmwProbe
{
    // Instance FIELDS, deliberately: these methods take an 8-byte field slot here. An int ARRAY element is
    // 4 bytes (ARRAY_INT_INDEX_SCALE), and joe-ng has no 32-bit CAS to address one with -- see the note on
    // the read-modify-write family in the Unsafe overlay.
    int i;
    long l;

    // All three operators give a different answer from these, and all three differ from the start.
    static final int IS = 12;          // 0b1100
    static final int IM = 10;          // 0b1010  -> or 14, and 8, xor 6
    static final long LS = 12L | (3L << 40);
    static final long LM = 10L | (1L << 40);   // -> the operators differ in BOTH halves

    static Unsafe u;
    static long io;
    static long lo;
    static BitwiseRmwProbe p;

    static void ai(String name, int ret, int want, int wantField)
    {
        String ok = (ret == want && p.i == wantField) ? "OK" : "BAD";
        System.out.println(name + " ret=" + ret + " field=" + p.i
                + " (want ret=" + want + " field=" + wantField + ") " + ok);
    }

    static void al(String name, long ret, long want, long wantField)
    {
        String ok = (ret == want && p.l == wantField) ? "OK" : "BAD";
        System.out.println(name + " ret=" + ret + " field=" + p.l
                + " (want ret=" + want + " field=" + wantField + ") " + ok);
    }

    public static void main(String[] args) throws Exception
    {
        u = Unsafe.getUnsafe();
        io = u.objectFieldOffset(BitwiseRmwProbe.class.getDeclaredField("i"));
        lo = u.objectFieldOffset(BitwiseRmwProbe.class.getDeclaredField("l"));
        p = new BitwiseRmwProbe();

        // ---- int: three operators x three memory modes. The return is always the OLD value (IS).
        p.i = IS;
        ai("orInt        ", u.getAndBitwiseOrInt(p, io, IM), IS, IS | IM);
        p.i = IS;
        ai("orIntAcq     ", u.getAndBitwiseOrIntAcquire(p, io, IM), IS, IS | IM);
        p.i = IS;
        ai("orIntRel     ", u.getAndBitwiseOrIntRelease(p, io, IM), IS, IS | IM);

        p.i = IS;
        ai("andInt       ", u.getAndBitwiseAndInt(p, io, IM), IS, IS & IM);
        p.i = IS;
        ai("andIntAcq    ", u.getAndBitwiseAndIntAcquire(p, io, IM), IS, IS & IM);
        p.i = IS;
        ai("andIntRel    ", u.getAndBitwiseAndIntRelease(p, io, IM), IS, IS & IM);

        p.i = IS;
        ai("xorInt       ", u.getAndBitwiseXorInt(p, io, IM), IS, IS ^ IM);
        p.i = IS;
        ai("xorIntAcq    ", u.getAndBitwiseXorIntAcquire(p, io, IM), IS, IS ^ IM);
        p.i = IS;
        ai("xorIntRel    ", u.getAndBitwiseXorIntRelease(p, io, IM), IS, IS ^ IM);

        // ---- long: the mask reaches above 2^32, so a truncating path is visible.
        p.l = LS;
        al("orLong       ", u.getAndBitwiseOrLong(p, lo, LM), LS, LS | LM);
        p.l = LS;
        al("orLongAcq    ", u.getAndBitwiseOrLongAcquire(p, lo, LM), LS, LS | LM);
        p.l = LS;
        al("orLongRel    ", u.getAndBitwiseOrLongRelease(p, lo, LM), LS, LS | LM);

        p.l = LS;
        al("andLong      ", u.getAndBitwiseAndLong(p, lo, LM), LS, LS & LM);
        p.l = LS;
        al("andLongAcq   ", u.getAndBitwiseAndLongAcquire(p, lo, LM), LS, LS & LM);
        p.l = LS;
        al("andLongRel   ", u.getAndBitwiseAndLongRelease(p, lo, LM), LS, LS & LM);

        p.l = LS;
        al("xorLong      ", u.getAndBitwiseXorLong(p, lo, LM), LS, LS ^ LM);
        p.l = LS;
        al("xorLongAcq   ", u.getAndBitwiseXorLongAcquire(p, lo, LM), LS, LS ^ LM);
        p.l = LS;
        al("xorLongRel   ", u.getAndBitwiseXorLongRelease(p, lo, LM), LS, LS ^ LM);

        // ---- already-set bits must not double-count, and the return is still the OLD value.
        p.i = 14;
        ai("orIdem       ", u.getAndBitwiseOrInt(p, io, 10), 14, 14);

        // ---- THE SIGN-EXTENSION ARM. The SECOND op is the test; the first only sets it up. An int lives in
        // an 8-byte slot kept sign-extended (Baseline.canonInt); a store that left the slot non-canonical
        // still reads back correctly through one (int) cast, and the next CAS's expected word no longer
        // matches what is stored -- so the retry loop spins for ever. A HANG, not a wrong number, which is
        // exactly why one op cannot find it.
        p.i = 1;
        ai("signOr       ", u.getAndBitwiseOrInt(p, io, 0x80000000), 1, 1 | 0x80000000);
        ai("signOr2      ", u.getAndBitwiseOrInt(p, io, 0x00000002), 1 | 0x80000000, 1 | 0x80000000 | 2);

        // The same, through AND and XOR: each clears or flips the sign bit, so a non-canonical slot left by
        // ANY of the three operators spins here rather than only through OR.
        p.i = -1;
        ai("signAnd      ", u.getAndBitwiseAndInt(p, io, 0x7FFFFFFF), -1, 0x7FFFFFFF);
        ai("signAnd2     ", u.getAndBitwiseAndInt(p, io, 0x0000000F), 0x7FFFFFFF, 0xF);
        p.i = 1;
        ai("signXor      ", u.getAndBitwiseXorInt(p, io, 0x80000000), 1, 1 ^ 0x80000000);
        ai("signXor2     ", u.getAndBitwiseXorInt(p, io, 0x80000000), 1 ^ 0x80000000, 1);

        // ---- the shape the blocker actually has: ForkJoinTask.setDone ORs a status bit and reads the old
        // value. The bit is printed as an INT, not a boolean: joe-ng renders a boolean concat argument as
        // 1/0 rather than true/false (Baseline.appendArg routes 'Z' to SC_INT), which would diverge from the
        // host control for a reason that has nothing to do with this method. Separate bug, separate fix.
        p.i = 0;
        int prev = u.getAndBitwiseOrInt(p, io, 0x40000000);
        System.out.println("setDone prev=" + prev + " bit=" + (p.i & 0x40000000) + " (want prev=0 bit=1073741824)");

        System.out.println("BitwiseRmwProbe done");
    }
}
