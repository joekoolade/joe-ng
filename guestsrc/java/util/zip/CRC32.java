package java.util.zip;

import zip.Crc32;

/**
 * A JDK-free {@code java.util.zip.CRC32} overlay (wins by name). The stock class is three natives — intrinsics
 * over the CPU's CRC instructions — with no bytecode to compile on metal. This overlay keeps the API and
 * delegates to {@link zip.Crc32}, the table-driven implementation the image-baked zip reader also uses, so
 * the UNMODIFIED stock {@code ZipInputStream}/{@code JarInputStream} verify entries exactly as on a hosted JVM.
 */
public class CRC32 implements Checksum
{
    private int crc;

    public CRC32()
    {
    }

    @Override
    public void update(int b)
    {
        crc = Crc32.updateByte(crc, b & 0xFF);
    }

    /** Overridden rather than inherited: {@code Checksum}'s version is a default method. */
    @Override
    public void update(byte[] b)
    {
        update(b, 0, b.length);
    }

    @Override
    public void update(byte[] b, int off, int len)
    {
        crc = Crc32.update(crc, b, off, len);
    }

    @Override
    public long getValue()
    {
        return crc & 0xFFFFFFFFL;
    }

    @Override
    public void reset()
    {
        crc = 0;
    }
}
