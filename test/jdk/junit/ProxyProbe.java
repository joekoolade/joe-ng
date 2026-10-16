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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code java.lang.reflect.Proxy}: a proxy for one and for two interfaces whose handler records every call --
 * the method's name, declaring interface and boxed arguments -- and answers by name. Argument kinds: none, an
 * int, a long, a double, a reference, and four mixed; return kinds: void, int, boolean, double, String and an
 * interface. A checked exception the handler throws arrives as itself; a RuntimeException likewise. Then
 * {@code equals}/{@code hashCode}/{@code toString} through the handler, {@code isProxyClass},
 * {@code getInvocationHandler}, {@code instanceof} each interface, and a default method -- which a proxy routes
 * to the handler like any other. Prints values only; the host diff is the gate.
 */
public class ProxyProbe
{
    interface Calc
    {
        int add(int a, int b);

        long scale(long x);

        double half(double d);

        String greet(String who);

        void ping();

        boolean flag();

        String mix(int i, long l, double d, String s);

        Calc self();

        default String hello()
        {
            return "default:" + greet("d");
        }
    }

    interface Named
    {
        String name() throws Exception;
    }

    static final List<String> LOG = new ArrayList<>();

    static class Handler implements InvocationHandler
    {
        Object self;

        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable
        {
            LOG.add(m.getDeclaringClass().getSimpleName() + "." + m.getName() + Arrays.toString(args));
            switch (m.getName())
            {
                case "add":
                    return (Integer) args[0] + (Integer) args[1];
                case "scale":
                    return (Long) args[0] * 10L;
                case "half":
                    return (Double) args[0] / 2;
                case "greet":
                    return "hi " + args[0];
                case "ping":
                    return null;
                case "flag":
                    return Boolean.TRUE;
                case "mix":
                    return args[0] + "/" + args[1] + "/" + args[2] + "/" + args[3];
                case "self":
                    return proxy;
                case "hello":
                    return "handler-hello";
                case "name":
                    if (LOG.size() > 30)
                    {
                        throw new java.io.IOException("checked");
                    }
                    return "named";
                case "hashCode":
                    return 4242;
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "ProxyOf(Calc)";
                default:
                    throw new IllegalStateException("unexpected " + m.getName());
            }
        }
    }

    public static void main(String[] args) throws Exception
    {
        Handler h = new Handler();
        Calc c = (Calc) Proxy.newProxyInstance(ProxyProbe.class.getClassLoader(), new Class<?>[] { Calc.class,
                Named.class }, h);
        System.out.println("add " + c.add(2, 3));
        System.out.println("scale " + c.scale(1L << 33));
        System.out.println("half " + c.half(5.0));
        System.out.println("greet " + c.greet("bob"));
        c.ping();
        System.out.println("flag " + c.flag());
        System.out.println("mix " + c.mix(1, 2L, 3.5, "four"));
        System.out.println("self same " + (c.self() == c));
        System.out.println("hello " + c.hello());
        System.out.println("name " + ((Named) c).name());
        System.out.println("hashCode " + c.hashCode());
        System.out.println("equals self " + c.equals(c) + " other " + c.equals("x"));
        System.out.println("toString " + c);
        System.out.println("isProxyClass " + Proxy.isProxyClass(c.getClass()) + " String " + Proxy.isProxyClass(
                String.class));
        System.out.println("handler same " + (Proxy.getInvocationHandler(c) == h));
        System.out.println("instanceof Calc " + (c instanceof Calc) + " Named " + (c instanceof Named)
                + " Proxy " + (c instanceof Proxy));
        System.out.println("interfaces " + Arrays.toString(c.getClass().getInterfaces()));
        while (LOG.size() <= 30)
        {
            LOG.add("pad");
        }
        try
        {
            ((Named) c).name();
            System.out.println("no exception");
        }
        catch (java.io.IOException e)
        {
            System.out.println("checked arrived: " + e.getMessage());
        }
        Runnable r = (Runnable) Proxy.newProxyInstance(null, new Class<?>[] { Runnable.class }, (p, m, a) ->
        {
            if (m.getName().equals("run"))
            {
                throw new UnsupportedOperationException("from handler");
            }
            return null;
        });
        try
        {
            r.run();
        }
        catch (UnsupportedOperationException e)
        {
            System.out.println("runtime arrived: " + e.getMessage());
        }
        for (int i = 0; i < 11; i++)
        {
            System.out.println("log " + LOG.get(i));
        }
        System.out.println("ProxyProbe done");
    }
}
