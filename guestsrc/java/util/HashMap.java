package java.util;

/**
 * A JDK-free, real-shaped {@code java/util/HashMap} (open addressing / linear probing): parallel
 * {@code Object[]} key/value tables sized to a power of two, indexed by {@code key.hashCode() & (cap-1)}
 * with linear probe + {@code key.equals()} to resolve collisions. Object-keyed — so {@code key.hashCode()}/
 * {@code key.equals()} (static type Object) dispatch into the key's real overrides (e.g. String's), which
 * is the whole point of the mini {@link Object} root. Fixed capacity (no resize) — enough for demand-loaded
 * demos. Compiled as a {@code java.base} patch so it carries the real name.
 * Implements the mini {@link Map} so callers can drive it by the interface, and exposes {@code keySet()}/
 * {@code values()} as {@link List} snapshots for iteration.
 */
public class HashMap implements Map
{
    private Object[] keys;
    private Object[] vals;
    private int size;
    private int cap;

    public HashMap()
    {
        cap = 16;
        keys = new Object[cap];
        vals = new Object[cap];
        size = 0;
    }

    private int slotFor(Object key)
    {
        int h = key.hashCode() & (cap - 1);
        while (keys[h] != null && !keys[h].equals(key))     // probe until an empty slot or a matching key
        {
            h = (h + 1) & (cap - 1);
        }
        return h;
    }

    public Object put(Object key, Object value)
    {
        int h = slotFor(key);
        Object old = vals[h];
        if (keys[h] == null)
        {
            size = size + 1;
        }
        keys[h] = key;
        vals[h] = value;
        return old;
    }

    public Object get(Object key)
    {
        int h = slotFor(key);
        if (keys[h] == null)
        {
            return null;
        }
        return vals[h];
    }

    public boolean containsKey(Object key)
    {
        return keys[slotFor(key)] != null;
    }

    /** Value for {@code key}, or {@code defaultValue} if absent. */
    public Object getOrDefault(Object key, Object defaultValue)
    {
        int i = slotFor(key);
        return keys[i] == null ? defaultValue : vals[i];
    }

    /**
     * Remove {@code key}, returning its old value (or null). Open-addressing backward-shift deletion: after
     * clearing the slot, pull following entries of the same probe cluster back into the gap, so no tombstones
     * are needed and {@code get}'s probe-until-null still finds every remaining key.
     */
    public Object remove(Object key)
    {
        int i = slotFor(key);
        if (keys[i] == null)
        {
            return null;                                // not present
        }
        Object old = vals[i];
        size = size - 1;
        int j = i;
        while (true)
        {
            keys[i] = null;
            vals[i] = null;
            while (true)
            {
                j = (j + 1) & (cap - 1);
                if (keys[j] == null)
                {
                    return old;                         // cluster ended
                }
                int k = keys[j].hashCode() & (cap - 1);   // this entry's ideal slot
                boolean keep = i <= j ? (i < k && k <= j) : (i < k || k <= j);
                if (!keep)
                {
                    break;                              // keys[j] must shift back into the gap at i
                }
            }
            keys[i] = keys[j];
            vals[i] = vals[j];
            i = j;                                      // the gap is now at j
        }
    }

    public int size()
    {
        return size;
    }

    /** A fresh {@link List} of the live keys (open-addressing order). */
    public List keySet()
    {
        List ks = new ArrayList();
        int i = 0;
        while (i < cap)
        {
            if (keys[i] != null)
            {
                ks.add(keys[i]);
            }
            i = i + 1;
        }
        return ks;
    }

    /** A fresh {@link List} of the live values, parallel to {@link #keySet}. */
    public List values()
    {
        List vs = new ArrayList();
        int i = 0;
        while (i < cap)
        {
            if (keys[i] != null)
            {
                vs.add(vals[i]);
            }
            i = i + 1;
        }
        return vs;
    }
}
