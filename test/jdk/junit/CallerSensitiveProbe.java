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
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * A method handle to an @CallerSensitive method must behave as if called FROM the lookup class. HotSpot's
 * MemberName resolution flags such a method CALLER_SENSITIVE, and Lookup then binds the handle to its caller
 * (MethodHandleImpl.bindCaller, through an injected invoker that is a nestmate of the lookup class). Without the
 * flag the method's getCallerClass sees whatever frame the LambdaForm machinery left on top.
 */
public final class CallerSensitiveProbe
{
    static final class Target
    {
        static int plain()
        {
            return 1;
        }
    }

    public static void main(String[] args) throws Throwable
    {
        MethodHandles.Lookup l = MethodHandles.lookup();
        arm("lookup() through a handle", () ->
        {
            MethodHandle mh = l.findStatic(MethodHandles.class, "lookup", MethodType.methodType(MethodHandles.Lookup.class));
            MethodHandles.Lookup got = (MethodHandles.Lookup) mh.invokeExact();
            return got.lookupClass().getName() + " full=" + ((got.lookupModes() & MethodHandles.Lookup.PRIVATE) != 0);
        });
        arm("Class.forName through a handle", () ->
        {
            MethodHandle mh = l.findStatic(Class.class, "forName", MethodType.methodType(Class.class, String.class));
            Class<?> c = (Class<?>) mh.invokeExact("CallerSensitiveProbe$Target");
            return c.getName();
        });
        arm("revealDirect refuses another lookup's CS handle", () ->
        {
            MethodHandle mh = l.findStatic(Class.class, "forName", MethodType.methodType(Class.class, String.class));
            try
            {
                MethodHandleInfo i = MethodHandles.publicLookup().revealDirect(mh);
                return "cracked " + i.getName();
            }
            catch (IllegalArgumentException e)
            {
                return "IllegalArgumentException";
            }
        });
        arm("revealDirect by its own lookup", () ->
                l.revealDirect(l.findStatic(Class.class, "forName", MethodType.methodType(Class.class, String.class))).toString());
        arm("plain method unaffected", () ->
                String.valueOf((int) l.findStatic(Target.class, "plain", MethodType.methodType(int.class)).invokeExact()));
        System.out.println("CallerSensitiveProbe done");
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
