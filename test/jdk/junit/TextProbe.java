import org.junit.platform.console.shadow.picocli.CommandLine;

/**
 * picocli's {@code Ansi.Text} arithmetic, isolated from the table layout.
 *
 * <p>{@code UsageProbe} showed the failure exactly: {@code plain.substring(33, 42)} on a builder whose
 * {@code count} is 39 -- picocli's shared {@code plain} is THREE characters shorter than the {@code Text}
 * that indexes into it believes. Text keeps {@code from}/{@code length} beside a StringBuilder that
 * {@code clone()} SHARES between instances, so the two can only disagree if one of the StringBuilder
 * operations Text uses moved fewer characters than it was asked to.
 *
 * <p>Every arm prints what it produced AND what it should be, so this runs identically on the host: the host
 * is the control, and a difference names the operation rather than the symptom.
 */
public class TextProbe
{
    public static void main(String[] args)
    {
        CommandLine.Help.Ansi ansi = CommandLine.Help.Ansi.OFF;

        String base = "Usage: probe [-h=<h>] [-l=<l>] [-s=<s>]";     // 39 chars: the synopsis that failed
        CommandLine.Help.Ansi.Text t = ansi.new Text(base);
        System.out.println("base length      = " + base.length() + " (want 39)");
        System.out.println("text plain len   = " + t.plainString().length() + " (want 39)");
        System.out.println("text CJK len     = " + t.getCJKAdjustedLength() + " (want 39)");

        // substring: the operation whose result was indexed out of range.
        CommandLine.Help.Ansi.Text mid = t.substring(33, 39);
        System.out.println("substring plain  = [" + mid.plainString() + "] (want [<s>])");
        System.out.println("substring CJK    = " + mid.getCJKAdjustedLength() + " (want 6)");

        // append/concat: these REBUILD plain, and are where a short copy would show.
        CommandLine.Help.Ansi.Text app = t.append("XYZ");
        System.out.println("append plain len = " + app.plainString().length() + " (want 42)");
        System.out.println("append CJK       = " + app.getCJKAdjustedLength() + " (want 42)");
        System.out.println("append tail      = [" + app.plainString().substring(39) + "] (want [XYZ])");

        CommandLine.Help.Ansi.Text cat = t.concat(ansi.new Text("ABC"));
        System.out.println("concat plain len = " + cat.plainString().length() + " (want 42)");
        System.out.println("concat CJK       = " + cat.getCJKAdjustedLength() + " (want 42)");

        // getStyledChars is how TextTable fills a column value: it writes INTO a destination Text.
        CommandLine.Help.Ansi.Text dest = ansi.new Text(80);
        t.getStyledChars(0, 39, dest, 0);
        System.out.println("styled dest len  = " + dest.plainString().length() + " (want >= 39)");
        System.out.println("styled dest head = [" + dest.plainString().substring(0, 6) + "] (want [Usage:])");

        // THE ROOT: a STRING LITERAL that is not ASCII. A classfile stores it as modified UTF-8, so U+00FF is
        // two bytes (C3 BF) -- taken verbatim as LATIN1 that is a two-character String. Everything below is
        // downstream of this one fact.
        System.out.println("lit u00ff len    = " + "\u00ff".length() + " (want 1)");
        System.out.println("lit u00ff char0  = " + (int) "\u00ff".charAt(0) + " (want 255)");
        System.out.println("lit eacute len   = " + "\u00e9".length() + " (want 1)");
        System.out.println("lit euro len     = " + "\u20ac".length() + " (want 1)");
        System.out.println("lit ascii len    = " + "abc".length() + " (want 3)");

        // THE CONDITION the arms above do NOT reproduce: TextTable.copy() word-wraps by handing
        // `text.plainString().replace("-", "\u00ff")` to a BreakIterator and then slicing the TEXT with the
        // boundaries it returns. Every boundary is an index into the REPLACED string, so if that string's
        // length differs from the text's by even one, a boundary can run past the end of the Text -- which is
        // exactly the failure: from=33, length=9, plain=39.
        String hy = "well-known-hyphenated-words like alpha-beta-gamma-delta";
        String rep = hy.replace("-", "\u00ff");
        System.out.println("replace srcLen   = " + hy.length());
        System.out.println("replace dstLen   = " + rep.length() + " (want the same)");
        System.out.println("replace sameLen  = " + (hy.length() == rep.length() ? 1 : 0) + " (want 1)");

        // And the loop itself, over a real Text: no boundary may exceed the text length.
        CommandLine.Help.Ansi.Text ht = ansi.new Text(hy);
        String plain = ht.plainString();
        String forBreaks = plain.replace("-", "\u00ff");
        System.out.println("loop plainLen    = " + plain.length());
        System.out.println("loop breakLen    = " + forBreaks.length() + " (want the same)");
        java.text.BreakIterator bi = java.text.BreakIterator.getLineInstance();
        bi.setText(forBreaks);
        int worst = -1;
        int n = 0;
        for (int st = bi.first(), en = bi.next(); en != java.text.BreakIterator.DONE; st = en, en = bi.next())
        {
            n += 1;
            if (en > worst)
            {
                worst = en;
            }
        }
        System.out.println("loop segments    = " + n);
        System.out.println("loop maxBoundary = " + worst + " (want <= " + plain.length() + ")");
        System.out.println("loop inBounds    = " + (worst <= plain.length() ? 1 : 0) + " (want 1)");

        System.out.println("TextProbe done");
    }
}
