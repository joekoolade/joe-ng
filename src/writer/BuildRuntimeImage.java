package writer;

import asm.CodeBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * M2: build the multi-class runtime image. Starting from {@code vm.VM.boot},
 * {@link ImageBuilder} discovers and compiles every reachable method across
 * classes (here {@code VM} + {@code board.bcm2711.Uart}), lays them out, and
 * relocates the calls between them — a multi-method, multi-class Java program
 * booting on bare metal (PLAN.md §4 M2). It still prints "hello from joe-ng",
 * now driven by real cross-class {@code BL} calls rather than one inlined method.
 *
 * Usage: {@code java writer.BuildRuntimeImage [classesDir] [output]}.
 */
public final class BuildRuntimeImage
{

    private static final String ENTRY = "vm/VM.boot()V";

    public static CodeBuffer build(Path classesDir) throws IOException
    {
        // The registry is the writer's class source (PLAN.md §M5.5c): a pure name->bytes
        // lookup ImageBuilder can run on metal. The seed host fills it from the compiled
        // tree here; over-inclusion is harmless (parsing is lazy, only reachable classes
        // are ever parsed). The metal harness will fill it from embedded blobs instead.
        ClassRegistry registry = new ClassRegistry();
        registerTree(classesDir, registry);
        // Real, unmodified classes from the JDK's java.base module (not in the compiled tree).
        try (var in = Integer.class.getResourceAsStream("/java/lang/Math.class"))
        {
            registry.add("java/lang/Math", in.readAllBytes());
        }
        try (var in = Integer.class.getResourceAsStream("/java/lang/Integer.class"))
        {
            registry.add("java/lang/Integer", in.readAllBytes());
        }
        try (var in = Integer.class.getResourceAsStream("/java/lang/Long.class"))
        {
            registry.add("java/lang/Long", in.readAllBytes());
        }

        ImageBuilder ib = new ImageBuilder(registry);
        // Embed raw .class bytes for the on-metal loader (M4) — NOT compiled here.
        ib.addBlob("vm/VM.guestBytes",   "vm/VM.guestLen",   "vm/Guest",       registry.rawBytes("vm/Guest"));
        ib.addBlob("vm/VM.greeterBytes", "vm/VM.greeterLen", "vm/Greeter",     registry.rawBytes("vm/Greeter"));
        ib.addBlob("vm/VM.alphaBytes",   "vm/VM.alphaLen",   "vm/Alpha",       registry.rawBytes("vm/Alpha"));
        ib.addBlob("vm/VM.betaBytes",    "vm/VM.betaLen",    "vm/Beta",        registry.rawBytes("vm/Beta"));
        ib.addBlob("vm/VM.myExcBytes",   "vm/VM.myExcLen",   "vm/MyExc",       registry.rawBytes("vm/MyExc"));
        ib.addBlob("vm/VM.mathBytes",    "vm/VM.mathLen",    "java/lang/Math", registry.rawBytes("java/lang/Math"));
        // The mini java.base + the demand-loaded program (embedded raw; loaded on the metal by vm/Loader).
        // Order here must match VM.blobClass (the metal writer's self-build model of the blob region).
        ib.addBlob("vm/VM.runnableBytes",    "vm/VM.runnableLen",    "java/lang/Runnable",             registry.rawBytes("java/lang/Runnable"));
        ib.addBlob("vm/VM.threadBytes",      "vm/VM.threadLen",      "java/lang/Thread",               registry.rawBytes("java/lang/Thread"));
        ib.addBlob("vm/VM.semBytes",         "vm/VM.semLen",         "java/util/concurrent/Semaphore", registry.rawBytes("java/util/concurrent/Semaphore"));
        ib.addBlob("vm/VM.philosopherBytes", "vm/VM.philosopherLen", "demo/Philosopher",               registry.rawBytes("demo/Philosopher"));
        ib.addBlob("vm/VM.philBytes",        "vm/VM.philLen",        "demo/DiningPhilosophers",        registry.rawBytes("demo/DiningPhilosophers"));
        // invokedynamic slice 1: a mini java/lang/String (concat result) + the concat demo program.
        ib.addBlob("vm/VM.stringBytes",      "vm/VM.stringLen",      "java/lang/String",               registry.rawBytes("java/lang/String"));
        ib.addBlob("vm/VM.concatDemoBytes",  "vm/VM.concatDemoLen",  "demo/ConcatDemo",                registry.rawBytes("demo/ConcatDemo"));
        // invokedynamic slice 1c/1d: the lambda demo program + a SAM-with-arg functional interface.
        ib.addBlob("vm/VM.lambdaDemoBytes",  "vm/VM.lambdaDemoLen",  "demo/LambdaDemo",                registry.rawBytes("demo/LambdaDemo"));
        ib.addBlob("vm/VM.intOpBytes",       "vm/VM.intOpLen",       "demo/IntOp",                     registry.rawBytes("demo/IntOp"));
        // Experiment: a real, unmodified java.base class (java/lang/Integer) to map the loader's reach.
        ib.addBlob("vm/VM.integerBytes",     "vm/VM.integerLen",     "java/lang/Integer",              registry.rawBytes("java/lang/Integer"));
        ib.addBlob("vm/VM.floatDemoBytes",   "vm/VM.floatDemoLen",   "demo/FloatDemo",                 registry.rawBytes("demo/FloatDemo"));
        ib.addBlob("vm/VM.nativeDemoBytes",  "vm/VM.nativeDemoLen",  "demo/NativeDemo",                registry.rawBytes("demo/NativeDemo"));
        // real-shaped String + StringBuilder + their demo.
        ib.addBlob("vm/VM.stringBuilderBytes", "vm/VM.stringBuilderLen", "java/lang/StringBuilder",     registry.rawBytes("java/lang/StringBuilder"));
        ib.addBlob("vm/VM.strDemoBytes",     "vm/VM.strDemoLen",     "demo/StrDemo",                   registry.rawBytes("demo/StrDemo"));
        // implicit exceptions: the mini exception hierarchy (Type chain for catch) + the demo. Order matches VM.blobClass.
        ib.addBlob("vm/VM.throwableBytes",   "vm/VM.throwableLen",   "java/lang/Throwable",                     registry.rawBytes("java/lang/Throwable"));
        ib.addBlob("vm/VM.exceptionBytes",   "vm/VM.exceptionLen",   "java/lang/Exception",                     registry.rawBytes("java/lang/Exception"));
        ib.addBlob("vm/VM.runtimeExcBytes",  "vm/VM.runtimeExcLen",  "java/lang/RuntimeException",              registry.rawBytes("java/lang/RuntimeException"));
        ib.addBlob("vm/VM.npeBytes",         "vm/VM.npeLen",         "java/lang/NullPointerException",          registry.rawBytes("java/lang/NullPointerException"));
        ib.addBlob("vm/VM.ioobeBytes",       "vm/VM.ioobeLen",       "java/lang/IndexOutOfBoundsException",     registry.rawBytes("java/lang/IndexOutOfBoundsException"));
        ib.addBlob("vm/VM.aioobeBytes",      "vm/VM.aioobeLen",      "java/lang/ArrayIndexOutOfBoundsException", registry.rawBytes("java/lang/ArrayIndexOutOfBoundsException"));
        ib.addBlob("vm/VM.excDemoBytes",     "vm/VM.excDemoLen",     "demo/ExcDemo",                            registry.rawBytes("demo/ExcDemo"));
        // mini collections: java/util/ArrayList + its demo. Order matches VM.blobClass.
        ib.addBlob("vm/VM.arrayListBytes",   "vm/VM.arrayListLen",   "java/util/ArrayList",                     registry.rawBytes("java/util/ArrayList"));
        ib.addBlob("vm/VM.listDemoBytes",    "vm/VM.listDemoLen",    "demo/ListDemo",                           registry.rawBytes("demo/ListDemo"));
        // java/util/HashMap + the mini java/lang/Object root (its hashCode/equals slots) + the demo.
        ib.addBlob("vm/VM.objectBytes",      "vm/VM.objectLen",      "java/lang/Object",                        registry.rawBytes("java/lang/Object"));
        ib.addBlob("vm/VM.hashMapBytes",     "vm/VM.hashMapLen",     "java/util/HashMap",                       registry.rawBytes("java/util/HashMap"));
        ib.addBlob("vm/VM.mapDemoBytes",     "vm/VM.mapDemoLen",     "demo/MapDemo",                            registry.rawBytes("demo/MapDemo"));
        // real-java.base probe: a second unmodified class (java/lang/Long); Integer is already embedded above.
        ib.addBlob("vm/VM.longBytes",        "vm/VM.longLen",        "java/lang/Long",                          registry.rawBytes("java/lang/Long"));
        // dep/native surface for real Integer.parseInt: mini Character.digit + NumberFormatException hierarchy.
        ib.addBlob("vm/VM.characterBytes",   "vm/VM.characterLen",   "java/lang/Character",                     registry.rawBytes("java/lang/Character"));
        ib.addBlob("vm/VM.illegalArgBytes",  "vm/VM.illegalArgLen",  "java/lang/IllegalArgumentException",      registry.rawBytes("java/lang/IllegalArgumentException"));
        ib.addBlob("vm/VM.numberFmtBytes",   "vm/VM.numberFmtLen",   "java/lang/NumberFormatException",         registry.rawBytes("java/lang/NumberFormatException"));
        // reachable-loadAll demo: loads the real Integer via the closure path and calls parseInt.
        ib.addBlob("vm/VM.parseAllDemoBytes","vm/VM.parseAllDemoLen","demo/ParseAllDemo",                       registry.rawBytes("demo/ParseAllDemo"));
        // real Integer.toString surface: mini StringLatin1 + DecimalDigits + the demo.
        ib.addBlob("vm/VM.stringLatin1Bytes","vm/VM.stringLatin1Len","java/lang/StringLatin1",                  registry.rawBytes("java/lang/StringLatin1"));
        ib.addBlob("vm/VM.decimalDigitsBytes","vm/VM.decimalDigitsLen","jdk/internal/util/DecimalDigits",       registry.rawBytes("jdk/internal/util/DecimalDigits"));
        ib.addBlob("vm/VM.toStringDemoBytes","vm/VM.toStringDemoLen","demo/ToStringDemo",                       registry.rawBytes("demo/ToStringDemo"));
        // Integer.toHexString + Long.toString demo (real Integer.digits seeded by the loader; DecimalDigits long overloads).
        ib.addBlob("vm/VM.hexLongDemoBytes", "vm/VM.hexLongDemoLen", "demo/HexLongDemo",                        registry.rawBytes("demo/HexLongDemo"));
        return ib.build(ENTRY);
    }

    /** Register every {@code .class} under {@code dir}, keyed by its internal name. */
    private static void registerTree(Path dir, ClassRegistry registry) throws IOException
    {
        try (var paths = Files.walk(dir))
        {
            for (Path p : (Iterable<Path>) paths::iterator)
            {
                if (!p.toString().endsWith(".class"))
                {
                    continue;
                }
                String rel = dir.relativize(p).toString().replace(File.separatorChar, '/');
                registry.add(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
            }
        }
    }

    public static void main(String[] args) throws IOException
    {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "out");
        Path out = Path.of(args.length > 1 ? args[1] : "kernel8.img");

        CodeBuffer code = build(classesDir);
        BootImageWriter writer = new BootImageWriter(code);
        writer.writeImage(out);

        System.out.printf("wrote %s (%s + reachable methods, %d bytes)%n",
                          out.toAbsolutePath(), ENTRY, code.wordCount() * 4);
    }
}
