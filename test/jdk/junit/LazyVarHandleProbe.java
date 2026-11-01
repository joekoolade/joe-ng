/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-01
 */

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A static-field VarHandle looked up before its class is initialized is a LazyInitializingVarHandle: a wrapper
 * whose access modes are the TARGET handle's generated accessors, and which runs the class's initializer on first
 * access. SSLContext.getDefault is the stock case (a handle made in its own <clinit>, then compareAndSet(null, x));
 * joe-ng resolved the access mode against the wrapper's class, found nothing and trapped. The arms: every access
 * shape on a handle made inside its own <clinit>, two wrappers of DIFFERENT field types through the same call
 * sites, and a handle on another class's static whose first VarHandle access must run that class's initializer.
 * Each arm runs on its own and prints what it got.
 */
public class LazyVarHandleProbe
{
    interface Arm
    {
        String run() throws Throwable;
    }

    static void report(String what, Arm arm)
    {
        String got;
        try
        {
            got = arm.run();
        }
        catch (Throwable t)
        {
            got = "threw " + t.getClass().getName() + " " + t.getMessage();
        }
        System.out.println(what + ": " + got);
    }

    /** SSLContext's shape: a volatile static and its handle, made in the same <clinit>. */
    static class Holder
    {
        static volatile String value;
        static volatile long count;
        static final VarHandle VALUE;
        static final VarHandle COUNT;

        static
        {
            try
            {
                VALUE = MethodHandles.lookup().findStaticVarHandle(Holder.class, "value", String.class);
                COUNT = MethodHandles.lookup().findStaticVarHandle(Holder.class, "count", long.class);
            }
            catch (ReflectiveOperationException e)
            {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /** Looked up from OUTSIDE before it is initialized: the first access through the handle must run this. */
    static class Late
    {
        static int seen = 7;
        static String log = "late-init";
    }

    /** A second never-initialized class, for every access shape through handles looked up from outside. */
    static class Outside
    {
        static volatile String value = "start";
        static volatile long count = 1;
        static final String FIXED = String.valueOf("fixed");
    }

    static VarHandle outside(String name, Class<?> type) throws ReflectiveOperationException
    {
        return MethodHandles.lookup().findStaticVarHandle(Outside.class, name, type);
    }

    static String cas(VarHandle vh, Object expect, Object update)
    {
        return String.valueOf(vh.compareAndSet(expect, update));
    }

    public static void main(String[] args) throws Throwable
    {
        report("compareAndSet null->a", () -> cas(Holder.VALUE, null, "a") + " now=" + Holder.value);
        report("compareAndSet null->b (fails)", () -> cas(Holder.VALUE, null, "b") + " now=" + Holder.value);
        report("compareAndSet a->c", () -> cas(Holder.VALUE, "a", "c") + " now=" + Holder.value);
        report("get", () -> (String) Holder.VALUE.get());
        report("getVolatile", () -> (String) Holder.VALUE.getVolatile());
        report("set", () ->
        {
            Holder.VALUE.set("d");
            return Holder.value;
        });
        report("getAndSet", () -> (String) Holder.VALUE.getAndSet("e") + " now=" + Holder.value);
        report("long getAndAdd", () ->
        {
            long old = (long) Holder.COUNT.getAndAdd(5L);
            return old + " now=" + Holder.count;
        });
        report("long compareAndSet", () -> Holder.COUNT.compareAndSet(5L, 9L) + " now=" + Holder.count);
        report("other class's static, first access runs its init", () ->
        {
            VarHandle seen = MethodHandles.lookup().findStaticVarHandle(Late.class, "seen", int.class);
            VarHandle log = MethodHandles.lookup().findStaticVarHandle(Late.class, "log", String.class);
            int v = (int) seen.get();
            return v + " " + (String) log.get();
        });
        report("other class's static, compareAndSet", () ->
        {
            VarHandle log = MethodHandles.lookup().findStaticVarHandle(Late.class, "log", String.class);
            return cas(log, "late-init", "swapped") + " now=" + Late.log;
        });
        report("outside: compareAndSet start->a", () -> cas(outside("value", String.class), "start", "a")
                + " now=" + Outside.value);
        report("outside: compareAndSet fails", () -> cas(outside("value", String.class), "start", "b")
                + " now=" + Outside.value);
        report("outside: getVolatile", () -> (String) outside("value", String.class).getVolatile());
        report("outside: set", () ->
        {
            outside("value", String.class).set("c");
            return Outside.value;
        });
        report("outside: getAndSet", () -> (String) outside("value", String.class).getAndSet("d")
                + " now=" + Outside.value);
        report("outside: long getAndAdd", () ->
        {
            long old = (long) outside("count", long.class).getAndAdd(5L);
            return old + " now=" + Outside.count;
        });
        report("outside: long compareAndSet", () -> outside("count", long.class).compareAndSet(6L, 9L)
                + " now=" + Outside.count);
        report("outside: final static get", () -> (String) outside("FIXED", String.class).get());
        report("outside: final static set refused", () ->
        {
            outside("FIXED", String.class).set("no");
            return "stored " + Outside.FIXED;
        });
        System.out.println("LazyVarHandleProbe done");
    }
}
