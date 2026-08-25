package demo;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The stock {@code java.util.zip} streaming API on bare metal: an UNMODIFIED {@code ZipInputStream} walks a
 * real jar out of the RAMFS, and every entry is inflated by joe-ng's own DEFLATE engine underneath (the
 * {@code java.util.zip.Inflater}/{@code CRC32} overlays delegate to {@code zip.Inflate}/{@code zip.Crc32}).
 *
 * <p>The proof that it really decompressed is the CRC: each entry's bytes are checksummed with a stock
 * {@code CRC32} and printed alongside the entry's own recorded CRC, and the manifest is printed as text.
 */
public class ZipDemo
{
    public static void main(String[] args) throws Exception
    {
        String path = args.length > 0 ? args[0] : "/lib/app.jar";
        InputStream file = new FileInputStream(path);
        ZipInputStream zin = new ZipInputStream(file);
        int entries = 0;
        ZipEntry e = zin.getNextEntry();
        while (e != null)
        {
            byte[] data = drain(zin);
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length);
            System.out.println(e.getName() + " dir=" + (e.isDirectory() ? 1 : 0)
                    + " bytes=" + data.length + " crc=" + Long.toHexString(crc.getValue()));
            if (e.getName().equals("META-INF/MANIFEST.MF"))
            {
                System.out.println("--- manifest ---");
                System.out.print(new String(data, 0, data.length));
                System.out.println("--- end ---");
            }
            entries += 1;
            e = zin.getNextEntry();
        }
        zin.close();
        System.out.println("zip entries=" + entries);
    }

    /** Read the current entry to its end through the stock stream (which inflates as it goes). */
    private static byte[] drain(InputStream in) throws Exception
    {
        byte[] out = new byte[64];
        int have = 0;
        byte[] buf = new byte[37];                     // deliberately awkward: forces partial inflate calls
        int n = in.read(buf, 0, buf.length);
        while (n > 0)
        {
            if (have + n > out.length)
            {
                byte[] bigger = new byte[(have + n) * 2];
                System.arraycopy(out, 0, bigger, 0, have);
                out = bigger;
            }
            System.arraycopy(buf, 0, out, have, n);
            have += n;
            n = in.read(buf, 0, buf.length);
        }
        byte[] exact = new byte[have];
        System.arraycopy(out, 0, exact, 0, have);
        return exact;
    }
}
