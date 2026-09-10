import org.junit.platform.console.shadow.picocli.CommandLine;
import org.junit.platform.console.shadow.picocli.CommandLine.Command;
import org.junit.platform.console.shadow.picocli.CommandLine.Option;

/**
 * picocli's USAGE RENDERING, which is where the console launcher now stops.
 *
 * <p>The launcher dies in {@code Text.getCJKAdjustedLength}, whose one line is
 * {@code plain.substring(from, from + length)} -- thrown with {@code start = 22}, so picocli's {@code Text}
 * believes its shared {@code plain} buffer is longer than it is. Reaching that through the launcher costs a
 * ~12 minute boot; this reaches the same code ({@code Help.Layout.layout} -> {@code TextTable.putValue} ->
 * {@code copy(BreakIterator, ...)} -> {@code Text.substring}) in a couple of minutes.
 *
 * <p>The descriptions are deliberately LONGER than the column, because the failing path is the WRAP branch --
 * a description that fits is copied whole and never consults the BreakIterator at all. One option also
 * carries hyphens, since picocli rewrites '-' before handing the text to the iterator.
 */
public class UsageProbe
{
    @Command(name = "probe", description = "A probe command whose description is long enough to wrap.")
    static class Cmd
    {
        @Option(names = {"-s", "--short"}, description = "short one")
        String s;

        @Option(names = {"-l", "--long-option-name"},
                description = "A deliberately long description that must be word-wrapped across several "
                        + "lines so that the wrap path runs rather than the copy-whole fast path.")
        String l;

        @Option(names = {"-h", "--hyphen-laden"},
                description = "well-known-hyphenated-words like alpha-beta-gamma-delta exercise the '-' "
                        + "rewrite picocli performs before it consults the break iterator.")
        String h;
    }

    public static void main(String[] args)
    {
        // The properties picocli reads UNCONDITIONALLY. A null here is not a rendering bug at all -- it is an
        // NPE inside the library, and the two failures it produces (isWindows and trimLineSeparator) look
        // unrelated while sharing one cause.
        System.out.println("prop os.name        = " + System.getProperty("os.name") + " (want joe-ng)");
        System.out.println("prop line.separator = "
                + (System.getProperty("line.separator") == null ? "NULL <== BUG" : "non-null") + " (want non-null)");
        System.out.println("System.lineSeparator= "
                + (System.lineSeparator() == null ? "NULL <== BUG" : "non-null") + " (want non-null)");
        System.out.println("prop absent         = "
                + (System.getProperty("no.such.prop") == null ? "null" : "NON-NULL <== BUG") + " (want null)");

        System.out.println("-- usage begins --");
        CommandLine cl = new CommandLine(new Cmd());
        // Ansi.OFF, because that is the launcher's CONDITION: it is invoked with --disable-ansi-colors, so it
        // never reaches Ansi.enabled(). Rendering with ansi AUTO instead introduces a DIFFERENT failure --
        // Ansi.isWindows() NPEs here -- and a probe that stops on a bug the target never hits proves nothing
        // about the target.
        try
        {
            cl.usage(System.out, CommandLine.Help.Ansi.OFF);
            System.out.println("-- usage ends --");
        }
        catch (Throwable t)
        {
            // Expected until non-ASCII string LITERALS are decoded from modified UTF-8: picocli word-wraps by
            // handing `plainString().replace("-", "\u00ff")` to a BreakIterator, and on joe-ng that
            // replacement string is TWO characters, so every boundary past a hyphen is shifted right and the
            // Text is sliced past the end of its own buffer. See TextProbe, which measures the cause directly.
            System.out.println("usage threw          = " + t.getClass().getName());
        }
        System.out.println("UsageProbe done");
    }
}
