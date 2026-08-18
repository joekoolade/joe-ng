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
        "vm/Heap.alloc(I)J", "vm/Heap.allocArray(II)J", "vm/VMGc.gcCollect(J)V",
        "vm/VM.instanceOf(JJ)I", "vm/VM.checkCast(JJ)J", "vm/VMUnwind.unwind(JJJ)V",
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
        String cls = cf.classAt(classCp);
        if (cls.startsWith("["))
        {
            // The host writer lays out no array Types (only the metal loader has them) -- an
            // instanceof/checkcast against an array type can't compile here. Under the M8 bake,
            // such a stock method becomes a trap stub instead of a wrong answer.
            throw new UnsupportedOperationException("array type-check not compiled by the host writer: " + cls);
        }
        relocs.typeRefs().add(new TypeRef(cb.reserveAddr(reg), reg, cls));
    }
    public void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp)
    {
        relocs.interfaceRefs().add(new TypeRef(cb.reserveAddr(reg), reg, cf.memberRef(ifaceMethodCp).owner()));
    }
    public void tagArray(CodeBuffer cb, int arrReg, int operand, boolean isRef)
    {
        // Host writer: no runtime array Types; VM's own arrays stay raw (element size in the header).
    }
    public void classLiteral(CodeBuffer cb, int reg, int classCp)
    {
        throw new UnsupportedOperationException("ldc class-literal not compiled by the host writer");
    }
    public boolean isGetClass(int methodCp)
    {
        return false;   // host writer: no getClass intrinsic (VM's own code doesn't call it)
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
        // M8 bake: vm-side code takes its literal as a raw byte[] (the Magic.bytes contract), but a
        // STOCK java.base method's ldc must produce a real java/lang/String OBJECT -- the writer
        // interns the host String and bakes it (stock layout: value byte[] + coder) via the
        // deep-snapshot graph, and this site is patched with that object's address.
        String caller = cf.thisClassName();
        if (caller.startsWith("java/") || caller.startsWith("jdk/") || caller.startsWith("sun/"))
        {
            relocs.stringObjs().add(new StrRef(cb.reserveAddr(reg), reg, text));
            return;
        }
        relocs.strRefs().add(new StrRef(cb.reserveAddr(reg), reg, text));
    }
    public void exceptionSlot(CodeBuffer cb, int reg)
    {
        relocs.staticRefs().add(new StaticRef(cb.reserveAddr(reg), reg, EXCEPTION_KEY));
    }

    // ----- Symbols: numeric / boolean queries resolved against the classfiles -----
    public int fieldOffset(int fieldCp)
    {
        // M8 world unification: inherited fields lay out FIRST (chainFieldBase), matching the
        // loader; the Fieldref's owner is the declaring class, so its own index sits on top.
        ClassFile.MemberRef ref = cf.memberRef(fieldCp);
        ClassFile owner = resolve(ref.owner());
        return ObjectModel.fieldOffset(chainBase(ref.owner()) + owner.instanceFieldIndex(ref.name()));
    }
    public int objectSize(int classCp)
    {
        String cls = cf.classAt(classCp);
        return ObjectModel.scalarSize(chainBase(cls) + resolve(cls).instanceFieldCount());
    }

    /** Inherited-field slot count of {@code cls}; 0 in resolver-less fixture compiles (flat classes). */
    private int chainBase(String cls)
    {
        if (resolver == null)
        {
            return 0;
        }
        return ClassFile.chainFieldBase(cls, this);
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
        return n.equals("gc") || n.equals("call0") || n.equals("call2") || n.equals("callN");
    }
    public int intrinsicId(int methodCp)
    {
        ClassFile.MemberRef ref = cf.memberRef(methodCp);
        String key = ref.name() + ref.descriptor();
        return switch (key)
        {
        case "wfe()V" -> Intrinsics.WFE;
        case "isb()V" -> Intrinsics.ISB;
        case "svc()V" -> Intrinsics.SVC;
        case "sev()V" -> Intrinsics.SEV;
        case "writeMAIR_EL1(J)V" -> Intrinsics.WRITE_MAIR_EL1;
        case "writeTCR_EL1(J)V" -> Intrinsics.WRITE_TCR_EL1;
        case "writeTTBR0_EL1(J)V" -> Intrinsics.WRITE_TTBR0_EL1;
        case "tlbiAll()V" -> Intrinsics.TLBI_ALL;
        case "spinLock(J)V" -> Intrinsics.SPIN_LOCK;
        case "spinUnlock(J)V" -> Intrinsics.SPIN_UNLOCK;
        case "dsb()V" -> Intrinsics.DSB;
        case "dcCVAU(J)V" -> Intrinsics.DC_CVAU;
        case "dcCVAC(J)V" -> Intrinsics.DC_CVAC;
        case "dcCIVAC(J)V" -> Intrinsics.DC_CIVAC;
        case "icIALLU()V" -> Intrinsics.IC_IALLU;
        case "readCurrentEL()J" -> Intrinsics.READ_CURRENT_EL;
        case "readMPIDR()J" -> Intrinsics.READ_MPIDR;
        case "readCNTFRQ_EL0()J" -> Intrinsics.READ_CNTFRQ_EL0;
        case "readCNTPCT_EL0()J" -> Intrinsics.READ_CNTPCT_EL0;
        case "writeCNTP_TVAL_EL0(J)V" -> Intrinsics.WRITE_CNTP_TVAL_EL0;
        case "writeCNTP_CTL_EL0(J)V" -> Intrinsics.WRITE_CNTP_CTL_EL0;
        case "enableIrq()V" -> Intrinsics.ENABLE_IRQ;
        case "readDaif()J" -> Intrinsics.READ_DAIF;
        case "disableIrq()V" -> Intrinsics.DISABLE_IRQ;
        case "readCNTP_CTL_EL0()J" -> Intrinsics.READ_CNTP_CTL_EL0;
        case "writeVBAR_EL1(J)V" -> Intrinsics.WRITE_VBAR_EL1;
        case "readESR_EL1()J" -> Intrinsics.READ_ESR_EL1;
        case "readELR_EL1()J" -> Intrinsics.READ_ELR_EL1;
        case "readFAR_EL1()J" -> Intrinsics.READ_FAR_EL1;
        case "gc()V" -> Intrinsics.GC;
        case "call0(J)J" -> Intrinsics.CALL0;
        case "call2(JJJ)J" -> Intrinsics.CALL2;
        case "callN(JJ)J" -> Intrinsics.CALL_N;
        case "addrOf(Ljava/lang/Object;)J" -> Intrinsics.ADDR_OF;
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
        case "readLR()J" -> Intrinsics.READ_LR;
        case "readX0()J" -> Intrinsics.READ_X0;
        case "resume(JJJJJ)V" -> Intrinsics.RESUME;
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
        if (!ClassFile.isRoot(r.owner()) || !r.name().equals("<init>"))
        {
            return false;
        }
        // The skip is the vm-side shim: a vm/... class's super() into a JDK class it doesn't
        // compile (throwables extending java/lang/Exception etc.). Inside the M8 bake domain the
        // stock <init>s ARE real compiled code -- java.base calling a java.base <init> (e.g.
        // Integer.valueOf's `new Integer(i)` -> Integer.<init> -> Number.<init> -> Object.<init>)
        // must run, or the constructed object's fields stay zero.
        String caller = cf.thisClassName();
        boolean callerBaked = caller.startsWith("java/") || caller.startsWith("jdk/") || caller.startsWith("sun/");
        return !callerBaked;
    }

    // invokedynamic never occurs in the image's own (JDK-free) classes, so the writer never lowers it.
    public boolean isConcatIndy(int idx) { return false; }
    public int concatRecipeOff(int idx) { return -1; }
    public void newStringFromBytes(CodeBuffer cb) { throw new IllegalStateException("no invokedynamic in image code"); }
    public boolean isLambdaIndy(int idx) { return false; }
    public int lambdaSize(int idx) { return 0; }
    public int lambdaSamArgc(int idx) { return 0; }
    public void lambdaTib(CodeBuffer cb, int reg, int idx) { throw new IllegalStateException("no invokedynamic in image code"); }
    public boolean isRecordIndy(int idx) { return false; }
    public void recordTrap(CodeBuffer cb) { throw new IllegalStateException("no invokedynamic in image code"); }

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
    public boolean canResolve(String owner)
    {
        return resolver != null || owner.equals(cf.thisClassName());
    }
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
