package sun.nio.ch;

import java.nio.ByteBuffer;

/**
 * Name-winning {@code sun.nio.ch.Util} overlay: the temporary-direct-buffer pool the socket dispatcher uses.
 * Stock caches per-carrier-thread {@code DirectByteBuffer}s; on metal we just hand back a fresh heap-backed
 * overlay {@link ByteBuffer} each time (the socket path is single-threaded and short-lived). Only the three
 * methods {@code NioSocketImpl} calls are provided.
 */
public class Util
{
    private Util()
    {
    }

    public static ByteBuffer getTemporaryDirectBuffer(int size)
    {
        return new ByteBuffer(size);
    }

    public static void offerFirstTemporaryDirectBuffer(ByteBuffer buf)
    {
    }

    public static void releaseTemporaryDirectBuffer(ByteBuffer buf)
    {
    }
}
