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
public final class CompilerTest
{

    public static void main(String[] args) throws Exception
    {
        Path classesDir = Path.of(args.length > 0 ? args[0] : "out");

        // ---- spin() must equal the M0 hand-emitted spin loop ----
        int[] spin = BuildCompiledSpinImage.compileSpin(classesDir).toWords();
        T.eqWords("spin()", new int[] { A64.wfe(), A64.b(-4) }, spin);

        // ---- constant-arg intrinsics lower exactly ----
        ClassFile fx = ClassFile.parse(classesDir.resolve("compiler/Fixtures.class"));

        // operand stack maps to x9 (addr), x10 (value)
        List<Integer> pokeWant = new ArrayList<>();
        addAll(pokeWant, A64.loadImm64(9, 0xFE215004L));  // address -> x9
        addAll(pokeWant, A64.loadImm64(10, 1L));          // value   -> x10
        pokeWant.add(A64.strw(10, 9, 0));                // STR w10,[x9]
        pokeWant.add(A64.ret());
        T.eqWords("pokeWord()", toArray(pokeWant), compile(fx, "pokeWord"));

        List<Integer> regWant = new ArrayList<>();
        addAll(regWant, A64.loadImm64(9, 0x80000000L));   // value -> x9
        regWant.add(A64.msr(A64.HCR_EL2, 9));            // MSR HCR_EL2, x9
        regWant.add(A64.ret());
        T.eqWords("writeReg()", toArray(regWant), compile(fx, "writeReg", "()V"));

        // ---- calling convention: frame prologue/epilogue + return value ----
        List<Integer> addWant = new ArrayList<>();
        addWant.add(A64.subImm(31, 31, 16));             // sub sp,sp,#16
        addWant.add(A64.strx(19, 31, 0));                // str x19,[sp]  (save callee-saved local)
        addWant.add(A64.movReg(19, 0));                  // mov x19,x0    (param x -> local slot0)
        addWant.add(A64.movReg(9, 19));                  // iload_0
        addAll(addWant, A64.loadImm64(10, 1));            // iconst_1
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

        // ---- new with a live operand stack ----------------------------------
        // `x + new FieldFixture().value` pushes x, then allocates. Heap.alloc
        // clobbers the operand registers, so x must survive across the call. This
        // shape was rejected outright before (PLAN.md §M5.1). Match on encodings
        // rather than a fixed offset, so the check doesn't pin the frame layout.
        // (compileMethod, not compile(): `new` records a call site + TIB ref)
        int[] nl = new BaselineCompiler(ff)
        .compileMethod(ff.method("newWithLiveStack", "(I)I"), CodeBuffer.LOAD_ADDRESS, false).words();
        int bl = -1;
        boolean spilled = false;
        boolean reloaded = false;
        for (int i = 0; i < nl.length; i++)
        {
            boolean strX9Sp = (nl[i] & 0xFFC003FF) == (0xF9000000 | (31 << 5) | 9);
            boolean ldrX9Sp = (nl[i] & 0xFFC003FF) == (0xF9400000 | (31 << 5) | 9);
            if (bl < 0 && (nl[i] & 0xFC000000) == 0x94000000)
            {
                bl = i;                                  // the BL to Heap.alloc
            }
            spilled |= strX9Sp && bl < 0;                // stored before the call
            reloaded |= ldrX9Sp && bl >= 0;              // restored after it
        }
        T.check("newWithLiveStack compiles", nl.length > 0);
        T.check("live operand spilled before Heap.alloc", spilled);
        T.check("live operand reloaded after Heap.alloc", reloaded);

        // ---- more locals than callee-saved registers -------------------------
        // Slots 0..9 stay in x19..x28; the rest live in the frame. Previously this
        // was rejected with "local slot out of range".
        int[] ml = compile(fx, "manyLocals", "(I)I");
        int overflowStores = 0;
        int overflowLoads = 0;
        for (int w : ml)
        {
            // str/ldr Xt,[SP,#imm] where Xt is an operand register (x9..x15):
            // the frame traffic for locals that didn't fit in registers.
            int rt = w & 0x1F;
            boolean sp = ((w >> 5) & 0x1F) == 31;
            if (sp && rt >= 9 && rt <= 15)
            {
                if ((w & 0xFFC00000) == 0xF9000000)
                {
                    overflowStores++;
                }
                if ((w & 0xFFC00000) == 0xF9400000)
                {
                    overflowLoads++;
                }
            }
        }
        // ---- char[] elements: 2-byte access via LDRH/STRH ---------------------
        int[] ce = compile(fx, "charElem", "([C)I");
        boolean sawLdrh = false;
        boolean sawStrh = false;
        for (int w : ce)
        {
            sawLdrh |= (w & 0xFFC00000) == 0x79400000;
            sawStrh |= (w & 0xFFC00000) == 0x79000000;
        }
        T.check("charElem uses LDRH (caload)", sawLdrh);
        T.check("charElem uses STRH (castore)", sawStrh);

        // ---- anewarray: reference array allocates like long[] (elemSize 8) ----
        // (compileMethod, not compile(): the Heap.allocArray helper records a call site)
        int[] ana = new BaselineCompiler(fx)
        .compileMethod(fx.method("makeRefs", "(I)[Ljava/lang/Object;"), CodeBuffer.LOAD_ADDRESS, false).words();
        boolean sawSize8 = false;
        boolean sawBl = false;
        for (int w : ana)
        {
            sawSize8 |= (w & 0xFFFFFFE0) == (0xD2800000 | (8 << 5));   // MOVZ x?,#8 (elemSize)
            sawBl |= (w & 0xFC000000) == 0x94000000;                  // BL to Heap.allocArray
        }
        T.check("makeRefs (anewarray) compiles", ana.length > 0);
        T.check("anewarray pushes element size 8", sawSize8);
        T.check("anewarray calls the array allocator", sawBl);

        T.check("manyLocals compiles (>10 locals)", ml.length > 0);
        T.check("overflow locals are stored to the frame", overflowStores > 0);
        T.check("overflow locals are loaded from the frame", overflowLoads > 0);

        // ---- arrays: baload (base+index<<0) and arraylength (@16) ----
        List<Integer> elemWant = new ArrayList<>();
        elemWant.add(A64.subImm(31, 31, 16));
        elemWant.add(A64.strx(19, 31, 0));
        elemWant.add(A64.movReg(19, 0));                 // a -> slot0
        elemWant.add(A64.movReg(9, 19));                 // aload_0
        addAll(elemWant, A64.loadImm64(10, 0));           // iconst_0 (index)
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

        // ---- ternary: value survives the branch merge (same register both paths) ----
        List<Integer> ternWant = new ArrayList<>();
        ternWant.add(A64.subImm(31, 31, 16));
        ternWant.add(A64.strx(19, 31, 0));
        ternWant.add(A64.movReg(19, 0));                 // x -> slot0
        ternWant.add(A64.movReg(9, 19));                 // iload_0
        ternWant.add(A64.cbz(9, 12));                    // ifeq -> else
        addAll(ternWant, A64.loadImm64(9, 0x41));         // bipush 0x41
        ternWant.add(A64.b(8));                          // goto end
        addAll(ternWant, A64.loadImm64(9, 0x42));         // else: bipush 0x42 (same reg x9)
        ternWant.add(A64.movReg(0, 9));                  // ireturn
        ternWant.add(A64.ldrx(19, 31, 0));
        ternWant.add(A64.addImm(31, 31, 16));
        ternWant.add(A64.ret());
        T.eqWords("tern(int)", toArray(ternWant), compile(fx, "tern", "(I)I"));

        // ---- static field: reserved address load + ldr from the statics area ----
        ClassFile counter = ClassFile.parse(classesDir.resolve("vm/Counter.class"));
        int[] getStatic = new BaselineCompiler(counter)
        .compileMethod(counter.method("get", "()I"), CodeBuffer.LOAD_ADDRESS, false).words();
        int[] getStaticWant =
        {
            A64.movz(9, 0, 0), A64.movk(9, 0, 1),   // reserved &Counter.count (patched by writer)
            A64.ldrx(9, 9, 0),                       // getstatic: value = *addr
            A64.movReg(0, 9), A64.ret(),             // ireturn (leaf, no frame)
        };
        T.eqWords("Counter.get (getstatic)", getStaticWant, getStatic);

        // ---- class hierarchy: flattened vtable (override in place, shared slot) ----
        java.util.function.Function<String, ClassFile> res = c ->
        {
            try
            {
                return ClassFile.parse(classesDir.resolve(c + ".class"));
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        };
        T.eq("Animal vtable size", 1, ClassFile.vtable("vm/Animal", res).size());
        T.eq("Dog vtable size (override, not append)", 1, ClassFile.vtable("vm/Dog", res).size());
        T.eq("Animal.sound slot", 0, ClassFile.vtableSlot("vm/Animal", "sound", "()I", res));
        T.eq("Dog.sound shares slot 0", 0, ClassFile.vtableSlot("vm/Dog", "sound", "()I", res));
        T.eq("Dog slot 0 impl is Dog", 1, ClassFile.vtable("vm/Dog", res).get(0).owner().equals("vm/Dog") ? 1 : 0);

        // ---- interfaces: implements set, itable slot, resolved implementation ----
        T.eq("Robot implements Speaker", 1, ClassFile.allInterfaces("vm/Robot", res).contains("vm/Speaker") ? 1 : 0);
        T.eq("Speaker.speak itable slot", 0, res.apply("vm/Speaker").interfaceSlot("speak", "()I"));
        T.eq("Phone.speak impl is Phone", 1, ClassFile.findImpl("vm/Phone", "speak", "()I", res).equals("vm/Phone") ? 1 : 0);

        // ---- exceptions: the try/catch table is parsed with its catch type ----
        ClassFile vmcf = ClassFile.parse(classesDir.resolve("vm/VM.class"));
        ClassFile.Method runM = vmcf.method("run", "()V");
        T.eq("run() has a try/catch entry", 1, runM.exceptions.length >= 1 ? 1 : 0);
        T.eq("catch type is MyExc", 1, vmcf.classAt(runM.exceptions[0].catchType()).equals("vm/MyExc") ? 1 : 0);

        // ---- string literals: interned as a byte[] object laid out in the image ----
        String img = new String(BuildRuntimeImage.build(classesDir).toBytes(), StandardCharsets.US_ASCII);
        T.eq("interned 'hello from joe-ng' in image", 1, img.contains("hello from joe-ng") ? 1 : 0);

        T.summary("compiler");
    }

    private static int[] compile(ClassFile cf, String method)
    {
        return compile(cf, method, "()V");
    }

    private static int[] compile(ClassFile cf, String method, String desc)
    {
        CodeBuffer cb = new CodeBuffer();
        new BaselineCompiler(cf).compile(cf.method(method, desc), cb);
        return cb.toWords();
    }

    private static int[] toArray(List<Integer> l)
    {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++)
        {
            a[i] = l.get(i);
        }
        return a;
    }

    /** Append each word of {@code ws} to {@code l} (loadImm64 now returns int[]). */
    private static void addAll(List<Integer> l, int[] ws)
    {
        for (int w : ws)
        {
            l.add(w);
        }
    }
}
