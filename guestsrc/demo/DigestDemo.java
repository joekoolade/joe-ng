/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-21
 */
package demo;

import java.security.MessageDigest;

/**
 * {@code java.security.MessageDigest} in the boot suite, so the digest path is PI-GATED.
 *
 * <p>Every arm is a KNOWN-ANSWER vector -- the published NIST/RFC digests -- not a self-consistency check.
 * A hash that is self-consistent and wrong is the worst failure this subsystem has: stable, the right
 * length, and agreeing with nothing else in the world. {@code test/jdk/junit/DigestProbe} is the wide
 * version (48 arms, all six algorithms, DigestInputStream/OutputStream, argument sanity); this is the
 * narrow one the suite can afford to print.
 *
 * <p>The two arms that DISCRIMINATE rather than merely exercise:
 * <ul>
 * <li>{@code sha256 stream} feeds 200 bytes ONE AT A TIME. That crosses the 64-byte block boundary and the
 *     offset 56 where the length field starts -- where a padding or buffering bug lives, and where a short
 *     message cannot see one. It must equal the one-shot digest of the same bytes.
 * <li>{@code sha256 clone} finishes a CLONE and keeps feeding the original, then checks BOTH.
 *     {@code Object.clone()} is shallow, so an inherited clone would leave the two sharing one engine and
 *     BOTH answers would be wrong -- and still look exactly like digests.
 * </ul>
 */
public final class DigestDemo
{
    private static String hex(byte[] b)
    {
        String s = "";
        for (int i = 0; i < b.length; i++)
        {
            int v = b[i] & 0xFF;
            s = s + "0123456789abcdef".charAt(v >>> 4) + "0123456789abcdef".charAt(v & 0xF);
        }
        return s;
    }

    private static void arm(String what, String got, String want)
    {
        System.out.println("  " + what + " = " + got + (got.equals(want) ? "" : " (want " + want + ")"));
    }

    public static void main(String[] args) throws Exception
    {
        byte[] abc = { (byte) 'a', (byte) 'b', (byte) 'c' };

        arm("md5    (abc)", hex(MessageDigest.getInstance("MD5").digest(abc)),
                "900150983cd24fb0d6963f7d28e17f72");
        arm("sha1   (abc)", hex(MessageDigest.getInstance("SHA-1").digest(abc)),
                "a9993e364706816aba3e25717850c26c9cd0d89d");
        arm("sha224 (abc)", hex(MessageDigest.getInstance("SHA-224").digest(abc)),
                "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7");
        arm("sha256 (abc)", hex(MessageDigest.getInstance("SHA-256").digest(abc)),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        arm("sha384 (abc)", hex(MessageDigest.getInstance("SHA-384").digest(abc)),
                "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7");
        arm("sha512 (abc)", hex(MessageDigest.getInstance("SHA-512").digest(abc)),
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");

        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++)
        {
            data[i] = (byte) (i * 31 + 7);
        }
        String want = hex(MessageDigest.getInstance("SHA-256").digest(data));

        MessageDigest byteWise = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < data.length; i++)
        {
            byteWise.update(data[i]);
        }
        arm("sha256 stream", hex(byteWise.digest()), want);

        MessageDigest orig = MessageDigest.getInstance("SHA-256");
        orig.update(data, 0, 150);
        MessageDigest fork = (MessageDigest) orig.clone();
        orig.update(data, 150, 50);
        fork.update(data, 150, 50);
        arm("sha256 clone", hex(orig.digest()) + "/" + (hex(fork.digest()).equals(want) ? "fork-ok" : "FORK-WRONG"),
                want + "/fork-ok");
    }
}
