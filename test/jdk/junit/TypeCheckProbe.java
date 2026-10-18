/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-18
 */
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An {@code instanceof} / {@code checkcast} compiled BEFORE its target class is loaded must still answer
 * correctly once instances exist. The compiler bakes the target Type as an immediate, and it was baked as 0 when
 * the class was not registered yet -- so stock {@code zf.stream().forEach(e -> e instanceof ZipEntry)} answered
 * false for a ZipEntry, because the lambda body compiled when it was LINKED, before any entry existed. Each arm
 * names a class nothing else in this program touches first: a lambda linked (and called once on a String) before
 * the class's first instance, and a plain static method compiled by a first call on a String.
 *
 * <p>A SHAPE GUARD, NOT A REPRODUCTION -- measured, and stated so nobody reads its pass as more: every arm
 * (ZipEntry and JarEntry included, the classes that failed) passes on the commit WITHOUT the fix too, so this
 * probe does not reach the condition. The failure needs the stock test's closure; its regression evidence is
 * {@code StreamZipEntriesTest} under MetalJUnit (2 of 4 failing without the fix, 4 of 4 with it).
 */
public class TypeCheckProbe
{
    static boolean isJoiner(Object o)
    {
        return o instanceof java.util.StringJoiner;
    }

    static Object asTreeSet(Object o)
    {
        return (java.util.TreeSet<?>) o;
    }

    public static void main(String[] args)
    {
        Predicate<Object> isBits = o -> o instanceof java.util.BitSet;
        Predicate<Object> isZipEntry = o -> o instanceof java.util.zip.ZipEntry;
        Predicate<Object> isJarEntry = o -> o instanceof java.util.jar.JarEntry;
        System.out.println("before: " + isZipEntry.test("x") + " " + isJarEntry.test("x"));
        Function<Object, Object> asDeque = o -> (java.util.ArrayDeque<?>) o;
        System.out.println("before: " + isBits.test("x") + " " + isJoiner("x"));
        try
        {
            asTreeSet("x");
        }
        catch (ClassCastException e)
        {
            System.out.println("before: a String is not a TreeSet");
        }
        System.out.println("lambda instanceof: " + isBits.test(new java.util.BitSet()));
        System.out.println("lambda instanceof ZipEntry: " + isZipEntry.test(new java.util.zip.ZipEntry("e")));
        System.out.println("lambda instanceof JarEntry: " + isJarEntry.test(new java.util.jar.JarEntry("e")));
        System.out.println("method instanceof: " + isJoiner(new java.util.StringJoiner(",")));
        System.out.println("lambda checkcast: " + asDeque.apply(new java.util.ArrayDeque<String>()).getClass().getName());
        System.out.println("method checkcast: " + asTreeSet(new java.util.TreeSet<String>()).getClass().getName());
        System.out.println("TypeCheckProbe done");
    }
}
