package zip;

/**
 * A raw DEFLATE decompressor (RFC 1951) — the engine under every zip/jar entry joe-ng reads. Stock
 * {@code java.util.zip.Inflater} is a thin shell over native zlib, which does not exist on metal, so this is
 * the real implementation: {@link zip.Inflater} (the {@code java.util.zip} overlay) and the on-metal class
 * loader both decode through it.
 *
 * <p>Strictly JDK-free (primitive arrays + int math, no imports, no exceptions), so the same code runs on the
 * seed JVM under {@code test/zip/ZipTest} and compiles into the bare-metal image via our own baseline compiler.
 *
 * <p><b>Streaming and resumable.</b> Compressed bytes arrive through {@link #input}; {@link #inflate} produces
 * as much output as the caller's buffer holds and returns. It can stop anywhere — mid-block, mid-LZ-copy, or
 * out of input mid-symbol — and continue on the next call. Two mechanisms make that work: a 32 KiB sliding
 * {@link #win window} keeps the last 32 KiB of output so back-references still resolve after the caller has
 * taken the bytes away, and every step that reads bits {@link #mark marks} the bit position first and
 * {@link #rewind rewinds} to it if the input runs dry, so a half-read Huffman code is simply re-read once more
 * bytes arrive.
 *
 * <p>No zlib/gzip wrapper (zip entries store raw DEFLATE), no preset dictionaries, and no Zip64/encryption
 * concerns — those live above, in {@link ZipDir}.
 */
public final class Inflate
{
    /** Block type 0: stored (literal) bytes. */
    private static final int BT_STORED = 0;

    /** Base length for length codes 257..285, and the extra bits each carries (RFC 1951 §3.2.5). */
    private static final int[] LEN_BASE =
    {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
        67, 83, 99, 115, 131, 163, 195, 227, 258
    };

    private static final int[] LEN_EXTRA =
    {
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
        4, 4, 4, 4, 5, 5, 5, 5, 0
    };

    /** Base distance for distance codes 0..29, and the extra bits each carries. */
    private static final int[] DIST_BASE =
    {
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
        1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577
    };

    private static final int[] DIST_EXTRA =
    {
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
        9, 9, 10, 10, 11, 11, 12, 12, 13, 13
    };

    /** The order code lengths for the code-length alphabet appear in, in a dynamic block header. */
    private static final int[] CLEN_ORDER =
    {
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    private static final int ST_HEADER = 0;            // between blocks: read BFINAL + BTYPE
    private static final int ST_STORED = 1;            // copying a stored block's bytes
    private static final int ST_TABLES = 2;            // reading a compressed block's Huffman tables
    private static final int ST_BLOCK  = 3;            // decoding symbols with the current tables
    private static final int ST_DONE   = 4;            // past the final block
    private static final int ST_ERROR  = 5;            // malformed stream; no recovery

    /** Unconsumed compressed input; {@link #input} appends and compacts away what is already consumed. */
    private byte[] in = new byte[0];
    private int inEnd;                                 // bytes valid in `in`
    private int inPos;                                 // next byte to shift into the bit buffer
    private int bitBuf;                                // bits pulled from `in` but not yet used, LSB first
    private int bitCnt;                                // how many bits `bitBuf` holds
    private long dropped;                              // input bytes compacted away (for bytesRead)

    private int markPos;                               // bit position saved by mark(), restored by rewind()
    private int markBuf;
    private int markCnt;
    private boolean starved;                           // the last step ran past the end of the input

    /** The last 32 KiB of output — an LZ back-reference may reach bytes the caller already took. */
    private final byte[] win = new byte[32768];
    private int wpos;

    private int state;
    private boolean last;                              // BFINAL of the block being decoded
    private int storedRem;                             // bytes still to copy in a stored block
    private int copyRem;                               // bytes still to copy in an in-progress LZ match
    private int copyDist;
    private long produced;

    private Huff lit;                                  // the current block's literal/length code ...
    private Huff dst;                                  // ... and distance code: the fixed or the dynamic pair
    private final Huff dynLit = new Huff(288);         // rebuilt per dynamic block
    private final Huff dynDst = new Huff(30);
    private final Huff clen = new Huff(19);            // the code-length code that encodes a dynamic header
    private final int[] lengths = new int[320];        // scratch: code lengths read from a dynamic header

    private boolean fixedBuilt;
    private final Huff fixedLit = new Huff(288);
    private final Huff fixedDst = new Huff(30);

    /** Start over on a fresh DEFLATE stream, keeping the allocated buffers. */
    public void reset()
    {
        inEnd = 0;
        inPos = 0;
        bitBuf = 0;
        bitCnt = 0;
        dropped = 0L;
        starved = false;
        wpos = 0;
        state = ST_HEADER;
        last = false;
        storedRem = 0;
        copyRem = 0;
        copyDist = 0;
        produced = 0L;
    }

    /** Append compressed bytes {@code b[off..off+len)} to the input, dropping what has already been consumed. */
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
        dropped += keepFrom;
        inPos = 0;
        inEnd = keep;
        int i = 0;
        while (i < len)
        {
            in[inEnd + i] = b[off + i];
            i += 1;
        }
        inEnd += len;
        starved = false;
    }

