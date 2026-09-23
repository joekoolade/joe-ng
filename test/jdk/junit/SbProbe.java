/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-23
 */

/*
 * The ten stock StringBuilder members this VM's overlay had DROPPED, asserted arm for arm.
 *
 * Run on a HOST the calls reach the JDK's own StringBuilder, so every expectation here is an independent
 * known answer; run on metal they reach the overlay. The two runs must print the same line -- which is the
 * whole point, and is why the arms pin VALUES rather than checking that nothing threw.
 *
 * The arms that discriminate, as opposed to merely exercising the API: a zero repeat count (legal, and an
 * implementation that rejected it would still pass every other repeat arm), a NEGATIVE count (stock throws,
 * and answering an empty run instead is a plausible wrong answer), `replace` with an end PAST the length
 * (stock clamps), `offsetByCodePoints` walking to exactly `count` (legal -- one past the last index), and
 * append(float), which must use the FLOAT formatter: this file records that Float.toString(0.1f) is "0.1"
 * where the widened double is "0.10000000149011612", so the last arm asserts the two DIFFER.
 */
/** Host control: joe-ng's StringBuilder overlay members vs the JDK's own, arm for arm. */
public class SbProbe
{
    static int checks;
    static int fails;

    static void eq(String what, String got, String want)
    {
        checks++;
        if (!got.equals(want)) { fails++; System.out.println("FAIL " + what + " got=[" + got + "] want=[" + want + "]"); }
    }

    interface Body { void run(); }

    static void throws_(String what, Class<?> exc, Body b)
    {
        checks++;
        try { b.run(); fails++; System.out.println("FAIL " + what + " did not throw"); }
        catch (Throwable t)
        {
            if (!exc.isInstance(t)) { fails++; System.out.println("FAIL " + what + " threw " + t.getClass().getName()); }
        }
    }

    public static void main(String[] a)
    {
        // repeat(int, int)
        eq("repeat('x',3)", new StringBuilder("a").repeat('x', 3).toString(), "axxx");
        eq("repeat('x',0)", new StringBuilder("a").repeat('x', 0).toString(), "a");
        eq("repeat('0',12)", new StringBuilder().repeat('0', 12).toString(), "000000000000");
        throws_("repeat('x',-1)", IllegalArgumentException.class, () -> new StringBuilder().repeat('x', -1));

        // repeat(CharSequence, int)
        eq("repeat(\"ab\",3)", new StringBuilder("-").repeat("ab", 3).toString(), "-ababab");
        eq("repeat(\"ab\",0)", new StringBuilder("-").repeat("ab", 0).toString(), "-");
        eq("repeat(null,2)", new StringBuilder().repeat(null, 2).toString(), "nullnull");
        throws_("repeat(cs,-1)", IllegalArgumentException.class, () -> new StringBuilder().repeat("a", -1));

        // replace
        eq("replace(1,3,\"XY\")", new StringBuilder("abcdef").replace(1, 3, "XY").toString(), "aXYdef");
        eq("replace(0,6,\"\")", new StringBuilder("abcdef").replace(0, 6, "").toString(), "");
        eq("replace(2,2,\"Z\")", new StringBuilder("abcdef").replace(2, 2, "Z").toString(), "abZcdef");
        eq("replace end past len", new StringBuilder("abc").replace(1, 99, "Q").toString(), "aQ");

        // setCharAt
        StringBuilder sc = new StringBuilder("abc");
        sc.setCharAt(1, 'Z');
        eq("setCharAt(1,'Z')", sc.toString(), "aZc");
        throws_("setCharAt(9,..)", StringIndexOutOfBoundsException.class, () -> new StringBuilder("abc").setCharAt(9, 'x'));

        // ensureCapacity -- advisory; only the CONTENT is contractual
        StringBuilder ec = new StringBuilder("ab");
        ec.ensureCapacity(500);
        ec.append("cd");
        eq("ensureCapacity keeps content", ec.toString(), "abcd");
        eq("ensureCapacity length", "" + ec.length(), "4");

        // offsetByCodePoints (ASCII: offset == index arithmetic)
        eq("offsetByCodePoints(1,2)", "" + new StringBuilder("abcdef").offsetByCodePoints(1, 2), "3");
        eq("offsetByCodePoints(3,-2)", "" + new StringBuilder("abcdef").offsetByCodePoints(3, -2), "1");
        eq("offsetByCodePoints(0,6)", "" + new StringBuilder("abcdef").offsetByCodePoints(0, 6), "6");
        throws_("offsetByCodePoints(0,9)", IndexOutOfBoundsException.class,
                () -> new StringBuilder("abc").offsetByCodePoints(0, 9));

        // insert overloads
        eq("insert(1,'Z')", new StringBuilder("abc").insert(1, 'Z').toString(), "aZbc");
        eq("insert(0,long)", new StringBuilder("abc").insert(0, 42L).toString(), "42abc");
        eq("insert(3,long neg)", new StringBuilder("abc").insert(3, -7L).toString(), "abc-7");
        eq("insert(1,char[])", new StringBuilder("abc").insert(1, new char[] { 'X', 'Y' }).toString(), "aXYbc");

        // append(float) -- shortest round-trip for the FLOAT type, not the widened double
        eq("append(0.1f)", new StringBuilder().append(0.1f).toString(), "0.1");
        eq("append(1.5f)", new StringBuilder().append(1.5f).toString(), "1.5");
        eq("float is not a widened double", "" + (Float.toString(0.1f).equals(Double.toString((double) 0.1f))), "false");

        // append(StringBuffer)
        eq("append(StringBuffer)", new StringBuilder("a").append(new StringBuffer("bc")).toString(), "abc");

        System.out.println("sb-probe: " + checks + " checks, " + fails + " failures");
    }
}
