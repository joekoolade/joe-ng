package vm;

import asm.A64Enc;
import asm.CodeBuffer;
import compiler.Symbols;

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
        emitBl(cb, Loader.resolveCallBuf(methodCp));
    }
    public void callHelper(CodeBuffer cb, int helper)
    {
        emitBl(cb, helperAddr(helper));
    }

    // ----- address loads: the target is known, so load it directly into reg -----
    public void tib(CodeBuffer cb, int reg, int classCp)
    {
        cb.emitAll(A64Enc.loadImm64(reg, Loader.tibOfClass(classCp)));
    }
    public void type(CodeBuffer cb, int reg, int classCp)
    {
        cb.emitAll(A64Enc.loadImm64(reg, Loader.typeOfClass(classCp)));
    }
    public void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp)
    {
        cb.emitAll(A64Enc.loadImm64(reg, Loader.ifaceTypeOfMethod(ifaceMethodCp)));
    }
    public void staticField(CodeBuffer cb, int reg, int fieldCp)
    {
        cb.emitAll(A64Enc.loadImm64(reg, Loader.staticAddr(fieldCp)));
    }
    public void string(CodeBuffer cb, int reg, int stringCp)
    {
        // TODO(4.4e): interned string literals for on-metal-loaded classes. Guest
        // classes the metal JITs today carry none, so this path is never reached.
        cb.emitAll(A64Enc.loadImm64(reg, 0L));
    }
    public void exceptionSlot(CodeBuffer cb, int reg)
    {
        // TODO(4.4e): a metal in-flight-exception slot (the writer's vm/VM.$exception).
        cb.emitAll(A64Enc.loadImm64(reg, 0L));
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
        // TODO(4.4e): recognise magic/Magic by Utf8-offset compare. The classes the
        // metal loads today make no magic calls, so no invokestatic is an intrinsic.
        return false;
    }
    public boolean intrinsicEmitsCall(int methodCp)
    {
        return false;
    }
    public int intrinsicId(int methodCp)
    {
        return 0;                                   // unreached while isIntrinsicCall is false
    }
    public boolean isSkippableInit(int methodCp)
    {
        // A real same-class / loaded ctor is a call; anything else (Object.<init>,
        // an unloaded root) is the super() we skip.
        return !Loader.isRealSpecial(methodCp);
    }

    /** A compile failure on metal is unrecoverable and message-free: halt. */
    public void fail(int reason, int a, int b)
    {
        for (;;)
        {
            // spin — a JIT bug or an unsupported bytecode; nothing to recover to
        }
    }

    /** BL from the current position in {@code cb} to an absolute {@code target}. */
    private static void emitBl(CodeBuffer cb, long target)
    {
        long here = cb.base() + (long) cb.wordCount() * 4;
        int words = (int) ((target - here) >> 2);       // A64Enc branches take word offsets
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
        return VM.unwindAddr;                       // UNWIND
    }
}
