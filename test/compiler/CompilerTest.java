package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import writer.BuildCompiledSpinImage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end check of the metacircular compile path: parse vm/VM.class, compile
 * spin() from real bytecode, and assert the result is bit-identical to the M0
 * hand-emitted spin loop ({@code wfe; b .-4}). If these match, the classfile
 * parser + baseline compiler agree with the assembler we already trust.
 *
 * Run: {@code java compiler.CompilerTest <classesDir>}
 */
public final class CompilerTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "out");

        // ---- spin() must equal the M0 hand-emitted spin loop ----
        int[] spin = BuildCompiledSpinImage.compileSpin(classesDir).toWords();
        expect("spin()", new int[]{ A64.wfe(), A64.b(-4) }, spin);

        // ---- constant-arg intrinsics lower exactly ----
        ClassFile fx = ClassFile.parse(classesDir.resolve("compiler/Fixtures.class"));

        // operand stack maps to x9 (addr), x10 (value)
        List<Integer> pokeWant = new ArrayList<>();
        pokeWant.addAll(A64.loadImm64(9, 0xFE215004L));  // address -> x9
        pokeWant.addAll(A64.loadImm64(10, 1L));          // value   -> x10
        pokeWant.add(A64.strw(10, 9, 0));                // STR w10,[x9]
        pokeWant.add(A64.ret());
        expect("pokeWord()", toArray(pokeWant), compile(fx, "pokeWord"));

        List<Integer> regWant = new ArrayList<>();
        regWant.addAll(A64.loadImm64(9, 0x80000000L));   // value -> x9
        regWant.add(A64.msr(A64.HCR_EL2, 9));            // MSR HCR_EL2, x9
        regWant.add(A64.ret());
        expect("writeReg()", toArray(regWant), compile(fx, "writeReg"));

        System.out.printf("%n%s%n", failures == 0 ? "all compiler checks passed" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    private static int[] compile(ClassFile cf, String method) {
        CodeBuffer cb = new CodeBuffer();
        new BaselineCompiler(cf).compile(cf.method(method, "()V"), cb);
        return cb.toWords();
    }

    private static void expect(String name, int[] want, int[] got) {
        if (java.util.Arrays.equals(want, got)) {
            System.out.printf("PASS %-12s %s%n", name, hex(got));
        } else {
            failures++;
            System.out.printf("FAIL %-12s%n  want %s%n  got  %s%n", name, hex(want), hex(got));
        }
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static String hex(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i > 0 ? ", " : "").append(String.format("0x%08X", a[i]));
        return sb.append("]").toString();
    }
}
