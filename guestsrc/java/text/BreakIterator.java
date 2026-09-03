package java.text;

/**
 * A JDK-free {@code java.text.BreakIterator} providing LINE-BREAK opportunities: the positions where text may
 * be wrapped. picocli uses exactly four methods of it -- {@code getLineInstance}, {@code setText},
 * {@code first}, {@code next} -- to word-wrap help and error output.
 *
 * <p>Overlaid rather than left to stock because stock's factory goes through
 * {@code java.text.spi.BreakIteratorProvider}, and {@code java/text/spi/} is DENYLISTED: the SPI lookup pulls
 * the locale-provider machinery this VM deliberately does not carry.
 *
 * <p><b>Whitespace and hyphen boundaries only, and that is a real limit.</b> Stock line breaking follows the
 * Unicode line-breaking algorithm with locale tailoring; joe-ng has none of those tables, so text in a script
 * that does not delimit words with spaces will not wrap where a reader expects. For ASCII -- every caller
 * reached here -- the two agree. Stated rather than hidden, like the {@link Character} classification limits.
 *
 * <p>Boundary semantics match stock closely enough for wrapping: {@code first()} is 0, each {@code next()}
 * returns the position AFTER the next break opportunity (so a segment carries its own trailing whitespace),
 * the final boundary is the text length, and the call after that returns {@link #DONE}.
 */
public class BreakIterator
{
    public static final int DONE = -1;

    private String text = "";
    private int pos;

    protected BreakIterator()
    {
    }

    public static BreakIterator getLineInstance()
    {
        return new BreakIterator();
    }

    public static BreakIterator getLineInstance(java.util.Locale locale)
    {
        return new BreakIterator();                     // no locale tailoring: see the class note
    }

    public static BreakIterator getWordInstance()
    {
        return new BreakIterator();
    }

    public static BreakIterator getWordInstance(java.util.Locale locale)
    {
        return new BreakIterator();
    }

    public void setText(String newText)
    {
        this.text = newText == null ? "" : newText;
        this.pos = 0;
    }

    public int first()
    {
        pos = 0;
        return pos;
    }

    public int last()
    {
        pos = text.length();
        return pos;
    }

    public int current()
    {
        return pos;
    }

    /**
     * The position after the next break opportunity, or {@link #DONE} past the end.
     *
     * <p>Trailing spaces are absorbed into the segment that precedes them: a wrapper measures a segment and
     * decides whether it still fits, and a segment that excluded its own spaces would make the caller
     * re-derive them. That is what stock does too.
     */
    public int next()
    {
        int n = text.length();
        if (pos >= n)
        {
            return DONE;
        }
        int i = pos;
        while (i < n)
        {
            char c = text.charAt(i);
            i++;
            if (c == ' ' || c == '\t' || c == '\n' || c == '-')
            {
                while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t'))
                {
                    i++;                                // absorb the whitespace run into this segment
                }
                break;
            }
        }
        pos = i;
        return pos;
    }

    public int next(int count)
    {
        int r = pos;
        for (int k = 0; k < count; k++)
        {
            r = next();
            if (r == DONE)
            {
                return DONE;
            }
        }
        return r;
    }

    public int following(int offset)
    {
        pos = offset < 0 ? 0 : offset;
        return next();
    }

    public String getText()
    {
        return text;
    }
}
