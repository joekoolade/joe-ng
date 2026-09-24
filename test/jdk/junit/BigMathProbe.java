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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * {@code java.math} on the metal -- does BigInteger/BigDecimal work at all, and where does it stop?
 *
 * <p>This file records java.math as a landmine: BigInteger's initializer reaches
 * {@code ForkJoinPool.getCommonPoolParallelism()} through {@code BigInteger$RecursiveOp}, and its
 * {@code <clinit>} was REJECTED by the tag-7 gate so its statics read null for ever. **Both halves of that
 * premise expired with standing rule 2 (2026-09-12): every {@code <clinit>} runs now.** So the old
 * measurement describes a VM that no longer exists, and this probe measures the one that does.
 *
 * <p>STAGED DELIBERATELY: each arm prints before the next runs, so the first failure says how far the
 * subsystem got rather than only that it failed. The arms assert VALUES against known answers -- a broken
 * BigInteger still returns a perfectly plausible BigInteger, which is the silent wrong answer this VM is
 * worst at noticing.
 */
public class BigMathProbe
{
    public static void main(String[] args)
    {
        say("stage 0: class literal", BigInteger.class.getName(), "java.math.BigInteger");

        // Stage 1 -- the statics the rejected <clinit> used to leave null. ZERO/ONE/TEN are THE canary:
        // a skipped initializer makes every one of them null and the first arithmetic NPEs.
        say("stage 1: BigInteger.ZERO", str(BigInteger.ZERO), "0");
        say("stage 1: BigInteger.ONE", str(BigInteger.ONE), "1");
        say("stage 1: BigInteger.TEN", str(BigInteger.TEN), "10");

        // Stage 2 -- small values through valueOf (the cache path) and int round-trip.
        say("stage 2: valueOf(42)", str(BigInteger.valueOf(42)), "42");
        say("stage 2: valueOf(-7)", str(BigInteger.valueOf(-7)), "-7");
        say("stage 2: intValue", "" + BigInteger.valueOf(123456).intValue(), "123456");

        // Stage 3 -- the string constructor and toString, which is the whole external surface.
        say("stage 3: new(\"12345678901234567890\")", str(new BigInteger("12345678901234567890")),
            "12345678901234567890");
        say("stage 3: new(\"-99\")", str(new BigInteger("-99")), "-99");
        say("stage 3: radix 16", str(new BigInteger("ff", 16)), "255");
        say("stage 3: toString(16)", new BigInteger("255").toString(16), "ff");

        // Stage 4 -- arithmetic BEYOND long, so an implementation backed by a long cannot pass.
        BigInteger a = new BigInteger("123456789012345678901234567890");
        BigInteger b = new BigInteger("987654321098765432109876543210");
        say("stage 4: add", str(a.add(b)), "1111111110111111111011111111100");
        say("stage 4: subtract", str(b.subtract(a)), "864197532086419753208641975320");
        say("stage 4: multiply", str(a.multiply(b)),
            "121932631137021795226185032733622923332237463801111263526900");
        say("stage 4: divide", str(b.divide(a)), "8");
        say("stage 4: remainder", str(b.remainder(a)), "9000000000900000000090");
        say("stage 4: negate", str(a.negate()), "-123456789012345678901234567890");
        say("stage 4: abs", str(a.negate().abs()), "123456789012345678901234567890");

        // Stage 5 -- bit surface and comparison.
        say("stage 5: bitLength", "" + a.bitLength(), "97");
        say("stage 5: signum", a.signum() + "/" + a.negate().signum() + "/" + BigInteger.ZERO.signum(),
            "1/-1/0");
        say("stage 5: compareTo", a.compareTo(b) + "/" + b.compareTo(a) + "/" + a.compareTo(a), "-1/1/0");
        say("stage 5: equals", a.equals(new BigInteger("123456789012345678901234567890")) + "/"
            + a.equals(b), "true/false");
        say("stage 5: shiftLeft", str(BigInteger.ONE.shiftLeft(100)),
            "1267650600228229401496703205376");
        say("stage 5: testBit", BigInteger.valueOf(5).testBit(0) + "/" + BigInteger.valueOf(5).testBit(1),
            "true/false");

        // Stage 6 -- pow/gcd/modPow. modPow is what every public-key path needs, and it is the arm most
        // likely to reach BigInteger's PARALLEL multiply, which is the recorded ForkJoin blocker.
        say("stage 6: pow", str(BigInteger.valueOf(2).pow(100)), "1267650600228229401496703205376");
        say("stage 6: gcd", str(new BigInteger("123456789").gcd(new BigInteger("987654321"))), "9");
        say("stage 6: modPow", str(BigInteger.valueOf(4).modPow(BigInteger.valueOf(13),
            BigInteger.valueOf(497))), "445");
        say("stage 6: modInverse", str(BigInteger.valueOf(3).modInverse(BigInteger.valueOf(11))), "4");

        // Stage 7 -- BigDecimal. The 0.1 + 0.2 arm is the whole reason the class exists: a double gets
        // 0.30000000000000004, and an implementation that quietly fell back to double would show it.
        say("stage 7: BigDecimal.ONE", str2(BigDecimal.ONE), "1");
        say("stage 7: new(\"0.1\")+new(\"0.2\")",
            str2(new BigDecimal("0.1").add(new BigDecimal("0.2"))), "0.3");
        say("stage 7: multiply", str2(new BigDecimal("1.5").multiply(new BigDecimal("2.5"))), "3.75");
        say("stage 7: scale/precision", new BigDecimal("1.230").scale() + "/"
            + new BigDecimal("1.230").precision(), "3/4");
        say("stage 7: divide HALF_UP",
            str2(new BigDecimal("10").divide(new BigDecimal("3"), 5, RoundingMode.HALF_UP)), "3.33333");
        say("stage 7: setScale", str2(new BigDecimal("2.345").setScale(2, RoundingMode.HALF_UP)), "2.35");
        say("stage 7: stripTrailingZeros", str2(new BigDecimal("1.2300").stripTrailingZeros()), "1.23");
        say("stage 7: compareTo vs equals",
            new BigDecimal("1.0").compareTo(new BigDecimal("1.00")) + "/"
            + new BigDecimal("1.0").equals(new BigDecimal("1.00")), "0/false");
        say("stage 7: toBigInteger", str(new BigDecimal("123.99").toBigInteger()), "123");
        say("stage 7: valueOf(long)", str2(BigDecimal.valueOf(12345, 2)), "123.45");

        System.out.println("BigMathProbe done");
    }

    /** Null-safe, so a null static reports as such instead of NPEing and hiding every later arm. */
    private static String str(BigInteger v)
    {
        return v == null ? "<null>" : v.toString();
    }

    private static String str2(BigDecimal v)
    {
        return v == null ? "<null>" : v.toString();
    }

    private static void say(String what, String got, String want)
    {
        System.out.println("  " + what + " = " + got + " (want " + want + ")");
    }
}
