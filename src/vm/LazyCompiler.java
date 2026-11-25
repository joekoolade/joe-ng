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

import asm.A64Enc;
import board.Uart;
import compiler.Baseline;
import magic.Address;
import magic.CodeAddress;
import magic.DataReference;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * The lazy-compile engine: every method body compiles on its first call (JikesRVM: {@code
 * LazyCompilationTrampoline} + {@code RVMMethod.compile()}). Split out of {@link Loader}.
 *
 * <p>A method is reached one of two ways, both landing in the same engine. A STATIC gets a phase-A cell at structure
 * registration ({@code armPhaseACells}) and its callers emit {@code ldr x16,[cell]; blr x16}; a VIRTUAL (or any
 * non-static) gets a deferral stub as both its registered buffer and its TIB slot ({@code emitDeferredStub}). Either
 * way the first call routes through the shared trampoline into {@code lazyCompile}, which compiles the one method
 * and data-patches the cell/slot, so later calls dispatch straight to the body. Also here: reflective
 * compile-on-demand ({@code compileMethodOnDemand}, {@code compileSigOnDemand}) and the stub table.
 *
 * <p>The writer stashes {@code vm/LazyCompiler.lazyCompile(I)J} into {@code VM.lazyCompileAddr} BY NAME.
 */
final class LazyCompiler
{
    private LazyCompiler()
    {
    }

    /** High-water mark of {@code lzN}: the deepest the lazy table has EVER been, across reclaims. The one
     *  fact that separates "a stale index from a previous table generation" from "an index that was never
     *  an lzTab index at all" -- and those want opposite fixes. */
    static int lzNHigh;
    // ----- the lazy-compile engine: every method body compiles on its first call -----
    // A method is reached one of two ways, both landing in the same engine. A STATIC gets a phase-A cell at
    // structure registration (armPhaseACells) and its callers emit `ldr x16,[cell]; blr x16`; a VIRTUAL (or
    // any non-static) gets a deferral stub as both its registered buffer and its TIB slot (emitDeferredStub).
    // Either way the first call routes through the shared trampoline into lazyCompile, which compiles the one
    // method and data-patches the cell/slot, so later calls dispatch straight to the body.
    static final int MAXLAZY = 32768;
    static LazyMethod[] lzTab;     // deferred/lazy methods (reified: one LazyMethod per entry)
    static int    lzN;
    static long   lazyTrampAddr;   // the shared arg-preserving trampoline
    static long   lazyTrampLo, lazyTrampHi;    // the range over which its frame is live
    private static final boolean LAZY_TRACE  = false;   // per-method "jitc" compile trace over the UART (debug)
    /** Per-method stub: x9 = deferred index, then branch to the shared trampoline. */
    /**
     * Stubs, by address: {stub, lazy index}. A stub is 32 bytes of movz/movk/br — its whole purpose is to
     * be branched to from somewhere else, which is exactly the reference the collector cannot see. Recording
     * them lets the sweep say WHICH stub it is freeing, and the fault reporter say which method's stub the
     * PC landed in. 64 KiB = 4,096 entries; recording stops rather than wrapping.
     */
    // Placed in the free 256 KiB between CODE_INDEX's end (0x0374_0000) and CODE_PIN_BITMAP (0x0378_0000).
    // It used to be "64 KiB below the swept log" -- 0x037B_0000 -- which is INSIDE CODE_PIN_BITMAP
    // [0x0378_0000, 0x037C_0000): the two overlapped exactly, so stub-table writes clobbered the pin bits
    // for the arena's top 4 MiB and pin writes clobbered stub entries. Nothing detected it because a lost
    // pin only matters if that code is later swept, and a corrupted stub entry only makes stubIdxAt lie.
    // The new region is also 4x larger: at 16 bytes an entry the old 64 KiB held 4,096 stubs while a suite
    // run creates 10,000+, so stubIdxAt silently returned -1 for most of them -- which is why the
    // "it was the deferral stub for ..." diagnostics and the stub-mislink check kept coming back empty.
    // Fixed scratch at 0x0306_0000, in the free band between Board.DRIVER_SCRATCH (0x0305_0000..0x0305_9000, the
    // board drivers' buffers) and Loader.CODE_ROOTS (0x0310_0000); 0x030A_0000..0x0310_0000 stays free after it.
    //
    // TWO homes were wrong before this one, both from picking an address that LOOKED unused:
    //   0x0374_0000 (#145) overlays THREE tables -- Heap.STATS (histograms; STATS+128 is stub entry 8),
    //     VMGc.STALE_TAB (STATS+0x400 = entry 64) and VMGc.FREED_RANGES (0x0376_0000 = entry 8192). A suite
    //     run creates 10,000+ stubs, so all three were overwritten with stub buffer addresses. Visible in
    //     every boot log since as `reqCode: 16=33563776` -- 0x2002480, a code-arena pointer sitting where an
    //     allocation count belongs.
    //   0x0380_0000 is NOT the free hole below MARK_BITMAP it appears to be: VM.SEC_STACK_HI puts the
    //     secondary core stacks there, tops at 0x0390/0x03A0/0x03B0_0000 growing DOWN, so core 1's stack
    //     occupies exactly that address.
    // These are diagnostic tables, so nothing miscomputes -- but FREED_RANGES is what makes a swept-pc fault
    // readable (it is what diagnosed #146) and the histograms are the measurement the arena work is steered
    // by, so a corrupted one costs a whole investigation. Check a candidate address against EVERY table in
    // the 0x0300_0000+ band, not just the one being cleared.
    public static final long STUB_TAB = 0x0306_0000L;
    private static final long STUB_TAB_END = 0x030A_0000L;         // 256 KiB = 16,384 stubs
    public static long stubTabN;

    /* RETIRED PREDICATE -- `notInit`. Kept as a note because it records why two method kinds were once
     * excluded from the deferral route, and both exclusions turned out to be wrong.
     *
     * <p>{@code <clinit>} USED to be excluded too, on the stated grounds that "loading a class runs them".
     * The lazy-initialization arc (#114-#117) retired that premise: classes now initialize on first active
     * use, the eager list is empty, and initializers reach the engine through {@code ensureClinit} like any
     * other call. An eagerly compiled initializer for a class that never initializes is pure waste, and it
     * was expensive waste -- {@code java/lang/Character$UnicodeScript.<clinit>} compiles to 1.17 MB, was
     * emitted once per batch that loaded the class, and NEVER RAN. Four instances, ~4.7 MB, in an 8 MB
     * arena: roughly 70% of every code byte the VM emitted.
     *
     * <p>{@code <init>} DEFERS NOW TOO, and this predicate is retired. It had said constructors "are reached
     * through {@code invokespecial} on paths that assume a compiled body" -- a claim with no mechanism behind
     * it, and the deferral stub IS a compiled body as far as any caller can tell: the buffer is the method's
     * registered address AND its TIB slot, so a direct BL and a virtual blr both land on it either way.
     *
     * <p>ONE ENTRY PER METHOD, WHOSE CONTENTS CHANGE -- which is what HotSpot does and what this VM had
     * already built everywhere except here. {@code Method::_from_compiled_entry} is "Cache of: _code ?
     * _code->entry_point() : _adapter->c2i_entry()", one word that always holds a valid callable address, and
     * {@code Method::set_code} merely stores the new entry into it; call sites never change. joe-ng's
     * equivalent is this buffer, holding a deferral stub until the body exists. Leaving `<init>` eager kept a
     * SECOND shape alive, and the constructor-cell arc (reverted in 9e030aa) is what that cost: a cell was
     * added as a third, and it shadowed the registry.
     */

