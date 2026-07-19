package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import harness.T;
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

    public static void main(String[] args) throws Exception {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "out");

        // ---- spin() must equal the M0 hand-emitted spin loop ----
        int[] spin = BuildCompiledSpinImage.compileSpin(classesDir).toWords();
        T.eqWords("spin()", new int[]{ A64.wfe(), A64.b(-4) }, spin);

        // ---- constant-arg intrinsics lower exactly ----
        ClassFile fx = ClassFile.parse(classesDir.resolve("compiler/Fixtures.class"));

        // operand stack maps to x9 (addr), x10 (value)
        List<Integer> pokeWant = new ArrayList<>();
        pokeWant.addAll(A64.loadImm64(9, 0xFE215004L));  // address -> x9
        pokeWant.addAll(A64.loadImm64(10, 1L));          // value   -> x10
        pokeWant.add(A64.strw(10, 9, 0));                // STR w10,[x9]
        pokeWant.add(A64.ret());
        T.eqWords("pokeWord()", toArray(pokeWant), compile(fx, "pokeWord"));

        List<Integer> regWant = new ArrayList<>();
        regWant.addAll(A64.loadImm64(9, 0x80000000L));   // value -> x9
        regWant.add(A64.msr(A64.HCR_EL2, 9));            // MSR HCR_EL2, x9
        regWant.add(A64.ret());
        T.eqWords("writeReg()", toArray(regWant), compile(fx, "writeReg", "()V"));

        // ---- calling convention: frame prologue/epilogue + return value ----
        List<Integer> addWant = new ArrayList<>();
        addWant.add(A64.subImm(31, 31, 16));             // sub sp,sp,#16
        addWant.add(A64.strx(19, 31, 0));                // str x19,[sp]  (save callee-saved local)
        addWant.add(A64.movReg(19, 0));                  // mov x19,x0    (param x -> local slot0)
        addWant.add(A64.movReg(9, 19));                  // iload_0
        addWant.addAll(A64.loadImm64(10, 1));            // iconst_1
        addWant.add(A64.addReg(9, 9, 10));               // iadd
        addWant.add(A64.movReg(0, 9));                   // ireturn: result -> x0
        addWant.add(A64.ldrx(19, 31, 0));                // restore x19
        addWant.add(A64.addImm(31, 31, 16));             // add sp,sp,#16
        addWant.add(A64.ret());
        T.eqWords("addOne(int)", toArray(addWant), compile(fx, "addOne", "(I)I"));

        T.summary("compiler");
    }

    private static int[] compile(ClassFile cf, String method) { return compile(cf, method, "()V"); }

    private static int[] compile(ClassFile cf, String method, String desc) {
        CodeBuffer cb = new CodeBuffer();
        new BaselineCompiler(cf).compile(cf.method(method, desc), cb);
        return cb.toWords();
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
