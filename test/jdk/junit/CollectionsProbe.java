/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-28
 */
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Exercises {@code java.util.Collections}' wrapper surface -- the unmodifiable views, the empty and singleton
 * constants, the synchronized wrappers, and the ordering statics.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values:
 * run on a host JVM the arms reach stock {@code Collections}, run on the metal they reach whatever
 * {@code java.util.Collections} this VM carries, and every arm must print the same bytes in both worlds. An
 * oracle that cannot be typed wrong removes the mis-transcription failure mode this project has paid for
 * twice.
 *
 * <p>THE CONTROL IS THE OVERLAY RESTORED. {@code guestsrc/java/util/Collections.java} was deleted so stock
 * runs; restoring it and rebuilding makes {@code 35 of these 57 arms} answer differently. Unlike
 * {@code TimeUnitProbe}, ONE source compiles against both worlds -- see the closing paragraph -- so the
 * control runs every arm to the end rather than hard-stopping part way, which is the stronger evidence.
 *
 * <p>THE MUTATION ARMS ARE THE ONES THAT DISCRIMINATE, and they are why this file exists. The retired overlay
 * implemented every {@code unmodifiableXxx} as {@code return the argument} -- so the "unmodifiable" view IS
 * the backing collection. A caller that mutates it does not merely get away with it: the write lands in the
 * original, which is the object the owner still holds and believes nobody else can change. So each arm prints
 * the BACKING collection after the attempt, not just whether the call threw -- "did it throw" alone cannot
 * tell a permissive view from a CORRUPTING one, and those want different fixes.
 *
 * <p>AND THE EMPTY CONSTANTS ARE A SECOND, QUIETER SHAPE. Stock's {@code emptyList()} hands back the shared
 * immutable {@code EMPTY_LIST} singleton; the overlay returned {@code new ArrayList()} -- a FRESH MUTABLE list
 * per call. Two consequences that look nothing alike: {@code emptyList() == emptyList()} answers false where
 * stock answers true, and a caller treating the result as a safe shared constant can append to it.
 *
 * <p>THE SYNCHRONIZED ARMS ARE ABOUT A FOUR-CORE VM, not about identity for its own sake. The overlay's
 * {@code synchronizedList} also returned its argument, so it provided NO mutual exclusion at all -- on a VM
 * whose scheduler runs on all four A72s. Stock's wrappers guard every method with a {@code synchronized
 * (mutex)} BLOCK, which lowers to monitorenter/monitorexit and is machinery joe-ng has; they are NOT the
 * {@code ACC_SYNCHRONIZED} methods this VM does not implement. The arm asserts the view is a DISTINCT object,
 * because a wrapper is the only shape that can hold a lock.
 *
 * <p>THE READ-THROUGH AND ORDERING ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in
 * both states is not a control. {@code get}/{@code size}/{@code contains}/iteration through a view, and
 * {@code sort}/{@code reverse}/{@code enumeration}/{@code addAll}, were all correct under the overlay -- they
 * are what its javadoc was written for. A change that broke them would show here rather than in the arms
 * above.
 *
 * <p>NO ANONYMOUS OR NESTED CLASSES ANYWHERE IN THIS FILE, deliberately. A probe lives in the DEFAULT
 * PACKAGE, which matches no {@code demandLoadable} prefix, so a nested {@code CollectionsProbe$1} reaching
 * the image's classDir is a claim nothing here has measured -- and an arm that cannot load looks exactly like
 * an arm that passed. {@code sort(List,Comparator)} is therefore driven by {@code reverseOrder()}, which
 * exercises both members with no extra class.
 *
 * <p>DELIBERATELY NOT PROBED: the stock-only members the overlay never declared ({@code nCopies},
 * {@code frequency}, {@code max}, {@code min}, {@code swap}, {@code fill}, {@code binarySearch}). Naming one
 * would stop this source compiling against {@code guestsrc}, so the CONTROL could not be built and would
 * hard-stop part way instead of running every arm -- the weaker evidence. What those members do on metal is a
 * separate claim and is not made here. The no-arg {@code reverseOrder()} is in that set too -- javac refuses
 * it against {@code guestsrc}, which is how it was found: the overlay declares only the one-argument form.
 */
