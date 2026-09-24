/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-23
 */
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code getClass().getName()} on a SYNTHESISED class -- a lambda or a method reference.
 *
 * <p>It used to answer NULL: a lambda's Type is not in the class registry (a hidden class has no binary
 * name, BY CONSTRUCTION), and {@code classNameLen} answered 0 for anything it could not find, which
 * {@code classNameString} turned into a null String. Stock's {@code getName()} NEVER returns null, so that
 * null flows into ordinary library code and NPEs somewhere unrelated -- the exact shape this VM had just
 * spent five launcher boots chasing for a different object.
 *
 * <p>WHAT DISCRIMINATES, because "non-null" is a weak claim here:
 * <ul>
 *   <li>The name must be NON-NULL and carry the {@code $$Lambda} marker -- stock's own shape for a hidden
 *       class, and the thing that tells a reader this is synthetic rather than a class they can look up.</li>
 *   <li>TWO DIFFERENT lambdas must get DIFFERENT names. A single constant would satisfy every
 *       non-null check and make every lambda compare equal by name -- a silent wrong answer that looks
 *       exactly like a working one.</li>
 *   <li>The SAME lambda object asked twice must give the same name, or the name is a fresh accident
 *       rather than a property of the class.</li>
 *   <li>A method reference is included beside a lambda body: they take different indy implementation
 *       kinds in this VM, and this file records a bug that lived in precisely that difference.</li>
 *   <li>An ORDINARY class is the control -- it must still report its real binary name, so the new branch
 *       cannot have swallowed the registry lookup.</li>
 * </ul>
 */
public final class SynthNameProbe
{
    public static void main(String[] args)
    {
        Function<String, String> f1 = x -> x + "!";
        Function<String, String> f2 = x -> x + "?";
        Supplier<String> mref = "abc"::trim;

        String n1 = f1.getClass().getName();
        String n2 = f2.getClass().getName();
        String nm = mref.getClass().getName();
        String again = f1.getClass().getName();

        System.out.println("lambda1 name = " + n1);
        System.out.println("lambda2 name = " + n2);
        System.out.println("mref    name = " + nm);
        System.out.println("nonNull = " + (n1 != null && n2 != null && nm != null) + " (want true)");
        System.out.println("marked  = " + (n1 != null && n1.contains("$$Lambda")) + " (want true)");
        System.out.println("distinct = " + (n1 != null && !n1.equals(n2)) + " (want true)");
        System.out.println("stable   = " + (n1 != null && n1.equals(again)) + " (want true)");
        System.out.println("control  = " + "abc".getClass().getName() + " (want java.lang.String)");
        System.out.println("SynthNameProbe done");
    }
}
