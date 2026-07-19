package writer;

import asm.A64;
import asm.CodeBuffer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * M0 target: the writer emits a booting {@code kernel8.img} (PLAN.md §4).
 *
 * The image is a park loop:
 * <pre>
 *   0x80000:  wfe          ; wait for event (low-power park)
 *   0x80004:  b   .-4      ; loop back to the wfe
 * </pre>
 * All four Cortex-A72 cores enter here at EL2 and simply park. Nothing prints
 * yet — that is M1 (first light over UART). M0 proves the whole all-Java
 * pipeline end to end: our A64 encoder produces the words, our writer lays them
 * out and emits the raw image, and the Pi runs it. No C, no external assembler,
 * no linker anywhere in the loop.
 *
 * Usage: {@code java writer.BuildSpinImage [output-path]} (default kernel8.img).
 */
public final class BuildSpinImage {

    public static CodeBuffer buildSpinLoop() {
        CodeBuffer code = new CodeBuffer();          // links at 0x80000
        int wfe = code.emit(A64.wfe());              // 0x80000: wfe
        code.emit(A64.b((int) (code.pcAt(wfe) - code.here()))); // 0x80004: b .-4 -> wfe
        return code;
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "kernel8.img");

        CodeBuffer code = buildSpinLoop();
        BootImageWriter writer = new BootImageWriter(code);
        writer.writeImage(out);

        System.out.print(writer.layoutDump());
        System.out.printf("wrote %s%n", out.toAbsolutePath());
    }
}
