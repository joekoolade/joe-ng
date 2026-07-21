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
    public void staticField(CodeBuffer cb, int reg, int fieldCp)
    {
        emitAddr(cb, reg, Loader.staticAddr(fieldCp));
    }
    public void string(CodeBuffer cb, int reg, int stringCp)
    {
        // Intern the literal as a heap byte[] now and bake in its address. (The size
        // pass compiles at base 0 too, so a spare byte[] leaks per string — harmless
        // under the bump allocator; interning by content would dedup it.)
        emitAddr(cb, reg, Loader.internString(stringCp));
    }
    public void exceptionSlot(CodeBuffer cb, int reg)
    {
        // TODO(4.4e): a metal in-flight-exception slot (the writer's vm/VM.$exception).
        emitAddr(cb, reg, 0L);
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
        return false;   // the memory/bytes intrinsics this JIT recognises emit no BL/BLR
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
