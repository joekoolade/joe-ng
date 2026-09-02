package sun.nio.cs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

/**
 * A JDK-free {@code sun.nio.cs.StreamEncoder}: the char sink behind {@code OutputStreamWriter} and
 * {@code PrintWriter}, which is how JUnit's console output reaches a stream.
 *
 * <p>Overlaid because the stock class is a shell over the {@code CharsetEncoder} runtime -- its
 * {@code forOutputStreamWriter} calls {@code charset.newEncoder()}, and joe-ng's {@code Charset}/{@code UTF_8}
 * overlays are IDENTITY TOKENS with no encoder behind them (the stock String fast paths only ever compare
 * {@code charset == UTF_8.INSTANCE}). Supplying a real CharsetEncoder would mean CharBuffer, ByteBuffer,
 * CoderResult and the whole nio coder protocol.
 *
 * <p>None of that is needed, because <b>joe-ng already encodes UTF-8 correctly</b>: {@code String.getBytes()}
 * is the stock UTF-8 fast path and is what {@code PrintStream} has always used. This routes the same way, so
 * a Writer and a PrintStream produce identical bytes for identical text rather than two encoders that might
 * disagree.
 *
 * <p>UNBUFFERED, deliberately. Stock buffers into an 8 KiB ByteBuffer and flushes on demand; here every write
 * goes straight to the stream, so {@code flush}/{@code flushBuffer} have nothing to do. That trades throughput
 * for having no buffered state to lose -- and a console writer whose output is stuck in a buffer when the VM
 * halts is a debugging problem this project has paid for elsewhere.
 */
public final class StreamEncoder extends Writer
{
    private final OutputStream out;
    private final String encoding;
    private volatile boolean closed;

    private StreamEncoder(OutputStream out, String encoding)
    {
        this.out = out;
        this.encoding = encoding;
    }

    public static StreamEncoder forOutputStreamWriter(OutputStream out, Object lock, String csn)
    {
        return new StreamEncoder(out, csn == null ? "UTF-8" : csn);
    }

    public static StreamEncoder forOutputStreamWriter(OutputStream out, Object lock, java.nio.charset.Charset cs)
    {
        return new StreamEncoder(out, cs == null ? "UTF-8" : cs.name());
    }

    /** The historical name, as stock reports it. */
    public String getEncoding()
    {
        return closed ? null : encoding;
    }

    @Override
    public void write(int c) throws IOException
    {
        write(new char[] { (char) c }, 0, 1);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException
    {
        write(new String(cbuf, off, len), 0, len);
    }

    @Override
    public void write(String str, int off, int len) throws IOException
    {
        ensureOpen();
        if (len <= 0)
        {
            return;
        }
        byte[] b = str.substring(off, off + len).getBytes();   // the stock UTF-8 fast path
        out.write(b, 0, b.length);
    }

    /** Nothing is buffered here, so both flushes are no-ops beyond the open check (see the class note). */
    public void flushBuffer() throws IOException
    {
        ensureOpen();
    }

    @Override
    public void flush() throws IOException
    {
        ensureOpen();
        out.flush();
    }

    @Override
    public void close() throws IOException
    {
        if (closed)
        {
            return;                                    // idempotent, as Writer requires
        }
        closed = true;
        out.close();
    }

    private void ensureOpen() throws IOException
    {
        if (closed)
        {
            throw new IOException("Stream closed");
        }
    }
}
