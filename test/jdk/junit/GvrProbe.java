/**
 * Reads picocli's {@code GroupValidationResult} directly, now that reflection over STATIC fields works.
 *
 * <p>The console launcher dies on {@code maybeThrow(validationResult.exception)} with a NULL exception, out of
 * {@code blockingFailure() { return type == Type.FAILURE_PRESENT || type == Type.FAILURE_PARTIAL; }}. Every
 * Type constant is known non-null and distinct (NestedEnumProbe, and #240's direct read), and every failure
 * path in validateGroupMultiplicity and ArgGroupSpec.validate supplies an exception. So the remaining
 * candidate is the INSTANCE field read itself.
 *
 * <p>{@code SUCCESS_PRESENT} and {@code SUCCESS_ABSENT} are the lever: they are statics built by the class's
 * own initializer with the ONE-ARG constructor, so their {@code type} must be the matching constant and their
 * {@code exception} must be null. Reading both off them tests the instance-field path against a known answer
 * rather than against a guess.
 *
 * <p>Every field is also listed with its declaration order, because two fields resolving to the SAME offset is
 * the failure shape that would make `exception` read as `type` (or the reverse), and a count cannot show it.
 */
public class GvrProbe
{
    public static void main(String[] args) throws Exception
    {
        Class<?> gvr = Class.forName(
                "org.junit.platform.console.shadow.picocli.CommandLine$ParseResult$GroupValidationResult");

        System.out.println("declared fields, in order:");
        java.lang.reflect.Field[] fs = gvr.getDeclaredFields();
        int i = 0;
        while (i < fs.length)
        {
            System.out.println("   [" + i + "] " + fs[i].getName()
                    + " type=" + fs[i].getType().getName()
                    + " static=" + java.lang.reflect.Modifier.isStatic(fs[i].getModifiers()));
            i += 1;
        }

        show(gvr, "SUCCESS_PRESENT");
        show(gvr, "SUCCESS_ABSENT");
    }

    /** Read a static GroupValidationResult, then its `type` and `exception` INSTANCE fields off it. */
    private static void show(Class<?> gvr, String constant)
    {
        try
        {
            java.lang.reflect.Field sf = gvr.getDeclaredField(constant);
            sf.setAccessible(true);
            Object inst = sf.get(null);
            if (inst == null)
            {
                System.out.println(constant + " = NULL  <== the static itself is unset");
                return;
            }
            java.lang.reflect.Field tf = gvr.getDeclaredField("type");
            java.lang.reflect.Field ef = gvr.getDeclaredField("exception");
            tf.setAccessible(true);
            ef.setAccessible(true);
            Object t = tf.get(inst);
            Object e = ef.get(inst);
            // `type` must be the matching constant and `exception` must be null: this instance came from the
            // ONE-ARG constructor. A null `type` means the instance read is wrong; a non-null `exception`
            // means the two fields alias.
            System.out.println(constant
                    + ": type=" + (t == null ? "NULL  <== WRONG" : t.toString())
                    + " exception=" + (e == null ? "null (correct)" : ("<" + e.getClass().getName() + ">  <== WRONG")));
        }
        catch (Throwable x)
        {
            System.out.println(constant + " THREW " + x);
        }
    }
}
