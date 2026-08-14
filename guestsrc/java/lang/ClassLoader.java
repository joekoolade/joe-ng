package java.lang;

/**
 * A JDK-free {@code java.lang.ClassLoader} overlay (wins by name) for joe-ng reflection arc M3. The stock class
 * is a large machine — a delegation hierarchy, parallel-capable locking, package/module bookkeeping, protection
 * domains, native-library management, and a wall of natives over the JVM's internal class registry — none of
 * which exists on metal. This overlay is a SINGLE application loader with no delegation hierarchy and no
 * unloading:
 *
 * <ul>
 *   <li>{@link #loadClass} resolves a binary name through the M1 {@code Class.forName} path (demand-load the
 *       class + its dependency closure from the embedded classDir).</li>
 *   <li>{@link #defineClass} materializes a class from SUPPLIED classfile bytes via the one native
 *       {@code defineClass0} -> {@code VM.defineClass} -> {@code Loader.defineFromBytes}, returning its
 *       {@link Class} mirror. This is the point of M3: bytes the program holds become a live, runnable class.</li>
 *   <li>{@link #findClass} is the subclass extension point (default: not found), so a custom loader can override
 *       it and call {@code defineClass} on bytes it fetched itself.</li>
 * </ul>
 */
public class ClassLoader
{
    private static final ClassLoader SYSTEM = new ClassLoader();

    protected ClassLoader()
    {
    }

    /** The single application class loader (no real delegation hierarchy here). */
    public static ClassLoader getSystemClassLoader()
    {
        return SYSTEM;
    }

    /** Load the class named {@code name} (binary, dotted), demand-loading it from the classDir (M1 forName). */
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        Class<?> c = findLoadedOrSystem(name);
        if (c != null)
        {
            return c;
        }
        return findClass(name);                            // subclass hook (default throws)
    }

    /** M1 demand-load by name; null if not embedded (so {@link #loadClass} falls through to {@link #findClass}). */
    private Class<?> findLoadedOrSystem(String name)
    {
        try
        {
            return Class.forName(name, false, this);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
    }

    /** Subclass extension point: locate + define a class this loader is responsible for. Default: not found. */
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        throw new ClassNotFoundException(name);
    }

    /**
     * Define a class from classfile {@code b[off..off+len)}. The {@code name} is advisory — the loader uses the
     * classfile's own {@code this_class}. Returns the new {@link Class}; throws {@link ClassFormatError} if the
     * bytes can't be loaded.
     */
    protected final Class<?> defineClass(String name, byte[] b, int off, int len)
    {
        Class<?> c = defineClass0(name, b, off, len);
        if (c == null)
        {
            throw new ClassFormatError(name);
        }
        return c;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.defineClass} -> {@code Loader.defineFromBytes}). */
    private static native Class<?> defineClass0(String name, byte[] b, int off, int len);
}
