/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-10
 */
import java.util.List;

/**
 * STATIC FIELDS RESOLVED THROUGH A CLASS THAT DOES NOT DECLARE THEM (JVMS 5.4.3.2). javac names an inherited static
 * through the class the source wrote, so {@code Sub.SHARED} compiles to {@code getstatic Sub.SHARED} even though
 * {@code Base} declares it. joe-ng's static lookup was class-qualified only: the read came back NULL with no report
 * -- found as an NPE in stock {@code CharBuffer.<clinit>}, which reads {@code CharBuffer.UNSAFE}, declared by
 * {@code java.nio.Buffer}.
 *
 * <p>Shapes: one level up, two levels up, an INTERFACE's non-constant static through an implementing class (searched
 * before the superclass), a WRITE through the subclass seen by a read through the declaring class, and a subclass
 * that SHADOWS the name (its own cell must win). The values are non-constant objects so javac cannot inline them.
 * Prints values only, so the host diff is the gate.
 */
public class InheritedStaticProbe
{
    static class Base
    {
        static Object shared = new StringBuilder("base-shared");
        static int[] counter = new int[] {7};
    }

    static class Mid extends Base
    {
    }

    static class Leaf extends Mid
    {
    }

    interface Holder
    {
        List<String> NAMES = List.of("alpha", "beta");
    }

    static class Impl extends Base implements Holder
    {
    }

    static class Shadow extends Base
    {
        static Object shared = new StringBuilder("shadow-own");
    }

    public static void main(String[] args)
    {
        System.out.println("one level -> " + Mid.shared + " " + Mid.counter[0]);
        System.out.println("two levels -> " + Leaf.shared + " " + Leaf.counter[0]);
        System.out.println("interface static via implementor -> " + Impl.NAMES);
        Leaf.counter[0] = 42;
        Mid.shared = "written via Mid";
        System.out.println("write via subclass, read via declarer -> " + Base.counter[0] + " " + Base.shared);
        System.out.println("shadowing -> " + Shadow.shared + " | base still " + Base.shared);
        System.out.println("InheritedStaticProbe done");
    }
}
