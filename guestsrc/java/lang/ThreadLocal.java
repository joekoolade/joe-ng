package java.lang;

/**
 * A JDK-free {@code java.lang.ThreadLocal}: per-thread storage kept in a small map on the current Thread
 * (see {@code Thread.tl*}). {@link #get()} lazily initialises the entry via {@link #initialValue()}.
 * {@link InheritableThreadLocal} extends this so a child thread inherits the parent's value at creation.
 */
public class ThreadLocal<T>
{
    /** The value for a thread that has no entry yet (default null; subclasses override). */
    protected T initialValue()
    {
        return null;
    }

    /** Inheritance hook: the child's value derived from the parent's (InheritableThreadLocal overrides). */
    T childValue(T parentValue)
    {
        return parentValue;
    }

    /** Whether a child thread inherits this thread-local (only InheritableThreadLocal does). */
    boolean inheritable()
    {
        return false;
    }

    @SuppressWarnings("unchecked")
    public T get()
    {
        Thread t = Thread.currentThread();
        int i = t.tlIndex(this);
        if (i >= 0)
        {
            return (T) t.tlValueAt(i);
        }
        T v = initialValue();
        t.tlPut(this, v);
        return v;
    }

    public void set(T value)
    {
        Thread.currentThread().tlPut(this, value);
    }

    public void remove()
    {
        Thread.currentThread().tlRemove(this);
    }
}
