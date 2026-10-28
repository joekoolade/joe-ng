/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-28
 */

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.StringConcatFactory;
import java.lang.invoke.SwitchPoint;
import java.lang.invoke.VarHandle;
import java.lang.invoke.VolatileCallSite;
import java.lang.reflect.Method;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;

/**
 * The java.lang.invoke surface the arc's probes (MethodHandleProbe, MhInvokeProbe, MhSpinProbe, VarHandleProbe,
 * IndyProbe) do not reach: VarHandles on statics and arrays, the mutable call sites and SwitchPoint, the factories a
 * library calls DIRECTLY (MethodHandleProxies, LambdaMetafactory, StringConcatFactory -- the JIT lowers lambdas and
 * concat itself, so their stock paths are otherwise never run), findSpecial / privateLookupIn / revealDirect, and the
 * loop / tryFinally / array / invoker / cast combinators. One line per arm, each caught on its own, so the output is
 * the list of what works and what does not.
 */
public final class InvokeGapProbe
{
    static int counter = 3;
    static int sum(int a, int b)
    {
        return a + b;
    }

    static class Base
    {
        String who()
        {
            return "base";
        }
    }

    static final class Derived extends Base
    {
        @Override
        String who()
        {
            return "derived";
        }

        String viaSuper() throws Throwable
        {
            MethodHandle mh = MethodHandles.lookup().findSpecial(Base.class, "who", MethodType.methodType(String.class),
                    Derived.class);
            return (String) mh.invoke(this);
        }
    }

