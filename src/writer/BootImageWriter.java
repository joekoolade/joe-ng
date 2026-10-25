/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-18
 */
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

    /**
     * {@code Heap.IMAGE_CEILING}: the image is loaded at {@code 0x80000}, and the code bitmaps begin here, below
     * the fixed scratch band -- so THE IMAGE MUST END BELOW THIS ADDRESS. Until 2026-10-24 the ceiling was the
     * JIT code arena itself ({@code Heap.CODE_BASE == 0x0240_0000}); the arena moved above the scratch band.
     * Duplicated as a literal because the writer runs on the seed JVM and must not load the metal {@code Heap}.
     */
    private static final long IMAGE_CEILING = 0x02F0_0000L;

    /** Write the raw image bytes to {@code path}. */
    public void writeImage(Path path) throws IOException
    {
        byte[] bytes = code.toBytes();
        long end = code.base() + bytes.length;
        if (end > IMAGE_CEILING)
        {
            // THE IMAGE HAS GROWN INTO THE JIT CODE ARENA. Nothing at runtime can detect this: the arena
            // bump-allocates from CODE_BASE and the first compiled body simply overwrites whatever the image
            // put there -- which is its tail, where the bake stub table's Utf8 name runs live. The damage
            // surfaces far away and much later, as a class or descriptor name with a few bytes replaced by
            // machine code, and it MOVES with every layout change, so it reads like a mystery stray write.
            //
            // It went unnoticed because the overlap was small and only clobbered names nothing reached. Cost:
            // a red demo suite on hardware and several boots bisecting a change that was only moving the
            // furniture. Fail the BUILD instead -- this is the one place that knows both numbers.
            throw new IllegalStateException(String.format(
                    "image overruns Heap.IMAGE_CEILING: ends at 0x%X, the ceiling is 0x%X (over by %d bytes)."
                    + " Move the code bitmaps (and this constant) up, or shrink the image.",
                    end, IMAGE_CEILING, end - IMAGE_CEILING));
        }
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
