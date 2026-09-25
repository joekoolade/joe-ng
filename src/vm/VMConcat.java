/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-16
 */
package vm;

import board.bcm2711.Uart;
import magic.Magic;
import objectmodel.ObjectModel;
import static vm.VM.*;   // strBytes (the string-ref -> byte[] accessor stays in VM, shared with the natives)

/**
 * The {@code invokedynamic} string-concat runtime, extracted from VM.java: a growable {@code byte[]} builder
 * driven by the JIT's {@code StringConcatFactory} lowering ({@code Baseline.lowerConcat} -> {@code MetalSymbols}
 * -> the {@code VM.sc*Addr} helper addresses). {@code scStart} opens a builder; {@code scChar}/{@code scInt}/
 * {@code scLong}/{@code scStr} append; {@code scEnd} finishes it into a real typed {@code byte[]} (tagged with the
 * batch's array TIB in {@code byteArrayTibCache} so stock code can checkcast/clone {@code String.value}). Reached
 * only via the stashed {@code sc*Addr} statics (which stay in VM); grouped here to shrink VM.java.
 */
final class VMConcat
{
    /** Begin a concat: a fresh builder over a 64-byte byte[]. */
    static long scStart()
    {
        long buf = Heap.allocArray(64, 1);
        long sb = Heap.alloc(32);
        Magic.store64(sb + 16L, buf);
        Magic.store64(sb + 24L, 0L);
        return sb;
    }

    /** Grow a builder's backing byte[] to twice {@code cap}, copying {@code count} bytes; returns the new buf. */
    static long scGrow(long sb, long buf, long count, long cap)
    {
        long nbuf = Heap.allocArray((int) (cap * 2L), 1);
        long i = 0L;
        while (i < count)
        {
            Magic.store8(nbuf + 24L + i, (byte) Magic.load8(buf + 24L + i));
            i = i + 1L;
        }
        Magic.store64(sb + 16L, nbuf);
        return nbuf;
    }

    /**
     * Append {@code v} as the WORD {@code "true"} or {@code "false"} -- NOT as 1/0.
     *
     * <p>JLS 15.18.1 defines string concatenation of a boolean through {@code String.valueOf(boolean)},
     * which is the word. joe-ng routed a {@code 'Z'} concat argument to {@link #scInt} for the life of the
     * project, so {@code "flag=" + true} rendered {@code flag=1}: a SILENTLY WRONG ANSWER in a core language
     * feature, and the failure mode this project pays for most. It survived because
     * {@code StringBuilder.append(boolean)} is a DIFFERENT path and is correct -- so the suite's own
     * {@code count=42 ok=true} arm, which goes through the builder, never had a chance to see it.
     *
     * <p>A verified classfile only ever holds 0 or 1 in a boolean, so {@code v != 0} is the whole test.
     */
    static void scBool(long sb, int v)
    {
        if (v != 0)
        {
            scChar(sb, 0x74);                              // 't'
            scChar(sb, 0x72);                              // 'r'
            scChar(sb, 0x75);                              // 'u'
            scChar(sb, 0x65);                              // 'e'
            return;
        }
        scChar(sb, 0x66);                                  // 'f'
        scChar(sb, 0x61);                                  // 'a'
        scChar(sb, 0x6C);                                  // 'l'
        scChar(sb, 0x73);                                  // 's'
        scChar(sb, 0x65);                                  // 'e'
    }

