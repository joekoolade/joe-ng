/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-26
 */

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * The core java.lang.invoke API, arm by arm, against the host: the java.lang.invoke arc's first measurement.
 * Every arm is guarded so a failure prints and the probe carries on; a DENYLIST TRAP halts the program, so the
 * arms are ordered from the plainest (MethodType) to the most machinery-heavy (combinators).
 */
public final class MethodHandleProbe
{
    static int twice(int x)
    {
        return x * 2;
    }

    String greet(String who)
    {
        return "hi " + who;
    }

    int field = 7;

    public static void main(String[] args)
    {
        MethodHandles.Lookup l = MethodHandles.lookup();
        arm("MethodType", () -> MethodType.methodType(int.class, int.class).toString());
        arm("MethodType generic", () -> MethodType.genericMethodType(2).toString());
        arm("findStatic invokeExact", () ->
        {
            MethodHandle mh = l.findStatic(MethodHandleProbe.class, "twice", MethodType.methodType(int.class, int.class));
            int r = (int) mh.invokeExact(21);
            return String.valueOf(r);
        });
        arm("findVirtual invoke", () ->
        {
            MethodHandle mh = l.findVirtual(MethodHandleProbe.class, "greet", MethodType.methodType(String.class, String.class));
            return (String) mh.invoke(new MethodHandleProbe(), "there");
        });
        arm("findVirtual String.length", () ->
        {
            MethodHandle mh = l.findVirtual(String.class, "length", MethodType.methodType(int.class));
            return String.valueOf((int) mh.invokeExact("abcde"));
        });
        arm("findConstructor", () ->
        {
            MethodHandle mh = l.findConstructor(ArrayList.class, MethodType.methodType(void.class));
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) mh.invoke();
            list.add("x");
            return list.toString();
        });
        arm("findGetter", () ->
        {
            MethodHandle mh = l.findGetter(MethodHandleProbe.class, "field", int.class);
            return String.valueOf((int) mh.invoke(new MethodHandleProbe()));
        });
        arm("bindTo", () ->
        {
            MethodHandle mh = l.findVirtual(MethodHandleProbe.class, "greet", MethodType.methodType(String.class, String.class))
                    .bindTo(new MethodHandleProbe());
            return (String) mh.invoke("bound");
        });
        arm("invokeWithArguments", () ->
        {
            MethodHandle mh = l.findStatic(Math.class, "max", MethodType.methodType(int.class, int.class, int.class));
            return String.valueOf(mh.invokeWithArguments(3, 9));
        });
        arm("insertArguments", () ->
        {
            MethodHandle mh = MethodHandles.insertArguments(
                    l.findStatic(Math.class, "max", MethodType.methodType(int.class, int.class, int.class)), 0, 5);
            return String.valueOf((int) mh.invoke(2));
        });
        arm("asType", () ->
        {
            MethodHandle mh = l.findStatic(MethodHandleProbe.class, "twice", MethodType.methodType(int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class));
            return String.valueOf(mh.invoke(Integer.valueOf(4)));
        });
        arm("unreflect", () ->
        {
            MethodHandle mh = l.unreflect(MethodHandleProbe.class.getDeclaredMethod("twice", int.class));
            return String.valueOf((int) mh.invoke(8));
        });
        arm("type()", () -> l.findStatic(MethodHandleProbe.class, "twice",
                MethodType.methodType(int.class, int.class)).type().toString());
        System.out.println("MethodHandleProbe done");
    }

    private interface Arm
    {
        String run() throws Throwable;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = a.run();
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName() + ": " + t.getMessage();
        }
        System.out.println(what + " = " + r);
    }
}
