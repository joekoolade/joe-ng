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
        // ENUM IDENTITY, which is what picocli's success()/blockingFailure() actually test:
        //     boolean success() { return type == Type.SUCCESS_PRESENT || type == Type.SUCCESS_ABSENT; }
        // Reference comparison, not equals. Printing the field shows "SUCCESS_PRESENT" whether or not it is
        // THE constant, so every earlier reading of this probe was blind to a duplicate object -- and
        // GroupMatchContainer.validate throws precisely when success() is false, passing a null exception.
        try
        {
            java.lang.reflect.Field spf = gvr.getDeclaredField("SUCCESS_PRESENT");
            spf.setAccessible(true);
            Object inst0 = spf.get(null);
            java.lang.reflect.Field tf0 = gvr.getDeclaredField("type");
            tf0.setAccessible(true);
            Object held = tf0.get(inst0);
            System.out.println("enum identity: instance.type == Type.SUCCESS_PRESENT -> " + (held == spv)
                    + (held == spv ? "  OK" : "  <== WRONG (same name, different object)"));
            Object[] cs3 = ty.getEnumConstants();
            int z = 0;
            while (z < cs3.length)
            {
                if (String.valueOf(cs3[z]).equals("SUCCESS_PRESENT"))
                {
                    System.out.println("enum identity: getEnumConstants()[i] == static field -> " + (cs3[z] == spv));
                }
                z += 1;
            }
        }
        catch (Throwable t)
        {
            System.out.println("enum identity probe unavailable: " + t.getClass().getName());
        }

        // THE PREDICATES THEMSELVES, which is what picocli actually calls:
        //     GroupMatchContainer.validate does `if (validationResult.success()) return;` and throws otherwise,
        //     passing a null exception -- the messageless NPE the launcher dies of.
        // Reading the `type` FIELD (above) does not test these: success() reads Type.SUCCESS_PRESENT and
        // Type.SUCCESS_ABSENT, whose names COLLIDE with this class's own statics of the same names, which is
        // the exact shape of the same-class static fast-path bug. The field being right does not make the
        // predicate right.
        try
        {
            java.lang.reflect.Method succ = gvr.getDeclaredMethod("success");
            java.lang.reflect.Method block = gvr.getDeclaredMethod("blockingFailure");
            succ.setAccessible(true);
            block.setAccessible(true);
            String[] names = { "SUCCESS_PRESENT", "SUCCESS_ABSENT" };
            int n = 0;
            while (n < names.length)
            {
                java.lang.reflect.Field cf = gvr.getDeclaredField(names[n]);
                cf.setAccessible(true);
                Object v = cf.get(null);
                Object sv = succ.invoke(v);
                Object bv = block.invoke(v);
                System.out.println(names[n] + ".success()=" + sv + " (want true)"
                        + "  blockingFailure()=" + bv + " (want false)"
                        + (Boolean.TRUE.equals(sv) ? "  OK" : "  <== WRONG"));
                n += 1;
            }
        }
        catch (Throwable t)
        {
            System.out.println("predicate probe unavailable: " + t.getClass().getName() + ": " + t.getMessage());
        }

        // THE TWO-ARG CONSTRUCTOR, which is what every FAILURE result is built with. The one-arg form above
        // covers the SUCCESS_* statics; a failure carries an exception, and picocli's validate() does
        //     if (result.blockingFailure()) { maybeThrow(result.exception); }
        // so an `exception` field that does not hold what the constructor stored means maybeThrow(null) --
        // which our athrow-null rule then turns into the messageless NullPointerException the launcher dies
        // of, at CommandLine.java:13583 (`aload_1; athrow`).
        try
        {
            Class<?> pex = Class.forName(
                    "org.junit.platform.console.shadow.picocli.CommandLine$ParameterException");
            java.lang.reflect.Constructor<?> two = gvr.getDeclaredConstructor(ty, pex);
            two.setAccessible(true);
            java.lang.reflect.Field tf2 = gvr.getDeclaredField("type");
            java.lang.reflect.Field ef2 = gvr.getDeclaredField("exception");
            tf2.setAccessible(true);
            ef2.setAccessible(true);
            Object failType = null;
            Object[] cs2 = ty.getEnumConstants();
            int q = 0;
            while (q < cs2.length)
            {
                if (String.valueOf(cs2[q]).startsWith("FAILURE"))
                {
                    failType = cs2[q];
                    break;
                }
                q += 1;
            }
            Object made = two.newInstance(failType, null);      // null exception is legal; the FIELD is the test
            System.out.println("two-arg ctor: type=" + tf2.get(made)
                    + " (want " + failType + ")"
                    + (String.valueOf(tf2.get(made)).equals(String.valueOf(failType)) ? "  OK" : "  <== WRONG"));
            System.out.println("two-arg ctor: exception=" + ef2.get(made) + " (want null)");
        }
        catch (Throwable t)
        {
            System.out.println("two-arg ctor probe unavailable: " + t.getClass().getName());
        }

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
