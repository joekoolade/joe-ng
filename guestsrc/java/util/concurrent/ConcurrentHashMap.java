package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * JDK-free {@code ConcurrentHashMap} for joe-ng. The stock class is one of the heaviest VarHandle/Unsafe users
 * in java.base (denylisted on metal); joe-ng runs on a single unparked core, so a plain hash map with
 * WEAKLY-CONSISTENT views is behaviourally sufficient. Storage + the {@link Map} surface come from the real
 * {@link HashMap} (O(1)); only {@code entrySet()}/{@code values()} are overridden to give snapshot iterators
 * (no fail-fast {@code ConcurrentModificationException}) with write-through entries -- the CHM semantics the
 * tests probe. The {@link ConcurrentMap} default methods (putIfAbsent/replace/...) are inherited.
 */
public class ConcurrentHashMap<K, V> extends HashMap<K, V> implements ConcurrentMap<K, V>
{
    public ConcurrentHashMap()
    {
    }

    public ConcurrentHashMap(int initialCapacity)
    {
        super(initialCapacity);
    }

    /**
     * The remaining stock constructors. A NAME-WINNING OVERLAY SILENTLY DROPS whatever it does not declare, so
     * an undeclared one resolves nowhere and surfaces as a DENYLIST TRAP that blames a denylist CHM is not even
     * on. JUnit's console launcher calls {@code <init>(IFI)V}, and the trap said only "call into a pruned
     * class" until the link-failure report was taught to name the descriptor.
     *
     * <p>{@code loadFactor} and {@code concurrencyLevel} are accepted and IGNORED, which is exactly what they
     * are: sizing hints with no observable effect on the Map contract. The backing {@link HashMap} chooses its
     * own load factor, and joe-ng's map is not striped, so a concurrency level has nothing to tune. They are
     * deliberately not passed to {@code super}: the float is never used in arithmetic here, so this adds no
     * floating-point codegen to a path that had none.
     */
    public ConcurrentHashMap(int initialCapacity, float loadFactor)
    {
        super(initialCapacity);
    }

    public ConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel)
    {
        super(initialCapacity);
    }

    /** Copy constructor, as {@code ConcurrentHashMap(Map)}. */
    public ConcurrentHashMap(Map<? extends K, ? extends V> m)
    {
        putAll(m);
    }

    /** Snapshot the live entries (key+value) as fresh write-through entries -- the basis of every view. */
    private ArrayList<Map.Entry<K, V>> snapshot()
    {
        ArrayList<Map.Entry<K, V>> snap = new ArrayList<Map.Entry<K, V>>();
        for (Map.Entry<K, V> e : super.entrySet())
        {
            snap.add(new WriteThroughEntry(e.getKey(), e.getValue()));
        }
        return snap;
    }

    /** An entry whose {@code setValue} writes back into the map (CHM's entrySet iterator supports this). */
    final class WriteThroughEntry extends AbstractMap.SimpleEntry<K, V>
    {
        WriteThroughEntry(K key, V value)
        {
            super(key, value);
        }

        public V setValue(V value)
        {
            ConcurrentHashMap.this.put(getKey(), value);
            return super.setValue(value);
        }
    }

    public Set<Map.Entry<K, V>> entrySet()
    {
        return new EntryView();
    }

    final class EntryView extends AbstractSet<Map.Entry<K, V>>
    {
        public int size()
        {
            return ConcurrentHashMap.this.size();
        }

        public Iterator<Map.Entry<K, V>> iterator()
        {
            return snapshot().iterator();
        }

        public boolean removeIf(Predicate<? super Map.Entry<K, V>> filter)
        {
            boolean removed = false;
            for (Map.Entry<K, V> e : snapshot())
            {
                if (filter.test(e))
                {
                    ConcurrentHashMap.this.remove(e.getKey());
                    removed = true;
                }
            }
            return removed;
        }
    }

    public Collection<V> values()
    {
        return new ValueView();
    }

    final class ValueView extends AbstractCollection<V>
    {
        public int size()
        {
            return ConcurrentHashMap.this.size();
        }

        public Iterator<V> iterator()
        {
            ArrayList<V> vals = new ArrayList<V>();
            for (Map.Entry<K, V> e : snapshot())
            {
                vals.add(e.getValue());
            }
            return vals.iterator();
        }

        public boolean removeIf(Predicate<? super V> filter)
        {
            boolean removed = false;
            for (Map.Entry<K, V> e : snapshot())
            {
                if (filter.test(e.getValue()))
                {
                    ConcurrentHashMap.this.remove(e.getKey());
                    removed = true;
                }
            }
            return removed;
        }
    }

    public void forEach(BiConsumer<? super K, ? super V> action)
    {
        for (Map.Entry<K, V> e : snapshot())
        {
            action.accept(e.getKey(), e.getValue());
        }
    }
}
