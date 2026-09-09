package java.util.logging;

/**
 * One log record: a level, a message, and the bookkeeping a handler might want.
 *
 * <p>A plain carrier -- every field is set by the caller and read back unchanged. joe-ng has no
 * {@code LogManager}, no handlers and no resource bundles, so nothing here is interpreted; the record exists
 * because {@code Logger.log(LogRecord)} is the shape callers use, and because JUnit hands records to its own
 * {@code LogRecordListener}, which reads them.
 */
public class LogRecord
{
    private Level level;
    private String message;
    private String loggerName;
    private String sourceClassName;
    private String sourceMethodName;
    private String resourceBundleName;
    private java.util.ResourceBundle resourceBundle;
    private Throwable thrown;
    private Object[] parameters;
    private final long millis;

    public LogRecord(Level level, String message)
    {
        this.level = level;
        this.message = message;
        this.millis = System.currentTimeMillis();
    }

    public Level getLevel()
    {
        return level;
    }

    public void setLevel(Level level)
    {
        this.level = level;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public String getLoggerName()
    {
        return loggerName;
    }

    public void setLoggerName(String loggerName)
    {
        this.loggerName = loggerName;
    }

    public String getSourceClassName()
    {
        return sourceClassName;
    }

    public void setSourceClassName(String sourceClassName)
    {
        this.sourceClassName = sourceClassName;
    }

    public String getSourceMethodName()
    {
        return sourceMethodName;
    }

    public void setSourceMethodName(String sourceMethodName)
    {
        this.sourceMethodName = sourceMethodName;
    }

    public String getResourceBundleName()
    {
        return resourceBundleName;
    }

    public void setResourceBundleName(String resourceBundleName)
    {
        this.resourceBundleName = resourceBundleName;
    }

    public java.util.ResourceBundle getResourceBundle()
    {
        return resourceBundle;
    }

    public void setResourceBundle(java.util.ResourceBundle bundle)
    {
        this.resourceBundle = bundle;
    }

    public Throwable getThrown()
    {
        return thrown;
    }

    public void setThrown(Throwable thrown)
    {
        this.thrown = thrown;
    }

    public Object[] getParameters()
    {
        return parameters;
    }

    public void setParameters(Object[] parameters)
    {
        this.parameters = parameters;
    }

    public long getMillis()
    {
        return millis;
    }
}
