/**
 * A lambda with ONE capture and FOUR SAM arguments -- JUnit's invocation shape, reduced.
 *
 * <p>The launcher's tests now START and both fail with a NullPointerException whose innermost frame is
 * {@code InterceptingExecutableInvoker$ReflectiveInterceptorCall.lambda$ofVoidMethod$0}. javap gives that
 * method's whole body: load the captured {@code call}, load the four SAM arguments, and
 * {@code invokeinterface VoidMethodInterceptorCall.apply(...)}. An NPE at that frame means the CAPTURE
 * arrived null -- the receiver of the interface call is the captured value, not an argument.
 *
 * <p>THE ARITY IS THE WHOLE POINT, and it is why the existing capture probes do not cover this: they pin
 * MANY captures with FEW SAM arguments (the four-capture lambda that exposed the kind-5 bug). This is the
 * mirror image -- ONE capture and FOUR SAM arguments -- so the capture sits at one end of the register
 * window and the arguments fill the rest. A thunk that shifts arguments by the wrong amount, or loads the
 * capture into a register an argument has already claimed, fails here and passes every existing arm.
 *
 * <p>Each arm reports the CAPTURE and the ARGUMENTS separately, because "the capture was null" and "the
 * arguments were shuffled" are different bugs that both surface as a failure inside the callee.
 */
public class Lambda4Probe
{
    interface Call4
    {
        void apply(String a, String b, String c, String d);
    }

    interface Sam4
    {
        Object invoke(String a, String b, String c, String d);
    }

    static String seen = "<never called>";

    /** JUnit's ofVoidMethod exactly: capture `call`, take four SAM args, invokeinterface on the capture. */
    static Sam4 ofVoid(Call4 call)
    {
        return (a, b, c, d) ->
        {
            call.apply(a, b, c, d);
            return null;
        };
    }

    /** Two captures and four SAM args: catches a thunk that happens to get the single-capture case right. */
    static Sam4 ofVoid2(Call4 call, String tag)
    {
        return (a, b, c, d) ->
        {
            call.apply(tag, b, c, d);
            return null;
        };
    }

    interface Sam2
    {
        Object invoke(String a, String b);
    }

    /** lambda$invoke$0's shape: 3 captures, 2 SAM args, and the call site PERMUTES them. */
    static Sam2 chain(Call4 call, String c1, String c2)
    {
        return (a, b) ->
        {
            call.apply(a, b, c1, c2);
            return null;
        };
    }

    /** ofVoidMethod's shape: capture the kind-9 reference, invoke it from inside the capturing lambda. */
    static Sam2 wrap(Unbound2 ref)
    {
        return (a, b) -> ref.apply(new Hi(), a);
    }

    /** A capture that is itself reached by interface dispatch, as JUnit's `call` is. */
    static Call4 viaRef()
    {
        return Lambda4Probe::record;
    }

    static void record(String a, String b, String c, String d)
    {
        seen = a + "," + b + "," + c + "," + d;
    }

    /**
     * THE LAUNCHER'S ACTUAL CONDITION: a lambda whose factory -- and therefore whose synthetic body -- is a
     * STATIC METHOD ON AN INTERFACE.
     *
     * <p>javap on the jar shows ReflectiveInterceptorCall is a nested INTERFACE, so its bootstrap
     * MethodHandle is `REF_invokeStatic` over a **CONSTANT_InterfaceMethodref (tag 11)**, where a static
     * method on a CLASS gives CONSTANT_Methodref (tag 10). Every arm above uses the tag-10 form and passes,
     * which is why they could not reproduce: the SHAPE was right and the CONDITION was not.
     *
     * <p>This VM has been bitten by static-interface-methods before -- registerInterface walks only virtual
     * methods, so a static interface method is registered nowhere, which is what forced compileSigOnDemand's
     * fourth tier. A lambda BODY that is one is the same gap reached from a new direction.
     */
    interface Factory
    {
        static Sam4 ofVoidOnIface(Call4 call)
        {
            return (a, b, c, d) ->
            {
                call.apply(a, b, c, d);
                return null;
            };
        }

        static Sam2 chainOnIface(Call4 call, String c1, String c2)
        {
            return (a, b) ->
            {
                call.apply(a, b, c1, c2);
                return null;
            };
        }
    }

    /**
     * THE LAUNCHER'S ACTUAL FAILING VALUE: a kind-9 UNBOUND INTERFACE method reference, nc=0, stored by a
     * {@code <clinit>} into a static field.
     *
     * <p>TestMethodTestDescriptor's static initializer is two stores. The first
     * ({@code new InterceptingExecutableInvoker(); putstatic executableInvoker}) demonstrably WORKS -- that
     * object's invoke/chainAndInvoke frames appear in the failing trace. The second
     * ({@code invokedynamic; putstatic defaultInterceptorCall}) reads back NULL. So the initializer ran and
     * one store took effect while the next did not, and the only difference is that the failing one stores an
     * INDY RESULT.
     *
     * <p>{@code InvocationInterceptor::interceptTestMethod} is an unbound reference to an INSTANCE method of
     * an INTERFACE: implementation kind 9, zero captures, and the receiver arrives as SAM argument 0. Every
     * other arm here is kind 6 (a static method reference or a lambda body), which is why none of them
     * reproduced. The log confirms the kind: `LAMBDA idx=384 nc=0 samArgc=4 size=16 kind=9`.
     */
    interface Greeter
    {
        String greet(String x);
    }

