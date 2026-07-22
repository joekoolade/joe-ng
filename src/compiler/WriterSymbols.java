package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import objectmodel.ObjectModel;

import compiler.BaselineCompiler.CallSite;
import compiler.BaselineCompiler.StaticRef;
import compiler.BaselineCompiler.StrRef;
import compiler.BaselineCompiler.TibRef;
import compiler.BaselineCompiler.TypeRef;

/**
 * Writer implementation of the {@link Symbols} seam: emit a fixed-width
 * placeholder and record the site (with its resolved {@code String} key) for
 * {@link writer.ImageBuilder} to relocate after layout. The record lists it fills
 * are handed back through {@link BaselineCompiler.CompiledMethod}.
 *
 * <p>This is the ClassFile-bound half of the compiler — it resolves references by
 * {@code String} name against the parsed classfiles. The metal provides a
 * different {@code Symbols} that emits resolved addresses from its loaded-class
 * registries; the shared lowering above the seam is identical either way (§M5.4.4).
 */
final class WriterSymbols implements Symbols, ClassFile.Resolver
{
    /** Synthetic statics slot holding the in-flight exception during athrow dispatch. */
    private static final String EXCEPTION_KEY = "vm/VM.$exception";

    /** Runtime-helper method keys, indexed by the ids in {@link Symbols}. */
    private static final String[] HELPER_KEY =
    {
        "vm/Heap.alloc(I)J", "vm/Heap.allocArray(II)J", "vm/VM.gcCollect(J)V",
        "vm/VM.instanceOf(JJ)I", "vm/VM.checkCast(JJ)J", "vm/VM.unwind(JJJ)V",
    };

    private final ClassFile cf;
    private final BaselineCompiler.ClassResolver resolver;

    // The six relocation lists, bundled (a fresh WriterSymbols per method, so no
    // defensive copy needed). The driver reads them back through relocations().
    private final BaselineCompiler.Relocations relocs = new BaselineCompiler.Relocations();

    WriterSymbols(ClassFile cf, BaselineCompiler.ClassResolver resolver)
    {
        this.cf = cf;
        this.resolver = resolver;
    }

    BaselineCompiler.Relocations relocations() { return relocs; }

