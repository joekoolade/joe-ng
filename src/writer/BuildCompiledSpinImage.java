package writer;

import asm.CodeBuffer;
import classfile.ClassFile;
import compiler.BaselineCompiler;

import java.io.IOException;
import java.nio.file.Path;

/**
 * M1c step 1: the metacircular path proven end to end — our own classfile parser
 * reads {@code vm/VM.class} (produced by javac), our own baseline compiler turns
 * its {@code spin()} bytecode into A64, and our writer emits a booting image.
 * No hand-assembly: the machine code comes from compiling real Java bytecode.
 *
 * Usage: {@code java writer.BuildCompiledSpinImage [classesDir] [output]}.
 */
public final class BuildCompiledSpinImage
{

    public static CodeBuffer compileSpin(Path classesDir) throws IOException
    {
        ClassFile vm = ClassFile.parse(classesDir.resolve("vm/VM.class"));
        ClassFile.Method spin = vm.method("spin", "()V");
        CodeBuffer cb = new CodeBuffer();
        new BaselineCompiler(vm).compile(spin, cb);
        return cb;
    }

    public static void main(String[] args) throws IOException
    {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "out");
        Path out = Path.of(args.length > 1 ? args[1] : "kernel8.img");

        CodeBuffer code = compileSpin(classesDir);
        BootImageWriter writer = new BootImageWriter(code);
        writer.writeImage(out);

        System.out.print(writer.layoutDump());
        System.out.printf("wrote %s (compiled from vm.VM.spin bytecode)%n", out.toAbsolutePath());
    }
}
