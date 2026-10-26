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
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * SPUN method handles on metal -- increment 3 of the java.lang.invoke arc: every handle here has a LambdaForm the
 * seed does NOT pregenerate, so stock InvokerBytecodeGenerator builds a class with java.lang.classfile at run time
 * and Lookup.defineHiddenClass defines it (jdk.internal.misc.MetalHiddenClasses).
 *
 * <p>The arms are aimed at the hidden-class mechanics rather than at the combinators' arithmetic: MANY spun
 * classes of one kind (each LambdaForm$MH must be a distinct class, or a later one's statics and self-references
 * would resolve to an earlier one's), the class data a spun initializer reads, isHidden, the public
 * defineHiddenClass with bytes and class data supplied by the PROGRAM, and a spread of combinators whose forms
 * each spin differently -- guards, exception handlers, filters, folds, permutations, collectors and spreaders.
 */
public final class MhSpinProbe
{
    static int add(int a, int b)
    {
        return a + b;
    }

    static boolean isPositive(int x)
    {
        return x > 0;
    }

    static String pos(int x)
    {
        return "pos " + x;
    }

    static String neg(int x)
    {
        return "neg " + x;
    }

    static String risky(String s)
    {
        if (s.isEmpty())
        {
            throw new IllegalArgumentException("empty");
        }
        return s.toUpperCase();
    }

    static String recover(IllegalArgumentException e, String s)
    {
        return "recovered " + e.getMessage();
    }

    static String sum3(int a, int b, int c)
    {
        return a + "+" + b + "+" + c + "=" + (a + b + c);
    }

    static int count(String... xs)
    {
        return xs.length;
    }

    public static void main(String[] args) throws Throwable
    {
        MethodHandles.Lookup l = MethodHandles.lookup();
        Class<?> me = MhSpinProbe.class;
        MethodHandle add = l.findStatic(me, "add", MethodType.methodType(int.class, int.class, int.class));

        arm("many bound handles", () ->
        {
            StringBuilder sb = new StringBuilder();
            int k = 0;
            while (k < 12)
            {
                MethodHandle plusK = MethodHandles.insertArguments(add, 0, k);
                sb.append((int) plusK.invokeExact(100)).append(' ');
                k += 1;
            }
            return sb.toString().trim();
        });
        arm("bound string handles", () ->
        {
            List<String> out = new ArrayList<>();
            for (String w : new String[] { "a", "bb", "ccc" })
            {
                MethodHandle concat = l.findVirtual(String.class, "concat", MethodType.methodType(String.class, String.class));
                out.add((String) concat.bindTo(w).invokeExact("!"));
            }
            return out.toString();
        });
        arm("guardWithTest", () ->
        {
            MethodHandle test = l.findStatic(me, "isPositive", MethodType.methodType(boolean.class, int.class));
            MethodHandle p = l.findStatic(me, "pos", MethodType.methodType(String.class, int.class));
            MethodHandle n = l.findStatic(me, "neg", MethodType.methodType(String.class, int.class));
            MethodHandle g = MethodHandles.guardWithTest(test, p, n);
            return (String) g.invokeExact(5) + ", " + (String) g.invokeExact(-3);
        });
        arm("catchException", () ->
        {
            MethodHandle r = l.findStatic(me, "risky", MethodType.methodType(String.class, String.class));
            MethodHandle h = l.findStatic(me, "recover",
                    MethodType.methodType(String.class, IllegalArgumentException.class, String.class));
            MethodHandle safe = MethodHandles.catchException(r, IllegalArgumentException.class, h);
            return (String) safe.invokeExact("ok") + ", " + (String) safe.invokeExact("");
        });
        arm("filterReturnValue", () ->
        {
            MethodHandle len = l.findVirtual(String.class, "length", MethodType.methodType(int.class));
            MethodHandle plus1 = MethodHandles.insertArguments(add, 1, 1);
            MethodHandle f = MethodHandles.filterReturnValue(len, plus1);
            return f.type() + " " + (int) f.invokeExact("abc");
        });
        arm("filterArguments", () ->
        {
            MethodHandle len = l.findVirtual(String.class, "length", MethodType.methodType(int.class));
            MethodHandle f = MethodHandles.filterArguments(add, 0, len, len);
            return String.valueOf((int) f.invokeExact("abc", "de"));
        });
        arm("permuteArguments", () ->
        {
            MethodHandle s3 = l.findStatic(me, "sum3", MethodType.methodType(String.class, int.class, int.class, int.class));
            MethodHandle p = MethodHandles.permuteArguments(s3, s3.type(), 2, 0, 1);
            return (String) p.invokeExact(1, 2, 3);
        });
        arm("dropArguments", () ->
        {
            MethodHandle d = MethodHandles.dropArguments(add, 1, String.class);
            return String.valueOf((int) d.invokeExact(4, "ignored", 5));
        });
        arm("foldArguments", () ->
        {
            MethodHandle s3 = l.findStatic(me, "sum3", MethodType.methodType(String.class, int.class, int.class, int.class));
            MethodHandle fold = MethodHandles.foldArguments(s3, add);
            return (String) fold.invokeExact(10, 20);
        });
        arm("asCollector", () ->
        {
            MethodHandle c = l.findStatic(me, "count", MethodType.methodType(int.class, String[].class));
            MethodHandle three = c.asCollector(String[].class, 3);
            return String.valueOf((int) three.invokeExact("x", "y", "z"));
        });
        arm("varargs invoke", () ->
        {
            MethodHandle c = l.findStatic(me, "count", MethodType.methodType(int.class, String[].class));
            return c.isVarargsCollector() + " " + (int) c.invoke("p", "q") + " " + (int) c.invoke();
        });
        arm("asSpreader", () ->
        {
            MethodHandle s3 = l.findStatic(me, "sum3", MethodType.methodType(String.class, int.class, int.class, int.class));
            MethodHandle spread = s3.asSpreader(Object[].class, 3);
            return (String) spread.invokeExact(new Object[] { 7, 8, 9 });
        });
        arm("constant/identity", () ->
        {
            MethodHandle k = MethodHandles.constant(String.class, "K");
            MethodHandle i = MethodHandles.identity(int.class);
            return (String) k.invokeExact() + " " + (int) i.invokeExact(77);
        });
        arm("arrayElementGetter", () ->
        {
            MethodHandle g = MethodHandles.arrayElementGetter(int[].class);
            return String.valueOf((int) g.invokeExact(new int[] { 3, 4, 5 }, 2));
        });
        arm("invokeWithArguments", () -> String.valueOf(
                l.findStatic(me, "sum3", MethodType.methodType(String.class, int.class, int.class, int.class))
                        .invokeWithArguments(List.of(4, 5, 6))));
        arm("defineHiddenClass", () ->
        {
            byte[] bytes = ClassFile.of().build(ClassDesc.of("MhSpinProbe$Gen"), cb -> cb
                    .withMethodBody("hello", MethodTypeDesc.of(ConstantDescs.CD_String),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.ldc("hello from a hidden class").areturn()));
            MethodHandles.Lookup hl = l.defineHiddenClassWithClassData(bytes, "the data", true);
            Class<?> hc = hl.lookupClass();
            MethodHandle hello = hl.findStatic(hc, "hello", MethodType.methodType(String.class));
            String data = MethodHandles.classData(hl, ConstantDescs.DEFAULT_NAME, String.class);
            return hc.isHidden() + " " + (String) hello.invokeExact() + " / " + data + " / "
                    + hc.getName().startsWith("MhSpinProbe$Gen");
        });
        arm("two hidden classes, one name", () ->
        {
            byte[] bytes = ClassFile.of().build(ClassDesc.of("MhSpinProbe$Twin"), cb -> cb
                    .withMethodBody("data", MethodTypeDesc.of(ConstantDescs.CD_Object),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.aconst_null().areturn()));
            MethodHandles.Lookup a = l.defineHiddenClassWithClassData(bytes, "first", true);
            MethodHandles.Lookup b = l.defineHiddenClassWithClassData(bytes, "second", true);
            return (a.lookupClass() != b.lookupClass()) + " "
                    + MethodHandles.classData(a, ConstantDescs.DEFAULT_NAME, String.class) + " "
                    + MethodHandles.classData(b, ConstantDescs.DEFAULT_NAME, String.class);
        });
        System.out.println("MhSpinProbe done");
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