    private interface Arm
    {
        Object run() throws Throwable;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = String.valueOf(a.run());
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName() + ": " + t.getMessage();
            Throwable c = t.getCause();
            while (c != null)
            {
                r = r + " / cause " + c.getClass().getName() + ": " + c.getMessage();
                c = c.getCause();
            }
            StackTraceElement[] st = t.getStackTrace();
            int f = 0;
            while (f < st.length && f < 5)
            {
                r = r + (f == 0 ? " at " : " < ") + st[f].getClassName() + "." + st[f].getMethodName();
                f += 1;
            }
        }
        System.out.println(what + " = " + r);
    }

    public static void main(String[] args)
    {
        MethodHandles.Lookup lk = MethodHandles.lookup();
        MethodType ii = MethodType.methodType(int.class, int.class, int.class);

        arm("static VarHandle", () ->
        {
            VarHandle vh = lk.findStaticVarHandle(InvokeGapProbe.class, "counter", int.class);
            boolean ok = vh.compareAndSet(3, 10);
            return ok + " " + (int) vh.getAndAdd(5) + " " + counter;
        });
        arm("array VarHandle", () ->
        {
            VarHandle vh = MethodHandles.arrayElementVarHandle(long[].class);
            long[] a = new long[3];
            vh.set(a, 1, 7L);
            vh.getAndAdd(a, 1, 3L);
            return a[1] + " " + vh.compareAndSet(a, 1, 10L, 4L) + " " + a[1];
        });
        arm("MutableCallSite", () ->
        {
            MutableCallSite cs = new MutableCallSite(MethodHandles.constant(String.class, "one"));
            MethodHandle inv = cs.dynamicInvoker();
            String a = (String) inv.invokeExact();
            cs.setTarget(MethodHandles.constant(String.class, "two"));
            return a + " " + (String) inv.invokeExact();
        });
        arm("VolatileCallSite", () ->
        {
            VolatileCallSite cs = new VolatileCallSite(MethodHandles.constant(int.class, 1));
            cs.setTarget(MethodHandles.constant(int.class, 2));
            return (int) cs.dynamicInvoker().invokeExact();
        });
        arm("SwitchPoint", () ->
        {
            SwitchPoint sp = new SwitchPoint();
            MethodHandle g = sp.guardWithTest(MethodHandles.constant(String.class, "fast"),
                    MethodHandles.constant(String.class, "slow"));
            String before = (String) g.invokeExact();
            SwitchPoint.invalidateAll(new SwitchPoint[] { sp });
            return before + " " + (String) g.invokeExact() + " " + sp.hasBeenInvalidated();
        });
        arm("MethodHandleProxies", () ->
        {
            MethodHandle mh = lk.findStatic(InvokeGapProbe.class, "sum", ii);
            IntBinaryOperator op = MethodHandleProxies.asInterfaceInstance(IntBinaryOperator.class, mh);
            return op.applyAsInt(20, 22) + " " + MethodHandleProxies.isWrapperInstance(op);
        });
        arm("LambdaMetafactory", () ->
        {
            MethodHandle impl = lk.findStatic(InvokeGapProbe.class, "sum", ii);
            CallSite cs = LambdaMetafactory.metafactory(lk, "applyAsInt", MethodType.methodType(IntBinaryOperator.class),
                    ii, impl, ii);
            IntBinaryOperator op = (IntBinaryOperator) cs.getTarget().invoke();
            return op.applyAsInt(4, 5);
        });
        arm("StringConcatFactory", () ->
        {
            MethodType mt = MethodType.methodType(String.class, String.class, int.class);
            CallSite cs = StringConcatFactory.makeConcatWithConstants(lk, "concat", mt, "\u0001=\u0001!");
            return (String) cs.getTarget().invoke("x", 42);
        });
        arm("findSpecial", () -> new Derived().viaSuper());
        arm("privateLookupIn", () ->
        {
            MethodHandles.Lookup pl = MethodHandles.privateLookupIn(Derived.class, lk);
            MethodHandle mh = pl.findVirtual(Derived.class, "who", MethodType.methodType(String.class));
            return (String) mh.invoke(new Derived());
        });
        arm("revealDirect", () ->
        {
            MethodHandle mh = lk.findStatic(InvokeGapProbe.class, "sum", ii);
            MethodHandleInfo info = lk.revealDirect(mh);
            Method m = info.reflectAs(Method.class, lk);
            return info.getName() + " " + MethodHandleInfo.referenceKindToString(info.getReferenceKind()) + " "
                    + m.getName();
        });
        arm("countedLoop", () ->
        {
            MethodHandle body = lk.findStatic(InvokeGapProbe.class, "sum", ii);   // (v, i) -> v + i
            MethodHandle loop = MethodHandles.countedLoop(MethodHandles.constant(int.class, 5),
                    MethodHandles.constant(int.class, 0), body);
            return (int) loop.invoke();
        });
        arm("tryFinally", () ->
        {
            MethodHandle target = MethodHandles.constant(String.class, "body");
            MethodHandle cleanup = MethodHandles.dropArguments(MethodHandles.identity(String.class), 0,
                    Throwable.class);
            return (String) MethodHandles.tryFinally(target, cleanup).invoke();
        });
        arm("array helpers", () ->
        {
            MethodHandle ctor = MethodHandles.arrayConstructor(int[].class);
            MethodHandle len = MethodHandles.arrayLength(int[].class);
            MethodHandle set = MethodHandles.arrayElementSetter(int[].class);
            int[] a = (int[]) ctor.invoke(4);
            set.invoke(a, 2, 9);
            return (int) len.invoke(a) + " " + a[2];
        });
        arm("invokers", () ->
        {
            MethodHandle mh = lk.findStatic(InvokeGapProbe.class, "sum", ii);
            int a = (int) MethodHandles.exactInvoker(ii).invokeExact(mh, 1, 2);
            Object b = MethodHandles.spreadInvoker(ii, 0).invoke(mh, new Object[] { 3, 4 });
            return a + " " + b;
        });
        arm("explicitCastArguments", () ->
        {
            MethodHandle mh = lk.findStatic(InvokeGapProbe.class, "sum", ii);
            MethodHandle c = MethodHandles.explicitCastArguments(mh,
                    MethodType.methodType(long.class, byte.class, short.class));
            return (long) c.invokeExact((byte) 3, (short) 4);
        });
        arm("zero/empty/throwException", () ->
        {
            Object z = MethodHandles.zero(int.class).invoke();
            Object e = MethodHandles.empty(MethodType.methodType(String.class, int.class)).invoke(5);
            String t;
            try
            {
                MethodHandles.throwException(void.class, IllegalStateException.class)
                        .invoke(new IllegalStateException("thrown"));
                t = "no throw";
            }
            catch (IllegalStateException ex)
            {
                t = ex.getMessage();
            }
            return z + " " + e + " " + t;
        });
        arm("Supplier via metafactory", () ->
        {
            MethodHandle impl = MethodHandles.constant(String.class, "supplied");
            MethodHandle direct = lk.findStatic(InvokeGapProbe.class, "supplied", MethodType.methodType(String.class));
            CallSite cs = LambdaMetafactory.metafactory(lk, "get", MethodType.methodType(Supplier.class),
                    MethodType.methodType(Object.class), direct, MethodType.methodType(String.class));
            Supplier<?> s = (Supplier<?>) cs.getTarget().invoke();
            return s.get() + " " + impl.type();
        });
        System.out.println("InvokeGapProbe done");
    }

    static String supplied()
    {
        return "supplied";
    }
}
