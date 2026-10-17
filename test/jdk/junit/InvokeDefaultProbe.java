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

/**
 * {@code InvocationHandler.invokeDefault}: a default body run on a proxy, which calls BACK into the proxy (so the
 * body must run once, not re-enter the handler); primitive arguments and returns; a declared checked exception
 * from the body arriving as itself; a default INHERITED from a superinterface; a default OVERRIDDEN by another
 * proxy interface (refused); a non-default method, a non-proxy and a wrong-typed argument (each refused); and a
 * null args array for a zero-parameter default. Prints values only; the host diff is the gate.
 */
public class InvokeDefaultProbe
{
    static final ClassLoader L = InvokeDefaultProbe.class.getClassLoader();

    public interface Greeter
    {
        String name();

        default String greet(String who)
        {
            return "hello " + who + " from " + name();
        }

        default int twice(int x)
        {
            return 2 * x;
        }

        default long mix(long a, double b, char c)
        {
            return a + (long) b + c;
        }

        default String plain()
        {
            return "plain default";
        }

        default void fail() throws java.io.IOException
        {
            throw new java.io.IOException("from the default body");
        }
    }

    public interface Base
    {
        default String who()
        {
            return "Base";
        }

        default String inherited()
        {
            return "inherited from Base";
        }
    }

    public interface Sub extends Base
    {
        @Override
        default String who()
        {
            return "Sub";
        }
    }

    static final InvocationHandler DEFAULTS = (proxy, m, a) ->
    {
        if (m.isDefault())
        {
            return InvocationHandler.invokeDefault(proxy, m, a);
        }
        if (m.getName().equals("name"))
        {
            return "proxy";
        }
        return m.getName() + " (handler)";
    };

    interface Arm
    {
        Object call() throws Throwable;
    }

    static void arm(String what, Arm c)
    {
        try
        {
            System.out.println(what + " -> " + c.call());
        }
        catch (Throwable t)
        {
            System.out.println(what + " -> " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    public static void main(String[] args) throws Exception
    {
        Greeter g = (Greeter) Proxy.newProxyInstance(L, new Class<?>[] { Greeter.class }, DEFAULTS);
        arm("greet", () -> g.greet("bob"));
        arm("twice", () -> g.twice(21));
        arm("mix", () -> g.mix(1000L, 2.75, 'a'));
        arm("plain", () -> g.plain());
        arm("fail", () ->
        {
            g.fail();
            return "no exception";
        });
        Method plain = Greeter.class.getMethod("plain");
        Method greet = Greeter.class.getMethod("greet", String.class);
        Method name = Greeter.class.getMethod("name");
        arm("null args", () -> InvocationHandler.invokeDefault(g, plain, (Object[]) null));
        arm("non-default", () -> InvocationHandler.invokeDefault(g, name));
        arm("non-proxy", () -> InvocationHandler.invokeDefault("a string", plain));
        arm("wrong arg type", () -> InvocationHandler.invokeDefault(g, greet, 42));

        Sub s = (Sub) Proxy.newProxyInstance(L, new Class<?>[] { Sub.class }, DEFAULTS);
        arm("sub who", () -> s.who());
        arm("sub inherited", () -> s.inherited());
        Method baseWho = Base.class.getMethod("who");
        Method baseInherited = Base.class.getMethod("inherited");
        arm("Base.who on Sub proxy", () -> InvocationHandler.invokeDefault(s, baseWho));
        arm("Base.inherited on Sub proxy", () -> InvocationHandler.invokeDefault(s, baseInherited));
        arm("Greeter.plain on Sub proxy", () -> InvocationHandler.invokeDefault(s, plain));
        System.out.println("InvokeDefaultProbe done");
    }
}
