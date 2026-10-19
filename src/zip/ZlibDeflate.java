/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-18
 */
package zip;

/**
 * A DEFLATE compressor (RFC 1951) with zlib's {@code deflate()} contract: what stock
 * {@code java.util.zip.Deflater}'s natives ({@code Deflater.c}) need from a {@code z_stream}. Written from the
 * RFC and zlib's documented design, not its code: LZ77 over a 32 KiB window with hash chains, tuned per level by
 * zlib's configuration table (greedy matching for levels 1-3, lazy for 4-9, stored blocks for 0), the FILTERED and
 * HUFFMAN_ONLY strategies, and for each block the smallest of a stored, a fixed-code and a dynamic-code encoding,
 * dynamic codes length-limited to 15 bits. The output is valid DEFLATE that zlib inflates; it is NOT byte-for-byte
 * zlib's, which nothing specifies.
 *
 * <p>Flushes are zlib's: NO_FLUSH, BLOCK (end the block, as deflateParams does before a change), SYNC_FLUSH and
 * FULL_FLUSH (end the block, then an empty stored block so everything so far is decodable; FULL also forgets the
 * history), FINISH. With {@code nowrap} false the output is the zlib format (RFC 1950): header (FDICT when a
 * dictionary was set), body, Adler-32 trailer. {@link #adler} is {@code strm->adler}.
 *
 * <p>Input is taken in full on every call -- the compressed form waits in a pending buffer until the caller's
 * output has room -- so {@link #inUsed} is always the whole slice. zlib stops taking input when its output is full;
 * the totals at the end of a stream are the same.
 */
public final class ZlibDeflate
{
    public static final int OK = 0;
    public static final int STREAM_END = 1;
    public static final int BUF_ERROR = 2;
    public static final int STREAM_ERROR = 3;

    public static final int NO_FLUSH = 0;
    public static final int SYNC_FLUSH = 2;
    public static final int FULL_FLUSH = 3;
    public static final int FINISH = 4;
    public static final int BLOCK = 5;

    public static final int DEFAULT_STRATEGY = 0;
    public static final int FILTERED = 1;
    public static final int HUFFMAN_ONLY = 2;

    private static final int WSIZE = 32768;
    private static final int WMASK = WSIZE - 1;
    private static final int MIN_MATCH = 3;
    private static final int MAX_MATCH = 258;
    private static final int MIN_LOOKAHEAD = MAX_MATCH + MIN_MATCH + 1;
    private static final int MAX_DIST = WSIZE - MIN_LOOKAHEAD;
    private static final int HASH_SIZE = 32768;
    private static final int HASH_MASK = HASH_SIZE - 1;
    private static final int TOO_FAR = 4096;
    private static final int LIT_BUFSIZE = 16384;
    private static final int NIL = -1;

    /** zlib's configuration table: good_length, max_lazy, nice_length, max_chain; and 0 stored, 1 fast, 2 slow. */
    private static final int[][] CONFIG = {
        { 0, 0, 0, 0, 0 },
        { 4, 4, 8, 4, 1 },
        { 4, 5, 16, 8, 1 },
        { 4, 6, 32, 32, 1 },
        { 4, 4, 16, 16, 2 },
        { 8, 16, 32, 32, 2 },
        { 8, 16, 128, 128, 2 },
        { 8, 32, 128, 256, 2 },
        { 32, 128, 258, 1024, 2 },
        { 32, 258, 258, 4096, 2 },
    };

