/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-02
 */
import java.util.Random;

/**
 * {@code Random.nextDouble()}/{@code nextFloat()}, and the consumer that makes the first one reachable:
 * {@code java.lang.Math.random()}.
 *
 * <p>THE DEFECT, named by {@code make overlaycheck-deep} and MEASURED before a line of the fix was written.
 * {@code Math.random()} is literally
 * {@code RandomNumberGeneratorHolder.randomNumberGenerator.nextDouble()} and {@code StrictMath.random()} is
 * the same, and the {@code java/util/Random} overlay did not declare {@code nextDouble} -- so the member
 * ceased to exist and the call halted the VM:
 *
 * <pre>
 *   VIRTUALRESOLVE FAILED java/util/Random.nextDouble()D
 *   DENYLIST TRAP   at java/lang/Math.random(Math.java:897)
 * </pre>
 *
 * <p>THE ARMS ARE RAW BITS, NOT RENDERED VALUES, and that is the point of the probe rather than fussiness.
 * This class promises a sequence that is bit-for-bit the JDK's for a given seed, so a seeded draw has ONE
 * right answer and {@code Double.doubleToRawLongBits} is what states it; comparing
 * {@code Double.toString(d)} would also be testing the formatter and would round a one-ULP error away.
 *
 * <p>THE DRAW COUNT IS ASSERTED SEPARATELY, because no value arm can see it. {@code nextDouble} takes TWO
 * LCG steps ({@code next(26)} then {@code next(27)}, because a double has 53 significand bits and
 * {@code next} yields at most 32) and {@code nextFloat} takes ONE. An implementation using a single 32-bit
 * draw scaled by 2^-32, or {@code (float) nextDouble()} for the float, produces a perfectly uniform value in
 * range and leaves the stream at the WRONG STATE -- so every later draw is wrong, arbitrarily far away. The
 * arms compare the state after a draw against a reference Random advanced by the same number of
 * {@code nextInt()} calls (each is one step), which needs no host-specific constant and so fails for the
 * right reason in both worlds.
 *
 * <p>AND A STRUCTURAL ARM FOR THE SECOND DRAW: {@code d * 2^53} is an exact integer (the 53-bit
 * significand), so an implementation that kept only {@code next(26) << 27} would make its low 27 bits ZERO
 * for every draw. One draw in a hundred with a non-zero low 27 bits is what says the second {@code next} is
 * really there.
 *
 * <p>ONE SOURCE, BOTH WORLDS: against the real JDK the arms reach stock, against {@code guestsrc} the
 * overlay, and the gate is a byte-for-byte diff. Host control: plain {@code java}, no flags.
 */
public class RandomFpProbe
{
    static int failures;

    static void say(String name, String got, String want)
    {
        boolean ok = got.equals(want);
        if (!ok)
        {
            failures++;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + name + " = " + got + " (want " + want + ")");
    }

    /** The raw bits, in hex: the only rendering of a double that cannot hide a one-ULP error. */
    static String bits(double d)
    {
        return Long.toHexString(Double.doubleToRawLongBits(d));
    }

    static String bits(float f)
    {
        return Integer.toHexString(Float.floatToRawIntBits(f));
    }

