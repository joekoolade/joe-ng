package demo;

/**
 * The shape that broke the zip JUnit harness, reduced: a functional interface with a STATIC factory, exactly
 * like JUnit's {@code Arguments}. {@code registerInterface} gives itable indices to {@code isVirtual} methods
 * only, so {@link #tag()} gets no itable index, no vtable slot and no static cell — it is registered nowhere
 * dispatchable, even once the interface itself is loaded.
 */
public interface RtaIface
{
    String describe();

    static String tag()
    {
        return "iface-static";
    }
}
