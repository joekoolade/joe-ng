package java.lang.ref;

/**
 * A minimal, name-winning {@code java.lang.ref.Cleaner} overlay for joe-ng. Stock Cleaner spins up a daemon
 * thread and a {@code PhantomReference}/{@code ReferenceQueue} to run cleanup on GC; on metal we make it
 * synchronous and explicit: {@code register(obj, action)} returns a {@link Cleanable} that runs {@code
 * action} exactly once when {@code clean()} is called. GC-triggered auto-cleanup is unsupported (fine);
 * {@code sun.nio.ch.NioSocketImpl} calls {@code cleaner.clean()} explicitly on {@code close()}, which is
 * what actually closes the socket.
 */
public final class Cleaner
{
    private Cleaner()
    {
    }

    public static Cleaner create()
    {
        return new Cleaner();
    }

    public Cleanable register(Object obj, Runnable action)
    {
        return new Sync(action);
    }

    public interface Cleanable
    {
        void clean();
    }

    static final class Sync implements Cleanable
    {
        private Runnable action;

        Sync(Runnable a)
        {
            action = a;
        }

        public void clean()
        {
            Runnable a = action;
            if (a != null)
            {
                action = null;
                a.run();
            }
        }
    }
}
