/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-03
 */
package demo;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.MalformedInputException;

/**
 * The charset closure: STOCK {@code new String(byte[])} and {@code String.getBytes()} on metal. Both go
 * through {@code Charset.defaultCharset()} (overlay -> the {@code sun.nio.cs.UTF_8.INSTANCE} singleton),
 * whose identity pins stock String's pure-Java UTF-8 fast paths ({@code String.utf8} decode /
 * {@code encodeUTF8}); the deep {@code CharsetDecoder} fallback is denylisted, statically-unreachable
 * code. Covers ASCII (Latin1 copy path) and a 2-byte UTF-8 sequence (é -> the decode2/encode
 * non-ASCII path), round-tripping both. Ordinary Java, no VM hooks.
 *
 * <p>Also covers MALFORMED input, which is two separate claims. Stock {@code new String(byte[])}
 * REPLACES a bad sequence with U+FFFD rather than throwing -- only a {@code CharsetDecoder} with
 * REPORT throws, and that is denylisted and statically unreachable here -- so one arm pins the
 * replacement. The other CONSTRUCTS a {@code MalformedInputException} directly, and is the only arm
 * that discriminates: with the denial restored this demo HALTS at that {@code new} rather than
 * printing a wrong number (see the negative control recorded at the call site). Reading 3 is what
 * says the constructor ran.
 */
public class CharsetDemo
{
    public static void main(String[] args)
    {
        byte[] ascii = new byte[] { 104, 101, 108, 108, 111 };          // "hello"
        String s = new String(ascii);
        System.out.println("new String(ascii)=" + s + " len=" + s.length()
                + " eq=" + (s.equals("hello") ? 1 : 0));                // hello 5 1
        byte[] round = s.getBytes();
        boolean ok = round.length == ascii.length;
        int i = 0;
        while (ok && i < ascii.length)
        {
            if ((round[i] & 0xff) != (ascii[i] & 0xff))                 // mask BOTH sides (baload zero-extends)
            {
                ok = false;
            }
            i += 1;
        }
        System.out.println("getBytes len=" + round.length + " roundtrip=" + (ok ? 1 : 0));   // 5 1

        byte[] utf = new byte[] { (byte) 0xC3, (byte) 0xA9 };           // U+00E9 (e-acute) in UTF-8
        String e = new String(utf);
        System.out.println("utf8 len=" + e.length() + " char=" + (int) e.charAt(0));         // 1 233
        byte[] back = e.getBytes();
        System.out.println("utf8 back len=" + back.length
                + " b0=" + (back[0] & 0xff) + " b1=" + (back[1] & 0xff));                    // 2 195 169

        // UTF-8 OUTPUT: println now encodes through stock getBytes(), so non-ASCII text leaves the UART as
        // real UTF-8. Printed DIRECTLY (not concatenated -- the metal concat intrinsic is Latin1-only):
        // a Latin1 string (é) and a UTF16 string (€, char 0x20AC > 0xFF -> 3-byte UTF-8 sequence).
        System.out.print("out latin1: ");
        System.out.println(e);                                          // wire bytes: C3 A9
        byte[] euroUtf = new byte[] { (byte) 0xE2, (byte) 0x82, (byte) 0xAC };   // U+20AC euro sign
        String euro = new String(euroUtf);
        System.out.println("euro len=" + euro.length() + " char=" + (int) euro.charAt(0));   // 1 8364
        System.out.print("out utf16: ");
        System.out.println(euro);                                       // wire bytes: E2 82 AC

        // MALFORMED INPUT. Two different questions, and only one of them is a control.
        //
        // (1) WHAT THIS VM DOES WITH BAD BYTES. 0x80 is a lone continuation byte: not a valid UTF-8
        //     sequence start. Stock `new String(byte[])` REPLACES malformed input with U+FFFD rather
        //     than throwing -- only a CharsetDecoder with REPORT throws, and that is denylisted and
        //     statically unreachable here. So this arm pins REPLACEMENT, and records that the
        //     exception below is NOT on this path.
        byte[] bad = new byte[] { (byte) 0x41, (byte) 0x80, (byte) 0x42 };   // 'A', a stray 0x80, 'B'
        String m = new String(bad);
        System.out.println("malformed len=" + m.length()
                + " repl=" + (m.charAt(1) == 0xFFFD ? 1 : 0)
                + " kept=" + (m.charAt(0) == 'A' && m.charAt(2) == 'B' ? 1 : 0));   // 3 1 1

        // (2) THE ARM THAT DISCRIMINATES, and the reason this demo exists at all.
        //     java/nio/charset/Malformed was DENYLISTED until bc3c1ca. NEGATIVE CONTROL, run rather
        //     than predicted: with that denial restored this demo does not print a wrong number, it
        //     HALTS HERE -- `UNRESOLVED NEW: java/nio/charset/MalformedInputException` naming this
        //     demo's main as the site -- because a denied class is never pulled,
        //     so emitNew has no registered class to size the object against and emits the halting
        //     helper instead. The same control brings back ten `CTOR SKIPPED ...
        //     MalformedInputException.<init> -- DENYLISTED` lines from the charset code BELOW this
        //     demo, which is the other shape of the same denial: there the class IS reached, the
        //     constructor is lowered to a POP, and inputLength stays 0 silently. So getInputLength()
        //     reading 3 is what says the constructor ran.
        //     The instanceof arms below are SHAPE COVERAGE, not controls: a skipped constructor
        //     still yields an object of the right type, so they pass in both states.
        MalformedInputException mie = new MalformedInputException(3);
        System.out.println("mie inputLength=" + mie.getInputLength()
                + " (want 3 -- 0 means the ctor was skipped)");
        System.out.println("mie message=" + mie.getMessage() + " (want Input length = 3)");
        System.out.println("mie isCCE=" + (mie instanceof CharacterCodingException ? 1 : 0)
                + " isIOE=" + (mie instanceof IOException ? 1 : 0));                         // 1 1
    }
}