    /**
     * Append a double, and a float, exactly as {@code String.valueOf} would (JLS 15.18.1).
     *
     * <p>THE ARGUMENT IS THE RAW BITS, not a {@code double}, and that is what leaves the lowering alone:
     * this VM holds a double in an ordinary 64-bit operand register and only {@code fmov}s it into an FP
     * register to do arithmetic, so {@code appendArg}'s existing integer {@code movReg} path carries it and
     * a compiled {@code Double.toString} receives the bits in x0 the same way.
     *
     * <p>THE JDK'S OWN FORMATTER DOES THE WORK, reached BY NAME THROUGH THE LOADER rather than by a static
     * call. {@code Double.toString} is specified as the SHORTEST decimal that round-trips (Schubfach in
     * JDK 19+), which is not something to re-derive by hand in a core language feature -- and a static call
     * from here would drag {@code jdk/internal/math/DoubleToDecimal} into the BAKE domain, which cannot
     * carry it (its {@code <clinit>} uses an {@code ldc} class literal the host writer refuses, and its
     * {@code special} method does not resolve). Demand-loaded it works: measured byte-identical to a host
     * JVM on 1.5, 0.1, 0.0, -0.0, 1e20, 1e-9 and NaN before any of this was written.
     *
     * <p>FLOAT IS NOT A WIDENED DOUBLE, and a host control is what said so: {@code Float.toString(0.1f)} is
     * {@code "0.1"} while {@code Double.toString((double) 0.1f)} is {@code "0.10000000149011612"} --
     * shortest-round-trip is relative to the type's OWN precision. Widening would have been wrong in every
     * float concat, and wrong in a way that still looks like a number.
     */
    static void scDouble(long sb, long bits)
    {
        long buf = Loader.doubleToStringBuf();
        if (buf == 0L)
        {
            appendNoFormatter(sb, 'D');
            return;
        }
        // THE NESTED FORM IS DELIBERATE, and it is this file's regression test for a COMPILER fix. `sb` is
        // live ON THE OPERAND STACK across the call, and the operand stack is x9.. (caller-saved), so the
        // BLR used to destroy it: every arm printed an EMPTY string -- no null, no trap, no fault -- while
        // the formatter returned the right bytes all along. The workaround was to hoist the result into a
        // local; the fix is that Baseline now spills the operand stack around Magic.call0/call2/callN/gc
        // exactly as it always has around an ordinary call. Written back nested ON PURPOSE: with a local
        // here, nothing in the demo suite would exercise a live operand across an intrinsic call, and a
        // gate that cannot fail is not a gate. Revert the spill and ConcatDemo's eleven arms go empty.
        scStr(sb, Magic.call2(buf, bits, 0L));         // (D)->String takes ONE arg; x1 is ignored by it
    }

    /** The float half of {@link #scDouble} -- its own shortest string, never the widened double's. */
    static void scFloat(long sb, int bits)
    {
        long buf = Loader.floatToStringBuf();
        if (buf == 0L)
        {
            appendNoFormatter(sb, 'F');
            return;
        }
        scStr(sb, Magic.call2(buf, bits & 0xFFFFFFFFL, 0L));  // nested, for scDouble's reason
    }

    /**
     * The formatter could not be resolved: say so IN THE STRING, and loudly once.
     *
     * <p>Deliberately not a plausible number and not an empty append. This concat was a HARD compile-time
     * failure before ({@code JIT unsupported reason=0 a=0xBA b=2}), so a marker is not a regression -- and
     * a stub that answers something numeric-looking is indistinguishable from a working one, which is the
     * shape this project pays for most.
     */
    private static void appendNoFormatter(long sb, int kind)
    {
        if (!noFmtSaid)
        {
            noFmtSaid = true;
            Uart.write(Magic.bytes("\n  NO FLOAT FORMATTER: java/lang/"));
            Uart.write(kind == 'D' ? Magic.bytes("Double") : Magic.bytes("Float"));
            Uart.write(Magic.bytes(".toString would not resolve -- concat prints a marker, not a number\n"));
        }
        scChar(sb, 0x3C);                              // '<'
        scChar(sb, kind == 'D' ? 0x64 : 0x66);         // 'd' / 'f'
        scChar(sb, 0x3F);                              // '?'
        scChar(sb, 0x3E);                              // '>'
    }

    private static boolean noFmtSaid;

    /** Append one byte {@code c} to the builder. */
    static void scChar(long sb, int c)
    {
        long buf = Magic.load64(sb + 16L);
        long count = Magic.load64(sb + 24L);
        long cap = Magic.load64(buf + 16L);                // byte[] length (ARRAY_LENGTH_OFFSET)
        if (count >= cap)
        {
            buf = scGrow(sb, buf, count, cap);
        }
        Magic.store8(buf + 24L + count, (byte) c);         // ARRAY_BASE_OFFSET = 24
        Magic.store64(sb + 24L, count + 1L);
    }

