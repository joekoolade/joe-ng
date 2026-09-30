/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-29
 */
import java.util.Arrays;
import java.util.Comparator;

/**
 * Exercises {@code java.util.Comparator}'s whole surface -- the shared singletons, the {@code reversed}/
 * {@code thenComparing} combinators, the {@code comparing*} factories, {@code max}/{@code min}, and the
 * null-friendly wrappers.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values:
 * run on a host JVM the arms reach stock {@code Comparator}, run on the metal they reach whatever
 * {@code java.util.Comparator} this VM carries, and every arm must print the same bytes in both worlds.
 *
 * <p>THE CONTROL IS THE OVERLAY RESTORED, and unlike {@code CollectionsProbe} one source does NOT compile
 * against both worlds: the retired overlay declared ten of stock's eighteen members, so the arms naming
 * {@code max}, {@code min}, {@code thenComparingLong}, {@code thenComparingDouble}, {@code comparingLong},
 * {@code comparingDouble}, {@code nullsFirst} and {@code nullsLast} do not exist for javac to bind. That is
 * itself the overlay-drops-stock-members finding, stated as a compile error rather than inferred -- and it is
 * why the control is reported as arms WRONG plus arms that would not COMPILE.
 *
 * <p>THE IDENTITY ARMS ARE WHAT THE OVERLAY GOT WRONG WHILE LOOKING RIGHT. Stock's {@code naturalOrder()} is
 * the shared enum constant {@code Comparators.NaturalOrderComparator.INSTANCE} and its {@code reverseOrder()}
 * is {@code Collections.ReverseComparator.REVERSE_ORDER}, so each answers the SAME OBJECT every call; the
 * overlay returned a FRESH LAMBDA per call, so {@code naturalOrder() == naturalOrder()} was false where stock
 * says true. That is the {@code emptyList() == emptyList()} shape the {@code Collections} deletion found, and
 * three more identities come with it, each written into stock as an override rather than falling out of the
 * default: {@code naturalOrder().reversed()} IS {@code reverseOrder()}, {@code reverseOrder().reversed()} IS
 * {@code naturalOrder()}, and for any other comparator {@code c.reversed().reversed()} IS {@code c} (stock's
 * {@code ReverseComparator2.reversed()} hands back the comparator it wraps). A fresh lambda cannot satisfy any
 * of the four.
 *
 * <p>AND ONE ARM WHERE A DROPPED OVERRIDE CHANGES AN ANSWER RATHER THAN AN IDENTITY.
 * {@code Comparators.NullComparator} overrides BOTH {@code reversed()} and {@code thenComparing(Comparator)}
 * to preserve its null handling: reversing flips the null PLACEMENT as well as the element order, and chaining
 * re-wraps so the chain still tolerates a null. An implementation that inherited the plain defaults would
 * reverse the elements while leaving nulls first, and would hand a null to the chained comparator.
 *
 * <p>THE ORDERING ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in both states is not a
 * control: {@code compare} through {@code naturalOrder}/{@code reverseOrder}/{@code comparingInt}/
 * {@code comparing}/{@code thenComparing*} was correct under the overlay -- those are what its javadoc was
 * written for -- so a change that broke them shows here rather than in the identity arms.
 *
 * <p>THE {@code Integer.MIN_VALUE} ARM IS A CONTROL AND ITS PREMISE WAS NEVER TRUE OF STOCK. The overlay's own
 * comment defended {@code reversed()} as {@code compare(b, a)} "rather than negating the result: negation is
 * WRONG for a comparator that returns Integer.MIN_VALUE, whose negation is itself". Stock's
 * {@code ReverseComparator2.compare} is also {@code cmp.compare(t2, t1)} and negates nothing, so the comment
 * was arguing with something stock never did. The arm is kept because it passes in BOTH states and says so.
 *
 * <p>SIGNS, NOT DIFFERENCES. {@code compare} is specified by its SIGN, and a {@code String.compareTo} result
 * is a character difference that an implementation is free to change; printing {@code lt}/{@code eq}/{@code gt}
 * asserts the contract instead of an incidental magnitude. The TIE arms are the exception and they print an
 * IDENTITY: stock's {@code max}/{@code min} are {@code >= 0} / {@code <= 0}, so both return the FIRST argument
 * when the two compare equal, where the obvious {@code >} / {@code <} returns the second -- a wrong answer that
 * no value comparison can see, since the two elements are equal by construction.
 *
 * <p>NO NESTED OR ANONYMOUS CLASSES. The key extractors are method references to private statics, which javac
 * lowers to {@code invokedynamic} plus a synthetic method ON THIS CLASS -- so the probe is still exactly one
 * class file, and the default package's lack of a {@code demandLoadable} prefix cannot hide an arm.
 */
