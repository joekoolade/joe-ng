import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A JUnit runner for bare metal: give it test class names, it discovers the {@code @Test} methods by
 * ANNOTATION and runs each on a fresh instance, with {@code @BeforeEach}/{@code @AfterEach} around it.
 *
 * <p>This replaces the hand-written per-suite runners (ZipJUnitAll and friends), which had to spell out every
 * test method name because joe-ng could not read annotations. The annotations and {@code Assertions} come from
 * the real JUnit jar, demand-loaded at runtime out of the RAMFS archive named by {@code /etc/init}'s
 * {@code classpath=} -- none of it is baked into the image.
 *
 * <p>It is NOT the Jupiter engine: no nested/parameterised tests, no lifecycle beyond BeforeEach/AfterEach, no
 * display names, no assumptions. It is the subset that a jtreg {@code @run junit} test in practice uses, and
 * it runs where the engine cannot (the engine needs annotation element VALUES, ServiceLoader and
 * java.nio.file).
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
                runOne(c, all, all[i]);
            }
            i += 1;
        }
    }

    /** One test: fresh instance, @BeforeEach, the test, @AfterEach. A fresh instance per test is JUnit's rule
     *  and it matters -- these tests routinely mutate fields in @BeforeEach. */
    private static void runOne(Class<?> c, Method[] all, Method test) {
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
            test.invoke(instance, new Object[0]);
        } catch (Throwable t) {
            report(test, "FAIL", t);
            invokeTagged(all, instance, AfterEach.class, test);
            return;
        }
        if (!invokeTagged(all, instance, AfterEach.class, test)) {
            return;
        }
        System.out.println("  ok   " + test.getName());
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
