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
        // A real class from the JDK's java.base module (not in the compiled tree).
        try (var in = Integer.class.getResourceAsStream("/java/lang/Math.class"))
        {
            registry.add("java/lang/Math", in.readAllBytes());
        }

        ImageBuilder ib = new ImageBuilder(registry);
        // Embed raw .class bytes for the on-metal loader (M4) — NOT compiled here.
        ib.addBlob("vm/VM.guestBytes",   "vm/VM.guestLen",   "vm/Guest",       registry.rawBytes("vm/Guest"));
        ib.addBlob("vm/VM.greeterBytes", "vm/VM.greeterLen", "vm/Greeter",     registry.rawBytes("vm/Greeter"));
        ib.addBlob("vm/VM.alphaBytes",   "vm/VM.alphaLen",   "vm/Alpha",       registry.rawBytes("vm/Alpha"));
        ib.addBlob("vm/VM.betaBytes",    "vm/VM.betaLen",    "vm/Beta",        registry.rawBytes("vm/Beta"));
        ib.addBlob("vm/VM.myExcBytes",   "vm/VM.myExcLen",   "vm/MyExc",       registry.rawBytes("vm/MyExc"));
        ib.addBlob("vm/VM.mathBytes",    "vm/VM.mathLen",    "java/lang/Math", registry.rawBytes("java/lang/Math"));
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
