import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;

/**
 * A JUnit runner for bare metal: give it test class names, it discovers the {@code @Test} methods by
 * ANNOTATION and runs each on a fresh instance, with {@code @BeforeEach}/{@code @AfterEach} around it.
 *
 * <p>This replaces the hand-written per-suite runners (ZipJUnitAll and friends), which had to spell out every
 * test method name because joe-ng could not read annotations. The annotations and {@code Assertions} come from
 * the real JUnit jar, demand-loaded at runtime out of the RAMFS archive named by {@code /etc/init}'s
 * {@code classpath=} -- none of it is baked into the image.
 *
 * <p>{@code @ParameterizedTest} is supported for the {@code @MethodSource} case ONLY in its DEFAULT-NAME form
 * -- the factory is the static method with the same name as the test. That is not a simplification for its own
 * sake: reading {@code @MethodSource("someName")}'s element VALUE needs a live annotation instance (a Proxy),
 * which joe-ng has no runtime for, while {@code isAnnotationPresent} is answered from the classfile. The
 * default-name form is what the stock tests in practice write.
 *
 * <p>It is NOT the Jupiter engine: no nested tests, no lifecycle beyond BeforeEach/AfterEach, no display names,
 * no assumptions, no argument sources other than {@code @MethodSource}. It is the subset that a jtreg
 * {@code @run junit} test in practice uses, and it runs where the engine cannot (the engine needs annotation
 * element VALUES, ServiceLoader and java.nio.file).
 */
public class MetalJUnit {

