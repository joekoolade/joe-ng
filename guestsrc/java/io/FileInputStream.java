package java.io;

import magic.Magic;

/**
 * A JDK-free {@code java/io/FileInputStream} overlay (wins by name) reading from the image's embedded
 * read-only RAMFS (M3). The stock class is a wall of natives — {@code open0}/{@code read0}/{@code readBytes}/
 * {@code length0}/{@code position0}/{@code skip0}/{@code available0}/{@code initIDs} — over an OS file
 * descriptor that doesn't exist on metal. Here a single native remains: {@code open0(String)} ->
 * {@code VM.fileOpen} (wired in {@code Loader.nativeBuf}) resolves the path against the writer-baked file
 * table and returns the directory entry address {nameAddr, nameLen, bytesAddr@+16, bytesLen@+24}; every
 * read then walks the content bytes directly via {@code Magic.load8/load64} — pure Java, no descriptor.
 *
 * <p>Read-only and always "regular": no {@code write}, no {@code File}/{@code FileDescriptor} objects. It
 * DOES extend the stock {@code java.io.InputStream}, so it can be handed to anything that takes one — which
 * is what lets {@code new ZipInputStream(new FileInputStream("/lib/app.jar"))} work with the stock zip
 * classes. (The stock super is small and its unreached methods never compile, so the closure cost is nil.)
 */
public class FileInputStream extends InputStream
{
    private long entry;                                  // RAMFS dir entry, 0 = closed/absent
    private int pos;                                     // read cursor into the content bytes

    public FileInputStream(String name) throws FileNotFoundException
    {
        entry = open0(name);
        if (entry == 0L)
        {
            throw new FileNotFoundException(name);
        }
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.fileOpen}): path -> file-table entry addr, or 0. */
    private static native long open0(String name);

    /** The next content byte (0..255), or -1 at end of file. */
    @Override
    public int read()
    {
        if (entry == 0L || pos >= (int) Magic.load64(entry + 24L))
        {
            return -1;
        }
        int b = Magic.load8(Magic.load64(entry + 16L) + pos) & 0xff;
        pos += 1;
        return b;
    }

    @Override
    public int read(byte[] buf)
    {
        return read(buf, 0, buf.length);
    }

    /** Fill {@code buf[off..off+len)} from the cursor; the count actually read, or -1 at end of file. */
    @Override
    public int read(byte[] buf, int off, int len)
    {
        if (entry == 0L)
        {
            return -1;
        }
        int remain = (int) Magic.load64(entry + 24L) - pos;
        if (remain <= 0)
        {
            return -1;
        }
        int n = len < remain ? len : remain;
        long bytes = Magic.load64(entry + 16L);
        int i = 0;
        while (i < n)
        {
            buf[off + i] = (byte) Magic.load8(bytes + pos + i);
            i += 1;
        }
        pos += n;
        return n;
    }

    /** Bytes remaining from the cursor to end of file. */
    @Override
    public int available()
    {
        if (entry == 0L)
        {
            return 0;
        }
        int remain = (int) Magic.load64(entry + 24L) - pos;
        return remain > 0 ? remain : 0;
    }

    /** The rest of the file as a fresh array (the whole file when nothing has been read yet). */
    @Override
    public byte[] readAllBytes()
    {
        int n = available();
        byte[] all = new byte[n];
        if (n > 0)
        {
            int unused = read(all, 0, n);
        }
        return all;
    }

    /** Advance the cursor up to {@code n} bytes; the count actually skipped. */
    @Override
    public long skip(long n)
    {
        int step = (int) n;
        int remain = available();
        if (step > remain)
        {
            step = remain;
        }
        if (step < 0)
        {
            step = 0;
        }
        pos += step;
        return step;
    }

    @Override
    public void close()
    {
        entry = 0L;
    }
}
