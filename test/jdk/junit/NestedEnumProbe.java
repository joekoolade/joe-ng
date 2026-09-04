/**
 * Pins a deeply-nested private enum's constants read through {@code getstatic} -- the path picocli's
 * {@code blockingFailure()} compiles to, and the one no reflective probe can reach.
 *
 * <p>The launcher dies with {@code maybeThrow(null)} out of
 * {@code blockingFailure() { return type == Type.FAILURE_PRESENT || type == Type.FAILURE_PARTIAL; }}.
 * Reading the bytecode rules out a genuine validation failure: those always carry a MissingParameterException,
 * and only the ONE-ARG constructor (used by SUCCESS_PRESENT/SUCCESS_ABSENT) leaves {@code exception} null. So
 * the result is a SUCCESS whose {@code blockingFailure()} answered true -- which is what happens when EVERY
 * constant reads null, because {@code null == null}.
 *
 * <p>{@code getEnumConstants()} already reports len=5 on the metal, but that is a DIFFERENT path from
 * {@code getstatic}, so it is not evidence about this one. This probe uses getstatic directly and compares
 * identities the way picocli does.
 *
 * <p>Shaped like the real thing on purpose: PRIVATE, nested TWO deep, with the enum's own values referenced by
 * an enclosing class's static initializer -- picocli's Type sits inside GroupValidationResult inside
 * ParseResult inside CommandLine, and its constants are read from a sibling nested class.
 */
public class NestedEnumProbe
{
    static class Outer
    {
        private enum Kind
        {
            SUCCESS_PRESENT, SUCCESS_ABSENT, FAILURE_PRESENT, FAILURE_ABSENT, FAILURE_PARTIAL
        }

        /** Built in the enclosing class's <clinit>, exactly as GroupValidationResult builds SUCCESS_*. */
        static final Outer SUCCESS = new Outer(Kind.SUCCESS_ABSENT);
        static final Outer FAILED = new Outer(Kind.FAILURE_PARTIAL);

        final Kind kind;

        Outer(Kind k)
        {
            this.kind = k;
        }

        /** picocli's blockingFailure(), verbatim in shape: two getstatic compares against the field. */
        boolean blockingFailure()
        {
            return kind == Kind.FAILURE_PRESENT || kind == Kind.FAILURE_PARTIAL;
        }
    }

    public static void main(String[] args)
    {
        // Each constant read through getstatic, named, and checked for null. A null here is the bug; the
        // identity checks below are what turn it into the observed symptom.
        System.out.println("SUCCESS_PRESENT null=" + (Outer.Kind.SUCCESS_PRESENT == null));
        System.out.println("SUCCESS_ABSENT  null=" + (Outer.Kind.SUCCESS_ABSENT == null));
        System.out.println("FAILURE_PRESENT null=" + (Outer.Kind.FAILURE_PRESENT == null));
        System.out.println("FAILURE_PARTIAL null=" + (Outer.Kind.FAILURE_PARTIAL == null));

        // Distinctness: if the constants were all null they would compare EQUAL, which is precisely how a
        // SUCCESS result starts reporting a blocking failure.
        System.out.println("SUCCESS_ABSENT == FAILURE_PARTIAL = "
                + (Outer.Kind.SUCCESS_ABSENT == Outer.Kind.FAILURE_PARTIAL) + "   (want false)");

        // And the symptom itself.
        System.out.println("SUCCESS.blockingFailure() = " + Outer.SUCCESS.blockingFailure() + "   (want false)");
        System.out.println("FAILED.blockingFailure()  = " + Outer.FAILED.blockingFailure() + "   (want true)");
        System.out.println("SUCCESS.kind null=" + (Outer.SUCCESS.kind == null) + "   (want false)");
    }
}
