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
 * <p>THERE IS NO LONGER ANY STATED DIVERGENCE, AND THAT IS THE POINT OF THIS FILE NOW. It used to record
 * FIVE arms that had to differ -- four illegal format strings that stock throws on and the overlay formatted,
 * plus {@code %q}. The overlay is DELETED and stock {@code java.util.Formatter} runs on the metal, so
 * **every arm must match byte for byte**, throws included: a {@code THREW} line must name the same exception
 * class in both worlds. An expected-divergence list is a place for a real regression to hide, and this file
 * no longer has one.
 *
 * <p>The float, {@code %h} and argument-index arms exist because they were UNREACHABLE before. The overlay
 * knew nine conversions and emitted anything else VERBATIM WHILE CONSUMING NO ARGUMENT -- so
 * {@code String.format("value=%.2f ok=%s", 3.14159, "yes")} answered {@code value=%.2f ok=3.14159}, silently
 * shifting every later argument by one. Those arms are the ones that discriminate; a fix that merely
 * recognised {@code %f} without consuming its argument would still fail them.
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

        // FLOATING POINT -- UNREACHABLE before this increment. `%f` defaults to precision 6, and 2.675 is
        // the arm that pins WHICH value gets rounded: the double nearest 2.675 is 2.674999999999999822...,
        // so rounding HALF_UP on the EXACT value gives 2.67 -- and stock answers **2.68**, because
        // jdk.internal.math.FormattedFPDecimal rounds the SHORTEST decimal that round-trips ("2.675") rather
        // than the exact one. I wrote 2.67 into this comment and the host oracle said otherwise; it is
        // recorded because it is precisely the rule a from-scratch implementation would get wrong while
        // looking careful, and a BigDecimal-exact one would get wrong while looking rigorous.
        System.out.println("  -- floating point --");
        show("%f|", Double.valueOf(1.5));
        show("%f|", Double.valueOf(-1.5));
        show("%.2f|", Double.valueOf(3.14159));
        show("%.0f|", Double.valueOf(2.5));
        show("%.2f|", Double.valueOf(2.675));
        show("%.3f|", Double.valueOf(0.0005));
        show("%10.2f|", Double.valueOf(3.14159));
        show("%-10.2f|", Double.valueOf(3.14159));
        show("%010.2f|", Double.valueOf(-3.14159));
        show("%+.2f|", Double.valueOf(3.14159));
        show("%,.2f|", Double.valueOf(1234567.891));
        show("%(.2f|", Double.valueOf(-1234.5));
        show("%f|", Double.valueOf(0.0));
        show("%f|", Double.valueOf(-0.0));
        show("%f|", Double.valueOf(Double.NaN));
        show("%f|", Double.valueOf(Double.POSITIVE_INFINITY));
        show("%f|", Double.valueOf(Double.NEGATIVE_INFINITY));
        show("%f|", Float.valueOf(1.5f));
        show("%e|", Double.valueOf(1234.5));
        show("%E|", Double.valueOf(1234.5));
        show("%.2e|", Double.valueOf(0.000123));
        show("%g|", Double.valueOf(1234.5));
        show("%g|", Double.valueOf(0.000012345));
        show("%a|", Double.valueOf(1.5));

        // THE ARGUMENT-SHIFT ARM, which is what the defect actually was: an unrecognised conversion consumed
        // no argument, so everything after it was off by one. One line, and it is the whole bug.
        System.out.println("  -- argument alignment across a float --");
        show("value=%.2f ok=%s", Double.valueOf(3.14159), "yes");
        show("%s %f %s|", "a", Double.valueOf(1.0), "b");

        // %h is hashCode-as-hex, and ARGUMENT INDEXES (%1$s) -- both unreachable before.
        System.out.println("  -- %h and argument indexes --");
        show("%h|", "abc");
        show("%h|", (Object) null);
        show("%1$s %1$s %2$s|", "x", "y");
        show("%2$s %1$s|", "first", "second");

        // ILLEGAL FORMAT STRINGS. These used to be the stated divergence -- stock throws four different
        // IllegalFormatException subclasses and the overlay formatted instead. With stock running they must
        // THROW HERE TOO, and `show` prints the exception's class name, so the diff checks WHICH exception.
        System.out.println("  -- illegal: must throw the SAME exception in both worlds --");
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
