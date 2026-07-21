package asm;

/**
 * A growable stream of A64 instruction words tied to a load address.
 *
 * The load address is where the first word will live in memory once the Pi
 * firmware places the image (joe-ng links everything to {@code 0x80000}). Keeping
 * it here lets code that needs absolute PCs (branch targets, later relocations)
 * compute them from {@link #pcAt}.
 *
 * <p>Written JDK-free (an {@code int[]} grown by hand, encodings via
 * {@link A64Enc}) so it can be compiled into the image: it is the emit target the
 * shared core will use on the metal as well as in the writer (M5.4).
 */
public final class CodeBuffer
{
    /** Pi 4 loads {@code kernel8.img} here; execution starts at byte 0 of the image. */
    public static final long LOAD_ADDRESS = 0x8_0000L;

    private final long base;
    private int[] words = new int[64];
    private int count;

    public CodeBuffer()
    {
        this(LOAD_ADDRESS);
    }
    public CodeBuffer(long base)
    {
        this.base = base;
    }

    /** Append one already-encoded instruction word. Returns its word index. */
    public int emit(int word)
    {
        if (count == words.length)
        {
            int[] bigger = new int[words.length * 2];
            int i = 0;
            while (i < count)
            {
                bigger[i] = words[i];
                i += 1;
            }
            words = bigger;
        }
        words[count] = word;
        count += 1;
        return count - 1;
    }

    /** Append several words (e.g. a {@code loadImm64} expansion). */
    public void emitAll(int[] ws)
    {
        int i = 0;
        while (i < ws.length)
        {
            emit(ws[i]);
            i += 1;
        }
    }

    /** Overwrite the word at index {@code i} — used to backpatch forward refs. */
    public void set(int i, int word)
    {
        words[i] = word;
    }

    /**
     * Reserve a two-word {@code MOVZ}/{@code MOVK} slot for a &lt;4 GiB absolute
     * address to be filled by {@link #patchAddr} once the target is laid out.
     * Fixed width keeps layout deterministic (no address-dependent sizing).
     * Returns the index of the first reserved word.
     */
    public int reserveAddr(int rd)
    {
        int at = emit(A64Enc.movz(rd, 0, 0));
        emit(A64Enc.movk(rd, 0, 1));
        return at;
    }

    /** Fill a slot from {@link #reserveAddr} with {@code addr} (low/high 16 bits). */
    public void patchAddr(int at, int rd, long addr)
    {
        set(at,     A64Enc.movz(rd, (int) (addr & 0xFFFF), 0));
        set(at + 1, A64Enc.movk(rd, (int) ((addr >>> 16) & 0xFFFF), 1));
    }

    /** Absolute PC of the instruction at word index {@code i}. */
    public long pcAt(int i)
    {
        return base + (long) i * 4;
    }

    /** Absolute PC of the next instruction to be emitted. */
    public long here()
    {
        return pcAt(count);
    }

    public int wordCount()
    {
        return count;
    }
    public long base()
    {
        return base;
    }

    public int[] toWords()
    {
        int[] a = new int[count];
        int i = 0;
        while (i < count)
        {
            a[i] = words[i];
            i += 1;
        }
        return a;
    }

    /** Raw little-endian bytes — this is the payload of {@code kernel8.img} (writer-side). */
    public byte[] toBytes()
    {
        return A64Enc.wordsToLittleEndian(toWords());
    }
}
