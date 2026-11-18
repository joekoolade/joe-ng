/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import magic.Address;
import magic.Magic;

/**
 * The frame layout every resolve trampoline and thunk that CALLS shares, so an exception can unwind through it, and
 * the registration that tells the unwinder where those trampolines are. Split out of {@link Loader}.
 */
final class StubFrames
{
    private StubFrames()
    {
    }

    // ---- unwindable stub frame layout ------------------------------------------------------------------
    // Shared by the three resolve trampolines (lazy-compile, link-resolve, late-virtual) and the lambda
    // thunk -- every stub that establishes a frame and then CALLS, so an exception can unwind through it.
    // The LR offset is not a free choice: VM.unwind reads a frame's saved LR from [sp+0] (and a handler's
    // callee-saved locals from [sp+8]), so putting it anywhere else makes the walk treat a saved argument
    // register as the return address. STUB_FRAME is the trampolines' size specifically; the lambda thunk
    // sizes its own frame from the ctor's arity.
    static final int STUB_SP      = 31;                      // SP in the load/store encodings
    static final int STUB_LR      = 30;                      // x30
    static final int STUB_LR_OFF  = 0;                       // ... saved where the unwinder looks
    static final int STUB_SAVE0   = 8;                       // x0 here; x_r at STUB_SAVE0 + r*WORD
    static final int STUB_SAVE_HI = 15;                      // x0..x15: every register a callee can
                                                                     //   read as an argument (see buildLazyTramp)
    static final int STUB_FRAME   = 144;                     // LR + 16 saved = 136, rounded up to the
                                                                     //   16-byte SP alignment AArch64 requires

    /**
     * Re-publish the persistent trampolines' frames into the JIT unwind table.
     *
     * <p>These trampolines establish a real frame and then CALL — {@code lazyCompile} runs the whole
     * compiler, {@code virtualResolve} demand-loads classes and can run a {@code <clinit>} — so an exception
     * thrown underneath one has to unwind THROUGH it. Without an entry the walk cannot size the frame: it
     * stops there, and the frames it prints below are stale stack words rather than callers. That is what made
     * a MetalJUnit failure report a NullPointerException at a bare {@code Magic.load64} in check-free image
     * code, which cannot throw one.
     *
     * <p>Called after every {@link Loader#resetLoader} as well as at build time, because the trampolines are built
     * ONCE and cached for the life of the VM while {@code jitFrameCount} is zeroed per batch — registering
     * only at build time would leave every batch after the first unwalkable again.
     */
    static void registerTrampFrames()
    {
        if (LazyCompiler.lazyTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(LazyCompiler.lazyTrampLo), Address.fromLong(LazyCompiler.lazyTrampHi), STUB_FRAME, 0L);
        }
        if (VirtualDispatch.virtualTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(VirtualDispatch.virtualTrampLo), Address.fromLong(VirtualDispatch.virtualTrampHi), STUB_FRAME, 0L);
        }
        if (MethodHandles.mhTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(MethodHandles.mhTrampLo), Address.fromLong(MethodHandles.mhTrampHi), STUB_FRAME, 0L);
        }
        if (Indy.indyTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(Indy.indyTrampLo), Address.fromLong(Indy.indyTrampHi), STUB_FRAME, 0L);
        }
    }
}
