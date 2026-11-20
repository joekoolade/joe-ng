/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package vm;

import compiler.Baseline;

/**
 * What one run of the shared compiler core produced for a class-load method body: {@code Loader.compileMethod}
 * returns one and {@code Loader.emitMethod} installs it (the words, the unwinder's frame entry, the try/catch
 * ranges and the stack-trace line table).
 *
 * <p>Before increment P2-5 these were eight statics ({@code gFrameSize}, {@code gRegLocals}, {@code gHN},
 * {@code gHStartW}, {@code gHEndW}, {@code gHandlerW}, {@code gHCatchCp}, {@code gBcToWord}) that the LAST compile
 * left behind -- so the sizing pass's dry-run compile and the real emit wrote the same cells, and the reader had to
 * run before any other compile. IMMUTABLE once returned.
 */
final class CompileResult
{
    final int[] words;        // the A64 instruction words, placed at the base compileMethod was given
    final int frameSize;      // frame size; 0 = no frame for VM.unwind to pop
    final int gcMapWord;      // word offset of the reference map after the code (RefMaps); 0 = none
    final int handlerCount;   // try/catch ranges, as machine word offsets into words ...
    final int[] hStartW;
    final int[] hEndW;
    final int[] handlerW;
    final int[] catchCp;      // ... and each one's catch_type cp index (0 = catch-all)
    final int[] bcToWord;     // bci -> machine word offset (-1 where no instruction starts), for the line table

    CompileResult(Baseline b, int[] words, int[] catchCp)
    {
        this.words = words;
        this.frameSize = b.frameSize();
        this.gcMapWord = b.gcMapWord();
        int n = b.handlerCount();
        this.handlerCount = n;
        this.hStartW = new int[n];
        this.hEndW = new int[n];
        this.handlerW = new int[n];
        this.catchCp = new int[n];
        int h = 0;
        while (h < n)
        {
            this.hStartW[h] = b.handlerStartWord(h);
            this.hEndW[h] = b.handlerEndWord(h);
            this.handlerW[h] = b.handlerWord(h);
            this.catchCp[h] = catchCp[h];
            h += 1;
        }
        this.bcToWord = b.bcToWord();
    }
}
