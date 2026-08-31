import java.lang.reflect.Method;
import java.util.StringTokenizer;

/**
 * Regression for the three adaptation faults a stock {@code @run junit} test uncovered, each of which
 * produced a NullPointerException nowhere near its cause.
 *
 * <ol>
 *   <li><b>Boxing.</b> A method REFERENCE whose referent returns a primitive, handed to a generic functional
 *       interface (erased return {@code Object}), must box. javac adapts a lambda BODY itself, so only a
 *       method reference reaches the metafactory unadapted; the raw primitive travelled in x0 where the
 *       caller expected a reference, and the first virtual call on it threw NPE.
 *   <li><b>The interface.</b> A lambda's functional interface is named ONLY as the return type inside the
 *       indy's descriptor, so nothing in the producing class pulls it. It normally arrives with the lambda's
 *       CONSUMER; a lambda whose consumer is not in the batch resolved to Type 0 -- also the itable
 *       directory's END SENTINEL -- so the lambda satisfied no interface at all.
 *   <li><b>The vtable slot.</b> A bound method reference on a class pulled LATE has no vtable numbering yet;
 *       slot -1 indexed TIB[0], the Type pointer, and the thunk branched into the heap.
 * </ol>
 *
 * <p>Each arm is run twice: directly, and through {@code Method.invoke}. The reflective one is not a
 * formality -- it is the only one that reproduced (2) and (3), because a reflectively reached method is
 * compiled on demand, after the batch that would have pulled what it needs.
 */
public class LambdaAdaptProbe
{
    /** A generic SAM: its erased return is Object, so every primitive referent below must be boxed. */
    interface Get<T>
    {
        T get();
    }

    static class Prims
    {
        boolean z() { return true; }
        byte b() { return (byte) -7; }
        char c() { return 'q'; }
        short s() { return (short) -300; }
        int i() { return 1234; }
        long j() { return 9876543210L; }
    }

    public static void main(String[] args) throws Exception
    {
        run();
        Method m = LambdaAdaptProbe.class.getDeclaredMethod("run");
        m.setAccessible(true);
        System.out.println("-- again, through Method.invoke");
        m.invoke(null, new Object[0]);
    }

    static void run()
    {
        Prims p = new Prims();
        check("boolean", Boolean.valueOf(true), ((Get<Boolean>) p::z).get());
        check("byte", Byte.valueOf((byte) -7), ((Get<Byte>) p::b).get());
        check("char", Character.valueOf('q'), ((Get<Character>) p::c).get());
        check("short", Short.valueOf((short) -300), ((Get<Short>) p::s).get());
        check("int", Integer.valueOf(1234), ((Get<Integer>) p::i).get());
        check("long", Long.valueOf(9876543210L), ((Get<Long>) p::j).get());

        // A bound method reference on a class the batch did not pull for this method: the interface has to be
        // demand-loaded and the referent has no vtable slot yet.
        StringTokenizer st = new StringTokenizer("a b");
        check("late-class ref", Boolean.valueOf(true), ((Get<Boolean>) st::hasMoreTokens).get());
    }

    private static void check(String what, Object want, Object got)
    {
        boolean ok = want.equals(got);
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + " -> " + got + (ok ? "" : " (want " + want + ")"));
    }
}
