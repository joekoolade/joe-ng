/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-21
 */

/**
 * A MUTUALLY-REFERENTIAL {@code <clinit>} pair, reduced to twenty lines from the
 * {@code java.lang.constant} failure that blocks the MethodHandle arc.
 *
 * <p><b>The shape, copied from the real one rather than invented.</b> {@code ConstantDescs} assigns
 * {@code CD_Class} (line 79) and {@code BSM_PRIMITIVE_CLASS} (line 199) BEFORE it first touches
 * {@code PrimitiveClassDescImpl} (line 250, {@code CD_int = PrimitiveClassDescImpl.CD_int}); and
 * {@code PrimitiveClassDescImpl.<clinit>} is just {@code CD_int = new PrimitiveClassDescImpl("I")}, whose
 * CONSTRUCTOR reads {@code ConstantDescs.BSM_PRIMITIVE_CLASS} straight back. {@link Early} and {@link Late}
 * below are that, with the names changed.
 *
 * <p><b>The decisive detail is WHERE the back-reference lives: in {@code <init>}, an ordinary method, not in
 * {@code <clinit>}.</b> An initialization trigger that covers only initializer bodies never fires on the
 * instruction that matters -- which is exactly how the first attempt at this fix missed, so the probe is
 * built to expose that specific mistake rather than the general shape.
 *
 * <p><b>The assertion is ORDER-INDEPENDENT, which is what makes it a fair test.</b> Whichever class is
 * touched first, {@code Late.FIRST.sawFromEarly} must be {@code "early-value"} on a conforming JVM:
 * <ul>
 *   <li>Early first -- {@code EARLY} is assigned, then {@code Late} is triggered, so the constructor reads an
 *       assigned field.
 *   <li>Late first -- the constructor's read of {@code Early.EARLY} is itself the active use that triggers
 *       {@code Early}, whose body assigns {@code EARLY} before returning, so the constructor still reads an
 *       assigned field.
 * </ul>
 * Both orders are exercised, by two independent pairs, because a fix that works for one and not the other
 * would otherwise pass. {@code Early.VIA_LATE} is deliberately NOT asserted: with Late touched first a real
 * JVM legitimately leaves it null (recursive initialization returns immediately), so asserting it would be
 * asserting something the specification does not promise -- the trap a probe arm of mine fell into once
 * before on {@code Annotation} identity.
 *
 * <p>Compare against a stock JVM: every line is byte-comparable.
 */
public class ClinitOrderProbe
{
    public static void main(String[] args)
    {
        // ORDER 1 -- touch Early first. This is the ConstantDescs order.
        String viaEarly = Early.EARLY;                     // active use of Early
        System.out.println("order1 early field   = " + viaEarly + " (want early-value)");
        System.out.println("order1 ctor saw      = " + Late.FIRST.sawFromEarly + " (want early-value)");

        // ORDER 2 -- touch the constructor-holding class first, on an independent pair.
        System.out.println("order2 ctor saw      = " + Late2.FIRST2.sawFromEarly2 + " (want early2-value)");
        System.out.println("order2 early field   = " + Early2.EARLY2 + " (want early2-value)");

        System.out.println("ClinitOrderProbe done");
    }
}

/** Mirrors {@code ConstantDescs}: assigns its own static BEFORE it first touches the other class. */
class Early
{
    // NOT a compile-time constant, and that is the whole point. `static final String X = "lit"` is a
    // constant variable (JLS 4.12.4), so javac INLINES it at every use site as an ldc -- there is then no
    // getstatic, no initialization dependency, and the probe tests nothing. The first cut of this file made
    // exactly that mistake and passed on a VM that demonstrably has the bug. A method call makes the
    // initializer a non-constant expression, so uses compile to a real getstatic.
    static final String EARLY = makeTag();
    static final Late VIA_LATE = Late.FIRST;               // triggers Late -- AFTER EARLY is assigned

    private static String makeTag()
    {
        return "early-value";
    }
}

/** Mirrors {@code PrimitiveClassDescImpl}: a {@code <clinit>} that is one {@code new}, reading back in its ctor. */
class Late
{
    static final Late FIRST = new Late();
    final String sawFromEarly;

    Late()
    {
        this.sawFromEarly = Early.EARLY;                   // THE ACTIVE USE, inside <init> not <clinit>
    }
}

/** The same pair again, so the opposite initialization order is covered by a class nothing has touched. */
class Early2
{
    static final String EARLY2 = makeTag();                 // non-constant, as above
    static final Late2 VIA_LATE2 = Late2.FIRST2;

    private static String makeTag()
    {
        return "early2-value";
    }
}

class Late2
{
    static final Late2 FIRST2 = new Late2();
    final String sawFromEarly2;

    Late2()
    {
        this.sawFromEarly2 = Early2.EARLY2;
    }
}
