package util;

/**
 * Byte-array helpers the writer needs once its identities are {@code byte[]} content
 * rather than {@code String} (PLAN.md §M5.5b) — content equality and concatenation,
 * both metal-compilable (no {@code String}, no {@code java.util}). Used as the compare
 * for {@link ByteKeySet} / {@link ByteKeyIntTable} keys and to build composite keys.
 */
public final class Bytes
{
    private Bytes() {}

    /** Whether {@code a} and {@code b} hold the same bytes. */
    public static boolean eq(byte[] a, byte[] b)
    {
        if (a.length != b.length)
        {
            return false;
        }
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i])
            {
                return false;
            }
        }
        return true;
    }

    /** A fresh array holding {@code a} then {@code b} — a metal-safe key builder. */
    public static byte[] concat(byte[] a, byte[] b)
    {
        byte[] out = new byte[a.length + b.length];
        for (int i = 0; i < a.length; i++)
        {
            out[i] = a[i];
        }
        for (int i = 0; i < b.length; i++)
        {
            out[a.length + i] = b[i];
        }
        return out;
    }

    /** {@code a} then the single byte {@code sep} then {@code b} (e.g. {@code class|iface}). */
    public static byte[] join(byte[] a, byte sep, byte[] b)
    {
        byte[] out = new byte[a.length + 1 + b.length];
        for (int i = 0; i < a.length; i++)
        {
            out[i] = a[i];
        }
        out[a.length] = sep;
        for (int i = 0; i < b.length; i++)
        {
            out[a.length + 1 + i] = b[i];
        }
        return out;
    }
}
