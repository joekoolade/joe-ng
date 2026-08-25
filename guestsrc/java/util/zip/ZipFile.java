package java.util.zip;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.NoSuchElementException;

import zip.ZipDir;

/**
 * A JDK-free {@code java.util.zip.ZipFile} overlay (wins by name) — the RANDOM-ACCESS half of the zip API,
 * where {@code ZipInputStream} is the streaming half. The stock class cannot run here: it reaches its entries
 * through {@code RandomAccessFile}, a {@code java.nio.file} attribute-keyed cache, {@code PerfCounter} and the
 * native {@code zip_util}, and its signature path pulls {@code sun.security}. This overlay keeps the API and
 * reads the archive with {@link zip.ZipDir} — the same central-directory reader the on-metal class loader uses
 * for {@code classpath=} jars.
 *
 * <p>The whole archive is read from the RAMFS into memory at construction (there is no seekable file layer to
 * be lazy over), and {@link #getInputStream} hands back the entry's already-inflated bytes. Entries are
 * populated into stock {@link ZipEntry} objects, so everything above this — {@code JarFile},
 * {@code Manifest}, user code — is the unmodified API.
 *
 * <p>Read-only: no writing, no {@code File}/{@code Charset} constructors, no signature verification.
 */
public class ZipFile implements Closeable
{
    /** Mode flag kept for API compatibility; this overlay is always read-only. */
    public static final int OPEN_READ = 0x1;

    /** Delete-on-close is meaningless for a read-only RAMFS; the constant exists so callers still compile. */
    public static final int OPEN_DELETE = 0x4;

    private final String name;
    private ZipDir dir;

    public ZipFile(String name) throws IOException
    {
        this.name = name;
        byte[] raw = readFile(name);
        ZipDir d = new ZipDir();
        if (!d.open(raw))
        {
            throw new ZipException("not a readable zip: " + name);
        }
        dir = d;
    }

    public ZipFile(String name, int mode) throws IOException
    {
        this(name);
    }

    /** Read a whole RAMFS file into a byte array through the {@code FileInputStream} overlay's native. */
    private static byte[] readFile(String path) throws IOException
    {
        java.io.FileInputStream in = new java.io.FileInputStream(path);
        try
        {
            return in.readAllBytes();
        }
        finally
        {
            in.close();
        }
    }

    /** The archive's path, as it was opened. */
    public String getName()
    {
        return name;
    }

    /** How many entries the archive holds. */
    public int size()
    {
        ensureOpen();
        return dir.count();
    }

    /** The entry named {@code entryName}, or null. A directory may be named with or without its '/'. */
    public ZipEntry getEntry(String entryName)
    {
        ensureOpen();
        int i = indexOf(entryName);
        return i < 0 ? null : entryAt(i);
    }

    /** The entry's uncompressed bytes as a stream, or null if this file has no such entry. */
    public InputStream getInputStream(ZipEntry entry) throws IOException
    {
        ensureOpen();
        int i = indexOf(entry.getName());
        if (i < 0)
        {
            return null;
        }
        byte[] data = dir.read(i);
        if (data == null)
        {
            if (dir.isDirectory(i))
            {
                return new ByteArrayInputStream(new byte[0]);
            }
            throw new ZipException("cannot read entry: " + entry.getName());
        }
        return new ByteArrayInputStream(data);
    }

    /** Every entry, in central-directory order. */
    public Enumeration<? extends ZipEntry> entries()
    {
        ensureOpen();
        return new Entries(this);
    }

    @Override
    public void close()
    {
        dir = null;
    }

    // ---------------------------------------------------------------- internals

    /** The central-directory index of {@code entryName}, trying the directory form too; -1 if absent. */
    private int indexOf(String entryName)
    {
        int i = dir.find(ascii(entryName));
        if (i < 0 && !entryName.endsWith("/"))
        {
            i = dir.find(ascii(entryName + "/"));
        }
        return i;
    }

    /** Build a stock {@link ZipEntry} for index {@code i}, filling the fields the archive records. */
    ZipEntry entryAt(int i)
    {
        ZipEntry e = new ZipEntry(new String(dir.name(i)));
        e.method = dir.method(i);                        // package-private fields: this overlay IS java.util.zip
        e.size = dir.size(i) & 0xFFFFFFFFL;
        e.csize = dir.csize(i) & 0xFFFFFFFFL;
        e.crc = dir.crc(i) & 0xFFFFFFFFL;
        e.xdostime = dir.dosTime(i) & 0xFFFFFFFFL;
        return e;
    }

    private void ensureOpen()
    {
        if (dir == null)
        {
            throw new IllegalStateException("zip file closed");
        }
    }

    /** Entry names are ASCII/UTF-8 bytes in the archive; encode without pulling a charset encoder. */
    private static byte[] ascii(String s)
    {
        return s.getBytes();
    }

    /** The {@link #entries} cursor — a named class rather than an anonymous one, for a readable stack trace. */
    private static final class Entries implements Enumeration<ZipEntry>
    {
        private final ZipFile file;
        private int at;

        Entries(ZipFile file)
        {
            this.file = file;
        }

        @Override
        public boolean hasMoreElements()
        {
            return file.dir != null && at < file.dir.count();
        }

        @Override
        public ZipEntry nextElement()
        {
            if (!hasMoreElements())
            {
                throw new NoSuchElementException();
            }
            ZipEntry e = file.entryAt(at);
            at += 1;
            return e;
        }
    }
}
