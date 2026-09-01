package java.nio;

import magic.Magic;
import sun.nio.ch.DirectBuffer;

/**
 * A minimal, name-winning {@code java.nio.ByteBuffer} overlay for joe-ng, backing the socket dispatcher's
 * temporary buffers. Stock DirectByteBuffer allocates off-heap via {@code Unsafe.allocateMemory} and (in
 * JDK 26) pulls the {@code java.lang.foreign} scope machinery -- infeasible on metal. This wraps a plain
 * heap {@code byte[]} and exposes {@code address()} as the array payload address ({@code addrOf + 24}); the
 * socket {@code read0}/{@code write0} natives read/write that address directly, and {@code get}/{@code put}
 * are pure-Java array copies.
 *
 * <p>Beyond the socket path it also covers {@link #wrap(byte[])} plus absolute and relative accessors for
 * byte/short/int/long with a settable {@link ByteOrder} — the surface stock zip code and its tests use to
 * read and patch little-endian archive headers. Not implemented: slice/duplicate/compact, char/float/double
 * views, and read-only buffers.
 */
public class ByteBuffer implements DirectBuffer
{
    byte[] hb;      // backing heap array (its payload is what address() points at)
    int off;        // index in hb of this buffer's element 0 (0 unless wrapped with an offset)
    int pos;
    int lim;
    int cap;
    boolean big = true;                                  // ByteBuffer's documented default is BIG_ENDIAN

    public ByteBuffer(int capacity)
    {
        hb = new byte[capacity];
        cap = capacity;
        lim = capacity;
        pos = 0;
    }

    /**
     * Wraps an existing array, sharing it: writes through the buffer are visible in {@code array}. That
     * aliasing is the whole point for the callers here — a test builds a zip in a byte[], patches a header
     * field through a buffer view, and feeds the same array to ZipInputStream.
     */
    public static ByteBuffer wrap(byte[] array)
    {
        return wrap(array, 0, array.length);
    }

    public static ByteBuffer wrap(byte[] array, int offset, int length)
    {
        ByteBuffer b = new ByteBuffer(0);
        b.hb = array;
        b.off = offset;
        b.pos = 0;
        b.lim = length;
        b.cap = length;
        return b;
    }

    /**
     * Identical to {@link #allocate}: joe-ng has no off-heap region, and a "direct" buffer's only guarantee a
     * caller can observe here is that it works. {@code isDirect()} answers false accordingly rather than
     * claiming otherwise -- a caller that branches on it gets the truth.
     */
    public static ByteBuffer allocateDirect(int capacity)
    {
        return allocate(capacity);
    }

    public static ByteBuffer allocate(int capacity)
    {
        return new ByteBuffer(capacity);
    }

    public byte[] array()
    {
        return hb;
    }

    public int arrayOffset()
    {
        return off;
    }

    public boolean hasArray()
    {
        return hb != null;
    }

    public ByteBuffer order(ByteOrder order)
    {
        big = order != ByteOrder.LITTLE_ENDIAN;
        return this;
    }

    public ByteOrder order()
    {
        if (big)
        {
            return ByteOrder.BIG_ENDIAN;
        }
        return ByteOrder.LITTLE_ENDIAN;
    }

    public int remaining()
    {
        return lim - pos;
    }

    public boolean hasRemaining()
    {
        return pos < lim;
    }

    public ByteBuffer limit(int l)
    {
        lim = l;
        return this;
    }

    public int position()
    {
        return pos;
    }

    public ByteBuffer position(int p)
    {
        pos = p;
        return this;
    }

    public int limit()
    {
        return lim;
    }

    public int capacity()
    {
        return cap;
    }

    public ByteBuffer flip()
    {
        lim = pos;
        pos = 0;
        return this;
    }

    public ByteBuffer clear()
    {
        pos = 0;
        lim = cap;
        return this;
    }

    public ByteBuffer put(byte[] src, int srcOff, int len)
    {
        int i = 0;
        while (i < len)
        {
            hb[off + pos + i] = src[srcOff + i];
            i = i + 1;
        }
        pos = pos + len;
        return this;
    }

    public ByteBuffer put(byte[] src)
    {
        return put(src, 0, src.length);
    }

