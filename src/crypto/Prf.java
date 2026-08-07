package crypto;

/**
 * The IEEE 802.11i pseudo-random function (PRF) built on HMAC-SHA1 — used to expand the PMK into the PTK
 * during the 4-way handshake: {@code PTK = PRF(PMK, "Pairwise key expansion", Min(AA,SPA)||Max(AA,SPA)||
 * Min(ANonce,SNonce)||Max(ANonce,SNonce), 384)}. JDK-free (see {@link Sha1}).
 *
 * <p>{@code PRF(K,A,B,n)} = the first n bytes of HMAC-SHA1(K, A || 0x00 || B || i) for i = 0,1,2,… concatenated.
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

        byte[] digest = new byte[Sha1.DIGEST];
        int pos = 0;
        int counter = 0;
        while (pos < outLen)
        {
            in[counterPos] = (byte) counter;
            Hmac.sha1(key, keyLen, in, in.length, digest);
            int j = 0;
            while (j < Sha1.DIGEST && pos < outLen)
            {
                out[pos] = digest[j];
                pos = pos + 1;
                j = j + 1;
            }
            counter = counter + 1;
        }
    }
}