public class CmpProbe
{
    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = " + value);
    }

    private static String sign(int v)
    {
        return v < 0 ? "lt" : (v > 0 ? "gt" : "eq");
    }

    private static String bool(boolean b)
    {
        return b ? "1" : "0";
    }

    private static String join(String[] a)
    {
        String out = "";
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0)
            {
                out = out + ",";
            }
            out = out + a[i];
        }
        return out;
    }

    private static int len(String s)
    {
        return s.length();
    }

    private static long lenLong(String s)
    {
        return s.length();
    }

    private static double lenDouble(String s)
    {
        return s.length();
    }

    private static String head(String s)
    {
        return s.substring(0, 1);
    }

    public static void main(String[] args)
    {
        Comparator<String> nat = Comparator.naturalOrder();
        Comparator<String> rev = Comparator.reverseOrder();
        Comparator<String> byLen = Comparator.comparingInt(CmpProbe::len);
        Comparator<String> byHead = Comparator.comparing(CmpProbe::head);

        System.out.println("-- identity: stock's singletons and its reversed() overrides --");
        say("naturalOrder() == naturalOrder()",
                bool(Comparator.<String>naturalOrder() == Comparator.<String>naturalOrder()));
        say("reverseOrder() == reverseOrder()",
                bool(Comparator.<String>reverseOrder() == Comparator.<String>reverseOrder()));
        say("naturalOrder() == reverseOrder()", bool(nat == rev));
        say("naturalOrder().reversed() == reverseOrder()", bool(nat.reversed() == rev));
        say("reverseOrder().reversed() == naturalOrder()", bool(rev.reversed() == nat));
        say("byLen.reversed().reversed() == byLen", bool(byLen.reversed().reversed() == byLen));

        System.out.println("-- ordering: the built-in comparison --");
        say("nat b,a", sign(nat.compare("b", "a")));
        say("nat a,b", sign(nat.compare("a", "b")));
        say("nat a,a", sign(nat.compare("a", "a")));
        say("rev b,a", sign(rev.compare("b", "a")));
        say("rev a,b", sign(rev.compare("a", "b")));
        say("byLen xx,y", sign(byLen.compare("xx", "y")));
        say("byLen y,xx", sign(byLen.compare("y", "xx")));
        say("byLen xx,yy", sign(byLen.compare("xx", "yy")));
        say("byHead ax,by", sign(byHead.compare("ax", "by")));
        say("comparing(head,rev) ax,by",
                sign(Comparator.comparing(CmpProbe::head, rev).compare("ax", "by")));
        say("byLen.reversed() xx,y", sign(byLen.reversed().compare("xx", "y")));

        System.out.println("-- thenComparing: the tie-break chain --");
        Comparator<String> lenThenNat = byLen.thenComparing(nat);
        say("len,nat bb,aa", sign(lenThenNat.compare("bb", "aa")));
        say("len,nat aa,bb", sign(lenThenNat.compare("aa", "bb")));
        say("len,nat a,bb", sign(lenThenNat.compare("a", "bb")));
        say("len,then head bb,ba", sign(byLen.thenComparing(CmpProbe::head).compare("bb", "ba")));
        say("len,then head+rev ab,bb",
                sign(byLen.thenComparing(CmpProbe::head, rev).compare("ab", "bb")));
        say("head,thenInt len ab,abc", sign(byHead.thenComparingInt(CmpProbe::len).compare("ab", "abc")));

        System.out.println("-- stock-only: the widths the overlay dropped --");
        say("comparingLong xx,y", sign(Comparator.comparingLong(CmpProbe::lenLong).compare("xx", "y")));
        say("comparingDouble xx,y",
                sign(Comparator.comparingDouble(CmpProbe::lenDouble).compare("xx", "y")));
        say("head,thenLong ab,abc",
                sign(byHead.thenComparingLong(CmpProbe::lenLong).compare("ab", "abc")));
        say("head,thenDouble ab,abc",
                sign(byHead.thenComparingDouble(CmpProbe::lenDouble).compare("ab", "abc")));

        System.out.println("-- stock-only: max/min, and the TIE returns the FIRST argument --");
        say("nat.max(a,b)", nat.max("a", "b"));
        say("nat.min(a,b)", nat.min("a", "b"));
        say("rev.max(a,b)", rev.max("a", "b"));
        String t1 = new String("x");
        String t2 = new String("x");
        say("max tie is first arg", bool(nat.max(t1, t2) == t1));
        say("min tie is first arg", bool(nat.min(t1, t2) == t1));

        System.out.println("-- stock-only: the null-friendly wrappers --");
        Comparator<String> nf = Comparator.nullsFirst(nat);
        Comparator<String> nl = Comparator.nullsLast(nat);
        say("nullsFirst null,a", sign(nf.compare(null, "a")));
        say("nullsFirst a,null", sign(nf.compare("a", null)));
        say("nullsFirst null,null", sign(nf.compare(null, null)));
        say("nullsFirst b,a", sign(nf.compare("b", "a")));
        say("nullsLast null,a", sign(nl.compare(null, "a")));
        say("nullsLast a,null", sign(nl.compare("a", null)));
        say("nullsLast null,null", sign(nl.compare(null, null)));
        say("nullsLast b,a", sign(nl.compare("b", "a")));
        say("nullsFirst(null) b,a", sign(Comparator.<String>nullsFirst(null).compare("b", "a")));
        say("nullsFirst(null) null,a", sign(Comparator.<String>nullsFirst(null).compare(null, "a")));

        System.out.println("-- stock-only: NullComparator's own reversed/thenComparing overrides --");
        Comparator<String> nfr = nf.reversed();
        say("nullsFirst.reversed null,a", sign(nfr.compare(null, "a")));
        say("nullsFirst.reversed b,a", sign(nfr.compare("b", "a")));
        Comparator<String> nfThen = nf.thenComparing(byLen);
        say("nullsFirst.then null,a", sign(nfThen.compare(null, "a")));
        say("nullsFirst.then aa,b", sign(nfThen.compare("aa", "b")));

        System.out.println("-- Integer.MIN_VALUE: correct in BOTH states, so a control --");
        Comparator<String> minv = (a, b) -> a.equals(b) ? 0
                : (a.compareTo(b) < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE);
        say("minValue a,b", sign(minv.compare("a", "b")));
        say("minValue reversed a,b", sign(minv.reversed().compare("a", "b")));
        say("minValue a,a", sign(minv.compare("a", "a")));

        System.out.println("-- end to end: a real sort through the chain --");
        String[] arr = { "bb", "a", "cc", "b", "aa" };
        Arrays.sort(arr, lenThenNat);
        say("sort len,nat", join(arr));
        String[] withNulls = { "b", null, "a", null };
        Arrays.sort(withNulls, nf);
        say("sort nullsFirst", join(withNulls));

        System.out.println("CmpProbe done");
    }
}
