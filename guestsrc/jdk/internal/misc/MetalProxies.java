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
package jdk.internal.misc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import magic.Magic;

/**
 * The one entry every proxy thunk calls ({@code Loader.proxyThunk}): box the arguments, call the proxy's
 * {@link InvocationHandler}, unbox the result. What stock's generated proxy method bodies do, in Java.
 *
 * <p>The thunk passes the proxy, the {@link Method} it stands for, and the address of the frame it spilled
 * {@code x1..x15} into -- every register an argument can arrive in. Each argument is its raw 64-bit register value
 * (a float or double as its bits, as this VM passes them); past fifteen arguments {@code x15} holds a pointer to
 * the rest, the VM's convention for more than sixteen counting the receiver.
 *
 * <p>Stock's exception rule: a {@code RuntimeException}, an {@code Error} or a checked exception the method
 * declares propagates as itself; any other checked exception is wrapped in {@link UndeclaredThrowableException}.
 * Stock's return rule: {@code null} for a primitive result is a {@code NullPointerException}, and a value of the
 * wrong type a {@code ClassCastException}.
 */
public final class MetalProxies
{
    private MetalProxies()
    {
    }

    /** The thunk's call: {@code proxy} the receiver, {@code method} a Method, {@code args} the spill frame. */
    public static long dispatch(Object proxy, Object method, long args) throws Throwable
    {
        Method m = (Method) method;
        InvocationHandler h = Proxy.getInvocationHandler(proxy);
        Class<?>[] pt = m.getParameterTypes();
        Object[] a = null;
        if (pt.length > 0)
        {
            a = new Object[pt.length];
            for (int i = 0; i < pt.length; i++)
            {
                a[i] = box(pt[i], raw(args, i, pt.length));
            }
        }
        Object r;
        try
        {
            r = h.invoke(proxy, m, a);
        }
        catch (RuntimeException | Error e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            for (Class<?> ex : m.getExceptionTypes())
            {
                if (ex.isInstance(t))
                {
                    throw t;
                }
            }
            throw new UndeclaredThrowableException(t);
        }
        return unbox(m.getReturnType(), r);
    }

    /** Argument {@code i} of {@code n}: register x(1+i), or past fifteen arguments, through x15's pointer. */
    private static long raw(long args, int i, int n)
    {
        if (n <= 15 || i < 14)
        {
            return Magic.load64(args + 8L * i);
        }
        return Magic.load64(Magic.load64(args + 8L * 14) + 8L * (i - 14));
    }

    private static Object box(Class<?> t, long raw)
    {
        if (!t.isPrimitive())
        {
            return Magic.fromAddr(raw);
        }
        if (t == int.class)
        {
            return Integer.valueOf((int) raw);
        }
        if (t == long.class)
        {
            return Long.valueOf(raw);
        }
        if (t == boolean.class)
        {
            return Boolean.valueOf((int) raw != 0);
        }
        if (t == double.class)
        {
            return Double.valueOf(Double.longBitsToDouble(raw));
        }
        if (t == float.class)
        {
            return Float.valueOf(Float.intBitsToFloat((int) raw));
        }
        if (t == char.class)
        {
            return Character.valueOf((char) raw);
        }
        if (t == byte.class)
        {
            return Byte.valueOf((byte) raw);
        }
        return Short.valueOf((short) raw);
    }

    private static long unbox(Class<?> t, Object r)
    {
        if (t == void.class)
        {
            return 0L;
        }
        if (!t.isPrimitive())
        {
            if (r != null && !t.isInstance(r))
            {
                throw new ClassCastException(r.getClass().getName() + " cannot be cast to " + t.getName());
            }
            return r == null ? 0L : Magic.addrOf(r);
        }
        if (r == null)
        {
            throw new NullPointerException("null returned for a primitive " + t.getName() + " result");
        }
        if (t == int.class)
        {
            return (Integer) r;
        }
        if (t == long.class)
        {
            return (Long) r;
        }
        if (t == boolean.class)
        {
            return ((Boolean) r) ? 1L : 0L;
        }
        if (t == double.class)
        {
            return Double.doubleToRawLongBits((Double) r);
        }
        if (t == float.class)
        {
            return Float.floatToRawIntBits((Float) r) & 0xFFFFFFFFL;
        }
        if (t == char.class)
        {
            return (Character) r;
        }
        if (t == byte.class)
        {
            return (Byte) r;
        }
        return (Short) r;
    }
}
