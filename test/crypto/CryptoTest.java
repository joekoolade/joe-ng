package crypto;

import harness.T;

import java.nio.charset.StandardCharsets;

/**
 * Validates the JDK-free {@link crypto} primitives (SHA-1 so far; HMAC/PBKDF2/PRF/AES to follow) on the seed
 * JVM against published test vectors — the same code compiles into the image for the on-metal WPA2 supplicant,
 * so agreement here means the crypto is correct before it ever runs on the metal.
 *
 * <p>Run: {@code java crypto.CryptoTest}
 */
public final class CryptoTest
{
    public static void main(String[] args)
    {
        // SHA-1 (FIPS 180-1 examples + the RFC 3174 boundary case).
        sha1("", "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        sha1("abc", "a9993e364706816aba3e25717850c26c9cd0d89d");
        sha1("The quick brown fox jumps over the lazy dog", "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12");
        sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",   // 56 bytes -> two blocks
                "84983e441c3bd26ebaae4aa1f95129e5e54670f1");

        // HMAC-SHA1 (RFC 2202).
        hmac(rep((byte) 0x0b, 20), "Hi There", "b617318655057264e28bc0b6fb378c8ef146be00");
        hmac(ascii("Jefe"), "what do ya want for nothing?", "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79");

        // PBKDF2-HMAC-SHA1 (RFC 6070).
        pbkdf2("password", "salt", 1, 20, "0c60c80f961f0e71f3a9b524af6012062fe037a6");
        pbkdf2("password", "salt", 2, 20, "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957");
        pbkdf2("password", "salt", 4096, 20, "4b007901b765489abead49d926f721d065a429c1");

        // WPA2 PMK = PBKDF2(passphrase, ssid, 4096, 32) — IEEE 802.11i test vector.
        pbkdf2("password", "IEEE", 4096, 32,
                "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e");

        // PRF self-consistency: each 20-byte block must equal HMAC-SHA1(K, A || 0x00 || B || i). HMAC is
        // already RFC-validated above, so this confirms the PRF's input construction + counter across blocks
        // (the full PTK is ultimately proven by the on-metal 4-way handshake).
        prfConsistency();

        T.summary("crypto");
    }

    private static void prfConsistency()
    {
        byte[] key = rep((byte) 0x0b, 32);
        byte[] label = ascii("Pairwise key expansion");
        byte[] data = ascii("some 22-byte nonce-ish");
        byte[] prf = new byte[48];
        Prf.sha1(key, key.length, label, label.length, data, data.length, prf, 48);

        // rebuild the expected first three blocks independently from the tested Hmac
        byte[] in = new byte[label.length + 1 + data.length + 1];
        System.arraycopy(label, 0, in, 0, label.length);
        in[label.length] = 0;
        System.arraycopy(data, 0, in, label.length + 1, data.length);
        for (int blk = 0; blk < 3; blk++)
        {
            in[in.length - 1] = (byte) blk;
            byte[] mac = new byte[Sha1.DIGEST];
            Hmac.sha1(key, key.length, in, in.length, mac);
            int n = Math.min(20, 48 - blk * 20);
            T.eqStr("prf block " + blk, hex(mac, n), hexSlice(prf, blk * 20, n));
        }
    }

    private static String hexSlice(byte[] b, int off, int len)
    {
        byte[] s = new byte[len];
        System.arraycopy(b, off, s, 0, len);
        return hex(s, len);
    }

    private static void hmac(byte[] key, String msg, String expect)
    {
        byte[] m = ascii(msg);
        byte[] out = new byte[Sha1.DIGEST];
        Hmac.sha1(key, key.length, m, m.length, out);
        String label = msg.length() > 12 ? msg.substring(0, 12) + "..." : msg;
        T.eqStr("hmac(\"" + label + "\")", expect, hex(out, out.length));
    }

    private static void pbkdf2(String pw, String salt, int iters, int dkLen, String expect)
    {
        byte[] p = ascii(pw);
        byte[] s = ascii(salt);
        byte[] out = new byte[dkLen];
        Pbkdf2.deriveSha1(p, p.length, s, s.length, iters, out, dkLen);
        T.eqStr("pbkdf2(\"" + salt + "\"," + iters + ")", expect, hex(out, dkLen));
    }

    private static byte[] ascii(String s)
    {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] rep(byte v, int n)
    {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
        {
            b[i] = v;
        }
        return b;
    }

    private static void sha1(String msg, String expect)
    {
        byte[] m = msg.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[Sha1.DIGEST];
        Sha1.hash(m, m.length, out);
        String label = msg.length() > 12 ? msg.substring(0, 12) + "..." : msg;
        T.eqStr("sha1(\"" + label + "\")", expect, hex(out, out.length));
    }

    static String hex(byte[] b, int len)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++)
        {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
