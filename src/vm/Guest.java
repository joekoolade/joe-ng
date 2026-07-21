package vm;

/**
 * A class joe-ng has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} exercises <em>interface dispatch through an itable</em>. Both
 * calls below are the same {@code invokeinterface Greeter.greet()} site, but the
 * receivers are different classes that put {@code greet()} at different vtable
 * slots ({@link Alpha} slot 0, {@link Beta} slot 1). A single compile-time vtable
 * slot therefore cannot serve both — resolving by slot would call Beta's
 * {@code filler()} (7) instead of its {@code greet()} (22). Each class's imap maps
 * the interface method's global index to its own implementation, so both dispatch
 * correctly and the total is {@code 20 + 22 = 42 = '*'}.
 */
public class Guest
{
    public static int answer()
    {
        Greeter a = new Alpha();     // greet() at Alpha's vtable slot 0
        Greeter b = new Beta();      // greet() at Beta's  vtable slot 1
        // 20 + 22 = 42, plus JIT'd instanceof checks that must net zero. Class checks
        // (super-chain): a Beta is a Beta, an Alpha is not. Interface checks (itable
        // directory): both Alpha and Beta are Greeters. All exercise the shared core's
        // instanceof lowering (a VM.instanceOf call) on on-metal-JIT'd objects (M5.4.e).
        int corrections = (b instanceof Beta ? 0 : 100) + (a instanceof Beta ? 100 : 0)
                        + (b instanceof Greeter ? 0 : 100) + (a instanceof Greeter ? 0 : 100);
        return a.greet() + b.greet() + corrections + bias();   // one call site, two layouts -> 42
    }

    /**
     * A same-class static callee, so {@code answer()} contains an {@code invokestatic}.
     * The on-metal JIT discovers it, places it in its own buffer, and resolves the
     * {@code BL} to it — verifying the shared core's call lowering and the loader's
     * two-pass (size-then-emit) buffer placement on metal (M5.4.e).
     */
    private static int bias()
    {
        return 0;
    }
}
