package java.util;

/**
 * A JDK-free, singly-linked {@code java/util/LinkedList} -- a SECOND {@link List} implementation, so the same
 * List-typed call sites (the demo's {@code totalLen(List)} and enhanced-for) dispatch polymorphically across
 * this and {@link ArrayList}. Appends at the tail; {@code get(i)} walks from the head. {@code iterator()}
 * returns a node-walking {@link LinkedListIterator} (O(1) per step, vs the indexed get()).
 */
public class LinkedList implements List
{
    private LinkedListNode head;
    private LinkedListNode tail;
    private int size;

    public LinkedList()
    {
        head = null;
        tail = null;
        size = 0;
    }

    public boolean add(Object e)
    {
        LinkedListNode n = new LinkedListNode(e);
        if (tail == null)
        {
            head = n;                                   // first element
        }
        else
        {
            tail.next = n;
        }
        tail = n;
        size = size + 1;
        return true;
    }

    public Object get(int index)
    {
        LinkedListNode n = head;
        int i = 0;
        while (i < index)
        {
            n = n.next;
            i = i + 1;
        }
        return n.item;
    }

    public int size()
    {
        return size;
    }

    public boolean isEmpty()
    {
        return size == 0;
    }

    /** First index whose element {@code o.equals(...)} (content), walking the chain, or -1. */
    public int indexOf(Object o)
    {
        LinkedListNode n = head;
        int i = 0;
        while (n != null)
        {
            if (o.equals(n.item))
            {
                return i;
            }
            n = n.next;
            i = i + 1;
        }
        return -1;
    }

    public boolean contains(Object o)
    {
        return indexOf(o) >= 0;
    }

    /** Remove by index: relink around the node (fixing head/tail), return its element. */
    public Object remove(int index)
    {
        LinkedListNode prev = null;
        LinkedListNode n = head;
        int i = 0;
        while (i < index)
        {
            prev = n;
            n = n.next;
            i = i + 1;
        }
        if (prev == null)
        {
            head = n.next;                              // removed the head
        }
        else
        {
            prev.next = n.next;
        }
        if (n == tail)
        {
            tail = prev;                                // removed the tail
        }
        size = size - 1;
        return n.item;
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

    public Iterator iterator()
    {
        return new LinkedListIterator(head);
    }
}
