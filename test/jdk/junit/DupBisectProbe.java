/**
 * Bisects the launcher's {@code Multiple options [--help, --help, --help, --help]} warning into its two
 * possible halves, using the SMALLEST real command so the closure stays affordable on QEMU.
 *
 * <p>Arm A builds ONE spec from {@code ListTestEnginesCommand} (which extends BaseCommand, where the single
 * {@code usageHelp=true} --help is declared) with no parent and therefore no inheritance. Arm B adds it as a
 * subcommand of the real {@code MainCommand}, which is {@code @Command(scope = INHERIT)} and so copies its own
 * args down.
 *
 * <p>The two answers are mutually exclusive and each names the culprit outright: 4 in arm A means one spec's
 * own construction duplicates the option; 1 in arm A and 4 in arm B means the INHERIT copy is what repeats.
 */
public class DupBisectProbe
{
    public static void main(String[] args) throws Exception
    {
        Object engines = make("org.junit.platform.console.command.ListTestEnginesCommand");
        org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec solo =
                org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec
                        .forAnnotatedObject(engines);
        report("A: engines alone", solo);

        // Arm B is the real MainCommand's spec ALONE. It is cheap next to arm C and it answers a question
        // arm C cannot: MainCommand's own --help is @Option(help=true), NOT usageHelp, so its spec must
        // report usageHelp=0. Printed before arm C because arm C needs the whole subcommand closure and may
        // not finish -- partial output from a slow harness is worth more than an all-or-nothing run.
        Object main = make("org.junit.platform.console.command.MainCommand");
        report("B: junit alone", org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec
                .forAnnotatedObject(main));

        Object main2 = make("org.junit.platform.console.command.MainCommand");
        org.junit.platform.console.shadow.picocli.CommandLine cl =
                new org.junit.platform.console.shadow.picocli.CommandLine(main2)
                        .addSubcommand(make("org.junit.platform.console.command.ListTestEnginesCommand"));
        org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec top = cl.getCommandSpec();
        report("C: junit (parent)", top);
        for (String k : top.subcommands().keySet())
        {
            report("C: sub " + k, top.subcommands().get(k).getCommandSpec());
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
        System.out.println("BISECT " + label + " options=" + spec.options().size()
                + " map=" + spec.optionsMap().size() + " usageHelp=" + help
                + ((help <= 1) ? "  OK" : "  <== DUPLICATED"));
        // EVERY option is named, not only the duplicated ones: arm A finds one option fewer than the host,
        // and a count cannot say whether what went missing is an inherited option or the @Mixin's.
        for (org.junit.platform.console.shadow.picocli.CommandLine.Model.OptionSpec o : spec.options())
        {
            System.out.println("   o " + o.longestName() + " usageHelp=" + o.usageHelp()
                    + " inherited=" + o.inherited() + " scope=" + o.scopeType());
        }
    }
}
