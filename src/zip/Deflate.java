package zip;

/**
 * A DEFLATE compressor (RFC 1951) emitting STORED blocks — the write half of joe-ng's zip support, and the
 * counterpart to {@link Inflate}. Stock {@code java.util.zip.Deflater} is a shell over native zlib, so
 * without this every stock class that WRITES an archive ({@code Deflater}, {@code DeflaterOutputStream},
 * {@code ZipOutputStream}, {@code GZIPOutputStream}) is unreachable on metal.
 *
 * <p><b>Stored blocks only, and deliberately so.</b> A stored block is a first-class DEFLATE block type
 * (BTYPE=00): the output is a valid deflate stream that any conforming inflater accepts, it just does not
 * shrink. That buys the whole write-side API — correctly framed streams, correct CRCs, correct round-trips —
 * for a fraction of the code an LZ77 matcher plus dynamic Huffman tables would need. Compression ratio is the
 * one thing given up, and nothing on metal currently needs it. A real matcher can be added behind this same
 * interface later without disturbing a caller.
 *
 * <p>Strictly JDK-free (primitive arrays + int math, no imports, no exceptions), so the same code runs on the
 * seed JVM under {@code test/zip/ZipTest} — where the JDK's own {@code Inflater} decodes its output, which is
 * what proves the framing is right — and compiles into the bare-metal image via our own baseline compiler.
 *
 * <p>Output is staged a whole block at a time into {@link #stage} and handed out from there, so the caller's
 * buffer may be any size, even one byte, without complicating block construction.
 */
public final class Deflate
{
    /** Bytes of payload per stored block. Any value up to 65535 is legal; this keeps the staging buffer small. */
    private static final int MAX_BLOCK = 8192;

    /** zlib header: CMF=0x78 (deflate, 32K window), FLG=0x01 — (0x78 << 8 | 0x01) % 31 == 0, as RFC 1950 requires. */
    private static final int CMF = 0x78;
    private static final int FLG = 0x01;

    private byte[] in = new byte[0];                   // pending uncompressed input
    private int inEnd;
    private int inPos;

    private final byte[] stage = new byte[MAX_BLOCK + 8];   // one framed block (or header/trailer), ready to hand out
    private int stageLen;
    private int stagePos;

    private boolean nowrap;                            // true = raw deflate; false = zlib header + Adler-32 trailer
    private boolean headerDone;
    private boolean finishing;
    private boolean emittedFinal;
    private boolean trailerDone;

    private int adler;                                 // Adler-32 of the UNCOMPRESSED input, per RFC 1950
    private long consumed;
    private long produced;

    /** Start a fresh stream. {@code nowrap} selects raw DEFLATE (what zip entries store) over the zlib wrapper. */
    public void reset(boolean nowrap)
    {
        this.nowrap = nowrap;
        inEnd = 0;
        inPos = 0;
        stageLen = 0;
        stagePos = 0;
        headerDone = false;
        finishing = false;
        emittedFinal = false;
        trailerDone = false;
        adler = Adler32.initial();
        consumed = 0L;
        produced = 0L;
    }

    /** Append uncompressed bytes, dropping whatever has already been packed into a block. */
    public void input(byte[] b, int off, int len)
    {
        int keepFrom = inPos;
        int keep = inEnd - keepFrom;
        if (in.length < keep + len)
        {
            int cap = 64;
            while (cap < keep + len)
            {
                cap = cap * 2;
            }
            byte[] bigger = new byte[cap];
            int i = 0;
            while (i < keep)
            {
                bigger[i] = in[keepFrom + i];
                i += 1;
            }
            in = bigger;
        }
        else if (keepFrom != 0)
        {
            int i = 0;
            while (i < keep)
            {
                in[i] = in[keepFrom + i];
                i += 1;
            }
        }
        inPos = 0;
        inEnd = keep;
        int i = 0;
        while (i < len)
        {
            in[inEnd + i] = b[off + i];
            i += 1;
        }
        inEnd += len;
    }

