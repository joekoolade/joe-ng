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
        T.summary("crypto");
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
