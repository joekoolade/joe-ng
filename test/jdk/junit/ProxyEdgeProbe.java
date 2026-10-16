/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-16
 */
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The edges of a dynamic proxy's calling convention and contract. Nine and seventeen arguments (past the
 * register arguments, and through the pointer the VM passes past sixteen), each distinct so a shift shows;
 * float, char, byte and short arguments and returns; an UNDECLARED checked exception (wrapped in
 * {@code UndeclaredThrowableException}); a null for a primitive result ({@code NullPointerException}) and a
 * result of the wrong type ({@code ClassCastException}); a {@code Comparator} proxy driving
 * {@code Collections.sort}; the class cache ({@code getProxyClass} twice); a super-interface method, whose
 * Method names the SUPER-interface; and proxies as {@code HashMap} keys through the handler's
 * {@code equals}/{@code hashCode}. Prints values only; the host diff is the gate.
 */
public class ProxyEdgeProbe
{
    static final ClassLoader L = ProxyEdgeProbe.class.getClassLoader();

    interface Base
    {
        String base(String s);
    }

    interface Wide extends Base
    {
        long nine(int a, int b, int c, int d, int e, int f, int g, int h, int i);

        String seventeen(int a, long b, double c, float d, String e, int f, int g, int h, int i, long j, double k,
                int l, String m, int n, int o, double p, String q);

        float f(float x);

        char c(char x);

        byte b(byte x);

        short s(short x);

        int nullPrimitive();

        String wrongType();

        void undeclared();
    }

    /** The same nine-int call, for receivers that are NOT proxies: an ordinary class and a lambda. */
    interface Nine
    {
        long nine(int a, int b, int c, int d, int e, int f, int g, int h, int i);
    }

    static class NineImpl implements Nine
    {
        public long nine(int a, int b, int c, int d, int e, int f, int g, int h, int i)
        {
            return a + 2L * b + 3L * c + 4L * d + 5L * e + 6L * f + 7L * g + 8L * h + 9L * i;
        }
    }

    static class Echo implements InvocationHandler
    {
        public Object invoke(Object proxy, Method m, Object[] a) throws Throwable
        {
            switch (m.getName())
            {
                case "base":
                    return m.getDeclaringClass().getSimpleName() + ":" + a[0];
                case "nine":
                    long sum = 0;
                    for (int i = 0; i < a.length; i++)
                    {
                        sum += (long) (Integer) a[i] * (i + 1);
                    }
                    return sum;
                case "seventeen":
                    return Arrays.toString(a);
                case "f":
                    return (Float) a[0] * 2;
                case "c":
                    return (char) ((Character) a[0] + 1);
                case "b":
                    return (byte) ((Byte) a[0] - 1);
                case "s":
                    return (short) ((Short) a[0] * 3);
                case "nullPrimitive":
                    return null;
                case "wrongType":
                    return 42;
                case "undeclared":
                    throw new java.io.IOException("not declared");
                case "hashCode":
                    return 7;
                case "equals":
                    return a[0] instanceof Wide;
                case "toString":
                    return "Echo";
                default:
                    throw new IllegalStateException(m.getName());
            }
        }
    }

    static void arm(String what, java.util.concurrent.Callable<Object> c)
    {
        try
        {
            System.out.println(what + " -> " + c.call());
        }
        catch (Throwable t)
        {
            System.out.println(what + " -> " + t.getClass().getName()
                    + (t.getCause() != null ? " cause=" + t.getCause().getClass().getName() : ""));
        }
    }

    public static void main(String[] args) throws Exception
    {
        Nine viaClass = new NineImpl();
        Nine viaLambda = (a, b, c, d, e, f, g, h, i) -> a + 2L * b + 3L * c + 4L * d + 5L * e + 6L * f + 7L * g + 8L * h
                + 9L * i;
        arm("nine via class", () -> viaClass.nine(1, 2, 3, 4, 5, 6, 7, 8, 9));
        arm("nine via lambda", () -> viaLambda.nine(1, 2, 3, 4, 5, 6, 7, 8, 9));
        Wide w = (Wide) Proxy.newProxyInstance(L, new Class<?>[] { Wide.class }, new Echo());
        arm("base", () -> w.base("x"));
        arm("nine", () -> w.nine(1, 2, 3, 4, 5, 6, 7, 8, 9));
        arm("seventeen", () -> w.seventeen(1, 2L, 3.5, 4.25f, "five", 6, 7, 8, 9, 10L, 11.5, 12, "thirteen", 14, 15,
                16.75, "seventeen"));
        arm("f", () -> w.f(1.25f));
        arm("c", () -> w.c('a'));
        arm("b", () -> w.b((byte) -5));
        arm("s", () -> w.s((short) 300));
        arm("nullPrimitive", () -> w.nullPrimitive());
        arm("wrongType", () -> w.wrongType());
        arm("undeclared", () ->
        {
            w.undeclared();
            return "no exception";
        });

        Comparator<String> byLength = (Comparator<String>) Proxy.newProxyInstance(L, new Class<?>[] {
                Comparator.class }, (p, m, a) ->
                {
                    if (m.getName().equals("compare"))
                    {
                        return ((String) a[0]).length() - ((String) a[1]).length();
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        List<String> words = new ArrayList<>(List.of("ccc", "a", "bb", "dddd"));
        Collections.sort(words, byLength);
        System.out.println("sorted by proxy comparator " + words);

        @SuppressWarnings("deprecation")
        Class<?> pc1 = Proxy.getProxyClass(L, Wide.class);
        @SuppressWarnings("deprecation")
        Class<?> pc2 = Proxy.getProxyClass(L, Wide.class);
        System.out.println("proxy class cached " + (pc1 == pc2) + " same as instance's " + (pc1 == w.getClass()));
        System.out.println("isProxyClass(Comparator proxy) " + Proxy.isProxyClass(byLength.getClass()));

        Wide w2 = (Wide) Proxy.newProxyInstance(L, new Class<?>[] { Wide.class }, new Echo());
        Map<Object, String> map = new HashMap<>();
        map.put(w, "first");
        map.put(w2, "second");
        System.out.println("map size " + map.size() + " value " + map.get(w));
        System.out.println("not an interface -> " + tryMake(String.class));
        System.out.println("repeated -> " + tryMake(Wide.class, Wide.class));
        System.out.println("ProxyEdgeProbe done");
    }

    static String tryMake(Class<?>... is)
    {
        try
        {
            Proxy.newProxyInstance(L, is, (p, m, a) -> null);
            return "made";
        }
        catch (IllegalArgumentException e)
        {
            return "IllegalArgumentException";
        }
    }
}
