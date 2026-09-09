package java.util;

import java.io.InputStream;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A JDK-free {@code java.util.ServiceLoader} overlay (wins by name) for joe-ng.
 *
 * <p>THIS OVERLAY EXISTS BECAUSE THE STOCK CLASS CANNOT RUN HERE, not because it is convenient to replace.
 * The standing rule is that {@code guestsrc/} is for classes that need NATIVES and everything else loads from
 * java.base; the reason this one is an exception is worth stating rather than glossing:
 *
 * <ul>
 *   <li>Stock discovery goes through {@code ClassLoader.getResources}, whose element type is
 *       {@link java.net.URL}. A working URL needs a protocol handler, which needs the
 *       {@code jdk/internal/loader} machinery this VM DENIES. There is no way to hand stock the bytes.</li>
 *   <li>Stock also scans the module graph ({@code ModuleLayer}, {@code ServiceLoader$ModuleServicesLookupIterator}),
 *       and joe-ng has no module layer -- {@code Class.getModule()} answers the single unnamed module.</li>
 *   <li>Stock instantiates through {@code MethodHandles}/{@code AccessController}, both denied.</li>
 * </ul>
 *
 * <p>So the search itself is reimplemented over the one resource path that works here --
 * {@link ClassLoader#getResourceAsStream}, which serves the classpath jar's bytes and never mentions a URL.
 * The FORMAT is stock's, read from the specification: one binary class name per line, {@code #} starts a
 * comment, blank lines are skipped, duplicates are ignored, order is file order.
 *
 * <p>SCOPE, stated as a real limit: there is ONE classpath jar, so the provider file is read from exactly one
 * place. Stock concatenates every {@code META-INF/services/<service>} on the whole class path (and the module
 * graph); a second jar's providers would be invisible here. Nothing on metal has a second jar, and the day one
 * exists this is the method to widen.
 *
 * <p>Laziness is preserved where it is load-bearing. {@link #stream} hands back a {@link Provider} per NAME:
 * {@link Provider#type} resolves the class and {@link Provider#get} instantiates it, so a caller that filters
 * on the type -- which is exactly what JUnit's {@code ServiceLoaderUtils.filter} does -- never constructs the
 * providers it rejects. Only the provider FILE is read eagerly.
 */
public final class ServiceLoader<S> implements Iterable<S>
{
    /** Where a service's provider file lives, per the {@code ServiceLoader} specification. */
    private static final String PREFIX = "META-INF/services/";

    /**
     * A located provider whose class is not yet resolved and whose instance is not yet built -- stock's own
     * handle, and the reason {@link #stream} can be filtered without instantiating anything.
     */
    public interface Provider<S> extends Supplier<S>
    {
        /** The provider class, resolving it on first ask. */
        Class<? extends S> type();

        /** A provider instance, constructed on each call. */
        @Override
        S get();
    }

    private final Class<S> service;
    private final ClassLoader loader;
    private final ArrayList<String> names = new ArrayList<String>();

    private ServiceLoader(Class<S> service, ClassLoader loader)
    {
        if (service == null)
        {
            throw new NullPointerException("service");
        }
        this.service = service;
        this.loader = loader == null ? ClassLoader.getSystemClassLoader() : loader;
        reload();
    }

    /** The providers of {@code service} visible to {@code loader}; a null loader means the system loader. */
    public static <S> ServiceLoader<S> load(Class<S> service, ClassLoader loader)
    {
        return new ServiceLoader<S>(service, loader);
    }

    /** As {@link #load(Class, ClassLoader)}, against the system class loader. */
    public static <S> ServiceLoader<S> load(Class<S> service)
    {
        return new ServiceLoader<S>(service, null);
    }

    /**
     * As {@link #load(Class)}. Stock restricts this to providers installed in the platform's extension
     * directories; there are none on metal, so the two searches coincide rather than one being a subset.
     */
    public static <S> ServiceLoader<S> loadInstalled(Class<S> service)
    {
        return new ServiceLoader<S>(service, null);
    }

    /** Re-read the provider file, discarding whatever was found before. */
    public void reload()
    {
        names.clear();
        InputStream in = loader.getResourceAsStream(PREFIX + service.getName());
        if (in == null)
        {
            return;                                    // no provider file: an empty service, not an error
        }
        byte[] raw;
        try
        {
            raw = in.readAllBytes();
            in.close();
        }
        catch (java.io.IOException e)
        {
            throw new ServiceConfigurationError(service.getName() + ": cannot read provider file", e);
        }
        parse(new String(raw));
    }

    /**
     * Split a provider file into class names.
     *
     * <p>Written against the specification rather than by splitting on a regex: a {@code #} comment may follow
     * a name on the same line, and the LAST line need not be terminated -- the file this VM is built for ends
     * without a newline, so a parser that only accepts terminated lines silently drops a provider.
     */
    private void parse(String text)
    {
        int at = 0;
        int n = text.length();
        while (at < n)
        {
            int nl = text.indexOf('\n', at);
            int end = nl < 0 ? n : nl;
            String line = text.substring(at, end);
            at = end + 1;
            int hash = line.indexOf('#');
            if (hash >= 0)
            {
                line = line.substring(0, hash);
            }
            line = line.trim();                        // also drops a CR left by a CRLF file
            if (line.length() == 0)
            {
                continue;
            }
            if (!names.contains(line))
            {
                names.add(line);                       // duplicates are ignored, per the specification
            }
        }
    }

    /** The providers, each as an unresolved handle; class loading and construction are deferred. */
    public Stream<Provider<S>> stream()
    {
        ArrayList<Provider<S>> out = new ArrayList<Provider<S>>();
        int i = 0;
        while (i < names.size())
        {
            out.add(new Lazy<S>(this, names.get(i)));
            i += 1;
        }
        return out.stream();
    }

    /** The provider instances, constructed as the iteration reaches them. */
    @Override
    public Iterator<S> iterator()
    {
        return new It<S>(this);
    }

    /** Resolve a provider class name, checking it really is a subtype -- stock's own check. */
    Class<? extends S> resolve(String cn)
    {
        Class<?> c;
        try
        {
            c = Class.forName(cn, false, loader);
        }
        catch (ClassNotFoundException e)
        {
            throw new ServiceConfigurationError(service.getName() + ": provider " + cn + " not found", e);
        }
        if (!service.isAssignableFrom(c))
        {
            throw new ServiceConfigurationError(service.getName() + ": provider " + cn + " is not a subtype");
        }
        return c.asSubclass(service);
    }

    /** Build a provider instance through its public no-argument constructor. */
    S construct(Class<? extends S> c)
    {
        try
        {
            return c.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new ServiceConfigurationError(service.getName() + ": provider " + c.getName()
                    + " could not be instantiated", e);
        }
    }

    @Override
    public String toString()
    {
        return "java.util.ServiceLoader[" + service.getName() + "]";
    }

    /** A {@link Provider} that remembers only a NAME until asked. */
    private static final class Lazy<S> implements Provider<S>
    {
        private final ServiceLoader<S> sl;
        private final String name;
        private Class<? extends S> cls;

        Lazy(ServiceLoader<S> sl, String name)
        {
            this.sl = sl;
            this.name = name;
        }

        @Override
        public Class<? extends S> type()
        {
            if (cls == null)
            {
                cls = sl.resolve(name);
            }
            return cls;
        }

        @Override
        public S get()
        {
            return sl.construct(type());
        }
    }

    /** Instantiates one provider per {@link #next}, so an unreached provider is never constructed. */
    private static final class It<S> implements Iterator<S>
    {
        private final ServiceLoader<S> sl;
        private int at;

        It(ServiceLoader<S> sl)
        {
            this.sl = sl;
        }

        @Override
        public boolean hasNext()
        {
            return at < sl.names.size();
        }

        @Override
        public S next()
        {
            if (at >= sl.names.size())
            {
                throw new NoSuchElementException();
            }
            String cn = sl.names.get(at);
            at += 1;
            return sl.construct(sl.resolve(cn));
        }
    }
}
