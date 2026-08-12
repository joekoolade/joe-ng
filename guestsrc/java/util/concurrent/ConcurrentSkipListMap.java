package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * JDK-free {@code ConcurrentSkipListMap} for joe-ng: like {@link ConcurrentHashMap}, but the stock skip-list is
 * a heavy VarHandle user, so this backs a real (sorted) {@link TreeMap} and adds weakly-consistent snapshot
 * views. Single core, so no concurrency control is needed.
 */
public class ConcurrentSkipListMap<K, V> extends TreeMap<K, V> implements ConcurrentMap<K, V>
{
    public ConcurrentSkipListMap()
    {
    }

    // ConcurrentMap methods: TreeMap doesn't concretely override the Map/ConcurrentMap default diamond, so
    // provide them explicitly (single core, so no atomicity needed).
    public V putIfAbsent(K key, V value)
    {
        V v = get(key);
        return v == null ? put(key, value) : v;
    }

    public boolean remove(Object key, Object value)
    {
        V cur = get(key);
        if (cur == null ? value == null : cur.equals(value))
        {
            remove(key);
            return true;
        }
        return false;
    }

    public boolean replace(K key, V oldValue, V newValue)
    {
        V cur = get(key);
        if (containsKey(key) && (cur == null ? oldValue == null : cur.equals(oldValue)))
        {
            put(key, newValue);
            return true;
        }
        return false;
    }

    public V replace(K key, V value)
    {
        return containsKey(key) ? put(key, value) : null;
    }

    private ArrayList<Map.Entry<K, V>> snapshot()
    {
        ArrayList<Map.Entry<K, V>> snap = new ArrayList<Map.Entry<K, V>>();
        for (Map.Entry<K, V> e : super.entrySet())
        {
            snap.add(new WriteThroughEntry(e.getKey(), e.getValue()));
        }
        return snap;
    }

    final class WriteThroughEntry extends AbstractMap.SimpleEntry<K, V>
    {
        WriteThroughEntry(K key, V value)
        {
            super(key, value);
        }

        public V setValue(V value)
        {
            ConcurrentSkipListMap.this.put(getKey(), value);
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
            return ConcurrentSkipListMap.this.size();
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
                    ConcurrentSkipListMap.this.remove(e.getKey());
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
            return ConcurrentSkipListMap.this.size();
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
                    ConcurrentSkipListMap.this.remove(e.getKey());
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
