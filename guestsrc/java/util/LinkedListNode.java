package java.util;

/**
 * A singly-linked node for the mini {@link LinkedList}: an element plus the next node. Package-private with
 * package-private fields so {@code LinkedList} and {@code LinkedListIterator} reach them with plain
 * getfield/putfield (same package -> no synthetic accessors).
 */
final class LinkedListNode
{
    Object item;
    LinkedListNode next;

    LinkedListNode(Object item)
    {
        this.item = item;
        this.next = null;
    }
}
