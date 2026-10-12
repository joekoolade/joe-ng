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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * STOCK CHARSETS BY IDENTITY: every {@code StandardCharsets} constant must be its own object, named as stock names it,
 * with its own decoder class -- because stock {@code String} picks its decode/encode path by comparing the charset
 * against {@code UTF_8.INSTANCE}, {@code ISO_8859_1.INSTANCE} and {@code US_ASCII.INSTANCE} by IDENTITY, and only
 * otherwise asks for a {@code CharsetDecoder}. A charset that compares equal to the wrong singleton decodes with the
 * wrong algorithm and no error. Each arm decodes and encodes the same text, through {@code String} AND through the
 * charset's own coder, so a wrong fast path and a wrong coder show separately. Prints values only; the host diff is the
 * gate.
 */
public class CharsetIdentityProbe
{
    public static void main(String[] args) throws Exception
    {
        Charset[] all = {StandardCharsets.US_ASCII, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8,
            StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16};
        for (int i = 0; i < all.length; i += 1)
        {
            Charset cs = all[i];
            int same = 0;
            for (int j = 0; j < all.length; j += 1)
            {
                if (all[j] == cs)
                {
                    same += 1;
                }
            }
            CharsetDecoder cd = cs.newDecoder();
            System.out.println(cs.name() + ": identical-to=" + same + " forName-same=" + (Charset.forName(cs.name()) == cs)
                    + " class=" + cs.getClass().getName() + " decoder=" + cd.getClass().getName()
                    + " maxCharsPerByte=" + cd.maxCharsPerByte());
            String text = "Hi\u00e9";
            byte[] enc = text.getBytes(cs);
            String back = new String(enc, cs);
            CharBuffer viaCoder = cs.newDecoder().decode(ByteBuffer.wrap(enc));
            ByteBuffer encCoder = cs.newEncoder().encode(CharBuffer.wrap("Hi"));
            byte[] ec = new byte[encCoder.remaining()];
            encCoder.get(ec);
            System.out.println("| getBytes=" + Arrays.toString(enc) + " String=" + codes(back) + " coder="
                    + codes(viaCoder.toString()) + " encoder(Hi)=" + Arrays.toString(ec));
        }
        System.out.println("defaultCharset=" + Charset.defaultCharset() + " isSupported(UTF-32)="
                + Charset.isSupported("UTF-32"));
        System.out.println("CharsetIdentityProbe done");
    }

    /** The UTF-16 units of {@code s}, so a wrong decode is visible whatever the console encoding. */
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
