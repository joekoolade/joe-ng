/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-18
 */
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
        // Memory-based frame (frames/GC arc S1): every local has a frame slot; no callee-saved register is touched.
        List<Integer> addWant = new ArrayList<>();
        addWant.add(A64.subImm(31, 31, 16));             // sub sp,sp,#16
        addWant.add(A64.strx(0, 31, 0));                 // str x0,[sp]   (param x -> local slot0's frame slot)
        addWant.add(A64.ldrx(9, 31, 0));                 // iload_0
        addAll(addWant, A64.loadImm64(10, 1));            // iconst_1
        addWant.add(A64.addReg(9, 9, 10));               // iadd
        addWant.add(A64.sxtw(9, 9));                     // ... canonicalised: an int stays sign-extended in
                                                         //     its 64-bit register even when it overflows,
                                                         //     which is what idiv/irem read (Baseline.canonInt)
        addWant.add(A64.movReg(0, 9));                   // ireturn: result -> x0
        addWant.add(A64.addImm(31, 31, 16));             // add sp,sp,#16
        addWant.add(A64.ret());
        T.eqWords("addOne(int)", toArray(addWant), compile(fx, "addOne", "(I)I"));

        // ---- object fields: getfield/putfield at offset 16 (header=16) ----
        ClassFile ff = ClassFile.parse(classesDir.resolve("compiler/FieldFixture.class"));

        List<Integer> getWant = new ArrayList<>();
        getWant.add(A64.subImm(31, 31, 16));
        getWant.add(A64.strx(0, 31, 0));                 // this-less: static param f -> slot0
        getWant.add(A64.ldrx(9, 31, 0));                 // aload_0
        getWant.add(A64.ldrx(9, 9, 16));                 // getfield value @16
        getWant.add(A64.movReg(0, 9));                   // ireturn
        getWant.add(A64.addImm(31, 31, 16));
        getWant.add(A64.ret());
        T.eqWords("FieldFixture.get", toArray(getWant), compile(ff, "get", "(Lcompiler/FieldFixture;)I"));

        List<Integer> setWant = new ArrayList<>();
        setWant.add(A64.subImm(31, 31, 16));
        setWant.add(A64.strx(0, 31, 0));                 // f -> slot0
        setWant.add(A64.strx(1, 31, 8));                 // v -> slot1
        setWant.add(A64.ldrx(9, 31, 0));                 // aload_0
        setWant.add(A64.ldrx(10, 31, 8));                // iload_1
        setWant.add(A64.strx(10, 9, 16));                // putfield value @16
        setWant.add(A64.addImm(31, 31, 16));
        setWant.add(A64.ret());
        T.eqWords("FieldFixture.set", toArray(setWant), compile(ff, "set", "(Lcompiler/FieldFixture;I)V"));

        // ---- invokevirtual: dispatch through the TIB vtable (val() = slot 0) ----
        // frame: LR + 1 local + 7-slot spill area = align16(72) = 80
        List<Integer> vWant = new ArrayList<>();
        vWant.add(A64.subImm(31, 31, 80));
        vWant.add(A64.strx(30, 31, 0));                  // save LR (non-leaf)
        vWant.add(A64.strx(0, 31, 8));                   // f -> slot0
        vWant.add(A64.ldrx(9, 31, 8));                   // aload_0
        vWant.add(A64.movReg(0, 9));                     // receiver -> x0
        vWant.add(A64.ldrx(16, 0, 0));                   // x16 = receiver.tib
        vWant.add(A64.ldrx(16, 16, 8));                  // x16 = tib[vmethod 0]  (offset 8)
        vWant.add(A64.blr(16));                          // dispatch
        vWant.add(A64.movReg(9, 0));                     // result -> stack
        vWant.add(A64.movReg(0, 9));                     // ireturn
        vWant.add(A64.ldrx(30, 31, 0));
        vWant.add(A64.addImm(31, 31, 80));
        vWant.add(A64.ret());
        T.eqWords("FieldFixture.callVal", toArray(vWant), compile(ff, "callVal", "(Lcompiler/FieldFixture;)I"));
        // Its reference map (frames/GC arc S2): one call site, returning to word 8 (after the blr at 7); local 0
        // (the FieldFixture argument) is a reference; the operand stack entering the invoke is 1 deep (the
        // receiver, popped into x0 before the spill), so no operand slot is live across the call.
        // Header: magic, sites, locals, operand slots, locals base, operand base, bit words, unique maps.
        T.eqWords("FieldFixture.callVal map", new int[] {0x50414D47, 1, 1, 7, 8, 16, 1, 1, 8, 0x01000000, 1},
                gcMap(ff, "callVal", "(Lcompiler/FieldFixture;)I"));
        T.check("a leaf has no map", gcMap(ff, "get", "(Lcompiler/FieldFixture;)I").length == 0);

        // ---- reference maps: the dataflow's merges, operands and unboxed words (compiler.RefMaps) ----
        ClassFile mf = ClassFile.parse(classesDir.resolve("compiler/MapFixture.class"));
        int[] jm = gcMap(mf, "join", "(ZLjava/lang/Object;)V");
        T.check("join: three sites", jm.length > 1 && jm[1] == 3);
        T.check("join site 0: slot 2 (t) is a reference", mapSlot(jm, 0, 2) == 1);
        T.check("join site 1: slot 2 (t) is an int", mapSlot(jm, 1, 2) == 2);
        T.check("join site 2: slot 2 merged REF/int -> dead", mapSlot(jm, 2, 2) == 0);
        T.check("join site 2: slot 1 (o) is a reference", mapSlot(jm, 2, 1) == 1);
        T.check("join site 2: slot 0 (c) is an int", mapSlot(jm, 2, 0) == 2);
        int[] lm = gcMap(mf, "live", "(Ljava/lang/Object;)Ljava/lang/Object;");
        T.check("live site 0: one operand live across the call", mapLive(lm, 0) == 1);
        T.check("live site 0: that operand is a reference", mapSlot(lm, 0, lm[2] + 0) == 1);
        T.check("live site 1: no operand live", mapLive(lm, 1) == 0);
        int[] rm = gcMap(mf, "raw", "(Ljava/lang/Object;J)V");
        T.check("raw site 0: slot 0 (o) is a reference", mapSlot(rm, 0, 0) == 1);
        T.check("raw site 0: slot 1 (n, a long) is raw", mapSlot(rm, 0, 1) == 2);
        T.check("raw site 0: slot 2 (n's second half) is dead", mapSlot(rm, 0, 2) == 0);
        T.check("raw site 0: slot 3 (an Address) is raw", mapSlot(rm, 0, 3) == 2);
        T.check("raw site 0: slot 4 (m, a long) is raw", mapSlot(rm, 0, 4) == 2);

        // Yieldpoints (frames/GC arc S3): a prologue poll in a framed method, one per loop header, none in a
        // magic.Uninterruptible class. A poll is `ldr x16, [x16]` on the request word, then a cbz over the call.
        int[] lp = polled(mf, "loop", "(Ljava/lang/Object;I)V");
        T.check("loop: two yieldpoints (prologue + loop header)", polls(lp) == 2);
        int ldrLr = asm.A64Enc.ldrx(30, 31, 0);
        T.check("loop: the prologue poll reloads x30 for the body (readLR as a first op)",
                count(lp, ldrLr) == count(new BaselineCompiler(mf).compileMethod(mf.method("loop", "(Ljava/lang/Object;I)V"),
                        CodeBuffer.LOAD_ADDRESS, false).words(), ldrLr) + 1);
        T.check("loop: ... and both argument registers", count(lp, asm.A64Enc.ldrx(0, 31, 8)) >= 1
                && count(lp, asm.A64Enc.ldrx(1, 31, 16)) >= 1);
        int[] lpm = polledMap(mf, "loop", "(Ljava/lang/Object;I)V");
        T.check("loop: three map sites (two polls + the call)", lpm.length > 1 && lpm[1] == 3);
        T.check("loop site 0 (prologue poll): slot 0 (o) is a reference", mapSlot(lpm, 0, 0) == 1);
        T.check("loop site 0 (prologue poll): no operand live", mapLive(lpm, 0) == 0);
        T.check("loop site 1 (loop poll): slot 2 (i) is an int", mapSlot(lpm, 1, 2) == 2);
        T.check("count: two yieldpoints (framed by its loop poll, so a prologue poll too)",
                polls(polled(mf, "count", "(I)I")) == 2);
        T.check("count: framed by its poll, so it has a map", polledMap(mf, "count", "(I)I").length > 0);
        T.check("count: a leaf without yieldpoints", gcMap(mf, "count", "(I)I").length == 0);
        T.check("count: no polls unless asked", polls(compile(mf, "count", "(I)I")) == 0);
        T.check("UNINTERRUPTIBLE_HASH is magic/Uninterruptible's",
                Baseline.UNINTERRUPTIBLE_HASH == utf8HashOf("magic/Uninterruptible"));
        ClassFile np = ClassFile.parse(classesDir.resolve("compiler/NoPollFixture.class"));
        T.check("Uninterruptible loop: no yieldpoints", polls(polled(np, "loop", "(Ljava/lang/Object;I)V")) == 0);
        T.check("Uninterruptible count: no yieldpoints", polls(polled(np, "count", "(I)I")) == 0);
        T.check("Uninterruptible count: still a leaf", polledMap(np, "count", "(I)I").length == 0);

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
        elemWant.add(A64.strx(0, 31, 0));                // a -> slot0
        elemWant.add(A64.ldrx(9, 31, 0));                // aload_0
        addAll(elemWant, A64.loadImm64(10, 0));           // iconst_0 (index)
        elemWant.add(A64.addImm(9, 9, 24));              // &elem0
        elemWant.add(A64.addRegLsl(9, 9, 10, 0));        // &elem[index]
        elemWant.add(asm.A64Enc.ldrsb(9, 9, 0));        // baload (SIGN-extends: JVM byte semantics)
        elemWant.add(A64.movReg(0, 9));                  // ireturn
        elemWant.add(A64.addImm(31, 31, 16));
        elemWant.add(A64.ret());
        T.eqWords("arrElem0(byte[])", toArray(elemWant), compile(fx, "arrElem0", "([B)I"));

        List<Integer> lenWant = new ArrayList<>();
        lenWant.add(A64.subImm(31, 31, 16));
        lenWant.add(A64.strx(0, 31, 0));                 // a -> slot0
        lenWant.add(A64.ldrx(9, 31, 0));                 // aload_0
        lenWant.add(A64.ldrx(9, 9, 16));                 // arraylength @16
        lenWant.add(A64.movReg(0, 9));                   // ireturn
        lenWant.add(A64.addImm(31, 31, 16));
        lenWant.add(A64.ret());
        T.eqWords("arrLen(int[])", toArray(lenWant), compile(fx, "arrLen", "([I)I"));

        // ---- irem: SDIV + MSUB, with the original dividend stashed in SCRATCH (x16) ----
        List<Integer> remWant = new ArrayList<>();
        remWant.add(A64.subImm(31, 31, 16));
        remWant.add(A64.strx(0, 31, 0));                 // a -> slot0
        remWant.add(A64.strx(1, 31, 8));                 // b -> slot1
        remWant.add(A64.ldrx(9, 31, 0));                 // iload_0 (a)
        remWant.add(A64.ldrx(10, 31, 8));                // iload_1 (b)
        remWant.add(A64.movReg(16, 9));                  // SCRATCH = a
        remWant.add(A64.sdivReg(9, 16, 10));             // r = a / b
        remWant.add(A64.msub(9, 9, 10, 16));             // r = a - (a/b)*b
        remWant.add(A64.movReg(0, 9));                   // ireturn
        remWant.add(A64.addImm(31, 31, 16));
        remWant.add(A64.ret());
        T.eqWords("rem(int,int)", toArray(remWant), compile(fx, "rem", "(II)I"));

        // ---- dup2: a[i] |= 1 duplicates the ref (x9) + index (x10) into x11,x12 ----
        int[] orInto = compile(fx, "orInto", "([II)V");
        T.eq("orInto dup2 duplicates ref",   1, contains(orInto, A64.movReg(11, 9)) ? 1 : 0);
        T.eq("orInto dup2 duplicates index", 1, contains(orInto, A64.movReg(12, 10)) ? 1 : 0);

        // ---- ternary: value survives the branch merge (same register both paths) ----
        List<Integer> ternWant = new ArrayList<>();
        ternWant.add(A64.subImm(31, 31, 16));
        ternWant.add(A64.strx(0, 31, 0));                // x -> slot0
        ternWant.add(A64.ldrx(9, 31, 0));                // iload_0
        ternWant.add(A64.cbz(9, 12));                    // ifeq -> else
        addAll(ternWant, A64.loadImm64(9, 0x41));         // bipush 0x41
        ternWant.add(A64.b(8));                          // goto end
        addAll(ternWant, A64.loadImm64(9, 0x42));         // else: bipush 0x42 (same reg x9)
        ternWant.add(A64.movReg(0, 9));                  // ireturn
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
        ClassFile.Resolver res = c ->
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
        // M8 world unification: the whole registered super chain flattens -- guest java/lang/Object's
        // virtuals prefix every vtable (matching the on-metal loader's numbering), so Animal's own
        // sound() lands at the first slot after them and Dog overrides it in place. Object's count is READ,
        // not written down: it was 9 for the hand-written overlay and is 12 for the JDK 26 class (finalize,
        // wait(long, int) and the private wait0 joined), and the hard-coded 9 went stale silently.
        int objN = ClassFile.vtable("java/lang/Object", res).size();
        T.eq("Animal vtable size", objN + 1, ClassFile.vtable("vm/Animal", res).size());
        T.eq("Dog vtable size (override, not append)", objN + 1, ClassFile.vtable("vm/Dog", res).size());
        T.eq("Animal.sound slot", objN, ClassFile.vtableSlot("vm/Animal", "sound", "()I", res));
        T.eq("Dog.sound shares Animal's slot", objN, ClassFile.vtableSlot("vm/Dog", "sound", "()I", res));
        T.eq("Dog's sound slot impl is Dog", 1, ClassFile.vtable("vm/Dog", res).get(objN).owner().equals("vm/Dog") ? 1 : 0);

        // ---- interfaces: implements set, itable slot, resolved implementation ----
        T.eq("Robot implements Speaker", 1, ClassFile.allInterfaces("vm/Robot", res).contains("vm/Speaker") ? 1 : 0);
        T.eq("Speaker.speak itable slot", 0, ClassFile.interfaceSlot("vm/Speaker", "speak", "()I", res));
        T.eq("Phone.speak impl is Phone", 1, ClassFile.findImpl("vm/Phone", "speak", "()I", res).equals("vm/Phone") ? 1 : 0);

        // ---- exceptions: the try/catch table is parsed with its catch type ----
        ClassFile vmcf = ClassFile.parse(classesDir.resolve("vm/VM.class"));
        ClassFile.Method runM = vmcf.method("run", "()V");
        T.eq("run() has a try/catch entry", 1, runM.exceptions.length >= 1 ? 1 : 0);
        T.eq("catch type is MyExc", 1, vmcf.classAt(runM.exceptions[0].catchType()).equals("vm/MyExc") ? 1 : 0);

        // ---- regression: a long[]/double[] PARAM is a 1-slot reference (not category-2) ----
        // afterLongArray(long[] a, int n): a -> slot0, n -> slot1, each in its frame slot (8 bytes apart). If [J were
        // miscounted as 2 slots, n would land in slot 2's home and the body's iload_1 would read garbage. This was
        // the #43 loadGuest hang.
        int[] ala = compile(fx, "afterLongArray", "([JI)I");
        int aOff = -1;
        for (int w : ala)
        {
            for (int o = 0; o < 64 && aOff < 0; o += 8)
            {
                if (w == A64.strx(0, 31, o)) { aOff = o; }   // str x0, [sp, #o]  (a -> slot0)
            }
        }
        boolean nToSlot1 = aOff >= 0 && contains(ala, A64.strx(1, 31, aOff + 8));    // n -> slot1 (correct)
        boolean nToSlot2 = aOff >= 0 && contains(ala, A64.strx(1, 31, aOff + 16));   // n -> slot2 (the bug)
        T.check("long[] param is 1 slot: int n -> slot1 (not slot2)", nToSlot1 && !nToSlot2);

        // ---- string literals: interned as a byte[] object laid out in the image ----
        String img = new String(BuildRuntimeImage.build(classesDir).toBytes(), StandardCharsets.US_ASCII);
        T.eq("interned 'hello from joe-ng' in image", 1, img.contains("hello from joe-ng") ? 1 : 0);

        // ---- NO METHOD IS BOTH DEEP-STACK AND HANDLER-BEARING ----
        // `OP_MAX = 7` (Baseline): past it the operand stack moves out of registers into FRAME MEMORY, and
        // a method's codegen MODE changes. A deep-stack HANDLER is where this VM has already been bitten --
        // the caught exception read from a spill slot nothing had written -- and it bit again here: adding
        // two timestamp LOCALS to `Loader.lazyCompile` took it from stack=4 to stack=8, which on the Pi
        // wedged the launcher at batch 2 while the SAME binary ran to batch 209 on QEMU. QEMU could not see
        // it and neither could reading the diff; the shape is visible in the bytecode, so check it there.
        //
        // The bound is measured, not guessed: `vm/Loader` has 599 compiled methods of which 52 are legally
        // deep, and NONE of those carries an exception handler. The pairing is what is forbidden, not depth.
        // NEGATIVE CONTROL (run before this shipped): against the failing commit this reports exactly one
        // violation, `lazyCompile(I)J stack=8`.
        BaselineCompiler.ClassResolver reg = BuildRuntimeImage.classResolver(classesDir);
        assertNoDeepHandlers(reg, "vm/Loader");
        assertNoDeepHandlers(reg, "vm/VMGc");
        // VMNatives JOINED THIS GUARD when the reflection entry points took the loader lock: every one
        // of those 23 wrappers now carries a try/finally, so the pairing this check forbids became
        // reachable in a file it did not cover. They are thin by construction (the deepest is stack=6),
        // and the point of checking is that a future edit which fattens one is caught HERE rather than
        // on a Pi -- which is the whole history of the Loader entry above.
        assertNoDeepHandlers(reg, "vm/VMNatives");

        T.summary("compiler");
    }

    private static int[] compile(ClassFile cf, String method)
    {
        return compile(cf, method, "()V");
    }

    /** The method's instructions -- without the reference map that follows them (see {@link #gcMap}). */
    private static int[] compile(ClassFile cf, String method, String desc)
    {
        CodeBuffer cb = new CodeBuffer();
        new BaselineCompiler(cf).compile(cf.method(method, desc), cb);
        int[] all = cb.toWords();
        int code = new BaselineCompiler(cf).compileMethod(cf.method(method, desc), cb.base(), false).gcMapWord();
        return code == 0 ? all : java.util.Arrays.copyOf(all, code);
    }

    /** The 2-bit type of map slot {@code slot} (locals first, then operands) at site {@code site}: 0 dead,
     *  1 reference, 2 raw, 3 ObjectReference. */
    private static int mapSlot(int[] m, int site, int slot)
    {
        int nSites = m[1];
        int bitWords = m[6];
        int idx = m[8 + site * 2 + 1] & 0xFFFF;
        int bit = 2 * slot;
        return (m[8 + nSites * 2 + idx * bitWords + (bit >> 5)] >>> (bit & 31)) & 3;
    }

    /** How many operand slots site {@code site} holds live across its call. */
    private static int mapLive(int[] m, int site)
    {
        return (m[8 + site * 2 + 1] >>> 16) & 0xFF;
    }

    /** The method's words (instructions and map) compiled WITH yieldpoints, as the image writer does. */
    private static int[] polled(ClassFile cf, String method, String desc)
    {
        return new BaselineCompiler(cf).yieldPolls(true).compileMethod(cf.method(method, desc),
                CodeBuffer.LOAD_ADDRESS, false).words();
    }

    /** The reference map of the method compiled with yieldpoints, or an empty array. */
    private static int[] polledMap(ClassFile cf, String method, String desc)
    {
        BaselineCompiler.CompiledMethod cm = new BaselineCompiler(cf).yieldPolls(true)
                .compileMethod(cf.method(method, desc), CodeBuffer.LOAD_ADDRESS, false);
        int at = cm.gcMapWord();
        return at == 0 ? new int[0] : java.util.Arrays.copyOfRange(cm.words(), at, cm.words().length);
    }

    /** {@link classfile.ClassReader#utf8Hash} of an ASCII name, laid out as a class-file Utf8 (u2 length, bytes). */
    private static long utf8HashOf(String name)
    {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        byte[] u = new byte[n.length + 2];
        u[0] = (byte) (n.length >> 8);
        u[1] = (byte) n.length;
        System.arraycopy(n, 0, u, 2, n.length);
        return classfile.ClassReader.utf8Hash(u, 0);
    }

    private static int count(int[] w, int word)
    {
        int n = 0;
        for (int x : w)
        {
            if (x == word)
            {
                n += 1;
            }
        }
        return n;
    }

    /** Yieldpoints in {@code w}: each loads the request word with {@code ldr x16, [x16]} followed by {@code cbz x16}. */
    private static int polls(int[] w)
    {
        int ldr = asm.A64Enc.ldrx(16, 16, 0);
        int n = 0;
        for (int i = 0; i + 1 < w.length; i++)
        {
            if (w[i] == ldr && (w[i + 1] & 0xFF00001F) == 0xB4000010)
            {
                n += 1;
            }
        }
        return n;
    }

    /** The method's reference map words (compiler.RefMaps), or an empty array when it has none. */
    private static int[] gcMap(ClassFile cf, String method, String desc)
    {
        BaselineCompiler.CompiledMethod cm = new BaselineCompiler(cf).compileMethod(cf.method(method, desc),
                CodeBuffer.LOAD_ADDRESS, false);
        int at = cm.gcMapWord();
        return at == 0 ? new int[0] : java.util.Arrays.copyOfRange(cm.words(), at, cm.words().length);
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

    /**
     * Assert no method in {@code cls} both COMPILES in deep-stack mode and is covered by an exception handler --
     * the codegen-mode pairing described at the call site. Depth is the COMPILER's answer, not javac's
     * {@code max_stack}: max_stack counts a long or double as two slots, while Baseline's operand stack holds one
     * entry per value (and adds lowering transients max_stack cannot see), so {@code proxyType(JJJJJ)J} -- five
     * longs, max_stack 10, peak depth 5 -- tripped the old declared-depth test while compiling shallow. Named in
     * the failure so a breakage says WHICH method crossed the line rather than only that one did.
     */
    private static void assertNoDeepHandlers(BaselineCompiler.ClassResolver res, String cls)
    {
        ClassFile cf = res.resolve(cls);
        int bad = 0;
        int checked = 0;
        for (ClassFile.Method m : cf.methods())
        {
            if (m.code == null || m.exceptions == null || m.exceptions.length == 0)
            {
                continue;
            }
            BaselineCompiler bc = new BaselineCompiler(cf, res);
            bc.compileMethod(m, 0x80000L, false);
            checked += 1;
            if (bc.lastDeepStack())
            {
                bad += 1;
                System.out.println("  deep-stack handler: " + m.name + m.descriptor + " max_stack=" + m.maxStack);
            }
        }
        // vm/VMGc has none today, so its check is vacuous until one appears -- which is when it matters.
        System.out.println("  " + cls + ": " + checked + " handler-bearing methods compiled");
        T.eq("no deep-stack handlers in " + cls, 0, bad);
    }


    /** Append each word of {@code ws} to {@code l} (loadImm64 now returns int[]). */
    private static void addAll(List<Integer> l, int[] ws)
    {
        for (int w : ws)
        {
            l.add(w);
        }
    }

    /** Whether {@code words} contains {@code w} anywhere. */
    private static boolean contains(int[] words, int w)
    {
        for (int x : words)
        {
            if (x == w)
            {
                return true;
            }
        }
        return false;
    }
}
