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
            dumpExecuteSpec();
            // THE SPEC'S OWN VALUES, which is what validation reads -- not the annotation's.
            // GroupMatchContainer.validate fails a subgroup only when `subgroup.validate()` is TRUE and the
            // group is not effectively optional. JUnit WRITES validate=false, and the annotation reads back
            // false (above), but nothing has checked that the value survives into the built ArgGroupSpec:
            // it travels annotation -> updateArgGroupAttributes -> Builder.validate(boolean) -> spec, and a
            // boolean lost anywhere on that path turns every one of these groups into a validated, required
            // group -- which is exactly the failure the launcher ends in.
            for (Object g : spec.argGroups())
            {
                org.junit.platform.console.shadow.picocli.CommandLine.Model.ArgGroupSpec gs =
                        (org.junit.platform.console.shadow.picocli.CommandLine.Model.ArgGroupSpec) g;
                System.out.println("   SPEC group: validate=" + gs.validate()
                        + " (want false)"
                        + " exclusive=" + gs.exclusive()
                        + " multiplicity=" + gs.multiplicity()
                        + " args=" + gs.args().size()
                        + (gs.validate() ? "  <== WRONG, a written false became true" : "  OK"));
                // Range.min IS THE DECIDER. isGroupEffectivelyOptional returns true immediately when
                // `multiplicity().min == 0` -- a getfield, not a method -- and that early exit is what stops
                // the unmatched-subgroup loop from building a failure result. toString() can render "0..1"
                // from the original string while the min/max FIELDS read wrong, so the printed multiplicity
                // above proves nothing about this.
                org.junit.platform.console.shadow.picocli.CommandLine.Range r = gs.multiplicity();
                System.out.println("     multiplicity fields: min=" + r.min + " (want 0)"
                        + " max=" + r.max + " (want 1)"
                        + " isVariable=" + r.isVariable
                        + (r.min == 0 ? "  OK" : "  <== WRONG, group reads as REQUIRED"));
            }
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

    /**
     * The LAUNCHER's OWN spec, which is the only one that matters.
     *
     * <p>Every earlier reading here built a spec for a simple object and saw ONE group; the command the
     * launcher runs is ExecuteTestsCommand, whose groups arrive through @Mixin fields --
     * TestDiscoveryOptionsMixin alone declares three. The mixin path is exactly where an earlier bug (#231)
     * silently lost options, so a group's `validate` surviving on a simple spec says nothing about surviving
     * on this one. GroupMatchContainer.validate fails an unmatched subgroup only when `validate()` is TRUE
     * and multiplicity().min != 0, so those two values, per group, decide the launcher's failure.
     */
    private static void dumpExecuteSpec()
    {
        try
        {
            Class<?> cmd = Class.forName("org.junit.platform.console.command.ExecuteTestsCommand");
            Class<?> fac = Class.forName("org.junit.platform.console.command.ConsoleTestExecutor$Factory");
            java.lang.reflect.Constructor<?> cc = cmd.getDeclaredConstructor(fac);
            cc.setAccessible(true);
            Object inst = cc.newInstance(new Object[] { null });   // the factory is unused for spec building
            org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec sp =
                    org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec
                            .forAnnotatedObject(inst);
            System.out.println("EXECUTE SPEC: options=" + sp.options().size()
                    + " argGroups=" + sp.argGroups().size());
            for (Object g : sp.argGroups())
            {
                dumpGroup((org.junit.platform.console.shadow.picocli.CommandLine.Model.ArgGroupSpec) g, "  ");
            }
        }
        catch (Throwable t)
        {
            System.out.println("EXECUTE SPEC FAILED: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void dumpGroup(org.junit.platform.console.shadow.picocli.CommandLine.Model.ArgGroupSpec g,
                                  String pad)
    {
        // One accessor at a time. The whole-line print NPE'd, and which member is null is the finding --
        // picocli reads `multiplicity().min` with no null check, so a null multiplicity is a crash in its
        // own validation path rather than a wrong answer.
        String vs = "?";
        String ms = "?";
        String as = "?";
        String ss = "?";
        try { vs = String.valueOf(g.validate()); } catch (Throwable t) { vs = "THREW " + t.getClass().getName(); }
        try
        {
            org.junit.platform.console.shadow.picocli.CommandLine.Range r = g.multiplicity();
            ms = (r == null) ? "NULL <== " : ("min=" + r.min + " max=" + r.max);
        }
        catch (Throwable t) { ms = "THREW " + t.getClass().getName(); }
        try { as = String.valueOf(g.args() == null ? "NULL" : "" + g.args().size()); }
        catch (Throwable t) { as = "THREW " + t.getClass().getName(); }
        try { ss = String.valueOf(g.subgroups() == null ? "NULL" : "" + g.subgroups().size()); }
        catch (Throwable t) { ss = "THREW " + t.getClass().getName(); }
        System.out.println(pad + "group validate=" + vs + " " + ms + " args=" + as + " subgroups=" + ss);
        if (!"?".equals(ss) && ss.startsWith("THREW")) { return; }
        try
        {
            for (Object sub : g.subgroups())
            {
                dumpGroup((org.junit.platform.console.shadow.picocli.CommandLine.Model.ArgGroupSpec) sub, pad + "  ");
            }
        }
        catch (Throwable t)
        {
            System.out.println(pad + "  subgroup walk THREW " + t.getClass().getName());
        }
    }
}
