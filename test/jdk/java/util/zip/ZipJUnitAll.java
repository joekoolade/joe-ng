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

        System.out.println("zip junit: ran " + ran + ", failures " + fails);
        if (fails == 0)
        {
            System.out.println("ALL PASSED");
        }
    }
}
