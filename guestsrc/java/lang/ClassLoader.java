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

    /**
     * Every classpath resource matching {@code name} -- which on metal is NONE, and says so honestly.
     *
     * <p>joe-ng serves classes out of the classpath jar but not resources, because a resource is handed back
     * as a {@link java.net.URL} and a working URL needs a protocol handler, which needs the
     * {@code jdk/internal/loader} machinery this VM denies. An EMPTY enumeration is therefore the exactly
     * correct answer whenever the resource is genuinely absent, and callers are written for it: JUnit's
     * {@code LauncherConfigurationParameters.findConfigFile} does
     * {@code Collections.list(cl.getResources(name))} and returns null on an empty list, so its caller simply
     * skips loading {@code junit-platform.properties} -- which this jar does not contain.
     *
     * <p>WHEN THE RESOURCE DOES EXIST, THIS SAYS SO RATHER THAN ANSWERING EMPTY IN SILENCE. That case is a
     * real gap, and a silently empty answer would present "we cannot serve this" as "there is nothing here" --
     * the caller would then run with default configuration and no indication why. {@code resourceExists0}
     * exists purely to keep those two apart.
     *
     * <p>Serving resources for real is the same piece of work as {@code ServiceLoader} discovery, which reads
     * {@code META-INF/services/*} out of this jar; both wait on a resource-stream path that does not go
     * through URL.
     */
    public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException
    {
        if (name != null && resourceExists0(name.getBytes()) != 0L)
        {
            // No concatenation: in a java.base overlay that lowers to invokedynamic and drags the
            // string-concat machinery into a closure that must stay cold.
            System.err.print("joe-ng: classpath resource present but not served (no URL support): ");
            System.err.println(name);
        }
        return java.util.Collections.enumeration(new java.util.ArrayList<java.net.URL>());
    }

    /**
     * As {@link #getResources}, for a single resource: always null, for the same reason. Stock returns null
     * when nothing matches, so an absent resource is answered exactly; a present one is reported first.
     */
    public java.net.URL getResource(String name)
    {
        if (name != null && resourceExists0(name.getBytes()) != 0L)
        {
            System.err.print("joe-ng: classpath resource present but not served (no URL support): ");
            System.err.println(name);
        }
        return null;
    }

    /**
     * The bytes of a classpath resource as a stream, or null when the classpath jar has no such entry.
     *
     * <p>This is the resource path that DOES work here, because it never mentions {@link java.net.URL}: the
     * entry is located and inflated by the VM and handed back as a {@code byte[]}. {@link #getResource} and
     * {@link #getResources} still cannot be served for the reason above -- their return type is the URL.
     *
     * <p>Stock consults the parent loader first and then the boot class path; there is one loader here, so the
     * jar is the whole search. A resource baked into the image rather than the jar is not visible.
     */
    public java.io.InputStream getResourceAsStream(String name)
    {
        if (name == null)
        {
            return null;
        }
        byte[] b = resourceBytes0(name.getBytes());
        return b == null ? null : new java.io.ByteArrayInputStream(b);
    }

    /** As {@link #getResourceAsStream}, against the single application loader. */
    public static java.io.InputStream getSystemResourceAsStream(String name)
    {
        return SYSTEM.getResourceAsStream(name);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.resourceExists} -> {@code JarFs.hasResource}). */
    private static native long resourceExists0(byte[] name);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.resourceBytes} -> {@code JarFs.resourceData}). */
    private static native byte[] resourceBytes0(byte[] name);
}
