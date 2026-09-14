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

    /** A capture that is itself reached by interface dispatch, as JUnit's `call` is. */
    static Call4 viaRef()
    {
        return Lambda4Probe::record;
    }

    static void record(String a, String b, String c, String d)
    {
        seen = a + "," + b + "," + c + "," + d;
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

        System.out.println("Lambda4Probe done");
    }
}