    /**
     * Append {@code v} in decimal to the builder.
     *
     * <p>THE DIGITS ARE ACCUMULATED IN A LONG, because {@code -Integer.MIN_VALUE} is still
     * {@code Integer.MIN_VALUE}: negating in int left the value NEGATIVE, the {@code v > 0} loop ran zero
     * times, and {@code Integer.MIN_VALUE} concatenated into a string as a bare {@code "-"} -- a silently
     * truncated number rather than a crash. The int range fits a long with room to negate, so widening once
     * removes the case instead of special-casing it.
     */
    static void scInt(long sb, int v)
    {
        if (v == 0)
        {
            scChar(sb, 0x30);
            return;
        }
        long m = v;
        if (m < 0L)
        {
            scChar(sb, 0x2D);                              // '-'
            m = -m;                                        // safe: |Integer.MIN_VALUE| fits a long
        }
        byte[] tmp = new byte[12];
        int n = 0;
        while (m > 0L)
        {
            tmp[n] = (byte) (0x30 + (int) (m % 10L));
            n = n + 1;
            m = m / 10L;
        }
        while (n > 0)
        {
            n = n - 1;
            scChar(sb, tmp[n]);
        }
    }

    /** Finish a concat: a fresh byte[] trimmed to the builder's length (typed with VM.byteArrayTibCache). */
    static long scEnd(long sb)
    {
        long buf = Magic.load64(sb + 16L);
        long count = Magic.load64(sb + 24L);
        long out = Heap.allocArray((int) count, 1);
        if (byteArrayTibCache != 0L)
        {
            Magic.store64(out, byteArrayTibCache);
        }
        long i = 0L;
        while (i < count)
        {
            Magic.store8(out + 24L + i, (byte) Magic.load8(buf + 24L + i));
            i = i + 1L;
        }
        return out;
    }

    /**
     * Append the concat argument {@code ref} -- a String, a byte[] carrier, or ANY other object, which
     * converts through its own {@code toString} (JLS 15.18.1: every reference argument is
     * {@code String.valueOf(obj)}).
     *
     * <p>THE OBJECT ARM IS THE FIX, and its absence was not cosmetic. {@code Baseline.appendArg} collapses
     * every reference argument to one helper, and this read whatever it was handed AS a String: a
     * non-String's first FIELD became a byte[] pointer. For {@code "x" + Integer.valueOf(5)} that field is
     * the int, so the load faulted at address 21 -- an NPE inside the VM's own concat helper, naming
     * nothing the program had done. Found by {@code BoxCacheProbe} walking into it while measuring
     * something else, on unmodified main, so it is nobody's regression.
     *
     * <p>WHAT ACTUALLY REACHES HERE AS A NON-STRING IS THE EIGHT BOXED WRAPPERS, and that is MEASURED with
     * {@code javap} rather than assumed: javac hands the indy a reference only for {@code String} and the
     * eight boxes, and emits {@code invokestatic String.valueOf(Object)} ITSELF for every other reference
     * type -- {@code Number}, {@code Object}, {@code CharSequence}, an array, a user class. So this gap
     * could only ever be reached by boxing, which is why it survived: joe-ng's own demos concatenate
     * primitives. It also means an arm built from a user class with its own {@code toString} passes on an
     * UNFIXED VM and discriminates nothing -- a first cut of ConcatDemo's block had five of them.
     *
     * <p>THE DISCRIMINATION IS DYNAMIC, NOT STATIC, and that is what keeps it correct AND cheap. Fixing it
     * in {@code appendArg} instead would key on the call site's DECLARED type, which is routinely
     * {@code Object} for a value that IS a String; {@link VM#isStringLike} reads the receiver's own TIB, so
     * a String appends its bytes directly however it was declared, only a genuine non-String pays a
     * dispatch, and bytecode from something other than javac is covered by the same arm. It also needs no
     * new helper id, no writer stash and no change to emitted code in either world.
     */
    static void scStr(long sb, long ref)
    {
        // A NULL REFERENCE CONCATENATES AS "null" (JLS 15.18.1), and getting this wrong was not merely
        // cosmetic: the old code ran strBytes(0) and then read `0 + 16`, i.e. ADDRESS 16 -- low firmware
        // memory on this board, which is readable. It happened to answer a zero length and print nothing;
        // any other value there would have appended that many bytes of garbage. Found by a probe printing
        // `q.peek()` on an empty queue, where stock prints "null" and this printed an empty string.
        if (ref == 0L)
        {
            appendNull(sb);
            return;
        }
        long str = ref;
        if (!isStringLike(ref))
        {
            str = Loader.objToString(ref);                 // the String.valueOf(Object) JLS 15.18.1 asks for
            if (str == -1L)
            {
                appendNoToString(sb);                      // could not RESOLVE it -- not the same as null
                return;
            }
            if (str == 0L)
            {
                appendNull(sb);                            // toString() RETURNED null: String.valueOf gives "null"
                return;
            }
        }
        long arr = strBytes(str);
        if (arr == 0L)
        {
            appendNull(sb);                                // a String with no value array: say so, never read +16
            return;
        }
        long len = Magic.load64(arr + 16L);
        long i = 0L;
        while (i < len)
        {
            scChar(sb, Magic.load8(arr + 24L + i));
            i = i + 1L;
        }
    }

