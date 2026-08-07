package crypto;

/**
 * HMAC-SHA1 (RFC 2104) — the keyed MAC under PBKDF2 (PMK derivation), the WPA2 PRF (PTK), and the EAPOL-Key
 * MIC. JDK-free, so it runs the seed-JVM vectors and compiles into the image (see {@link Sha1}).
 */
public final class Hmac
{
    private Hmac()
    {
    }

    /** HMAC-SHA1 of {@code msg[0..msgLen)} under {@code key[0..keyLen)}; 20-byte MAC into {@code out}. */
    public static void sha1(byte[] key, int keyLen, byte[] msg, int msgLen, byte[] out)
    {
        byte[] k = new byte[Sha1.BLOCK];                 // key padded/hashed to the 64-byte block
        if (keyLen > Sha1.BLOCK)
        {
            byte[] kh = new byte[Sha1.DIGEST];
            Sha1.hash(key, keyLen, kh);
            copy(kh, k, Sha1.DIGEST);
        }
        else
        {
            copy(key, k, keyLen);
        }

        byte[] inner = new byte[Sha1.BLOCK + msgLen];    // SHA1( (k^ipad) || msg )
        int i = 0;
        while (i < Sha1.BLOCK)
        {
            inner[i] = (byte) ((k[i] & 0xFF) ^ 0x36);
            i = i + 1;
        }
        i = 0;
        while (i < msgLen)
        {
            inner[Sha1.BLOCK + i] = msg[i];
            i = i + 1;
        }
        byte[] ih = new byte[Sha1.DIGEST];
        Sha1.hash(inner, Sha1.BLOCK + msgLen, ih);

        byte[] outer = new byte[Sha1.BLOCK + Sha1.DIGEST];   // SHA1( (k^opad) || inner-hash )
        i = 0;
        while (i < Sha1.BLOCK)
        {
            outer[i] = (byte) ((k[i] & 0xFF) ^ 0x5C);
            i = i + 1;
        }
        i = 0;
        while (i < Sha1.DIGEST)
        {
            outer[Sha1.BLOCK + i] = ih[i];
            i = i + 1;
        }
        Sha1.hash(outer, Sha1.BLOCK + Sha1.DIGEST, out);
    }

    private static void copy(byte[] src, byte[] dst, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst[i] = src[i];
            i = i + 1;
        }
    }
}
