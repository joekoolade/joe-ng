package zip;

/**
 * A canonical Huffman decoding table for DEFLATE (RFC 1951 §3.2.2) — the "counts and symbols" form: for each
 * code length 1..15, how many codes have that length, plus every symbol ordered by (length, symbol). That pair
 * is enough to walk a code one bit at a time, so {@link Inflate} needs no multi-bit lookup table and can stop
 * mid-code when the input runs dry (the property the streaming decoder is built on).
 *
 * <p>Strictly JDK-free (primitive arrays + int math, no imports), so the same code runs on the seed JVM under
 * {@code test/zip/ZipTest} and compiles into the bare-metal image via our own baseline compiler.
 */
public final class Huff
{
    /** DEFLATE never uses a code longer than 15 bits. */
    public static final int MAXBITS = 15;

    /** {@code count[n]} = how many codes are {@code n} bits long ({@code count[0]} counts unused symbols). */
    final int[] count = new int[MAXBITS + 1];

    /** Symbols ordered by code length, then by symbol value — the canonical order codes are assigned in. */
    final int[] symbol;

    public Huff(int maxSymbols)
    {
        symbol = new int[maxSymbols];
    }

    /**
     * Build the table from {@code length[0..n)}, the bit length of each symbol's code (0 = symbol unused).
     * Returns 0 for a complete code, a positive count of unused codes for an incomplete one (legal only for
     * the single-symbol distance code), and -1 when the code is over-subscribed (malformed).
     */
    public int build(int[] length, int n)
    {
        int i = 0;
        while (i <= MAXBITS)
        {
            count[i] = 0;
            i += 1;
        }
        i = 0;
        while (i < n)
        {
            count[length[i]] += 1;
            i += 1;
        }
        if (count[0] == n)
        {
            return 0;                                  // no symbols at all: an empty but not invalid code
        }
        // Kraft check: at each length, the codes used may not exceed the codes available.
        int left = 1;
        int len = 1;
        while (len <= MAXBITS)
        {
            left = left << 1;
            left = left - count[len];
            if (left < 0)
            {
                return -1;                             // over-subscribed
            }
            len += 1;
        }
        // First symbol index for each length, then fill `symbol` in canonical order.
        int[] offs = new int[MAXBITS + 2];
        offs[1] = 0;
        len = 1;
        while (len < MAXBITS)
        {
            offs[len + 1] = offs[len] + count[len];
            len += 1;
        }
        i = 0;
        while (i < n)
        {
            if (length[i] != 0)
            {
                symbol[offs[length[i]]] = i;
                offs[length[i]] += 1;
            }
            i += 1;
        }
        return left;                                   // 0 = complete; >0 = incomplete
    }
}