    private static final int[] LEN_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227,
        258
    };
    private static final int[] LEN_EXTRA = {
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
    };
    private static final int[] DIST_BASE = {
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
        6145, 8193, 12289, 16385, 24577
    };
    private static final int[] DIST_EXTRA = {
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
    };
    private static final int[] BL_ORDER = { 16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15 };

    // ---------------------------------------------------------------- state

    private final boolean wrap;
    private int level;
    private int strategy;
    private int goodMatch;
    private int maxLazy;
    private int niceMatch;
    private int maxChain;
    private int func;

    private final byte[] window = new byte[2 * WSIZE];
    private final int[] head = new int[HASH_SIZE];
    private final int[] prev = new int[WSIZE];
    private int strstart;
    private int blockStart;
    private int lookahead;
    private int matchLength;
    private int matchStart;
    private int prevLength;
    private int prevMatch;
    private boolean matchAvailable;

    private final int[] symLit = new int[LIT_BUFSIZE];
    private final int[] symDist = new int[LIT_BUFSIZE];
    private int symNext;
    private final int[] litFreq = new int[286];
    private final int[] distFreq = new int[30];

    private byte[] pend = new byte[1024];
    private int pendStart;
    private int pendEnd;
    private int biBuf;
    private int biValid;

    private boolean headerDone;
    private boolean dictSet;
    private int dictId;
    private boolean finishing;
    private boolean finishDone;
    private boolean anyDeflated;
    /**
     * An empty sync marker has gone out and no input has arrived since. A caller that keeps flushing into a tiny
     * buffer ("until the output is not full") would otherwise get a fresh marker on every refill and never finish
     * -- zlib's own control flow does that with a small enough buffer; the extra markers carry nothing.
     */
    private boolean markerSent;
    private int lastFlush;
    private int adler;

    // the caller's input, for the duration of one call
    private byte[] in;
    private int ip;
    private int iend;

    /** Input bytes the last call consumed. */
    public int inUsed;

    /** Output bytes the last call produced. */
    public int outUsed;

    public ZlibDeflate(int level, int strategy, boolean nowrap)
    {
        wrap = !nowrap;
        setLevel(level, strategy);
        reset();
    }

    /** {@code deflateReset}: a fresh stream, the level and strategy kept. */
    public void reset()
    {
        int i = 0;
        while (i < HASH_SIZE)
        {
            head[i] = NIL;
            i += 1;
        }
        strstart = 0;
        blockStart = 0;
        lookahead = 0;
        matchLength = MIN_MATCH - 1;
        prevLength = MIN_MATCH - 1;
        matchAvailable = false;
        matchStart = 0;
        prevMatch = 0;
        initBlock();
        pendStart = 0;
        pendEnd = 0;
        biBuf = 0;
        biValid = 0;
        headerDone = !wrap;
        dictSet = false;
        dictId = 0;
        finishing = false;
        finishDone = false;
        anyDeflated = false;
        markerSent = false;
        lastFlush = -2;
        adler = 1;
    }

    /** {@code strm->adler}: the Adler-32 of the input so far (zlib format), the dictionary's before the header. */
    public int adler()
    {
        return adler;
    }

    private void setLevel(int newLevel, int newStrategy)
    {
        level = newLevel == -1 ? 6 : newLevel;
        strategy = newStrategy;
        goodMatch = CONFIG[level][0];
        maxLazy = CONFIG[level][1];
        niceMatch = CONFIG[level][2];
        maxChain = CONFIG[level][3];
        func = CONFIG[level][4];
    }

    // ---------------------------------------------------------------- the zlib entry points

    /**
     * {@code deflateParams}: anything already given is compressed and its block ended under the OLD parameters
     * (zlib's {@code deflate(strm, Z_BLOCK)}), then the new ones apply. Takes the slice like {@link #deflate}.
     */
    public int params(byte[] b, int off, int len, byte[] out, int outOff, int outLen, int newLevel, int newStrategy)
    {
        int nl = newLevel == -1 ? 6 : newLevel;
        int r = OK;
        if ((CONFIG[nl][4] != func || newStrategy != strategy) && anyDeflated)
        {
            r = deflate(b, off, len, out, outOff, outLen, BLOCK);
        }
        else
        {
            inUsed = 0;
            outUsed = 0;
        }
        setLevel(newLevel, newStrategy);
        return r == BUF_ERROR ? OK : r;
    }

    /** {@code deflate(strm, flush)} over {@code b[off..+len)} into {@code out[outOff..+outLen)}. */
    public int deflate(byte[] b, int off, int len, byte[] out, int outOff, int outLen, int flush)
    {
        in = b;
        ip = off;
        iend = off + len;
        int oldFlush = lastFlush;
        lastFlush = flush;
        int op = outOff;
        int oend = outOff + outLen;
        if (pendEnd > pendStart || biValid >= 8)
        {
            op = flushPending(out, op, oend);
            if (op == oend)
            {
                lastFlush = -1;                         // zlib: avoid a repeated flush being taken as no progress
            }
        }
        else if (len == 0 && rank(flush) <= rank(oldFlush) && flush != FINISH)
        {
            inUsed = 0;
            outUsed = 0;
            in = null;
            return BUF_ERROR;
        }
        if (finishDone && len > 0)
        {
            inUsed = 0;
            outUsed = op - outOff;
            in = null;
            return BUF_ERROR;                           // zlib: input after the stream ended
        }
        if (!headerDone)
        {
            writeHeader();
        }
        if (len > 0)
        {
            anyDeflated = true;
            markerSent = false;
        }
        if (!finishDone && (len > 0 || lookahead > 0 || flush != NO_FLUSH))
        {
            int f = flush == FINISH ? FINISH : flush;
            boolean done = compress(f);
            if (done && flush != NO_FLUSH)
            {
                if (flush == FINISH)
                {
                    flushBlock(true);
                    biWindup();
                    if (wrap)
                    {
                        putShortMsb(adler >>> 16);
                        putShortMsb(adler & 0xFFFF);
                    }
                    finishDone = true;
                }
                else
                {
                    if (symNext > 0 || strstart > blockStart)
                    {
                        flushBlock(false);
                    }
                    if ((flush == SYNC_FLUSH || flush == FULL_FLUSH) && !markerSent)
                    {
                        sendBits(0, 3);                 // an empty, non-final stored block: BTYPE 00
                        biWindup();
                        putByte(0);
                        putByte(0);
                        putByte(0xFF);
                        putByte(0xFF);
                        if (flush == FULL_FLUSH)
                        {
                            clearHash();
                        }
                        markerSent = true;
                    }
                }
            }
        }
        op = flushPending(out, op, oend);
        inUsed = ip - off;
        outUsed = op - outOff;
        in = null;
        if (finishDone && pendEnd == pendStart && biValid == 0)
        {
            return STREAM_END;
        }
        return OK;
    }

    private static int rank(int flush)
    {
        return flush == BLOCK ? 1 : flush == NO_FLUSH ? 0 : flush == -2 || flush == -1 ? -1 : flush + 1;
    }

    /**
     * {@code deflateSetDictionary}: {@link #STREAM_ERROR} once a zlib stream has started (or while raw input waits),
     * else the dictionary's last 32 KiB become history and, for the zlib format, its Adler-32 the FDICT id.
     */
    public int setDictionary(byte[] d, int off, int len)
    {
        if ((wrap && (headerDone || anyDeflated)) || lookahead > 0)
        {
            return STREAM_ERROR;
        }
        if (wrap)
        {
            adler = Adler32.update(adler, d, off, len);
            dictId = adler;
            dictSet = true;
        }
        int from = off;
        int n = len;
        if (n > WSIZE)
        {
            from = off + len - WSIZE;
            n = WSIZE;
        }
        if (strstart + n > 2 * WSIZE - MIN_LOOKAHEAD)
        {
            strstart = 0;
            blockStart = 0;
            clearHash();
        }
        System.arraycopy(d, from, window, strstart, n);
        int p = strstart;
        int end = strstart + n;
        while (p + 2 < end)
        {
            insert(p);
            p += 1;
        }
        strstart = end;
        blockStart = end;
        return OK;
    }

    private void writeHeader()
    {
        int levelFlags;
        if (strategy >= HUFFMAN_ONLY || level < 2)
        {
            levelFlags = 0;
        }
        else if (level < 6)
        {
            levelFlags = 1;
        }
        else if (level == 6)
        {
            levelFlags = 2;
        }
        else
        {
            levelFlags = 3;
        }
        int h = (0x78 << 8) | (levelFlags << 6);
        if (dictSet)
        {
            h |= 0x20;
        }
        h += 31 - (h % 31);
        putShortMsb(h);
        if (dictSet)
        {
            putShortMsb(dictId >>> 16);
            putShortMsb(dictId & 0xFFFF);
        }
        adler = 1;                                      // the data's checksum starts here
        headerDone = true;
    }

    // ---------------------------------------------------------------- the window

    /** Move caller input into the window behind the lookahead, sliding the window down when its top is reached. */
    private void fillWindow()
    {
        while (lookahead < MIN_LOOKAHEAD && ip < iend)
        {
            if (strstart >= WSIZE + MAX_DIST)
            {
                slide();
            }
            int room = 2 * WSIZE - (strstart + lookahead);
            int n = iend - ip;
            if (n > room)
            {
                n = room;
            }
            int at = strstart + lookahead;
            System.arraycopy(in, ip, window, at, n);
            if (wrap)
            {
                adler = Adler32.update(adler, in, ip, n);
            }
            ip += n;
            lookahead += n;
        }
    }

    private void slide()
    {
        if (level == 0 && blockStart < WSIZE)
        {
            flushBlock(false);                          // a stored block must still be in the window
        }
        System.arraycopy(window, WSIZE, window, 0, WSIZE);
        matchStart -= WSIZE;
        prevMatch -= WSIZE;
        strstart -= WSIZE;
        blockStart -= WSIZE;
        int i = 0;
        while (i < HASH_SIZE)
        {
            int v = head[i];
            head[i] = v >= WSIZE ? v - WSIZE : NIL;
            i += 1;
        }
        i = 0;
        while (i < WSIZE)
        {
            int v = prev[i];
            prev[i] = v >= WSIZE ? v - WSIZE : NIL;
            i += 1;
        }
    }

    private void clearHash()
    {
        int i = 0;
        while (i < HASH_SIZE)
        {
            head[i] = NIL;
            i += 1;
        }
    }

    /** Hash the three bytes at {@code p} into the chains; the previous head for that hash, or {@link #NIL}. */
    private int insert(int p)
    {
        int h = (((window[p] & 0xFF) << 10) ^ ((window[p + 1] & 0xFF) << 5) ^ (window[p + 2] & 0xFF)) & HASH_MASK;
        int old = head[h];
        prev[p & WMASK] = old;
        head[h] = p;
        return old;
    }

    private int longestMatch(int curMatch)
    {
        int chain = maxChain;
        int scan = strstart;
        int bestLen = prevLength;
        int nice = niceMatch;
        int limit = strstart > MAX_DIST ? strstart - MAX_DIST : NIL;
        if (prevLength >= goodMatch)
        {
            chain >>= 2;
        }
        if (nice > lookahead)
        {
            nice = lookahead;
        }
        int maxLen = lookahead < MAX_MATCH ? lookahead : MAX_MATCH;
        while (true)
        {
            int match = curMatch;
            if (window[match + bestLen] == window[scan + bestLen]
                    && window[match + bestLen - 1] == window[scan + bestLen - 1]
                    && window[match] == window[scan] && window[match + 1] == window[scan + 1])
            {
                int len = 2;
                while (len < maxLen && window[match + len] == window[scan + len])
                {
                    len += 1;
                }
                if (len > bestLen)
                {
                    matchStart = curMatch;
                    bestLen = len;
                    if (len >= nice)
                    {
                        break;
                    }
                }
            }
            curMatch = prev[curMatch & WMASK];
            chain -= 1;
            if (curMatch <= limit || curMatch == NIL || chain == 0)
            {
                break;
            }
        }
        return bestLen <= lookahead ? bestLen : lookahead;
    }

    // ---------------------------------------------------------------- the compressors

    /** Run the level's compressor over the input; true once everything given (and, for a flush, held) is done. */
    private boolean compress(int flush)
    {
        if (level == 0)
        {
            return compressStored(flush);
        }
        if (strategy == HUFFMAN_ONLY)
        {
            return compressHuff(flush);
        }
        return func == 1 ? compressFast(flush) : compressSlow(flush);
    }

    private boolean compressStored(int flush)
    {
        while (true)
        {
            fillWindow();
            if (lookahead == 0)
            {
                return true;
            }
            if (lookahead < MIN_LOOKAHEAD && flush == NO_FLUSH && ip >= iend)
            {
                strstart += lookahead;                  // stored data needs no lookahead: take it all
                lookahead = 0;
                return true;
            }
            strstart += lookahead;
            lookahead = 0;
            if (strstart - blockStart >= 65535)
            {
                flushBlock(false);
            }
        }
    }

    private boolean compressHuff(int flush)
    {
        while (true)
        {
            if (lookahead == 0)
            {
                fillWindow();
                if (lookahead == 0)
                {
                    return true;
                }
            }
            if (tallyLit(window[strstart] & 0xFF))
            {
                lookahead -= 1;
                strstart += 1;
                flushBlock(false);
            }
            else
            {
                lookahead -= 1;
                strstart += 1;
            }
        }
    }

    private boolean compressFast(int flush)
    {
        while (true)
        {
            if (lookahead < MIN_LOOKAHEAD)
            {
                fillWindow();
                if (lookahead < MIN_LOOKAHEAD && flush == NO_FLUSH)
                {
                    return false;
                }
                if (lookahead == 0)
                {
                    return true;
                }
            }
            int hashHead = NIL;
            if (lookahead >= MIN_MATCH)
            {
                hashHead = insert(strstart);
            }
            matchLength = MIN_MATCH - 1;
            if (hashHead != NIL && strstart - hashHead <= MAX_DIST)
            {
                prevLength = MIN_MATCH - 1;
                matchLength = longestMatch(hashHead);
            }
            boolean bflush;
            if (matchLength >= MIN_MATCH)
            {
                bflush = tallyDist(strstart - matchStart, matchLength - MIN_MATCH);
                lookahead -= matchLength;
                if (matchLength <= maxLazy && lookahead >= MIN_MATCH)
                {
                    matchLength -= 1;
                    while (matchLength != 0)
                    {
                        strstart += 1;
                        insert(strstart);
                        matchLength -= 1;
                    }
                    strstart += 1;
                }
                else
                {
                    strstart += matchLength;
                    matchLength = 0;
                }
            }
            else
            {
                bflush = tallyLit(window[strstart] & 0xFF);
                lookahead -= 1;
                strstart += 1;
            }
            if (bflush)
            {
                flushBlock(false);
            }
        }
    }

    private boolean compressSlow(int flush)
    {
        while (true)
        {
            if (lookahead < MIN_LOOKAHEAD)
            {
                fillWindow();
                if (lookahead < MIN_LOOKAHEAD && flush == NO_FLUSH)
                {
                    return false;
                }
                if (lookahead == 0)
                {
                    break;
                }
            }
            int hashHead = NIL;
            if (lookahead >= MIN_MATCH)
            {
                hashHead = insert(strstart);
            }
            prevLength = matchLength;
            prevMatch = matchStart;
            matchLength = MIN_MATCH - 1;
            if (hashHead != NIL && prevLength < maxLazy && strstart - hashHead <= MAX_DIST)
            {
                matchLength = longestMatch(hashHead);
                if (matchLength <= 5 && (strategy == FILTERED
                        || (matchLength == MIN_MATCH && strstart - matchStart > TOO_FAR)))
                {
                    matchLength = MIN_MATCH - 1;
                }
            }
            if (prevLength >= MIN_MATCH && matchLength <= prevLength)
            {
                int maxInsert = strstart + lookahead - MIN_MATCH;
                boolean bflush = tallyDist(strstart - 1 - prevMatch, prevLength - MIN_MATCH);
                lookahead -= prevLength - 1;
                prevLength -= 2;
                while (prevLength != 0)
                {
                    strstart += 1;
                    if (strstart <= maxInsert)
                    {
                        insert(strstart);
                    }
                    prevLength -= 1;
                }
                matchAvailable = false;
                matchLength = MIN_MATCH - 1;
                strstart += 1;
                if (bflush)
                {
                    flushBlock(false);
                }
            }
            else if (matchAvailable)
            {
                if (tallyLit(window[strstart - 1] & 0xFF))
                {
                    flushBlock(false);
                }
                strstart += 1;
                lookahead -= 1;
            }
            else
            {
                matchAvailable = true;
                strstart += 1;
                lookahead -= 1;
            }
        }
        if (matchAvailable)
        {
            tallyLit(window[strstart - 1] & 0xFF);
            matchAvailable = false;
        }
        return true;
    }

    // ---------------------------------------------------------------- blocks

    private void initBlock()
    {
        int i = 0;
        while (i < litFreq.length)
        {
            litFreq[i] = 0;
            i += 1;
        }
        i = 0;
        while (i < distFreq.length)
        {
            distFreq[i] = 0;
            i += 1;
        }
        litFreq[256] = 1;                               // the end-of-block code is always sent
        symNext = 0;
    }

    private boolean tallyLit(int c)
    {
        symLit[symNext] = c;
        symDist[symNext] = 0;
        symNext += 1;
        litFreq[c] += 1;
        return symNext == LIT_BUFSIZE - 1;
    }

    /** A match of distance {@code dist} and length {@code lc + 3}; true when the symbol buffer is full. */
    private boolean tallyDist(int dist, int lc)
    {
        symLit[symNext] = lc;
        symDist[symNext] = dist;
        symNext += 1;
        litFreq[257 + lengthCode(lc + 3)] += 1;
        distFreq[distCode(dist)] += 1;
        return symNext == LIT_BUFSIZE - 1;
    }

    private static int lengthCode(int len)
    {
        int c = LEN_BASE.length - 1;
        while (LEN_BASE[c] > len)
        {
            c -= 1;
        }
        return c;
    }

    private static int distCode(int dist)
    {
        int c = DIST_BASE.length - 1;
        while (DIST_BASE[c] > dist)
        {
            c -= 1;
        }
        return c;
    }

    /** End the block: the smallest of stored, fixed and dynamic, as zlib chooses. */
    private void flushBlock(boolean last)
    {
        int storedLen = strstart - blockStart;
        if (level == 0)
        {
            emitStored(last);
            initBlock();
            blockStart = strstart;
            return;
        }
        int[] litLen = codeLengths(litFreq, 286, 15);
        int[] distLen = codeLengths(distFreq, 30, 15);
        int lcodes = 286;
        while (lcodes > 257 && litLen[lcodes - 1] == 0)
        {
            lcodes -= 1;
        }
        int dcodes = 30;
        while (dcodes > 1 && distLen[dcodes - 1] == 0)
        {
            dcodes -= 1;
        }
        int[] all = new int[lcodes + dcodes];
        System.arraycopy(litLen, 0, all, 0, lcodes);
        System.arraycopy(distLen, 0, all, lcodes, dcodes);
        int[] rle = runLengths(all);
        int[] blFreq = new int[19];
        int k = 0;
        while (k < rle.length)
        {
            blFreq[rle[k] & 0xFF] += 1;
            k += 1;
        }
        int[] blLen = codeLengths(blFreq, 19, 7);
        int blcodes = 19;
        while (blcodes > 4 && blLen[BL_ORDER[blcodes - 1]] == 0)
        {
            blcodes -= 1;
        }
        long optBits = 5 + 5 + 4 + 3L * blcodes;
        k = 0;
        while (k < rle.length)
        {
            int sym = rle[k] & 0xFF;
            optBits += blLen[sym] + (sym == 16 ? 2 : sym == 17 ? 3 : sym == 18 ? 7 : 0);
            k += 1;
        }
        long staticBits = 0;
        int s = 0;
        while (s < 286)
        {
            if (litFreq[s] != 0)
            {
                int extra = s >= 257 ? LEN_EXTRA[s - 257] : 0;
                optBits += (long) litFreq[s] * (litLen[s] + extra);
                staticBits += (long) litFreq[s] * (fixedLitLen(s) + extra);
            }
            s += 1;
        }
        s = 0;
        while (s < 30)
        {
            if (distFreq[s] != 0)
            {
                optBits += (long) distFreq[s] * (distLen[s] + DIST_EXTRA[s]);
                staticBits += (long) distFreq[s] * (5 + DIST_EXTRA[s]);
            }
            s += 1;
        }
        long optLenb = (optBits + 3 + 7) >> 3;
        long staticLenb = (staticBits + 3 + 7) >> 3;
        if (staticLenb <= optLenb)
        {
            optLenb = staticLenb;
        }
        if (storedLen + 4 <= optLenb && blockStart >= 0 && storedLen <= 65535)
        {
            emitStored(last);
        }
        else if (staticLenb == optLenb)
        {
            sendBits(2 + (last ? 1 : 0), 3);
            emitSymbols(FIXED_LIT_CODE, FIXED_LIT_LEN, FIXED_DIST_CODE, FIXED_DIST_LEN);
        }
        else
        {
            sendBits(4 + (last ? 1 : 0), 3);
            sendBits(lcodes - 257, 5);
            sendBits(dcodes - 1, 5);
            sendBits(blcodes - 4, 4);
            int[] blCode = codes(blLen);
            int i = 0;
            while (i < blcodes)
            {
                sendBits(blLen[BL_ORDER[i]], 3);
                i += 1;
            }
            k = 0;
            while (k < rle.length)
            {
                int sym = rle[k] & 0xFF;
                int rep = rle[k] >>> 8;
                sendBits(blCode[sym], blLen[sym]);
                if (sym == 16)
                {
                    sendBits(rep - 3, 2);
                }
                else if (sym == 17)
                {
                    sendBits(rep - 3, 3);
                }
                else if (sym == 18)
                {
                    sendBits(rep - 11, 7);
                }
                k += 1;
            }
            emitSymbols(codes(litLen), litLen, codes(distLen), distLen);
        }
        initBlock();
        blockStart = strstart;
    }

    /** The block's bytes as stored blocks of at most 65535, straight from the window. */
    private void emitStored(boolean last)
    {
        int from = blockStart;
        int left = strstart - blockStart;
        do
        {
            int n = left > 65535 ? 65535 : left;
            left -= n;
            sendBits((last && left == 0) ? 1 : 0, 3);
            biWindup();
            putByte(n & 0xFF);
            putByte(n >>> 8);
            putByte(~n & 0xFF);
            putByte((~n >>> 8) & 0xFF);
            int i = 0;
            while (i < n)
            {
                putByte(window[from + i] & 0xFF);
                i += 1;
            }
            from += n;
        }
        while (left > 0);
    }

    private void emitSymbols(int[] lc, int[] ll, int[] dc, int[] dl)
    {
        int i = 0;
        while (i < symNext)
        {
            int dist = symDist[i];
            if (dist == 0)
            {
                int c = symLit[i];
                sendBits(lc[c], ll[c]);
            }
            else
            {
                int len = symLit[i] + 3;
                int code = lengthCode(len);
                sendBits(lc[257 + code], ll[257 + code]);
                if (LEN_EXTRA[code] != 0)
                {
                    sendBits(len - LEN_BASE[code], LEN_EXTRA[code]);
                }
                int d = distCode(dist);
                sendBits(dc[d], dl[d]);
                if (DIST_EXTRA[d] != 0)
                {
                    sendBits(dist - DIST_BASE[d], DIST_EXTRA[d]);
                }
            }
            i += 1;
        }
        sendBits(lc[256], ll[256]);
    }

    // ---------------------------------------------------------------- Huffman codes

    private static final int[] FIXED_LIT_LEN = new int[288];
    private static final int[] FIXED_DIST_LEN = new int[30];
    private static final int[] FIXED_LIT_CODE;
    private static final int[] FIXED_DIST_CODE;

    static
    {
        int i = 0;
        while (i < 288)
        {
            FIXED_LIT_LEN[i] = fixedLitLen(i);
            i += 1;
        }
        i = 0;
        while (i < 30)
        {
            FIXED_DIST_LEN[i] = 5;
            i += 1;
        }
        FIXED_LIT_CODE = codes(FIXED_LIT_LEN);
        FIXED_DIST_CODE = codes(FIXED_DIST_LEN);
    }

    private static int fixedLitLen(int s)
    {
        return s < 144 ? 8 : s < 256 ? 9 : s < 280 ? 7 : 8;
    }

    /** Canonical codes for {@code len}, bit-reversed for the LSB-first writer. */
    private static int[] codes(int[] len)
    {
        int[] count = new int[16];
        int i = 0;
        while (i < len.length)
        {
            count[len[i]] += 1;
            i += 1;
        }
        count[0] = 0;
        int[] next = new int[16];
        int code = 0;
        int b = 1;
        while (b < 16)
        {
            code = (code + count[b - 1]) << 1;
            next[b] = code;
            b += 1;
        }
        int[] out = new int[len.length];
        i = 0;
        while (i < len.length)
        {
            int l = len[i];
            if (l != 0)
            {
                int c = next[l];
                next[l] += 1;
                int r = 0;
                int j = 0;
                while (j < l)
                {
                    r = (r << 1) | (c & 1);
                    c >>>= 1;
                    j += 1;
                }
                out[i] = r;
            }
            i += 1;
        }
        return out;
    }

    /**
     * Huffman code lengths for {@code freq[0..n)}, none longer than {@code maxBits}. At least two codes get a length
     * (DEFLATE needs one distance code and one bit for a lone code); lengths over the limit are folded back the way
     * miniz's {@code tdefl_huffman_enforce_max_code_size} does, then handed out longest-to-rarest.
     */
    private static int[] codeLengths(int[] freqIn, int n, int maxBits)
    {
        int[] freq = new int[n];
        System.arraycopy(freqIn, 0, freq, 0, n);
        int used = 0;
        int i = 0;
        while (i < n)
        {
            if (freq[i] != 0)
            {
                used += 1;
            }
            i += 1;
        }
        i = 0;
        while (used < 2 && i < n)
        {
            if (freq[i] == 0)
            {
                freq[i] = 1;
                used += 1;
            }
            i += 1;
        }
        int[] sym = new int[used];
        int m = 0;
        i = 0;
        while (i < n)
        {
            if (freq[i] != 0)
            {
                sym[m] = i;
                m += 1;
            }
            i += 1;
        }
        // sort by frequency ascending (insertion sort; n <= 286), ties by symbol
        i = 1;
        while (i < used)
        {
            int s = sym[i];
            int j = i - 1;
            while (j >= 0 && (freq[sym[j]] > freq[s] || (freq[sym[j]] == freq[s] && sym[j] > s)))
            {
                sym[j + 1] = sym[j];
                j -= 1;
            }
            sym[j + 1] = s;
            i += 1;
        }
        // two-queue Huffman: leaves in sym order, internal nodes appended in creation order
        long[] w = new long[2 * used];
        int[] parent = new int[2 * used];
        i = 0;
        while (i < used)
        {
            w[i] = freq[sym[i]];
            i += 1;
        }
        int leaf = 0;
        int node = used;
        int nodeNext = used;
        while (nodeNext < 2 * used - 1)
        {
            int a;
            int b;
            if (leaf < used && (node >= nodeNext || w[leaf] <= w[node]))
            {
                a = leaf;
                leaf += 1;
            }
            else
            {
                a = node;
                node += 1;
            }
            if (leaf < used && (node >= nodeNext || w[leaf] <= w[node]))
            {
                b = leaf;
                leaf += 1;
            }
            else
            {
                b = node;
                node += 1;
            }
            w[nodeNext] = w[a] + w[b];
            parent[a] = nodeNext;
            parent[b] = nodeNext;
            nodeNext += 1;
        }
        int root = 2 * used - 2;
        int[] depth = new int[2 * used];
        i = root - 1;
        while (i >= 0)
        {
            depth[i] = depth[parent[i]] + 1;
            i -= 1;
        }
        int[] blCount = new int[33];
        i = 0;
        while (i < used)
        {
            int d = depth[i] > 32 ? 32 : depth[i];
            blCount[d] += 1;
            i += 1;
        }
        // fold lengths over maxBits back under it (Kraft sum restored to exactly 1)
        int over = 0;
        int d = maxBits + 1;
        while (d <= 32)
        {
            over += blCount[d];
            blCount[d] = 0;
            d += 1;
        }
        blCount[maxBits] += over;
        long total = 0;
        d = 1;
        while (d <= maxBits)
        {
            total += ((long) blCount[d]) << (maxBits - d);
            d += 1;
        }
        while (total != (1L << maxBits))
        {
            blCount[maxBits] -= 1;
            d = maxBits - 1;
            while (d > 0)
            {
                if (blCount[d] != 0)
                {
                    blCount[d] -= 1;
                    blCount[d + 1] += 2;
                    break;
                }
                d -= 1;
            }
            total -= 1;
        }
        // longest lengths to the rarest symbols (sym[] is ascending by frequency)
        int[] len = new int[n];
        int at = 0;
        d = maxBits;
        while (d >= 1)
        {
            int c = blCount[d];
            while (c > 0)
            {
                len[sym[at]] = d;
                at += 1;
                c -= 1;
            }
            d -= 1;
        }
        return len;
    }

    /** The code-length sequence as code-length-alphabet symbols, each {@code sym | repeat << 8}. */
    private static int[] runLengths(int[] lens)
    {
        int[] out = new int[lens.length];
        int n = 0;
        int i = 0;
        while (i < lens.length)
        {
            int l = lens[i];
            int run = 1;
            while (i + run < lens.length && lens[i + run] == l)
            {
                run += 1;
            }
            if (l == 0)
            {
                int left = run;
                while (left > 0)
                {
                    if (left >= 11)
                    {
                        int r = left > 138 ? 138 : left;
                        out[n] = 18 | (r << 8);
                        n += 1;
                        left -= r;
                    }
                    else if (left >= 3)
                    {
                        out[n] = 17 | (left << 8);
                        n += 1;
                        left = 0;
                    }
                    else
                    {
                        out[n] = 0;
                        n += 1;
                        left -= 1;
                    }
                }
            }
            else
            {
                out[n] = l;
                n += 1;
                int left = run - 1;
                while (left > 0)
                {
                    if (left >= 3)
                    {
                        int r = left > 6 ? 6 : left;
                        out[n] = 16 | (r << 8);
                        n += 1;
                        left -= r;
                    }
                    else
                    {
                        out[n] = l;
                        n += 1;
                        left -= 1;
                    }
                }
            }
            i += run;
        }
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    // ---------------------------------------------------------------- bits and pending output

    private void sendBits(int value, int length)
    {
        biBuf |= value << biValid;
        biValid += length;
        while (biValid >= 8)
        {
            putByte(biBuf & 0xFF);
            biBuf >>>= 8;
            biValid -= 8;
        }
    }

    private void biWindup()
    {
        if (biValid > 0)
        {
            putByte(biBuf & 0xFF);
        }
        biBuf = 0;
        biValid = 0;
    }

    private void putByte(int b)
    {
        if (pendEnd == pend.length)
        {
            if (pendStart > 0)
            {
                System.arraycopy(pend, pendStart, pend, 0, pendEnd - pendStart);
                pendEnd -= pendStart;
                pendStart = 0;
            }
            if (pendEnd == pend.length)
            {
                byte[] bigger = new byte[pend.length * 2];
                System.arraycopy(pend, 0, bigger, 0, pendEnd);
                pend = bigger;
            }
        }
        pend[pendEnd] = (byte) b;
        pendEnd += 1;
    }

    private void putShortMsb(int v)
    {
        putByte((v >>> 8) & 0xFF);
        putByte(v & 0xFF);
    }

    private int flushPending(byte[] out, int op, int oend)
    {
        int n = pendEnd - pendStart;
        if (n > oend - op)
        {
            n = oend - op;
        }
        System.arraycopy(pend, pendStart, out, op, n);
        pendStart += n;
        if (pendStart == pendEnd)
        {
            pendStart = 0;
            pendEnd = 0;
        }
        return op + n;
    }
}
