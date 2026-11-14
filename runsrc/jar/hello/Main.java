/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package hello;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * A program the web server runs out of an uploaded or downloaded jar (demo/RunProgram). Its classes refer to each
 * other in both directions -- Main uses Util and Util calls back into Main -- so no order of defining them
 * one at a time has every reference already defined. Main sorts first, Util last.
 */
public class Main
{
    static int calls;

    public static void main(String[] args)
    {
        System.out.println("hello from an uploaded jar");
        System.out.println("args " + args.length + " " + String.join(",", args));
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Square(3));
        shapes.add(new Square(5));
        int scale = args.length + 2;
        IntUnaryOperator f = x -> x * scale + calls;
        for (Shape s : shapes)
        {
            System.out.println(s.name() + " area " + s.area() + " scaled " + f.applyAsInt(s.area()));
        }
        System.out.println("util " + Util.twice(21) + " calls " + calls);
        try
        {
            Util.fail("deep");
        }
        catch (IllegalStateException e)
        {
            System.out.println("caught " + e.getMessage());
        }
        System.err.println("to stderr");
    }

    static void count()
    {
        calls = calls + 1;
    }
}
