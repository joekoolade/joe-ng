package demo;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * {@code java.util.logging} on the metal.
 *
 * <p>These classes live in the {@code java.logging} MODULE, which joe-ng's image does not carry -- so they
 * were absent entirely rather than denied or dropped, and the launcher stopped on
 * {@code Logger.getLogger} with "class not in the classDir".
 *
 * <p>The arms that matter are the ones that could be quietly wrong: a logger is the SAME object per name
 * (callers cache the reference), the threshold ORDERS correctly (a level whose value did not order would
 * silently change which records survive), and a record round-trips every field a handler reads back.
 * The visible write is asserted last, by simply doing it -- the point of not being silent.
 */
public class LoggingDemo
{
    public static void main(String[] args)
    {
        Logger a = Logger.getLogger("demo.one");
        Logger b = Logger.getLogger("demo.one");
        Logger c = Logger.getLogger("demo.two");
        System.out.println("logger sameName   = " + (a == b ? 1 : 0) + " (want 1)");
        System.out.println("logger diffName   = " + (a == c ? 1 : 0) + " (want 0)");
        System.out.println("logger name       = " + a.getName() + " (want demo.one)");

        // Threshold: WARNING and above are written, below is dropped -- and isLoggable is what callers ask
        // BEFORE building a message, so a wrong answer here costs correctness, not just noise.
        System.out.println("loggable SEVERE   = " + (a.isLoggable(Level.SEVERE) ? 1 : 0) + " (want 1)");
        System.out.println("loggable WARNING  = " + (a.isLoggable(Level.WARNING) ? 1 : 0) + " (want 1)");
        System.out.println("loggable INFO     = " + (a.isLoggable(Level.INFO) ? 1 : 0) + " (want 0)");
        System.out.println("loggable FINE     = " + (a.isLoggable(Level.FINE) ? 1 : 0) + " (want 0)");
        System.out.println("level order       = "
                + (Level.SEVERE.intValue() > Level.WARNING.intValue()
                        && Level.WARNING.intValue() > Level.INFO.intValue()
                        && Level.INFO.intValue() > Level.FINE.intValue() ? 1 : 0) + " (want 1)");

        LogRecord r = new LogRecord(Level.WARNING, "record message");
        r.setLoggerName("demo.one");
        r.setSourceClassName("demo.LoggingDemo");
        r.setSourceMethodName("main");
        System.out.println("record level      = " + r.getLevel().getName() + " (want WARNING)");
        System.out.println("record message    = " + r.getMessage() + " (want record message)");
        System.out.println("record logger     = " + r.getLoggerName() + " (want demo.one)");
        System.out.println("record source     = " + r.getSourceClassName() + "." + r.getSourceMethodName()
                + " (want demo.LoggingDemo.main)");
        System.out.println("record bundle     = " + (r.getResourceBundle() == null ? "null" : "NON-NULL")
                + " (want null)");

        // The write itself: WARNING appears on stderr, INFO does not. Silence for a real warning is the
        // failure mode this class exists to avoid.
        System.out.println("-- expect ONE [WARNING] line next, and no [INFO] line --");
        a.log(r);
        a.info("this must not appear");
        System.out.println("LoggingDemo done");
    }
}
