package crypto;

/**
 * PBKDF2-HMAC-SHA1 (RFC 2898) — derives the WPA2 PMK from the passphrase and SSID:
 * {@code PMK = PBKDF2(passphrase, ssid, 4096, 32)}. JDK-free (see {@link Sha1}).
 */
public final class Pbkdf2
{
    private Pbkdf2()
    {
    }

    /** PBKDF2-HMAC-SHA1({@code pw}, {@code salt}, {@code iters}) → {@code dkLen} bytes into {@code out}. */
    public static void deriveSha1(byte[] pw, int pwLen, byte[] salt, int saltLen, int iters, byte[] out, int dkLen)
    {
        byte[] u = new byte[Sha1.DIGEST];
        byte[] t = new byte[Sha1.DIGEST];
        byte[] block = new byte[saltLen + 4];            // salt || INT_BE(blockIndex)
        int outPos = 0;
        int b = 1;
        while (outPos < dkLen)
        {
            int i = 0;
            while (i < saltLen)
            {
                block[i] = salt[i];
                i = i + 1;
            }
            block[saltLen] = (byte) (b >>> 24);
            block[saltLen + 1] = (byte) (b >>> 16);
            block[saltLen + 2] = (byte) (b >>> 8);
            block[saltLen + 3] = (byte) b;

            Hmac.sha1(pw, pwLen, block, saltLen + 4, u);  // U1
            i = 0;
            while (i < Sha1.DIGEST)
            {
                t[i] = u[i];
                i = i + 1;
            }
            int c = 1;
            while (c < iters)                             // U2..Uc, XOR-accumulated into T
            {
                Hmac.sha1(pw, pwLen, u, Sha1.DIGEST, u);
                i = 0;
                while (i < Sha1.DIGEST)
                {
                    t[i] = (byte) (t[i] ^ u[i]);
                    i = i + 1;
                }
                c = c + 1;
            }
            i = 0;
            while (i < Sha1.DIGEST && outPos < dkLen)
            {
                out[outPos] = t[i];
                outPos = outPos + 1;
                i = i + 1;
            }
            b = b + 1;
        }
    }
}
