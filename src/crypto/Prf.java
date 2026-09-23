/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-05
 */
package crypto;

/**
 * The IEEE 802.11i pseudo-random function (PRF) built on HMAC-SHA1 — used to expand the PMK into the PTK
 * during the 4-way handshake: {@code PTK = PRF(PMK, "Pairwise key expansion", Min(AA,SPA)||Max(AA,SPA)||
 * Min(ANonce,SNonce)||Max(ANonce,SNonce), 384)}. JDK-free (see {@link Digest}).
 *
 * <p>{@code PRF(K,A,B,n)} = the first n bytes of HMAC-SHA1(K, A || 0x00 || B || i) for i = 0,1,2,… concatenated.
 *
 * <p><b>ONE {@link Hmac} IS BUILT AND REUSED across the blocks</b>, for the reason {@link Pbkdf2} records:
 * every block re-MACs under the SAME key, so a fresh one per block would re-derive both pads each time. A
 * 384-bit PTK is three blocks, so this is small -- but it is also the shape that keeps the key schedule in
 * one place, and the one-shot it replaced re-padded the key on every block.
 */
public final class Prf
{
    private Prf()
    {
    }

    public static void sha1(byte[] key, int keyLen, byte[] label, int labelLen,
            byte[] data, int dataLen, byte[] out, int outLen)
    {
        byte[] in = new byte[labelLen + 1 + dataLen + 1];   // A || 0x00 || B || counter
        int i = 0;
        while (i < labelLen)
        {
            in[i] = label[i];
            i = i + 1;
        }
        in[labelLen] = 0;
        i = 0;
        while (i < dataLen)
        {
            in[labelLen + 1 + i] = data[i];
            i = i + 1;
        }
        int counterPos = labelLen + 1 + dataLen;

        Hmac mac = new Hmac(Digest.SHA1, key, keyLen);
        int hLen = mac.macLength();
        byte[] digest = new byte[hLen];
        int pos = 0;
        int counter = 0;
        while (pos < outLen)
        {
            in[counterPos] = (byte) counter;
            mac.update(in, 0, in.length);
            mac.doFinal(digest, 0);          // resets under the same key, so the next block reuses the pads
            int j = 0;
            while (j < hLen && pos < outLen)
            {
                out[pos] = digest[j];
                pos = pos + 1;
                j = j + 1;
            }
            counter = counter + 1;
        }
    }
}
