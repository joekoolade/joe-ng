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
        // 20 + 22 = 42, plus two JIT'd instanceof checks that must net zero: a Beta is
        // a Beta (super-chain hit), an Alpha is not. This exercises the shared core's
        // instanceof lowering (a VM.instanceOf call) on on-metal-JIT'd objects (M5.4.e).
        int corrections = (b instanceof Beta ? 0 : 100) + (a instanceof Beta ? 100 : 0);
        return a.greet() + b.greet() + corrections;   // one call site, two layouts -> 42
    }
}