    private static int run;
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            System.out.println("MetalJUnit: no test classes given");
            return;
        }
        int i = 0;
        while (i < args.length) {
            runClass(args[i]);
            i += 1;
        }
        System.out.println("metal junit: ran " + run + ", failures " + failed);
        System.out.println(failed == 0 ? "ALL PASSED" : "FAILURES");
    }

    private static void runClass(String name) {
        System.out.println("-- " + name);
        Class<?> c;
        try {
            c = Class.forName(name);
        } catch (Throwable t) {
            System.out.println("  CANNOT LOAD: " + t.getClass().getName());
            failed += 1;
            return;
        }
        Method[] all;
        try {
            all = c.getDeclaredMethods();
        } catch (Throwable t) {
            System.out.println("  CANNOT ENUMERATE: " + t.getClass().getName());
            failed += 1;
            return;
        }
        int i = 0;
        while (i < all.length) {
            if (all[i].isAnnotationPresent(Test.class)) {
                runOne(c, all, all[i], new Object[0], "");
            } else if (all[i].isAnnotationPresent(ParameterizedTest.class)) {
                runParameterized(c, all, all[i]);
            }
            i += 1;
        }
    }

    /** One test: fresh instance, @BeforeEach, the test, @AfterEach. A fresh instance per test is JUnit's rule
     *  and it matters -- these tests routinely mutate fields in @BeforeEach. */
    private static void runOne(Class<?> c, Method[] all, Method test, Object[] args, String label) {
        run += 1;
        Object instance;
        try {
            instance = c.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            report(test, "INSTANTIATE", t);
            return;
        }
        if (!invokeTagged(all, instance, BeforeEach.class, test)) {
            return;
        }
        try {
            test.invoke(instance, args);
        } catch (Throwable t) {
            report(test, "FAIL" + label, t);
            invokeTagged(all, instance, AfterEach.class, test);
            return;
        }
        if (!invokeTagged(all, instance, AfterEach.class, test)) {
            return;
        }
        System.out.println("  ok   " + test.getName() + label);
    }

    /**
     * One {@code @ParameterizedTest}: call the same-named static factory, then run the test once per argument
     * set on its own fresh instance. The factory is reached the way the engine reaches it -- reflectively, with
     * setAccessible, since these factories are conventionally private.
     *
     * <p>The stream is consumed with {@code iterator()} rather than {@code toList()} on purpose: toList() pulls
     * ImmutableCollections$Access$1 and a good deal more closure for no gain here.
     */
    private static void runParameterized(Class<?> c, Method[] all, Method test) {
        Method factory = findFactory(c, test.getName());
        if (factory == null) {
            run += 1;
            failed += 1;
            System.out.println("  NO FACTORY " + test.getName() + " (no static " + test.getName() + "() to source arguments from)");
            return;
        }
        Object src;
        try {
            factory.setAccessible(true);
            src = factory.invoke(null, new Object[0]);
        } catch (Throwable t) {
            run += 1;
            report(test, "FACTORY", t);
            return;
        }
        int i = 0;
        // An ARRAY factory is indexed directly rather than wrapped in an Iterator: a stock @MethodSource may
        // return Arguments[] just as readily as Stream<Arguments>, and the array form costs no stream closure.
        if (src instanceof Object[]) {
            Object[] rows = (Object[]) src;
            while (i < rows.length) {
                runOne(c, all, test, spread(rows[i]), "[" + i + "]");
                i += 1;
            }
        } else {
            Iterator<?> it = iterate(src);
            if (it == null) {
                run += 1;
                failed += 1;
                System.out.println("  BAD FACTORY " + test.getName() + " (not a Stream, Iterable or array)");
                return;
            }
            while (it.hasNext()) {
                runOne(c, all, test, spread(it.next()), "[" + i + "]");
                i += 1;
            }
        }
        if (i == 0) {
            run += 1;
            failed += 1;
            System.out.println("  EMPTY FACTORY " + test.getName());
        }
    }

    /** The static no-arg method named {@code name} -- JUnit's default @MethodSource, the test's own name. */
    private static Method findFactory(Class<?> c, String name) {
        Method[] all = c.getDeclaredMethods();
        int i = 0;
        while (i < all.length) {
            if (all[i].getName().equals(name) && all[i].getParameterCount() == 0
                    && java.lang.reflect.Modifier.isStatic(all[i].getModifiers())) {
                return all[i];
            }
            i += 1;
        }
        return null;
    }

    private static Iterator<?> iterate(Object src) {
        if (src instanceof Stream) {
            return ((Stream<?>) src).iterator();
        }
        if (src instanceof Iterable) {
            return ((Iterable<?>) src).iterator();
        }
        return null;
    }

    /** One element of the argument stream -> the test's parameter array. */
    private static Object[] spread(Object element) {
        if (element instanceof Arguments) {
            return ((Arguments) element).get();
        }
        if (element instanceof Object[]) {
            return (Object[]) element;
        }
        return new Object[] { element };      // a single-parameter test may stream bare values
    }

    /** Invoke every method carrying {@code anno}; false if one threw (the test is then already reported). */
    private static boolean invokeTagged(Method[] all, Object instance, Class<?> anno, Method test) {
        int i = 0;
        while (i < all.length) {
            if (all[i].isAnnotationPresent(anno)) {
                try {
                    all[i].invoke(instance, new Object[0]);
                } catch (Throwable t) {
                    report(test, "LIFECYCLE " + all[i].getName(), t);
                    return false;
                }
            }
            i += 1;
        }
        return true;
    }

    private static void report(Method test, String what, Throwable t) {
        failed += 1;
        Throwable cause = t;
        // Method.invoke wraps whatever the test threw; the wrapper's type tells us nothing useful.
        if (t instanceof java.lang.reflect.InvocationTargetException) {
            Throwable inner = ((java.lang.reflect.InvocationTargetException) t).getCause();
            if (inner != null) {
                cause = inner;
            }
        }
        System.out.println("  " + what + " " + test.getName() + " -> " + cause.getClass().getName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        // An assertion failure is the test's own verdict and its message says everything. Anything else is a
        // fault in the harness or the VM, so show where it came from. printStackTrace(), not getStackTrace():
        // the array-materialising path wild-branched from here once, while this one is what ExcDemo uses.
        if (!cause.getClass().getName().startsWith("org.opentest4j.")) {
            cause.printStackTrace();
        } else if (cause.getCause() != null) {
            // ... except when the verdict WRAPS a throwable: assertDoesNotThrow and assertThrows both report
            // "unexpected exception" by name only, and the name alone has never been enough to place one.
            System.out.println("  caused by:");
            cause.getCause().printStackTrace();
        }
    }
}
