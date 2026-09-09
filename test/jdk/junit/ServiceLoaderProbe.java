import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectorIdentifierParser;

/**
 * {@code ServiceLoader} discovery on the metal, out of the real console-standalone jar.
 *
 * <p>The three things that can each be wrong independently are asserted separately: the provider FILE is
 * found and parsed (counts and names, with no class loading at all), a name RESOLVES to a class, and a
 * resolved class is CONSTRUCTED. A probe that only counted would pass with names it could never load.
 *
 * <p>The counts are pinned to the jar's own files, and the LAST entry of each is the one that matters:
 * neither file ends in a newline, so a parser that only accepts terminated lines drops exactly one provider
 * and still looks healthy.
 *
 * <p>Laziness is asserted too, because it is load-bearing rather than an optimisation: JUnit's
 * {@code ServiceLoaderUtils.filter} rejects providers on {@code type()} before ever calling {@code get()}, so
 * a stream that instantiated eagerly would construct engines nobody asked for.
 *
 * <p>THE IDENTITY ARMS ARE A REGRESSION FOR A VM BUG THIS PROBE FOUND, not ServiceLoader coverage. Calling
 * {@code getClass()} on an INTERFACE-typed receiver compiles to an {@code invokeinterface} whose owner is the
 * interface (JVMS 5.4.3.4 makes interface resolution search {@code Object} too), and no Object method has an
 * itable slot -- so the directory walk indexed a slot holding a REAL interface method and called it. The
 * result was non-null, stable, and completely wrong. Every arm is stated against a control that took the
 * {@code Object}-typed path, which always worked; {@code isInstance} carries its own negative control,
 * because an instrument that cannot say NO proves nothing.
 */