    /** True once the final block has been fully decoded. */
    public boolean finished()
    {
        return state == ST_DONE;
    }

    /** True when the stream is malformed; the decoder produces nothing further. */
    public boolean failed()
    {
        return state == ST_ERROR;
    }

    /**
     * True when more compressed input is needed before any more output can be produced. Note that this is not
     * simply "the input buffer is empty": a step that ran dry mid-symbol {@link #rewind rewound} the bit
     * position, leaving bytes unconsumed that are nevertheless not enough to decode anything, so the
     * {@link #starved} flag is what actually answers the question.
     */
    public boolean needsInput()
    {
        return state != ST_DONE && state != ST_ERROR && (starved || inPos >= inEnd);
    }

    /** True when output can still be produced with no further input — an LZ match copy stopped part-way. */
    public boolean pendingOutput()
    {
        return copyRem > 0;
    }

    /** Compressed bytes fully consumed so far (bits sitting in the bit buffer do not count). */
    public long bytesRead()
    {
        return dropped + inPos - (bitCnt >> 3);
    }

    /** Uncompressed bytes produced so far. */
    public long bytesWritten()
    {
        return produced;
    }

    /** Compressed bytes appended but not yet consumed. */
    public int remaining()
    {
        return inEnd - inPos + (bitCnt >> 3);
    }

    /**
     * Decompress into {@code out[off..off+len)}. Returns the number of bytes produced — 0 means the decoder
     * needs more {@link #input}, has {@link #finished}, or {@link #failed}. Never blocks and never throws.
     */
    public int inflate(byte[] out, int off, int len)
    {
        int at = off;
        int end = off + len;
        while (at < end && state != ST_DONE && state != ST_ERROR)
        {
            if (copyRem > 0)
            {
                at = copyMatch(out, at, end);
                continue;
            }
            if (state == ST_HEADER)
            {
                readBlockHeader();
            }
            else if (state == ST_STORED)
            {
                at = copyStored(out, at, end);
            }
            else if (state == ST_TABLES)
            {
                readTables();
            }
            else
            {
                at = decodeBlock(out, at, end);
            }
            if (starved)
            {
                break;
            }
        }
        return at - off;
    }

    /** One-shot convenience: inflate all of {@code in[off..off+len)} into {@code out}; bytes produced, or -1. */
    public static int inflate(byte[] in, int off, int len, byte[] out, int outOff, int outLen)
    {
        Inflate z = new Inflate();
        z.reset();
        z.input(in, off, len);
        int n = z.inflate(out, outOff, outLen);
        if (z.failed())
        {
            return -1;
        }
        return n;
    }

    // ---------------------------------------------------------------- bit input

    /** Save the bit position so a step that runs out of input can put every bit back. */
    private void mark()
    {
        markPos = inPos;
        markBuf = bitBuf;
        markCnt = bitCnt;
    }

    /** Undo every bit read since {@link #mark}, so the step re-runs unchanged once more input arrives. */
    private void rewind()
    {
        inPos = markPos;
        bitBuf = markBuf;
        bitCnt = markCnt;
    }