    /**
     * {@code toString} could not be resolved on this receiver: say so IN THE STRING, and loudly once.
     *
     * <p>{@code appendNoFormatter}'s reasoning, for the same reason. The alternative sentinel is "null",
     * which is a perfectly ordinary answer a caller will believe -- so a broken dispatch would read as an
     * object that legitimately stringifies to nothing. A marker cannot be mistaken for either.
     */
    private static void appendNoToString(long sb)
    {
        if (!noStrSaid)
        {
            noStrSaid = true;
            Uart.write(Magic.bytes("\n  NO toString: a concat argument's toString()Ljava/lang/String; would"));
            Uart.write(Magic.bytes(" not resolve -- concat prints a marker, not a value\n"));
        }
        scChar(sb, 0x3C);                                  // '<'
        scChar(sb, 0x6F);                                  // 'o'
        scChar(sb, 0x3F);                                  // '?'
        scChar(sb, 0x3E);                                  // '>'
    }

    private static boolean noStrSaid;

    /** Append the four characters of "null" -- what a null reference converts to (JLS 15.18.1). */
    private static void appendNull(long sb)
    {
        scChar(sb, 0x6E);                                  // 'n'
        scChar(sb, 0x75);                                  // 'u'
        scChar(sb, 0x6C);                                  // 'l'
        scChar(sb, 0x6C);                                  // 'l'
    }

    /** Append {@code v} in decimal to the concat builder. */
    static void scLong(long sb, long v)
    {
        if (v == 0L)
        {
            scChar(sb, 0x30);
            return;
        }
        // THE DIGITS ARE TAKEN IN THE NEGATIVE DOMAIN, because there is no wider type to borrow and
        // {@code -Long.MIN_VALUE} is still {@code Long.MIN_VALUE}: negating left the value negative, the
        // {@code v > 0} loop ran zero times, and Long.MIN_VALUE concatenated as a bare "-". The negative
        // side of two's complement holds one more value than the positive side, so working there covers
        // every long. {@code v % 10} is non-positive, and negating just the DIGIT is always in range.
        if (v < 0L)
        {
            scChar(sb, 0x2D);                              // '-'
        }
        else
        {
            v = -v;
        }
        byte[] tmp = new byte[24];
        int n = 0;
        while (v < 0L)
        {
            tmp[n] = (byte) (0x30 - (int) (v % 10L));
            n = n + 1;
            v = v / 10L;
        }
        while (n > 0)
        {
            n = n - 1;
            scChar(sb, tmp[n]);
        }
    }
}
