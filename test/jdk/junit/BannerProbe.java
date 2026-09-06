/**
 * Reproduces the console launcher's `--disable-banner` failure in isolation, and prints the CAUSE.
 *
 * <p>The launcher reports only picocli's own wrapper -- "NullPointerException: null while processing argument
 * at or before arg[3] '--disable-banner'" -- which names the argument and nothing about where the NPE came
 * from. Driving `CommandLine.parseArgs` directly lets the cause's stack trace be printed, which is the one
 * piece of evidence the failure has never carried.
 *
 * <p>`--disable-ansi-colors` parses FINE in the same command line, so the interesting comparison is in here
 * too: that option lives on a @Mixin object and is declared by that object's own class, while
 * `disableBanner` is declared by BaseCommand and set on an ExecuteTestsCommand instance -- an INHERITED
 * private boolean. ParseOptProbe already showed a direct reflective set of that field works, so if this
 * reproduces, the fault is in how picocli reaches the field rather than in reflection itself.
 */
public class BannerProbe
{
    /**
     * A MINIMAL picocli command, defined here so nothing about JUnit's structure is involved.
     *
     * <p>The JUnit command reaching this failure turned out to have ZERO arg groups, so the fault is in the
     * root container's validation of an empty group set -- i.e. core picocli parsing, not the command being
     * parsed. If this trivial one throws too, the reproduction shrinks from a 2135-entry jar to four lines
     * that can be bisected; if it PARSES, the fault needs something JUnit's commands have and this lacks
     * (mixins, inheritance, subcommands), which is just as useful a split.
     */
    @org.junit.platform.console.shadow.picocli.CommandLine.Command(name = "tiny")
    public static class Tiny
    {
        @org.junit.platform.console.shadow.picocli.CommandLine.Option(names = "--flag")
        boolean flag;
    }

    /** No options at all: the smallest command picocli can be handed. */
    @org.junit.platform.console.shadow.picocli.CommandLine.Command(name = "bare")
    public static class Bare
    {
    }

    /** One arm of the bisect: build a CommandLine on {@code obj} and parse {@code argv}. */
    private static void arm(String label, Object obj, String[] argv)
    {
        try
        {
            org.junit.platform.console.shadow.picocli.CommandLine c =
                    new org.junit.platform.console.shadow.picocli.CommandLine(obj);
            c.parseArgs(argv);
            System.out.println("  " + label + ": OK");
        }
        catch (Throwable t)
        {
            Throwable r = t;
            while (r.getCause() != null) { r = r.getCause(); }
            System.out.println("  " + label + ": THREW " + r.getClass().getName());
        }
    }

    /**
     * BISECT. Three arms that differ in one thing each, so the failing condition is read off directly rather
     * than reasoned about: whether an OPTION must be declared, and whether an ARGUMENT must be supplied.
     * validateGroups runs at the END of every parse, so if the bare command with no arguments also throws,
     * neither is required and the fault is in the parse epilogue itself.
     */
    private static void bisect()
    {
        System.out.println("--- bisect");
        arm("bare command, no args   ", new Bare(), new String[] { });
        arm("option declared, no args", new Tiny(), new String[] { });
        arm("option declared, passed ", new Tiny(), new String[] { "--flag" });
    }

