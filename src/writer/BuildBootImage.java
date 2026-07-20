package writer;

import asm.CodeBuffer;
import vm.EmitBoot;

import java.io.IOException;
import java.nio.file.Path;

/**
 * M1 target: the writer emits a {@code kernel8.img} whose compiled boot code
 * prints "hello from joe-ng" over the mini-UART (PLAN.md §4). Same pipeline as M0
 * (our assembler → our writer → raw image), now driving real board bring-up.
 *
 * Usage: {@code java writer.BuildBootImage [output-path]} (default kernel8.img).
 */
public final class BuildBootImage
{
    public static void main(String[] args) throws IOException
    {
        Path out = Path.of(args.length > 0 ? args[0] : "kernel8.img");

        CodeBuffer code = EmitBoot.build();
        BootImageWriter writer = new BootImageWriter(code);
        writer.writeImage(out);

        System.out.print(writer.layoutDump());
        System.out.printf("wrote %s (%d bytes)%n", out.toAbsolutePath(), code.wordCount() * 4);
    }
}
