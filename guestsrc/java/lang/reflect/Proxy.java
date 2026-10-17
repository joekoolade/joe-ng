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
package java.lang.reflect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code java.lang.reflect.Proxy} for joe-ng: dynamic proxy classes and instances, with stock's public contract.
 *
 * <p>Stock GENERATES a proxy class's bytecode ({@code ProxyGenerator}, over the {@code java.lang.classfile} API)
 * and defines it through the JVM. Here the VM SYNTHESISES the class instead, the way it builds a lambda or an
 * annotation instance ({@code Loader.proxyType}): a Type whose superclass is this class, whose itable names the
 * proxied interfaces, and whose every method -- and {@code equals}/{@code hashCode}/{@code toString} -- is a
 * thunk into {@code jdk.internal.misc.MetalProxies.dispatch}, which calls {@link #h}. What a caller sees is
 * stock's: the handler gets the {@link Method} of the interface (or of {@code Object}) and the boxed arguments,
 * a default method is routed to the handler like any other, and the instance is an instance of each interface
 * and of {@code Proxy}.
 *
 * <p>Difference, stated: the {@code loader} argument is not consulted -- this VM has one class namespace. Proxy
 * classes are cached by their interface list, as stock caches them, and {@link InvocationHandler#invokeDefault}
 * runs the default body directly where stock goes through a method handle.
 */
public class Proxy implements java.io.Serializable
{
    @java.io.Serial
    private static final long serialVersionUID = -2222568056686623797L;

    /** The invocation handler for this proxy instance. */
    @SuppressWarnings("serial")
    protected InvocationHandler h;

    /** Proxy class -> the interfaces it was made for, in the order given. Also the isProxyClass registry. */
    private static final Map<Class<?>, Class<?>[]> PROXY_INTERFACES = new ConcurrentHashMap<>();

    /** Interface list -> its proxy class, so one list always answers the same class, as stock's cache does. */
    private static final Map<List<Class<?>>, Class<?>> PROXY_CLASSES = new ConcurrentHashMap<>();

    /**
     * Every {@link Method} a proxy thunk names. A thunk carries the Method's ADDRESS in its code, and code is not a
     * GC root; this list is what keeps each of them alive (the collector does not move objects).
     */
    private static final List<Object> THUNK_METHODS = new ArrayList<>();

    private Proxy()
    {
    }

    /** Stock's: constructs a new {@code Proxy} instance with the given invocation handler. */
    protected Proxy(InvocationHandler h)
    {
        Objects.requireNonNull(h);
        this.h = h;
    }

    /** Stock's (deprecated): the proxy class for the given interfaces. */
    @Deprecated
    public static Class<?> getProxyClass(ClassLoader loader, Class<?>... interfaces) throws IllegalArgumentException
    {
        return proxyClassFor(interfaces);
    }

    /** Stock's: a proxy instance for the given interfaces that dispatches method invocations to {@code h}. */
    public static Object newProxyInstance(ClassLoader loader, Class<?>[] interfaces, InvocationHandler h)
    {
        Objects.requireNonNull(h);
        Class<?> pc = proxyClassFor(interfaces);
        Object o = proxyAlloc0(pc);
        if (o == null)
        {
            throw new InternalError("joe-ng: could not allocate a proxy instance");
        }
        ((Proxy) o).h = h;
        return o;
    }

    /** Stock's: true if and only if {@code cl} was made by {@link #getProxyClass} or {@link #newProxyInstance}. */
    public static boolean isProxyClass(Class<?> cl)
    {
        return Proxy.class.isAssignableFrom(cl) && PROXY_INTERFACES.containsKey(cl);
    }

    /** Stock's: the invocation handler of a proxy instance. */
    public static InvocationHandler getInvocationHandler(Object proxy) throws IllegalArgumentException
    {
        if (!isProxyClass(proxy.getClass()))
        {
            throw new IllegalArgumentException("not a proxy instance");
        }
        return ((Proxy) proxy).h;
    }

    /**
     * Stock's entry for {@link InvocationHandler#invokeDefault}, with stock's checks: a proxy instance, a default
     * method, the caller's access to it, and a method the proxy class really inherits from one of its proxy
     * interfaces (not one another proxy interface overrides). Stock then calls the default body through a method
     * handle; here {@link Method#invokeSpecial} calls it directly -- the same {@code invokespecial}, with the
     * same unwrapped exception. An argument of the wrong type is an {@code IllegalArgumentException}, as stock's
     * CCE/NPE from the handle's {@code asType} become one.
     */
    static Object invokeDefault(Object proxy, Method method, Object[] args, Class<?> caller) throws Throwable
    {
        if (!Proxy.isProxyClass(proxy.getClass()))
        {
            throw new IllegalArgumentException("'proxy' is not a proxy instance");
        }
        if (!method.isDefault())
        {
            throw new IllegalArgumentException("\"" + method + "\" is not a default method");
        }
        Class<?> proxyClass = proxy.getClass();
        if (caller != null)
        {
            Class<?> intf = method.getDeclaringClass();
            if (!AccessibleObject.verifyMemberAccess(caller, intf, proxyClass, method.getModifiers()))
            {
                throw new IllegalAccessException(caller.getName() + " cannot access a member of " + intf
                        + " with modifiers \"" + Modifier.toString(method.getModifiers()) + "\"");
            }
        }
        findProxyInterfaceOrElseThrow(proxyClass, method);
        Object[] params = args != null ? args : new Object[0];
        Class<?>[] pt = method.getParameterTypes();
        int n = Math.min(pt.length, params.length);
        for (int i = 0; i < n; i++)
        {
            Object a = params[i];
            if (!pt[i].isPrimitive() && a != null && !pt[i].isInstance(a))
            {
                // stock's asType casts each reference argument (Class.cast), and its CCE becomes this IAE
                throw new IllegalArgumentException("Cannot cast " + a.getClass().getName() + " to "
                        + pt[i].getName());
            }
        }
        try
        {
            return method.invokeSpecial(proxy, params);
        }
        catch (ClassCastException | NullPointerException e)
        {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Stock's: the first proxy interface that declares {@code method} directly or inherits it, breadth-first; an
     * IllegalArgumentException when none does or when another interface overrides it.
     */
    private static Class<?> findProxyInterfaceOrElseThrow(Class<?> proxyClass, Method method)
    {
        Class<?> declaringClass = method.getDeclaringClass();
        if (!declaringClass.isInterface())
        {
            throw new IllegalArgumentException("\"" + method + "\" is not a method declared in the proxy class");
        }
        List<Class<?>> proxyInterfaces = Arrays.asList(proxyClass.getInterfaces());
        if (proxyInterfaces.contains(declaringClass))
        {
            return declaringClass;
        }
        java.util.Deque<Class<?>> deque = new java.util.ArrayDeque<>();
        java.util.Set<Class<?>> visited = new java.util.HashSet<>();
        boolean indirectMethodRef = false;
        for (Class<?> proxyIntf : proxyInterfaces)
        {
            visited.add(proxyIntf);
            deque.add(proxyIntf);
            Class<?> c;
            while ((c = deque.poll()) != null)
            {
                if (c == declaringClass)
                {
                    try
                    {
                        Method m = proxyIntf.getMethod(method.getName(), method.getParameterTypes());
                        if (m.getDeclaringClass() == declaringClass)
                        {
                            return proxyIntf;
                        }
                        indirectMethodRef = true;
                    }
                    catch (NoSuchMethodException e)
                    {
                    }
                    continue;
                }
                visited.add(c);
                for (Class<?> superIntf : c.getInterfaces())
                {
                    if (!visited.contains(superIntf) && !deque.contains(superIntf))
                    {
                        if (superIntf == declaringClass)
                        {
                            deque.addFirst(superIntf);
                        }
                        else
                        {
                            deque.add(superIntf);
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("\"" + method + (indirectMethodRef
                ? "\" is overridden directly or indirectly by the proxy interfaces"
                : "\" is not a method declared in the proxy class"));
    }

    /**
     * Overlay-only, for {@code Class.getInterfaces}: the interfaces proxy class {@code c} was made for, or null when
     * it is not a proxy class. A synthesised class has no classfile to read them from.
     */
    public static Class<?>[] proxyInterfaces(Class<?> c)
    {
        Class<?>[] is = PROXY_INTERFACES.get(c);
        return is == null ? null : is.clone();
    }

    /** The proxy class for {@code interfaces}, made on first request: stock's checks, then the VM's synthesis. */
    private static Class<?> proxyClassFor(Class<?>[] interfaces)
    {
        Class<?>[] is = interfaces.clone();
        if (is.length > 65535)
        {
            throw new IllegalArgumentException("interface limit exceeded: " + is.length);
        }
        LinkedHashSet<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> c : is)
        {
            Objects.requireNonNull(c);
            if (!c.isInterface())
            {
                throw new IllegalArgumentException(c.getName() + " is not an interface");
            }
            if (!seen.add(c))
            {
                throw new IllegalArgumentException("repeated interface: " + c.getName());
            }
        }
        List<Class<?>> key = List.of(is);
        Class<?> pc = PROXY_CLASSES.get(key);
        if (pc != null)
        {
            return pc;
        }
        synchronized (PROXY_CLASSES)
        {
            pc = PROXY_CLASSES.get(key);
            if (pc != null)
            {
                return pc;
            }
            pc = defineProxyClass(is);
            PROXY_INTERFACES.put(pc, is);
            PROXY_CLASSES.put(key, pc);
            return pc;
        }
    }

    /**
     * Hand the VM the interface closure and, for every non-static method in it, the Method with its name and
     * descriptor: the VM gives each itable slot the first method that matches it, so the given interfaces come
     * first and a method's declaring interface is what the handler sees, as in stock.
     */
    private static Class<?> defineProxyClass(Class<?>[] is)
    {
        LinkedHashSet<Class<?>> closure = new LinkedHashSet<>();
        for (Class<?> c : is)
        {
            addWithSupers(c, closure);
        }
        List<Method> ms = new ArrayList<>();
        for (Class<?> c : closure)
        {
            for (Method m : c.getDeclaredMethods())
            {
                if (!Modifier.isStatic(m.getModifiers()))
                {
                    ms.add(m);
                }
            }
        }
        Object[] methods = ms.toArray();
        Object[] names = new Object[methods.length];
        Object[] descs = new Object[methods.length];
        for (int i = 0; i < methods.length; i++)
        {
            Method m = (Method) methods[i];
            names[i] = jdk.internal.misc.MetalNames.utf8(m.getName());
            descs[i] = jdk.internal.misc.MetalNames.utf8(m.descriptor());
        }
        Object[] objectMethods;
        try
        {
            objectMethods = new Object[] { Object.class.getMethod("hashCode"),
                    Object.class.getMethod("equals", Object.class), Object.class.getMethod("toString") };
        }
        catch (NoSuchMethodException e)
        {
            throw new InternalError(e);
        }
        synchronized (THUNK_METHODS)
        {
            THUNK_METHODS.addAll(Arrays.asList(methods));
            THUNK_METHODS.addAll(Arrays.asList(objectMethods));
        }
        Class<?> pc = proxyType0(closure.toArray(new Class<?>[0]), methods, names, descs, objectMethods);
        if (pc == null)
        {
            throw new InternalError("joe-ng: the VM could not build a proxy class for " + Arrays.toString(is));
        }
        return pc;
    }

    private static void addWithSupers(Class<?> c, LinkedHashSet<Class<?>> out)
    {
        if (out.add(c))
        {
            for (Class<?> s : c.getInterfaces())
            {
                addWithSupers(s, out);
            }
        }
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.proxyType}): build a proxy class; see Loader. */
    private static native Class<?> proxyType0(Class<?>[] interfaces, Object[] methods, Object[] names,
            Object[] descs, Object[] objectMethods);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.proxyAlloc}): a zeroed instance of a proxy class. */
    private static native Object proxyAlloc0(Class<?> proxyClass);
}
