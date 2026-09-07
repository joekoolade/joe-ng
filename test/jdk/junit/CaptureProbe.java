import java.util.Optional;
import java.util.function.Supplier;

/**
 * A capturing lambda whose captured reference arrives as the RECEIVER'S FIELD VALUE -- the console
 * launcher's blocker, reduced. Five arms, one variable each, so the ingredient is named rather than guessed.
 *
 * <p>The launcher does {@code createCustomContextClassLoaderExecutor().invoke(() -> executeTests(out,
 * reportsDir, failFast))}: an indy capturing four values with the executor already on the operand stack
 * beneath them. Downstream the {@code Optional} arrives as a {@code CustomClassLoaderCloseStrategy}, which
 * is the executor's OTHER field. {@code VIRTUALRESOLVE FAILED ...$2.map(Function)Optional} reports it.
 *
 * <p>No arm calls {@code map()}: each only reports the class of what arrived, so every arm runs even when
 * one is broken. An arm that prints java.util.Optional passed.
 */
public class CaptureProbe
{
    enum Strategy
    {
        CLOSE { void handle() { } },
        KEEP  { void handle() { } };

        abstract void handle();
    }

    /** Two fields, like the launcher's executor: an Optional and an enum constant. */
    static final class Holder
    {
        private final Optional<String> maybe;
        private final Strategy strategy;

        Holder(Optional<String> maybe, Strategy strategy)
        {
            this.maybe = maybe;
            this.strategy = strategy;
        }

        <T> T invoke(Supplier<T> body)
        {
            if (maybe.isPresent())
            {
                strategy.handle();
            }
            return body.get();
        }
    }

    /** One field only: isolates whether the SECOND field is what leaks into the capture. */
    static final class Plain
    {
        private final Optional<String> maybe;

        Plain(Optional<String> maybe)
        {
            this.maybe = maybe;
        }

        <T> T invoke(Supplier<T> body)
        {
            return body.get();
        }
    }

    /** Two fields like Holder, but invoke touches NEITHER -- field COUNT without the field READS. */
    static final class Quiet
    {
        private final Optional<String> maybe;
        private final Strategy strategy;

        Quiet(Optional<String> maybe, Strategy strategy)
        {
            this.maybe = maybe;
            this.strategy = strategy;
        }

        <T> T invoke(Supplier<T> body)
        {
            return body.get();
        }
    }

    /** ONE field, but invoke READS it and makes a virtual call first -- the reads without the second field. */
    static final class Busy
    {
        private final Strategy strategy;

        Busy(Strategy strategy)
        {
            this.strategy = strategy;
        }

        <T> T invoke(Supplier<T> body)
        {
            strategy.handle();
            return body.get();
        }
    }

    /** Fields SWAPPED: enum first, Optional second. Which index arrives? */
    static final class Swapped
    {
        private final Strategy strategy;
        private final Optional<String> maybe;

        Swapped(Strategy strategy, Optional<String> maybe)
        {
            this.strategy = strategy;
            this.maybe = maybe;
        }

        <T> T invoke(Supplier<T> body)
        {
            return body.get();
        }
    }

    /** THREE fields, each a distinguishable type, to read the index straight off the answer. */
    static final class Three
    {
        private final Optional<String> f0;
        private final Strategy f1;
        private final String f2;

        Three(Optional<String> f0, Strategy f1, String f2)
        {
            this.f0 = f0;
            this.f1 = f1;
            this.f2 = f2;
        }

        <T> T invoke(Supplier<T> body)
        {
            return body.get();
        }
    }

    static Holder makeHolder()
    {
        return new Holder(Optional.of("cl"), Strategy.KEEP);
    }

    static String kindOf(Optional<String> o)
    {
        return (o == null) ? "NULL" : o.getClass().getName();
    }

    String three(String out, Optional<String> reports)
    {
        return kindOf(reports);
    }

    String four(String out, Optional<String> reports, boolean flag)
    {
        return kindOf(reports);
    }

    /** A: the launcher's exact shape -- 4 captures, ALL parameters, receiver produced inline. */
    String execA(String out, Optional<String> reports, boolean flag)
    {
        return makeHolder().invoke(() -> four(out, reports, flag));
    }

    /** B: as A but 3 captures -- the primitive dropped. */
    String execB(String out, Optional<String> reports)
    {
        return makeHolder().invoke(() -> three(out, reports));
    }

    /** C: as A but the receiver is in a LOCAL, so it is not on the stack beneath the captures. */
    String execC(String out, Optional<String> reports, boolean flag)
    {
        Holder h = makeHolder();
        return h.invoke(() -> four(out, reports, flag));
    }

    /** D: as A but the receiver has ONE field -- is the enum field what leaks in? */
    String execD(String out, Optional<String> reports, boolean flag)
    {
        return new Plain(reports).invoke(() -> four(out, reports, flag));
    }

    /** E: as A but no invoke() -- the supplier is called directly. */
    String execE(String out, Optional<String> reports, boolean flag)
    {
        Supplier<String> s = () -> four(out, reports, flag);
        return s.get();
    }

    /** F: two fields, but invoke never reads them. */
    String execF(String out, Optional<String> reports, boolean flag)
    {
        return new Quiet(reports, Strategy.KEEP).invoke(() -> four(out, reports, flag));
    }

    /** G: one field, and invoke reads it and dispatches on it before calling the supplier. */
    String execG(String out, Optional<String> reports, boolean flag)
    {
        return new Busy(Strategy.KEEP).invoke(() -> four(out, reports, flag));
    }

    /** H: receiver fields are (Strategy, Optional) -- the reverse of Holder's. */
    String execH(String out, Optional<String> reports, boolean flag)
    {
        return new Swapped(Strategy.KEEP, reports).invoke(() -> four(out, reports, flag));
    }

    /** I: receiver fields are (Optional, Strategy, String) -- three distinguishable types. */
    String execI(String out, Optional<String> reports, boolean flag)
    {
        return new Three(reports, Strategy.KEEP, "third").invoke(() -> four(out, reports, flag));
    }

    public static void main(String[] args)
    {
        CaptureProbe p = new CaptureProbe();
        Optional<String> dir = Optional.of("dir");

        System.out.println("A inline recv, 4 captures = " + p.execA("W", dir, true));
        System.out.println("B inline recv, 3 captures = " + p.execB("W", dir));
        System.out.println("C local recv,  4 captures = " + p.execC("W", dir, true));
        System.out.println("D one-field recv, 4 caps  = " + p.execD("W", dir, true));
        System.out.println("E direct supplier, 4 caps = " + p.execE("W", dir, true));

        System.out.println("F 2 fields, invoke QUIET   = " + p.execF("W", dir, true));
        System.out.println("G 1 field,  invoke READS   = " + p.execG("W", dir, true));

        System.out.println("H swapped fields          = " + p.execH("W", dir, true));
        System.out.println("I three fields            = " + p.execI("W", dir, true));

        System.out.println("(want java.util.Optional on every line)");
        System.out.println("CaptureProbe done");
    }
}