public class ServiceLoaderProbe
{
    public static void main(String[] args) throws Exception
    {
        ClassLoader cl = ClassLoader.getSystemClassLoader();

        // ---- FIRST, a plain VM question with no ServiceLoader in it: does resolving one name TWICE give one
        // class? A second identity is not merely a failed ==; the mirror that comes back misdispatches.
        String twice = "org.junit.platform.engine.discovery.UriSelector$IdentifierParser";
        Class<?> c1 = Class.forName(twice, false, cl);
        Class<?> c2 = Class.forName(twice, false, cl);
        System.out.println("forName twice same  = " + (c1 == c2 ? 1 : 0) + " (want 1)");
        System.out.println("forName#1 isClass   = " + (c1 instanceof Class ? 1 : 0) + " (want 1)");
        System.out.println("forName#2 isClass   = " + (c2 instanceof Class ? 1 : 0) + " (want 1)");

        // ---- FILE: found, parsed, in order, with the unterminated last line kept.
        ServiceLoader<TestEngine> engines = ServiceLoader.load(TestEngine.class, cl);
        List<ServiceLoader.Provider<TestEngine>> ps = engines.stream().toList();
        System.out.println("engines count       = " + ps.size() + " (want 3)");
        System.out.println("engines toString    = " + engines.toString()
                + " (want java.util.ServiceLoader[org.junit.platform.engine.TestEngine])");

        ServiceLoader<DiscoverySelectorIdentifierParser> parsers =
                ServiceLoader.load(DiscoverySelectorIdentifierParser.class, cl);
        List<ServiceLoader.Provider<DiscoverySelectorIdentifierParser>> pl = parsers.stream().toList();
        System.out.println("parsers count       = " + pl.size() + " (want 13)");

        // ---- ABSENT: no provider file is an empty service, not an error.
        ServiceLoader<Runnable> none = ServiceLoader.load(Runnable.class, cl);
        System.out.println("absent count        = " + none.stream().toList().size() + " (want 0)");
        System.out.println("absent iterator     = " + (none.iterator().hasNext() ? "HASNEXT <== BUG" : "empty")
                + " (want empty)");

        // ---- RESOLVE: a name becomes a class. The LAST parser is the unterminated line.
        Class<?> first = pl.get(0).type();
        Class<?> last = pl.get(pl.size() - 1).type();
        System.out.println("parser[0] type      = " + first.getName()
                + " (want org.junit.platform.engine.discovery.ClasspathResourceSelector$IdentifierParser)");
        System.out.println("parser[last] type   = " + last.getName()
                + " (want org.junit.platform.engine.discovery.UriSelector$IdentifierParser)");
        System.out.println("parser[last] subtype= "
                + (DiscoverySelectorIdentifierParser.class.isAssignableFrom(last) ? 1 : 0) + " (want 1)");

        // ---- CONSTRUCT via the ALREADY-RESOLVED provider: its class came from the forName above, so if this
        // instance's getClass() matches `first` while the iterator's does not, the difference is the SECOND
        // resolve of the same name -- not construction.
        Object viaGet = pl.get(0).get();
        System.out.println("get() nonNull       = " + (viaGet != null ? 1 : 0) + " (want 1)");
        System.out.println("get() classIsFirst  = " + (viaGet.getClass() == first ? 1 : 0) + " (want 1)");
        System.out.println("get() isClass       = " + (viaGet.getClass() instanceof Class ? 1 : 0) + " (want 1)");
        System.out.print("get() className     = ");
        System.out.println(viaGet.getClass().getName());

        // ---- RE-RESOLVE a name that is ALREADY fully loaded and has live instances. The earlier forName
        // control used a name whose BOTH resolves were fresh, which is a different condition.
        Class<?> again = Class.forName(
                "org.junit.platform.engine.discovery.ClasspathResourceSelector$IdentifierParser", false, cl);
        System.out.println("forName again==first= " + (again == first ? 1 : 0) + " (want 1)");
        System.out.println("forName again isCls = " + (again instanceof Class ? 1 : 0) + " (want 1)");
        Object v2 = again.getDeclaredConstructor().newInstance();
        System.out.println("again ctor classOk  = " + (v2.getClass() == first ? 1 : 0) + " (want 1)");

        // ---- CONSTRUCT: through the iterator, which builds one per next().
        Iterator<DiscoverySelectorIdentifierParser> it = parsers.iterator();
        System.out.println("iterator hasNext    = " + (it.hasNext() ? 1 : 0) + " (want 1)");
        DiscoverySelectorIdentifierParser p0 = it.next();
        System.out.println("iterator[0] nonNull = " + (p0 != null ? 1 : 0) + " (want 1)");
        Class<?> pc = p0.getClass();
        System.out.println("iterator[0] mirror  = " + (pc != null ? 1 : 0) + " (want 1)");
        System.out.println("iterator[0] sameAs0 = " + (pc == first ? 1 : 0) + " (want 1)");
        System.out.println("iterator[0] isClass = " + (pc instanceof Class ? 1 : 0) + " (want 1)");
        System.out.println("iterator[0] isParse = "
                + (p0 instanceof DiscoverySelectorIdentifierParser ? 1 : 0) + " (want 1)");
        System.out.println("first     isClass   = " + (first instanceof Class ? 1 : 0) + " (want 1)");
        System.out.println("iter vs get sameCls = " + (pc == viaGet.getClass() ? 1 : 0) + " (want 1)");
        // isInstance is a native Type walk, so it answers even when the MIRROR is the thing that is wrong.
        System.out.println("first.isInstance p0 = " + (first.isInstance(p0) ? 1 : 0) + " (want 1)");
        System.out.println("first.isInstance vg = " + (first.isInstance(viaGet) ? 1 : 0) + " (want 1)");
        System.out.println("iterator[0] isUri   = " + (pc == c1 ? 1 : 0) + " (want 0)");
        // CONTROL for isInstance: it must be able to say NO, or the two lines above prove nothing.
        System.out.println("first.isInstance str= " + (first.isInstance("x") ? 1 : 0) + " (want 0)");
        // Which of the 13 provider mirrors, if any, IS this? -1 means it matches none of them.
        int match = -1;
        int mi = 0;
        while (mi < pl.size())
        {
            if (pl.get(mi).type() == pc)
            {
                match = mi;
            }
            mi += 1;
        }
        System.out.println("iterator[0] matches = " + match + " (want 0)");
        System.out.println("getClass stable     = " + (p0.getClass() == p0.getClass() ? 1 : 0) + " (want 1)");
        // CONTROL: the same two calls on an object whose class was NOT reached through ServiceLoader.
        Object ctl = new java.util.ArrayList<String>();
        System.out.println("control  class      = " + ctl.getClass().getName() + " (want java.util.ArrayList)");
        String pn = pc.getName();
        System.out.println("iterator[0] nameLen = " + (pn == null ? -1 : pn.length()) + " (want 78)");
        System.out.print("iterator[0] class   = ");
        System.out.println(pn);
        String pf = p0.getPrefix();
        System.out.print("iterator[0] prefix  = ");
        System.out.println(pf);

        // ---- LAZY: get() builds a NEW instance each call, and only when asked.
        DiscoverySelectorIdentifierParser a = pl.get(0).get();
        DiscoverySelectorIdentifierParser b = pl.get(0).get();
        System.out.println("get() distinct      = " + (a != b ? 1 : 0) + " (want 1)");
        System.out.println("get() sameClass     = " + (a.getClass() == b.getClass() ? 1 : 0) + " (want 1)");

        System.out.println("ServiceLoaderProbe done");
    }
}
