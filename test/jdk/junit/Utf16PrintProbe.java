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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A string that is NOT Latin-1 (a UTF-16-coded {@code String}: here U+20AC) on its way to bytes, separated into the
 * steps a console print takes: {@code getBytes(UTF_8)} on its own, the same through a {@code PrintStream} over a
 * byte array (stock {@code PrintStream} -> {@code OutputStreamWriter} -> {@code StreamEncoder}), and the charset
 * {@code System.out} reports. Each step prints BYTE VALUES, never the text itself, so the result does not depend on
 * how the console renders it.
 */
public class Utf16PrintProbe
{
    public static void main(String[] args) throws Exception
    {
        String s = "Hi" + (char) 0x20AC;
        System.out.println("length=" + s.length() + " last=" + Integer.toHexString(s.charAt(2)));
        System.out.println("getBytes(UTF_8)=" + Arrays.toString(s.getBytes(StandardCharsets.UTF_8)));
        System.out.println("getBytes()=" + Arrays.toString(s.getBytes()));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bout, true, StandardCharsets.UTF_8);
        ps.print(s);
        ps.flush();
        System.out.println("PrintStream(UTF_8).print=" + Arrays.toString(bout.toByteArray()));
        System.out.println("System.out.charset=" + System.out.charset());
        System.out.println("Utf16PrintProbe done");
    }
}
