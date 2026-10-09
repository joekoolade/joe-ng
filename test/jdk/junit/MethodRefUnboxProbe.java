/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-08
 */
import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * METHOD REFERENCES WHOSE REFERENT TAKES PRIMITIVES, called through a generic (erased) functional interface. The
 * SAM passes boxed references; {@code LambdaMetafactory} inserts the unboxing. joe-ng synthesises lambda classes
 * itself, and its thunks boxed a primitive RESULT but never unboxed an ARGUMENT: {@code Integer::sum} summed the
 * two {@code Integer} ADDRESSES ({@code ConcurrentHashMap.merge} returned 91,635,776 for a count of 100).
 *
 * <p>One arm per thunk shape and per primitive kind, because each is a separate code path in the thunk builder:
 * static, unbound instance (receiver + args), bound instance (captured receiver), constructor reference;
 * Z/B/C/S/I/J/F/D, a widening conversion, a primitive SAM return, and a NULL argument (which must throw
 * {@code NullPointerException}, as on a stock JVM, not read whatever is at a low address). Prints values only, so
 * the host diff is the gate.
 *
 * <p>The F/D arms RETURN an int or boolean on purpose: boxing a float/double RESULT is not implemented yet (see
 * {@code vm.VMBox}), and is reported at link time; {@code Float::sum} returning {@code Float} is the open case.
 */
public class MethodRefUnboxProbe
{
    static final class Cell
    {
        final int v;

        Cell(int v)
        {
            this.v = v;
        }
    }

    public static void main(String[] args)
    {
        BinaryOperator<Integer> isum = Integer::sum;
        System.out.println("static I,I -> I boxed: " + isum.apply(3, 4));
        BinaryOperator<Long> lsum = Long::sum;
        System.out.println("static J,J -> J boxed: " + lsum.apply(5000000000L, 7L));
        Function<Integer, String> hex = Integer::toHexString;
        System.out.println("static I -> ref: " + hex.apply(255));
        Comparator<Integer> cmp = Integer::compare;
        System.out.println("static I,I -> primitive SAM return: " + Integer.signum(cmp.compare(5, 3)));
        ToIntFunction<Integer> abs = Math::abs;
        System.out.println("static I -> int via ToIntFunction: " + abs.applyAsInt(-9));
        Predicate<Character> digit = Character::isDigit;
        System.out.println("static C -> Z: " + digit.test('7') + " " + digit.test('x'));
        BinaryOperator<Boolean> xor = Boolean::logicalXor;
        System.out.println("static Z,Z -> Z boxed: " + xor.apply(true, false) + " " + xor.apply(true, true));
        BiFunction<Short, Short, Integer> scmp = Short::compare;
        System.out.println("static S,S -> I boxed: " + scmp.apply((short) 5, (short) 3));
        BiFunction<Byte, Byte, Integer> bcmp = Byte::compare;
        System.out.println("static B,B -> I boxed: " + bcmp.apply((byte) -2, (byte) 2));
        Function<Integer, Long> widen = Long::valueOf;
        System.out.println("static Integer -> long (unbox + widen): " + widen.apply(42));
        BiFunction<Float, Float, Integer> fcmp = Float::compare;
        System.out.println("static F,F -> I boxed: " + fcmp.apply(1.5f, 2.25f) + " " + fcmp.apply(2.25f, 1.5f));
        Predicate<Double> fin = Double::isFinite;
        System.out.println("static D -> Z: " + fin.test(1.0 / 0.0) + " " + fin.test(16.0));
        BiFunction<Double, Double, Integer> dcmp = Double::compare;
        System.out.println("static D,D -> I boxed: " + dcmp.apply(-1.5, 2.5) + " " + dcmp.apply(2.5, 2.5));

        BiFunction<String, Integer, Character> charAt = String::charAt;
        System.out.println("unbound instance, receiver + I -> C boxed: " + charAt.apply("hello", 1));
        String s = "abcdef";
        Function<Integer, Character> bound = s::charAt;
        System.out.println("bound instance, I -> C boxed: " + bound.apply(2));
        Function<Integer, Cell> ctor = Cell::new;
        System.out.println("constructor reference, I: " + ctor.apply(77).v);

        try
        {
            System.out.println("null argument: " + isum.apply(null, 1));
        }
        catch (NullPointerException e)
        {
            System.out.println("null argument: NullPointerException");
        }
        try
        {
            System.out.println("null argument, bound: " + bound.apply(null));
        }
        catch (NullPointerException e)
        {
            System.out.println("null argument, bound: NullPointerException");
        }
        System.out.println("MethodRefUnboxProbe done");
    }
}
