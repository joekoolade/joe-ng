package vm;

import asm.A64Enc;
import asm.CodeBuffer;
import compiler.Intrinsics;
import compiler.Symbols;
import objectmodel.ObjectModel;

/**
 * The on-metal implementation of the compiler's {@link Symbols} seam (PLAN.md
 * §M5.4.c): the other half of {@code compiler/WriterSymbols}. Where the writer
 * emits a placeholder and records a String-keyed relocation for later layout,
 * the metal JIT has already loaded its dependencies, so every reference resolves
 * to a concrete address <em>now</em> — straight from {@link Loader}'s registries
 * and the writer-stashed helper addresses in {@link VM}. The shared
 * {@code Baseline} core drives this exactly as it drives the writer's version.
 *
 * <p>This runs while {@code Loader} is compiling a class, so its resolvers read
 * {@code Loader}'s current-class state (gbase/gcp) and the loaded-class tables.
 */
final class MetalSymbols implements Symbols
{
    // ----- calls: emit a BL/BLR-free BL straight to the resolved code address -----
    public void call(CodeBuffer cb, int methodCp)
    {
        long target = Loader.resolveCallBuf(methodCp);
        if (target == 0L)                               // callee's class not loaded yet (dependency cycle):
        {                                               // record the bl for patchRelocs to fix after loadAll
            Loader.recordCallReloc(cb.base() + (long) cb.wordCount() * 4L, methodCp);
        }
        emitBl(cb, target);
    }
    public void callHelper(CodeBuffer cb, int helper)
    {
        emitBl(cb, helperAddr(helper));
    }

