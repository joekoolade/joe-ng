package compiler;

import asm.CodeBuffer;

/**
 * The seam between the baseline compiler's code generation and symbol resolution
 * (PLAN.md §M5.4.2).
 *
 * <p>Every symbolic reference the compiler emits — a call, or an address load for a
 * TIB, a Type, a static field or an interned string — flows through here. The
 * compiler names the target by <em>constant-pool index</em> (or, for its own
 * synthesised runtime calls, by a helper id), never by a resolved string or
 * address, because the two contexts resolve it differently and at different times:
 *
 * <ul>
 *   <li>the <b>writer</b> can't know an address until layout, so its implementation
 *       emits a placeholder and records the site for {@code ImageBuilder} to
 *       relocate — keeping the {@code String} keys and record lists on its side;</li>
 *   <li>the <b>metal</b> JIT has already loaded its dependencies, so its
 *       implementation emits the resolved address immediately, straight from the
 *       loader's registries.</li>
 * </ul>
 *
 * With resolution behind this interface, the code generation above it is identical
 * in both worlds — which is what lets one compiler serve both (M5.4.4). Each method
 * emits into {@code cb}; the compiler has already set up the calling convention
 * around the call (argument moves, operand spill) and continues after it.
 */
public interface Symbols
{
    // Synthesised runtime helpers the compiler calls that are not in any classfile.
    int HEAP_ALLOC = 0;         // vm/Heap.alloc(I)J
    int HEAP_ALLOC_ARRAY = 1;   // vm/Heap.allocArray(II)J
    int GC_COLLECT = 2;         // vm/VM.gcCollect(J)V
    int INSTANCE_OF = 3;        // vm/VM.instanceOf(JJ)I
    int CHECK_CAST = 4;         // vm/VM.checkCast(JJ)J
    int UNWIND = 5;             // vm/VM.unwind(JJJ)V

    /** Emit a {@code BL} to the method at Methodref/InterfaceMethodref index {@code methodCp}. */
    void call(CodeBuffer cb, int methodCp);

    /** Emit a {@code BL} to a synthesised runtime helper (one of the ids above). */
    void callHelper(CodeBuffer cb, int helper);

    /** Load into {@code reg} the TIB address of the class at {@code classCp} (for {@code new}). */
    void tib(CodeBuffer cb, int reg, int classCp);

    /** Load into {@code reg} the Type address of the class at {@code classCp}. */
    void type(CodeBuffer cb, int reg, int classCp);

    /** Load into {@code reg} the Type address of the interface owning InterfaceMethodref {@code ifaceMethodCp}. */
    void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp);

    /** Load into {@code reg} the address of the static field at Fieldref index {@code fieldCp}. */
    void staticField(CodeBuffer cb, int reg, int fieldCp);

    /** Load into {@code reg} the address of the interned string at String index {@code stringCp}. */
    void string(CodeBuffer cb, int reg, int stringCp);

    /** Load into {@code reg} the address of the synthetic in-flight-exception static slot. */
    void exceptionSlot(CodeBuffer cb, int reg);
}