    // ----- Symbols: emit a placeholder, record the resolved key -----
    public void call(CodeBuffer cb, int methodCp)
    {
        ClassFile.MemberRef r = cf.memberRef(methodCp);
        relocs.callSites().add(new CallSite(cb.emit(A64.bl(0)), BaselineCompiler.key(r.owner(), r.name(), r.descriptor())));
    }
    public void callHelper(CodeBuffer cb, int helper)
    {
        relocs.callSites().add(new CallSite(cb.emit(A64.bl(0)), HELPER_KEY[helper]));
    }
    public void tib(CodeBuffer cb, int reg, int classCp)
    {
        relocs.tibRefs().add(new TibRef(cb.reserveAddr(reg), reg, cf.classAt(classCp)));
    }
    public void type(CodeBuffer cb, int reg, int classCp)
    {
        relocs.typeRefs().add(new TypeRef(cb.reserveAddr(reg), reg, cf.classAt(classCp)));
    }
    public void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp)
    {
        relocs.interfaceRefs().add(new TypeRef(cb.reserveAddr(reg), reg, cf.memberRef(ifaceMethodCp).owner()));
    }
    public void staticField(CodeBuffer cb, int reg, int fieldCp)
    {
        relocs.staticRefs().add(new StaticRef(cb.reserveAddr(reg), reg, staticKey(cf.memberRef(fieldCp))));
    }
    public void string(CodeBuffer cb, int reg, int stringCp)
    {
        // The literal is laid out as a US-ASCII byte[]; carry its raw bytes as the key
        // (the writer's identities are byte content, not String — §M5.5b). Decoding here
        // is fine: WriterSymbols is the seed-side Symbols; only the record is metal-bound.
        byte[] text = cf.stringAt(stringCp).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        relocs.strRefs().add(new StrRef(cb.reserveAddr(reg), reg, text));
    }
    public void exceptionSlot(CodeBuffer cb, int reg)
    {
        relocs.staticRefs().add(new StaticRef(cb.reserveAddr(reg), reg, EXCEPTION_KEY));
    }

    // ----- Symbols: numeric / boolean queries resolved against the classfiles -----
    public int fieldOffset(int fieldCp)
    {
        ClassFile.MemberRef ref = cf.memberRef(fieldCp);
        ClassFile owner = resolve(ref.owner());
        return ObjectModel.fieldOffset(owner.instanceFieldIndex(ref.name()));
    }
    public int objectSize(int classCp)
    {
        return ObjectModel.scalarSize(resolve(cf.classAt(classCp)).instanceFieldCount());
    }
    public int vtableSlot(int methodCp)
    {
        ClassFile.MemberRef ref = cf.memberRef(methodCp);
        return ClassFile.vtableSlot(ref.owner(), ref.name(), ref.descriptor(), this);
    }
    public int interfaceSlot(int ifaceMethodCp)
    {
        ClassFile.MemberRef ref = cf.memberRef(ifaceMethodCp);
        return resolve(ref.owner()).interfaceSlot(ref.name(), ref.descriptor());
    }
    public boolean isIntrinsicCall(int methodCp)
    {
        return cf.memberRef(methodCp).owner().equals("magic/Magic");
    }
    public boolean intrinsicEmitsCall(int methodCp)
    {
        String n = cf.memberRef(methodCp).name();
        return n.equals("gc") || n.equals("call0") || n.equals("call2");
    }
    public int intrinsicId(int methodCp)
    {
        ClassFile.MemberRef ref = cf.memberRef(methodCp);
        String key = ref.name() + ref.descriptor();
        return switch (key)
        {
        case "wfe()V" -> Intrinsics.WFE;
        case "isb()V" -> Intrinsics.ISB;
        case "dsb()V" -> Intrinsics.DSB;
        case "dcCVAU(J)V" -> Intrinsics.DC_CVAU;
        case "icIALLU()V" -> Intrinsics.IC_IALLU;
        case "gc()V" -> Intrinsics.GC;
        case "call0(J)J" -> Intrinsics.CALL0;
        case "call2(JJJ)J" -> Intrinsics.CALL2;
        case "eret()V" -> Intrinsics.ERET;
        case "dropToEL1()V" -> Intrinsics.DROP_TO_EL1;
        case "writeHCR_EL2(J)V" -> Intrinsics.WRITE_HCR_EL2;
        case "writeCPTR_EL2(J)V" -> Intrinsics.WRITE_CPTR_EL2;
        case "writeCNTHCTL_EL2(J)V" -> Intrinsics.WRITE_CNTHCTL_EL2;
        case "writeCNTVOFF_EL2(J)V" -> Intrinsics.WRITE_CNTVOFF_EL2;
        case "writeSCTLR_EL1(J)V" -> Intrinsics.WRITE_SCTLR_EL1;
        case "writeSPSR_EL2(J)V" -> Intrinsics.WRITE_SPSR_EL2;
        case "writeELR_EL2(J)V" -> Intrinsics.WRITE_ELR_EL2;
        case "writeCPACR_EL1(J)V" -> Intrinsics.WRITE_CPACR_EL1;
        case "writeSP(J)V" -> Intrinsics.WRITE_SP;
        case "readSP()J" -> Intrinsics.READ_SP;
        case "resume(JJJ)V" -> Intrinsics.RESUME;
        case "store32(JI)V" -> Intrinsics.STORE32;
        case "store8(JI)V" -> Intrinsics.STORE8;
        case "store64(JJ)V" -> Intrinsics.STORE64;
        case "load32(J)I" -> Intrinsics.LOAD32;
        case "load8(J)I" -> Intrinsics.LOAD8;
        case "load64(J)J" -> Intrinsics.LOAD64;
        case "bytes(Ljava/lang/String;)[B" -> Intrinsics.BYTES;
        default -> throw new UnsupportedOperationException("unknown intrinsic magic/Magic." + key);
        };
    }
    public boolean isSkippableInit(int methodCp)
    {
        ClassFile.MemberRef r = cf.memberRef(methodCp);
        return ClassFile.isRoot(r.owner()) && r.name().equals("<init>");
    }

    // ----- fatal diagnostics: the writer-side rendering of the core's fail() seam -----
    // The exception *types* matter: an unsupported opcode/atype/etc. is an
    // UnsupportedOperationException (how gaps stay loud and how M5Gap classifies
    // them, keyed on the "opcode 0xNN at bc=" prefix); an internal invariant break is
    // an IllegalStateException.
    public void fail(int reason, int a, int b)
    {
        switch (reason)
        {
        case FAIL_OPCODE -> throw new UnsupportedOperationException(
            String.format("opcode 0x%02X at bc=%d not yet supported", a, b));
        case FAIL_NEWARRAY_ATYPE -> throw new UnsupportedOperationException("bad newarray atype " + a);
        case FAIL_INTRINSIC_ID -> throw new UnsupportedOperationException("unknown intrinsic id " + a);
        case FAIL_LDC_CONST -> throw new UnsupportedOperationException("ldc of unsupported constant #" + a);
        case FAIL_LOCAL_SLOT -> throw new IllegalStateException("local slot out of range: " + a);
        case FAIL_STACK_NOT_EMPTY -> throw new IllegalStateException(
            "operand stack not empty at " + (b == SITE_NEW ? "new" : "dropToEL1") + ": " + a);
        case FAIL_STACK_DEPTH -> throw new IllegalStateException("inconsistent stack depth at bc: " + a);
        case FAIL_BRANCH_TARGET -> throw new IllegalStateException("branch to non-instruction bc: " + a);
        case FAIL_STACK_OVERFLOW -> throw new IllegalStateException("operand stack too deep");
        case FAIL_STACK_UNDERFLOW -> throw new IllegalStateException("operand stack underflow");
        default -> throw new IllegalStateException("compile failure " + reason + " (" + a + ", " + b + ")");
        }
    }

    // ----- classfile resolution (also the ClassFile.Resolver seam for vtableSlot) -----
    public ClassFile resolve(String owner)
    {
        if (owner.equals(cf.thisClassName()))
        {
            return cf;
        }
        if (resolver == null)
        {
            throw new IllegalStateException("no class resolver for " + owner);
        }
        return resolver.resolve(owner);
    }

    /** Static-field key: {@code owner.name} (no descriptor — statics are unique by name). */
    private static String staticKey(ClassFile.MemberRef ref)
    {
        return ref.owner() + "." + ref.name();
    }
}