    // ----- address loads: the target is known, so load it directly into reg -----
    // Always a fixed 2-word MOVZ+MOVK (addresses are <4 GiB on the Pi 4, as the
    // loader already assumes). Fixed width keeps a compiled method's size
    // placement-independent, so the metal can size/place/emit in phases.
    public void tib(CodeBuffer cb, int reg, int classCp)
    {
        emitAddr(cb, reg, Loader.tibOfClass(classCp));
    }
    public void type(CodeBuffer cb, int reg, int classCp)
    {
        emitAddr(cb, reg, Loader.typeOfClass(classCp));
    }
    public void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp)
    {
        emitAddr(cb, reg, Loader.ifaceTypeOfMethod(ifaceMethodCp));
    }
    public void classLiteral(CodeBuffer cb, int reg, int classCp)
    {
        emitAddr(cb, reg, Loader.classLiteral(classCp));
    }
    public boolean isGetClass(int methodCp)
    {
        return Loader.isGetClass(methodCp);
    }
    public boolean isArrayClone(int methodCp)
    {
        return Loader.isArrayClone(methodCp);
    }
    public boolean isDesiredAssertionStatus(int methodCp)
    {
        return Loader.isDesiredAssertionStatus(methodCp);
    }
    public int monitorOp(int methodCp)
    {
        return Loader.monitorOp(methodCp);
    }
    public void tagArray(CodeBuffer cb, int arrReg, int operand, boolean isRef)
    {
        long tib = isRef ? Loader.refArrayTibForClass(operand) : Loader.primArrayTib(operand);
        if (tib == 0L)
        {
            return;                                     // no array Type (Object not loaded): leave it raw
        }
        emitAddr(cb, 16, tib);                          // x16 = array TIB (scratch)
        cb.emit(A64Enc.strx(16, arrReg, ObjectModel.TIB_OFFSET));   // arr.@0 = array TIB (was the raw element size)
    }
    public void staticField(CodeBuffer cb, int reg, int fieldCp)
    {
        long addr = Loader.staticAddr(fieldCp);
        if (addr == 0L)                                 // cross-class static in a not-yet-loaded class:
        {                                               // record the movz/movk for patchRelocs
            Loader.recordStaticReloc(cb.base() + (long) cb.wordCount() * 4L, reg, fieldCp);
        }
        emitAddr(cb, reg, addr);
    }
    public void string(CodeBuffer cb, int reg, int stringCp)
    {
        // Intern the literal now and bake in its address: a mini java/lang/String OBJECT if String is
        // loaded (so String methods work on literals), else a raw byte[] (unchanged for String-free
        // guests). The size pass compiles at base 0 too, so a spare copy leaks per string — harmless
        // under the bump allocator.
        emitAddr(cb, reg, Loader.internStringObj(stringCp));
    }
    public void exceptionSlot(CodeBuffer cb, int reg)
    {
        emitAddr(cb, reg, Loader.exceptionSlotAddr());   // the metal in-flight-exception word
    }
    // The JIT compiles real java.base-shaped code, which relies on the VM throwing NPE/AIOOBE on a bad
    // deref/index — and the mini exception hierarchy is loaded here, so we can allocate the object.
    public boolean implicitChecks()
    {
        return true;
    }
    public void codePc(CodeBuffer cb, int reg, int targetWord)
    {
        emitAddr(cb, reg, cb.pcAt(targetWord));          // JIT compiles at the final base -> resolve now
    }

    /** Fixed 2-word load of a &lt;4 GiB address into {@code reg} (MOVZ low16 + MOVK bits16..31). */
    private static void emitAddr(CodeBuffer cb, int reg, long addr)
    {
        cb.emit(A64Enc.movz(reg, (int) addr, 0));
        cb.emit(A64Enc.movk(reg, (int) (addr >> 16), 1));
    }

    // ----- symbol queries: resolve to a number from the loaded-class tables -----
    public int fieldOffset(int fieldCp)
    {
        return Loader.fieldOffsetOf(fieldCp);
    }
    public int objectSize(int classCp)
    {
        return Loader.objectSizeOf(classCp);
    }
    public int vtableSlot(int methodCp)
    {
        return Loader.vtableSlotOf(methodCp);
    }
    public int interfaceSlot(int ifaceMethodCp)
    {
        return Loader.ifSlotOf(ifaceMethodCp);
    }
    public boolean isIntrinsicCall(int methodCp)
    {
        return Loader.isMagicOwner(methodCp);
    }
    public boolean intrinsicEmitsCall(int methodCp)
    {
        // The memory/bytes intrinsics emit no BL/BLR; the scheduler ops (spawn/sem*/sleep/newSem/report)
        // lower to a BL to a VM helper, so a caller using them must be treated as non-leaf.
        int id = Loader.magicId(methodCp);
        return id == Intrinsics.SPAWN || id == Intrinsics.SEM_WAIT || id == Intrinsics.SEM_POST
            || id == Intrinsics.SLEEP_MS || id == Intrinsics.NEW_SEM || id == Intrinsics.REPORT
            || id == Intrinsics.PRINT_STR
            || id == Intrinsics.MON_WAIT || id == Intrinsics.MON_NOTIFY
            || id == Intrinsics.MON_NOTALL || id == Intrinsics.THREAD_JOIN
            || id == Intrinsics.STACK_TRACE || id == Intrinsics.ALL_THREADS;
    }
    public int intrinsicId(int methodCp)
    {
        int id = Loader.magicId(methodCp);
        if (id < 0)
        {
            fail(Symbols.FAIL_INTRINSIC_ID, methodCp, 0);   // an unrecognised magic op: halt
        }
        return id;
    }
    public boolean isSkippableInit(int methodCp)
    {
        // A real same-class / loaded ctor is a call; anything else (Object.<init>,
        // an unloaded root) is the super() we skip.
        return !Loader.isRealSpecial(methodCp);
    }

    // ----- invokedynamic (string concat) -----
    public boolean isConcatIndy(int idx)
    {
        return Loader.isStringConcat(idx);
    }
    public int concatRecipeOff(int idx)
    {
        return Loader.concatRecipeOff(idx);
    }

    // ----- invokedynamic (lambda) -----
    public boolean isLambdaIndy(int idx)
    {
        return Loader.isLambdaIndy(idx);
    }
    public int lambdaSize(int idx)
    {
        return Loader.lambdaSize(idx);
    }
    public int lambdaSamArgc(int idx)
    {
        return Loader.lambdaSamArgc(idx);
    }
    public void lambdaTib(CodeBuffer cb, int reg, int idx)
    {
        emitAddr(cb, reg, Loader.buildLambdaTib(idx));      // synthesise the lambda class now; bake its TIB
    }

    /** Wrap the byte[] in x0 as a java/lang/String: alloc, set TIB, store into the sole `value` field. */
    public void newStringFromBytes(CodeBuffer cb)
    {
        cb.emit(A64Enc.subImm(31, 31, 16));                 // sub sp, #16 (preserve byte[] across the alloc)
        cb.emit(A64Enc.strx(0, 31, 0));                     // str x0, [sp]
        cb.emitAll(A64Enc.loadImm64(0, Loader.stringSize())); // x0 = String instance size
        emitBl(cb, VM.heapAlloc);                            // x0 = new object (header/size set, payload zeroed)
        cb.emit(A64Enc.ldrx(1, 31, 0));                     // x1 = byte[]
        cb.emit(A64Enc.addImm(31, 31, 16));                 // add sp, #16
        emitAddr(cb, 2, Loader.stringTib());                // x2 = String TIB
        cb.emit(A64Enc.strx(2, 0, 0));                      // obj.tib   = String TIB (TIB_OFFSET = 0)
        cb.emit(A64Enc.strx(1, 0, 16));                     // obj.value = byte[]      (field 0 -> offset 16)
    }

    /** A compile failure on metal is unrecoverable and message-free: halt. */
    public void fail(int reason, int a, int b)
    {
        VM.jitFail(reason, a, b);           // name the gap over the UART (unsupported bytecode/intrinsic/...)
        for (;;)
        {
            // then halt — a JIT bug or an unsupported bytecode; nothing to recover to
        }
    }

    /** BL from the current position in {@code cb} to an absolute {@code target}. */
    private static void emitBl(CodeBuffer cb, long target)
    {
        long here = cb.base() + (long) cb.wordCount() * 4;
        long d = target - here;                         // A64 bl reaches +-128 MiB (26-bit word offset)
        if (d > 0x07FFFFFFL || d < -0x08000000L)
        {
            VM.jitFail(Symbols.FAIL_BL_RANGE, (int) (here >> 12), (int) (target >> 12));
            for (;;) { }
        }
        int words = (int) (d >> 2);
        cb.emit(A64Enc.bl(words));
    }

    /** Writer-stashed address of the runtime helper with the given {@link Symbols} id. */
    private static long helperAddr(int helper)
    {
        if (helper == Symbols.HEAP_ALLOC)
        {
            return VM.heapAlloc;
        }
        if (helper == Symbols.HEAP_ALLOC_ARRAY)
        {
            return VM.allocArray;
        }
        if (helper == Symbols.GC_COLLECT)
        {
            return VM.gcCollect;
        }
        if (helper == Symbols.INSTANCE_OF)
        {
            return VM.instanceOfAddr;
        }
        if (helper == Symbols.CHECK_CAST)
        {
            return VM.checkCastAddr;
        }
        if (helper == Symbols.SPAWN)
        {
            return VM.startThreadAddr;
        }
        if (helper == Symbols.MON_WAIT)
        {
            return VM.objWaitAddr;
        }
        if (helper == Symbols.MON_NOTIFY)
        {
            return VM.objNotifyAddr;
        }
        if (helper == Symbols.MON_NOTALL)
        {
            return VM.objNotifyAllAddr;
        }
        if (helper == Symbols.THREAD_JOIN)
        {
            return VM.threadJoinAddr;
        }
        if (helper == Symbols.STACK_TRACE)
        {
            return VM.threadStackTraceAddr;
        }
        if (helper == Symbols.ALL_THREADS)
        {
            return VM.allThreadsAddr;
        }
        if (helper == Symbols.SEM_WAIT)
        {
            return VM.semWaitAddr;
        }
        if (helper == Symbols.SEM_POST)
        {
            return VM.semPostAddr;
        }
        if (helper == Symbols.SLEEP_MS)
        {
            return VM.sleepAddr;
        }
        if (helper == Symbols.NEW_SEM)
        {
            return VM.newSemAddr;
        }
        if (helper == Symbols.REPORT)
        {
            return VM.philReportAddr;
        }
        if (helper == Symbols.SC_START)
        {
            return VM.scStartAddr;
        }
        if (helper == Symbols.SC_CHAR)
        {
            return VM.scCharAddr;
        }
        if (helper == Symbols.SC_INT)
        {
            return VM.scIntAddr;
        }
        if (helper == Symbols.SC_END)
        {
            return VM.scEndAddr;
        }
        if (helper == Symbols.PRINT_STR)
        {
            return VM.printStrAddr;
        }
        if (helper == Symbols.SC_STR)
        {
            return VM.scStrAddr;
        }
        if (helper == Symbols.SC_LONG)
        {
            return VM.scLongAddr;
        }
        if (helper == Symbols.NEW_NPE)
        {
            return VM.newNpeAddr;
        }
        if (helper == Symbols.GET_CLASS)
        {
            return VM.getClassAddr;
        }
        if (helper == Symbols.ARRAY_CLONE)
        {
            return VM.arrayCloneAddr;
        }
        if (helper == Symbols.NEW_AIOOBE)
        {
            return VM.newAioobeAddr;
        }
        if (helper == Symbols.CAPTURE_TRACE)
        {
            return VM.captureTraceAddr;
        }
        if (helper == Symbols.NEW_ARITH)
        {
            return VM.newArithAddr;
        }
        return VM.unwindAddr;                       // UNWIND
    }

    /** The on-metal JIT records throw-site backtraces so printStackTrace() has frames for any throw. */
    @Override
    public boolean captureTraces()
    {
        return true;
    }
}
