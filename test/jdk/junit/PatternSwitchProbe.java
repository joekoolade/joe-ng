/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-10
 */
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * PATTERN-MATCHING {@code switch}, which javac compiles to {@code invokedynamic} bootstrapped by
 * {@code SwitchBootstraps.typeSwitch(Object target, int restart)}. joe-ng's JIT could not lower it ("JIT unsupported
 * ... a=0xBA"), and stock {@code java.text.DecimalFormat} uses four of them.
 *
 * <p>One arm per thing the lowering must get right: label ORDER (the first matching label wins, so a subtype listed
 * before its supertype must take it), a NULL selector ({@code case null}, the bootstrap's -1), GUARDS (a failed
 * {@code when} re-enters the switch with {@code restart = i+1}, so a later label must still be found), a guard on
 * the LAST label (restart past every label = the no-match answer), a sealed hierarchy, record deconstruction
 * (nested patterns compile to nested typeSwitch sites), a label class nothing ever instantiates, and the default.
 * Prints values only, so the host diff is the gate.
 */
public class PatternSwitchProbe
{
    sealed interface Shape permits Circle, Square, Rect
    {
    }

    record Circle(int r) implements Shape
    {
    }

    record Square(int side) implements Shape
    {
    }

    record Rect(int w, int h) implements Shape
    {
    }

    record Pair(Object left, Object right)
    {
    }

    static final class NeverMade
    {
    }

    static String classify(Object o)
    {
        return switch (o)
        {
            case null -> "null";
            case Integer i when i > 100 -> "big int " + i;
            case Integer i when i < 0 -> "negative int " + i;
            case Integer i -> "int " + i;
            case Long l -> "long " + l;
            case NeverMade n -> "never";
            case String s when s.isEmpty() -> "empty string";
            case String s -> "string " + s.length();
            case Number n -> "other number " + n.getClass().getSimpleName();
            case int[] a -> "int[" + a.length + "]";
            default -> "other " + o.getClass().getSimpleName();
        };
    }

    static int area(Shape s)
    {
        return switch (s)
        {
            case Circle c -> 3 * c.r() * c.r();
            case Square q -> q.side() * q.side();
            case Rect(int w, int h) when w == h -> -1;
            case Rect(int w, int h) -> w * h;
        };
    }

    static String nested(Pair p)
    {
        return switch (p)
        {
            case Pair(Integer a, Integer b) -> "ints " + (a + b);
            case Pair(String a, Pair(Integer b, Object c)) -> "string+pair " + a + b;
            case Pair(Object a, Object b) -> "objects";
        };
    }

    static String lastGuard(Object o)
    {
        switch (o)
        {
            case String s when s.length() > 3:
                return "long string";
            default:
                return "fell through to default";
        }
    }

    public static void main(String[] args)
    {
        List<Object> inputs = new ArrayList<>();
        inputs.add(null);
        inputs.add(500);
        inputs.add(-3);
        inputs.add(7);
        inputs.add(9L);
        inputs.add("");
        inputs.add("hello");
        inputs.add(new BigDecimal("1.5"));
        inputs.add(2.5);
        inputs.add(new int[4]);
        inputs.add(new StringBuilder("x"));
        for (Object o : inputs)
        {
            System.out.println("classify -> " + classify(o));
        }
        System.out.println("areas -> " + area(new Circle(2)) + " " + area(new Square(3)) + " " + area(new Rect(4, 4))
                + " " + area(new Rect(2, 5)));
        System.out.println("nested -> " + nested(new Pair(1, 2)) + " | " + nested(new Pair("s", new Pair(3, "z")))
                + " | " + nested(new Pair("s", null)) + " | " + nested(new Pair("s", "t")));
        System.out.println("last label guarded -> " + lastGuard("abcd") + " | " + lastGuard("ab") + " | "
                + lastGuard(42));
        System.out.println("PatternSwitchProbe done");
    }
}
