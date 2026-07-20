package vm;

/**
 * Also implements {@link Greeter}, but declares {@code filler()} first so
 * {@code greet()} lands at vtable <em>slot 1</em>, not slot 0 as in {@link Alpha}.
 *
 * <p>That mismatch is the whole point of the test: an {@code invokeinterface} site
 * has one compile-time target for both receivers, so resolving it to a fixed vtable
 * slot must get one of them wrong (calling Beta's {@code filler} instead of its
 * {@code greet}). Only an itable — indexed by the interface method's global index,
 * with each class mapping that index to its own implementation — dispatches both
 * correctly.
 */
public class Beta implements Greeter
{
    int filler()
    {
        return 7;                // vtable slot 0 — shifts greet() down one
    }

    @Override
    public int greet()
    {
        return 22;               // vtable slot 1
    }
}
