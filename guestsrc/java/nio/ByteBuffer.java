package java.nio;

import magic.Magic;
import sun.nio.ch.DirectBuffer;

/**
 * A minimal, name-winning {@code java.nio.ByteBuffer} overlay for joe-ng, backing the socket dispatcher's
 * temporary buffers. Stock DirectByteBuffer allocates off-heap via {@code Unsafe.allocateMemory} and (in
 * JDK 26) pulls the {@code java.lang.foreign} scope machinery -- infeasible on metal. This wraps a plain
 * heap {@code byte[]} and exposes {@code address()} as the array payload address ({@code addrOf + 24}); the
 * socket {@code read0}/{@code write0} natives read/write that address directly, and {@code get}/{@code put}
 * are pure-Java array copies. Only the handful of methods {@code sun.nio.ch.NioSocketImpl}'s tryRead/tryWrite
 * use are implemented; enough of the ByteBuffer surface for the blocking client path.
 */
public class ByteBuffer implements DirectBuffer
{
    byte[] hb;      // backing heap array (its payload is what address() points at)
    int pos;
    int lim;
    int cap;

    public ByteBuffer(int capacity)
    {
        hb = new byte[capacity];
        cap = capacity;
        lim = capacity;
        pos = 0;
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

    public ByteBuffer put(byte[] src, int off, int len)
    {
        int i = 0;
        while (i < len)
        {
            hb[pos + i] = src[off + i];
            i = i + 1;
        }
        pos = pos + len;
        return this;
    }

    public ByteBuffer get(byte[] dst, int off, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst[off + i] = hb[pos + i];
            i = i + 1;
        }
        pos = pos + len;
        return this;
    }

    /** The raw address of the backing array's payload (header is 24 bytes: TIB, status, length). */
    public long address()
    {
        return Magic.addrOf(hb) + 24L;
    }
}
