package vm;

import magic.Magic;

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

    /** Allocate a Cell and read its field back — a self-contained {@code new} + getfield,
     *  the metal image-writer's object-allocation test target (M5.5c step 3b.2). */
    public static int make(int v)
    {
        return new Cell(v).value;
    }

    /** {@code new Cell(0) instanceof Cell} — the metal writer's {@code type}/instanceof target. */
    public static int selfCheck()
    {
        return new Cell(0) instanceof Cell ? 1 : 0;
    }

    /** {@code Magic.bytes("Z")[0]} — the metal writer's ldc-string / interned-byte[] target. */
    public static int tag()
    {
        return Magic.bytes("Z")[0];
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
