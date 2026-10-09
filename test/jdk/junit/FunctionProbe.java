/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-09
 */
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every default and static method of the six {@code java.util.function} interfaces joe-ng used to overlay, and the
 * stream paths that reach the ones the overlays dropped.
 *
 * <p>The overlays were minimal hand-written shells; {@code BinaryOperator} had no {@code minBy}/{@code maxBy},
 * which stock {@code ReferencePipeline.max}/{@code min} and {@code Collectors.maxBy}/{@code minBy}/{@code reducing}
 * call -- so a plain {@code stream.max(comparator)} reached a missing method. {@code Consumer.andThen} and
 * {@code BiConsumer.andThen} were missing too. Each arm prints a value, ordered so an evaluation-order slip (the
 * combinators' contract) shows as a different string; the host diff is the gate.
 */
public class FunctionProbe
{
    public static void main(String[] args)
    {
        Function<Integer, Integer> inc = x -> x + 1;
        Function<Integer, Integer> dbl = x -> x * 2;
        System.out.println("Function andThen/compose/identity: " + inc.andThen(dbl).apply(3) + " "
                + inc.compose(dbl).apply(3) + " " + Function.<String>identity().apply("id"));

        BiFunction<Integer, Integer, Integer> add = (a, b) -> a + b;
        System.out.println("BiFunction andThen: " + add.andThen(dbl).apply(4, 5));

        BinaryOperator<String> shorter = BinaryOperator.minBy(Comparator.comparingInt(String::length));
        BinaryOperator<String> longer = BinaryOperator.maxBy(Comparator.comparingInt(String::length));
        System.out.println("BinaryOperator minBy/maxBy: " + shorter.apply("ab", "abc") + " " + longer.apply("ab", "abc")
                + " tie->first " + shorter.apply("xy", "zw"));

        StringBuilder log = new StringBuilder();
        Consumer<String> a = s -> log.append("a:").append(s).append(' ');
        Consumer<String> b = s -> log.append("b:").append(s).append(' ');
        a.andThen(b).accept("v");
        BiConsumer<String, Integer> c = (s, n) -> log.append("c:").append(s).append(n).append(' ');
        BiConsumer<String, Integer> d = (s, n) -> log.append("d:").append(s).append(n);
        c.andThen(d).accept("w", 7);
        System.out.println("Consumer/BiConsumer andThen order: " + log);

        Predicate<Integer> pos = x -> x > 0;
        Predicate<Integer> even = x -> x % 2 == 0;
        int[] calls = new int[1];
        Predicate<Integer> counted = x ->
        {
            calls[0] += 1;
            return true;
        };
        System.out.println("Predicate and/or/negate/not/isEqual: " + pos.and(even).test(4) + " " + pos.or(even).test(-3)
                + " " + pos.negate().test(5) + " " + Predicate.not(even).test(3) + " "
                + Predicate.isEqual("k").test("k") + " " + Predicate.isEqual(null).test(null));
        boolean shortCircuit = pos.and(counted).test(-1) || pos.negate().or(counted).test(-1);
        System.out.println("Predicate short-circuits: " + shortCircuit + ", second evaluated " + calls[0] + " times");

        List<String> words = new ArrayList<>();
        words.add("pear");
        words.add("fig");
        words.add("banana");
        words.add("kiwi");
        Comparator<String> byLen = Comparator.comparingInt(String::length);
        Optional<String> max = words.stream().max(byLen);
        Optional<String> min = words.stream().min(byLen);
        System.out.println("stream max/min (ReferencePipeline -> BinaryOperator.maxBy/minBy): " + max.get() + " "
                + min.get());
        System.out.println("Collectors.maxBy/minBy/reducing: " + words.stream().collect(Collectors.maxBy(byLen)).get()
                + " " + words.stream().collect(Collectors.minBy(byLen)).get() + " "
                + Stream.of(5, 3, 9).collect(Collectors.reducing(BinaryOperator.<Integer>minBy(Comparator.naturalOrder()))).get());
        System.out.println("FunctionProbe done");
    }
}
