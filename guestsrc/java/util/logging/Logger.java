package java.util.logging;

import java.util.HashMap;
import java.util.Map;

/**
 * A minimal {@code java.util.logging.Logger} for joe-ng.
 *
 * <p>NOT an overlay: {@code java.util.logging} lives in the {@code java.logging} MODULE, and joe-ng's image
 * carries only {@code java.base} -- so this class was ABSENT ENTIRELY, and the report said so exactly
 * ({@code LINK FAILED ... class not in the classDir (nothing can load it)}), which is a different diagnosis
 * from a denied class or a dropped overlay member. Nothing is being shadowed here; the standing rule that
 * {@code guestsrc/} is for classes needing natives governs SHADOWING a stock class.
 *
 * <p>WHAT IT DOES, AND WHY IT IS NOT SILENT. There is no {@code LogManager}, no handler chain, no hierarchy,
 * no configuration and no resource bundles: a logger is a NAME, and {@link #log(LogRecord)} writes
 * {@code WARNING} and above to {@link System#err}, dropping anything below. Silence was the easy choice and
 * the wrong one -- libraries report real trouble through this and then CARRY ON, so a dropped warning turns a
 * nameable failure into a mystery later. JUnit's own launcher logs a {@code TestEngine} that fails to load at
 * {@code WARNING} and continues with the engines that did load; on this VM that is exactly the line worth
 * having.
 *
 * <p>{@link #isLoggable} answers against the same threshold, which is what makes the quiet levels cheap:
 * callers ask before building the message, so a dropped record is never formatted. That is why the threshold
 * lives in one place rather than being applied only at the write.
 */
public class Logger
{
    /** Records at or above this level are written; everything below is dropped. */
    private static final int THRESHOLD = 900;          // Level.WARNING

    /** One logger per name, as stock guarantees -- callers hold on to the reference and compare it. */
    private static final Map<String, Logger> LOGGERS = new HashMap<String, Logger>();

    private final String name;
    private Level level;

    protected Logger(String name, String resourceBundleName)
    {
        this.name = name;
    }

    /** The logger for {@code name}, created on first ask and the SAME object thereafter. */
    public static synchronized Logger getLogger(String name)
    {
        Logger existing = LOGGERS.get(name);
        if (existing != null)
        {
            return existing;
        }
        Logger created = new Logger(name, null);
        LOGGERS.put(name, created);
        return created;
    }

    /** As {@link #getLogger(String)}; the bundle name is accepted and ignored -- there are no bundles here. */
    public static synchronized Logger getLogger(String name, String resourceBundleName)
    {
        return getLogger(name);
    }

    /** The anonymous logger stock hands out for callers that do not want a shared one. */
    public static Logger getAnonymousLogger()
    {
        return new Logger("", null);
    }

    public String getName()
    {
        return name;
    }

    public Level getLevel()
    {
        return level;
    }

    /** Accepted and remembered so {@link #getLevel} is truthful; the write threshold is fixed. */
    public void setLevel(Level newLevel)
    {
        this.level = newLevel;
    }

    /**
     * No bundle exists here, so this is null -- and null is the answer stock gives for a logger created
     * without one, which is every logger on this VM. Callers copy it straight into a {@link LogRecord}.
     */
    public java.util.ResourceBundle getResourceBundle()
    {
        return null;
    }

    public String getResourceBundleName()
    {
        return null;
    }

    /** Whether a record at {@code recordLevel} would be written; a null level is treated as loggable. */
    public boolean isLoggable(Level recordLevel)
    {
        return recordLevel == null || recordLevel.intValue() >= THRESHOLD;
    }

    /** Write a record, if its level survives the threshold. */
    public void log(LogRecord record)
    {
        if (record == null || !isLoggable(record.getLevel()))
        {
            return;
        }
        // Separate prints rather than concatenation, matching the other java.* classes here: concatenation
        // lowers to invokedynamic, and this path must stay usable from anywhere including a failure report.
        System.err.print("[");
        System.err.print(record.getLevel() == null ? "INFO" : record.getLevel().getName());
        System.err.print("] ");
        if (record.getLoggerName() != null)
        {
            System.err.print(record.getLoggerName());
            System.err.print(": ");
        }
        System.err.println(record.getMessage());
        Throwable thrown = record.getThrown();
        if (thrown != null)
        {
            // The cause is the reason the caller logged at all -- printing the trace is what makes the line
            // actionable, and the caller has already decided to carry on regardless.
            thrown.printStackTrace();
        }
    }

    public void log(Level recordLevel, String message)
    {
        LogRecord record = new LogRecord(recordLevel, message);
        record.setLoggerName(name);
        log(record);
    }

    public void log(Level recordLevel, String message, Throwable thrown)
    {
        LogRecord record = new LogRecord(recordLevel, message);
        record.setLoggerName(name);
        record.setThrown(thrown);
        log(record);
    }

    public void severe(String message)
    {
        log(Level.SEVERE, message);
    }

    public void warning(String message)
    {
        log(Level.WARNING, message);
    }

    public void info(String message)
    {
        log(Level.INFO, message);
    }

    public void config(String message)
    {
        log(Level.CONFIG, message);
    }

    public void fine(String message)
    {
        log(Level.FINE, message);
    }

    public void finer(String message)
    {
        log(Level.FINER, message);
    }

    public void finest(String message)
    {
        log(Level.FINEST, message);
    }
}
