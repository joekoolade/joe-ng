package crypto;

/**
 * AES Key Unwrap (RFC 3394) — the WPA2 4-way handshake sends the GTK in EAPOL message 3 wrapped with this
 * under the KEK (bytes 16..31 of the PTK); the supplicant unwraps it here. JDK-free (see {@link Aes}).
 */
public final class KeyWrap
{
    private KeyWrap()
    {
    }

    /**
     * Unwrap {@code cipher[0..cipherLen)} (an (n+1)·8-byte wrapped key) with the 16-byte {@code kek} into
     * {@code out} (n·8 bytes). Returns true iff the recovered integrity value is the 0xA6 default IV.
     */
    public static boolean unwrap(byte[] kek, byte[] cipher, int cipherLen, byte[] out)
    {
        int n = cipherLen / 8 - 1;
        int[] w = new int[44];
        Aes.expandKey(kek, w);

        byte[] a = new byte[8];                          // A = C[0]
        int i = 0;
        while (i < 8)
        {
            a[i] = cipher[i];
            i = i + 1;
        }
        i = 0;
        while (i < n * 8)                                // R[1..n] = C[1..n]
        {
            out[i] = cipher[8 + i];
            i = i + 1;
        }

        byte[] block = new byte[16];
        byte[] dec = new byte[16];
        int j = 5;
        while (j >= 0)
        {
            int k = n;
            while (k >= 1)
            {
                int t = n * j + k;                       // small here (WPA2 GTK), fits the low 32 bits
                int m = 0;
                while (m < 8)
                {
                    block[m] = a[m];
                    m = m + 1;
                }
                block[7] = (byte) ((block[7] & 0xFF) ^ (t & 0xFF));
                block[6] = (byte) ((block[6] & 0xFF) ^ ((t >>> 8) & 0xFF));
                block[5] = (byte) ((block[5] & 0xFF) ^ ((t >>> 16) & 0xFF));
                block[4] = (byte) ((block[4] & 0xFF) ^ ((t >>> 24) & 0xFF));
                m = 0;
                while (m < 8)
                {
                    block[8 + m] = out[(k - 1) * 8 + m];
                    m = m + 1;
                }
                Aes.decryptBlock(w, block, 0, dec, 0);
                m = 0;
                while (m < 8)
                {
                    a[m] = dec[m];                        // A = MSB64
                    out[(k - 1) * 8 + m] = dec[8 + m];   // R[k] = LSB64
                    m = m + 1;
                }
                k = k - 1;
            }
            j = j - 1;
        }

        boolean ok = true;
        i = 0;
        while (i < 8)
        {
            if ((a[i] & 0xFF) != 0xA6)
            {
                ok = false;
            }
            i = i + 1;
        }
        return ok;
    }
}
