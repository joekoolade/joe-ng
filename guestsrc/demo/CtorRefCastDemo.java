package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The console launcher's execution-setup blocker, reduced. JUnit's ListenerRegistry is generic: its factory
 * is a CONSTRUCTOR REFERENCE {@code CompositeEngineExecutionListener::new} held as a
 * {@code Function<List<T>,T>}, and {@code getCompositeListener()} returns the erased {@code T} -- so the
 * CALLER emits {@code checkcast EngineExecutionListener} on the result. On metal that cast threw
 * ClassCastException.
 *
 * <p>THE CONDITION, not just the shape: the object must come back through an ERASED generic return (so the
 * cast is at the caller, not inside the factory), and the cast target must be an INTERFACE the constructed
 * class implements -- which is answered from the object's itable DIRECTORY, not its superclass chain.
 */
public final class CtorRefCastDemo
{
    public interface Listener
    {
        String name();
    }

    /** Implements the interface and takes a List, exactly like CompositeEngineExecutionListener. */
    public static final class Composite implements Listener
    {
        private final List<Listener> kids;

        public Composite(List<Listener> kids)
        {
            this.kids = kids;
        }

        public String name()
        {
            return "composite:" + kids.size();
        }
    }

    /** Generic holder, so getComposite()'s return type erases to Object and the caller casts. */
    public static final class Registry<T>
    {
        private final Function<List<T>, T> factory;
        private final List<T> items = new ArrayList<T>();

        Registry(Function<List<T>, T> factory)
        {
            this.factory = factory;
        }

        void add(T t)
        {
            items.add(t);
        }

        T getComposite()
        {
            return factory.apply(items);
        }
    }

    static Registry<Listener> forListeners()
    {
        return new Registry<Listener>(Composite::new);      // kind 8: REF_newInvokeSpecial
    }

    public static void main(String[] args)
    {
        System.out.println("constructor-reference factory + erased cast:");

        Registry<Listener> reg = forListeners();
        Object raw = reg.getComposite();
        System.out.println("  raw nonNull    = " + (raw != null ? 1 : 0) + " (want 1)");
        System.out.println("  raw class      = " + (raw == null ? "NULL" : raw.getClass().getName())
                + " (want ...$Composite)");
        System.out.println("  instanceof     = " + (raw instanceof Listener ? 1 : 0) + " (want 1)");

        // THE FAILING OPERATION: checkcast to the interface, exactly as the launcher's caller does.
        Listener l = (Listener) raw;
        System.out.println("  checkcast ok   = " + (l != null ? 1 : 0) + " (want 1)");
        System.out.println("  call through   = " + l.name() + " (want composite:0)");

        // Non-empty, so the constructor argument is proved to arrive.
        Registry<Listener> r2 = forListeners();
        r2.add(new Composite(new ArrayList<Listener>()));
        Listener l2 = (Listener) r2.getComposite();
        System.out.println("  ctor arg used  = " + l2.name() + " (want composite:1)");

        // A DELIBERATELY FAILING cast: the exception must now NAME both classes, which is what a bare
        // ClassCastException could not do -- and is what the launcher's failure in EngineExecutionOrchestrator
        // cost a disassembly to work out.
        Object notAListener = "a string";
        try
        {
            Listener bad = (Listener) notAListener;
            System.out.println("  cce thrown     = 0 (want 1) <== BUG, the cast should have failed");
        }
        catch (ClassCastException e)
        {
            System.out.println("  cce thrown     = 1 (want 1)");
            System.out.println("  cce message    = " + e.getMessage()
                    + " (want: class java.lang.String cannot be cast to class ...$Listener)");
        }

        // THE LAUNCHER'S CONDITION, which the arms above do NOT create: a constructor reference compiled
        // LATE, whose target class RTA never pulled. Reached reflectively so nothing statically names it.
        try
        {
            Class<?> c = Class.forName("demo.CtorRefLate");
            Object r = c.getDeclaredMethod("make").invoke(null);
            System.out.println("  late ctor-ref  = " + r + " (want late-ctor-ref:0)");
        }
        catch (Throwable t)
        {
            System.out.println("  late ctor-ref  = THREW " + t.getClass().getName() + ": " + t.getMessage()
                    + " (want late-ctor-ref:0)");
        }

        System.out.println("CtorRefCastDemo done");
    }
}
