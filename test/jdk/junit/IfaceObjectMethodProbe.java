/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-22
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * {@code hashCode}/{@code equals}/{@code toString} called through an INTERFACE-typed receiver, on objects whose
 * class has no binary name -- an annotation instance and a lambda. javac emits each as an
 * {@code invokeinterface} owned by the interface; the interface has no vtable, so the slot lookup used to miss
 * and the site went to late resolution, which against an object with no registry entry fell back to
 * {@code Object.hashCode} -- an identity hash, a different number every run. Every value printed here is fixed
 * by a contract (the annotation formula, a String's hash), and the Object-typed call of the same method is
 * printed beside it.
 */
public class IfaceObjectMethodProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @interface Inner
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Outer
    {
        Inner inner() default @Inner("in");
    }

    @Outer
    static class Holder
    {
    }

    interface Named
    {
        String name();
    }

    public static void main(String[] args)
    {
        Outer outer = Holder.class.getAnnotation(Outer.class);
        Inner in = outer.inner();
        Object asObject = in;
        System.out.println("inner hash via Inner=" + in.hashCode() + " via Object=" + asObject.hashCode()
                + " formula=" + ((127 * "value".hashCode()) ^ "in".hashCode()));
        System.out.println("outer hash=" + outer.hashCode());
        System.out.println("inner equals self via Inner=" + in.equals(in) + " other=" + in.equals(outer));
        System.out.println("inner toString via Inner equals via Object=" + in.toString().equals(asObject.toString()));
        Named n = () -> "lam";
        Object nObj = n;
        System.out.println("lambda hash via Named==via Object " + (n.hashCode() == nObj.hashCode())
                + " equals self=" + n.equals(n));
        System.out.println("IfaceObjectMethodProbe done");
    }
}
