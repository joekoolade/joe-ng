package java.util;

/**
 * {@link ArrayList}'s {@link Iterator}, kept as a standalone class (not an inner class -- avoids synthetic
 * outer-access accessors). It holds its list by the {@link List} INTERFACE, not the concrete {@code ArrayList}:
 * that both breaks the ArrayList<->iterator class cycle (so neither is force-loaded before the other is
 * registered) and routes the cursor walk's {@code size()}/{@code get(int)} through invokeinterface too.
 * {@code ArrayList.iterator()} returns one of these; the demo's enhanced-for drives {@code hasNext}/{@code next}.
 */
final class ArrayListIterator implements Iterator
{
    private final List list;
    private int cursor;

    ArrayListIterator(List list)
    {
        this.list = list;
        this.cursor = 0;
    }

    public boolean hasNext()
    {
        return cursor < list.size();
    }

    public Object next()
    {
        Object e = list.get(cursor);
        cursor = cursor + 1;
        return e;
    }
}
