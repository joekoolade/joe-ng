/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-13
 */
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
    /**
     * Stock's two built-in loaders: the PLATFORM loader (parent: the bootstrap loader) and the application loader
     * under it. Built in that order, through the explicit-parent constructor, so neither reads a static that is
     * still being assigned.
     */
    private static final ClassLoader PLATFORM = new ClassLoader((ClassLoader) null);

    private static final ClassLoader SYSTEM = new ClassLoader(PLATFORM);

    /** The delegation parent, as stock keeps it; null means the bootstrap loader. */
    private final ClassLoader parent;

    /** Stock's default: the parent is the system class loader. */
    protected ClassLoader()
    {
        this(getSystemClassLoader());
    }

    /** Stock's: a loader delegating to {@code parent}, null for the bootstrap loader. */
    protected ClassLoader(ClassLoader parent)
    {
        this.parent = parent;
    }

    /** Stock's: this loader's unnamed module -- the one module every class here belongs to (see Module). */
    public final Module getUnnamedModule()
    {
        return Module.UNNAMED;
    }

    /** Stock's accessor for the delegation parent. */
    public final ClassLoader getParent()
    {
        return parent;
    }

    /**
     * Null: this VM creates no {@link Package} objects, for the reason given on {@link Class#getPackage}, so no
     * package is ever DEFINED by a loader -- and stock answers null for a package the loader has not defined.
     * The two methods agree by construction.
     */
    public final Package getDefinedPackage(String name)
    {
        java.util.Objects.requireNonNull(name, "name cannot be null");
        return null;
    }

    /** The application class loader: what {@link Class#getClassLoader} answers for an application class. */
    public static ClassLoader getSystemClassLoader()
    {
        return SYSTEM;
    }

    /**
     * Stock's platform loader, the system loader's parent. No class reports it as its defining loader (a JDK
     * class answers the bootstrap loader, null, from {@link Class#getClassLoader}), but stock code compares against
     * it: {@code jdk.internal.misc.VM.isSystemDomainLoader} does, on every hidden-class definition. The overlay
     * used to drop it, which nothing reached while getClassLoader answered null for every class -- MEASURED once it
     * stopped: {@code LINK FAILED ... getPlatformClassLoader()} then a TRAPWIRE in {@code makeHiddenClassDefiner}
     * (IndyProbe's type switch, MhSpinProbe's defineHiddenClassWithClassData).
     */
    public static ClassLoader getPlatformClassLoader()
    {
        return PLATFORM;
    }

    /** Stock's: the class loader of {@code caller}, null for the bootstrap loader or a null caller. */
    static ClassLoader getClassLoader(Class<?> caller)
    {
        if (caller == null)
        {
            return null;
        }
        return caller.getClassLoader0();
    }

    /**
     * Stock's registration hook, which every class loader's initializer may call (jdk.internal.misc.CDS's
     * nested loaders do, URLClassLoader does). TRUE, and that is a statement about this overlay rather than a
     * placeholder: stock's distinction is whether loadClass locks the LOADER or a per-name lock object, and
     * {@link #loadClass} here takes no loader lock at all -- the VM's loader lock serializes every definition --
     * so every loader already behaves as a registered parallel-capable one.
     */
    protected static boolean registerAsParallelCapable()
    {
        return true;
    }

    /** Stock's query: true for every loader here, for the reason {@link #registerAsParallelCapable} gives. */
    public final boolean isRegisteredAsParallelCapable()
    {
        return true;
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
        if (name != null && resourceExists0(jdk.internal.misc.MetalNames.utf8(name)) != 0L)
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
        if (name != null && resourceExists0(jdk.internal.misc.MetalNames.utf8(name)) != 0L)
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
        byte[] b = resourceBytes0(jdk.internal.misc.MetalNames.utf8(name));
        return b == null ? null : new java.io.ByteArrayInputStream(b);
    }

    /** Stock's: {@link #getResource} on the system class loader. */
    public static java.net.URL getSystemResource(String name)
    {
        return getSystemClassLoader().getResource(name);
    }

    /** As {@link #getResourceAsStream}, against the single application loader. */
    public static java.io.InputStream getSystemResourceAsStream(String name)
    {
        return SYSTEM.getResourceAsStream(name);
    }

    /**
     * joe-ng: one of java.base's OWN resources -- {@code java/util/currency.data}, the break-iterator rule data --
     * or null. They live inside the JDK's modules image, in no jar, so the build copies each one the VM serves
     * out of the seed's image to {@code /lib/java.base/<name>} in the RAMFS (Makefile {@code JAVA_BASE_RES}).
     * {@link Class#getResourceAsStream} and {@link Module#getResourceAsStream} fall back to this when the
     * classpath jar has nothing; stock java.base reads its resources through exactly those two methods.
     */
    static java.io.InputStream javaBaseResource(String name)
    {
        java.io.File f = new java.io.File(JAVA_BASE_RESOURCES + name);
        if (!f.isFile())
        {
            return null;
        }
        try
        {
            return new java.io.FileInputStream(f);
        }
        catch (java.io.FileNotFoundException e)
        {
            return null;
        }
    }

    /** Where the build puts java.base's own resources in the RAMFS (see {@link #javaBaseResource}). */
    private static final String JAVA_BASE_RESOURCES = "/lib/java.base/";

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.resourceExists} -> {@code JarFs.hasResource}). */
    private static native long resourceExists0(byte[] name);

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.resourceBytes} -> {@code JarFs.resourceData}). */
    private static native byte[] resourceBytes0(byte[] name);
}