public class CollectionsProbe
{
    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = " + value);
    }

    private static String show(Collection<?> c)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("size=").append(c.size()).append(" [");
        boolean first = true;
        for (Object o : c)
        {
            if (!first)
            {
                sb.append(',');
            }
            sb.append(o);
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private static String showMap(Map<String, String> m)
    {
        return "size=" + m.size() + " k=" + m.get("k") + " z=" + m.get("z");
    }

    public static void main(String[] args)
    {
        System.out.println("-- unmodifiable views: mutation must be refused, and the BACKING must not move --");

        List<String> backingList = new ArrayList<String>();
        backingList.add("a");
        backingList.add("b");
        List<String> viewList = Collections.unmodifiableList(backingList);
        say("unmodifiableList is the backing object", String.valueOf(viewList == backingList));
        say("unmodifiableList read-through", show(viewList));
        say("unmodifiableList get(1)", viewList.get(1));

        String r;
        try
        {
            viewList.add("z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("unmodifiableList.add", r);
        say("  backing after add", show(backingList));

        try
        {
            viewList.set(0, "z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("unmodifiableList.set", r);
        say("  backing after set", show(backingList));

        try
        {
            viewList.remove(0);
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("unmodifiableList.remove", r);
        say("  backing after remove", show(backingList));

        try
        {
            viewList.clear();
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("unmodifiableList.clear", r);
        say("  backing after clear", show(backingList));

        Set<String> backingSet = new HashSet<String>();
        backingSet.add("s");
        Set<String> viewSet = Collections.unmodifiableSet(backingSet);
        say("unmodifiableSet is the backing object", String.valueOf(viewSet == backingSet));
        say("unmodifiableSet contains(s)", String.valueOf(viewSet.contains("s")));
        try
        {
            viewSet.add("z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("unmodifiableSet.add", r);
        say("  backing after add", show(backingSet));

        Map<String, String> backingMap = new HashMap<String, String>();
        backingMap.put("k", "v");
        Map<String, String> viewMap = Collections.unmodifiableMap(backingMap);
        say("unmodifiableMap is the backing object", String.valueOf(viewMap == backingMap));
        say("unmodifiableMap get(k)", viewMap.get("k"));
        try
        {
            viewMap.put("z", "z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("unmodifiableMap.put", r);
        say("  backing after put", showMap(backingMap));

        List<String> backingColl = new ArrayList<String>();
        backingColl.add("c");
        Collection<String> viewColl = Collections.unmodifiableCollection(backingColl);
        say("unmodifiableCollection is the backing object", String.valueOf(viewColl == backingColl));
        try
        {
            viewColl.add("z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("unmodifiableCollection.add", r);
        say("  backing after add", show(backingColl));

        System.out.println("-- empty constants: shared, and immutable --");

        List<String> e1 = Collections.emptyList();
        List<String> e2 = Collections.emptyList();
        say("emptyList() == emptyList()", String.valueOf(e1 == e2));
        say("emptyList size/isEmpty", e1.size() + "/" + e1.isEmpty());
        try
        {
            e1.add("z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("emptyList.add", r);
        say("  a FRESH emptyList after that", show(Collections.<String>emptyList()));

        Set<String> es1 = Collections.emptySet();
        Set<String> es2 = Collections.emptySet();
        say("emptySet() == emptySet()", String.valueOf(es1 == es2));
        try
        {
            es1.add("z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("emptySet.add", r);
        say("  a FRESH emptySet after that", show(Collections.<String>emptySet()));

        Map<String, String> em1 = Collections.emptyMap();
        Map<String, String> em2 = Collections.emptyMap();
        say("emptyMap() == emptyMap()", String.valueOf(em1 == em2));
        try
        {
            em1.put("z", "z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("emptyMap.put", r);
        say("  a FRESH emptyMap size after that", String.valueOf(Collections.emptyMap().size()));

        System.out.println("-- singletons: one element, and immutable --");

        List<String> sl = Collections.singletonList("one");
        say("singletonList contents", show(sl));
        try
        {
            sl.add("z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("singletonList.add", r);
        try
        {
            sl.set(0, "z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("singletonList.set", r);
        say("  singletonList after", show(sl));

        Set<String> ss = Collections.singleton("one");
        say("singleton contents", show(ss));
        try
        {
            ss.add("z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("singleton.add", r);
        say("  singleton after", show(ss));

        Map<String, String> sm = Collections.singletonMap("k", "v");
        say("singletonMap get/size", sm.get("k") + "/" + sm.size());
        try
        {
            sm.put("z", "z");
            r = "SUCCEEDED (no exception)";
        }
        catch (UnsupportedOperationException e)
        {
            r = "UnsupportedOperationException";
        }
        say("singletonMap.put", r);
        say("  singletonMap size after", String.valueOf(sm.size()));

        System.out.println("-- synchronized wrappers: a wrapper is the only shape that can hold a lock --");

        List<String> syncBacking = new ArrayList<String>();
        syncBacking.add("p");
        List<String> syncList = Collections.synchronizedList(syncBacking);
        say("synchronizedList is the backing object", String.valueOf(syncList == syncBacking));
        syncList.add("q");
        say("synchronizedList after add", show(syncList));
        say("  backing sees it", show(syncBacking));

        Set<String> syncSetBacking = new HashSet<String>();
        say("synchronizedSet is the backing object",
                String.valueOf(Collections.synchronizedSet(syncSetBacking) == syncSetBacking));
        Map<String, String> syncMapBacking = new HashMap<String, String>();
        say("synchronizedMap is the backing object",
                String.valueOf(Collections.synchronizedMap(syncMapBacking) == syncMapBacking));
        Collection<String> syncCollBacking = new ArrayList<String>();
        say("synchronizedCollection is the backing object",
                String.valueOf(Collections.synchronizedCollection(syncCollBacking) == syncCollBacking));

        System.out.println("-- ordering and iteration: the built-in comparison --");

        List<String> toSort = new ArrayList<String>();
        toSort.add("pear");
        toSort.add("apple");
        toSort.add("fig");
        toSort.add("apple");
        Collections.sort(toSort);
        say("sort(List) natural", show(toSort));

        List<Integer> nums = new ArrayList<Integer>();
        nums.add(Integer.valueOf(5));
        nums.add(Integer.valueOf(-3));
        nums.add(Integer.valueOf(12));
        nums.add(Integer.valueOf(0));
        Collections.sort(nums);
        say("sort(List) Integer", show(nums));

        Collections.sort(nums, Comparator.<Integer>reverseOrder());
        say("sort(List,Comparator.reverseOrder) descending", show(nums));

        Collections.reverse(nums);
        say("reverse", show(nums));

        say("Collections.reverseOrder(null).compare(1,2)",
                String.valueOf(Collections.<Integer>reverseOrder(null).compare(
                        Integer.valueOf(1), Integer.valueOf(2))));

        List<String> src = new ArrayList<String>();
        Collections.addAll(src, "x", "y", "z");
        say("addAll varargs", show(src));

        Enumeration<String> en = Collections.enumeration(src);
        StringBuilder walked = new StringBuilder();
        while (en.hasMoreElements())
        {
            walked.append(en.nextElement());
        }
        say("enumeration walk", walked.toString());
        say("list(enumeration)", show(Collections.list(Collections.enumeration(src))));

        List<Integer> deck = new ArrayList<Integer>();
        for (int i = 0; i < 8; i++)
        {
            deck.add(Integer.valueOf(i));
        }
        Collections.shuffle(deck, new Random(12345L));
        say("shuffle(list, Random(12345))", show(deck));

        System.out.println("CollectionsProbe done");
    }
}
