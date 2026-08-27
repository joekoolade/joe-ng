import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.stream.Stream;

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.provider.Arguments;

/*
 * One runner for every stock java/util/zip JUnit test joe-ng can host, so a single image and a single boot
 * covers all of them. That matters more than tidiness here: the demand-load closure of a program that
 * touches ZipOutputStream is ~500 classes (it reaches java.time, GregorianCalendar, Formatter, BigInteger,
 * ForkJoinPool), and paying that once instead of once per test is the difference between a run that
 * finishes and seven that do not.
 *
 * Each test class is UNMODIFIED; this only replaces the JUnit engine, invoking each @Test method on a fresh
 * instance (and calling the @BeforeEach method first where the test has one), exactly as the engine would.
 * A method passes if it returns without throwing.
 */
public class ZipJUnitAll
{
    interface Body
    {
        void run() throws Throwable;
    }

    /** One @ParameterizedTest case: the test instance and the argument the engine would have spread into it. */
    interface Case
    {
        void run(BasicGZIPInputStreamTest t, Executable arg) throws Throwable;
    }

    static int ran = 0;
    static int fails = 0;
    static String current = "";

    static void group(String name)
    {
        current = name;
        System.out.println("-- " + name);
    }

    static void run(String name, Body b)
    {
        ran += 1;
        try
        {
            b.run();
            System.out.println("  ok   " + name);
        }
        catch (Throwable e)
        {
            System.out.println("  FAIL " + name + " : " + e);
            e.printStackTrace();                       // the frame is the diagnosis; a bare NPE is not
            fails += 1;
        }
    }

    /**
     * Calls, from STATICALLY REACHABLE code, everything the reflectively-reached {@code @MethodSource}
     * factories call. RTA cannot see through reflection: those factories are invoked only via
     * {@code Method.invoke}, so nothing reachable from main mentions the {@code Stream.of} /
     * {@code Arguments.of} they use, those bodies are never pulled, and each of their call sites becomes a
     * DENYLIST TRAP naming the callee. Seeding has to match the exact DESCRIPTOR, not just the name --
     * {@code Stream.of(T)} and the varargs {@code Stream.of(T...)} are different call sites, and seeding
     * the wrong one leaves the trap in place.
     */
    static void seedFactoryClosure()
    {
        Iterator<Arguments> it = Stream.of(Arguments.of("seed"), Arguments.of("seed")).iterator();
        while (it.hasNext())
        {
            it.next();
        }
    }

    /**
     * Runs a {@code @ParameterizedTest} whose arguments come from a {@code @MethodSource} factory. The
     * factory is PRIVATE and static, exactly as the JUnit engine expects to find it, so it is reached the
     * same way the engine reaches it: reflectively, with setAccessible.
     *
     * <p>The stream is consumed with {@code iterator()} rather than {@code toList()} on purpose --
     * toList() pulls ImmutableCollections$Access$1 and a good deal more closure for no gain here.
     */
    static void runParameterized(String testName, String factory, Case c) throws Exception
    {
        Method f = BasicGZIPInputStreamTest.class.getDeclaredMethod(factory);
        f.setAccessible(true);
        Stream<Arguments> src = (Stream<Arguments>) f.invoke(null);
        Iterator<Arguments> it = src.iterator();
        int i = 0;
        while (it.hasNext())
        {
            Object[] a = it.next().get();
            Executable arg = (Executable) a[0];
            BasicGZIPInputStreamTest t = new BasicGZIPInputStreamTest();
            run(testName + "[" + i + "]", () -> c.run(t, arg));
            i += 1;
        }
    }

