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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * jdk/internal/reflect/ is no longer denied. Everything in it except Reflection and ReflectionFactory had been
 * trap-wired: the accessor interfaces and classes, the @CallerSensitive annotations and ConstantPool. The probe
 * reaches them by NAME (Class.forName), which needs no --add-exports, so the host JVM runs it unchanged. The
 * adversarial arm is the annotation: Class.forName(String) carries @CallerSensitive, and while the annotation type
 * was denied, isAnnotationPresent had no type to match. Each arm runs on its own and prints what it got.
 */
public class InternalReflectProbe
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
        report("CallerSensitive", () ->
        {
            Class<?> c = Class.forName("jdk.internal.reflect.CallerSensitive");
            return c.getName() + " isAnnotation=" + c.isAnnotation();
        });
        report("forName is @CallerSensitive", () ->
        {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> cs =
                    (Class<? extends Annotation>) Class.forName("jdk.internal.reflect.CallerSensitive");
            Method m = Class.class.getMethod("forName", String.class);
            return "present=" + m.isAnnotationPresent(cs);
        });
        report("accessor interfaces", () ->
        {
            Class<?> ma = Class.forName("jdk.internal.reflect.MethodAccessor");
            Class<?> ca = Class.forName("jdk.internal.reflect.ConstructorAccessor");
            Class<?> fa = Class.forName("jdk.internal.reflect.FieldAccessor");
            return ma.isInterface() + " " + ca.isInterface() + " " + fa.isInterface();
        });
        report("accessor hierarchy", () ->
        {
            Class<?> impl = Class.forName("jdk.internal.reflect.MethodHandleIntegerFieldAccessorImpl");
            Class<?> fa = Class.forName("jdk.internal.reflect.FieldAccessor");
            return impl.getSuperclass().getSimpleName() + " " + impl.getSuperclass().getSuperclass().getSimpleName()
                    + " isFieldAccessor=" + fa.isAssignableFrom(impl);
        });
        report("ConstantPool natives", () ->
        {
            Class<?> cp = Class.forName("jdk.internal.reflect.ConstantPool");
            int natives = 0;
            for (Method m : cp.getDeclaredMethods())
            {
                if (Modifier.isNative(m.getModifiers()))
                {
                    natives += 1;
                }
            }
            return "native methods=" + natives;
        });
        System.out.println("InternalReflectProbe done");
    }
}