    interface Unbound2
    {
        String apply(Greeter g, String x);
    }

    interface Unbound4
    {
        String apply(Greeter g, String a, String b, String c);
    }

    static class Holder
    {
        /** Set by <clinit> from an indy, exactly as defaultInterceptorCall is. */
        static Unbound2 REF;
        static Unbound4 REF4;
        static String MARKER;

        static
        {
            MARKER = "set";                          // a NON-indy store, the control: this one must survive
            REF = Greeter::greet;                    // kind 9, nc=0, samArgc=2
            REF4 = (g, a, b, c) -> g.greet(a + b + c);
        }
    }

    static class Hi implements Greeter
    {
        public String greet(String x)
        {
            return "hi:" + x;
        }
    }

    public static void main(String[] args)
    {
        Call4 sink = (a, b, c, d) -> seen = a + "," + b + "," + c + "," + d;

        // ONE capture, FOUR SAM args -- the launcher's shape.
        Sam4 f = ofVoid(sink);
        Object r = f.invoke("p", "q", "r", "s");
        System.out.println("1cap4arg seen = " + seen + " (want p,q,r,s)");
        System.out.println("1cap4arg ret null = " + (r == null) + " (want true)");

        // TWO captures, four SAM args: the capture is used as an ARGUMENT here, so a dropped second capture
        // shows up in the text rather than as a null receiver.
        seen = "<never called>";
        Sam4 g = ofVoid2(sink, "TAG");
        g.invoke("p", "q", "r", "s");
        System.out.println("2cap4arg seen = " + seen + " (want TAG,q,r,s)");

        // The capture reached as a RECEIVER at all: a null capture would have thrown before `seen` moved.
        System.out.println("capture non-null = " + (sink != null) + " (want true)");

        // THREE captures, TWO SAM args, ARGUMENTS PERMUTED -- lambda$invoke$0's exact shape. javap shows it
        // loads (capture0, samArg0, samArg1, capture1, capture2) in that order, so the thunk must both shift
        // the SAM args up and interleave the captures around them. A thunk that shifts by the wrong amount
        // still passes the 1-capture case above, where the arguments happen to already sit where they belong.
        seen = "<never called>";
        Sam2 h = chain(sink, "C1", "C2");
        h.invoke("A0", "A1");
        System.out.println("3cap2arg permuted = " + seen + " (want A0,A1,C1,C2)");

        // The capture reached as a RECEIVER through a chain, which is what the launcher does: the captured
        // value is itself a lambda, invoked by interface dispatch from inside another lambda's body.
        seen = "<never called>";
        Sam2 nested = chain(viaRef(), "C1", "C2");
        nested.invoke("A0", "A1");
        System.out.println("nested capture = " + seen + " (want A0,A1,C1,C2)");

        // The condition, not just the shape: the factory is a static method on an INTERFACE, so the lambda's
        // implementation method is referenced by InterfaceMethodref rather than Methodref.
        seen = "<never called>";
        Sam4 fi = Factory.ofVoidOnIface(sink);
        Object ri = fi.invoke("p", "q", "r", "s");
        System.out.println("iface-static 1cap4arg = " + seen + " (want p,q,r,s)");
        System.out.println("iface-static ret null = " + (ri == null) + " (want true)");

        seen = "<never called>";
        Sam2 hi = Factory.chainOnIface(sink, "C1", "C2");
        hi.invoke("A0", "A1");
        System.out.println("iface-static 3cap2arg = " + seen + " (want A0,A1,C1,C2)");

        // A <clinit>-stored INDY RESULT, which is the launcher's failing store. The non-indy MARKER is the
        // control: if it survives and REF does not, the initializer ran and the indy store was lost.
        System.out.println("clinit marker = " + Holder.MARKER + " (want set)");
        System.out.println("clinit indy ref nonNull = " + (Holder.REF != null) + " (want true)");
        System.out.println("clinit indy ref4 nonNull = " + (Holder.REF4 != null) + " (want true)");
        if (Holder.REF != null)
        {
            System.out.println("unbound kind9 = " + Holder.REF.apply(new Hi(), "x") + " (want hi:x)");
        }
        if (Holder.REF4 != null)
        {
            System.out.println("unbound 4arg = " + Holder.REF4.apply(new Hi(), "a", "b", "c") + " (want hi:abc)");
        }

        // And the launcher's full chain: the <clinit>-stored kind-9 reference CAPTURED by a second lambda,
        // then invoked through it -- which is where the NPE actually surfaces.
        Unbound2 captured = Holder.REF;
        Sam2 viaCapture = wrap(captured);
        System.out.println("captured via lambda = " + viaCapture.invoke("A0", "A1") + " (want hi:A0)");

        System.out.println("Lambda4Probe done");
    }
}
