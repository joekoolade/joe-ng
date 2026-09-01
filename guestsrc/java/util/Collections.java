package java.util;

/**
 * A JDK-free, mini {@code java/util/Collections}: the handful of statics stock code on metal actually
 * reaches. {@code sort(List)} operates on
 * any {@link List} through the interface (get/set/size are invokeinterface, so it sorts an ArrayList or a
 * LinkedList alike), comparing elements GENERICALLY via {@link Comparable} -- {@code ((Comparable) x).compareTo(y)}
 * is invokeinterface {@code Comparable.compareTo(Object)}, which for a String element dispatches through the
 * synthetic {@code compareTo(Object)} bridge into the typed {@code compareTo(String)}. Plain bubble sort (small
 * demand-loaded lists; O(n^2) get/set, fine here).
 */
public final class Collections
{
    public static void sort(List list)
    {
        int n = list.size();
        int i = 0;
        while (i < n - 1)
        {
            int j = 0;
            while (j < n - 1 - i)
            {
                Object x = list.get(j);
                Object y = list.get(j + 1);
                if (((Comparable) x).compareTo(y) > 0)  // generic compare -> String's bridge -> compareTo(String)
                {
                    list.set(j, y);                     // out of order -> swap through the List interface
                    list.set(j + 1, x);
                }
                j = j + 1;
            }
            i = i + 1;
        }
    }

    /**
     * Wrap {@code s} as an "unmodifiable" set. In the paths we run (a {@link java.util.stream.Collector}'s
     * {@code characteristics()} set, built in {@code Collectors.<clinit>}) the result is only ever read
     * ({@code contains}/{@code iterator}/{@code isEmpty}) and never mutated, so returning the backing set
     * directly is behaviourally exact here — we simply don't enforce immutability yet.
     */
    public static Set unmodifiableSet(Set s)
    {
        return s;
    }

    /**
     * The {@code List}/{@code Collection}/{@code Map} siblings of {@link #unmodifiableSet}, with the same
     * caveat and the same reason for existing: a name-winning overlay silently drops whatever it does not
     * declare, so an undeclared one resolves nowhere and ends in a denylist trap rather than a missing-method
     * error. JUnit's picocli builds its default colour scheme out of {@code unmodifiableList}, and the
     * trap-wire dump showed {@code unmodifiableCollection} queued behind it.
     *
     * <p>Returning the backing collection is behaviourally exact wherever the result is only READ, which is
     * every path joe-ng runs; immutability is simply not enforced yet. A caller that mutates the result would
     * silently succeed instead of throwing UnsupportedOperationException -- the one way this differs from
     * stock, and the reason these are documented rather than quietly aliased.
     */
    public static <T> List<T> unmodifiableList(List<T> l)
    {
        return l;
    }

    public static <T> Collection<T> unmodifiableCollection(Collection<T> c)
    {
        return c;
    }

    public static <K, V> Map<K, V> unmodifiableMap(Map<K, V> m)
    {
        return m;
    }

    /** An empty set ({@code Collections.emptySet()}) — mutable, but used read-only in the paths we run. */
    public static Set emptySet()
    {
        return new HashSet();
    }

    /** Empty {@code List}/{@code Map}, the siblings of {@link #emptySet()} and equally read-only in practice. */
    public static <T> List<T> emptyList()
    {
        return new ArrayList<T>();
    }

    public static <K, V> Map<K, V> emptyMap()
    {
        return new HashMap<K, V>();
    }

    /** A single-element immutable-by-convention list, as {@code Collections.singletonList}. */
    public static <T> List<T> singletonList(T o)
    {
        ArrayList<T> l = new ArrayList<T>();
        l.add(o);
        return l;
    }

    /** As {@link #sort(List)}, but ordered by a caller-supplied {@link Comparator} (typically a lambda). */
    public static void sort(List list, Comparator cmp)
    {
        int n = list.size();
        int i = 0;
        while (i < n - 1)
        {
            int j = 0;
            while (j < n - 1 - i)
            {
                Object x = list.get(j);
                Object y = list.get(j + 1);
                if (cmp.compare(x, y) > 0)              // out of order per the comparator -> swap
                {
                    list.set(j, y);
                    list.set(j + 1, x);
                }
                j = j + 1;
            }
            i = i + 1;
        }
    }

