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
