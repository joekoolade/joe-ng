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

    /** Calls into another class (Counter) — the metal writer's cross-class discovery target. */
    public static int readCounter()
    {
        Counter.bump();
        return Counter.get();
    }

    /** {@code new Robot(); s.speak()} across classes — the metal writer's cross-class interface target. */
    public static int viaSpeaker()
    {
        Speaker s = new Robot();
        return s.speak();
    }

    /**
     * The metal writer's capstone: one closure exercising every relocation kind across many
     * classes — new + invokeinterface (Robot/Speaker), new + invokevirtual (Dog/Animal),
     * instanceof (Cell), ldc-string, a cross-class call into throw/catch (MyExc.probe), and a
     * cross-class static (Counter.count). Sums to 82+87+1+90+1+1 = 262.
     */
    public static int capstone()
    {
        Speaker s = new Robot();
        int a = s.speak();                             // 0x52 — new + invokeinterface
        int b = new Dog().sound();                     // 0x57 — new + invokevirtual
        int c = new Cell(0) instanceof Cell ? 1 : 0;   // 1    — new + instanceof
        int d = Magic.bytes("Z")[0];                   // 0x5A — ldc-string
        int e = MyExc.probe();                         // 1    — cross-class call -> throw/catch
        Counter.bump();                                // cross-class static
        int f = Counter.get();                         // 1
        return a + b + c + d + e + f;                  // 262
    }

    /** {@code new Cell(v); c.get()} — the metal writer's invokevirtual (vtable-dispatch) target. */
    public static int viaVirtual(int v)
    {
        Cell c = new Cell(v);
        return c.get();
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
