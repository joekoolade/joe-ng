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

    /** An empty set ({@code Collections.emptySet()}) — mutable, but used read-only in the paths we run. */
    public static Set emptySet()
    {
        return new HashSet();
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
}
