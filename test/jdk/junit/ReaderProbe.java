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
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * TEXT I/O THROUGH CHARSETS: reading a file line by line ({@code BufferedReader} over {@code InputStreamReader} /
 * {@code FileReader}), writing through {@code OutputStreamWriter}, and a non-UTF-8 charset (UTF-16) both ways. Every
 * Reader/Writer bridge decodes through {@code sun.nio.cs.StreamDecoder}/{@code StreamEncoder} and a
 * {@code CharsetDecoder}/{@code CharsetEncoder}, which joe-ng denied -- only {@code String}'s UTF-8 fast path worked.
 *
 * <p>Reads the RAMFS file {@code /data/sample.txt}; on a host JVM pass the same file's repo path
 * ({@code ramfs/data/sample.txt}) as the argument, so the host diff is the gate. (Not {@code File.exists}: java.io.File
 * metadata over the RAMFS is a separate gap.)
 */
public class ReaderProbe
{
    public static void main(String[] args) throws Exception
    {
        String path = args.length > 0 ? args[0] : "/data/sample.txt";      // host: pass ramfs/data/sample.txt
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8));
        int lines = 0;
        int chars = 0;
        String first = null;
        String line = br.readLine();
        while (line != null)
        {
            if (first == null)
            {
                first = line;
            }
            lines += 1;
            chars += line.length();
            line = br.readLine();
        }
        br.close();
        System.out.println("BufferedReader(InputStreamReader): lines=" + lines + " chars=" + chars + " first=" + first);
        BufferedReader fr = new BufferedReader(new FileReader(path));
        System.out.println("FileReader lines=" + fr.lines().count());
        fr.close();

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Writer w = new OutputStreamWriter(bout, StandardCharsets.UTF_8);
        w.write("café €\n");
        w.close();
        System.out.println("OutputStreamWriter UTF-8 bytes=" + Arrays.toString(bout.toByteArray()));

        byte[] u16 = "Hi€".getBytes(StandardCharsets.UTF_16BE);
        System.out.println("getBytes(UTF_16BE)=" + Arrays.toString(u16) + " back=" + new String(u16, StandardCharsets.UTF_16BE));
        BufferedReader r16 = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(
                "one\ntwo\n".getBytes(StandardCharsets.UTF_16LE)), StandardCharsets.UTF_16LE));
        System.out.println("UTF-16LE reader -> " + r16.readLine() + "," + r16.readLine() + "," + r16.readLine());

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.printf("%d-%s%n", 7, "x");
        pw.flush();
        System.out.println("PrintWriter(StringWriter) -> " + sw.toString().trim());
        System.out.println("ReaderProbe done");
    }
}