    public static void main(String[] args) throws Exception
    {
        group("DeflaterClose");
        run("testCloseMultipleTimes", () -> { DeflaterClose t = new DeflaterClose(); t.testCloseMultipleTimes(); });
        run("testCloseThenEnd", () -> { DeflaterClose t = new DeflaterClose(); t.testCloseThenEnd(); });
        run("testEndThenClose", () -> { DeflaterClose t = new DeflaterClose(); t.testEndThenClose(); });

        group("InflaterClose");
        run("testCloseMultipleTimes", () -> { InflaterClose t = new InflaterClose(); t.testCloseMultipleTimes(); });
        run("testCloseThenEnd", () -> { InflaterClose t = new InflaterClose(); t.testCloseThenEnd(); });
        run("testEndThenClose", () -> { InflaterClose t = new InflaterClose(); t.testEndThenClose(); });

        group("GZIPInputStreamAvailable");
        run("testZeroAvailable", () -> { GZIPInputStreamAvailable t = new GZIPInputStreamAvailable(); t.testZeroAvailable(); });

        group("DataDescriptorIgnoreCrcAndSizeFields");
        run("shouldIgnoreCrcAndSizeValuesInStreamingMode",
            () -> { DataDescriptorIgnoreCrcAndSizeFields t = new DataDescriptorIgnoreCrcAndSizeFields();
                    t.shouldIgnoreCrcAndSizeValuesInStreamingMode(); });

        group("DataDescriptorSignatureMissing");
        run("shouldParseSignaturelessDescriptor",
            () -> { DataDescriptorSignatureMissing t = new DataDescriptorSignatureMissing();
                    t.shouldParseSignaturelessDescriptor(); });

        group("CloseWrappedStream");
        run("exceptionDuringFinish", () -> { CloseWrappedStream t = new CloseWrappedStream(); t.exceptionDuringFinish(); });
        run("noExceptions", () -> { CloseWrappedStream t = new CloseWrappedStream(); t.noExceptions(); });
        run("exceptionDuringClose", () -> { CloseWrappedStream t = new CloseWrappedStream(); t.exceptionDuringClose(); });
        run("doubleCloseShouldCloseWrappedStreamOnce", () -> { CloseWrappedStream t = new CloseWrappedStream(); t.doubleCloseShouldCloseWrappedStreamOnce(); });
        run("exceptionDuringFinishAndClose", () -> { CloseWrappedStream t = new CloseWrappedStream(); t.exceptionDuringFinishAndClose(); });
        run("sameExceptionDuringFinishAndClose", () -> { CloseWrappedStream t = new CloseWrappedStream(); t.sameExceptionDuringFinishAndClose(); });

        group("Zip64DataDescriptor");
        run("shouldReadZip64Descriptor", () -> { Zip64DataDescriptor t = new Zip64DataDescriptor(); t.setup(); t.shouldReadZip64Descriptor(); });
        run("shouldIgnoreInvalidExtraSize", () -> { Zip64DataDescriptor t = new Zip64DataDescriptor(); t.setup(); t.shouldIgnoreInvalidExtraSize(); });
        run("shouldIgnoreNoZip64Header", () -> { Zip64DataDescriptor t = new Zip64DataDescriptor(); t.setup(); t.shouldIgnoreNoZip64Header(); });
        run("shouldFailParsingZip64With4ByteDataDescriptor", () -> { Zip64DataDescriptor t = new Zip64DataDescriptor(); t.setup(); t.shouldFailParsingZip64With4ByteDataDescriptor(); });
        run("shouldIgnoreExcessiveExtraSize", () -> { Zip64DataDescriptor t = new Zip64DataDescriptor(); t.setup(); t.shouldIgnoreExcessiveExtraSize(); });
        run("shouldIgnoreNoMagicMarkers", () -> { Zip64DataDescriptor t = new Zip64DataDescriptor(); t.setup(); t.shouldIgnoreNoMagicMarkers(); });
        run("shouldIgnoreTrucatedZip64Extra", () -> { Zip64DataDescriptor t = new Zip64DataDescriptor(); t.setup(); t.shouldIgnoreTrucatedZip64Extra(); });

        group("BasicGZIPInputStreamTest");
        seedFactoryClosure();
        runParameterized("testNPEFromConstructors", "npeFromConstructors",
                         (t, e) -> t.testNPEFromConstructors(e));
        runParameterized("testIAEFromConstructors", "iaeFromConstructors",
                         (t, e) -> t.testIAEFromConstructors(e));
        runParameterized("testIOEFromConstructors", "ioeFromConstructors",
                         (t, e) -> t.testIOEFromConstructors(e));

        System.out.println("zip junit: ran " + ran + ", failures " + fails);
        if (fails == 0)
        {
            System.out.println("ALL PASSED");
        }
    }
}
