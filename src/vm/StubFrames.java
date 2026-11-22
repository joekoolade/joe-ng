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
    static final int STUB_ID_OFF  = 136;                     // the alignment word: a resolve trampoline stores
                                                                     //   x17 (its index) here, for the stack walk

    // ---- stub frame descriptors (frames/GC arc S5) -----------------------------------------------------
    // A stub frame has no reference map; its unwind-table entry's map word holds a DESCRIPTOR instead, so the
    // precise stack walk ({@link StackRoots}) knows which of its words hold references. Bit 63 tags it (a map
    // address is positive, and -1 is "no entry"), bits 56..62 are the kind, bits 0..47 a kind's payload.
    static final long STUB_TAG = 1L << 63;
    /** Resolve trampolines: x0..x15 at [sp+8..], the index at [sp+136]; the arguments are typed by the
     *  descriptor that index names. */
    static final int K_LAZY = 1;                             // LazyCompiler.lzTab[idx]: the callee's own descriptor
    static final int K_LINK = 2;                             // DynamicLinker link stub idx (+ its linkRecv)
    static final int K_VIRTUAL = 3;                          // VirtualDispatch site idx: receiver + descriptor
    static final int K_MH = 4;                               // MethodHandles site idx (invoke*/linkTo*)
    static final int K_INDY = 5;                             // Indy site idx: the descriptor; FrameMaps.INDY_SHIFTED
                                                             //   once the resolver has inserted the target in front
    /** A per-stub frame whose reference words are fixed when it is built: payload bit i = [sp + 8*i] for i < 17.
     *  With {@link #MASK_OVERFLOW} set, [sp + 128] (a saved x15) points at a call's arguments past the fifteenth,
     *  and bit 17 + j types argument 15 + j (up to 30 of them). */
    static final int K_MASK = 6;
    static final long MASK_OVERFLOW = 1L << 47;
    static final int MASK_OVERFLOW_SLOT = 16;
    static final int MASK_OVERFLOW_BIT0 = 17;

    /** The descriptor word for a stub frame of {@code kind}, with {@code payload} (a K_MASK slot mask). */
    static long stubDesc(int kind, long payload)
    {
        return STUB_TAG | ((long) kind << 56) | (payload & 0xFFFF_FFFF_FFFFL);
    }

    /** True if a frame table's map word is a stub descriptor (not a map address, 0, or -1 for no entry). */
    static boolean isStubDesc(long m)
    {
        return m < 0L && m != -1L;
    }

    static int stubKind(long m)
    {
        return (int) ((m >>> 56) & 0x7FL);
    }

    static long stubPayload(long m)
    {
        return m & 0xFFFF_FFFF_FFFFL;
    }

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
    /** Store x17 (the trampoline's index) in the frame's spare word: the precise stack walk reads it back to find
     *  the descriptor that types the saved argument registers. Emitted right after the register saves. */
    static int emitSaveIndex(Address buf, int w)
    {
        buf.plus(w * 4L).store(asm.A64Enc.strx(17, STUB_SP, STUB_ID_OFF));
        return w + 1;
    }

    static void registerTrampFrames()
    {
        if (LazyCompiler.lazyTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(LazyCompiler.lazyTrampLo), Address.fromLong(LazyCompiler.lazyTrampHi), STUB_FRAME,
                    stubDesc(K_LAZY, 0L));
        }
        if (VirtualDispatch.virtualTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(VirtualDispatch.virtualTrampLo), Address.fromLong(VirtualDispatch.virtualTrampHi), STUB_FRAME,
                    stubDesc(K_VIRTUAL, 0L));
        }
        if (MethodHandles.mhTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(MethodHandles.mhTrampLo), Address.fromLong(MethodHandles.mhTrampHi), STUB_FRAME,
                    stubDesc(K_MH, 0L));
        }
        if (Indy.indyTrampAddr != 0L)
        {
            VM.addJitFrame(Address.fromLong(Indy.indyTrampLo), Address.fromLong(Indy.indyTrampHi), STUB_FRAME,
                    stubDesc(K_INDY, 0L));
        }
    }
}