    /** The next {@code n} bits (LSB first), or 0 with {@link #starved} set when the input is exhausted. */
    private int bits(int n)
    {
        while (bitCnt < n)
        {
            if (inPos >= inEnd)
            {
                starved = true;
                return 0;
            }
            bitBuf = bitBuf | ((in[inPos] & 0xFF) << bitCnt);
            inPos += 1;
            bitCnt += 8;
        }
        int v = bitBuf & ((1 << n) - 1);
        bitBuf = bitBuf >>> n;
        bitCnt = bitCnt - n;
        return v;
    }

    /** Walk one canonical Huffman code bit by bit; the symbol, or -1 on a malformed code (or starvation). */
    private int decodeSym(Huff h)
    {
        int code = 0;
        int first = 0;
        int index = 0;
        int len = 1;
        while (len <= Huff.MAXBITS)
        {
            code = code | bits(1);
            if (starved)
            {
                return -1;
            }
            int count = h.count[len];
            if (code - first < count)
            {
                return h.symbol[index + (code - first)];
            }
            index += count;
            first = (first + count) << 1;
            code = code << 1;
            len += 1;
        }
        return -1;                                     // no code longer than 15 bits exists
    }

    // ---------------------------------------------------------------- output

    /** Write one byte to the caller's buffer and mirror it into the sliding window. */
    private int emit(byte[] out, int at, int b)
    {
        out[at] = (byte) b;
        win[wpos] = (byte) b;
        wpos = (wpos + 1) & 32767;
        produced += 1;
        return at + 1;
    }

    // ---------------------------------------------------------------- block decoding

    /** Read BFINAL + BTYPE and enter the block's state. */
    private void readBlockHeader()
    {
        mark();
        int bfinal = bits(1);
        int btype = bits(2);
        if (starved)
        {
            rewind();
            return;
        }
        last = bfinal != 0;
        if (btype == BT_STORED)
        {
            readStoredHeader();
        }
        else if (btype == 1)
        {
            buildFixed();
            lit = fixedLit;
            dst = fixedDst;
            state = ST_BLOCK;
        }
        else if (btype == 2)
        {
            state = ST_TABLES;
        }
        else
        {
            state = ST_ERROR;                          // btype 3 is reserved
        }
    }

    /** Discard to the byte boundary, then read a stored block's LEN/NLEN pair. */
    private void readStoredHeader()
    {
        bitBuf = bitBuf >>> (bitCnt & 7);              // stored blocks start on a byte boundary
        bitCnt = bitCnt - (bitCnt & 7);
        int lo = bits(8);
        int hi = bits(8);
        int nlo = bits(8);
        int nhi = bits(8);
        if (starved)
        {
            rewind();                                  // back to before BFINAL/BTYPE: the whole step re-runs
            return;
        }
        int n = lo | (hi << 8);
        int inv = nlo | (nhi << 8);
        if ((n ^ 0xFFFF) != inv)
        {
            state = ST_ERROR;
            return;
        }
        storedRem = n;
        state = ST_STORED;
    }

    /** Copy a stored block's bytes out; the bit buffer is byte-aligned here, so each byte is one 8-bit read. */
    private int copyStored(byte[] out, int at, int end)
    {
        while (storedRem > 0 && at < end)
        {
            mark();
            int b = bits(8);
            if (starved)
            {
                rewind();
                return at;
            }
            at = emit(out, at, b);
            storedRem -= 1;
        }
        if (storedRem == 0)
        {
            state = last ? ST_DONE : ST_HEADER;
        }
        return at;
    }

