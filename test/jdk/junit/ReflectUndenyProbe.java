/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-30
 */

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.MalformedParametersException;
import java.lang.reflect.ReflectPermission;

/**
 * The last eight java/lang/reflect classes left behind the package's deny prefix, which is gone now: the
 * Annotated*Type interfaces, InaccessibleObjectException, MalformedParametersException and ReflectPermission.
 * While denied, a call into one trap-wired the task and a class literal or instanceof read null/false. Each arm
 * runs on its own so one failure cannot hide the others, and prints what it got.
 */
public class ReflectUndenyProbe
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
            got = "threw " + t.getClass().getName();
        }
        System.out.println(what + ": " + got);
    }

    public static void main(String[] args)
    {
        report("ReflectPermission", () ->
        {
            ReflectPermission p = new ReflectPermission("suppressAccessChecks");
            return p.getName() + " implies=" + p.implies(new ReflectPermission("suppressAccessChecks"));
        });
        report("InaccessibleObjectException", () ->
        {
            RuntimeException e = new InaccessibleObjectException("closed");
            return e.getMessage() + " isRuntime=" + (e instanceof RuntimeException);
        });
        report("MalformedParametersException", () ->
        {
            RuntimeException e = new MalformedParametersException("bad");
            return e.getMessage() + " isRuntime=" + (e instanceof RuntimeException);
        });
        report("class literals", () -> AnnotatedType.class.getName() + " " + AnnotatedArrayType.class.isInterface()
                + " " + AnnotatedParameterizedType.class.getInterfaces()[0].getSimpleName());
        report("catch by type", () ->
        {
            try
            {
                throw new InaccessibleObjectException("x");
            }
            catch (InaccessibleObjectException e)
            {
                return "caught " + e.getMessage();
            }
        });
        System.out.println("ReflectUndenyProbe done");
    }
}
