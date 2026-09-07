import java.util.Optional;
import java.util.function.Supplier;

/**
 * A capturing lambda whose captured reference arrives as the RECEIVER'S field 1 -- the console launcher's
 * blocker, reduced to a boot of seconds. Each arm varies ONE thing, and every capture is reported, so a
 * SHIFT of the capture list is distinguishable from a single wrong slot.
 *
 * <p>Reporting uses direct prints: building the report with `+` allocates enough to hit `large region OOM`
 * here, which killed the first attempt at this instrument.
 */
public class CaptureProbe
{
    /** An instance field, so a wrong `this` capture is visible rather than merely suspected. */
    final String tag = "PROBE";

    enum Strategy
    {
        CLOSE { void handle() { } },
        KEEP  { void handle() { } };

        abstract void handle();
    }

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

    static Holder makeHolder()
    {
        return new Holder(Optional.of("cl"), Strategy.KEEP);
    }

    static String kindOf(Optional<String> o)
    {
        return (o == null) ? "NULL" : o.getClass().getName();
    }

    void report(String out, Optional<String> reports, boolean flag)
    {
        System.out.print("      this.tag=");
        System.out.print(this.tag == null ? "NULL" : this.tag);
        System.out.print(" out=");
        System.out.print(out == null ? "NULL" : out);
        System.out.print(" reports=");
        System.out.print(kindOf(reports));
        System.out.print(" flag=");
        System.out.println(flag);
    }

    String four(String out, Optional<String> reports, boolean flag)
    {
        report(out, reports, flag);
        return kindOf(reports);
    }

    /** A: the launcher's exact shape -- 4 captures, all parameters, receiver produced inline. */
    String execA(String out, Optional<String> reports, boolean flag)
    {
        return makeHolder().invoke(() -> four(out, reports, flag));
    }

    /** F: two fields, invoke never reads them -- the minimal failing shape. */
    String execF(String out, Optional<String> reports, boolean flag)
    {
        return new Quiet(reports, Strategy.KEEP).invoke(() -> four(out, reports, flag));
    }

    /** E: control -- no invoke(), the supplier called directly. */
    String execE(String out, Optional<String> reports, boolean flag)
    {
        Supplier<String> s = () -> four(out, reports, flag);
        return s.get();
    }

    public static void main(String[] args)
    {
        CaptureProbe p = new CaptureProbe();
        Optional<String> dir = Optional.of("dir");

        System.out.println("A inline recv, 2-field holder:");
        p.execA("W", dir, true);

        System.out.println("F quiet 2-field holder:");
        p.execF("W", dir, true);

        System.out.println("E direct supplier (control):");
        p.execE("W", dir, true);

        System.out.println("(want this.tag=PROBE out=W reports=java.util.Optional flag=true)");
        System.out.println("CaptureProbe done");
    }
}
