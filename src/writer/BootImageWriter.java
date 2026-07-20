package writer;

import asm.CodeBuffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The boot-image writer — joe-ng's foundation (PLAN.md §2). It takes an assembled
 * {@link CodeBuffer} and emits a raw {@code kernel8.img}: the exact bytes the
 * Pi 4 GPU firmware copies to {@link CodeBuffer#LOAD_ADDRESS} and jumps to.
 *
 * <p><b>The image has no header.</b> The firmware transfers control to the very
 * first byte, so byte 0 of the file must be the first instruction to run. Any
 * "image header" in the design is writer-internal bookkeeping — it never lands
 * in {@code kernel8.img}. We do our own layout and emission; there is no
 * {@code ld}/{@code objcopy} in the loop (PLAN.md §8, hard constraint).
 *
 * <p>For M0 the payload is a spin loop; later milestones append compiled
 * methods, TIBs, statics, and the object graph, relocated to the load address.
 */
public final class BootImageWriter
{

    private final CodeBuffer code;

    public BootImageWriter(CodeBuffer code)
    {
        this.code = code;
    }

    /** Write the raw image bytes to {@code path}. */
    public void writeImage(Path path) throws IOException
    {
        byte[] bytes = code.toBytes();
        Files.write(path, bytes);
    }

    /**
     * Human-readable layout dump for diffing images across changes — relocation
     * bugs are the classic silent failure, so we make layout visible (PLAN.md §6).
     */
    public String layoutDump()
    {
        int[] words = code.toWords();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("kernel8.img  load=0x%X  words=%d  bytes=%d%n",
                                code.base(), words.length, words.length * 4));
        for (int i = 0; i < words.length; i++)
        {
            sb.append(String.format("  0x%08X:  0x%08X%n", code.pcAt(i), words[i]));
        }
        return sb.toString();
    }
}
