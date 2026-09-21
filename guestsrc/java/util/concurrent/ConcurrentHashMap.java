/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-12
 */
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
    ArrayList<Map.Entry<K, V>> snapshot()
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

    /**
     * A concurrent {@link Set} backed by a map -- stock's {@code ConcurrentHashMap.newKeySet()}.
     *
     * <p>The overlay had no {@code newKeySet}, and a member a name-winning overlay does not declare CEASES TO
     * EXIST: the call resolved nowhere and surfaced on metal as a {@code DENYLIST TRAP} naming a list this
     * class is not on. That is the same trap this project has now paid for ten times; see the
     * "overlay-drops-stock-members" note in CLAUDE.md.
     */
    public static <K> KeySetView<K, Boolean> newKeySet()
    {
        return new KeySetView<K, Boolean>(new ConcurrentHashMap<K, Boolean>(), Boolean.TRUE);
    }

    /** As {@link #newKeySet()}; the capacity is a sizing hint with no observable effect on the Set contract. */
    public static <K> KeySetView<K, Boolean> newKeySet(int initialCapacity)
    {
        return new KeySetView<K, Boolean>(new ConcurrentHashMap<K, Boolean>(initialCapacity), Boolean.TRUE);
    }

    /**
     * The {@link Set} of keys, with {@code mappedValue} stored for every key {@code add} inserts.
     *
     * <p>The RETURN TYPE is what makes this worth declaring separately from the inherited {@code keySet()}:
     * stock code assigns it to a {@code KeySetView}, and a {@code Set} would not bind.
     */
    public KeySetView<K, V> keySet(V mappedValue)
    {
        if (mappedValue == null)
        {
            throw new NullPointerException("mappedValue");
        }
        return new KeySetView<K, V>(this, mappedValue);
    }

    /**
     * The {@link Set} of keys. Declared HERE, rather than inherited from {@link HashMap}, because stock's
     * return type is {@code KeySetView} and joe-ng resolves a call by name AND DESCRIPTOR: a stock caller's
     * {@code keySet()Ljava/util/concurrent/ConcurrentHashMap$KeySetView;} does not bind to an inherited
     * {@code ()Ljava/util/Set;}. javac emits the {@code Set}-returning bridge, so both descriptors exist and
     * an ordinary {@code Set}-typed caller is unaffected.
     *
     * <p>The view's {@code mappedValue} is null, so {@code add} throws {@link UnsupportedOperationException} --
     * exactly as in stock, and exactly what separates {@code keySet()} from {@code newKeySet()}.
     */
    @Override
    public KeySetView<K, V> keySet()
    {
        return new KeySetView<K, V>(this, null);
    }

    /**
     * The LEGACY {@link java.util.Hashtable}-compatible surface. CHM carries it because it was retrofitted
     * onto Hashtable's API, and stock java.base still uses it: {@code PropertyPermissionCollection.elements()}
     * is {@code (Enumeration) perms.elements()}, which is how this gap was found -- as a DENYLIST TRAP with an
     * EMPTY callee and {@code TRAPWIRE index=-1}, i.e. a late-resolution failure blaming a denylist CHM is not
     * on. The whole surface lands together rather than one member per boot, which is what has made this family
     * expensive nine times over.
     *
     * <p>Weakly consistent like every other view here: the enumeration walks a {@link #snapshot()}, so a
     * concurrent write neither appears nor throws.
     *
     * @return an enumeration of this map's keys
     */
    public java.util.Enumeration<K> keys()
    {
        final Iterator<Map.Entry<K, V>> it = snapshot().iterator();
        return new java.util.Enumeration<K>()
        {
            @Override
            public boolean hasMoreElements()
            {
                return it.hasNext();
            }

            @Override
            public K nextElement()
            {
                return it.next().getKey();
            }
        };
    }

    /** {@return an enumeration of this map's values} See {@link #keys()} for why the legacy surface exists. */
    public java.util.Enumeration<V> elements()
    {
        final Iterator<Map.Entry<K, V>> it = snapshot().iterator();
        return new java.util.Enumeration<V>()
        {
            @Override
            public boolean hasMoreElements()
            {
                return it.hasNext();
            }

            @Override
            public V nextElement()
            {
                return it.next().getValue();
            }
        };
    }

    /**
     * Legacy {@code Hashtable.contains}: tests for a VALUE, not a key. Named so confusingly that stock's own
     * javadoc warns about it; it is kept exact rather than aliased to {@code containsKey}, which is the wrong
     * answer in the shape a caller is most likely to write.
     *
     * @param value the value to look for
     * @return whether some key maps to {@code value}
     */
    public boolean contains(Object value)
    {
        return containsValue(value);
    }

    /**
     * {@return the number of mappings, as a {@code long}} Stock offers this because a CHM may exceed
     * {@code Integer.MAX_VALUE} entries; joe-ng's cannot, so it is {@code size()} widened.
     */
    public long mappingCount()
    {
        return size();
    }

    /**
     * A {@link Set} view of a map's KEYS, each mapped to one fixed value.
     *
     * <p>Weakly consistent like the other views here: {@link #iterator} walks a snapshot rather than failing
     * fast, which is the CHM semantics callers rely on. A null {@code value} makes {@link #add} unsupported,
     * exactly as in stock -- that is how {@code keySet()} differs from {@code newKeySet()}.
     */
    public static class KeySetView<K, V> extends AbstractSet<K> implements java.io.Serializable
    {
        private final ConcurrentHashMap<K, V> map;
        private final V value;

        KeySetView(ConcurrentHashMap<K, V> map, V value)
        {
            this.map = map;
            this.value = value;
        }

        /** The backing map, as stock exposes it. */
        public ConcurrentHashMap<K, V> getMap()
        {
            return map;
        }

        public int size()
        {
            return map.size();
        }

        public boolean isEmpty()
        {
            return map.isEmpty();
        }

        public boolean contains(Object o)
        {
            return map.containsKey(o);
        }

        public boolean add(K e)
        {
            if (value == null)
            {
                throw new UnsupportedOperationException();
            }
            return map.put(e, value) == null;
        }

        public boolean remove(Object o)
        {
            return map.remove(o) != null;
        }

        public void clear()
        {
            map.clear();
        }

        public Iterator<K> iterator()
        {
            ArrayList<K> keys = new ArrayList<K>();
            for (Map.Entry<K, V> e : map.snapshot())
            {
                keys.add(e.getKey());
            }
            return keys.iterator();
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
