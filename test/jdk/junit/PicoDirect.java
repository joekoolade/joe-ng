package org.junit.platform.console.shadow.picocli;

/**
 * Calls picocli's own package-private predicates DIRECTLY, from inside its package.
 *
 * <p>Every previous reading of {@code success()} and {@code blockingFailure()} went through
 * {@code Method.invoke}, which marshals a return value differently from a compiled
 * {@code invokevirtual}. That leaves one hypothesis untested and consistent with everything measured so far:
 * the predicates are CORRECT, and the branch on their result is not -- a boolean returned by a virtual call
 * being read wrong at the call site would make every input measure right while
 * {@code GroupMatchContainer.validate} still takes the throwing path.
 *
 * <p>Being in the package is what makes the test possible: these members are package-private, so this is a
 * real compiled call, the same shape picocli itself emits. Both values are also printed as ints, because a
 * boolean that is neither 0 nor 1 is exactly what a mis-read return looks like and `true`/`false` would hide
 * it.
 */
public class PicoDirect
{
    public static void main(String[] args)
    {
        CommandLine.ParseResult.GroupValidationResult sp =
                CommandLine.ParseResult.GroupValidationResult.SUCCESS_PRESENT;
        CommandLine.ParseResult.GroupValidationResult sa =
                CommandLine.ParseResult.GroupValidationResult.SUCCESS_ABSENT;

        System.out.println("SUCCESS_PRESENT: type=" + sp.type
                + " success()=" + sp.success()
                + " asInt=" + (sp.success() ? 1 : 0)
                + " blockingFailure()=" + sp.blockingFailure());
        System.out.println("SUCCESS_ABSENT:  type=" + sa.type
                + " success()=" + sa.success()
                + " asInt=" + (sa.success() ? 1 : 0)
                + " blockingFailure()=" + sa.blockingFailure());

        // The branch picocli actually takes, written the same way it writes it.
        if (sp.success())
        {
            System.out.println("branch: success() TRUE  -- validate would RETURN (correct)");
        }
        else
        {
            System.out.println("branch: success() FALSE -- validate would THROW  <== the bug");
        }
        if (sp.blockingFailure())
        {
            System.out.println("branch: blockingFailure() TRUE  <== would also throw");
        }
        else
        {
            System.out.println("branch: blockingFailure() FALSE (correct)");
        }

        container();
    }

    /**
     * Drive the failing method itself, in-package and WITHOUT reflection, printing the field it branches on
     * at every stage. The reflective version of this was worthless: it built the container by hand, validate()
     * threw before reaching the assignment at bytecode 51, and the null I then read was my own doing.
     *
     * <p>Here the container is built the way picocli builds the ROOT one (a null group), and
     * `validationResult` is read directly at each step -- so "what did validate() see" is answered by the
     * program rather than inferred from bytecode.
     */
    private static void container()
    {
        CommandLine cl = new CommandLine(new Bare());
        CommandLine.ParseResult.GroupMatchContainer root =
                new CommandLine.ParseResult.GroupMatchContainer(null, cl);
        System.out.println("root: group=" + root.group()
                + " matches=" + (root.matches() == null ? "NULL" : "" + root.matches().size())
                + " validationResult=" + vrOf(root));
        try
        {
            root.validate(cl);
            System.out.println("root: validate() RETURNED normally");
        }
        catch (Throwable t)
        {
            System.out.println("root: validate() THREW " + t.getClass().getName());
        }
        Object vr = vrOf(root);
        System.out.println("root: after validate, validationResult=" + vr
                + (vr == null ? "  <== never assigned"
                   : "  type=" + ((CommandLine.ParseResult.GroupValidationResult) vr).type
                     + " success()=" + ((CommandLine.ParseResult.GroupValidationResult) vr).success()));
    }

    /** The field is private; only the READ needs reflection -- the validate() call above stays a real one. */
    private static Object vrOf(CommandLine.ParseResult.GroupMatchContainer c)
    {
        try
        {
            java.lang.reflect.Field f = c.getClass().getDeclaredField("validationResult");
            f.setAccessible(true);
            return f.get(c);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    @CommandLine.Command(name = "bare")
    public static class Bare
    {
    }
}
