package java.util;

/**
 * A JDK-free, real-shaped {@code java/util/ArrayList}: an {@code Object[]} + size, grown lazily via the
 * provided {@code System.arraycopy} native. Enough of the real surface (add/get/size/isEmpty) for real
 * code that stores references. Element access rides the JIT's array bounds checks (a bad index throws
 * {@link ArrayIndexOutOfBoundsException}). Compiled as a {@code java.base} patch so it carries the real name.
 * Implements the mini {@link List} so callers can hold it by the interface and dispatch via invokeinterface.
 */
public class ArrayList implements List
{
    private Object[] elementData;
    private int size;

    public ArrayList()
    {
        elementData = new Object[8];
        size = 0;
    }

    public boolean add(Object e)
    {
        if (size >= elementData.length)
        {
            Object[] nv = new Object[elementData.length * 2];
            System.arraycopy(elementData, 0, nv, 0, size);
            elementData = nv;
        }
        elementData[size] = e;
        size = size + 1;
        return true;
    }

    public Object get(int i)
    {
        return elementData[i];
    }

    public int size()
    {
        return size;
    }

    public boolean isEmpty()
    {
        return size == 0;
    }

    /** First index whose element {@code o.equals(...)} (content, via the element's real equals), or -1. */
    public int indexOf(Object o)
    {
        int i = 0;
        while (i < size)
        {
            if (o.equals(elementData[i]))
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    public boolean contains(Object o)
    {
        return indexOf(o) >= 0;
    }

    /** Remove by index: shift the tail down one, null the vacated slot, return the old element. */
    public Object remove(int index)
    {
        Object old = elementData[index];
        int i = index;
        while (i < size - 1)
        {
            elementData[i] = elementData[i + 1];
            i = i + 1;
        }
        size = size - 1;
        elementData[size] = null;                       // drop the stale reference
        return old;
    }

    /** Remove the first element equal to {@code o} (by the element's real equals); true if one was removed. */
    public boolean remove(Object o)
    {
        int idx = indexOf(o);
        if (idx < 0)
        {
            return false;
        }
        remove(idx);
        return true;
    }

    /** A fresh cursor over this list (inherited from {@code List}/{@code Iterable}); drives the enhanced-for. */
    public Iterator iterator()
    {
        return new ArrayListIterator(this);
    }
}
