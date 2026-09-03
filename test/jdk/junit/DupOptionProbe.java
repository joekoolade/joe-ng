import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pins the guard picocli uses to keep {@code mixinStandardHelpOptions} from being added twice.
 *
 * <p>The launcher reports {@code Multiple options [--help, --help, --help, --help] are marked as
 * 'usageHelp=true'}, and the HOST JVM running the same jar and command reports nothing -- so the guard is
 * failing open here. Its whole body is
 *
 * <pre>
 *   for (String key : mixin.optionsMap().keySet())
 *       if (this.optionsMap().containsKey(key)) { overlap = true; break; }
 *   if (!overlap) { addMixin(...); }
 * </pre>
 *
 * where both maps are a {@code LinkedHashMap} behind picocli's {@code CaseAwareLinkedMap} (case-SENSITIVE by
 * default, so {@code containsKey} delegates straight to it) reached through {@code
 * Collections.unmodifiableMap} -- which joe-ng overlays.
 *
 * <p>The keys are the crux and are why this is not simply a map test: an option name is an element of
 * {@code @Option(names = {"-h", "--help"})}, so it is a String the ANNOTATION RUNTIME built, not a literal
 * the compiler interned. Two separately-built Strings must hash alike AND compare equal for the lookup to
 * hit; a mismatch in either makes {@code containsKey} miss while every other use of the map looks correct.
 * Each arm therefore prints its inputs, not just a verdict.
 */