    /** No more input is coming: the next blocks finish the stream. */
    public void finish()
    {
        finishing = true;
    }

    /** True once the final block (and the zlib trailer, when wrapped) has been handed to the caller. */
    public boolean finished()
    {
        return emittedFinal && (nowrap || trailerDone) && stagePos >= stageLen;
    }

    /** True when more {@link #input} is needed before anything further can be produced. */
    public boolean needsInput()
    {
        return !finishing && inPos >= inEnd && stagePos >= stageLen;
    }

    /** Uncompressed bytes consumed so far. */
    public long bytesRead()
    {
        return consumed;
    }

    /** Compressed bytes produced so far. */
    public long bytesWritten()
    {
        return produced;
    }

    /** Adler-32 of the uncompressed input (the value the zlib trailer carries). */
    public int adler()
    {
        return adler;
    }

    /**
     * Fill {@code out[off..off+len)} with compressed bytes; the count produced. 0 means the compressor needs
     * more {@link #input}, or the stream is {@link #finished}.
     */
    public int deflate(byte[] out, int off, int len)
    {
        int n = 0;
        while (n < len)
        {
            if (stagePos < stageLen)
            {
                out[off + n] = stage[stagePos];
                stagePos += 1;
                n += 1;
                continue;
            }
            if (!stageNext())
            {
                break;
            }
        }
        produced += n;
        return n;
    }

    /**
     * Stage the next unit of output — the zlib header, one stored block, the empty final block, or the
     * Adler-32 trailer. False when nothing can be staged without more input.
     */
    private boolean stageNext()
    {
        stageLen = 0;
        stagePos = 0;
        if (!headerDone)
        {
            headerDone = true;
            if (!nowrap)
            {
                stage[0] = (byte) CMF;
                stage[1] = (byte) FLG;
                stageLen = 2;
                return true;
            }
        }
        int pending = inEnd - inPos;
        if (pending > 0)
        {
            int take = pending < MAX_BLOCK ? pending : MAX_BLOCK;
            boolean last = finishing && take == pending;
            putBlockHeader(last, take);
            int i = 0;
            while (i < take)
            {
                stage[5 + i] = in[inPos + i];
                i += 1;
            }
            adler = Adler32.update(adler, in, inPos, take);
            inPos += take;
            consumed += take;
            stageLen = 5 + take;
            if (last)
            {
                emittedFinal = true;
            }
            return true;
        }
        if (finishing && !emittedFinal)
        {
            putBlockHeader(true, 0);                   // an empty final block: legal, and how a 0-byte stream ends
            stageLen = 5;
            emittedFinal = true;
            return true;
        }
        if (emittedFinal && !trailerDone && !nowrap)
        {
            trailerDone = true;
            int a = adler();
            stage[0] = (byte) (a >>> 24);              // RFC 1950: the trailer is BIG-endian
            stage[1] = (byte) (a >>> 16);
            stage[2] = (byte) (a >>> 8);
            stage[3] = (byte) a;
            stageLen = 4;
            return true;
        }
        return false;
    }

    /**
     * Write a stored block's 5-byte frame: {@code BFINAL|BTYPE=00} in a byte of its own (a stored block starts
     * on a byte boundary, so the remaining 5 bits are padding), then LEN and its one's complement, both
     * little-endian.
     */
    private void putBlockHeader(boolean last, int take)
    {
        stage[0] = (byte) (last ? 1 : 0);
        stage[1] = (byte) take;
        stage[2] = (byte) (take >>> 8);
        stage[3] = (byte) ~take;
        stage[4] = (byte) (~take >>> 8);
    }

    /** One-shot convenience: compress all of {@code in[off..off+len)} into {@code out}; bytes produced. */
    public static int deflate(byte[] in, int off, int len, byte[] out, int outOff, int outLen, boolean nowrap)
    {
        Deflate d = new Deflate();
        d.reset(nowrap);
        d.input(in, off, len);
        d.finish();
        return d.deflate(out, outOff, outLen);
    }
}
