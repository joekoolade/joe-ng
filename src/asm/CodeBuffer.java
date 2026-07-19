package asm;

import java.util.ArrayList;
import java.util.List;

/**
 * A growable stream of A64 instruction words tied to a load address.
 *
 * The load address is where the first word will live in memory once the Pi
 * firmware places the image (joe2 links everything to {@code 0x80000}). Keeping
 * it here lets code that needs absolute PCs (branch targets, later relocations)
 * compute them from {@link #pcAt}. For M0 the only client is the spin loop, but
 * this is the seam where relocation grows in later milestones (PLAN.md §6).
 */
public final class CodeBuffer
{
    /** Pi 4 loads {@code kernel8.img} here; execution starts at byte 0 of the image. */
    public static final long LOAD_ADDRESS = 0x8_0000L;

    private final long base;
    private final List<Integer> words = new ArrayList<>();

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
        words.add(word);
        return words.size() - 1;
    }

    /** Append several words (e.g. a {@code loadImm64} expansion). */
    public void emitAll(List<Integer> ws)
    {
        for (int w : ws)
        {
            emit(w);
        }
    }

    /** Overwrite the word at index {@code i} — used to backpatch forward refs. */
    public void set(int i, int word)
    {
        words.set(i, word);
    }

    /**
     * Reserve a two-word {@code MOVZ}/{@code MOVK} slot for a &lt;4 GiB absolute
     * address to be filled by {@link #patchAddr} once the target is laid out.
     * Fixed width keeps layout deterministic (no address-dependent sizing).
     * Returns the index of the first reserved word.
     */
    public int reserveAddr(int rd)
    {
        int at = emit(A64.movz(rd, 0, 0));
        emit(A64.movk(rd, 0, 1));
        return at;
    }

    /** Fill a slot from {@link #reserveAddr} with {@code addr} (low/high 16 bits). */
    public void patchAddr(int at, int rd, long addr)
    {
        set(at,     A64.movz(rd, (int) (addr & 0xFFFF), 0));
        set(at + 1, A64.movk(rd, (int) ((addr >>> 16) & 0xFFFF), 1));
    }

    /** Absolute PC of the instruction at word index {@code i}. */
    public long pcAt(int i)
    {
        return base + (long) i * 4;
    }

    /** Absolute PC of the next instruction to be emitted. */
    public long here()
    {
        return pcAt(words.size());
    }

    public int wordCount()
    {
        return words.size();
    }
    public long base()
    {
        return base;
    }

    public int[] toWords()
    {
        int[] a = new int[words.size()];
        for (int i = 0; i < a.length; i++)
        {
            a[i] = words.get(i);
        }
        return a;
    }

    /** Raw little-endian bytes — this is the payload of {@code kernel8.img}. */
    public byte[] toBytes()
    {
        return A64.wordsToLittleEndian(toWords());
    }
}
