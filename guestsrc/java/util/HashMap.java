package java.util;

/**
 * A JDK-free, real-shaped {@code java/util/HashMap} (open addressing / linear probing): parallel
 * {@code Object[]} key/value tables sized to a power of two, indexed by {@code key.hashCode() & (cap-1)}
 * with linear probe + {@code key.equals()} to resolve collisions. Object-keyed — so {@code key.hashCode()}/
 * {@code key.equals()} (static type Object) dispatch into the key's real overrides (e.g. String's), which
 * is the whole point of the mini {@link Object} root. Fixed capacity (no resize) — enough for demand-loaded
 * demos. Compiled as a {@code java.base} patch so it carries the real name.
 */
public class HashMap
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

    public int size()
    {
        return size;
    }
}
