package writer;

import asm.CodeBuffer;
import classfile.ClassFile;
import compiler.BaselineCompiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * M1c goal: a fully compiled printing boot. Our classfile parser reads
 * {@code vm/VM.class}, our baseline compiler lowers {@code boot()} — EL2→EL1
 * drop, FP enable, stack, mini-UART bring-up, and the print loop — to A64, and
 * our writer emits {@code kernel8.img}. The boot message is appended as the
 * method's data blob (referenced via {@code Magic.message()}). This is the
 * metacircular equivalent of the hand-emitted {@code vm.EmitBoot}: same output
 * (prints "hello from joe2"), but produced by compiling real Java bytecode.
 *
 * Usage: {@code java writer.BuildCompiledBootImage [classesDir] [output]}.
 */
public final class BuildCompiledBootImage {

    private static final byte[] MESSAGE = "hello from joe2\r\n".getBytes(StandardCharsets.US_ASCII);

    public static CodeBuffer compileBoot(Path classesDir) throws IOException {
        ClassFile vm = ClassFile.parse(classesDir.resolve("vm/VM.class"));
        CodeBuffer cb = new CodeBuffer();
        new BaselineCompiler(vm, MESSAGE).compile(vm.method("boot", "()V"), cb);
        return cb;
    }

    public static void main(String[] args) throws IOException {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "out");
        Path out = Path.of(args.length > 1 ? args[1] : "kernel8.img");

        CodeBuffer code = compileBoot(classesDir);
        BootImageWriter writer = new BootImageWriter(code);
        writer.writeImage(out);

        System.out.print(writer.layoutDump());
        System.out.printf("wrote %s (compiled from vm.VM.boot bytecode, %d bytes)%n",
                out.toAbsolutePath(), code.wordCount() * 4);
    }
}
