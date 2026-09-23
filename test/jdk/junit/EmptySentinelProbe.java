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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Does {@code ImmutableCollections.EMPTY} LEAK out of a one-element immutable collection?
 *
 * <p>WHY. The console launcher ends in an NPE at {@code ClassSelector.hashCode}, and the instrumented boot
 * traced it to a {@code Class.getName()} whose RECEIVER was {@code 0x0016C300} -- an IMAGE address, below
 * the heap. Read straight out of the image file, that object has instance size 16 (header only, no fields)
 * and a vtable EVERY slot of which is {@code java/lang/Object}'s own method; there is exactly ONE such
 * object in the whole image, referenced from exactly one statics cell. That is a baked
 * {@code static final Object} sentinel, and {@code ImmutableCollections.EMPTY} is the one this VM bakes.
 *
 * <p>{@code List12}/{@code Set12} store that sentinel in the slot a one-element collection does not use and
 * test {@code e1 != EMPTY} BY IDENTITY before handing an element out. joe-ng has a recorded family of bugs
 * where a static exists TWICE -- once baked in the image, once in the guest world -- and an identity test
 * across the two copies is false when it should be true. The sentinel then escapes as if it were an
 * element, and the first thing the caller does with it (here {@code getName()}) dispatches
 * {@code Class.getName}'s vtable slot into a bare Object's vtable and returns whatever sits there: 0 on one
 * boot, 1 on another, which is why the symptom looked like noise.
 *
 * <p>WHAT DISCRIMINATES. Sizes and element CLASS NAMES, per collection shape, with the ONE- and TWO-element
 * forms side by side: the two-element form has no unused slot, so it is the built-in control -- an
 * implementation that leaked in both would be a different bug, and one that leaks in neither says the
 * hypothesis is wrong. Every arm names the element's CLASS, because a leaked sentinel is a perfectly good
 * object and prints without complaint; only its type gives it away.
 */
public final class EmptySentinelProbe
{
    public static void main(String[] args)
    {
        listArm("List.of(a)", List.of("a"), 1);
        listArm("List.of(a,b)", List.of("a", "b"), 2);
        listArm("List.of(a,b,c)", List.of("a", "b", "c"), 3);

        setArm("Set.of(a)", Set.of("a"), 1);
        setArm("Set.of(a,b)", Set.of("a", "b"), 2);

        Map<String, String> m1 = Map.of("k", "v");
        System.out.println("Map.of(k,v): size=" + m1.size() + " (want 1)");
        for (Map.Entry<String, String> e : m1.entrySet())
        {
            System.out.println("    entry key=" + cls(e.getKey()) + " value=" + cls(e.getValue()));
        }

        System.out.println("EmptySentinelProbe done");
    }

    private static void listArm(String what, List<String> l, int want)
    {
        System.out.println(what + ": size=" + l.size() + " (want " + want + ")");
        int i = 0;
        while (i < l.size())
        {
            System.out.println("    get(" + i + ") = " + cls(l.get(i)));
            i = i + 1;
        }
        l.forEach(x -> System.out.println("    forEach -> " + cls(x)));
        Object[] a = l.toArray();
        System.out.println("    toArray len=" + a.length + " (want " + want + ")");
        int j = 0;
        while (j < a.length)
        {
            System.out.println("    toArray[" + j + "] = " + cls(a[j]));
            j = j + 1;
        }
    }

    private static void setArm(String what, Set<String> s, int want)
    {
        System.out.println(what + ": size=" + s.size() + " (want " + want + ")");
        s.forEach(x -> System.out.println("    forEach -> " + cls(x)));
        Object[] a = s.toArray();
        System.out.println("    toArray len=" + a.length + " (want " + want + ")");
        int j = 0;
        while (j < a.length)
        {
            System.out.println("    toArray[" + j + "] = " + cls(a[j]));
            j = j + 1;
        }
    }

    /** An element's VALUE and its CLASS: a leaked sentinel is a fine object and only its type names it. */
    private static String cls(Object o)
    {
        if (o == null)
        {
            return "null";
        }
        return "[" + o + "] " + o.getClass().getName();
    }
}
