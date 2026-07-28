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

    public Iterator iterator()
    {
        return new LinkedListIterator(head);
    }
}
