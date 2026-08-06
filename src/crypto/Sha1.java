package crypto;

/**
 * SHA-1 (FIPS 180-1) — the hash under HMAC-SHA1 / PBKDF2 / the WPA2 PRF, all needed for the in-Java WPA2
 * supplicant (this firmware has no in-chip supplicant, so joe-ng runs the 4-way handshake itself).
 *
 * <p>Strictly JDK-free (primitive arrays + int/long math, {@code new int[]}/{@code new byte[]} only), so the
 * same code runs on the seed JVM under {@code test/crypto/CryptoTest} and compiles into the bare-metal image
 * via our own baseline compiler. 20-byte digest, 64-byte block.
 */
public final class Sha1
{
    public static final int DIGEST = 20;
    public static final int BLOCK = 64;

    private Sha1()
    {
    }

    private static int rotl(int x, int n)
    {
        return (x << n) | (x >>> (32 - n));
    }

    /** The round function f + additive constant K for round {@code i}, given state {@code s} (a,b,c,d,e). */
    private static int fk(int i, int[] s)
    {
        if (i < 20)
        {
            return ((s[1] & s[2]) | (~s[1] & s[3])) + 0x5A827999;
        }
        if (i < 40)
        {
            return (s[1] ^ s[2] ^ s[3]) + 0x6ED9EBA1;
        }
        if (i < 60)
        {
            return ((s[1] & s[2]) | (s[1] & s[3]) | (s[2] & s[3])) + 0x8F1BBCDC;
        }
        return (s[1] ^ s[2] ^ s[3]) + 0xCA62C1D6;
    }

    /** Process one 64-byte block at {@code data[off..]} into state {@code h}, using scratch {@code w}/{@code s}. */
    private static void block(int[] h, byte[] data, int off, int[] w, int[] s)
    {
        int i = 0;
        while (i < 16)
        {
            w[i] = ((data[off + i * 4] & 0xFF) << 24) | ((data[off + i * 4 + 1] & 0xFF) << 16)
                    | ((data[off + i * 4 + 2] & 0xFF) << 8) | (data[off + i * 4 + 3] & 0xFF);
            i = i + 1;
        }
        while (i < 80)
        {
            w[i] = rotl(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
            i = i + 1;
        }
        s[0] = h[0];
        s[1] = h[1];
        s[2] = h[2];
        s[3] = h[3];
        s[4] = h[4];
        i = 0;
        while (i < 80)
        {
            int t = rotl(s[0], 5) + s[4] + w[i] + fk(i, s);
            s[4] = s[3];
            s[3] = s[2];
            s[2] = rotl(s[1], 30);
            s[1] = s[0];
            s[0] = t;
            i = i + 1;
        }
        h[0] = h[0] + s[0];
        h[1] = h[1] + s[1];
        h[2] = h[2] + s[2];
        h[3] = h[3] + s[3];
        h[4] = h[4] + s[4];
    }

    /** SHA-1 of {@code msg[0..len)}; writes the 20-byte digest to {@code out[0..20)}. */
    public static void hash(byte[] msg, int len, byte[] out)
    {
        int[] h = new int[5];
        h[0] = 0x67452301;
        h[1] = 0xEFCDAB89;
        h[2] = 0x98BADCFE;
        h[3] = 0x10325476;
        h[4] = 0xC3D2E1F0;

        int total = len + 1;                             // 0x80 terminator
        while ((total % 64) != 56)
        {
            total = total + 1;
        }
        total = total + 8;                               // 64-bit big-endian length
        byte[] m = new byte[total];
        int i = 0;
        while (i < len)
        {
            m[i] = msg[i];
            i = i + 1;
        }
        m[len] = (byte) 0x80;
        long bits = ((long) len) * 8L;
        i = 0;
        while (i < 8)
        {
            m[total - 1 - i] = (byte) (bits >>> (8 * i));
            i = i + 1;
        }

        int[] w = new int[80];
        int[] s = new int[5];
        int off = 0;
        while (off < total)
        {
            block(h, m, off, w, s);
            off = off + 64;
        }

        i = 0;
        while (i < 5)
        {
            out[i * 4] = (byte) (h[i] >>> 24);
            out[i * 4 + 1] = (byte) (h[i] >>> 16);
            out[i * 4 + 2] = (byte) (h[i] >>> 8);
            out[i * 4 + 3] = (byte) h[i];
            i = i + 1;
        }
    }
}