    public ByteBuffer get(byte[] dst, int dstOff, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst[dstOff + i] = hb[off + pos + i];
            i = i + 1;
        }
        pos = pos + len;
        return this;
    }

    public ByteBuffer get(byte[] dst)
    {
        return get(dst, 0, dst.length);
    }

    // ----- absolute single-value access -------------------------------------------------------------
    // Absolute (index-taking) accessors do NOT move the position, which is exactly why header-patching
    // code uses them. Multi-byte values honour the buffer's current byte order; zip data is little-endian
    // while the ByteBuffer default is big-endian, so getting that wrong is silent, not loud.

    public byte get(int index)
    {
        return hb[off + index];
    }

    public ByteBuffer put(int index, byte value)
    {
        hb[off + index] = value;
        return this;
    }

    public ByteBuffer put(int index, byte[] src)
    {
        return put(index, src, 0, src.length);
    }

    public ByteBuffer put(int index, byte[] src, int srcOff, int len)
    {
        int i = 0;
        while (i < len)
        {
            hb[off + index + i] = src[srcOff + i];
            i = i + 1;
        }
        return this;
    }

    public ByteBuffer get(int index, byte[] dst)
    {
        return get(index, dst, 0, dst.length);
    }

    public ByteBuffer get(int index, byte[] dst, int dstOff, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst[dstOff + i] = hb[off + index + i];
            i = i + 1;
        }
        return this;
    }

    public short getShort(int index)
    {
        int a = hb[off + index] & 0xFF;
        int b = hb[off + index + 1] & 0xFF;
        if (big)
        {
            return (short) ((a << 8) | b);
        }
        return (short) ((b << 8) | a);
    }

    public ByteBuffer putShort(int index, short value)
    {
        int v = value & 0xFFFF;
        if (big)
        {
            hb[off + index] = (byte) (v >>> 8);
            hb[off + index + 1] = (byte) v;
        }
        else
        {
            hb[off + index] = (byte) v;
            hb[off + index + 1] = (byte) (v >>> 8);
        }
        return this;
    }

    public int getInt(int index)
    {
        int i = 0;
        int v = 0;
        while (i < 4)
        {
            int b = hb[off + index + i] & 0xFF;
            if (big)
            {
                v = (v << 8) | b;
            }
            else
            {
                v = v | (b << (8 * i));
            }
            i = i + 1;
        }
        return v;
    }

    public ByteBuffer putInt(int index, int value)
    {
        int i = 0;
        while (i < 4)
        {
            int shift = 8 * i;
            if (big)
            {
                shift = 8 * (3 - i);
            }
            hb[off + index + i] = (byte) (value >>> shift);
            i = i + 1;
        }
        return this;
    }

    public long getLong(int index)
    {
        int i = 0;
        long v = 0;
        while (i < 8)
        {
            long b = hb[off + index + i] & 0xFFL;
            if (big)
            {
                v = (v << 8) | b;
            }
            else
            {
                v = v | (b << (8 * i));
            }
            i = i + 1;
        }
        return v;
    }

    public ByteBuffer putLong(int index, long value)
    {
        int i = 0;
        while (i < 8)
        {
            int shift = 8 * i;
            if (big)
            {
                shift = 8 * (7 - i);
            }
            hb[off + index + i] = (byte) (value >>> shift);
            i = i + 1;
        }
        return this;
    }

    // ----- relative single-value access -------------------------------------------------------------

    public byte get()
    {
        byte v = hb[off + pos];
        pos = pos + 1;
        return v;
    }

    public ByteBuffer put(byte value)
    {
        hb[off + pos] = value;
        pos = pos + 1;
        return this;
    }

    public short getShort()
    {
        short v = getShort(pos);
        pos = pos + 2;
        return v;
    }

    public ByteBuffer putShort(short value)
    {
        putShort(pos, value);
        pos = pos + 2;
        return this;
    }

    public int getInt()
    {
        int v = getInt(pos);
        pos = pos + 4;
        return v;
    }

    public ByteBuffer putInt(int value)
    {
        putInt(pos, value);
        pos = pos + 4;
        return this;
    }

    public long getLong()
    {
        long v = getLong(pos);
        pos = pos + 8;
        return v;
    }

    public ByteBuffer putLong(long value)
    {
        putLong(pos, value);
        pos = pos + 8;
        return this;
    }

    /** The raw address of the backing array's payload (header is 24 bytes: TIB, status, length). */
    public long address()
    {
        return Magic.addrOf(hb) + 24L + off;
    }
}
