package vm;

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

    /** Append {@code v} in decimal to the builder. */
    static void scInt(long sb, int v)
    {
        if (v == 0)
        {
            scChar(sb, 0x30);
            return;
        }
        if (v < 0)
        {
            scChar(sb, 0x2D);                              // '-' (Integer.MIN_VALUE not special-cased)
            v = -v;
        }
        byte[] tmp = new byte[12];
        int n = 0;
        while (v > 0)
        {
            tmp[n] = (byte) (0x30 + v % 10);
            n = n + 1;
            v = v / 10;
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

    /** Append a String/byte[] {@code ref}'s bytes to the concat builder. */
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
        long arr = strBytes(ref);
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
        if (v < 0L)
        {
            scChar(sb, 0x2D);                              // '-' (Long.MIN_VALUE not special-cased)
            v = -v;
        }
        byte[] tmp = new byte[24];
        int n = 0;
        while (v > 0L)
        {
            tmp[n] = (byte) (0x30 + (int) (v % 10L));
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