    /** Build the literal/length + distance tables of a dynamic block (RFC 1951 §3.2.7). */
    private void readTables()
    {
        mark();
        int hlit = bits(5) + 257;
        int hdist = bits(5) + 1;
        int hclen = bits(4) + 4;
        if (starved)
        {
            rewind();
            return;
        }
        if (hlit > 286 || hdist > 30)
        {
            state = ST_ERROR;
            return;
        }
        int i = 0;
        while (i < 19)
        {
            lengths[i] = 0;
            i += 1;
        }
        i = 0;
        while (i < hclen)
        {
            lengths[CLEN_ORDER[i]] = bits(3);
            if (starved)
            {
                rewind();
                return;
            }
            i += 1;
        }
        if (clen.build(lengths, 19) != 0)
        {
            state = ST_ERROR;
            return;
        }
        int n = 0;
        while (n < hlit + hdist)
        {
            int sym = decodeSym(clen);
            if (starved)
            {
                rewind();
                return;
            }
            if (sym < 0)
            {
                state = ST_ERROR;
                return;
            }
            if (sym < 16)
            {
                lengths[n] = sym;
                n += 1;
            }
            else
            {
                int rep = 0;
                int value = 0;
                if (sym == 16)
                {
                    if (n == 0)
                    {
                        state = ST_ERROR;
                        return;
                    }
                    value = lengths[n - 1];
                    rep = 3 + bits(2);
                }
                else if (sym == 17)
                {
                    rep = 3 + bits(3);
                }
                else
                {
                    rep = 11 + bits(7);
                }
                if (starved)
                {
                    rewind();
                    return;
                }
                if (n + rep > hlit + hdist)
                {
                    state = ST_ERROR;
                    return;
                }
                while (rep > 0)
                {
                    lengths[n] = value;
                    n += 1;
                    rep -= 1;
                }
            }
        }
        if (lengths[256] == 0)                         // no end-of-block code: the stream could never terminate
        {
            state = ST_ERROR;
            return;
        }
        if (dynLit.build(lengths, hlit) != 0)
        {
            state = ST_ERROR;
            return;
        }
        int d = 0;
        while (d < hdist)
        {
            lengths[d] = lengths[hlit + d];
            d += 1;
        }
        if (dynDst.build(lengths, hdist) < 0)
        {
            state = ST_ERROR;
            return;
        }
        lit = dynLit;
        dst = dynDst;
        state = ST_BLOCK;
    }

    /** Decode literal/length symbols until the caller's buffer fills, the input runs out, or the block ends. */
    private int decodeBlock(byte[] out, int at, int end)
    {
        while (at < end)
        {
            mark();
            int sym = decodeSym(lit);
            if (starved)
            {
                rewind();
                return at;
            }
            if (sym < 0)
            {
                state = ST_ERROR;
                return at;
            }
            if (sym < 256)
            {
                at = emit(out, at, sym);
                continue;
            }
            if (sym == 256)
            {
                state = last ? ST_DONE : ST_HEADER;
                return at;
            }
            sym = sym - 257;
            if (sym >= LEN_BASE.length)
            {
                state = ST_ERROR;
                return at;
            }
            int length = LEN_BASE[sym] + bits(LEN_EXTRA[sym]);
            if (starved)
            {
                rewind();
                return at;
            }
            int dsym = decodeSym(dst);
            if (starved)
            {
                rewind();
                return at;
            }
            if (dsym < 0 || dsym >= DIST_BASE.length)
            {
                state = ST_ERROR;
                return at;
            }
            int distance = DIST_BASE[dsym] + bits(DIST_EXTRA[dsym]);
            if (starved)
            {
                rewind();
                return at;
            }
            if (distance > produced || distance > 32768)
            {
                state = ST_ERROR;                      // reaches before the start of the output
                return at;
            }
            copyRem = length;
            copyDist = distance;
            at = copyMatch(out, at, end);
        }
        return at;
    }

    /** Copy an LZ match out of the window, one byte at a time so overlapping matches self-extend. */
    private int copyMatch(byte[] out, int at, int end)
    {
        while (copyRem > 0 && at < end)
        {
            int b = win[(wpos - copyDist) & 32767] & 0xFF;
            at = emit(out, at, b);
            copyRem -= 1;
        }
        return at;
    }

    // ---------------------------------------------------------------- fixed tables

    /** Build the fixed literal/length + distance codes once (RFC 1951 §3.2.6); they never change. */
    private void buildFixed()
    {
        if (fixedBuilt)
        {
            return;
        }
        int i = 0;
        while (i < 144)
        {
            lengths[i] = 8;
            i += 1;
        }
        while (i < 256)
        {
            lengths[i] = 9;
            i += 1;
        }
        while (i < 280)
        {
            lengths[i] = 7;
            i += 1;
        }
        while (i < 288)
        {
            lengths[i] = 8;
            i += 1;
        }
        int unused = fixedLit.build(lengths, 288);
        i = 0;
        while (i < 30)
        {
            lengths[i] = 5;
            i += 1;
        }
        unused = fixedDst.build(lengths, 30);
        fixedBuilt = true;
    }

}
