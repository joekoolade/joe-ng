package java.util.zip;

import zip.Deflate;

/**
 * A JDK-free {@code java.util.zip.Deflater} overlay (wins by name). The stock class is a shell over native
 * zlib — {@code init}/{@code deflateBytesBytes}/{@code end} over a {@code z_stream} address — so without this
 * every stock class that WRITES an archive is unreachable on metal. It keeps the public API and delegates to
 * {@link zip.Deflate}, which emits STORED deflate blocks: valid, conforming output that does not shrink (see
 * that class for why that trade is the right one here).
 *
 * <p>{@link #setLevel} and {@link #setStrategy} are therefore accepted and ignored — every level produces the
 * same stored-block stream. {@link #getAdler} is real, since the compressor tracks it for the zlib trailer.
 * Preset dictionaries are unsupported; {@link #setDictionary} still bounds-checks its arguments first, which
 * is what stock does and what {@code java/util/zip/Bounds} tests.
 */
public class Deflater
{
    public static final int DEFLATED = 8;
    public static final int NO_COMPRESSION = 0;
    public static final int BEST_SPEED = 1;
    public static final int BEST_COMPRESSION = 9;
    public static final int DEFAULT_COMPRESSION = -1;
    public static final int FILTERED = 1;
    public static final int HUFFMAN_ONLY = 2;
    public static final int DEFAULT_STRATEGY = 0;
    public static final int NO_FLUSH = 0;
    public static final int SYNC_FLUSH = 2;
    public static final int FULL_FLUSH = 3;

    private final Deflate engine = new Deflate();
    private boolean nowrap;
    private boolean ended;

    public Deflater()
    {
        this(DEFAULT_COMPRESSION, false);
    }

    public Deflater(int level)
    {
        this(level, false);
    }

    public Deflater(int level, boolean nowrap)
    {
        this.nowrap = nowrap;
        reset();
    }

    /** Discard all state and start a fresh stream. */
    public void reset()
    {
        engine.reset(nowrap);
        ended = false;
    }

    /** Release the compressor. There is no native resource here, so this only latches the closed state. */
    public void end()
    {
        ended = true;
    }

    public void setInput(byte[] input)
    {
        setInput(input, 0, input.length);
    }

    /** Hand the compressor more uncompressed bytes. */
    public void setInput(byte[] input, int off, int len)
    {
        checkBounds(input, off, len);
        if (!ended && len > 0)
        {
            engine.input(input, off, len);
        }
    }

    /** Accepted and ignored: a stored-block stream is the same at every level. */
    public void setLevel(int level)
    {
    }

    /** Accepted and ignored: there are no Huffman tables to strategize over. */
    public void setStrategy(int strategy)
    {
    }

    /** Preset dictionaries are unsupported on metal; the bounds check still runs first, as stock does. */
    public void setDictionary(byte[] dictionary, int off, int len)
    {
        checkBounds(dictionary, off, len);
        throw new IllegalStateException("preset dictionaries are not supported");
    }

    public void setDictionary(byte[] dictionary)
    {
        setDictionary(dictionary, 0, dictionary.length);
    }

    /** No more input is coming; the next {@link #deflate} calls finish the stream. */
    public void finish()
    {
        engine.finish();
    }

    /** True once the whole compressed stream has been handed to the caller. */
    public boolean finished()
    {
        return engine.finished();
    }

    /** True when more {@link #setInput} is needed before anything further can be produced. */
    public boolean needsInput()
    {
        return engine.needsInput();
    }

    public int deflate(byte[] output)
    {
        return deflate(output, 0, output.length);
    }

    /** Compress into {@code output[off..off+len)}; the count produced, 0 when more input is needed. */
    public int deflate(byte[] output, int off, int len)
    {
        checkBounds(output, off, len);
        if (ended)
        {
            return 0;
        }
        return engine.deflate(output, off, len);
    }

    /** Flush modes are accepted and ignored: every stored block already ends on a byte boundary. */
    public int deflate(byte[] output, int off, int len, int flush)
    {
        return deflate(output, off, len);
    }

    /** Adler-32 of the uncompressed input so far — real, since the zlib trailer needs it. */
    public int getAdler()
    {
        return engine.adler();
    }

    public long getBytesRead()
    {
        return engine.bytesRead();
    }

    public long getBytesWritten()
    {
        return engine.bytesWritten();
    }

    public int getTotalIn()
    {
        return (int) engine.bytesRead();
    }

    public int getTotalOut()
    {
        return (int) engine.bytesWritten();
    }

    /**
     * The range check stock performs before touching the array. Written as a subtraction rather than
     * {@code off + len > b.length} so a length near {@code Integer.MAX_VALUE} cannot overflow into looking
     * valid — which is precisely the case {@code java/util/zip/Bounds} passes in.
     */
    static void checkBounds(byte[] b, int off, int len)
    {
        if (off < 0 || len < 0 || off > b.length - len)
        {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
}
