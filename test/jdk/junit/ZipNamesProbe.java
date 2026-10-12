/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-11
 */
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP ENTRY NAMES THROUGH STOCK {@code ZipCoder}: an archive written in memory with ASCII, Latin-1 ({@code é}), BMP
 * ({@code €}, CJK) and supplementary-plane names plus a non-ASCII entry comment, then read back through
 * {@code ZipInputStream}. joe-ng's {@code ZipCoder} overlay decoded every name as UTF-8 through {@code String}; stock
 * decodes through {@code JavaLangAccess.uncheckedNewStringOrThrow} and encodes through
 * {@code getBytesUTF8OrThrow}, both of which threw {@code unsupported} until the charsets ran stock. Prints names as
 * UTF-16 code units and the contents' lengths, so the console's encoding cannot hide a wrong decode. The host diff is
 * the gate.
 */
public class ZipNamesProbe
{
    public static void main(String[] args) throws Exception
    {
        String[] names = {"plain.txt", "café.txt", "€/price.txt", "中文.txt",
            "emoji-" + new String(Character.toChars(0x1F600)) + ".txt"};
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ZipOutputStream zout = new ZipOutputStream(bout, StandardCharsets.UTF_8);
        for (int i = 0; i < names.length; i += 1)
        {
            ZipEntry e = new ZipEntry(names[i]);
            // A FIXED time: the archive's bytes depend on it, and the size is part of the gate.
            e.setTime(1577836800000L);                         // 2020-01-01T00:00:00Z
            // STORED, with size and CRC set: joe-ng's Deflater emits stored deflate blocks by design (no zlib), so a
            // DEFLATED archive's size differs from the host's whatever the names do. Stored bytes are identical.
            byte[] body = ("body " + i).getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(body);
            e.setMethod(ZipEntry.STORED);
            e.setSize(body.length);
            e.setCompressedSize(body.length);
            e.setCrc(crc.getValue());
            if (i == 1)
            {
                e.setComment("naïve €");
            }
            zout.putNextEntry(e);
            zout.write(body);
            zout.closeEntry();
        }
        zout.setComment("archive é");
        zout.close();
        byte[] zip = bout.toByteArray();
        System.out.println("archive bytes=" + zip.length);
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8);
        ZipEntry e = zin.getNextEntry();
        int n = 0;
        while (e != null)
        {
            byte[] body = zin.readAllBytes();
            System.out.println("entry " + n + ": units=" + codes(e.getName()) + " body=" + new String(body, StandardCharsets.UTF_8)
                    + " matches=" + e.getName().equals(names[n]));
            n += 1;
            e = zin.getNextEntry();
        }
        zin.close();
        System.out.println("entries=" + n);
        System.out.println("ZipNamesProbe done");
    }

    private static String codes(String s)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 1)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(Integer.toHexString(s.charAt(i)));
        }
        return sb.toString();
    }
}
