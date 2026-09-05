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
        // DISCRIMINATOR, run BEFORE GroupValidationResult is touched. Both `type` and `exception` read null,
        // so the reading alone cannot tell "the field holds null" from "the read used the wrong slot". Forcing
        // the Type enum to initialize FIRST separates them: if `type` then comes back non-null the fault was
        // ORDERING (the initializer ran before Type had its constants), and if it is still null the ordering
        // is exonerated and the instance-field path itself is wrong.
        Class<?> ty = Class.forName(
                "org.junit.platform.console.shadow.picocli.CommandLine$ParseResult$GroupValidationResult$Type");
        java.lang.reflect.Field sp = ty.getDeclaredField("SUCCESS_PRESENT");
        sp.setAccessible(true);
        Object spv = sp.get(null);
        System.out.println("Type.SUCCESS_PRESENT (forced first) nonNull=" + (spv != null));

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

        // SEPARATE THE WRITE FROM THE READ. Both fields read null, so the reading alone cannot say whether the
        // constructor stored nothing or the get is looking in the wrong place. Writing a KNOWN value through
        // the same Field and reading it straight back answers that: a successful round-trip means the get is
        // sound at the offset the set used, so a constructor value missing from it was never written there.
        java.lang.reflect.Field sf = gvr.getDeclaredField("SUCCESS_PRESENT");
        sf.setAccessible(true);
        Object inst = sf.get(null);
        System.out.println("instance class = " + inst.getClass().getName());
        java.lang.reflect.Field tf = gvr.getDeclaredField("type");
        tf.setAccessible(true);
        tf.set(inst, spv);
        Object back = tf.get(inst);
        System.out.println("reflective set/get round-trip nonNull=" + (back != null)
                + " same=" + (back == spv));

        // SPLIT THE CONSTRUCTOR FROM ITS CALL SITE. The read is now known sound, so the value the <clinit>
        // stored never reached that offset -- which is either the constructor body failing to store it, or
        // the <clinit>'s own invokespecial not reaching that body. Building one HERE, through a completely
        // different call path, tells the two apart: a fresh instance with a non-null `type` exonerates the
        // body and puts the fault at the <clinit> call site.
        try
        {
            java.lang.reflect.Constructor<?> ctor = gvr.getDeclaredConstructor(ty);
            ctor.setAccessible(true);
            Object fresh = ctor.newInstance(spv);
            System.out.println("fresh via reflective ctor: type nonNull=" + (tf.get(fresh) != null)
                    + " same=" + (tf.get(fresh) == spv));
        }
        catch (Throwable t)
        {
            System.out.println("reflective ctor unavailable: " + t.getClass().getName());
        }
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
