/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-13
 */
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@code Throwable.toString}, which this VM answered with {@code Object.toString} ({@code name@hash}) because the
 * overlay could not declare it: the host writer had no {@code getClass} intrinsic. Each shape reaches it a
 * different way -- directly, through string concatenation and {@code String.valueOf}, through a subclass that
 * overrides {@code getLocalizedMessage}, and through the cause constructor, which builds its message from
 * {@code cause.toString()} (a wrong toString there corrupts the MESSAGE of every wrapping exception too).
 * Also one {@code getClass()} on a plain object, for the same intrinsic. Prints values only; the host diff is
 * the gate.
 */
public class ThrowableToStringProbe
{
    static class Localized extends RuntimeException
    {
        Localized(String m)
        {
            super(m);
        }

        @Override
        public String getLocalizedMessage()
        {
            return "localized(" + getMessage() + ")";
        }
    }

    static class Quiet extends Exception
    {
    }

    public static void main(String[] args)
    {
        System.out.println("1 " + new RuntimeException("boom").toString());
        System.out.println("2 " + new IllegalStateException().toString());
        System.out.println("3 " + new Throwable("t"));
        System.out.println("4 " + String.valueOf(new Error((String) null)));
        System.out.println("5 " + new Localized("x"));
        System.out.println("6 " + new Quiet());
        RuntimeException cause = new IllegalArgumentException("inner");
        RuntimeException wrap = new RuntimeException(cause);
        System.out.println("7 msg=" + wrap.getMessage());
        System.out.println("8 " + wrap);
        System.out.println("9 " + new UncheckedIOException(new IOException("io")));
        try
        {
            Object o = "s";
            Integer i = (Integer) o;
            System.out.println("cast passed " + i);
        }
        catch (ClassCastException e)
        {
            System.out.println("10 class=" + e.getClass().getName() + " starts=" + e.toString().startsWith(
                    "java.lang.ClassCastException"));
        }
        try
        {
            int[] a = new int[1];
            a[2] = 1;
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            System.out.println("11 " + e);
        }
        Object plain = new StringBuilder("sb");
        System.out.println("12 " + plain.getClass().getName());
        System.out.println("ThrowableToStringProbe done");
    }
}
