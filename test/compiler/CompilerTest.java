package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import harness.T;
import writer.BuildCompiledSpinImage;
import writer.BuildRuntimeImage;

import java.nio.charset.StandardCharsets;
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

        // ---- object fields: getfield/putfield at offset 16 (header=16) ----
        ClassFile ff = ClassFile.parse(classesDir.resolve("compiler/FieldFixture.class"));

        List<Integer> getWant = new ArrayList<>();
        getWant.add(A64.subImm(31, 31, 16));
        getWant.add(A64.strx(19, 31, 0));
        getWant.add(A64.movReg(19, 0));                  // this-less: static param f -> slot0
        getWant.add(A64.movReg(9, 19));                  // aload_0
        getWant.add(A64.ldrx(9, 9, 16));                 // getfield value @16
        getWant.add(A64.movReg(0, 9));                   // ireturn
        getWant.add(A64.ldrx(19, 31, 0));
        getWant.add(A64.addImm(31, 31, 16));
        getWant.add(A64.ret());
        T.eqWords("FieldFixture.get", toArray(getWant), compile(ff, "get", "(Lcompiler/FieldFixture;)I"));

        List<Integer> setWant = new ArrayList<>();
        setWant.add(A64.subImm(31, 31, 16));
        setWant.add(A64.strx(19, 31, 0));
        setWant.add(A64.strx(20, 31, 8));
        setWant.add(A64.movReg(19, 0));                  // f -> slot0
        setWant.add(A64.movReg(20, 1));                  // v -> slot1
        setWant.add(A64.movReg(9, 19));                  // aload_0
        setWant.add(A64.movReg(10, 20));                 // iload_1
        setWant.add(A64.strx(10, 9, 16));                // putfield value @16
        setWant.add(A64.ldrx(19, 31, 0));
        setWant.add(A64.ldrx(20, 31, 8));
        setWant.add(A64.addImm(31, 31, 16));
        setWant.add(A64.ret());
        T.eqWords("FieldFixture.set", toArray(setWant), compile(ff, "set", "(Lcompiler/FieldFixture;I)V"));

        // ---- invokevirtual: dispatch through the TIB vtable (val() = slot 0) ----
        // frame: LR + 1 local + 7-slot spill area = align16(72) = 80
        List<Integer> vWant = new ArrayList<>();
        vWant.add(A64.subImm(31, 31, 80));
        vWant.add(A64.strx(30, 31, 0));                  // save LR (non-leaf)
        vWant.add(A64.strx(19, 31, 8));
        vWant.add(A64.movReg(19, 0));                    // f -> slot0
        vWant.add(A64.movReg(9, 19));                    // aload_0
        vWant.add(A64.movReg(0, 9));                     // receiver -> x0
        vWant.add(A64.ldrx(16, 0, 0));                   // x16 = receiver.tib
        vWant.add(A64.ldrx(16, 16, 8));                  // x16 = tib[vmethod 0]  (offset 8)
        vWant.add(A64.blr(16));                          // dispatch
        vWant.add(A64.movReg(9, 0));                     // result -> stack
        vWant.add(A64.movReg(0, 9));                     // ireturn
        vWant.add(A64.ldrx(30, 31, 0));
        vWant.add(A64.ldrx(19, 31, 8));
        vWant.add(A64.addImm(31, 31, 80));
        vWant.add(A64.ret());
        T.eqWords("FieldFixture.callVal", toArray(vWant), compile(ff, "callVal", "(Lcompiler/FieldFixture;)I"));

        // ---- arrays: baload (base+index<<0) and arraylength (@16) ----
        List<Integer> elemWant = new ArrayList<>();
        elemWant.add(A64.subImm(31, 31, 16));
        elemWant.add(A64.strx(19, 31, 0));
        elemWant.add(A64.movReg(19, 0));                 // a -> slot0
        elemWant.add(A64.movReg(9, 19));                 // aload_0
        elemWant.addAll(A64.loadImm64(10, 0));           // iconst_0 (index)
        elemWant.add(A64.addImm(9, 9, 24));              // &elem0
        elemWant.add(A64.addRegLsl(9, 9, 10, 0));        // &elem[index]
        elemWant.add(A64.ldrb(9, 9, 0));                 // baload
        elemWant.add(A64.movReg(0, 9));                  // ireturn
        elemWant.add(A64.ldrx(19, 31, 0));
        elemWant.add(A64.addImm(31, 31, 16));
        elemWant.add(A64.ret());
        T.eqWords("arrElem0(byte[])", toArray(elemWant), compile(fx, "arrElem0", "([B)I"));

        List<Integer> lenWant = new ArrayList<>();
        lenWant.add(A64.subImm(31, 31, 16));
        lenWant.add(A64.strx(19, 31, 0));
        lenWant.add(A64.movReg(19, 0));
        lenWant.add(A64.movReg(9, 19));                  // aload_0
        lenWant.add(A64.ldrx(9, 9, 16));                 // arraylength @16
        lenWant.add(A64.movReg(0, 9));                   // ireturn
        lenWant.add(A64.ldrx(19, 31, 0));
        lenWant.add(A64.addImm(31, 31, 16));
        lenWant.add(A64.ret());
        T.eqWords("arrLen(int[])", toArray(lenWant), compile(fx, "arrLen", "([I)I"));

        // ---- string literals: interned as a byte[] object laid out in the image ----
        String img = new String(BuildRuntimeImage.build(classesDir).toBytes(), StandardCharsets.US_ASCII);
        T.eq("interned 'hello from joe2' in image", 1, img.contains("hello from joe2") ? 1 : 0);

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
