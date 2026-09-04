/**
 * Reproduces the console launcher's last blocker: parsing {@code --disable-banner}.
 *
 * <p>The launcher reports {@code NullPointerException ... while processing argument at or before arg[3]
 * '--disable-banner'} and prints usage instead of running the test. That option is a PRIVATE BOOLEAN declared
 * in {@code BaseCommand} and set on an {@code ExecuteTestsCommand} instance, so it is an INHERITED PRIMITIVE
 * field written reflectively -- a shape the inherited-field fix (#231) covered for offsets and that
 * FieldSlotProbe checked only for a REFERENCE field.
 *
 * <p>Parses against the `execute` subcommand alone rather than the whole launcher tree: same option, same
 * declaring class, far smaller closure than building MainCommand plus three subcommands.
 *
 * <p>picocli catches and re-wraps, so the message names the argument and throws the frames away. The CAUSE is
 * retained and is where the answer is -- printed here rather than inferred.
 */
public class ParseOptProbe
{
    public static void main(String[] args) throws Exception
    {
        // Direct reflective set of the same field, first: if THIS fails the parse never had a chance, and the
        // two answers separate "reflection cannot write an inherited primitive" from "picocli's path differs".
        Class<?> base = Class.forName("org.junit.platform.console.command.BaseCommand");
        Object exec = make("org.junit.platform.console.command.ExecuteTestsCommand");
        java.lang.reflect.Field f = base.getDeclaredField("disableBanner");
        f.setAccessible(true);
        try
        {
            Object before = f.get(exec);
            f.set(exec, Boolean.TRUE);
            Object after = f.get(exec);
            System.out.println("direct set inherited boolean: before=" + before + " after=" + after
                    + (Boolean.TRUE.equals(after) ? "  OK" : "  <== WRONG"));
        }
        catch (Throwable t)
        {
            System.out.println("direct set THREW " + t);
            t.printStackTrace();
        }

        // Is the group-validation enum initialised? picocli's blockingFailure() is
        //     type == Type.FAILURE_PRESENT || type == Type.FAILURE_PARTIAL
        // so if the CONSTANTS read null AND the instance's `type` is null, `null == null` is TRUE: the result
        // claims a blocking failure and hands over its null `exception`, which is the maybeThrow(null) the
        // launcher dies on. `UNRESOLVED STATIC` cannot see this -- it fires on a MISSING cell, not on a cell
        // that exists and was never written because the <clinit> did not run.
        try
        {
            Class<?> ty = Class.forName(
                    "org.junit.platform.console.shadow.picocli.CommandLine$ParseResult$GroupValidationResult$Type");
            Object[] cs = ty.getEnumConstants();
            System.out.println("Type.getEnumConstants: " + (cs == null ? "NULL" : ("len=" + cs.length)));
            java.lang.reflect.Field[] fs = ty.getDeclaredFields();
            int i = 0;
            while (i < fs.length)
            {
                if (fs[i].getType() == ty)                      // the constants themselves
                {
                    fs[i].setAccessible(true);
                    Object v = fs[i].get(null);
                    System.out.println("   const " + fs[i].getName() + " = "
                            + (v == null ? "NULL  <== uninitialised" : "present"));
                }
                i += 1;
            }
        }
        catch (Throwable t)
        {
            System.out.println("enum probe THREW " + t);
        }

        // Now the real thing.
        try
        {
            org.junit.platform.console.shadow.picocli.CommandLine cl =
                    new org.junit.platform.console.shadow.picocli.CommandLine(
                            make("org.junit.platform.console.command.ExecuteTestsCommand"));
            cl.parseArgs("--disable-banner");
            System.out.println("PARSE OK");
        }
        catch (Throwable t)
        {
            System.out.println("PARSE FAILED: " + t);
            t.printStackTrace();
            Throwable c = t.getCause();
            System.out.println("  cause: " + (c == null ? "<none>" : c.toString()));
            if (c != null)
            {
                c.printStackTrace();
            }
        }
    }

    private static Object make(String cn) throws Exception
    {
        Class<?> c = Class.forName(cn);
        try
        {
            java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        }
        catch (Throwable t)
        {
            Class<?> fac = Class.forName("org.junit.platform.console.command.ConsoleTestExecutor$Factory");
            java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor(fac);
            k.setAccessible(true);
            return k.newInstance(new Object[] { null });
        }
    }
}
