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
    public static void main(String[] args) throws Exception
    {
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
