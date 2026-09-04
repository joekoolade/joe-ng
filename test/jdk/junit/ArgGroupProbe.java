/**
 * Pins the {@code @ArgGroup} annotation the console launcher stops on.
 *
 * <p>The launcher dies with {@code Could not access or modify ArgGroup member ...
 * TestConsoleOutputOptionsMixin.consoleOutputOptions: NullPointerException}, thrown out of
 * {@code extractArgGroupSpec}. Reading that method's bytecode rules out the obvious candidates: the getter's
 * exception is SWALLOWED, and a null type is guarded before use. What is NOT guarded is
 *
 * <pre>builder.updateArgGroupAttributes(member.getAnnotation(ArgGroup.class));</pre>
 *
 * and, when the member is multi-value, {@code getTypeInfo().getAuxiliaryTypes()[0]}. So this reads the real
 * annotation off the real field and prints every element beside the host's answer.
 *
 * <p>{@code isAnnotationPresent} is checked alongside {@code getAnnotation}: the field was already CLASSIFIED
 * as an ArgGroup to get this far, so a null instance here would be the two disagreeing -- a self-contradiction
 * this runtime is supposed to avoid, and one that would explain the NPE exactly.
 */
public class ArgGroupProbe
{
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception
    {
        Class<?> ag = Class.forName("org.junit.platform.console.shadow.picocli.CommandLine$ArgGroup");
        String[] owners = {
            "org.junit.platform.console.options.TestConsoleOutputOptionsMixin",
            "org.junit.platform.console.options.TestDiscoveryOptionsMixin"
        };
        int k = 0;
        while (k < owners.length)
        {
            Class<?> c = Class.forName(owners[k]);
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            System.out.println("class " + owners[k].substring(owners[k].lastIndexOf('.') + 1)
                    + " fields=" + fs.length);
            int i = 0;
            while (i < fs.length)
            {
                boolean present = fs[i].isAnnotationPresent(
                        (Class<? extends java.lang.annotation.Annotation>) ag);
                Object got = fs[i].getAnnotation((Class<? extends java.lang.annotation.Annotation>) ag);
                // getGenericType() as well as getType(): picocli builds its ITypeInfo from the GENERIC type,
                // and extractArgGroupSpec ends with an UNGUARDED `cls.getName()` -- a null type there is the
                // NPE, and getType() alone would not show it. These are NESTED classes, which getGenericType
                // has not been exercised on.
                String gen;
                try
                {
                    Object g = fs[i].getGenericType();
                    gen = (g == null) ? "<<NULL>>" : (g instanceof Class ? ((Class<?>) g).getName() : g.toString());
                }
                catch (Throwable t)
                {
                    gen = "THREW " + t;
                }
                System.out.println("   f " + fs[i].getName()
                        + " type=" + fs[i].getType().getName()
                        + " generic=" + gen
                        + " agPresent=" + present
                        + " agGet=" + (got != null)
                        + ((present && got == null) ? "   <== PRESENT BUT NULL" : ""));
                if (got != null)
                {
                    org.junit.platform.console.shadow.picocli.CommandLine.ArgGroup g =
                            (org.junit.platform.console.shadow.picocli.CommandLine.ArgGroup) got;
                    // Every element updateArgGroupAttributes reads. A null String here is what turns into an
                    // NPE inside picocli, far from the annotation that produced it.
                    System.out.println("     heading=" + q(g.heading())
                            + " headingKey=" + q(g.headingKey())
                            + " exclusive=" + g.exclusive()
                            + " multiplicity=" + q(g.multiplicity())
                            + " validate=" + g.validate()
                            + " order=" + g.order());
                }
                i += 1;
            }
            k += 1;
        }

        // --- reproduce the launcher's failure and print the CAUSE ------------------------------------
        // picocli wraps whatever went wrong as
        //     InitializationException("Could not access or modify ArgGroup member ...", cause)
        // and extractArgGroupSpec's exception table covers only the getter, so the NPE comes from the
        // RECURSION into the group class's own members -- frames the wrapped message throws away. The cause
        // is retained, so ask for it: one stack beats another round of reading bytecode.
        try
        {
            Class<?> mx = Class.forName("org.junit.platform.console.options.TestConsoleOutputOptionsMixin");
            java.lang.reflect.Constructor<?> kc = mx.getDeclaredConstructor();
            kc.setAccessible(true);
            Object inst = kc.newInstance();
            org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec spec =
                    org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec
                            .forAnnotatedObject(inst);
            System.out.println("SPEC BUILT ok, options=" + spec.options().size()
                    + " argGroups=" + spec.argGroups().size());
        }
        catch (Throwable t)
        {
            // The metal throws this RAW, not wrapped, so its own stack is the evidence -- the cause is
            // empty because there is no wrapping here. Print both, in that order.
            System.out.println("SPEC FAILED: " + t);
            t.printStackTrace();
            Throwable c = t.getCause();
            System.out.println("  cause: " + (c == null ? "<none>" : c.toString()));
            if (c != null)
            {
                c.printStackTrace();
            }
        }
    }

    /** Quoted, and NULL called out: an empty string and a null print the same otherwise, and only one is a bug. */
    private static String q(String s)
    {
        return s == null ? "<<NULL>>" : ("[" + s + "]");
    }
}
