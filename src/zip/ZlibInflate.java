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
 * zlib's {@code inflate()} contract over {@link Inflate}: what stock {@code java.util.zip.Inflater}'s natives
 * ({@code Inflater.c}) need from a {@code z_stream}. The caller OWNS its input and passes the unconsumed part again
 * each call ({@code next_in}/{@code avail_in}), so every call reports exactly how many input bytes it consumed
 * ({@link #inUsed}) and how many it produced ({@link #outUsed}) -- GZIPInputStream and ZipInputStream read what
 * follows a stream out of that remainder.
 *
 * <p>With {@code nowrap} false this is the zlib FORMAT (RFC 1950) around the DEFLATE body: the two-byte header with
 * zlib's checks and messages, an FDICT dictionary id (answered as {@link #NEED_DICT} until {@link #setDictionary}),
 * and the Adler-32 trailer, checked. {@link #adler} is {@code strm->adler}: 0 for a raw stream, 1 after a zlib
 * header, the dictionary id while one is needed, the running checksum of the output otherwise.
 */
public final class ZlibInflate
{
    public static final int OK = 0;
    public static final int STREAM_END = 1;
    public static final int NEED_DICT = 2;
    public static final int DATA_ERROR = 3;
    public static final int STREAM_ERROR = 4;

    private static final int HEAD = 0;
    private static final int DICTID = 1;
    private static final int DICT = 2;
    private static final int BODY = 3;
    private static final int CHECK = 4;
    private static final int DONE = 5;
    private static final int BAD = 6;

    private final boolean wrap;
    private final Inflate body = new Inflate();
    private int mode;
    private int hold;                                   // header / dictionary id / trailer bytes gathered so far
    private int have;
    private int adler;
    private boolean haveDict;

    /** Input bytes the last {@link #inflate} consumed. */
    public int inUsed;

    /** Output bytes the last {@link #inflate} produced. */
    public int outUsed;

    /** zlib's {@code strm->msg} after a {@link #DATA_ERROR}, else null. */
    public String msg;

    public ZlibInflate(boolean nowrap)
    {
        wrap = !nowrap;
        reset();
    }

    /** {@code inflateReset}. */
    public void reset()
    {
        body.reset();
        mode = wrap ? HEAD : BODY;
        hold = 0;
        have = 0;
        adler = wrap ? 1 : 0;
        haveDict = false;
        msg = null;
    }

    /** {@code strm->adler}. */
    public int adler()
    {
        return adler;
    }

    /**
     * One {@code inflate(strm, Z_PARTIAL_FLUSH)} over {@code in[inOff..+inLen)} into {@code out[outOff..+outLen)}:
     * {@link #OK}, {@link #STREAM_END}, {@link #NEED_DICT} or {@link #DATA_ERROR} (with {@link #msg}); the counts
     * are in {@link #inUsed} / {@link #outUsed} whatever the answer.
     */
    public int inflate(byte[] in, int inOff, int inLen, byte[] out, int outOff, int outLen)
    {
        int ip = inOff;
        int iend = inOff + inLen;
        int op = outOff;
        int oend = outOff + outLen;
        int ret = OK;
        boolean going = true;
        while (going)
        {
            if (mode == HEAD || mode == DICTID || mode == CHECK)
            {
                int want = mode == HEAD ? 2 : 4;
                while (have < want && ip < iend)
                {
                    hold = (hold << 8) | (in[ip] & 0xFF);
                    ip += 1;
                    have += 1;
                }
                if (have < want)
                {
                    going = false;
                }
                else if (mode == HEAD)
                {
                    headerDone();
                }
                else if (mode == DICTID)
                {
                    adler = hold;                       // strm->adler = the dictionary's id while it is needed
                    hold = 0;
                    have = 0;
                    mode = DICT;
                }
                else
                {
                    if (hold != adler)
                    {
                        bad("incorrect data check");
                    }
                    else
                    {
                        mode = DONE;
                    }
                }
            }
            else if (mode == DICT)
            {
                if (!haveDict)
                {
                    ret = NEED_DICT;
                    going = false;
                }
                else
                {
                    adler = 1;                          // zlib: strm->adler = adler32(0, NULL, 0) and on to TYPE
                    mode = BODY;
                }
            }
            else if (mode == BODY)
            {
                body.feed(in, ip, iend - ip);
                int n = body.inflate(out, op, oend - op);
                if (wrap && n > 0)
                {
                    adler = Adler32.update(adler, out, op, n);
                }
                op += n;
                boolean starved = !body.finished() && !body.failed() && op < oend;
                int back = body.release(starved ? 0 : iend - ip);   // starved: zlib keeps the partial symbol
                ip = iend - back;
                if (body.failed())
                {
                    bad(body.error());
                }
                else if (body.finished())
                {
                    mode = wrap ? CHECK : DONE;         // the trailer starts at the next byte boundary
                }
                else
                {
                    going = false;                      // output full, or more input needed
                }
            }
            else if (mode == DONE)
            {
                ret = STREAM_END;
                going = false;
            }
            else
            {
                ret = DATA_ERROR;
                going = false;
            }
        }
        inUsed = ip - inOff;
        outUsed = op - outOff;
        return ret;
    }

    /** CMF/FLG in {@code hold}: zlib's three header checks, in its order and with its messages. */
    private void headerDone()
    {
        int cmf = (hold >> 8) & 0xFF;
        int flg = hold & 0xFF;
        hold = 0;
        have = 0;
        if (((cmf << 8) + flg) % 31 != 0)
        {
            bad("incorrect header check");
            return;
        }
        if ((cmf & 0x0F) != 8)
        {
            bad("unknown compression method");
            return;
        }
        if ((cmf >> 4) + 8 > 15)
        {
            bad("invalid window size");
            return;
        }
        adler = 1;
        mode = (flg & 0x20) != 0 ? DICTID : BODY;
    }

    private void bad(String why)
    {
        msg = why;
        mode = BAD;
    }

    /**
     * {@code inflateSetDictionary}: {@link #STREAM_ERROR} for a zlib stream not waiting for one, {@link #DATA_ERROR}
     * when its Adler-32 is not the id the stream asked for, else {@link #OK} with the window preloaded.
     */
    public int setDictionary(byte[] d, int off, int len)
    {
        if (wrap && mode != DICT)
        {
            return STREAM_ERROR;
        }
        if (mode == DICT && Adler32.of(d, off, len) != adler)
        {
            return DATA_ERROR;
        }
        body.setDictionary(d, off, len);
        haveDict = true;
        return OK;
    }
}
