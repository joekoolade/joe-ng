package vm;

/**
 * A minimal heap object exercising the object model end to end: a constructor
 * ({@code putfield}), a directly-accessed field ({@code getfield}/{@code putfield}),
 * and two virtual methods dispatched through the TIB vtable ({@code invokevirtual}).
 * One 8-byte field, so an instance is {@code header(16) + 8 = 24} bytes
 * (objectmodel.ObjectModel). Its vtable is {@code [get(0), inc(1)]}.
 */
public final class Cell
{
    int value;

    public Cell(int v)
    {
        value = v;
    }

    public int get()
    {
        return value;
    }

    public void inc()
    {
        value = value + 1;
    }
}
