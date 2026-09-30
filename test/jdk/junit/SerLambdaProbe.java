/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-29
 */
import java.io.Serializable;
import java.util.concurrent.Callable;

/**
 * Exercises a lambda cast to an INTERSECTION type -- {@code (Runnable & Serializable) () -> ...} -- which
 * javac bootstraps through {@code LambdaMetafactory.altMetafactory} rather than {@code metafactory}.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values.
 *
 * <p>WHY THE FORM MATTERS. {@code Loader.isLambdaIndy} matched the bootstrap name {@code metafactory}
 * EXACTLY, so an intersection-cast lambda fell through to {@code Baseline.lowerInvokeDynamic}'s
 * unsupported-bootstrap arm and the whole enclosing class refused to compile:
 * {@code JIT unsupported: reason=0 a=0xBA b=0}. That is not an exotic shape -- it is how stock
 * {@code java.util.Comparator} writes every one of its combinators, so the class had exactly ONE bootstrap
 * method and it was the alt one.
 *
 * <p>THE {@code instanceof} ARMS ARE THE POINT, NOT THE CALL. Accepting the bootstrap is enough to make the
 * lambda RUN, because {@code altMetafactory}'s first three static arguments are byte-for-byte
 * {@code metafactory}'s. What the flags add is {@code FLAG_SERIALIZABLE}: the function object must also
 * implement {@code java.io.Serializable}. Nothing in this VM serialises anything, so that marker's ONLY
 * observable is a type test -- which is exactly why it has to be asserted. A lambda that runs correctly and
 * answers {@code false} to {@code instanceof Serializable} is the silent-wrong-answer shape, and the arm
 * below is the difference between honouring the flag and ignoring it.
 *
 * <p>THE PLAIN LAMBDA IS THE CONTROL. A lambda with no intersection cast must answer {@code false} to
 * {@code instanceof Serializable} -- so "fixed" cannot quietly mean "every lambda claims to be
 * serializable", which a marker added unconditionally would produce and which no positive arm can see.
 *
 * <p>THREE SAM SHAPES, because the thunk arms differ and only one of them was exercised by the class that
 * found this. A zero-arg {@code Runnable}, a one-arg-returning {@code Callable} equivalent, and a CAPTURING
 * lambda -- the kind-6 path, where the captures are shifted before the SAM args -- so a marker entry
 * appended to the itable directory is checked against a directory that already has a real closure in it.
 *
 * <p>AND ONE ARM FOR THE DIRECTORY ITSELF: the functional interface must still be found. Adding an entry
 * before the sentinel is exactly the kind of edit that can write over it, and the symptom of a lost sentinel
 * is a dispatch walking off the end of the directory -- an NPE far from here. Calling the lambda through its
 * interface after the type tests is what says the directory survived.
 */
public class SerLambdaProbe
{
    private static int sink;

    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = " + value);
    }

    private static String bool(boolean b)
    {
        return b ? "1" : "0";
    }

    public static void main(String[] args) throws Exception
    {
        System.out.println("-- zero-arg Runnable, intersection-cast --");
        Runnable ser = (Runnable & Serializable) () -> sink = 41;
        say("ran", bool(runIt(ser) == 41));
        say("instanceof Serializable", bool(ser instanceof Serializable));
        say("instanceof Runnable", bool(ser instanceof Runnable));
        say("instanceof Object", bool(ser instanceof Object));

        System.out.println("-- plain lambda: the control, NOT serializable --");
        Runnable plain = () -> sink = 7;
        say("ran", bool(runIt(plain) == 7));
        say("instanceof Serializable", bool(plain instanceof Serializable));
        say("instanceof Runnable", bool(plain instanceof Runnable));

        System.out.println("-- capturing, intersection-cast (the kind-6 shift path) --");
        int a = 20;
        int b = 3;
        Callable<String> cap = (Callable<String> & Serializable) () -> "cap:" + (a * b);
        say("called", cap.call());
        say("instanceof Serializable", bool(cap instanceof Serializable));
        say("instanceof Callable", bool(cap instanceof Callable));

        System.out.println("-- a method reference, intersection-cast --");
        Runnable mref = (Runnable & Serializable) SerLambdaProbe::bump;
        say("ran", bool(runIt(mref) == 99));
        say("instanceof Serializable", bool(mref instanceof Serializable));

        System.out.println("-- the directory survived: dispatch again after the type tests --");
        say("re-ran", bool(runIt(ser) == 41));
        say("re-called", cap.call());

        System.out.println("SerLambdaProbe done");
    }

    private static void bump()
    {
        sink = 99;
    }

    private static int runIt(Runnable r)
    {
        sink = 0;
        r.run();
        return sink;
    }
}