    /**
     * The {@link Enumeration} view stock code still asks for -- {@code SequenceInputStream(s1, s2)} is
     * {@code this(Collections.enumeration(Arrays.asList(s1, s2)))}, which is how GZIPInputStream reads its
     * trailer. Backed by the collection's own iterator, so it works for any List or Set.
     *
     * <p>Deliberately a NAMED nested class rather than an anonymous one: an anonymous class capturing a
     * local becomes a synthetic {@code val$} field, and captured-field initialisation inside a deep JDK
     * hierarchy is the one shape with an open corruption bug (see the stream notes in PLAN.md).
     */
    public static <T> Enumeration<T> enumeration(Collection<T> c)
    {
        return new IteratorEnumeration<T>(c.iterator());
    }

    private static final class IteratorEnumeration<T> implements Enumeration<T>
    {
        private final Iterator<T> it;

        IteratorEnumeration(Iterator<T> it)
        {
            this.it = it;
        }

        public boolean hasMoreElements()
        {
            return it.hasNext();
        }

        public T nextElement()
        {
            return it.next();
        }
    }

    /**
     * The remaining statics {@code make overlaycheck} listed as REFERENCED but dropped. Each traps if reached,
     * so they are declared rather than left to a boot to discover.
     *
     * <p>The {@code synchronized*} wrappers return the backing collection unchanged. That is honest for joe-ng
     * as it stands -- what stock buys with them is a lock, and every reached caller here uses the result from
     * one task -- but it is NOT a general substitute: a genuinely shared mutable collection would race. Noted
     * so the day that matters, this is the line to change.
     */
    public static <T> boolean addAll(Collection<? super T> c, T... elements)
    {
        boolean changed = false;
        for (int i = 0; i < elements.length; i++)
        {
            if (c.add(elements[i]))
            {
                changed = true;
            }
        }
        return changed;
    }

    /** Drain an {@link Enumeration} into a list -- the inverse of the existing {@code enumeration}. */
    public static <T> ArrayList<T> list(Enumeration<T> e)
    {
        ArrayList<T> out = new ArrayList<T>();
        while (e.hasMoreElements())
        {
            out.add(e.nextElement());
        }
        return out;
    }

    public static void reverse(List<?> list)
    {
        int lo = 0;
        int hi = list.size() - 1;
        while (lo < hi)
        {
            swapRaw(list, lo, hi);
            lo = lo + 1;
            hi = hi - 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static void swapRaw(List list, int i, int j)
    {
        Object a = list.get(i);
        list.set(i, list.get(j));
        list.set(j, a);
    }

    /** A comparator's reverse; {@code reverseOrder(null)} is natural-order reversed, as stock. */
    public static <T> Comparator<T> reverseOrder(Comparator<T> cmp)
    {
        if (cmp == null)
        {
            return (T a, T b) -> ((Comparable<T>) b).compareTo(a);
        }
        return (T a, T b) -> cmp.compare(b, a);
    }

    /** Fisher-Yates, as stock: the ONLY correct shuffle here -- picking random pairs is not uniform. */
    public static void shuffle(List<?> list, Random rnd)
    {
        for (int i = list.size() - 1; i > 0; i--)
        {
            swapRaw(list, i, rnd.nextInt(i + 1));
        }
    }

    public static void shuffle(List<?> list)
    {
        shuffle(list, new Random());
    }

    public static <T> Set<T> singleton(T o)
    {
        HashSet<T> s = new HashSet<T>();
        s.add(o);
        return s;
    }

    public static <K, V> Map<K, V> singletonMap(K key, V value)
    {
        HashMap<K, V> m = new HashMap<K, V>();
        m.put(key, value);
        return m;
    }

    public static <T> List<T> synchronizedList(List<T> l)
    {
        return l;
    }

    public static <K, V> Map<K, V> synchronizedMap(Map<K, V> m)
    {
        return m;
    }

    public static <T> Set<T> synchronizedSet(Set<T> s)
    {
        return s;
    }

    public static <T> Collection<T> synchronizedCollection(Collection<T> c)
    {
        return c;
    }
}
