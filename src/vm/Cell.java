package vm;

/**
 * A minimal heap object used to exercise the object model end to end: a
 * constructor (writes a field via {@code putfield}) and a directly-accessed
 * instance field (read/written via {@code getfield}/{@code putfield}). One
 * 8-byte field, so an instance is {@code header(16) + 8 = 24} bytes
 * (objectmodel.ObjectModel). No methods, so no vtable is exercised yet
 * (invokevirtual is a later step).
 */
public final class Cell {
    int value;

    public Cell(int v) {
        value = v;
    }
}
