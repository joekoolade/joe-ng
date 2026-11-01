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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A class whose initializer FAILS (JVMS 12.4.2 steps 10-12). The first use rethrows an Error as it is and wraps
 * anything else in ExceptionInInitializerError; the class is then ERRONEOUS, and every later use -- new, a static
 * read, a static call, a subclass, Class.forName -- throws NoClassDefFoundError. Found when un-denying
 * java/net/HttpConnectSocketImpl: its <clinit> throws InternalError, and a second new Socket(httpProxy) on metal
 * SUCCEEDED with the class's statics null. Also: a class whose initializer USES a failing class (it fails too, and
 * is erroneous after), and two threads racing one failing initializer -- the loser waits (step 2) and must then see
 * NoClassDefFoundError, not a half-initialized class. Each arm runs on its own and prints what it got.
 */
public class ClinitFailProbe
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
            got = "returned " + arm.run();
        }
        catch (Throwable t)
        {
            got = "threw " + t.getClass().getName();
            if (t.getCause() != null && !(t instanceof NoClassDefFoundError))
            {
                got = got + " cause " + t.getCause().getClass().getName();
            }
        }
        System.out.println(what + ": " + got);
    }

    static int fail(Throwable t) throws Throwable
    {
        throw t;
    }

    static int failError()
    {
        throw new InternalError("boom");
    }

    static int failRuntime()
    {
        throw new IllegalStateException("boom");
    }

    static class ErrorInit
    {
        static final Object TAG = new Object();
        static int value = failError();

        ErrorInit()
        {
        }

        static String tag()
        {
            return TAG == null ? "null" : "set";
        }
    }

    static class RuntimeInit
    {
        static int value = failRuntime();

        static int read()
        {
            return value;
        }
    }

    static class SubOfFailed extends RuntimeInit
    {
        static int own = 7;
    }

    static class FailsLater
    {
        static int value = failRuntime();
    }

    static class UsesFailing
    {
        static int value = FailsLater.value + 1;
    }

    /** Set from inside SlowFail's initializer, so the second thread starts only once the first is in it. */
    static class Started
    {
        static volatile boolean flag;
    }

    static class SlowFail
    {
        static int value = slowThenFail();
    }

    static int slowThenFail()
    {
        Started.flag = true;
        try
        {
            Thread.sleep(200);
        }
        catch (InterruptedException e)
        {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("slow boom");
    }

    static String outcome(Throwable t)
    {
        return t == null ? "returned" : t.getClass().getSimpleName();
    }

    static String race() throws Throwable
    {
        Throwable[] got = new Throwable[2];
        Thread first = new Thread(() ->
        {
            try
            {
                int v = SlowFail.value;
            }
            catch (Throwable t)
            {
                got[0] = t;
            }
        });
        first.start();
        while (!Started.flag)
        {
            Thread.sleep(5);
        }
        Thread second = new Thread(() ->
        {
            try
            {
                int v = SlowFail.value;
            }
            catch (Throwable t)
            {
                got[1] = t;
            }
        });
        second.start();
        first.join();
        second.join();
        List<String> both = new ArrayList<>();
        both.add(outcome(got[0]));
        both.add(outcome(got[1]));
        Collections.sort(both);
        return both.toString();
    }

    public static void main(String[] args)
    {
        report("Error init, first new", () -> String.valueOf(new ErrorInit() != null));
        report("Error init, second new", () -> String.valueOf(new ErrorInit() != null));
        report("Error init, static call", () -> ErrorInit.tag());
        report("Error init, static read", () -> String.valueOf(ErrorInit.value));
        report("Error init, static call again", () -> ErrorInit.tag());
        report("Runtime init, first read", () -> String.valueOf(RuntimeInit.value));
        report("Runtime init, second read", () -> String.valueOf(RuntimeInit.value));
        report("Runtime init, static call", () -> String.valueOf(RuntimeInit.read()));
        report("subclass of failed", () -> String.valueOf(SubOfFailed.own));
        report("forName of failed", () -> Class.forName("ClinitFailProbe$RuntimeInit").getName());
        report("forName no-init", () -> Class.forName("ClinitFailProbe$RuntimeInit", false,
                ClinitFailProbe.class.getClassLoader()).getName());
        report("uses failing, first", () -> String.valueOf(UsesFailing.value));
        report("uses failing, second", () -> String.valueOf(UsesFailing.value));
        report("failing it used, after", () -> String.valueOf(FailsLater.value));
        report("two threads, one failing init", () -> race());
        System.out.println("ClinitFailProbe done");
    }
}
