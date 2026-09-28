/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-30
 */
/**
 * Exercises String.format / String.formatted directly, in the shapes JUnit's AssertionFailureBuilder uses,
 * so a failure there can be told apart from a failure in JUnit's own message plumbing.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected strings:
 * compiled against the real JDK it reaches stock {@code String.format}, compiled against {@code guestsrc} it
 * reaches joe-ng's {@code java/util/Formatter}, and every LEGAL arm must print the same bytes in both worlds.
 * That is deliberate rather than terse -- this project has mis-read an arm whose expected value sat on the
 * same line as its answer twice, once as passing and once as failing, and an oracle that cannot be typed
 * wrong removes the whole failure mode.
 *
 * <p>FIVE arms are the STATED DIVERGENCE and are the only lines expected to differ -- the four under
 * "illegal", where stock throws an {@code IllegalFormatException} subclass and joe-ng formats instead (see
 * the {@code Formatter} javadoc for why those classes are not pulled into the printing path), plus
 * {@code %q}, an unknown conversion, which stock throws on and this emits verbatim. {@code %q} sits up in the
 * first group rather than with the other four because it long predates them; it is named here because an
 * unlabelled difference in a diff is indistinguishable from a regression, and a count of "four" against five
 * differing lines would send the next reader looking for a fifth bug.
 */
public class FormatProbe
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("format probe:");
        show("plain", "no conversions");
        show("one %s", "A");
        show("expected: <%s> but was: <%s>", "2", "3");
        show("expected: %s but was: %s", "x", "y");
        show("%d and %d", Integer.valueOf(7), Long.valueOf(8L));
        show("%x %o %c %b", Integer.valueOf(255), Integer.valueOf(8), Character.valueOf('Z'), Boolean.TRUE);
        show("null arg <%s>", (Object) null);
        show("100%% done", new Object[0]);
        show("%-10s|", "pad");
        show("%q unknown", "arg");

        // WIDTH. The negative arms are what separate a correct pad from one that runs before the sign
        // (which would give 000-42 and still look like a padded number), and %2d of 12345 is here because a
        // width SHORTER than the value must not truncate.
        System.out.println("  -- width --");
        show("%5d|", Integer.valueOf(42));
        show("%-5d|", Integer.valueOf(42));
        show("%05d|", Integer.valueOf(42));
        show("%5d|", Integer.valueOf(-42));
        show("%-5d|", Integer.valueOf(-42));
        show("%05d|", Integer.valueOf(-42));
        show("%2d|", Integer.valueOf(12345));
        show("%5s|", "ab");
        show("%-5s|", "ab");
        show("%5c|", Character.valueOf('Z'));
        show("%5b|", Boolean.TRUE);
        show("%5s|", (Object) null);
        show("[%5d][%-5s]", Integer.valueOf(7), "q");
        show("%5%|", new Object[0]);

        // PRECISION truncates a string-like conversion. %8.3s exercises both at once -- an implementation
        // that honoured only one of the two passes every single-mechanism arm above.
        System.out.println("  -- precision --");
        show("%.2s|", "abcdef");
        show("%.0s|", "abcdef");
        show("%8.3s|", "abcdef");
        show("%-8.3s|", "abcdef");
        show("%.2b|", Boolean.TRUE);

        // SIGN AND GROUPING FLAGS. %+,09d is the discriminating one: grouping runs before padding and the
        // pad zeros are NOT themselves grouped, so the answer is +0012,345 and not +00,012,345.
        System.out.println("  -- sign and grouping --");
        show("%+d|", Integer.valueOf(42));
        show("%+d|", Integer.valueOf(-42));
        show("% d|", Integer.valueOf(42));
        show("% d|", Integer.valueOf(-42));
        show("%(d|", Integer.valueOf(-42));
        show("%(d|", Integer.valueOf(42));
        show("%,d|", Integer.valueOf(12345678));
        show("%,d|", Integer.valueOf(-12345678));
        show("%,d|", Integer.valueOf(123));
        show("%+,09d|", Integer.valueOf(12345));
        show("%d|", Long.valueOf(Long.MIN_VALUE));
        show("%,d|", Long.valueOf(Long.MIN_VALUE));

        // ALTERNATE FORM, and the zero pad going behind the 0x rather than in front of it.
        System.out.println("  -- radix and alternate form --");
        show("%#x|", Integer.valueOf(255));
        show("%#X|", Integer.valueOf(255));
        show("%08x|", Integer.valueOf(255));
        show("%-8x|", Integer.valueOf(255));
        show("%#010x|", Integer.valueOf(255));
        show("%#o|", Integer.valueOf(8));

        // THE ARGUMENT'S OWN WIDTH. %x/%X/%o are UNSIGNED, so -1 answers a different number of digits for
        // each box width; widening every argument to long -- which this VM used to do -- is wrong for all
        // three of the narrow ones and right only for Long.
        System.out.println("  -- unsigned at the argument's width --");
        show("%x|", Integer.valueOf(-1));
        show("%x|", Long.valueOf(-1L));
        show("%x|", Short.valueOf((short) -1));
        show("%x|", Byte.valueOf((byte) -1));
        show("%o|", Integer.valueOf(-1));
        show("%X|", Integer.valueOf(-255));

        // ILLEGAL: stock THROWS, joe-ng formats. The one stated divergence, and the only lines a
        // host-vs-metal diff is expected to show.
        System.out.println("  -- illegal in stock: the stated divergence --");
        show("%.2d|", Integer.valueOf(42));
        show("%#d|", Integer.valueOf(42));
        show("%+x|", Integer.valueOf(255));
        show("%-05d|", Integer.valueOf(42));

        // the exact call formatValues makes
        String v = "expected: <%s> but was: <%s>".formatted("2", "3");
        System.out.println("  formatted() = [" + v + "]");
        System.out.println("  length      = " + v.length());
        // The PrintWriter path, which is a DIFFERENT construction from String.format: stock builds
        // `new Formatter(this)` and expects the formatter to write THROUGH to the writer rather than
        // accumulate. An overlay missing that constructor traps; one that accumulates instead of writing
        // prints NOTHING and looks like a working call -- so these arms check the TEXT ARRIVED, not that the
        // call returned.
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        pw.printf("pw %s=%d", "n", 7);
        pw.flush();
        System.out.println("  printf -> [" + sw.toString() + "] (want [pw n=7])");

        // Called TWICE on the same writer: PrintWriter caches its Formatter, so a flush that failed to reset
        // would repeat the first call's text here and a one-shot arm would not see it.
        java.io.StringWriter sw2 = new java.io.StringWriter();
        java.io.PrintWriter pw2 = new java.io.PrintWriter(sw2);
        pw2.printf("a%d", 1);
        pw2.printf("b%d", 2);
        pw2.flush();
        System.out.println("  printf x2 -> [" + sw2.toString() + "] (want [a1b2])");

        // String.format must still accumulate -- the sink-less path, unchanged.
        System.out.println("  format still ok = [" + String.format("%s-%d", "x", 9) + "] (want [x-9])");

        System.out.println("survived");
    }

    private static void show(String fmt, Object... args)
    {
        String out;
        try
        {
            out = String.format(fmt, args);
        }
        catch (Throwable t)
        {
            out = "THREW " + t.getClass().getName();
        }
        System.out.println("  [" + fmt + "] -> [" + out + "]");
    }
}
