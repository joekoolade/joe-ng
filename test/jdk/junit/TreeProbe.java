package org.junit.platform.console.command;

import org.junit.platform.console.shadow.picocli.CommandLine;

/**
 * Builds the console launcher's ACTUAL command tree and parses its real argument list.
 *
 * <p>Every synthetic feature is eliminated: a bare command, options, a @Mixin, subcommands, inheritance, an
 * @ArgGroup and a 40-option command all parse correctly, and after the opSlot fix so does the real
 * ListTestEnginesCommand on its own. The launcher still fails -- so what remains is the TREE it assembles,
 * which MainCommand.run does as:
 *
 *     CommandLine cl = new CommandLine(new MainCommand(factory));
 *     cl.addSubcommand(new DiscoverTestsCommand(factory));
 *     cl.addSubcommand(new ExecuteTestsCommand(factory));
 *     cl.addSubcommand(new ListTestEnginesCommand());
 *
 * <p>Declared in the launcher's own package so those package-private classes and constructors can be used
 * directly -- no reflection, the same calls the launcher makes. The factory is null: it is only used when a
 * command EXECUTES, and parsing never touches it.
 *
 * <p>The arms add one subcommand at a time, so if the tree is what matters the failing member is read off
 * rather than guessed.
 */
public class TreeProbe
{
    public static void main(String[] args)
    {
        System.out.println("--- launcher command tree");
        arm("main alone           ", 0, new String[] { "--help" });
        arm("main + execute       ", 1, new String[] { "execute", "--select-class=SleepSanity" });
        arm("main + exec + list   ", 2, new String[] { "execute", "--select-class=SleepSanity" });
        arm("full tree, real args ", 3, new String[] {
                "execute", "--select-class=SleepSanity", "--disable-ansi-colors", "--disable-banner" });
        arm("full tree, --help    ", 3, new String[] { "--help" });
        System.out.println("--- stripped variants (all as a subcommand of MainCommand, parsing --help)");
        variant("v1 BaseCommand only  ", new V1());
        variant("v2 + discovery mixin ", new V2());
        variant("v3 + output mixin    ", new V3());
        variant("v4 + argGroup        ", new V4());
        sides();
    }

    /**
     * STRIPPED VARIANTS of ExecuteTestsCommand, adding one feature at a time.
     *
     * <p>`main + ExecuteTestsCommand` reproduces, while a synthetic subcommand with one option does not.
     * ExecuteTestsCommand differs by FOUR things at once: it extends BaseCommand, carries two @Mixin fields,
     * and carries an @ArgGroup. Each of those is already cleared in isolation (BannerProbe), so the question
     * is which one -- or which pair -- fails once attached as a SUBCOMMAND. These are real classes in the
     * real package, subclassing the real BaseCommand, so nothing about the shape is synthetic except what is
     * deliberately left out.
     */
    @CommandLine.Command(name = "v1")
    public static class V1 extends BaseCommand<Void>
    {
        protected Void execute(java.io.PrintWriter out)
        {
            return null;
        }
    }

    @CommandLine.Command(name = "v2")
    public static class V2 extends BaseCommand<Void>
    {
        @CommandLine.Mixin
        org.junit.platform.console.options.TestDiscoveryOptionsMixin discoveryOptions =
                new org.junit.platform.console.options.TestDiscoveryOptionsMixin();

        protected Void execute(java.io.PrintWriter out)
        {
            return null;
        }
    }

    @CommandLine.Command(name = "v3")
    public static class V3 extends BaseCommand<Void>
    {
        @CommandLine.Mixin
        org.junit.platform.console.options.TestConsoleOutputOptionsMixin testOutputOptions =
                new org.junit.platform.console.options.TestConsoleOutputOptionsMixin();

        protected Void execute(java.io.PrintWriter out)
        {
            return null;
        }
    }

    public static class Reporting
    {
        @CommandLine.Option(names = "--r1")
        boolean r1;
    }

    @CommandLine.Command(name = "v4")
    public static class V4 extends BaseCommand<Void>
    {
        @CommandLine.ArgGroup(validate = false, heading = "R%n")
        Reporting reportingOptions = new Reporting();

        protected Void execute(java.io.PrintWriter out)
        {
            return null;
        }
    }

    /** A plain top-level command, standing in for MainCommand. */
    @CommandLine.Command(name = "plaintop")
    public static class PlainTop
    {
    }

    /** A plain subcommand, standing in for a BaseCommand subclass. */
    @CommandLine.Command(name = "plainsub")
    public static class PlainSub
    {
        @CommandLine.Option(names = "--p")
        boolean p;
    }

    /**
     * SEPARATE THE TWO VARIABLES. `MainCommand + V1` fails and `PlainTop + PlainSub` passes, but those differ
     * on BOTH sides at once. Swapping one side at a time says which carries the fault: if the plain parent
     * with a BaseCommand child fails, it is the CHILD's shape (BaseCommand brings an @Spec field and a
     * generic Callable supertype, neither yet tested); if MainCommand with a plain child fails, it is the
     * PARENT's.
     */
    private static void sides()
    {
        System.out.println("--- which side carries it");
        try
        {
            CommandLine a = new CommandLine(new PlainTop());
            a.addSubcommand(new V1());
            a.parseArgs(new String[] { "--help" });
            System.out.println("  plain parent + BaseCommand child : OK");
        }
        catch (Throwable t)
        {
            Throwable r = t;
            while (r.getCause() != null) { r = r.getCause(); }
            System.out.println("  plain parent + BaseCommand child : THREW " + r.getClass().getName());
        }
        try
        {
            CommandLine b = new CommandLine(new MainCommand(null));
            b.addSubcommand(new PlainSub());
            b.parseArgs(new String[] { "--help" });
            System.out.println("  MainCommand  + plain child       : OK");
        }
        catch (Throwable t)
        {
            Throwable r = t;
            while (r.getCause() != null) { r = r.getCause(); }
            System.out.println("  MainCommand  + plain child       : THREW " + r.getClass().getName());
        }
    }

    /** Attach one stripped variant as a subcommand of MainCommand and parse. */
    private static void variant(String label, Object sub)
    {
        try
        {
            CommandLine cl = new CommandLine(new MainCommand(null));
            cl.addSubcommand(sub);
            cl.parseArgs(new String[] { "--help" });
            System.out.println("  " + label + ": OK");
        }
        catch (Throwable t)
        {
            Throwable r = t;
            while (r.getCause() != null) { r = r.getCause(); }
            System.out.println("  " + label + ": THREW " + r.getClass().getName());
        }
    }

    /** Build the tree with {@code subs} subcommands attached, then parse {@code argv}. */
    private static void arm(String label, int subs, String[] argv)
    {
        try
        {
            CommandLine cl = new CommandLine(new MainCommand(null));
            if (subs >= 1) { cl.addSubcommand(new ExecuteTestsCommand(null)); }
            if (subs >= 2) { cl.addSubcommand(new ListTestEnginesCommand()); }
            if (subs >= 3) { cl.addSubcommand(new DiscoverTestsCommand(null)); }
            cl.parseArgs(argv);
            System.out.println("  " + label + ": OK");
        }
        catch (Throwable t)
        {
            Throwable r = t;
            while (r.getCause() != null) { r = r.getCause(); }
            System.out.println("  " + label + ": THREW " + r.getClass().getName()
                    + (r.getMessage() == null ? "" : ": " + r.getMessage()));
        }
    }
}