    public static void main(String[] args)
    {
        // ---- BIT-EXACTNESS for a fixed seed. These are the JDK's values, taken from a host run of this
        // same source rather than transcribed, and they are what "bit-for-bit the JDK's" means.
        say("d  seed 42 #1 ", bits(new Random(42L).nextDouble()), bits(new Random(42L).nextDouble()));
        Random r42 = new Random(42L);
        say("d  seed 42 [0]", bits(r42.nextDouble()), HOST_D42_0);
        say("d  seed 42 [1]", bits(r42.nextDouble()), HOST_D42_1);
        say("d  seed 42 [2]", bits(r42.nextDouble()), HOST_D42_2);

        Random f42 = new Random(42L);
        say("f  seed 42 [0]", bits(f42.nextFloat()), HOST_F42_0);
        say("f  seed 42 [1]", bits(f42.nextFloat()), HOST_F42_1);

        // A different seed, so an implementation that ignored the seed entirely fails here rather than
        // passing three arms that all read the same wrong constant.
        say("d  seed 7  [0]", bits(new Random(7L).nextDouble()), HOST_D7_0);
        say("f  seed 7  [0]", bits(new Random(7L).nextFloat()), HOST_F7_0);

        // ---- THE DRAW COUNT, with no host constant involved: each nextInt() is exactly one LCG step, so a
        // Random that has done one nextDouble() must be in the same state as one that has done TWO
        // nextInt()s -- and one nextFloat() the same as ONE nextInt().
        Random a = new Random(7L);
        a.nextDouble();
        Random b = new Random(7L);
        b.nextInt();
        b.nextInt();
        say("d  steps = 2  ", "" + (a.nextInt() == b.nextInt()), "true");

        Random c = new Random(7L);
        c.nextFloat();
        Random e = new Random(7L);
        e.nextInt();
        say("f  steps = 1  ", "" + (c.nextInt() == e.nextInt()), "true");

        // ...and the NEGATIVE of the same shape: one nextDouble() must NOT leave the state a single
        // nextInt() would, or the two-draw claim above is vacuous.
        Random g = new Random(7L);
        g.nextDouble();
        Random h = new Random(7L);
        h.nextInt();
        say("d  not 1 step ", "" + (g.nextInt() == h.nextInt()), "false");

        // ---- THE SECOND DRAW IS REALLY THERE: d * 2^53 is an exact integer, and an implementation keeping
        // only next(26)<<27 would leave its low 27 bits zero on EVERY draw.
        Random lo = new Random(99L);
        boolean sawLowBits = false;
        for (int i = 0; i < 100; i++)
        {
            long scaled = (long) (lo.nextDouble() * 0x1.0p53);
            if ((scaled & ((1L << 27) - 1L)) != 0L)
            {
                sawLowBits = true;
            }
        }
        say("d  low 27 bits", "" + sawLowBits, "true");

        // ---- RANGE, over enough draws that a sign or scale error cannot hide.
        Random rr = new Random(123L);
        boolean dRange = true;
        boolean fRange = true;
        for (int i = 0; i < 200; i++)
        {
            double d = rr.nextDouble();
            if (!(d >= 0.0 && d < 1.0))
            {
                dRange = false;
            }
            float f = rr.nextFloat();
            if (!(f >= 0.0f && f < 1.0f))
            {
                fRange = false;
            }
        }
        say("d  in [0,1)   ", "" + dRange, "true");
        say("f  in [0,1)   ", "" + fRange, "true");

        // float and double are DIFFERENT algorithms off the same first step, so they must disagree -- an
        // implementation that defined nextFloat as (float) nextDouble() passes every arm above but this one.
        say("f  != (f)d    ", "" + (new Random(5L).nextFloat() == (float) new Random(5L).nextDouble()),
            "false");

        // setSeed re-seeding reproduces the stream, which is what makes a seeded test reproducible at all.
        Random s1 = new Random(1L);
        s1.nextDouble();
        s1.setSeed(42L);
        say("d  reseeded   ", bits(s1.nextDouble()), HOST_D42_0);

        // ---- THE MEASURED CONSUMER. Math.random() is unseeded, so only its RANGE and its liveness can be
        // asserted -- but reaching it at all is the whole point: this is the call that halted the VM.
        boolean mRange = true;
        for (int i = 0; i < 100; i++)
        {
            double d = Math.random();
            if (!(d >= 0.0 && d < 1.0))
            {
                mRange = false;
            }
        }
        say("Math.random   ", "" + mRange, "true");
        say("Math.r differs", "" + (Math.random() != Math.random()), "true");
        say("StrictMath.r  ", "" + inRange(StrictMath.random()), "true");

        System.out.println("RandomFpProbe done, failures=" + failures);
    }

    static boolean inRange(double d)
    {
        return d >= 0.0 && d < 1.0;
    }

    // The JDK's own answers, taken from a HOST run of this same source (see the class javadoc) and not
    // transcribed from anywhere: an arm whose expected value came from my arithmetic would be testing my
    // arithmetic.
    static final String HOST_D42_0 = "3fe74833a06ff457";
    static final String HOST_D42_1 = "3fe5dcf778622e01";
    static final String HOST_D42_2 = "3fd3c20f3f12bbb4";
    static final String HOST_F42_0 = "3f3a419d";
    static final String HOST_F42_1 = "3d5fe8a0";
    static final String HOST_D7_0 = "3fe761e2f51bb9a0";
    static final String HOST_F7_0 = "3f3b0f17";
}
