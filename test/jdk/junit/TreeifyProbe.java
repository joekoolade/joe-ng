/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-02
 */
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code Class.getGenericInterfaces()}, and the reason it is not a reflection nicety: STOCK
 * {@code java.util.HashMap} CALLS IT whenever a bin TREEIFIES.
 *
 * <p>THE DEFECT, named by {@code make overlaycheck-deep} and measured before a line of the fix was written.
 * {@code HashMap.comparableClassFor(Object)} is reached from all three of the tree paths
 * ({@code TreeNode.find}, {@code putTreeVal}, {@code treeify}) and its body is:
 *
 * <pre>
 *   if (x instanceof Comparable) {
 *       if ((c = x.getClass()) == String.class) return c;      // bypass checks
 *       if ((ts = c.getGenericInterfaces()) != null) { ... }
 * </pre>
 *
 * The {@code Class} overlay did not declare {@code getGenericInterfaces}, and an overlay WINS the name -- so
 * the member ceased to exist, the call resolved nowhere, and a treeified bin HALTED THE VM with a
 * {@code DENYLIST TRAP} blaming a list {@code java/lang/Class} is not on.
 *
 * <p>WHY NOTHING HAD NOTICED, and it is the whole reason this sat in a class every closure carries: the
 * {@code == String.class} line SHORT-CIRCUITS before the call, so a String-keyed map is immune -- and a
 * non-Comparable key never gets past the {@code instanceof} on the line above. The condition is a key that
 * is Comparable and is not a String, in a bin of eight, at a table capacity of at least
 * {@code MIN_TREEIFY_CAPACITY} (64). {@code HashMap<Integer,...>} is exactly that.
 *
 * <p>THE ANSWER IS THE ERASURE, which is the decision already recorded for {@code Field.getGenericType} and
 * {@code Method.getGenericReturnType}: stock returns a {@code ParameterizedType} only where a
 * {@code Signature} attribute is present, and every caller asks {@code instanceof ParameterizedType} first.
 * {@code comparableClassFor} IS one of those callers, so with erasures its test is false and it returns null
 * -- whereupon HashMap orders the tree by {@code tieBreakOrder} (identity hash) instead of by
 * {@code compareTo}. That is HashMap's OWN documented fallback and not a divergence in behaviour: the tree is
 * still a valid red-black tree, and {@code TreeNode.find} with a null {@code kc} searches both subtrees, so
 * every lookup still succeeds. What it costs is the ORDER of the tree, which no caller can observe.
 *
 * <p>THE ARMS ARE BEHAVIOUR, NOT TREE SHAPE, for that reason -- {@code tieBreakOrder} is keyed on
 * {@code System.identityHashCode}, so the shape differs between two runs of the SAME binary and could never
 * be asserted. What IS asserted is that every key put into a treeified bin is found again, that a removal
 * removes exactly one, that an equal key REPLACES rather than duplicates, and -- reflectively, because stock
 * exposes no API for it -- that {@code table[0]} really is a {@code HashMap$TreeNode}. An arm that only
 * checked "the map still works" could pass over a bin that never treeified at all, which would make the
 * whole probe vacuous.
 *
 * <p>THE NON-COMPARABLE ARMS RUN FIRST, AND THEY ARE THE BUILT-IN COMPARISON. A key that implements nothing
 * treeifies through {@code tieBreakOrder} without ever reaching {@code getGenericInterfaces}, so those arms
 * pass BEFORE the fix as well: they say the probe's collision setup is right independently of the fix, and
 * they put the pre-fix halt at the first COMPARABLE arm rather than at the first put.
 *
 * <p>ONE SOURCE, BOTH WORLDS: against the real JDK the arms reach stock, against {@code guestsrc} the
 * overlay, and the gate is a byte-for-byte diff. Host control:
 * {@code java --add-opens java.base/java.util=ALL-UNNAMED} -- the reflective {@code table} read is a private
 * field of java.base, which needs no flag on metal because there is no module system there.
 */
public class TreeifyProbe
{
    static int failures;
    static int divergences;

    static void say(String name, String got, String want)
    {
        boolean ok = got.equals(want);
        if (!ok)
        {
            failures++;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + name + " = " + got + " (want " + want + ")");
    }

    /** An arm joe-ng answers DIFFERENTLY from a host JVM on purpose; counted apart so failures=0 travels. */
    static void diverge(String name, String got)
    {
        divergences++;
        System.out.println("diff " + name + " = " + got);
    }

    /** A marker interface with no type parameters, so the ORDER arm can tell generic from raw apart. */
    interface Marker
    {
    }

    /**
     * Comparable and not a String: the one key shape that reaches {@code getGenericInterfaces}. Every
     * instance hashes to 0, so they all land in bucket 0 whatever the table capacity -- the collisions are
     * exact rather than arithmetic on {@code Integer.hashCode}, which would have to be re-derived if the
     * table ever resized.
     */
    static final class Key implements Comparable<Key>
    {
        final int id;

        Key(int id)
        {
            this.id = id;
        }

        @Override
        public int hashCode()
        {
            return 0;
        }

        @Override
        public boolean equals(Object o)
        {
            return (o instanceof Key) && ((Key) o).id == id;
        }

        @Override
        public int compareTo(Key o)
        {
            return Integer.compare(id, o.id);
        }
    }

    /** The CONTROL key: collides identically and implements NOTHING, so comparableClassFor returns at its
     *  own first line and the tree is built through tieBreakOrder. Passes before the fix as well. */
    static final class NotComparable
    {
        final int id;

        NotComparable(int id)
        {
            this.id = id;
        }

        @Override
        public int hashCode()
        {
            return 0;
        }

        @Override
        public boolean equals(Object o)
        {
            return (o instanceof NotComparable) && ((NotComparable) o).id == id;
        }
    }

    /** Two interfaces, one generic and one not, so the ORDER of the answer is asserted rather than the set. */
    static final class Marked implements Marker, Comparable<Marked>
    {
        @Override
        public int compareTo(Marked o)
        {
            return 0;
        }
    }

    /** Implements nothing at all: the answer must be an EMPTY array, never null -- comparableClassFor
     *  iterates it unguarded once its null test passes. */
    static final class Plain
    {
    }

    /** Each element's ERASURE, which is what both worlds can print identically: a host JVM answers a
     *  ParameterizedType for a generic interface and joe-ng answers the raw Class, and the raw type of the
     *  first IS the second. */
    static String rawNames(Class<?> c)
    {
        Type[] ts = c.getGenericInterfaces();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ts.length; i++)
        {
            if (i > 0)
            {
                sb.append(",");
            }
            Type t = ts[i];
            Class<?> raw = (t instanceof ParameterizedType) ? (Class<?>) ((ParameterizedType) t).getRawType()
                                                            : (Class<?>) t;
            sb.append(raw.getName());
        }
        return sb.toString();
    }

    /** How many elements are ParameterizedType -- the one STATED divergence, since joe-ng has no generic
     *  signatures and answers the erasure for all of them. */
    static int parameterizedCount(Class<?> c)
    {
        Type[] ts = c.getGenericInterfaces();
        int n = 0;
        for (int i = 0; i < ts.length; i++)
        {
            if (ts[i] instanceof ParameterizedType)
            {
                n += 1;
            }
        }
        return n;
    }

    /** The class of {@code table[0]} -- a {@code Node} for a plain bin, a {@code TreeNode} for a treeified
     *  one. Stock exposes no API for this, and without it every behavioural arm would pass over a bin that
     *  never treeified. */
    static String binKind(Map<?, ?> m) throws Exception
    {
        Field f = HashMap.class.getDeclaredField("table");
        f.setAccessible(true);
        Object[] tab = (Object[]) f.get(m);
        if (tab == null || tab[0] == null)
        {
            return "empty";
        }
        String n = tab[0].getClass().getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }

    static final int N = 24;

    public static void main(String[] args) throws Exception
    {
        // ---- THE CONTROL, FIRST. A non-Comparable key treeifies without ever reaching
        // getGenericInterfaces, so these arms pass in BOTH states -- and running them first puts the pre-fix
        // halt at the first Comparable arm instead of at the first put, which is what makes the pre-fix log
        // read as a diagnosis rather than as "the probe does not start".
        HashMap<NotComparable, String> nc = new HashMap<>(128);
        for (int i = 0; i < N; i++)
        {
            nc.put(new NotComparable(i), "v" + i);
        }
        say("nc  size      ", "" + nc.size(), "" + N);
        say("nc  bin       ", binKind(nc), "HashMap$TreeNode");
        say("nc  all found ", "" + allFound(nc), "true");

        // ---- The member itself. Erasures, in DECLARATION ORDER, and an EMPTY array for a class that
        // implements nothing (comparableClassFor walks the result unguarded once its null test passes).
        say("gi  Key       ", rawNames(Key.class), "java.lang.Comparable");
        say("gi  Marked    ", rawNames(Marked.class), "TreeifyProbe$Marker,java.lang.Comparable");
        say("gi  Plain len ", "" + Plain.class.getGenericInterfaces().length, "0");
        say("gi  Object len", "" + Object.class.getGenericInterfaces().length, "0");
        say("gi  not null  ", "" + (Plain.class.getGenericInterfaces() != null), "true");

        // A FRESH array per call, like every other reflective accessor: a caller that mutated a shared one
        // would corrupt the next caller's answer, and nothing would report it.
        say("gi  fresh     ", "" + (Key.class.getGenericInterfaces() != Key.class.getGenericInterfaces()),
            "true");

        // ...and it agrees with getInterfaces() element for element, which is the claim "the answer is the
        // erasure" made as an assertion rather than as a comment.
        say("gi  == ifaces ", "" + agreesWithRaw(Marked.class), "true");

        // ---- The treeify path with a COMPARABLE key: the arms that HALT before the fix.
        HashMap<Key, String> m = new HashMap<>(128);
        for (int i = 0; i < N; i++)
        {
            m.put(new Key(i), "v" + i);
        }
        say("key size      ", "" + m.size(), "" + N);
        say("key bin       ", binKind(m), "HashMap$TreeNode");
        say("key all found ", "" + allFoundKey(m), "true");
        say("key miss      ", "" + m.get(new Key(999)), "null");
        say("key has 7     ", "" + m.containsKey(new Key(7)), "true");
        say("key has 999   ", "" + m.containsKey(new Key(999)), "false");

        // An EQUAL key must REPLACE rather than duplicate -- a tree that cannot find an existing key would
        // insert a second node and the size would grow.
        say("key replace   ", "" + m.put(new Key(7), "w7"), "v7");
        say("key size again", "" + m.size(), "" + N);
        say("key read back ", m.get(new Key(7)), "w7");

        // Removal exercises the tree's own deletion (and, below UNTREEIFY_THRESHOLD, its untreeify).
        for (int i = 0; i < 10; i++)
        {
            m.remove(new Key(i));
        }
        say("key size -10  ", "" + m.size(), "" + (N - 10));
        say("key gone 0..9 ", "" + noneFound(m, 0, 10), "true");
        say("key kept 10.. ", "" + allFoundRange(m, 10, N), "true");

        // ---- LinkedHashMap is the same tree code with a linked list across it, so insertion ORDER is a
        // stronger assertion than any HashMap arm can make: a tree rotation that disturbed the list would
        // show here and nowhere else.
        LinkedHashMap<Key, String> lm = new LinkedHashMap<>(128);
        for (int i = 0; i < N; i++)
        {
            lm.put(new Key(i), "v" + i);
        }
        say("lhm size      ", "" + lm.size(), "" + N);
        say("lhm bin       ", binKind(lm), "HashMap$TreeNode");
        say("lhm order     ", order(lm), expectedOrder());

        // ---- And the one STATED divergence: no generic signatures here, so nothing is parameterized.
        diverge("gi  Key parameterized", "" + parameterizedCount(Key.class));
        diverge("gi  Marked parameterized", "" + parameterizedCount(Marked.class));

        System.out.println("TreeifyProbe done, failures=" + failures
            + " divergences-unmet=" + divergences);
    }

    static boolean allFound(Map<NotComparable, String> m)
    {
        for (int i = 0; i < N; i++)
        {
            if (!("v" + i).equals(m.get(new NotComparable(i))))
            {
                return false;
            }
        }
        return true;
    }

    static boolean allFoundKey(Map<Key, String> m)
    {
        return allFoundRange(m, 0, N);
    }

    static boolean allFoundRange(Map<Key, String> m, int from, int to)
    {
        for (int i = from; i < to; i++)
        {
            if (!("v" + i).equals(m.get(new Key(i))))
            {
                return false;
            }
        }
        return true;
    }

    static boolean noneFound(Map<Key, String> m, int from, int to)
    {
        for (int i = from; i < to; i++)
        {
            if (m.get(new Key(i)) != null)
            {
                return false;
            }
        }
        return true;
    }

    static boolean agreesWithRaw(Class<?> c)
    {
        Type[] g = c.getGenericInterfaces();
        Class<?>[] r = c.getInterfaces();
        if (g.length != r.length)
        {
            return false;
        }
        for (int i = 0; i < g.length; i++)
        {
            Type t = g[i];
            Class<?> raw = (t instanceof ParameterizedType) ? (Class<?>) ((ParameterizedType) t).getRawType()
                                                            : (Class<?>) t;
            if (raw != r[i])
            {
                return false;
            }
        }
        return true;
    }

    static String order(LinkedHashMap<Key, String> m)
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Key, String> e : m.entrySet())
        {
            if (sb.length() > 0)
            {
                sb.append(",");
            }
            sb.append(e.getKey().id);
        }
        return sb.toString();
    }

    static String expectedOrder()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N; i++)
        {
            if (i > 0)
            {
                sb.append(",");
            }
            sb.append(i);
        }
        return sb.toString();
    }
}
