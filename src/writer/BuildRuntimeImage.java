package writer;

import asm.CodeBuffer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * M2: build the multi-class runtime image. Starting from {@code vm.VM.boot},
 * {@link ImageBuilder} discovers and compiles every reachable method across
 * classes (here {@code VM} + {@code board.bcm2711.Uart}), lays them out, and
 * relocates the calls between them — a multi-method, multi-class Java program
 * booting on bare metal (PLAN.md §4 M2). It still prints "hello from joe2",
 * now driven by real cross-class {@code BL} calls rather than one inlined method.
 *
 * Usage: {@code java writer.BuildRuntimeImage [classesDir] [output]}.
 */
public final class BuildRuntimeImage {

    private static final String ENTRY = "vm/VM.boot()V";

    public static CodeBuffer build(Path classesDir) throws IOException {
        return new ImageBuilder(classesDir).build(ENTRY);
    }

    public static void main(String[] args) throws IOException {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "out");
        Path out = Path.of(args.length > 1 ? args[1] : "kernel8.img");

        CodeBuffer code = build(classesDir);
        BootImageWriter writer = new BootImageWriter(code);
        writer.writeImage(out);

        System.out.printf("wrote %s (%s + reachable methods, %d bytes)%n",
                out.toAbsolutePath(), ENTRY, code.wordCount() * 4);
    }
}
