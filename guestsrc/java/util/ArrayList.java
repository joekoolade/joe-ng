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
}
