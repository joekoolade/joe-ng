package java.util.zip;

import zip.Inflate;

/**
 * A JDK-free {@code java.util.zip.Inflater} overlay (wins by name). The stock class is a shell over native
 * zlib — {@code init}/{@code inflateBytesBytes}/{@code end} and a {@code Cleaner} over a {@code z_stream}
 * address — none of which exists on metal. This overlay keeps the public API exactly and delegates to
 * {@link zip.Inflate}, joe-ng's own RFC-1951 decoder, so the UNMODIFIED stock {@code InflaterInputStream},
 * {@code ZipInputStream} and {@code JarInputStream} run on top of it unchanged.
 *
 * <p>{@link zip.Inflate} is the same class the image-baked class loader inflates jar entries with; here it is
 * simply demand-loaded into the guest world like any other class, so there is one decoder implementation, not
 * two. That is why this overlay needs no VM native at all.
 *
 * <p>The engine decodes RAW DEFLATE. {@code nowrap=false} (the zlib-wrapped form) is handled here by skipping
 * the 2-byte zlib header and ignoring the trailing Adler-32; {@link #getAdler} therefore answers 0. Preset
 * dictionaries are not supported (nothing in the zip/jar path uses them).
 */
public class Inflater
{
    private final Inflate engine = new Inflate();
    private boolean nowrap;
    private int headerLeft;                              // zlib header bytes still to swallow (nowrap=false)
    private long headerRead;                             // ... counted into getBytesRead, like zlib does
    private boolean ended;

    public Inflater()
    {
        this(false);
    }

    public Inflater(boolean nowrap)
    {
        this.nowrap = nowrap;
        reset();
    }

    /** Discard all state and start on a fresh stream. */
    public void reset()
    {
        engine.reset();
        headerLeft = nowrap ? 0 : 2;
        headerRead = 0;
        ended = false;
    }

    /** Release the decoder. There is no native resource here, so this only latches the closed state. */
    public void end()
    {
        ended = true;
    }

    public void setInput(byte[] input)
    {
        setInput(input, 0, input.length);
    }

    /** Hand the decoder more compressed bytes; it keeps whatever it cannot consume yet. */
    public void setInput(byte[] input, int off, int len)
    {
        if (ended || len <= 0)
        {
            return;
        }
        while (headerLeft > 0 && len > 0)
        {
            off += 1;                                    // swallow the zlib CMF/FLG bytes
            len -= 1;
            headerLeft -= 1;
            headerRead += 1;
        }
        if (len > 0)
        {
            engine.input(input, off, len);
        }
    }

    public int inflate(byte[] output) throws DataFormatException
    {
        return inflate(output, 0, output.length);
    }

    /** Decompress into {@code output[off..off+len)}; the count produced, 0 when more input is needed. */
    public int inflate(byte[] output, int off, int len) throws DataFormatException
    {
        if (ended)
        {
            return 0;
        }
        int n = engine.inflate(output, off, len);
        if (engine.failed())
        {
            throw new DataFormatException("invalid deflate stream");
        }
        return n;
    }

    /** True when the decoder cannot proceed without more {@link #setInput}. */
    public boolean needsInput()
    {
        return headerLeft > 0 || engine.needsInput();
    }

    /** joe-ng never uses preset dictionaries, so a stream never stalls waiting for one. */
    public boolean needsDictionary()
    {
        return false;
    }

    /** True once the final DEFLATE block has been decoded. */
    public boolean finished()
    {
        return engine.finished();
    }

    /** Package-private hook {@code InflaterInputStream} uses: output is still available with no new input. */
    boolean hasPendingOutput()
    {
        return engine.pendingOutput();
    }

    /** Compressed bytes handed in but not yet consumed — how far {@code ZipInputStream} must rewind. */
    public int getRemaining()
    {
        return engine.remaining();
    }

    public long getBytesRead()
    {
        return headerRead + engine.bytesRead();
    }

    public long getBytesWritten()
    {
        return engine.bytesWritten();
    }

    /** Always 0: the raw DEFLATE engine does not maintain the zlib wrapper's Adler-32. */
    public int getAdler()
    {
        return 0;
    }

    public int getTotalIn()
    {
        return (int) getBytesRead();
    }

    public int getTotalOut()
    {
        return (int) getBytesWritten();
    }

    /** Preset dictionaries are unsupported on metal; nothing in the zip/jar path sets one. */
    public void setDictionary(byte[] dictionary, int off, int len)
    {
        throw new IllegalStateException("preset dictionaries are not supported");
    }

    public void setDictionary(byte[] dictionary)
    {
        setDictionary(dictionary, 0, dictionary.length);
    }
}