    private static void tiny()
    {
        try
        {
            org.junit.platform.console.shadow.picocli.CommandLine c =
                    new org.junit.platform.console.shadow.picocli.CommandLine(new Tiny());
            System.out.println("--- tiny: CommandLine built");
            c.parseArgs(new String[] { "--flag" });
            System.out.println("    tiny parseArgs OK");
        }
        catch (Throwable t)
        {
            Throwable r = t;
            while (r.getCause() != null) { r = r.getCause(); }
            System.out.println("    tiny THREW " + t.getClass().getName()
                    + " root " + r.getClass().getName() + ": " + r.getMessage());
            r.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception
    {
        bisect();
        tiny();

        // ListTestEnginesCommand, not ExecuteTestsCommand: it has a no-arg constructor and extends the same
        // BaseCommand, so it inherits `disableBanner` in exactly the same way -- the condition is preserved.
        Class<?> cmd = Class.forName("org.junit.platform.console.command.ListTestEnginesCommand");
        java.lang.reflect.Constructor<?> cc = cmd.getDeclaredConstructor();
        cc.setAccessible(true);                          // the command classes are package-private
        Object instance = cc.newInstance();
        System.out.println("command instance = " + instance.getClass().getName());

        Class<?> clazz = Class.forName("org.junit.platform.console.shadow.picocli.CommandLine");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor(Object.class);
        ctor.setAccessible(true);
        Object cl = ctor.newInstance(instance);
        System.out.println("CommandLine built = " + (cl != null));

        java.lang.reflect.Method parseArgs = clazz.getDeclaredMethod("parseArgs", String[].class);

        one(parseArgs, cl, "--disable-ansi-colors");   // the one that WORKS, as the control
        one(parseArgs, cl, "--disable-banner");        // the one that fails

        // WHAT validateArgs ACTUALLY RETURNS. GroupMatch.validate ends with
        //     this.validationResult = group.validateArgs(commandLine, matchedArgs);
        // and GroupMatchContainer.validate then throws on that result -- passing its `exception`, which is
        // null. A result whose `type` is NULL explains every observation at once: success() is false
        // (null == SUCCESS_PRESENT fails), blockingFailure() is false (null == FAILURE_* fails), and there is
        // no exception to hand over. The constants and predicates are already proven sound in isolation, so
        // the question is what THIS call produces.
        try
        {
            Class<?> gspec = Class.forName(
                    "org.junit.platform.console.shadow.picocli.CommandLine$Model$ArgGroupSpec");
            Class<?> gvr = Class.forName(
                    "org.junit.platform.console.shadow.picocli.CommandLine$ParseResult$GroupValidationResult");
            java.lang.reflect.Method va = gspec.getDeclaredMethod("validateArgs", clazz, java.util.Collection.class);
            va.setAccessible(true);
            java.lang.reflect.Field tf = gvr.getDeclaredField("type");
            java.lang.reflect.Field ef = gvr.getDeclaredField("exception");
            java.lang.reflect.Method succ = gvr.getDeclaredMethod("success");
            tf.setAccessible(true);
            ef.setAccessible(true);
            succ.setAccessible(true);
            java.lang.reflect.Method groups = clazz.getDeclaredMethod("getCommandSpec");
            Object spec = groups.invoke(cl);
            java.lang.reflect.Method ag = spec.getClass().getDeclaredMethod("argGroups");
            ag.setAccessible(true);
            java.util.List<?> gs = (java.util.List<?>) ag.invoke(spec);
            System.out.println("--- validateArgs on " + gs.size() + " group(s)");
            for (Object g : gs)
            {
                Object r = va.invoke(g, cl, new java.util.ArrayList<Object>());
                System.out.println("    result type=" + tf.get(r)
                        + " exception=" + ef.get(r)
                        + " success=" + succ.invoke(r)
                        + (tf.get(r) == null ? "  <== NULL TYPE" : ""));
            }
        }
        catch (Throwable t)
        {
            System.out.println("validateArgs probe unavailable: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void one(java.lang.reflect.Method parseArgs, Object cl, String opt)
    {
        System.out.println("--- parseArgs " + opt);
        try
        {
            parseArgs.invoke(cl, (Object) new String[] { opt });
            System.out.println("    OK");
        }
        catch (Throwable t)
        {
            Throwable c = t;
            while (c.getCause() != null)
            {
                c = c.getCause();
            }
            System.out.println("    THREW " + t.getClass().getName());
            System.out.println("    root  " + c.getClass().getName() + ": " + c.getMessage());
            c.printStackTrace();
        }
    }
}