    /**
     * Install method {@code i}'s deferral stub into its (stub-sized) buffer instead of compiling the body:
     * capture everything {@code compile()} needs (blob/context/bytecode) into an lz entry, then emit a stub
     * that routes the first call through the shared trampoline. Because the buffer IS the method's registered
     * address AND its TIB vtable slot, every caller -- direct BL or virtual blr -- hits the stub, so the body
     * is compiled exactly once, on first use (memoized in {@link #lazyCompile}).
     */
    static void emitDeferredStub(int i)
    {
        lazyEnsureTables();
        if (lzN >= MAXLAZY) { Loader.capHalt(Magic.bytes("MAXLAZY-defer"), lzN); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        int idx = lzN;
        int reg = ClassRegistry.classRegByName(Loader.gView.thisNameOff);
        int pd = Loader.findPdByName(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
        lzTab[idx] = new LazyMethod();
        lzTab[idx].blob = Loader.gView.base;
        lzTab[idx].len = Loader.pdLen[pd];
        lzTab[idx].reg = reg;
        lzTab[idx].nameOff = 0;
        lzTab[idx].descOff = Loader.mDescOff[i];
        lzTab[idx].slot = DataReference.zero();             // no single slot to patch: the stub buffer is shared
        lzTab[idx].code = Loader.mCode[i];                         // compile straight from the captured bytecode
        lzTab[idx].codeLen = Loader.mLen[i];
        lzTab[idx].isStatic = Loader.mStatic[i];
        lzTab[idx].isSync = Loader.mSync[i];
        lzTab[idx].cache = CodeAddress.zero();
        if (lzN >= lzNHigh)
        {
            lzNHigh = lzN + 1;                      // high-water across reclaims
        }
        lzN += 1;
        Address buf = Address.fromLong(Loader.mBuf.get(i));        // stub-sized buffer allocated by sizeMethod
        noteStub(buf, idx);                             // ... and make it NAMEABLE: without this a fault landing
                                                        //   here reports stubIdx -1 and reads as an ordinary body
        Heap.pinCodeAt(buf);                                              // the batch-path twin of buildLazyCompileStub: same stub,
                                                        //   same lifetime problem, same fix (see the note there)
        lzTab[idx].stubLit = CodeAddress.fromAddress(writeLazyStub(buf, idx));
    }

    /**
     * On-demand compile: a method invoked ONLY reflectively was never RTA-reached, so it isn't compiled. Compile
     * JUST that method (its declaring class is already structure-loaded) into the code arena and register it, so
     * {@code Method.invoke} can call its buffer. Uses {@code compileReuseTib} so the class's already-filled TIB is
     * left alone (invoke dispatches to the buffer directly, not through the vtable). Returns the new registry
     * index, or -1. Limitation: the method's cross-class callees must already be compiled (no dep pull here); a
     * same-class callee it needs is compiled alongside it (they share this compile batch).
     */
    static int compileMethodOnDemand(DataReference type, ObjectReference nameArr)
    {
        return compileMethodOnDemand(type, nameArr, ObjectReference.nullReference());
    }

    /**
     * THE COMPILE CONTEXT IS STATIC, so exactly one task may be inside the compiler at a time. {@code
     * lazyCompile} has always taken {@link VM#loaderLock} for that reason; this path reached {@code compile}
     * without it, so a second task compiling here while another compiled elsewhere clobbered {@code gView.bytes}/
     * {@code gView.cp} mid-compile -- which surfaces as a NULL bytecode array inside the compiler
     * ({@code NullPointerException} in {@code Baseline.s4}, lowering a switch) rather than as anything
     * resembling a locking bug. The lock is RECURSIVE and owned by TASK, so nesting inside an
     * already-locked path costs nothing.
     *
     * <p>The lock covers the COMPILE only, never the running of an initializer: a {@code <clinit>} can block
     * on a monitor or spawn threads, and holding the loader lock across that would deadlock any task that
     * needs to compile before it can release.
     */
    static int compileMethodOnDemand(DataReference type, ObjectReference nameArr, ObjectReference descArr)
    {
        VM.loaderLock(VM.LOCK_ON_DEMAND);
        int r;
        try
        {
            r = compileMethodOnDemandLocked(type, nameArr, descArr);
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
        return r;
    }

    /** {@link #compileMethodOnDemand} restricted to one DESCRIPTOR ({@code descArr} 0 = any). Without it the walk
     *  stops at the first method of that name, which for an overloaded class is a coin toss between two bodies. */
    private static int compileMethodOnDemandLocked(DataReference type, ObjectReference nameArr, ObjectReference descArr)
    {
        int ci = ClassRegistry.classIndexByType(type.toAddress());
        // AN INTERFACE IS NOT REFUSED any more. It was, for having no TIB to reuse -- but this path compiles with
        // compileReuseTib, which never touches one (the reason compileSigOnDemand already compiles interface
        // STATICS). The refusal left every not-yet-called DEFAULT method unresolvable by reflection:
        // getDeclaredMethods on an interface answered EMPTY and getMethods/getMethod never saw its defaults
        // (ReflectLookupProbe: a one-method interface declared 0).
        if (ci >= ClassRegistry.clCount)
        {
            return -1;
        }
        DataReference blob = ClassRegistry.clTab[ci].base;
        int len = Loader.blobLenOf(blob.toAddress());
        Loader.parseConstPool(blob, len);                         // set up the class's compile state (as loadBodies does,
        Loader.parseFields();                                     //   minus the TIB fill): statics block + vtable scratch
        int reg = ClassRegistry.classRegByName(Loader.gView.thisNameOff);
        if (reg < 0)
        {
            return -1;
        }
        ClassRegistry.clTab[reg].resumeBuild(blob.toAddress());
        Loader.parseForMethods(blob.toAddress(), len);                        // gp at the methods table for the by-name method walk
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int nlen = (int) nameArr.toAddress().plus(16L).loadLong();
        Address nbase = nameArr.toAddress().plus(24L);
        int dlen = descArr.toLong() <= 0x1000L ? 0 : (int) descArr.toAddress().plus(16L).loadLong();
        Address dbase = descArr.toAddress().plus(24L);
        long code = 0L;
        int descOff = 0;
        int isStatic = 0;
        int isSync = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (Utf8.rawEqUtf8(nbase, nlen, blob.toAddress(), Loader.gView.cp[u2(p.plus(2))])
                    && (dlen == 0 || Utf8.rawEqUtf8(dbase, dlen, blob.toAddress(), Loader.gView.cp[u2(p.plus(4))])))
            {
                long c = Loader.findCode(blob.toAddress(), p.plus(8), attrs);
                if (c != 0L)
                {
                    code = c;
                    descOff = Loader.gView.cp[u2(p.plus(4))];
                    isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                    isSync = (u2(p) & 0x0020) != 0 ? 1 : 0;   // ACC_SYNCHRONIZED: the implicit monitor
                    break;
                }
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        if (code == 0L)
        {
            return -1;                                     // no such method, or abstract/native (no Code)
        }
        boolean reuseWas = Loader.compileReuseTib;              // a nested load resets it: restore, never clear
        Loader.compileReuseTib = true;                            // do NOT rebuild/refill the class's TIB
        int rcMark = Relocator.rcCount;                              // this compile's own reloc sites start here
        int rsMark = Relocator.rsCount;
        boolean outerCompiling = Loader.lzCompiling;              // a drain re-enters the compiler: save the whole note state
        boolean outerRetried = Loader.lzRetried;
        int outerPullN = Loader.lzPullN;
        Loader.lzCompiling = true;                                // collect the classes this body's statics NAME
        Loader.lzPullN = 0;
        Loader.lzRetried = false;
        int myRetry = Loader.odRetries;                       // THIS compile's retries so far: captured, and the
        Loader.odRetries = 0;                                 //   global cleared so a NESTED compile gets its own
        Loader.compile(code, ClassBytes.codeLength(code), descOff, isStatic, isSync);
        Loader.lzCompiling = outerCompiling;
        Loader.compileReuseTib = reuseWas;
        boolean pulled = Loader.lzPullN > 0;
        if (pulled)
        {
            Loader.drainPendingPulls();
            Relocator.truncateRelocs(rcMark);                        // drop the doomed body's reloc sites: patching them later
            Relocator.rsCount = rsMark;                              //   would write into a buffer the sweep may have reused
        }
        Loader.lzRetried = outerRetried;
        Loader.lzPullN = outerPullN;
        if (pulled && myRetry >= Loader.OD_RETRY_MAX)
        {
            Loader.reportRetryExhausted();                   // the retry pulled again: say so, never register silently
        }
        if (pulled && myRetry < Loader.OD_RETRY_MAX)
        {
            // Everything above is re-derived from (type, nameArr, descArr), so the retry is just this method
            // again -- with the pulled classes present, so the getstatic resolves to the real cell. The first
            // buffer is abandoned: nothing has registered it or stored it in a TIB slot, so no caller can have
            // run it. `odRetries` bounds it (Loader.OD_RETRY_MAX), since a class that is still missing after the pull
            // would otherwise re-note and recurse for ever.
            Loader.odRetries = myRetry + 1;
            int r = compileMethodOnDemand(type, nameArr, descArr);
            Loader.odRetries = 0;
            return r;
        }
        MethodRegistry.registerAll();                                     // register the just-compiled method(s) -> globalBuf
        Relocator.patchRelocs();                                     // resolve its cross-class calls (idempotent over prior relocs)
        if (dlen != 0)
        {
            return Reflection.methodResolveRegistryDesc(type.toAddress(), nameArr, descArr);
        }
        return Reflection.methodResolveRegistry(type.toAddress(), nameArr);
    }

    /** Note that {@code buf} is the deferral stub for lazy method {@code idx}. */
    static void noteStub(Address buf, int idx)
    {
        if (STUB_TAB + stubTabN * 16L + 16L <= STUB_TAB_END)
        {
            Address.fromLong(STUB_TAB).plus(stubTabN * 16L).store(buf);
            Address.fromLong(STUB_TAB).plus(stubTabN * 16L).plus(8L).store((long) idx);
            stubTabN = stubTabN + 1L;
        }
    }

    /** The lazy index of the stub containing {@code addr}, or -1. Stubs are 32 bytes. */
    public static long stubIdxAt(Address addr)
    {
        long i = 0;
        while (i < stubTabN)
        {
            long b = Address.fromLong(STUB_TAB).plus(i * 16L).loadLong();
            if (addr.toLong() >= b && addr.toLong() < b + 32L)
            {
                return Address.fromLong(STUB_TAB).plus(i * 16L).plus(8L).loadLong();
            }
            i += 1L;
        }
        return -1L;
    }

    /** Name the method a lazy index belongs to, for a diagnostic. */
    public static void printLazyName(long idx)
    {
        int k = (int) idx;
        if (k < 0 || k >= lzN || lzTab[k] == null)
        {
            Uart.write(Magic.bytes("<idx out of range>"));
            return;
        }
        Loader.printNameAt(ClassRegistry.clTab[lzTab[k].reg].base.toAddress(), ClassRegistry.clTab[lzTab[k].reg].nameOff);
        Uart.putc(0x2E);
        Loader.writeName(lzTab[k].blob.plus(lzTab[k].nameOff + 2), u2(lzTab[k].blob.plus(lzTab[k].nameOff)));
    }

    /**
     * Write the deferral stub for lazy method {@code idx} into {@code buf} (32 bytes) and return the address of
     * its TARGET LITERAL, or 0 if this stub has none:
     * <pre>
     *   +0   movz x17, #idx          the trampoline reads the index here
     *   +4   ldr  x16, [pc, #12]     the literal at +16
     *   +8   br   x16
     *   +12  nop                     pad: keeps the literal 8-byte aligned
     *   +16  .quad lazyTrampAddr     -> the compiled body, once there is one (see patchLazyStub)
     * </pre>
     * x17/x16, NOT x9/x10: x0.. are the argument registers, so a stub that scratches x9/x10 destroys the 10th
     * and 11th arguments of the very call it stands in for (this is what broke demo deep10); x16/x17 are the
     * architectural intra-procedure scratch pair and never arguments. A 0 trampoline would bake {@code br 0},
     * which writes no x30 and surfaces as an untraceable reboot, so checkTramp fails first.
     *
     * <p>THE TARGET IS DATA, NOT AN INSTRUCTION. This stub used to be {@code movz/movk x16, tramp; br x16} and
     * was never rewritten, so every call through it -- every caller of a method whose deferral stub IS its
     * registered address -- went stub -> trampoline (16 registers saved and restored) -> lazyCompile -> cache
     * hit -> branch, for the life of the VM. MEASURED (QEMU, SslEngineProbe, 2026-11-06): 58,174,133
     * lazyCompile calls, 99.99% cache hits, against 20,731 deferred methods; on the Pi, 3.83M calls and 19.3 s
     * inside lazyCompile over one HTTPS GET. Patching a 64-bit aligned literal is single-copy atomic, so another
     * core sees the trampoline or the body -- both correct -- and since no instruction changes there is no
     * cross-core I-cache maintenance and none of the ARM ARM's concurrent-modification-of-code rules apply
     * (MOVZ -> B is not in their permitted set). A misaligned buffer keeps the old, unpatchable form.
     */
    static Address writeLazyStub(Address buf, int idx)
    {
        VirtualDispatch.checkTramp(Address.fromLong(lazyTrampAddr), idx, 1);
        buf.store(A64Enc.movz(17, idx & 0xFFFF, 0));                                  // x17 = idx
        if ((buf.toLong() & 7L) != 0L)
        {
            buf.plus(4L).store(A64Enc.movz(16, (int) (lazyTrampAddr & 0xFFFF), 0));
            buf.plus(8L).store(A64Enc.movk(16, (int) ((lazyTrampAddr >> 16) & 0xFFFF), 1));
            buf.plus(12L).store(A64Enc.movk(16, (int) ((lazyTrampAddr >> 32) & 0xFFFF), 2));
            buf.plus(16L).store(A64Enc.br(16));
            Heap.publishCode(buf, buf.plus(20L));
            return Address.zero();
        }
        buf.plus(4L).store(A64Enc.ldrLitX(16, 12));                                      // x16 = [buf + 16]
        buf.plus(8L).store(A64Enc.br(16));
        buf.plus(12L).store(A64Enc.nop());
        buf.plus(16L).store(lazyTrampAddr);
        Heap.publishCode(buf, buf.plus(24L));
        return buf.plus(16L);
    }

    /**
     * Point lazy method {@code idx}'s deferral stub straight at {@code body}, so later calls through it skip the
     * trampoline. Called where the TIB slot / offset cell is patched, with the same body, so a caller sees what
     * the cache-hit path of lazyCompile would have returned. The body is pinned (a CODE->CODE edge whose only
     * holder is this literal), and the DSB orders its publication before the literal can be observed.
     */
    private static void patchLazyStub(int idx, long body)
    {
        long lit = lzTab[idx].stubLit.toLong();
        if (lit == 0L || body == 0L)
        {
            return;
        }
        Heap.pinCodeAt(Address.fromLong(body));
        Magic.dsb();
        Address.fromLong(lit).store(body);
    }

    private static Address buildLazyCompileStub(int idx)
    {
        Address buf = Heap.allocCode(32);
        noteStub(buf, idx);
        // A deferral stub outlives its own dispatch cell. Once the first call patches the cell to the compiled
        // body the stub looks unreferenced, and the sweep frees it -- correctly, by reachability. But the cell
        // is not its only caller: stale dispatch copies (an inherited TIB slot, an itable entry) still name the
        // stub, and calling one is HARMLESS by design -- it re-enters lazyCompile and returns the real body.
        // It turns fatal only once the stub has been swept: the space is reused and the branch lands in
        // whatever data now lives there. So pin it. 32 bytes each, and it is the safety net that makes every
        // stale dispatch copy correct.
        Heap.pinCodeAt(buf);
        lzTab[idx].stubLit = CodeAddress.fromAddress(writeLazyStub(buf, idx));
        return buf;
    }

    /**
     * The shared arg-preserving trampoline. Entered with x9 = deferred index, x0..x7 = the call's args, x30 =
     * caller return. Saves the arg registers + LR, calls {@code lazyCompile(idx)} (via the writer-stashed
     * {@code VM.lazyCompileAddr}) which returns the freshly-compiled buffer, restores the args + LR, and
     * tail-branches into the method so it sees the exact original call state.
     */
    static void buildLazyTramp()
    {
        Address buf = Heap.allocCode(256);
        // PINNED: reached through every deferral stub's immediate, which no trace reads. Its only root was the
        // conservative scan of the raw static lazyTrampAddr -- gone once the statics are read by kind (frames/GC
        // arc S6d).
        Heap.pinCodeAt(buf);
        long ca = VM.lazyCompileAddr;
        int w = 0;
        // Preserve x0..x15, not x0..x7. lazyCompile runs the WHOLE compiler between the entry to this
        // trampoline and the tail-branch, so every argument register the callee will read must survive it.
        // Saving only x0..x7 silently dropped the 9th argument onward -- garbage that shifted whenever
        // compilation order changed, which is exactly how demo deep10 printed a different wrong answer each
        // time the loader changed. The 16 extra loads/stores happen once per method, on its first call.
        buf.plus(w * 4L).store(A64Enc.subImm(StubFrames.STUB_SP, StubFrames.STUB_SP, StubFrames.STUB_FRAME)); // sub sp,sp,#144
        w += 1;
        int r = 0;
        while (r <= StubFrames.STUB_SAVE_HI)
        {
            buf.plus(w * 4L).store(A64Enc.strx(r, StubFrames.STUB_SP, StubFrames.STUB_SAVE0 + r * ObjectModel.WORD));
            w += 1;
            r += 1;
        }
        buf.plus(w * 4L).store(A64Enc.strx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF)); // LR at [sp+0]: what VM.unwind reads to pop this frame
        w += 1;
        w = StubFrames.emitSaveIndex(buf, w);         // x17 (the index) at [sp+136]: the GC walk types the args by it
        buf.plus(w * 4L).store(A64Enc.movReg(0, 17)); // x0 = idx (the stub put it in x17)
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (ca & 0xFFFF), 0)); // x16 = lazyCompile
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ca >> 16) & 0xFFFF), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ca >> 32) & 0xFFFF), 2));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.blr(16)); // x0 = fresh buffer
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movReg(16, 0)); // x16 = target (outside the restore set)
        w += 1;
        // A zero target here would `br` to address 0 -- and `br` does not write x30, so the wild
        // branch would erase its own provenance. Check while the frame is still up: the call site
        // is on it (lazy-compile trampoline).
        w = VirtualDispatch.emitTailGuard(buf, w, 1);
        r = 0;
        while (r <= StubFrames.STUB_SAVE_HI)
        {
            buf.plus(w * 4L).store(A64Enc.ldrx(r, StubFrames.STUB_SP, StubFrames.STUB_SAVE0 + r * ObjectModel.WORD));
            w += 1;
            r += 1;
        }
        buf.plus(w * 4L).store(A64Enc.ldrx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF)); // (see registerTrampFrames)
        w += 1;
        lazyTrampHi = buf.toLong() + w * 4L;
        buf.plus(w * 4L).store(A64Enc.addImm(StubFrames.STUB_SP, StubFrames.STUB_SP, StubFrames.STUB_FRAME)); // add sp,sp,#144
        w += 1;
        buf.plus(w * 4L).store(A64Enc.br(16)); // tail-call the fresh method
        w += 1;
        Heap.publishCode(buf, buf.plus(w * 4L));
        lazyTrampAddr = buf.toLong();
        lazyTrampLo = buf.toLong() + 4L;
        StubFrames.registerTrampFrames();
    }

    /**
     * THE COMPILE CONTEXT IS STATIC, so exactly one task may be inside the compiler at a time. {@code
     * lazyCompile} has always taken {@link VM#loaderLock} for that reason; this path reached {@code compile}
     * without it, so a second task compiling here while another compiled elsewhere clobbered {@code gView.bytes}/
     * {@code gView.cp} mid-compile -- which surfaces as a NULL bytecode array inside the compiler
     * ({@code NullPointerException} in {@code Baseline.s4}, lowering a switch) rather than as anything
     * resembling a locking bug. The lock is RECURSIVE and owned by TASK, so nesting inside an
     * already-locked path costs nothing.
     *
     * <p>The lock covers the COMPILE only, never the running of an initializer: a {@code <clinit>} can block
     * on a monitor or spawn threads, and holding the loader lock across that would deadlock any task that
     * needs to compile before it can release.
     */
    static long compileSigOnDemand(Address clsU, Address nameU, Address descU)
    {
        VM.loaderLock(VM.LOCK_SIG_DEMAND);
        long r;
        try
        {
            r = compileSigOnDemandLocked(clsU, nameU, descU);
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
        return r;
    }

    /**
     * Compile one method of an already structure-loaded class, chosen by name AND descriptor, and return its
     * buffer. The last resort for a link stub: {@link DynamicLinker#bufBySigU}'s three tiers all answer through a DISPATCH
     * table (a registered buffer, a static cell, a vtable slot), and a method can be perfectly callable while
     * appearing in none of them.
     *
     * <p>The case that forced this is a <b>static method on an interface</b>. {@code registerInterface} walks
     * only {@code isVirtual} methods, to give them itable indices; a static one gets no itable index, no
     * vtable slot and no static cell, so it is registered nowhere at all. {@code Arguments.of} in the JUnit
     * shim is exactly that, and it is what a reflectively-reached {@code @MethodSource} factory calls. The
     * older reflective path ({@link #compileMethodOnDemand}) refuses interfaces outright for the same reason
     * it has no TIB to reuse — but {@code compileReuseTib} already means we never touch one.
     *
     * <p>Matching on the DESCRIPTOR as well as the name is not optional here: {@code of(T)} and the varargs
     * {@code of(T...)} are different methods at the same name, and compiling the wrong one links the call
     * site to a body with the wrong signature. Returns 0 if there is no such method or it has no Code.
     */
    private static long compileSigOnDemandLocked(Address clsU, Address nameU, Address descU)
    {
        int reg = DynamicLinker.regBySigU(clsU);
        if (reg < 0)
        {
            return 0L;
        }
        DataReference blob = ClassRegistry.clTab[reg].base;
        int len = Loader.blobLenOf(blob.toAddress());
        Loader.parseConstPool(blob, len);                      // rebuild this class's compile state (as loadBodies
        Loader.parseFields();                                  //   does, minus the TIB fill)
        ClassRegistry.clTab[reg].resumeBuild(blob.toAddress());              // compileReuseTib means the TIB is unused here
        Loader.parseForMethods(blob.toAddress(), len);
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        long code = 0L;
        int descOff = 0;
        int isStatic = 0;
        int isSync = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (Loader.utf8EqAt(blob.toAddress(), Loader.gView.cp[u2(p.plus(2))], nameU, 0)
                    && Loader.utf8EqAt(blob.toAddress(), Loader.gView.cp[u2(p.plus(4))], descU, 0))
            {
                long c = Loader.findCode(blob.toAddress(), p.plus(8), attrs);
                if (c != 0L)
                {
                    code = c;
                    descOff = Loader.gView.cp[u2(p.plus(4))];
                    isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                    isSync = (u2(p) & 0x0020) != 0 ? 1 : 0;   // ACC_SYNCHRONIZED: the implicit monitor
                    break;
                }
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        if (code == 0L)
        {
            return 0L;                                  // no such signature, or abstract/native (no Code)
        }
        int rcMark = Relocator.rcCount;                           // patch only THIS compile's relocs (see patchRelocsFrom)
        int rsMark = Relocator.rsCount;
        boolean reuseWas = Loader.compileReuseTib;              // a nested load resets it: restore, never clear
        Loader.compileReuseTib = true;
        boolean outerCompiling = Loader.lzCompiling;
        boolean outerRetried = Loader.lzRetried;
        int outerPullN = Loader.lzPullN;
        Loader.lzCompiling = true;                             // same deferred-pull retry as compileMethodOnDemand
        Loader.lzPullN = 0;
        Loader.lzRetried = false;
        int myRetry = Loader.odRetries;                       // THIS compile's retries so far: captured, and the
        Loader.odRetries = 0;                                 //   global cleared so a NESTED compile gets its own
        Loader.compile(code, ClassBytes.codeLength(code), descOff, isStatic, isSync);
        Loader.lzCompiling = outerCompiling;
        Loader.compileReuseTib = reuseWas;
        boolean pulled = Loader.lzPullN > 0;
        if (pulled)
        {
            Loader.drainPendingPulls();
            Relocator.truncateRelocs(rcMark);
            Relocator.rsCount = rsMark;
        }
        Loader.lzRetried = outerRetried;
        Loader.lzPullN = outerPullN;
        if (pulled && myRetry >= Loader.OD_RETRY_MAX)
        {
            Loader.reportRetryExhausted();                   // the retry pulled again: say so, never register silently
        }
        if (pulled && myRetry < Loader.OD_RETRY_MAX)
        {
            Loader.odRetries = myRetry + 1;                           // re-derived from (clsU, nameU, descU), like the twin above
            long r = compileSigOnDemand(clsU, nameU, descU);
            Loader.odRetries = 0;
            return r;
        }
        MethodRegistry.registerAll();
        Relocator.patchRelocsFrom(rcMark, rsMark);                // its own callees, including further link stubs
        // INITIALIZE WHAT THIS BODY TOUCHES, before it can run -- what lazyCompileLocked does and this path
        // did not. The compile above recorded every cross-class getstatic/new site it contains (JVMS 5.5
        // active uses); without the drain they are noted and never acted on, so the body reads those classes'
        // statics while they are still 0. A null that looks exactly like a value.
        //
        // This tier is the one that compiles a STATIC INTERFACE METHOD, which is registered nowhere else, and
        // that is where it bit: JUnit's `CancellationToken.disabled()` is `getstatic
        // DisabledCancellationToken.INSTANCE; areturn`. With that class never initialized the cell read 0, so
        // `disabled()` returned null, and `Objects.requireNonNullElseGet(x, CancellationToken::disabled)`
        // threw `NullPointerException: supplier.get()` -- three frames and one lambda away from the cause.
        ClassInitializer.drainPendingInit();
        return DynamicLinker.bufBySigU(clsU, nameU, descU);
    }

    /**
     * Pull the functional interface of every {@code invokedynamic} in a just-pulled blob.
     *
     * <p>A lambda's interface is named ONLY as the return type inside the indy's own descriptor -- never as a
     * {@code CONSTANT_Class} -- so no dependency scan sees it. Normally it arrives with the lambda's CONSUMER,
     * which does name it (an {@code invokeinterface} on the SAM), and that is why lambdas work at all. A class
     * pulled INCREMENTALLY has no such guarantee: a test method reached through {@code Method.invoke} compiles
     * on demand and its {@code Assertions} call is resolved later still through a link stub, so when the
     * lambda class is built neither the consumer nor the interface exists.
     *
     * <p>{@code buildLambdaTib} then resolves the interface to Type 0 -- which is also the itable directory's
     * END SENTINEL, so the lambda satisfies NO interface and every dispatch on it throws NPE from the
     * directory miss, inside whatever library received it.
     *
     * <p>Here rather than at the lambda's compile: a demand-load parses every blob and can collect, and the
     * in-progress code buffer is not reachable yet -- doing this mid-compile swept it, and the method ran off
     * a zeroed buffer ({@code ec=0} at offset +0).
     */
    static void pullIndyIfaces(DataReference blob)
    {
        Loader.parseConstPool(blob, Loader.blobLenOf(blob.toAddress()));
        int c = 1;
        while (c < Loader.gView.cpCount)
        {
            if (Loader.gView.cpTag[c] == 18)
            {
                pullIndyIface(c);
            }
            c += 1;
        }
    }

    /** Pull the class named by the return type of the indy descriptor at cp index {@code c}, if any. */
    private static void pullIndyIface(int c)
    {
        int nt = u2(Loader.gView.base.plus(Loader.gView.cp[c] + 2));                // InvokeDynamic { u2 bsmAttrIdx, u2 nameAndTypeIdx }
        Address p = Loader.gView.base.plus(Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[nt] + 2))] + 2);   // NameAndType -> descriptor Utf8 body
        while (u1(p) != ')')
        {
            p = p.plus(1);
        }
        p = p.plus(1);
        if (u1(p) != 'L')
        {
            return;                                     // a primitive return names no class
        }
        Address q = p.plus(1);
        while (u1(q) != ';')
        {
            q = q.plus(1);
        }
        int off = Loader.internIndyNameAt(p.plus(1), q.diff(p).toInt() - 1);
        if (DynamicLinker.regBySigU(Loader.indyNameBuf.plus(off)) >= 0)
        {
            return;                                     // already a REGISTERED class -- re-adding its blob
        }                                               //   re-registers it (lifecycle DIFF, then a wild branch)
        // registerNameFromDir, not pullClass: it dedupes by blob ADDRESS (addBlob does), skips
        // java/lang/Object, and -- the part that matters -- honours the DENYLIST. pullClass(byte[]) goes
        // straight to the classDir, which is how the deferred-`new` path once pulled a denylisted class.
        long pulled = Closure.registerNameFromDir(Loader.indyNameBuf, off);
    }

    /**
     * Compile deferred method {@code idx} at call time: restore its class's compile context, re-find the
     * method, compile just it, install the fresh buffer into the TIB slot, and return it. Called only from
     * the trampoline (via the stashed address). Guarded so the writer's dead force-reference is a no-op.
     */
    static long lazyCompile(int idx)
    {
        if (idx < 0 || lzTab == null || idx >= lzN)
        {
            // SILENT UNTIL NOW. On the WRITER this is a dead force-reference and 0 is right; on METAL it is
            // reached only from a deferral stub, whose idx was baked at emit time -- so a miss here means a
            // stub outlived the table it indexes, and the 0 becomes `br 0` in the trampoline. Note the stub
            // carries idx as a SINGLE movz, i.e. truncated to 16 bits.
            if (lzTab != null)
            {
                Uart.write(Magic.bytes("\n  LAZY INDEX OUT OF RANGE (stub outlived its table): idx="));
                VM.printDec(idx);
                Uart.write(Magic.bytes(" lzN="));
                VM.printDec(lzN);
                Uart.write(Magic.bytes(" cap="));
                VM.printDec(lzTab.length);
                Uart.write(Magic.bytes(" everDeepest="));
                VM.printDec(lzNHigh);
                Uart.putc(0x0A);
                VirtualDispatch.reportBakeMemoFor(idx);       // who is still handing this index out?
            }
            return 0L;                                  // dead force-reference (writer) / bad index
        }
        if (lzTab[idx].cache.toLong() != 0L)
        {
            return lzTab[idx].cache.toLong();                         // memoized: compile once, however many callers hit the stub
        }
        VM.loaderLock(VM.LOCK_LAZY);                                // SMP: the compile context is static -- one compiler at a time
        long done;
        try
        {
            done = lazyCompileLocked(idx);
        }
        finally
        {
            // A THROW INSIDE THE LOCKED REGION MUST NOT STRAND THE LOCK, and it did. Neither call was
            // inside a try/finally and VM.unwind touches none of loaderOwner/loaderDepth/loaderUnlock, so
            // an exception exiting non-locally skipped the unlock and the lock was never handed back --
            // every other thread then blocked on it for the rest of the boot. MEASURED on the launcher:
            // three waiters, `held` growing 29,282ms -> 229,224ms -> 299,053ms while the completed-release
            // counter sat at `rel 12865` for ALL THREE -- not one outermost release in 270 seconds -- and
            // `depth` stuck at 3/3/2 rather than unwinding to 0. Deterministic: QEMU stops at the same
            // `rel 12865`.
            //
            // This path genuinely can throw: lazyCompile -> drainPendingPulls -> loadClassIncremental ->
            // loadAll, whose tail RUNS QUEUED <clinit>s through Magic.call0 -- guest code, and JUnit throws
            // as ordinary control flow. (That the lock is held across guest code at all violates the
            // invariant clinitEntryOf states and observes; this fixes the leak, not that.)
            VM.loaderUnlock();
        }
        if (done != 0L && !DynamicLinker.plausibleCode(Address.fromLong(done)))
        {
            // The third and last tail-branching path (with the link stub and the late-virtual resolve). Its
            // trampoline branches to whatever comes back with nothing checking it, so a bogus buffer here is a
            // wild branch at a pc no table claims -- and if it happens on a freshly spawned task there is not
            // even a caller on the stack to name. Say what was being compiled instead.
            Uart.write(Magic.bytes("\n  BAD LAZY TARGET buf="));
            VM.printHex(done);
            Uart.write(Magic.bytes(" for "));
            Loader.printCompiling();
            Uart.putc(0x0A);
            return 0L;
        }
        return done;
    }

    /** {@link #lazyCompile}'s body, run under the loader lock. */
    private static long lazyCompileLocked(int idx)
    {
        if (lzTab[idx].cache.toLong() != 0L)
        {
            return lzTab[idx].cache.toLong();                    // another core compiled it while we waited for the lock
        }
        ClassInitializer.ensureClinit(lzTab[idx].reg);                   // JVMS 5.5 barrier: first call into the class initializes it.
                                                        // BEFORE restoreCtxForCompile -- the initializer re-enters here
                                                        // and each nested compile clobbers the g* context.
        restoreCtxForCompile(DataReference.fromAddress(lzTab[idx].blob.toAddress()), lzTab[idx].len, lzTab[idx].reg);
        long code;
        int len;
        int descOff;
        int isStatic;
        int isSync;
        if (lzTab[idx].code != 0L)                           // genuine deferral: the body was captured, compile it
        {
            code = lzTab[idx].code;
            len = lzTab[idx].codeLen;
            descOff = lzTab[idx].descOff;
            isStatic = lzTab[idx].isStatic;
            isSync = lzTab[idx].isSync;
        }
        else                                            // 1b/1c: re-find the method by name+descriptor
        {
            FoundMethod f = Loader.findMethodByOffsets(lzTab[idx].nameOff, lzTab[idx].descOff);
            if (f == null)
            {
                // SILENT UNTIL NOW, and the silence is what made it untraceable: 0 flows back through
                // lazyCompile (whose BAD LAZY TARGET guard only catches IMPLAUSIBLE buffers, not zero)
                // into the trampoline, which tail-branches to address 0 -- and `br` writes no x30, so the
                // fault report then names whatever healthy call ran last.
                Uart.write(Magic.bytes("\n  LAZY RE-FIND FAILED (method not in its blob): idx="));
                VM.printDec(idx);
                Uart.write(Magic.bytes(" name="));
                Loader.printUtf8Capped(lzTab[idx].blob.plus(lzTab[idx].nameOff));
                Uart.write(Magic.bytes(" desc="));
                Loader.printUtf8Capped(lzTab[idx].blob.plus(lzTab[idx].descOff));
                Uart.write(Magic.bytes(" blob=0x"));
                VM.printHex(lzTab[idx].blob.toLong());
                Uart.write(Magic.bytes(" reg="));
                VM.printDec(lzTab[idx].reg);
                Uart.putc(0x0A);
                return 0L;
            }
            code = f.code;
            len = ClassBytes.codeLength(code);
            descOff = f.descOff;
            isStatic = f.isStatic;
            isSync = f.isSync;
        }
        // M8 endgame (the Loader USES baked java.base): if the writer baked this exact method and
        // listed it as loader-linkable, run the image's compiled stock code instead of compiling our
        // own copy -- the JikesRVM boot-image contract. Field offsets and direct calls inside baked
        // code are world-independent; the link table already excludes anything that isn't.
        if (DynamicLinker.BAKED_LINK)
        {
            int bkName = lzTab[idx].nameOff;
            if (bkName == 0)
            {
                bkName = Loader.findNameByCode(lzTab[idx].code);    // deferral entries capture only bytecode
            }
            long baked = DynamicLinker.bakedBuf(bkName, descOff);
            if (baked != 0L)
            {
                if (Loader.LOAD_LOG)
                {
                    Uart.write(Magic.bytes("  baked "));
                    Loader.printNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
                    Uart.putc(0x2E);
                    Loader.printNameAt(Loader.gView.base.toAddress(), bkName);
                    Uart.putc(0x0A);
                }
                lzTab[idx].cache = CodeAddress.fromLong(baked);
                if (!lzTab[idx].slot.isZero())
                {
                    lzTab[idx].slot.toAddress().store(baked);
                }
                patchLazyStub(idx, baked);
                return baked;
            }
        }
        if (LAZY_TRACE)                                  // per-method compile trace (debug; off by default)
        {
            Uart.write(Magic.bytes("  jitc "));
            Loader.printNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
            Uart.putc(0x2E);
            int trName = lzTab[idx].nameOff;            // deferral entries capture bytecode, not the name
            if (trName == 0)
            {
                trName = Loader.findNameByCode(lzTab[idx].code);
            }
            Loader.printNameAt(lzTab[idx].blob.toAddress(), trName);
            Loader.printNameAt(lzTab[idx].blob.toAddress(), lzTab[idx].descOff);
            Uart.putc(0x0A);
        }
        boolean reuseWas = Loader.compileReuseTib;              // a nested load resets it: restore, never clear
        Loader.compileReuseTib = true;                         // keep this class's TIB (already filled)
        int rcMark = Relocator.rcCount;                           // this compile's own reloc sites start here
        int rsMark = Relocator.rsCount;
        Loader.lzCompiling = true;                             // collect the classes this body actively uses
        Loader.lzPullN = 0;
        Loader.lzRetried = false;
        long buf = Loader.compile(code, len, descOff, isStatic, isSync);
        Loader.lzCompiling = false;
        if (Loader.lzPullN > 0)
        {
            // This body reads a static of a class nothing pulled. The compile is OVER, so loading is safe now
            // (mid-compile it would collect the in-progress buffer). Load, then compile the SAME body again
            // with the class present, so its getstatic resolves to the real cell instead of the zero one.
            //
            // The first buffer is simply abandoned -- unreachable, so the next sweep takes it. That is the
            // whole reason to retry HERE rather than one level up: nothing has registered it, cached it, or
            // stored it into a TIB slot yet, so no caller can ever have run the doomed code.
            Loader.drainPendingPulls();
            Relocator.truncateRelocs(rcMark);                     // and drop its reloc sites: patching them later would
            Relocator.rsCount = rsMark;                           //   write into a buffer the sweep may already have reused
            Loader.lzRetried = true;                           // once only -- a class that is still missing reads null
            restoreCtxForCompile(DataReference.fromAddress(lzTab[idx].blob.toAddress()), lzTab[idx].len, lzTab[idx].reg);   // the pull clobbered g*
            if (lzTab[idx].code == 0L)                    // a captured body needs nothing re-found: it is still in hand
            {
                FoundMethod f = Loader.findMethodByOffsets(lzTab[idx].nameOff, lzTab[idx].descOff);
                code = FoundMethod.codeOf(f);
                if (f != null)
                {
                    len = ClassBytes.codeLength(code);
                    descOff = f.descOff;
                    isStatic = f.isStatic;
                    isSync = f.isSync;
                }
            }
            Loader.lzCompiling = true;
            buf = Loader.compile(code, len, descOff, isStatic, isSync);
            Loader.lzCompiling = false;
        }
        Loader.compileReuseTib = reuseWas;
        registerCoCompiled();                           // the same-class callees compile() built beside it
        Relocator.patchRelocsFrom(rcMark, rsMark);                // batch-end patchRelocs is long past: resolve OUR sites now,
        if (buf == 0L)                                  // or a `bl 0` wild-branches to address 0 (see patchRelocsFrom)
        {
            // An index alone is unchaseable -- it is a slot number in a table rebuilt every batch. Name the
            // method the same way LAZY_TRACE does; the context is restored, so gView (and its thisNameOff) is valid.
            Uart.write(Magic.bytes("  compile returned null for "));
            Loader.printNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
            Uart.putc(0x2E);
            int nullName = lzTab[idx].nameOff;
            if (nullName == 0)
            {
                nullName = Loader.findNameByCode(lzTab[idx].code);
            }
            Loader.printNameAt(lzTab[idx].blob.toAddress(), nullName);
            Loader.printNameAt(lzTab[idx].blob.toAddress(), lzTab[idx].descOff);
            Uart.putc(0x0A);
            Loader.capHalt(Magic.bytes("lazy-compile-null"), idx);   // the trampoline would `br 0` -- halt with a name instead
        }
        rememberLazyBody(idx, buf);
        lzTab[idx].cache = CodeAddress.fromLong(buf);                         // memoize BEFORE draining: an initializer we are about
                                                        // to run may call straight back into this method
        ClassInitializer.drainPendingInit();                             // initialize what the body touches -- still before it runs
        if (!lzTab[idx].slot.isZero())                       // 1b/1c: point the offset cell at the buffer
        {
            lzTab[idx].slot.toAddress().store(buf);
        }
        patchLazyStub(idx, buf);                         // ... and the deferral stub itself, for every other caller
        return buf;
    }

    /**
     * Register the SAME-CLASS CALLEES that {@link Loader#compile} built beside the method it was asked for. Its
     * discover pass ({@code scanCallees}) adds every same-class {@code invokestatic}/{@code invokespecial}
     * target as {@code m[1..]}, each in its own buffer, and the caller BLs straight to them -- so they run.
     * The lazy and {@code <clinit>} paths registered only {@code m[0]}, leaving every other body in a code
     * block no registry entry claimed: a frame there printed as {@code <unclaimed pc ... after X.<init>>}, a
     * {@code StackTraceElement} for it was {@code <vm>.<unknown>}, and the uncaught-exception walk stopped at
     * it. The constructor a public one reaches by {@code this(...)} is exactly such a callee, which is why
     * every NetDemo boot carried one unclaimed pc in {@code Socket.<init>} (MEASURED, CtorChainTraceProbe:
     * the {@code this(...)} target compiled through {@code sizeMethod} with no lazy compile of its own).
     *
     * <p>APPENDED, as {@link MethodRegistry#registerAll} does on the other on-demand paths: an entry already pointing at
     * the method's deferral stub stays valid (the stub still compiles a copy on its own first call), and the
     * new one claims the body that is actually running. Called right after the final compile, before
     * {@code patchRelocsFrom} can start anything that reuses the {@code m[]} tables.
     */
    static void registerCoCompiled()
    {
        int i = 1;
        while (i < Loader.mCount)
        {
            if (Loader.mDefer[i] == 0 && Loader.mBuf.get(i) != 0L)
            {
                int nameOff = Loader.findNameByCode(Loader.mCode[i]);
                if (nameOff != 0)
                {
                    MethodRegistry.register(Loader.gView.base.toAddress(), Loader.gView.thisNameOff, nameOff, Loader.mDescOff[i], Loader.mBuf.get(i), Loader.mLine.get(i), Loader.mSrc.get(i),
                             accessInContext(Loader.gView.base.toAddress(), nameOff, Loader.mDescOff[i]));
                }
            }
            i += 1;
        }
    }

    /**
     * The registry entry for the method {@code blob} declares as {@code nameOff}+{@code descOff}, or -1 -- the
     * LOWEST such index, which is the entry the linear scan this replaces returned. Through the class+name index
     * (rgBucket): every entry of the class being compiled is filed under utf8Hash2(its name, the method name), and
     * the compile context is restored here, so gView.thisNameOff names that class. MEASURED (qprof, SslEngineProbe,
     * QEMU 2026-11-06): the scan of every registry entry, once per lazy compile, was 27 of the 40 samples of
     * lazyCompile's OWN work -- the Pi's ~1.9 ms per call of it outside compile/load/clinit/GC.
     */
    private static int lazyBodyEntry(Address blob, int nameOff, int descOff)
    {
        MethodRegistry.buildRegIndex();
        int best = -1;
        int i = MethodRegistry.rgBucket[Utf8.utf8Hash2(blob, Loader.gView.thisNameOff, blob, nameOff) & (MethodRegistry.RGTAB - 1)];
        while (i >= 0)
        {
            if ((best < 0 || i < best) && MethodRegistry.rgTab[i].base.EQ(DataReference.fromAddress(blob)) && MethodRegistry.rgTab[i].nameOff == nameOff
                    && MethodRegistry.rgTab[i].descOff == descOff)
            {
                best = i;
            }
            i = MethodRegistry.rgNext[i];
        }
        return best;
    }

    /**
     * Point the method registry at a freshly lazy-compiled body. {@link StackTrace#printFrameAt} names a PC by the
     * nearest registered buffer at-or-below it, so an unregistered fresh buffer makes every frame in that
     * method report as whatever unrelated method happens to sit below it (the socket stack traced as
     * {@code InternalError.<init>} until this landed). A celled static has no registry entry at all, so it
     * gets one here; a deferred method has one pointing at its stub, which is updated in place — which also
     * lets later direct calls link straight to the body instead of through the stub.
     */
    private static void rememberLazyBody(int idx, long buf)
    {
        int nameOff = lzTab[idx].nameOff;
        if (nameOff == 0)
        {
            nameOff = Loader.findNameByCode(lzTab[idx].code);   // deferral entries capture bytecode, not the name
        }
        int i = lazyBodyEntry(lzTab[idx].blob.toAddress(), nameOff, lzTab[idx].descOff);
        if (i >= 0)
        {
            MethodRegistry.rgTab[i].buf = CodeAddress.fromLong(buf);
            Relocator.markRegDirty(i);                             // its memoised call sites now want the body
            MethodRegistry.rgTab[i].line = CodeAddress.fromLong(Loader.mLine.get(0));
            MethodRegistry.rgTab[i].src = Loader.mSrc.get(0);
            return;
        }
        MethodRegistry.register(lzTab[idx].blob.toAddress(), Loader.gView.thisNameOff, nameOff, lzTab[idx].descOff, buf, Loader.mLine.get(0), Loader.mSrc.get(0),
                 accessInContext(lzTab[idx].blob.toAddress(), nameOff, lzTab[idx].descOff));
    }

    /**
     * The real {@code access_flags} of the method {@code blob} declares as {@code nameOff}+{@code descOff},
     * read from the methods table ALREADY IN THE COMPILE CONTEXT, or 0 if it declares no such method.
     *
     * <p>THIS EXISTS BECAUSE {@link #rememberLazyBody} USED TO PASS A LITERAL 0, and a registry entry's access
     * word is what {@code Method.getModifiers} answers and what {@code AccessibleObject.checkAccess} tests. A
     * 0 there reads as PACKAGE-PRIVATE -- no {@code ACC_PUBLIC} bit -- so a reflective call on a method that
     * is plainly {@code public} was refused with {@code IllegalAccessException} whenever the caller sat in a
     * different package. It reached only methods registered HERE, i.e. a celled static whose body compiled on
     * first call, which is why it survived: a method already registered by {@code registerAll} has the real
     * flags and {@code rememberLazyBody} only updates its buffer.
     *
     * <p>NO RE-PARSE, deliberately. {@code methodInfoPos} answers the same question by calling
     * {@code parseForMethods}, which moves the {@code g*} cursor -- and this runs INSIDE a lazy compile, whose
     * context that would clobber. The walk is the one {@link MethodRegistry#registerAll} already does per method, over the
     * table this compile is standing on; it is guarded on the blob matching so a caller in some other context
     * gets 0 (today's answer) rather than another class's flags.
     */
    private static int accessInContext(Address blob, int nameOff, int descOff)
    {
        if (blob.NE(Loader.gView.base.toAddress()) || Loader.gView.methodsStart.isZero())
        {
            return 0;
        }
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (Loader.utf8EqAt(Loader.gView.base.toAddress(), Loader.gView.cp[u2(p.plus(2))], Loader.gView.base.toAddress(), nameOff)
                    && Loader.utf8EqAt(Loader.gView.base.toAddress(), Loader.gView.cp[u2(p.plus(4))], Loader.gView.base.toAddress(), descOff))
            {
                return u2(p);
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return 0;
    }

    /** Re-establish the g* compile context for an already-structure-registered class (loadBodies' preamble). */
    static void restoreCtxForCompile(DataReference bytes, int len, int reg)
    {
        if (reg >= 0 && ClassRegistry.clTab[reg].state < RVMClass.ST_RESOLVED)
        {
            // Lifecycle guard: a lazy compile needs the class's STRUCTURE (field layout, statics,
            // vtable numbering, Type/TIB). Firing before phase A completes would compile against a
            // half-built context and corrupt silently -- halt loudly instead.
            Loader.capHalt(Magic.bytes("lifecycle-compile"), reg);
        }
        Loader.parseConstPool(DataReference.fromAddress(bytes.toAddress()), len);
        Loader.parseFields();
        ClassRegistry.clTab[reg].resumeBuild(bytes.toAddress());
        Loader.provideKnownStatics();
    }

    /**
     * The offset-table cell a direct call should indirect through, or 0 for a normal `bl`. Every static is
     * celled at structure registration, so the caller emits `ldr x16,[cell]; blr x16` and the cell resolves
     * itself on first call. Runs at compile time in both the size and emit passes, and dedups by target, so
     * both passes see the same fixed 5-word sequence and sizing stays consistent.
     */
    static long lazyStaticCell(int methodCp)
    {
        if (DynamicLinker.dlTab == null)
        {
            return 0L;                                  // no cells yet (nothing celled): normal BL path
        }
        return dlCellFor(Loader.refClassNameOff(methodCp), Loader.mrefNameOff(methodCp), Loader.mrefDescOff(methodCp));
    }

    /**
     * The phase-A cell for a static/special call ref, or 0. Resolution follows JVMS order: the ref'd class
     * first, then its superclasses — an INHERITED static is named through the subclass by javac (e.g.
     * {@code ArrayList.subListRangeCheck}, declared on {@code AbstractList}). Without the walk such a call
     * finds no cell, and no registry entry either (a metadata-only class's celled statics are never eagerly
     * compiled or registered), so it would trap as an unresolved callee.
     */
    private static long dlCellFor(int classOff, int nameOff, int descOff)
    {
        long cell = dlCellAt(Loader.gView.base.toAddress(), classOff, nameOff, descOff);
        if (cell != 0L)
        {
            return cell;
        }
        int pd = Loader.findPdByName(Loader.gView.base.toAddress(), classOff);
        while (pd >= 0 && Loader.pdSuperOff[pd] != 0)
        {
            int spd = Loader.findPdByName(Loader.pdBase.get(pd), Loader.pdSuperOff[pd]);
            if (spd < 0)
            {
                return 0L;
            }
            cell = dlCellAt(Loader.pdBase.get(spd), Loader.pdNameOff[spd], nameOff, descOff);
            if (cell != 0L)
            {
                return cell;
            }
            pd = spd;
        }
        return 0L;
    }

    /** Cell for the method (name, desc in gView.base) declared by the class named at {@code classOff} in
     *  {@code clsBase}, or 0 if that exact class has no such phase-A cell. */
    private static long dlCellAt(Address clsBase, int classOff, int nameOff, int descOff)
    {
        return DynamicLinker.dlCellOf(clsBase, classOff, Loader.gView.base.toAddress(), nameOff, Loader.gView.base.toAddress(), descOff);
    }

    /** M8 phase-A: at structure registration of the gated class, allocate an offset cell + lazy stub for each
     *  of its (static) methods, captured straight from the classfile method table (available at phase A via
     *  gView.methodsStart). The cells live in the {@code dl*} table so a later caller finds them regardless of load
     *  order; each carries an lz entry that compiles the body on first call and data-patches the cell. */
    static void armPhaseACells()
    {
        if (!stage2Gated(Loader.gView.base.toAddress(), Loader.gView.thisNameOff))
        {
            return;
        }
        lazyEnsureTables();
        if (DynamicLinker.dlTab == null)
        {
            DynamicLinker.dlTab = new DynLink[MAXLAZY];
            DynamicLinker.dlN = 0;
        }
        int reg = ClassRegistry.clCount - 1;                          // this class's registry index (registerClassStructure did ClassRegistry.clCount++)
        int pd = Loader.findPdByName(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
        int blobLen = Loader.pdLen[pd];
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        int armed = 0;
        while (m < mcount)
        {
            int access = u2(p);
            int attrs = u2(p.plus(6));
            int nameOff = Loader.gView.cp[u2(p.plus(2))];
            int descOff = Loader.gView.cp[u2(p.plus(4))];
            long code = Loader.findCode(Loader.gView.base.toAddress(), p.plus(8), attrs);
            if (code != 0L && (access & 0x0008) != 0    // static methods only (Objects is all-static)
                    && !Utf8.utf8IsAtBase(Loader.gView.base.toAddress(), nameOff, Magic.bytes("<clinit>")) && lzN < MAXLAZY && DynamicLinker.dlN < MAXLAZY)
            {
                int idx = lzN;
                lzTab[idx] = new LazyMethod();
                lzTab[idx].blob = Loader.gView.base;
                lzTab[idx].len = blobLen;
                lzTab[idx].reg = reg;
                lzTab[idx].nameOff = nameOff;
                lzTab[idx].descOff = descOff;
                lzTab[idx].code = code;
                lzTab[idx].codeLen = ClassBytes.codeLength(code);
                lzTab[idx].isStatic = 1;
                lzTab[idx].isSync = (access & 0x0020) != 0 ? 1 : 0;   // ACC_SYNCHRONIZED (JVMS 2.11.10)
                if (lzN >= lzNHigh)
                {
                    lzNHigh = lzN + 1;                      // high-water across reclaims
                }
                lzTab[idx].cache = CodeAddress.zero();
                DataReference cell = Heap.allocCodeTable(8);
                lzTab[idx].slot = cell;                     // first call data-patches the cell -> later calls dispatch direct
                cell.store(buildLazyCompileStub(idx));
                lzN += 1;
                DynLink d = new DynLink();
                if (Loader.gView == null)                            // PROBE: born zero, or zeroed later? This is the
                {                                            //   write site, so the class is still in hand.
                    Uart.write(Magic.bytes("\nDL BORN ZERO at dlN="));
                    VM.printDec(DynamicLinker.dlN);
                    Uart.write(Magic.bytes(" while compiling "));
                    Loader.printCurrentClass();
                    Uart.putc(0x0A);
                    while (true) { Magic.wfe(); }
                }
                d.blob = Loader.gView.base;
                d.gen = Loader.loaderGen;
                d.classOff = Loader.gView.thisNameOff;
                d.nameOff = nameOff;
                d.descOff = descOff;
                d.cell = cell;
                DynamicLinker.dlTab[DynamicLinker.dlN] = d;
                DynamicLinker.dlN += 1;
                armed += 1;
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        Loader.verifyDlTab();                                      // ... and check none went zero since insertion
        if (Loader.LOAD_TRACE)
        {
            Uart.write(Magic.bytes("  phaseA: "));
            VM.printDec(armed);
            Uart.write(Magic.bytes(" cells at structure time for "));
            Loader.printNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
            Uart.putc(0x0A);
        }
    }

    /**
     * M8 stage 5: LAZY, FULL STOP — and it is FULL now, with no exception at all. EVERY class is
     * metadata-only: phase-A cells for its statics, deferral stubs for its virtuals, its {@code <init>}
     * included. Nothing is compiled at load time any more. This is the OpenJDK shape the `<init>` increment
     * settled on, applied to the last class that was still outside it: ONE entry per method, always holding
     * a valid callable address, whose CONTENTS change when the body compiles.
     *
     * <p>The `java/`/`jdk/`/`sun/` prefix went first — guest code was never the right eager exception; the
     * prefix was scaffolding from when laziness was gated to a handful of named java.base utilities.
     *
     * <p>RETIRED PREDICATE — `eagerKept`. It held back {@code java/lang/Object} alone, on this stated
     * objection: "its 9 virtuals are the prefix of EVERY vtable in both worlds, so its slots are what
     * writer-baked code and loader-compiled code agree on. Deferring them would put stubs in that shared
     * prefix, and a stub there is entered before the loader can be sure which world the receiver came
     * from." That was a guess about a mechanism, not a measurement of one, and reading the mechanism
     * refutes each half:
     * <ul>
     *   <li>A DEFERRAL STUB IS RECEIVER-INDEPENDENT. It is five instructions — `movz x17,idx`, the
     *       trampoline address into x16, `br x16` — and never touches x0. It cannot ask which world the
     *       receiver came from because it never looks at the receiver. Object's body is the same body
     *       whoever is calling.</li>
     *   <li>THE SHARED PREFIX IS A STABLE WORD, which is the whole point of the shape. A deferral entry
     *       carries {@code slot = 0} ("no single slot to patch"), so {@link #lazyCompileLocked} never
     *       re-points a TIB slot; {@link #rememberLazyBody} updates the method REGISTRY and nothing else.
     *       The vtable word is the stub buffer's address for the life of the VM, so every copy of Object's
     *       prefix — {@code inheritVtable}, {@code fillObjectVtableUpTo}, {@code refillArrayTibVtables} —
     *       copies a word that never changes.</li>
     *   <li>AND THE HOTTEST PATH DOES NOT EXIST. {@code Object.<init>} looks like the risk, since every
     *       constructor begins with it — but {@link Loader#isRealSpecial} lowers it to a POP in both worlds, so
     *       it is never called at all and deferring it costs and risks nothing.</li>
     * </ul>
     * The synth-latch soundness note elsewhere in this file argues from "Object is the one eagerly-compiled
     * class, so its slots are never re-pointed". The conclusion survives; the REASON is now the stub
     * buffer's address stability rather than eagerness, and `sy:chg` on the batch line is the counter that
     * says so empirically.
     */
    static boolean stage2Gated(Address base, int off)
    {
        return true;
    }

    /** M8 Stage 2: true if the current class's method (name, desc) already has a structure-time phase-A cell,
     *  so compileClass can skip its eager compile (it will compile on first call through the cell). */
    static boolean phaseACelled(int nameOff, int descOff)
    {
        if (DynamicLinker.dlTab == null)
        {
            return false;
        }
        // THROUGH THE dlTab INDEX (dlBucket, keyed utf8Hash2(class name, method name)), with the scan's own
        // identity test. compileClass asks this once per method and it walked EVERY phase-A cell to answer --
        // methods x cells per batch. MEASURED (qprof, SslEngineProbe, QEMU 2026-11-07): the largest single leaf
        // of batch loading, 17% of its samples. The class being compiled is the context, so gView.thisNameOff names
        // the class its own cells are filed under; the answer is only whether one exists, so chain order is moot.
        DynamicLinker.buildDlIndex();
        boolean hit = false;
        int k = DynamicLinker.dlBucket[Utf8.utf8Hash2(Loader.gView.base.toAddress(), Loader.gView.thisNameOff, Loader.gView.base.toAddress(), nameOff) & (DynamicLinker.DLTAB - 1)];
        while (k >= 0 && !hit)
        {
            DynLink d = DynamicLinker.dlTab[k];
            if (d.blob.EQ(Loader.gView.base) && d.nameOff == nameOff && d.descOff == descOff)
            {
                hit = true;
            }
            k = DynamicLinker.dlNext[k];
        }
        return hit;
    }

    static void lazyEnsureTables()
    {
        if (lzTab == null)
        {
            lzTab = new LazyMethod[MAXLAZY];
            lzN = 0;
        }
        if (lazyTrampAddr == 0L)
        {
            buildLazyTramp();
        }
    }
}
