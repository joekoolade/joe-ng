/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-11
 */
import java.util.List;
import java.util.Random;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * METHOD REFERENCES WHOSE REFERENT RETURNS {@code float}/{@code double}, called through a generic (erased) functional
 * interface, so the result must be BOXED. joe-ng's lambda thunks boxed the six integral kinds and refused these at
 * link time ({@code UNBOXABLE METHOD REF ... float/double}); at run time the raw bits came back posing as a
 * reference ({@code Float::sum} branched wild in #333's control).
 *
 * <p>One arm per thunk shape that can return F/D -- static, bound instance, unbound instance -- plus the stream
 * idioms that are why this matters ({@code reduce(0.0, Double::sum)}, {@code map(Math::sqrt)}), and values whose
 * bit patterns must survive the round trip exactly: -0.0, NaN, infinity, {@code Float.MAX_VALUE}. Prints values only,
 * so the host diff is the gate.
 */
public class MethodRefBoxFloatProbe
{
    public static void main(String[] args)
    {
        BinaryOperator<Float> fsum = Float::sum;
        System.out.println("static F,F -> F: " + fsum.apply(1.5f, 2.25f) + " " + fsum.apply(Float.MAX_VALUE, 0f));
        Function<Double, Double> sqrt = Math::sqrt;
        System.out.println("static D -> D: " + sqrt.apply(16.0) + " " + sqrt.apply(-1.0) + " " + sqrt.apply(2.0));
        BinaryOperator<Double> dmax = Math::max;
        BinaryOperator<Double> dsum = Double::sum;
        System.out.println("static D,D -> D: " + dmax.apply(-1.5, 2.5) + " " + dsum.apply(-0.0, -0.0) + " "
                + dsum.apply(Double.MAX_VALUE, Double.MAX_VALUE));
        Random random = new Random(5L);
        Supplier<Double> next = random::nextDouble;
        System.out.println("bound instance -> D: " + next.get() + " " + next.get());
        Function<Float, Float> unboxed = Float::floatValue;
        System.out.println("unbound instance -> F: " + unboxed.apply(-3.25f));
        List<Double> xs = List.of(1.5, 2.5, 3.0);
        System.out.println("stream reduce(0.0, Double::sum) -> " + xs.stream().reduce(0.0, Double::sum));
        System.out.println("stream map(Math::sqrt) -> "
                + List.of(4.0, 9.0, 0.25).stream().map(Math::sqrt).collect(Collectors.toList()));
        Double boxed = dsum.apply(0.1, 0.2);
        System.out.println("result is a real Double: " + (boxed instanceof Double) + " " + boxed.doubleValue()
                + " bits=" + Long.toHexString(Double.doubleToRawLongBits(boxed)));
        System.out.println("MethodRefBoxFloatProbe done");
    }
}
