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

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * invokedynamic and dynamic constants beyond the four bootstraps the JIT lowers itself (string concat, lambdas,
 * record ObjectMethods, class-label typeSwitch). javac reaches the SwitchBootstraps path with constant labels in
 * a pattern switch -- String, Integer and qualified enum constants -- and enumSwitch for an enum switch with
 * {@code case null}; a program or library reaches arbitrary bootstraps with its own bytecode. The last arms
 * build such classes at run time with java.lang.classfile and define them through Lookup.defineClass.
 */
public final class IndyProbe
{
    enum Color
    {
        RED, GREEN, BLUE
    }

    sealed interface Shape permits Sq, Tri
    {
    }

    record Sq(int s) implements Shape
    {
    }

    enum Tri implements Shape
    {
        EQUI, ISO
    }

    static String stringLabels(String s)
    {
        return switch (s)
        {
            case "alpha" -> "first";
            case "beta" -> "second";
            case String x when x.length() > 3 -> "long " + x;
            case String x -> "other " + x;
        };
    }

    static String integerLabels(Integer i)
    {
        return switch (i)
        {
            case 1 -> "one";
            case 2 -> "two";
            case Integer x when x < 0 -> "negative";
            case Integer x -> "int " + x;
        };
    }

    static String enumLabels(Shape sh)
    {
        return switch (sh)
        {
            case Tri.EQUI -> "equilateral";
            case Tri.ISO -> "isosceles";
            case Sq q -> "square " + q.s();
        };
    }

    static String enumWithNull(Color c)
    {
        return switch (c)
        {
            case null -> "null color";
            case RED -> "red";
            case Color x when x == Color.GREEN -> "green";
            case Color x -> "other " + x;
        };
    }

    /** A user bootstrap: binds the call site to String.concat with a fixed suffix. */
    public static CallSite bsm(MethodHandles.Lookup l, String name, MethodType type, String suffix) throws Throwable
    {
        MethodHandle concat = l.findVirtual(String.class, "concat", MethodType.methodType(String.class, String.class));
        return new ConstantCallSite(MethodHandles.insertArguments(concat, 1, suffix).asType(type));
    }

    /** A user condy bootstrap: a constant built from its static arguments. */
    public static Object cbsm(MethodHandles.Lookup l, String name, Class<?> type, int a, int b)
    {
        return name + "=" + (a * b);
    }

    public static void main(String[] args) throws Throwable
    {
        arm("String labels", () -> stringLabels("alpha") + ", " + stringLabels("beta") + ", " + stringLabels("gamma")
                + ", " + stringLabels("xy"));
        arm("Integer labels", () -> integerLabels(1) + ", " + integerLabels(2) + ", " + integerLabels(-5) + ", "
                + integerLabels(9));
        arm("enum constant labels", () -> enumLabels(Tri.EQUI) + ", " + enumLabels(Tri.ISO) + ", " + enumLabels(new Sq(3)));
        arm("enum switch with null", () -> enumWithNull(null) + ", " + enumWithNull(Color.RED) + ", "
                + enumWithNull(Color.GREEN) + ", " + enumWithNull(Color.BLUE));
        MethodHandles.Lookup l = MethodHandles.lookup();
        ClassDesc me = ClassDesc.of("IndyProbe");
        arm("user bootstrap", () ->
        {
            DirectMethodHandleDesc bsm = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, me, "bsm",
                    MethodTypeDesc.of(ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodHandles_Lookup,
                            ConstantDescs.CD_String, ConstantDescs.CD_MethodType, ConstantDescs.CD_String));
            byte[] bytes = ClassFile.of().build(ClassDesc.of("IndyProbe$Gen1"), cb -> cb
                    .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.aload(0)
                                    .invokedynamic(DynamicCallSiteDesc.of(bsm, "glue",
                                            MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String), "!"))
                                    .areturn()));
            Class<?> c = l.defineClass(bytes);
            MethodHandle run = l.findStatic(c, "run", MethodType.methodType(String.class, String.class));
            return (String) run.invokeExact("hi") + " " + (String) run.invokeExact("again");
        });
        arm("ConstantBootstraps condy", () ->
        {
            DynamicConstantDesc<Object> nul = DynamicConstantDesc.ofNamed(ConstantDescs.BSM_NULL_CONSTANT,
                    ConstantDescs.DEFAULT_NAME, ConstantDescs.CD_Object);
            byte[] bytes = ClassFile.of().build(ClassDesc.of("IndyProbe$Gen2"), cb -> cb
                    .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.ldc(nul).areturn()));
            Class<?> c = l.defineClass(bytes);
            return String.valueOf((Object) l.findStatic(c, "get", MethodType.methodType(Object.class)).invokeExact());
        });
        arm("user condy", () ->
        {
            DirectMethodHandleDesc cbsm = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, me, "cbsm",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_MethodHandles_Lookup,
                            ConstantDescs.CD_String, ConstantDescs.CD_Class, ConstantDescs.CD_int, ConstantDescs.CD_int));
            DynamicConstantDesc<Object> k = DynamicConstantDesc.ofNamed(cbsm, "area", ConstantDescs.CD_Object, 6, 7);
            byte[] bytes = ClassFile.of().build(ClassDesc.of("IndyProbe$Gen3"), cb -> cb
                    .withMethodBody("get", MethodTypeDesc.of(ConstantDescs.CD_Object),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.ldc(k).areturn()));
            Class<?> c = l.defineClass(bytes);
            MethodHandle get = l.findStatic(c, "get", MethodType.methodType(Object.class));
            Object a = (Object) get.invokeExact();
            Object b = (Object) get.invokeExact();
            return a + " " + (a == b);
        });
        System.out.println("IndyProbe done");
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
