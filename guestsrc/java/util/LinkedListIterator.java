package java.util;

/**
 * {@link LinkedList}'s {@link Iterator}: walks the node chain directly from the head (O(1) per {@code next}).
 * Holds only a {@code LinkedListNode} cursor -- no reference back to {@code LinkedList}, so there is no class
 * cycle (cf. the ArrayList<->iterator cycle that force-load ordering broke).
 */
final class LinkedListIterator implements Iterator
{
    private LinkedListNode cursor;

    LinkedListIterator(LinkedListNode head)
    {
        this.cursor = head;
    }

    public boolean hasNext()
    {
        return cursor != null;
    }

    public Object next()
    {
        Object e = cursor.item;
        cursor = cursor.next;
        return e;
    }
}
