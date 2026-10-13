/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-13
 */
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code java.lang.StackWalker}, which this VM did not have (stock's runs on HotSpot's stack-walk natives). Each
 * line is a fact the JDK's own users read: the frames {@code walk} sees from a known depth (class, method, line,
 * file), that {@code forEach} sees the same ones, {@code getCallerClass} and {@code getDeclaringClass} with
 * RETAIN_CLASS_REFERENCE, the UnsupportedOperationException without it, a frame inside a lambda body, the
 * short-circuit a {@code findFirst} takes, and the DROP_METHOD_INFO refusal. Lines are printed from this file
 * only, so they are the same on any JDK. Prints values only; the host diff is the gate.
 */
public class StackWalkerProbe
{
    static final StackWalker PLAIN = StackWalker.getInstance();
    static final StackWalker RETAIN = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public static void main(String[] args)
    {
        level1();
        System.out.println("caller of whoCalls=" + Helper.whoCalls().getName());
        System.out.println("forEach count=" + forEachCount() + " walk count=" + walkCount());
        Supplier<String> lambda = () -> PLAIN.walk(s -> s.findFirst()).get().getMethodName();
        System.out.println("lambda top starts=" + lambda.get().startsWith("lambda$main$"));
        StackWalker.StackFrame top = RETAIN.walk(s -> s.findFirst()).get();
        System.out.println("declaring=" + top.getDeclaringClass().getName() + " method=" + top.getMethodName()
                + " ste=" + top.toStackTraceElement().getMethodName());
        System.out.println("ste string=" + top.toStackTraceElement());
        try
        {
            PLAIN.walk(s -> s.findFirst()).get().getDeclaringClass();
            System.out.println("no-retain getDeclaringClass: allowed");
        }
        catch (UnsupportedOperationException e)
        {
            System.out.println("no-retain getDeclaringClass: UOE");
        }
        try
        {
            PLAIN.getCallerClass();
            System.out.println("no-retain getCallerClass: allowed");
        }
        catch (UnsupportedOperationException e)
        {
            System.out.println("no-retain getCallerClass: UOE");
        }
        StackWalker drop = StackWalker.getInstance(EnumSet.of(StackWalker.Option.DROP_METHOD_INFO));
        try
        {
            drop.walk(s -> s.findFirst()).get().getMethodName();
            System.out.println("drop getMethodName: allowed");
        }
        catch (UnsupportedOperationException e)
        {
            System.out.println("drop getMethodName: UOE");
        }
        System.out.println("drop className=" + drop.walk(s -> s.findFirst()).get().getClassName());
        Set<StackWalker.Option> opts = EnumSet.of(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        System.out.println("options walker caller=" + StackWalker.getInstance(opts).walk(
                s -> s.skip(0).findFirst()).get().getDeclaringClass().getSimpleName());
        System.out.println("StackWalkerProbe done");
    }

    static void level1()
    {
        level2();
    }

    static void level2()
    {
        List<String> frames = PLAIN.walk(s -> s.limit(3)
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber() + " "
                        + f.getFileName() + " native=" + f.isNativeMethod())
                .collect(Collectors.toList()));
        for (int i = 0; i < frames.size(); i += 1)
        {
            System.out.println("frame " + i + " " + frames.get(i));
        }
    }

    static int forEachCount()
    {
        int[] n = new int[1];
        PLAIN.forEach(f ->
        {
            if (f.getClassName().equals("StackWalkerProbe"))
            {
                n[0] += 1;
            }
        });
        return n[0];
    }

    static long walkCount()
    {
        return PLAIN.walk(s -> s.filter(f -> f.getClassName().equals("StackWalkerProbe")).count());
    }

    static class Helper
    {
        static Class<?> whoCalls()
        {
            return RETAIN.getCallerClass();
        }
    }
}
