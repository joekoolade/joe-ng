/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-15
 */
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * What reflective invoke does AROUND the call: an exception the target throws -- checked, unchecked, or an
 * Error -- arrives wrapped in {@code InvocationTargetException} with the original as its cause, from a method
 * and from a constructor; arguments are converted as stock converts them, unboxing and then WIDENING ({@code
 * Integer} to {@code long}, {@code Character} to {@code int}, {@code Float} to {@code double}, {@code Byte} to
 * {@code short}), and a value that cannot be converted -- a narrowing, a wrong reference type, a null for a
 * primitive -- is {@code IllegalArgumentException}, never a {@code ClassCastException} or a silent coercion. Each
 * arm prints the outcome's class, never a stack trace. Prints values only; the host diff is the gate.
 */
public class InvokeSemanticsProbe
{
    final String made;

    InvokeSemanticsProbe(long n)
    {
        made = "long ctor " + n;
    }

    InvokeSemanticsProbe(String s) throws IOException
    {
        if (s.isEmpty())
        {
            throw new IOException("empty");
        }
        made = "string ctor " + s;
    }

    static String takesLong(long v)
    {
        return "long " + v;
    }

    static String takesInt(int v)
    {
        return "int " + v;
    }

    static String takesDouble(double v)
    {
        return "double " + v;
    }

    static String takesShort(short v)
    {
        return "short " + v;
    }

    static String takesFloat(float v)
    {
        return "float " + v;
    }

    static String takesString(String s)
    {
        return "string " + s;
    }

    static String takesObject(Object o)
    {
        return "object " + o;
    }

    static void throwsChecked() throws IOException
    {
        throw new IOException("checked");
    }

    static void throwsUnchecked()
    {
        throw new IllegalStateException("unchecked");
    }

    static void throwsError()
    {
        throw new AssertionError("error");
    }

    static int depth(int n)
    {
        if (n == 0)
        {
            throw new UnsupportedOperationException("deep");
        }
        return depth(n - 1) + 1;
    }

    static void call(String what, Method m, Object... args)
    {
        try
        {
            System.out.println(what + " -> " + m.invoke(null, args));
        }
        catch (InvocationTargetException e)
        {
            Throwable c = e.getCause();
            System.out.println(what + " -> InvocationTargetException cause=" + c.getClass().getName() + ": "
                    + c.getMessage() + " targetSame=" + (e.getTargetException() == c));
        }
        catch (IllegalArgumentException e)
        {
            System.out.println(what + " -> IllegalArgumentException");
        }
        catch (Throwable t)
        {
            System.out.println(what + " -> UNEXPECTED " + t.getClass().getName());
        }
    }

    static void make(String what, Constructor<?> k, Object... args)
    {
        try
        {
            System.out.println(what + " -> " + ((InvokeSemanticsProbe) k.newInstance(args)).made);
        }
        catch (InvocationTargetException e)
        {
            System.out.println(what + " -> InvocationTargetException cause=" + e.getCause().getClass().getName()
                    + ": " + e.getCause().getMessage());
        }
        catch (IllegalArgumentException e)
        {
            System.out.println(what + " -> IllegalArgumentException");
        }
        catch (Throwable t)
        {
            System.out.println(what + " -> UNEXPECTED " + t.getClass().getName());
        }
    }

    public static void main(String[] args) throws Exception
    {
        Class<?> c = InvokeSemanticsProbe.class;
        Method mLong = c.getDeclaredMethod("takesLong", long.class);
        Method mInt = c.getDeclaredMethod("takesInt", int.class);
        Method mDouble = c.getDeclaredMethod("takesDouble", double.class);
        Method mShort = c.getDeclaredMethod("takesShort", short.class);
        Method mFloat = c.getDeclaredMethod("takesFloat", float.class);
        Method mString = c.getDeclaredMethod("takesString", String.class);
        Method mObject = c.getDeclaredMethod("takesObject", Object.class);

        System.out.println("-- exceptions are wrapped --");
        call("checked", c.getDeclaredMethod("throwsChecked"));
        call("unchecked", c.getDeclaredMethod("throwsUnchecked"));
        call("error", c.getDeclaredMethod("throwsError"));
        call("deep", c.getDeclaredMethod("depth", int.class), 5);

        System.out.println("-- widening --");
        call("long <- Integer", mLong, 7);
        call("long <- Character", mLong, 'A');
        call("long <- Short", mLong, (short) -3);
        call("int <- Character", mInt, 'B');
        call("int <- Byte", mInt, (byte) 9);
        call("double <- Integer", mDouble, 4);
        call("double <- Long", mDouble, 1L << 40);
        call("double <- Float", mDouble, 2.5f);
        call("short <- Byte", mShort, (byte) -7);
        call("float <- Long", mFloat, 123456789L);
        call("float <- Integer", mFloat, 16777217);
        call("object <- Integer", mObject, 3);

        System.out.println("-- mismatches --");
        call("int <- Long", mInt, 7L);
        call("int <- Double", mInt, 1.0);
        call("int <- Boolean", mInt, true);
        call("short <- Character", mShort, 'x');
        call("int <- String", mInt, "7");
        call("int <- null", mInt, (Object) null);
        call("string <- Integer", mString, 7);
        call("string <- null", mString, (Object) null);

        System.out.println("-- constructors --");
        Constructor<?> kLong = c.getDeclaredConstructor(long.class);
        Constructor<?> kString = c.getDeclaredConstructor(String.class);
        make("ctor(long) <- Integer", kLong, 42);
        make("ctor(long) <- String", kLong, "42");
        make("ctor(String) ok", kString, "x");
        make("ctor(String) throws", kString, "");
        System.out.println("InvokeSemanticsProbe done");
    }
}