public class DupOptionProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Option
    {
        String[] names();
    }

    /** Two DISTINCT classes carrying the same names, so the two Strings come from two annotation instances --
     *  exactly picocli's shape, where the mixin's spec and the receiver's spec are built separately. */
    @Option(names = { "-h", "--help" })
    public static class MixinLike
    {
    }

    @Option(names = { "-h", "--help" })
    public static class ReceiverLike
    {
    }

    public static void main(String[] args) throws Exception
    {
        String[] mixinNames = MixinLike.class.getAnnotation(Option.class).names();
        String[] recvNames = ReceiverLike.class.getAnnotation(Option.class).names();

        System.out.println("names.length     = " + mixinNames.length + " / " + recvNames.length);
        System.out.println("names[1]         = [" + mixinNames[1] + "] [" + recvNames[1] + "]");

        // --- the String pair itself: identity, equality, hash ---------------------------------------
        // A hash that matches while equals says false is the shape that makes containsKey miss silently.
        String anno = mixinNames[1];
        String lit = "--help";
        System.out.println("anno==lit        = " + (anno == lit));
        System.out.println("anno.equals(lit) = " + anno.equals(lit));
        System.out.println("lit.equals(anno) = " + lit.equals(anno));
        System.out.println("hash anno/lit    = " + anno.hashCode() + " / " + lit.hashCode());
        System.out.println("len anno/lit     = " + anno.length() + " / " + lit.length());
        System.out.println("anno.eq(recv[1]) = " + anno.equals(recvNames[1]));
        System.out.println("hash recv[1]     = " + recvNames[1].hashCode());

        // --- a plain LinkedHashMap keyed by LITERALS (the control) -----------------------------------
        LinkedHashMap<String, Object> byLit = new LinkedHashMap<String, Object>();
        byLit.put("-h", "X");
        byLit.put("--help", "X");
        System.out.println("lit map size     = " + byLit.size());
        System.out.println("lit ck lit       = " + byLit.containsKey("--help"));
        System.out.println("lit ck anno      = " + byLit.containsKey(anno));

        // --- keyed by ANNOTATION Strings, probed with the other instance's -- picocli's actual case ---
        LinkedHashMap<String, Object> byAnno = new LinkedHashMap<String, Object>();
        int i = 0;
        while (i < mixinNames.length)
        {
            byAnno.put(mixinNames[i], "X");
            i += 1;
        }
        System.out.println("anno map size    = " + byAnno.size());
        System.out.println("anno ck lit      = " + byAnno.containsKey("--help"));
        System.out.println("anno ck other    = " + byAnno.containsKey(recvNames[1]));

        // --- keySet iteration: an EMPTY keySet makes the guard's loop body never run ------------------
        int n = 0;
        for (String k : byAnno.keySet())
        {
            n += 1;
        }
        System.out.println("anno keySet n    = " + n);

        // --- through Collections.unmodifiableMap, which joe-ng overlays ------------------------------
        Map<String, Object> um = java.util.Collections.unmodifiableMap(byAnno);
        int un = 0;
        for (String k : um.keySet())
        {
            un += 1;
        }
        System.out.println("unmod keySet n   = " + un);
        System.out.println("unmod ck other   = " + um.containsKey(recvNames[1]));

        // --- the guard itself, run twice: the second pass MUST see the overlap -----------------------
        LinkedHashMap<String, Object> receiver = new LinkedHashMap<String, Object>();
        int adds = 0;
        int pass = 0;
        while (pass < 4)
        {
            boolean overlap = false;
            for (String key : um.keySet())
            {
                if (receiver.containsKey(key))
                {
                    overlap = true;
                    break;
                }
            }
            if (!overlap)
            {
                int j = 0;
                while (j < recvNames.length)
                {
                    receiver.put(recvNames[j], "X");
                    j += 1;
                }
                adds += 1;
            }
            pass += 1;
        }
        System.out.println("guard adds       = " + adds + " (want 1)");
        System.out.println(adds == 1 ? "GUARD OK" : "GUARD FAILS OPEN");

        // --- THE REAL THING: picocli's own CommandSpec, from the jar ---------------------------------
        // The synthetic arms above reproduce the guard's SHAPE; this runs the GUARD ITSELF. Reproducing a
        // shape is not reproducing a condition -- the lesson this VM has taught before -- so the arms are
        // kept as a control and the verdict comes from here.
        realPicocli();
    }

    private static void realPicocli() throws Exception
    {
        org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec spec =
                org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec.create();
        System.out.println("pico options0    = " + spec.options().size() + " map=" + spec.optionsMap().size());
        spec.mixinStandardHelpOptions(true);
        System.out.println("pico after1      = " + spec.options().size() + " map=" + spec.optionsMap().size());
        spec.mixinStandardHelpOptions(true);
        System.out.println("pico after2      = " + spec.options().size() + " map=" + spec.optionsMap().size());
        spec.mixinStandardHelpOptions(true);
        spec.mixinStandardHelpOptions(true);
        System.out.println("pico after4      = " + spec.options().size() + " map=" + spec.optionsMap().size()
                + " (want 2 / 4)");

        // Which half of the guard failed: are the mixin's names visible, and does the receiver admit them?
        org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec probe =
                org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec
                        .forAnnotatedObject(new AutoHelpLike());
        System.out.println("pico mixin keys  = " + probe.optionsMap().size());
        int seen = 0;
        for (String k : probe.optionsMap().keySet())
        {
            System.out.println("  mixin key      = [" + k + "] recvHas=" + spec.optionsMap().containsKey(k));
            seen += 1;
        }
        System.out.println("pico mixin iter  = " + seen);
        System.out.println((spec.options().size() == 2) ? "PICO GUARD OK" : "PICO GUARD FAILS OPEN");

        // --- picocli's ACTUAL hierarchy walk over JUnit's REAL command classes -----------------------
        // extractCommandSpec builds its class list as
        //     while (cls != null) { hierarchy.add(cls); cls = cls.getSuperclass(); }
        // and then calls initFromAnnotatedMembers ONCE PER LEVEL into the SAME spec. So an option declared
        // once is added once -- unless the walk sees its declaring class more than once. BaseCommand is
        // where @Option(names={"-h","--help"}, usageHelp=true) lives, and FOUR classes extend it, so this
        // measures exactly what picocli is fed. Counting is not enough: each level is NAMED, because a walk
        // that repeats and a walk that is merely long look identical in a total.
        realHierarchy();

        // --- every annotation element picocli CONSULTS, printed rather than guessed ------------------
        // Four command classes each contribute exactly one --help, and the launcher reports four on ONE
        // spec -- the arithmetic of ScopeType.INHERIT, which copies an option into related specs. `scope`
        // is an ENUM element read from its DEFAULT, and defaults and enum elements are both recent here,
        // so the whole element set is dumped and diffed against the host instead of one being guessed at.
        realElements();

        // --- JUnit's EXACT command structure, in real picocli ----------------------------------------
        // MainCommand.run() builds
        //     new CommandLine(mainCommand).addSubcommand(discover).addSubcommand(execute).addSubcommand(list)
        // where MainCommand is @Command(scope = INHERIT) and declares --help itself, and each subcommand
        // inherits --help from BaseCommand. INHERIT copies the parent's args into every subcommand, so this
        // is the one arrangement in which a second --help can reach a spec at all -- and the launcher's
        // count of FOUR is 3 subcommands + 1. Every spec's tally is printed, because "which spec" is the
        // question the warning itself cannot answer.
        realStructure();

        // --- the REAL command objects, assembled exactly as MainCommand.run() does -------------------
        // Every reconstruction above is correct on the metal, so the condition lives in the REAL classes,
        // not in the shape. These are built reflectively (the ConsoleTestExecutor factory is not touched
        // while the spec is constructed, so null is safe) and each --help is reported with its inherited()
        // flag: that is what separates "the walk found it twice" from "inheritance copied it in".
        realTree();
    }

    /** Constructs a command. The overlay offers {@code getDeclaredConstructor(Class...)} but not the plural
     *  form, so the two shapes JUnit actually uses are named outright: no-arg, or one ConsoleTestExecutor
     *  factory (which the spec build never dereferences, so null is safe). */
    private static Object make(String cn) throws Exception
    {
        Class<?> c = Class.forName(cn);
        try
        {
            java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);            // every one of these commands is package-private
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

    private static void realTree() throws Exception
    {
        Object main = make("org.junit.platform.console.command.MainCommand");
        org.junit.platform.console.shadow.picocli.CommandLine cl =
                new org.junit.platform.console.shadow.picocli.CommandLine(main)
                        .addSubcommand(make("org.junit.platform.console.command.DiscoverTestsCommand"))
                        .addSubcommand(make("org.junit.platform.console.command.ExecuteTestsCommand"))
                        .addSubcommand(make("org.junit.platform.console.command.ListTestEnginesCommand"));

        org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec top = cl.getCommandSpec();
        detail("junit", top);
        for (String k : top.subcommands().keySet())
        {
            detail(k, top.subcommands().get(k).getCommandSpec());
        }
    }

    private static void detail(String label,
            org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec spec)
    {
        int help = 0;
        for (org.junit.platform.console.shadow.picocli.CommandLine.Model.OptionSpec o : spec.options())
        {
            if (o.usageHelp())
            {
                help += 1;
            }
        }
        System.out.println("tree " + label + " options=" + spec.options().size()
                + " map=" + spec.optionsMap().size() + " usageHelp=" + help
                + ((help <= 1) ? "  OK" : "  <== DUPLICATED"));
        if (help > 1)
        {
            for (org.junit.platform.console.shadow.picocli.CommandLine.Model.OptionSpec o : spec.options())
            {
                if (o.usageHelp())
                {
                    System.out.println("   dup " + o.longestName() + " inherited=" + o.inherited()
                            + " scope=" + o.scopeType() + " id=" + System.identityHashCode(o));
                }
            }
        }
    }

    @org.junit.platform.console.shadow.picocli.CommandLine.Command(
            name = "parent",
            scope = org.junit.platform.console.shadow.picocli.CommandLine.ScopeType.INHERIT)
    public static class ParentLike
    {
        @org.junit.platform.console.shadow.picocli.CommandLine.Option(
                names = { "-h", "--help" }, usageHelp = true)
        boolean help;
    }

    /** Stands in for BaseCommand: declares the SAME option the parent will try to inherit down. */
    public abstract static class BaseLike
    {
        @org.junit.platform.console.shadow.picocli.CommandLine.Option(
                names = { "-h", "--help" }, usageHelp = true)
        boolean help;
    }

    @org.junit.platform.console.shadow.picocli.CommandLine.Command(name = "sub1")
    public static class Sub1 extends BaseLike
    {
    }

    @org.junit.platform.console.shadow.picocli.CommandLine.Command(name = "sub2")
    public static class Sub2 extends BaseLike
    {
    }

    @org.junit.platform.console.shadow.picocli.CommandLine.Command(name = "sub3")
    public static class Sub3 extends BaseLike
    {
    }

    private static void realStructure() throws Exception
    {
        org.junit.platform.console.shadow.picocli.CommandLine cl =
                new org.junit.platform.console.shadow.picocli.CommandLine(new ParentLike())
                        .addSubcommand(new Sub1())
                        .addSubcommand(new Sub2())
                        .addSubcommand(new Sub3());

        org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec top = cl.getCommandSpec();
        report("parent", top);
        for (String k : top.subcommands().keySet())
        {
            report(k, top.subcommands().get(k).getCommandSpec());
        }
    }

    private static void report(String label,
            org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec spec)
    {
        int help = 0;
        for (org.junit.platform.console.shadow.picocli.CommandLine.Model.OptionSpec o : spec.options())
        {
            if (o.usageHelp())
            {
                help += 1;
            }
        }
        System.out.println("spec " + label + " options=" + spec.options().size()
                + " map=" + spec.optionsMap().size() + " usageHelp=" + help
                + ((help <= 1) ? "  OK" : "  <== DUPLICATED"));
    }

    private static void realElements() throws Exception
    {
        Class<?> base = Class.forName("org.junit.platform.console.command.BaseCommand");
        java.lang.reflect.Field[] fs = base.getDeclaredFields();
        int i = 0;
        while (i < fs.length)
        {
            org.junit.platform.console.shadow.picocli.CommandLine.Option o =
                    fs[i].getAnnotation(org.junit.platform.console.shadow.picocli.CommandLine.Option.class);
            if (o != null)
            {
                System.out.println("opt " + fs[i].getName()
                        + " names0=" + o.names()[0]
                        + " usageHelp=" + o.usageHelp()
                        + " versionHelp=" + o.versionHelp()
                        + " required=" + o.required()
                        + " hidden=" + o.hidden()
                        + " scope=" + o.scope());
            }
            i += 1;
        }

        String[] cmds = {
            "org.junit.platform.console.command.MainCommand",
            "org.junit.platform.console.command.ExecuteTestsCommand"
        };
        int k = 0;
        while (k < cmds.length)
        {
            Class<?> c = Class.forName(cmds[k]);
            org.junit.platform.console.shadow.picocli.CommandLine.Command cm =
                    c.getAnnotation(org.junit.platform.console.shadow.picocli.CommandLine.Command.class);
            if (cm == null)
            {
                System.out.println("cmd " + c.getName() + " -> NO @Command");
            }
            else
            {
                System.out.println("cmd " + c.getName()
                        + " name=" + cm.name()
                        + " mixinStd=" + cm.mixinStandardHelpOptions()
                        + " scope=" + cm.scope()
                        + " helpCommand=" + cm.helpCommand()
                        + " subs=" + cm.subcommands().length);
            }
            k += 1;
        }
    }

    private static void realHierarchy() throws Exception
    {
        String[] names = {
            "org.junit.platform.console.command.ExecuteTestsCommand",
            "org.junit.platform.console.command.MainCommand",
            "org.junit.platform.console.command.DiscoverTestsCommand",
            "org.junit.platform.console.command.ListTestEnginesCommand"
        };
        int n = 0;
        while (n < names.length)
        {
            walkOne(names[n]);
            n += 1;
        }
    }

    private static void walkOne(String cn) throws Exception
    {
        Class<?> c;
        try
        {
            c = Class.forName(cn);
        }
        catch (Throwable t)
        {
            System.out.println("walk " + cn + " -> NOT LOADABLE: " + t);
            return;
        }
        System.out.println("walk " + cn);
        int hops = 0;
        int helps = 0;
        while (c != null && hops < 16)
        {
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            int hereHelp = 0;
            int i = 0;
            while (i < fs.length)
            {
                org.junit.platform.console.shadow.picocli.CommandLine.Option o =
                        fs[i].getAnnotation(org.junit.platform.console.shadow.picocli.CommandLine.Option.class);
                if (o != null)
                {
                    String[] nm = o.names();
                    int j = 0;
                    while (j < nm.length)
                    {
                        if ("--help".equals(nm[j]))
                        {
                            hereHelp += 1;
                        }
                        j += 1;
                    }
                }
                i += 1;
            }
            helps += hereHelp;
            System.out.println("  [" + hops + "] " + c.getName() + " fields=" + fs.length + " help=" + hereHelp);
            c = c.getSuperclass();
            hops += 1;
        }
        System.out.println("  hops=" + hops + " helpTotal=" + helps + (helps == 1 ? "  OK" : "  <== WRONG"));
    }

    /** Stands in for picocli's own AutoHelpMixin: same two options, same flags. */
    public static class AutoHelpLike
    {
        @org.junit.platform.console.shadow.picocli.CommandLine.Option(
                names = { "-h", "--help" }, usageHelp = true)
        boolean helpRequested;

        @org.junit.platform.console.shadow.picocli.CommandLine.Option(
                names = { "-V", "--version" }, versionHelp = true)
        boolean versionRequested;
    }
}
