package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import objectmodel.ObjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The baseline compiler: Java bytecode → A64 machine code, compile-only
 * (PLAN.md §2, §5). Runs on the seed JVM inside the writer today, compiled into
 * the image as the runtime JIT later.
 *
 * <p><b>Register model</b> (simple, no spilling): JVM operand stack → x9..x15 by
 * depth; JVM local slot {@code k} → x{@code 19+k}. Values are 64-bit; int/long
 * are not distinguished (our values fit). Holds because javac keeps the operand
 * stack shallow and empty at branch/call boundaries.
 *
 * <p><b>Calling convention</b> (ours, bare metal): arguments in x0..x7, return
 * value in x0, link register x30. Locals live in callee-saved x19.., so a
 * method's prologue saves the ones it will use (plus x30 if it makes any call)
 * and moves incoming arguments into them; each {@code return} restores and
 * {@code ret}s. The entry method ({@code isEntry}) has no frame — it sets its own
 * stack and never returns. Inter-method call targets are left as {@code BL}
 * placeholders and resolved by {@link writer.ImageBuilder} after layout.
 *
 * <p>Magic intrinsics lower to privileged/MMIO instructions inline (no call).
 * Unhandled opcodes throw loudly — gaps are never silent.
 */
public final class BaselineCompiler
{

    private static final int OP_BASE = 9;    // operand stack -> x9..x15
    private static final int OP_MAX = 7;
    private static final int LOC_BASE = 19;  // locals -> x19..x28
    private static final int LOC_MAX = 10;   // beyond this a local lives in the frame
    private static final int SCRATCH = 16;   // IP0 — not a local or operand register

    /** Resolves an owner class name (e.g. "vm/Cell") to its parsed classfile. */
    public interface ClassResolver
    {
        ClassFile resolve(String owner);
    }

    /** A single compiled method: its words, relocation fixups, and unwind metadata. */
    public record CompiledMethod(int[] words, List<CallSite> callSites, List<TibRef> tibRefs,
                                 List<StrRef> strRefs, List<StaticRef> staticRefs, List<TypeRef> typeRefs,
                                 List<TypeRef> interfaceRefs, int frameSize, List<HandlerRange> handlers) {}
    /** A try/catch region as word indices, for the writer's machine-PC handler table. */
    public record HandlerRange(int startWord, int endWord, int handlerWord, String catchClass) {}
    /** A {@code BL} site: word index within the method, and the callee key. */
    public record CallSite(int wordIndex, String calleeKey) {}
    /** A reserved TIB-pointer address load ({@code new}) awaiting the class's TIB address. */
    public record TibRef(int wordIndex, int reg, String className) {}
    /** A reserved address load for an interned string literal ({@code ldc}). */
    public record StrRef(int wordIndex, int reg, String text) {}
    /** A reserved address load for a static field ({@code getstatic}/{@code putstatic}). */
    public record StaticRef(int wordIndex, int reg, String fieldKey) {}
    /** A reserved address load for a class's Type ({@code instanceof}/{@code checkcast}). */
    public record TypeRef(int wordIndex, int reg, String className) {}

    private final ClassFile cf;
    private final ClassResolver resolver;

    /** Synthetic statics slot holding the in-flight exception during athrow dispatch. */
    private static final String EXCEPTION_KEY = "vm/VM.$exception";

    private int sp;
    private int[] bcDepth;         // operand-stack depth at each branch target, or -1
    private ClassFile.ExceptionEntry[] exceptions;
    private boolean isEntry;
    private int frameSize;
    private int localSaveBase;
    private int spillBase;
    private int overflowBase;     // frame offset of local slot LOC_MAX
    private int regLocals;        // locals held in x19.. (min(maxLocals, LOC_MAX))
    private int overflowLocals;   // locals held in the frame (maxLocals - LOC_MAX)
    private int maxLocals;
    private boolean saveLR;
    private boolean nonLeaf;
    private final List<Fixup> fixups = new ArrayList<>();
    private final List<CallSite> callSites = new ArrayList<>();
    private final List<TibRef> tibRefs = new ArrayList<>();
    private final List<StrRef> strRefs = new ArrayList<>();
    private final List<StaticRef> staticRefs = new ArrayList<>();
    private final List<TypeRef> typeRefs = new ArrayList<>();
    private final List<TypeRef> interfaceRefs = new ArrayList<>();   // interface Type address loads

    public BaselineCompiler(ClassFile cf)
    {
        this(cf, null);
    }
    public BaselineCompiler(ClassFile cf, ClassResolver resolver)
    {
        this.cf = cf;
        this.resolver = resolver;
    }

    private interface BranchEnc
    {
        int encode(int byteOffset);
    }
    private record Fixup(int wordIndex, int targetBc, BranchEnc enc) {}

    /** Method key used for call resolution: {@code owner.name+descriptor}. */
    public static String key(String owner, String name, String desc)
    {
        return owner + "." + name + desc;
    }

    /** Back-compat single-method compile with no real calls (spin/fixtures). */
    public void compile(ClassFile.Method method, CodeBuffer cb)
    {
        CompiledMethod cm = compileMethod(method, cb.base(), false);
        if (!cm.callSites.isEmpty() || !cm.tibRefs.isEmpty() || !cm.strRefs.isEmpty()
                || !cm.staticRefs.isEmpty() || !cm.typeRefs.isEmpty() || !cm.interfaceRefs.isEmpty())
        {
            throw new IllegalStateException("compile(Method,CodeBuffer) is for self-contained methods; use ImageBuilder");
        }
        for (int w : cm.words)
        {
            cb.emit(w);
        }
    }

    /** Compile one method at absolute {@code base}; {@code isEntry} => no frame. */
    public CompiledMethod compileMethod(ClassFile.Method method, long base, boolean isEntry)
    {
        byte[] code = method.code;
        if (code == null)
        {
            throw new IllegalArgumentException("method " + method.name + " has no Code");
        }

        this.isEntry = isEntry;
        this.maxLocals = method.maxLocals;
        this.exceptions = method.exceptions;
        this.nonLeaf = isNonLeaf(code);
        this.saveLR = !isEntry && nonLeaf;
        // Locals live in callee-saved x19..x28; a method needing more keeps the
        // overflow in the frame (see localMem). Slots 0..LOC_MAX-1 stay in
        // registers, so a method within the old limit gets exactly the old layout.
        this.regLocals = Math.min(maxLocals, LOC_MAX);
        this.overflowLocals = Math.max(0, maxLocals - LOC_MAX);
        // frame: [LR?][saved local regs][overflow locals][operand spill area]
        this.localSaveBase = saveLR ? 8 : 0;
        this.overflowBase = localSaveBase + regLocals * 8;
        this.spillBase = overflowBase + overflowLocals * 8;
        int spillWords = (!isEntry && nonLeaf) ? OP_MAX : 0;
        int savedWords = (saveLR ? 1 : 0) + regLocals + overflowLocals + spillWords;
        this.frameSize = isEntry ? 0 : A64.align16(savedWords * 8);
        sp = 0;

        CodeBuffer cb = new CodeBuffer(base);
        if (!isEntry)
        {
            emitPrologue(cb, method);
        }

        int[] bcToWord = new int[code.length];
        java.util.Arrays.fill(bcToWord, -1);
        bcDepth = new int[code.length];
        java.util.Arrays.fill(bcDepth, -1);
        for (ClassFile.ExceptionEntry en : method.exceptions)   // handler entry: exception on stack (depth 1)
        {
            bcDepth[en.handlerPc()] = 1;
        }
        int pos = 0;
        while (pos < code.length)
        {
            bcToWord[pos] = cb.wordCount();
            if (bcDepth[pos] >= 0)
            {
                sp = bcDepth[pos];    // merge point: adopt the branch-edge depth
            }
            int op = code[pos] & 0xFF;
            pos += step(op, code, pos, cb);
        }

        for (Fixup f : fixups)
        {
            int target = bcToWord[f.targetBc];
            if (target < 0)
            {
                throw new IllegalStateException("branch to non-instruction bc=" + f.targetBc);
            }
            cb.set(f.wordIndex, f.enc.encode((target - f.wordIndex) * 4));
        }
        // Machine-PC ranges for the writer's handler table (bytecode PCs -> word indices).
        int codeWords = cb.wordCount();
        List<HandlerRange> handlers = new ArrayList<>();
        for (ClassFile.ExceptionEntry en : method.exceptions)
        {
            int startW = bcToWord[en.startPc()];
            int endW = en.endPc() < code.length ? bcToWord[en.endPc()] : codeWords;
            int handlerW = bcToWord[en.handlerPc()];
            String catchClass = en.catchType() == 0 ? null : cf.classAt(en.catchType());
            handlers.add(new HandlerRange(startW, endW, handlerW, catchClass));
        }
        return new CompiledMethod(cb.toWords(), List.copyOf(callSites), List.copyOf(tibRefs),
                                  List.copyOf(strRefs), List.copyOf(staticRefs), List.copyOf(typeRefs), List.copyOf(interfaceRefs),
                                  frameSize, List.copyOf(handlers));
    }

    // ----- prologue / epilogue --------------------------------------------
    private void emitPrologue(CodeBuffer cb, ClassFile.Method method)
    {
        if (frameSize > 0)
        {
            cb.emit(A64.subImm(31, 31, frameSize));    // sub sp, sp, #frame
        }
        if (saveLR)
        {
            cb.emit(A64.strx(30, 31, 0));    // str x30, [sp]
        }
        for (int i = 0; i < regLocals; i++)              // only the register-backed ones
        {
            cb.emit(A64.strx(LOC_BASE + i, 31, localSaveBase + i * 8));
        }
        // move incoming arguments (x0..) into their locals; instance methods
        // receive `this` as x0 -> local slot 0. A parameter landing beyond the
        // register file is stored straight into its frame slot.
        int arg = 0;
        int slot = 0;
        if (!method.isStatic)
        {
            cb.emit(A64.movReg(localReg(0), 0));
            arg = 1;
            slot = 1;
        }
        for (char t : paramTypes(method.descriptor))
        {
            cb.emit(inReg(slot) ? A64.movReg(localReg(slot), arg)
                                : A64.strx(arg, 31, localMem(slot)));
            arg++;
            slot += (t == 'J' || t == 'D') ? 2 : 1;
        }
    }

    private void emitEpilogue(CodeBuffer cb)
    {
        if (isEntry)
        {
            cb.emit(A64.ret());
            return;
        }
        if (saveLR)
        {
            cb.emit(A64.ldrx(30, 31, 0));
        }
        for (int i = 0; i < regLocals; i++)              // only the register-backed ones
        {
            cb.emit(A64.ldrx(LOC_BASE + i, 31, localSaveBase + i * 8));
        }
        if (frameSize > 0)
        {
            cb.emit(A64.addImm(31, 31, frameSize));
        }
        cb.emit(A64.ret());
    }

    // ----- opcode dispatch -------------------------------------------------
    private int step(int op, byte[] code, int pos, CodeBuffer cb)
    {
        switch (op)
        {
        case 0x00 ->
        {
            return 1;
        }  // nop
            case 0xB1 ->
            {
                emitEpilogue(cb);
                return 1;
            }  // return
                case 0xAC, 0xAD, 0xB0 ->
                {
                    cb.emit(A64.movReg(0, popReg()));
                    emitEpilogue(cb);
                    return 1;
                }  // ireturn/lreturn/areturn

                    case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 ->
                    {
                        loadConst(cb, op - 0x03);
                        return 1;
                    }
                        case 0x01, 0x09 ->
                        {
                            loadConst(cb, 0);    // aconst_null (null == 0) / lconst_0
                            return 1;
                        }
                            case 0x0A ->
                            {
                                loadConst(cb, 1);
                                return 1;
                            }
                                case 0x10 ->
                                {
                                    loadConst(cb, (byte) code[pos + 1]);
                                    return 2;
                                }
                                    case 0x11 ->
                                    {
                                        loadConst(cb, (short) u2(code, pos + 1));
                                        return 3;
                                    }
                                        case 0x12 ->
                                        {
                                            ldc(cb, code[pos + 1] & 0xFF);
                                            return 2;
                                        }
                                            case 0x13 ->
                                            {
                                                ldc(cb, u2(code, pos + 1));
                                                return 3;
                                            }
                                                case 0x14 ->
                                                {
                                                    loadConst(cb, cf.longAt(u2(code, pos + 1)));
                                                    return 3;
                                                }

                                                    case 0x15, 0x16, 0x19 ->
                                                    {
                                                        load(cb, code[pos + 1] & 0xFF);
                                                        return 2;
                                                    }  // iload/lload/aload
                                                        case 0x1A, 0x1B, 0x1C, 0x1D ->
                                                        {
                                                            load(cb, op - 0x1A);
                                                            return 1;
                                                        }  // iload_0..3
                                                            case 0x1E, 0x1F, 0x20, 0x21 ->
                                                            {
                                                                load(cb, op - 0x1E);
                                                                return 1;
                                                            }  // lload_0..3
                                                                case 0x2A, 0x2B, 0x2C, 0x2D ->
                                                                {
                                                                    load(cb, op - 0x2A);
                                                                    return 1;
                                                                }  // aload_0..3

                                                                    case 0x36, 0x37, 0x3A ->
                                                                    {
                                                                        store(cb, code[pos + 1] & 0xFF);
                                                                        return 2;
                                                                    }  // istore/lstore/astore
                                                                        case 0x3B, 0x3C, 0x3D, 0x3E ->
                                                                        {
                                                                            store(cb, op - 0x3B);
                                                                            return 1;
                                                                        }  // istore_0..3
                                                                            case 0x3F, 0x40, 0x41, 0x42 ->
                                                                            {
                                                                                store(cb, op - 0x3F);
                                                                                return 1;
                                                                            }  // lstore_0..3
                                                                                case 0x4B, 0x4C, 0x4D, 0x4E ->
                                                                                {
                                                                                    store(cb, op - 0x4B);
                                                                                    return 1;
                                                                                }  // astore_0..3
                                                                                    case 0x57 ->
                                                                                    {
                                                                                        popReg();
                                                                                        return 1;
                                                                                    }  // pop (discard result)
                                                                                        case 0x59 ->
                                                                                        {
                                                                                            dup(cb);
                                                                                            return 1;
                                                                                        }  // dup
                                                                                            case 0x84 ->
                                                                                            {
                                                                                                iinc(cb, code[pos + 1] & 0xFF, (byte) code[pos + 2]);
                                                                                                return 3;
                                                                                            }

                                                                                                // ---- array element load/store (base + index<<scale) ----
                                                                                                case 0x33 ->
                                                                                                {
                                                                                                    arrayLoad(cb, 0);
                                                                                                    return 1;
                                                                                                }  // baload  (byte, zero-ext)
                                                                                                    case 0x2E ->
                                                                                                    {
                                                                                                        arrayLoad(cb, 2);
                                                                                                        return 1;
                                                                                                    }  // iaload  (int, sign-ext)
                                                                                                        case 0x2F ->
                                                                                                        {
                                                                                                            arrayLoad(cb, 3);
                                                                                                            return 1;
                                                                                                        }  // laload  (long)
                                                                                                            case 0x32 ->
                                                                                                            {
                                                                                                                arrayLoad(cb, 3);
                                                                                                                return 1;
                                                                                                            }  // aaload  (ref)
                                                                                                                case 0x54 ->
                                                                                                                {
                                                                                                                    arrayStore(cb, 0);
                                                                                                                    return 1;
                                                                                                                }  // bastore
                                                                                                                    case 0x4F ->
                                                                                                                    {
                                                                                                                        arrayStore(cb, 2);
                                                                                                                        return 1;
                                                                                                                    }  // iastore
                                                                                                                        case 0x50 ->
                                                                                                                        {
                                                                                                                            arrayStore(cb, 3);
                                                                                                                            return 1;
                                                                                                                        }  // lastore
                                                                                                                            case 0x53 ->
                                                                                                                            {
                                                                                                                                arrayStore(cb, 3);
                                                                                                                                return 1;
                                                                                                                            }  // aastore
                                                                                                                                case 0xBE ->
                                                                                                                                {
                                                                                                                                    arrayLength(cb);
                                                                                                                                    return 1;
                                                                                                                                }  // arraylength

                                                                                                                                    case 0x60, 0x61 ->
                                                                                                                                    {
                                                                                                                                        binop(cb, Bin.ADD);
                                                                                                                                        return 1;
                                                                                                                                    }
                                                                                                                                        case 0x64, 0x65 ->
                                                                                                                                        {
                                                                                                                                            binop(cb, Bin.SUB);
                                                                                                                                            return 1;
                                                                                                                                        }
                                                                                                                                            case 0x68, 0x69 ->
                                                                                                                                            {
                                                                                                                                                binop(cb, Bin.MUL);
                                                                                                                                                return 1;
                                                                                                                                            }
                                                                                                                                            case 0x6C, 0x6D ->
                                                                                                                                            {
                                                                                                                                                binop(cb, Bin.DIV);
                                                                                                                                                return 1;
                                                                                                                                            }
                                                                                                                                                case 0x74, 0x75 ->
                                                                                                                                                {
                                                                                                                                                    int r = OP_BASE + sp - 1;
                                                                                                                                                    cb.emit(A64.subReg(r, A64.XZR, r));
                                                                                                                                                    return 1;
                                                                                                                                                }  // ineg/lneg
                                                                                                                                                    case 0x78, 0x79 ->
                                                                                                                                                    {
                                                                                                                                                        binop(cb, Bin.SHL);
                                                                                                                                                        return 1;
                                                                                                                                                    }  // ishl/lshl
                                                                                                                                                        case 0x7A, 0x7B ->
                                                                                                                                                        {
                                                                                                                                                            binop(cb, Bin.ASR);
                                                                                                                                                            return 1;
                                                                                                                                                        }  // ishr/lshr
                                                                                                                                                            case 0x7C, 0x7D ->
                                                                                                                                                            {
                                                                                                                                                                binop(cb, Bin.LSR);
                                                                                                                                                                return 1;
                                                                                                                                                            }  // iushr/lushr
                                                                                                                                                                case 0x7E, 0x7F ->
                                                                                                                                                                {
                                                                                                                                                                    binop(cb, Bin.AND);
                                                                                                                                                                    return 1;
                                                                                                                                                                }
                                                                                                                                                                    case 0x80, 0x81 ->
                                                                                                                                                                    {
                                                                                                                                                                        binop(cb, Bin.OR);
                                                                                                                                                                        return 1;
                                                                                                                                                                    }  // ior/lor
                                                                                                                                                                        case 0x82, 0x83 ->
                                                                                                                                                                        {
                                                                                                                                                                            binop(cb, Bin.XOR);
                                                                                                                                                                            return 1;
                                                                                                                                                                        }  // ixor/lxor
                                                                                                                                                                            case 0x94 ->
                                                                                                                                                                            {
                                                                                                                                                                                lcmp(cb);
                                                                                                                                                                                return 1;
                                                                                                                                                                            }  // lcmp -> -1/0/1

                                                                                                                                                                                case 0x85, 0x88 ->
                                                                                                                                                                                {
                                                                                                                                                                                    return 1;
                                                                                                                                                                                }  // i2l/l2i: no-op (values fit 64-bit)
                                                                                                                                                                                    case 0x91 ->
                                                                                                                                                                                    {
                                                                                                                                                                                        int r = OP_BASE + sp - 1;
                                                                                                                                                                                        cb.emit(A64.sxtb(r, r));
                                                                                                                                                                                        return 1;
                                                                                                                                                                                    }  // i2b
                                                                                                                                                                                        case 0x92 ->
                                                                                                                                                                                        {
                                                                                                                                                                                            int r = OP_BASE + sp - 1;
                                                                                                                                                                                            cb.emit(A64.uxth(r, r));
                                                                                                                                                                                            return 1;
                                                                                                                                                                                        }  // i2c
                                                                                                                                                                                            case 0x93 ->
                                                                                                                                                                                            {
                                                                                                                                                                                                int r = OP_BASE + sp - 1;
                                                                                                                                                                                                cb.emit(A64.sxth(r, r));
                                                                                                                                                                                                return 1;
                                                                                                                                                                                            }  // i2s

                                                                                                                                                                                                case 0x99 ->
                                                                                                                                                                                                {
                                                                                                                                                                                                    branchZero(cb, code, pos, true);
                                                                                                                                                                                                    return 3;
                                                                                                                                                                                                }
                                                                                                                                                                                                    case 0x9A ->
                                                                                                                                                                                                    {
                                                                                                                                                                                                        branchZero(cb, code, pos, false);
                                                                                                                                                                                                        return 3;
                                                                                                                                                                                                    }
                                                                                                                                                                                                        case 0x9B ->
                                                                                                                                                                                                        {
                                                                                                                                                                                                            branchCmpZero(cb, code, pos, A64.LT);
                                                                                                                                                                                                            return 3;
                                                                                                                                                                                                        }
                                                                                                                                                                                                            case 0x9C ->
                                                                                                                                                                                                            {
                                                                                                                                                                                                                branchCmpZero(cb, code, pos, A64.GE);
                                                                                                                                                                                                                return 3;
                                                                                                                                                                                                            }
                                                                                                                                                                                                                case 0x9D ->
                                                                                                                                                                                                                {
                                                                                                                                                                                                                    branchCmpZero(cb, code, pos, A64.GT);
                                                                                                                                                                                                                    return 3;
                                                                                                                                                                                                                }
                                                                                                                                                                                                                    case 0x9E ->
                                                                                                                                                                                                                    {
                                                                                                                                                                                                                        branchCmpZero(cb, code, pos, A64.LE);
                                                                                                                                                                                                                        return 3;
                                                                                                                                                                                                                    }
                                                                                                                                                                                                                        case 0x9F ->
                                                                                                                                                                                                                        {
                                                                                                                                                                                                                            branchCmp(cb, code, pos, A64.EQ);
                                                                                                                                                                                                                            return 3;
                                                                                                                                                                                                                        }
                                                                                                                                                                                                                            case 0xA0 ->
                                                                                                                                                                                                                            {
                                                                                                                                                                                                                                branchCmp(cb, code, pos, A64.NE);
                                                                                                                                                                                                                                return 3;
                                                                                                                                                                                                                            }
                                                                                                                                                                                                                                case 0xA1 ->
                                                                                                                                                                                                                                {
                                                                                                                                                                                                                                    branchCmp(cb, code, pos, A64.LT);
                                                                                                                                                                                                                                    return 3;
                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                    case 0xA2 ->
                                                                                                                                                                                                                                    {
                                                                                                                                                                                                                                        branchCmp(cb, code, pos, A64.GE);
                                                                                                                                                                                                                                        return 3;
                                                                                                                                                                                                                                    }
                                                                                                                                                                                                                                        case 0xA3 ->
                                                                                                                                                                                                                                        {
                                                                                                                                                                                                                                            branchCmp(cb, code, pos, A64.GT);
                                                                                                                                                                                                                                            return 3;
                                                                                                                                                                                                                                        }
                                                                                                                                                                                                                                            case 0xA4 ->
                                                                                                                                                                                                                                            {
                                                                                                                                                                                                                                                branchCmp(cb, code, pos, A64.LE);
                                                                                                                                                                                                                                                return 3;
                                                                                                                                                                                                                                            }
                                                                                                                                                                                                                                                case 0xA7 ->
                                                                                                                                                                                                                                                {
                                                                                                                                                                                                                                                    int target = pos + s2(code, pos + 1);
                                                                                                                                                                                                                                                    int w = cb.emit(A64.b(0));
                                                                                                                                                                                                                                                    fixups.add(new Fixup(w, target, A64::b));
                                                                                                                                                                                                                                                    recordDepth(target);
                                                                                                                                                                                                                                                    return 3;
                                                                                                                                                                                                                                                }

        case 0xB2 ->
        {
            getstatic(cb, u2(code, pos + 1));
            return 3;
        }
            case 0xB3 ->
            {
                putstatic(cb, u2(code, pos + 1));
                return 3;
            }
                case 0xB4 ->
                {
                    getfield(cb, u2(code, pos + 1));
                    return 3;
                }
                    case 0xB5 ->
                    {
                        putfield(cb, u2(code, pos + 1));
                        return 3;
                    }
                        case 0xB6 ->
                        {
                            lowerInvokeVirtual(u2(code, pos + 1), cb);
                            return 3;
                        }
                            case 0xB7 ->
                            {
                                lowerInvokeSpecial(u2(code, pos + 1), cb);
                                return 3;
                            }
                                case 0xB8 ->
                                {
                                    lowerInvokeStatic(u2(code, pos + 1), cb);
                                    return 3;
                                }
                                    case 0xB9 ->
                                    {
                                        lowerInvokeInterface(u2(code, pos + 1), cb);
                                        return 5;
                                    }  // invokeinterface
                                        case 0xBB ->
                                        {
                                            lowerNew(u2(code, pos + 1), cb);
                                            return 3;
                                        }
                                            case 0xBC ->
                                            {
                                                lowerNewArray(code[pos + 1] & 0xFF, cb);
                                                return 2;
                                            }
                                                case 0xBF ->
                                                {
                                                    athrow(cb, pos);
                                                    return 1;
                                                }  // athrow
                                                    case 0xC0 ->
                                                    {
                                                        typeCheck(cb, u2(code, pos + 1), "checkCast", "(JJ)J");
                                                        return 3;
                                                    }  // checkcast
                                                        case 0xC1 ->
                                                        {
                                                            typeCheck(cb, u2(code, pos + 1), "instanceOf", "(JJ)I");
                                                            return 3;
                                                        }  // instanceof

                                                                default -> throw new UnsupportedOperationException(
                                                                    String.format("opcode 0x%02X at bc=%d not yet supported", op, pos));
        }
    }

    private enum Bin { ADD, SUB, MUL, DIV, AND, OR, XOR, SHL, ASR, LSR }

    // ----- register allocation ---------------------------------------------
    private int pushReg()
    {
        if (sp >= OP_MAX)
        {
            throw new IllegalStateException("operand stack too deep");
        }
        return OP_BASE + sp++;
    }
    private int popReg()
    {
        if (sp <= 0)
        {
            throw new IllegalStateException("operand stack underflow");
        }
        return OP_BASE + --sp;
    }
    /** Register holding local {@code slot}. Only valid when {@link #inReg} says so. */
    private int localReg(int slot)
    {
        if (slot < 0 || slot >= LOC_MAX)
        {
            throw new IllegalStateException("local slot out of range: " + slot);
        }
        return LOC_BASE + slot;
    }
    /** True if this local lives in a register rather than the frame. */
    private static boolean inReg(int slot)
    {
        return slot < LOC_MAX;
    }
    /** Frame offset of an overflow local (slot &gt;= LOC_MAX). */
    private int localMem(int slot)
    {
        return overflowBase + (slot - LOC_MAX) * 8;
    }
    private void expectEmpty(String where)
    {
        if (sp != 0)
        {
            throw new IllegalStateException("operand stack not empty (" + sp + ") at " + where);
        }
    }

    private void loadConst(CodeBuffer cb, long v)
    {
        cb.emitAll(A64.loadImm64(pushReg(), v));
    }
    private void load(CodeBuffer cb, int slot)
    {
        int r = pushReg();
        cb.emit(inReg(slot) ? A64.movReg(r, localReg(slot))
                            : A64.ldrx(r, 31, localMem(slot)));
    }
    private void store(CodeBuffer cb, int slot)
    {
        int r = popReg();
        cb.emit(inReg(slot) ? A64.movReg(localReg(slot), r)
                            : A64.strx(r, 31, localMem(slot)));
    }

    private void iinc(CodeBuffer cb, int slot, int delta)
    {
        if (inReg(slot))
        {
            int r = localReg(slot);
            cb.emit(delta >= 0 ? A64.addImm(r, r, delta) : A64.subImm(r, r, -delta));
            return;
        }
        int r = SCRATCH;                                   // read-modify-write in the frame
        cb.emit(A64.ldrx(r, 31, localMem(slot)));
        cb.emit(delta >= 0 ? A64.addImm(r, r, delta) : A64.subImm(r, r, -delta));
        cb.emit(A64.strx(r, 31, localMem(slot)));
    }

    private void binop(CodeBuffer cb, Bin kind)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        cb.emit(switch (kind)
    {
    case ADD -> A64.addReg(r, a, b);
        case SUB -> A64.subReg(r, a, b);
        case MUL -> A64.mulReg(r, a, b);
        case DIV -> A64.sdivReg(r, a, b);
        case AND -> A64.andReg(r, a, b);
        case OR  -> A64.orrReg(r, a, b);
        case XOR -> A64.eorReg(r, a, b);
        case SHL -> A64.lslv(r, a, b);
        case ASR -> A64.asrv(r, a, b);
        case LSR -> A64.lsrv(r, a, b);
        });
    }

    private void dup(CodeBuffer cb)
    {
        int top = OP_BASE + sp - 1;
        cb.emit(A64.movReg(pushReg(), top));
    }

    /** lcmp: push -1/0/1 for a&lt;b / a==b / a&gt;b (usually consumed by a following if). */
    private void lcmp(CodeBuffer cb)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        cb.emit(A64.cmpReg(a, b));
        cb.emit(A64.cset(r, A64.GT));            // a>b -> 1, else 0
        cb.emit(A64.csinv(r, r, A64.XZR, A64.GE)); // a<b -> -1, else keep
    }

    // ----- static fields: absolute address in the image statics area --------
    private void getstatic(CodeBuffer cb, int cpIndex)
    {
        emitLoadStatic(cb, staticKey(cf.memberRef(cpIndex)), pushReg());
    }
    private void putstatic(CodeBuffer cb, int cpIndex)
    {
        emitStoreStatic(cb, staticKey(cf.memberRef(cpIndex)), popReg());
    }

    /** Load static field {@code key} into {@code destReg}. */
    private void emitLoadStatic(CodeBuffer cb, String key, int destReg)
    {
        staticRefs.add(new StaticRef(cb.reserveAddr(destReg), destReg, key));
        cb.emit(A64.ldrx(destReg, destReg, 0));
    }
    /** Store {@code valReg} to static field {@code key} (via x16 for the address). */
    private void emitStoreStatic(CodeBuffer cb, String key, int valReg)
    {
        staticRefs.add(new StaticRef(cb.reserveAddr(16), 16, key));
        cb.emit(A64.strx(valReg, 16, 0));
    }

    private static String staticKey(ClassFile.MemberRef ref)
    {
        return ref.owner() + "." + ref.name();
    }

    /** instanceof/checkcast: push the target class's Type address, call the VM helper. */
    private void typeCheck(CodeBuffer cb, int classIndex, String helper, String desc)
    {
        String cls = cf.classAt(classIndex);
        int r = pushReg();                                       // objref stays below; push targetType addr
        typeRefs.add(new TypeRef(cb.reserveAddr(r), r, cls));
        emitCall(cb, "vm/VM." + helper + desc, desc, false);     // (ref, targetType) -> int/ref
    }

    // ----- object fields (8-byte slots; see objectmodel.ObjectModel) --------
    private void getfield(CodeBuffer cb, int cpIndex)
    {
        int off = fieldOffset(cf.memberRef(cpIndex));
        int obj = popReg();
        int r = pushReg();
        cb.emit(A64.ldrx(r, obj, off));
    }

    private void putfield(CodeBuffer cb, int cpIndex)
    {
        int off = fieldOffset(cf.memberRef(cpIndex));
        int val = popReg();
        int obj = popReg();
        cb.emit(A64.strx(val, obj, off));
    }

    private int fieldOffset(ClassFile.MemberRef ref)
    {
        ClassFile owner = resolve(ref.owner());
        return ObjectModel.fieldOffset(owner.instanceFieldIndex(ref.name()));
    }

    // ----- allocation: new -> Heap.alloc(size), store TIB, push ref ---------
    /**
     * {@code new} is a call underneath ({@code Heap.alloc}), so it clobbers the
     * operand registers exactly like any other call — and is spilled around exactly
     * like one. It used to demand an empty operand stack instead, which made
     * ordinary expressions such as {@code f(new X())} or {@code a.b = new X()}
     * uncompilable; that single restriction blocked 14 of BaselineCompiler's own
     * methods from self-hosting (PLAN.md §M5.1).
     *
     * <p>The entry method is the exception: it is frameless (it sets up SP itself),
     * so there is no spill area to use and the old requirement still holds.
     */
    private void lowerNew(int classIndex, CodeBuffer cb)
    {
        if (isEntry)
        {
            expectEmpty("new");                                   // frameless: nowhere to spill
        }
        String className = cf.classAt(classIndex);
        int size = ObjectModel.scalarSize(resolve(className).instanceFieldCount());
        cb.emitAll(A64.loadImm64(0, size));                       // x0 = size (Heap.alloc arg)
        spillLive(cb);                                            // Heap.alloc clobbers x9..
        int w = cb.emit(A64.bl(0));                               // x0 = object base
        callSites.add(new CallSite(w, "vm/Heap.alloc(I)J"));
        reloadLive(cb);
        int t = cb.reserveAddr(1);                                // x1 = &TIB (patched by ImageBuilder)
        tibRefs.add(new TibRef(t, 1, className));
        cb.emit(A64.strx(1, 0, ObjectModel.TIB_OFFSET));          // header.tib = &TIB
        cb.emit(A64.movReg(pushReg(), 0));                        // push the reference
    }

    // ----- branches --------------------------------------------------------
    private void branchZero(CodeBuffer cb, byte[] code, int pos, boolean eq)
    {
        int v = popReg();
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        fixups.add(new Fixup(w, target, eq ? off -> A64.cbz(v, off) : off -> A64.cbnz(v, off)));
        recordDepth(target);
    }

    private void branchCmpZero(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int v = popReg();
        cb.emit(A64.cmpImm(v, 0));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        fixups.add(new Fixup(w, target, off -> A64.bcond(cond, off)));
        recordDepth(target);
    }

    private void branchCmp(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int b = popReg();
        int a = popReg();
        cb.emit(A64.cmpReg(a, b));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        fixups.add(new Fixup(w, target, off -> A64.bcond(cond, off)));
        recordDepth(target);
    }

    /** Record the operand-stack depth on the edge into branch target {@code bc}. */
    private void recordDepth(int bc)
    {
        if (bcDepth[bc] < 0)
        {
            bcDepth[bc] = sp;
        }
        else if (bcDepth[bc] != sp)
        {
            throw new IllegalStateException("inconsistent stack depth at bc=" + bc);
        }
    }

    // ----- calls / intrinsics ----------------------------------------------
    private void lowerInvokeStatic(int cpIndex, CodeBuffer cb)
    {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        if (ref.owner().equals("magic/Magic"))
        {
            lowerIntrinsic(ref, cb);
            return;
        }
        lowerCall(ref, cb, false);
    }

    /** Virtual dispatch through the receiver's TIB vtable. Uses x16 (scratch) for the target. */
    private void lowerInvokeVirtual(int cpIndex, CodeBuffer cb)
    {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        int slot = ClassFile.vtableSlot(ref.owner(), ref.name(), ref.descriptor(), this::resolve);
        int nargs = paramTypes(ref.descriptor()).length + 1;    // receiver + params
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++)
        {
            src[k] = popReg();
        }
        for (int k = 0; k < nargs; k++)
        {
            cb.emit(A64.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
        }
        spillLive(cb);
        cb.emit(A64.ldrx(16, 0, ObjectModel.TIB_OFFSET));       // x16 = receiver.tib
        cb.emit(A64.ldrx(16, 16, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)))); // x16 = code
        cb.emit(A64.blr(16));
        reloadLive(cb);
        if (returnType(ref.descriptor()) != 'V')
        {
            cb.emit(A64.movReg(pushReg(), 0));
        }
    }

    /**
     * Interface dispatch: move args to x0.., then inline-search the receiver's
     * itable directory (Type→dir of {interfaceType, itable}) for the target
     * interface's Type, index the itable by the method's slot, and {@code blr}.
     * Uses x16 (target/code), x17 (walker), x9 (temp) — args in x0..x7 untouched.
     */
    private void lowerInvokeInterface(int cpIndex, CodeBuffer cb)
    {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        int slot = resolve(ref.owner()).interfaceSlot(ref.name(), ref.descriptor());
        int nargs = paramTypes(ref.descriptor()).length + 1;    // receiver + params
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++)
        {
            src[k] = popReg();
        }
        for (int k = 0; k < nargs; k++)
        {
            cb.emit(A64.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
        }
        spillLive(cb);

        interfaceRefs.add(new TypeRef(cb.reserveAddr(16), 16, ref.owner()));        // x16 = &interfaceType
        cb.emit(A64.ldrx(17, 0, ObjectModel.TIB_OFFSET));                           // x17 = receiver.tib
        cb.emit(A64.ldrx(17, 17, ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT))); // x17 = Type
        cb.emit(A64.ldrx(17, 17, ObjectModel.TYPE_ITABLE_DIR_OFFSET));              // x17 = itable dir
        int search = cb.wordCount();
        cb.emit(A64.ldrx(9, 17, ObjectModel.ITABLE_ENTRY_IFACE_OFFSET));            // x9 = entry.interfaceType
        cb.emit(A64.cmpReg(9, 16));
        int beq = cb.emit(A64.bcond(A64.EQ, 0));                                    // found?
        cb.emit(A64.addImm(17, 17, ObjectModel.ITABLE_ENTRY_SIZE));                 // next entry
        cb.emit(A64.b((search - cb.wordCount()) * 4));                              // loop
        int found = cb.wordCount();
        cb.set(beq, A64.bcond(A64.EQ, (found - beq) * 4));
        cb.emit(A64.ldrx(17, 17, ObjectModel.ITABLE_ENTRY_TABLE_OFFSET));          // x17 = itable
        cb.emit(A64.ldrx(16, 17, slot * ObjectModel.WORD));                        // x16 = code addr
        cb.emit(A64.blr(16));
        reloadLive(cb);
        if (returnType(ref.descriptor()) != 'V')
        {
            cb.emit(A64.movReg(pushReg(), 0));
        }
    }

    private void lowerInvokeSpecial(int cpIndex, CodeBuffer cb)
    {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        if (ClassFile.isRoot(ref.owner()) && ref.name().equals("<init>"))
        {
            popReg();                                            // super() into a JDK class — discard receiver
            return;
        }
        lowerCall(ref, cb, true);                                // constructor: receiver is arg0
    }

    /**
     * athrow: stash the exception, then for each covering try/catch entry test the
     * thrown type against the catch type ({@code VM.instanceOf}) and branch to the
     * handler (exception on the operand stack). No matching handler in this method
     * halts — cross-method unwinding is not implemented yet.
     */
    private void athrow(CodeBuffer cb, int pos)
    {
        int athrowStart = cb.wordCount();
        emitStoreStatic(cb, EXCEPTION_KEY, popReg());            // $exception = ref
        for (ClassFile.ExceptionEntry en : exceptions)
        {
            if (en.startPc() > pos || pos >= en.endPc())
            {
                continue;
            }
            if (en.catchType() == 0)
            {
                emitCatch(cb, en.handlerPc());    // finally / catch-all
                return;
            }
            int obj = pushReg();
            emitLoadStatic(cb, EXCEPTION_KEY, obj);
            int t = pushReg();
            typeRefs.add(new TypeRef(cb.reserveAddr(t), t, cf.classAt(en.catchType())));
            emitCall(cb, "vm/VM.instanceOf(JJ)I", "(JJ)I", false);  // (exc, catchType) -> int
            int cond = popReg();
            int skip = cb.emit(A64.cbz(cond, 0));
            emitCatch(cb, en.handlerPc());                       // matched
            cb.set(skip, A64.cbz(cond, (cb.wordCount() - skip) * 4));
        }
        // no local handler: unwind the stack — unwind(exc, thisPC, SP)
        int exc = pushReg();
        emitLoadStatic(cb, EXCEPTION_KEY, exc);
        int pc = pushReg();
        cb.patchAddr(cb.reserveAddr(pc), pc, cb.pcAt(athrowStart)); // a PC inside this method
        int sp = pushReg();
        cb.emit(A64.movFromSp(sp));
        emitCall(cb, "vm/VM.unwind(JJJ)V", "(JJJ)V", false);
        emitHalt(cb);                                            // unwind never returns
    }

    /** Push the pending exception and branch to a handler (which expects it at depth 1). */
    private void emitCatch(CodeBuffer cb, int handlerPc)
    {
        emitLoadStatic(cb, EXCEPTION_KEY, pushReg());
        int w = cb.emit(A64.b(0));
        fixups.add(new Fixup(w, handlerPc, A64::b));
        recordDepth(handlerPc);
        sp = 0;                                                  // fall-through (next check) resumes empty
    }

    private void emitHalt(CodeBuffer cb)
    {
        int h = cb.emit(A64.wfe());
        cb.emit(A64.b((h - cb.wordCount()) * 4));                // spin
    }

    /** Magic.gc(): spill x19..x28 (+LR) so live refs are scannable, call the collector, restore. */
    private void lowerGc(CodeBuffer cb)
    {
        int frame = 96;                                          // 10 locals (80) + LR (8), 16-aligned
        cb.emit(A64.subImm(31, 31, frame));
        cb.emit(A64.strx(30, 31, 80));                          // save LR (we make a call)
        for (int i = 0; i < 10; i++)
        {
            cb.emit(A64.strx(19 + i, 31, i * 8));    // spill x19..x28
        }
        cb.emit(A64.movFromSp(0));                              // x0 = scanFrom (bottom of spilled regs)
        int w = cb.emit(A64.bl(0));
        callSites.add(new CallSite(w, "vm/VM.gcCollect(J)V"));
        for (int i = 0; i < 10; i++)
        {
            cb.emit(A64.ldrx(19 + i, 31, i * 8));    // restore
        }
        cb.emit(A64.ldrx(30, 31, 80));
        cb.emit(A64.addImm(31, 31, frame));
    }

    /** A real call: args to x0.. (receiver first if any), BL placeholder, result from x0. */
    private void lowerCall(ClassFile.MemberRef ref, CodeBuffer cb, boolean hasReceiver)
    {
        emitCall(cb, key(ref.owner(), ref.name(), ref.descriptor()), ref.descriptor(), hasReceiver);
    }

    /** Emit a direct call to {@code calleeKey}: args to x0.., BL placeholder, result from x0. */
    private void emitCall(CodeBuffer cb, String calleeKey, String descriptor, boolean hasReceiver)
    {
        int nargs = paramTypes(descriptor).length + (hasReceiver ? 1 : 0);
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++)
        {
            src[k] = popReg();    // src[0] = last arg (top of stack)
        }
        for (int k = 0; k < nargs; k++)
        {
            cb.emit(A64.movReg(nargs - 1 - k, src[k]));    // -> x(argIndex)
        }
        spillLive(cb);                                           // preserve operand values below the args
        int w = cb.emit(A64.bl(0));
        callSites.add(new CallSite(w, calleeKey));
        reloadLive(cb);
        if (returnType(descriptor) != 'V')
        {
            cb.emit(A64.movReg(pushReg(), 0));
        }
    }

    // ----- arrays: [header][length @16][elements @24], element = base + index<<scale -----
    private void lowerNewArray(int atype, CodeBuffer cb)
    {
        loadConst(cb, arrayElemSize(atype));                     // push elemSize
        emitCall(cb, "vm/Heap.allocArray(II)J", "(II)J", false); // (length, elemSize) -> ref
    }

    private void arrayLength(CodeBuffer cb)
    {
        int arr = popReg();
        int r = pushReg();
        cb.emit(A64.ldrx(r, arr, ObjectModel.ARRAY_LENGTH_OFFSET));
    }

    private void arrayLoad(CodeBuffer cb, int scale)
    {
        int index = popReg(), arr = popReg(), r = pushReg();     // r == arr's register
        cb.emit(A64.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64.addRegLsl(arr, arr, index, scale));          // arr = &elem[index]
        cb.emit(scale == 0 ? A64.ldrb(r, arr, 0)                 // byte (zero-ext, ASCII)
                : scale == 2 ? A64.ldrsw(r, arr, 0)              // int (sign-ext)
                : A64.ldrx(r, arr, 0));                          // long / ref
    }

    private void arrayStore(CodeBuffer cb, int scale)
    {
        int val = popReg();
        int index = popReg();
        int arr = popReg();
        cb.emit(A64.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64.addRegLsl(arr, arr, index, scale));
        cb.emit(scale == 0 ? A64.strb(val, arr, 0) : scale == 2 ? A64.strw(val, arr, 0) : A64.strx(val, arr, 0));
    }

    /** newarray atype -> element size in bytes (JVMS Table 6.5.newarray-A). */
    private static int arrayElemSize(int atype)
    {
        return switch (atype)
        {
        case 4, 8 -> 1;              // boolean, byte
        case 5, 9 -> 2;              // char, short
        case 6, 10 -> 4;             // float, int
        case 7, 11 -> 8;             // double, long
            default -> throw new UnsupportedOperationException("bad newarray atype " + atype);
        };
    }

    /** Spill operand-stack values (x9..) to the frame so a call can't clobber them. */
    private void spillLive(CodeBuffer cb)
    {
        for (int i = 0; i < sp; i++)
        {
            cb.emit(A64.strx(OP_BASE + i, 31, spillBase + i * 8));
        }
    }
    private void reloadLive(CodeBuffer cb)
    {
        for (int i = 0; i < sp; i++)
        {
            cb.emit(A64.ldrx(OP_BASE + i, 31, spillBase + i * 8));
        }
    }

    private ClassFile resolve(String owner)
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

    private void lowerIntrinsic(ClassFile.MemberRef ref, CodeBuffer cb)
    {
        String key = ref.name() + ref.descriptor();
        switch (key)
        {
        case "wfe()V"  -> cb.emit(A64.wfe());
        case "isb()V"  -> cb.emit(A64.isb());
        case "dsb()V"  -> cb.emit(A64.dsb());
        case "gc()V"   -> lowerGc(cb);
        case "call0(J)J" ->
            {
                int addr = popReg();
                cb.emit(A64.blr(addr));
                cb.emit(A64.movReg(pushReg(), 0));
            }
            case "call2(JJJ)J" ->                                // addr, a->x1, b->x2, blr, result x0
                {
                    int b = popReg();
                    int a = popReg();
                    int addr = popReg();
                    cb.emit(A64.movReg(16, addr));
                    cb.emit(A64.movReg(1, a));
                    cb.emit(A64.movReg(2, b));
                    cb.emit(A64.blr(16));
                    cb.emit(A64.movReg(pushReg(), 0));
                }
                case "eret()V" -> cb.emit(A64.eret());
        case "dropToEL1()V" -> lowerDropToEL1(cb);

        case "writeHCR_EL2(J)V"     -> cb.emit(A64.msr(A64.HCR_EL2, popReg()));
        case "writeCPTR_EL2(J)V"    -> cb.emit(A64.msr(A64.CPTR_EL2, popReg()));
        case "writeCNTHCTL_EL2(J)V" -> cb.emit(A64.msr(A64.CNTHCTL_EL2, popReg()));
        case "writeCNTVOFF_EL2(J)V" -> cb.emit(A64.msr(A64.CNTVOFF_EL2, popReg()));
        case "writeSCTLR_EL1(J)V"   -> cb.emit(A64.msr(A64.SCTLR_EL1, popReg()));
        case "writeSPSR_EL2(J)V"    -> cb.emit(A64.msr(A64.SPSR_EL2, popReg()));
        case "writeELR_EL2(J)V"     -> cb.emit(A64.msr(A64.ELR_EL2, popReg()));
        case "writeCPACR_EL1(J)V"   -> cb.emit(A64.msr(A64.CPACR_EL1, popReg()));
        case "writeSP(J)V"          -> cb.emit(A64.movToSp(popReg()));
        case "readSP()J"            -> cb.emit(A64.movFromSp(pushReg()));
        case "resume(JJJ)V" ->                               // exc->x9, SP=sp, br pc (no return)
            {
                int exc = popReg();
                int spv = popReg();
                int pc = popReg();
                cb.emit(A64.movReg(16, pc));                     // target -> scratch (x9 gets clobbered next)
                cb.emit(A64.movReg(9, exc));                     // exception -> handler's stack slot
                cb.emit(A64.movToSp(spv));
                cb.emit(A64.br(16));
            }

            case "store32(JI)V" ->
                {
                    int val = popReg();
                    int addr = popReg();
                    cb.emit(A64.strw(val, addr, 0));
                }
                case "store8(JI)V"  ->
                    {
                        int val = popReg();
                        int addr = popReg();
                        cb.emit(A64.strb(val, addr, 0));
                    }
                    case "store64(JJ)V" ->
                        {
                            int val = popReg();
                            int addr = popReg();
                            cb.emit(A64.strx(val, addr, 0));
                        }
                        case "load32(J)I"   ->
                            {
                                int addr = popReg();
                                int r = pushReg();
                                cb.emit(A64.ldrw(r, addr, 0));
                            }
                            case "load8(J)I"    ->
                                {
                                    int addr = popReg();
                                    int r = pushReg();
                                    cb.emit(A64.ldrb(r, addr, 0));
                                }
                                case "load64(J)J"   ->
                                    {
                                        int addr = popReg();
                                        int r = pushReg();
                                        cb.emit(A64.ldrx(r, addr, 0));
                                    }

                                    case "bytes(Ljava/lang/String;)[B" ->
                                    {
                                        /* no-op: the operand is already an interned byte[] ref */;
                                    }

                                            default -> throw new UnsupportedOperationException("unknown intrinsic magic/Magic." + key);
        }
    }

    private void lowerDropToEL1(CodeBuffer cb)
    {
        expectEmpty("dropToEL1");
        cb.emit(A64.mrs(0, A64.CurrentEL));
        int tbz = cb.emit(A64.tbz(0, 3, 0));
        set64(cb, 0, 0x8000_0000L);
        cb.emit(A64.msr(A64.HCR_EL2, 0));
        set64(cb, 0, 0x33FFL);
        cb.emit(A64.msr(A64.CPTR_EL2, 0));
        set64(cb, 0, 0x3L);
        cb.emit(A64.msr(A64.CNTHCTL_EL2, 0));
        cb.emit(A64.msr(A64.CNTVOFF_EL2, A64.XZR));
        set64(cb, 0, 0x30D0_0800L);
        cb.emit(A64.msr(A64.SCTLR_EL1, 0));
        set64(cb, 0, 0x3C5L);
        cb.emit(A64.msr(A64.SPSR_EL2, 0));
        int elr = cb.reserveAddr(0);
        cb.emit(A64.msr(A64.ELR_EL2, 0));
        cb.emit(A64.eret());
        int cont = cb.wordCount();
        cb.set(tbz, A64.tbz(0, 3, (cont - tbz) * 4));
        cb.patchAddr(elr, 0, cb.pcAt(cont));
    }

    // ----- descriptor helpers ----------------------------------------------
    private static char[] paramTypes(String descriptor)
    {
        List<Character> out = new ArrayList<>();
        int i = descriptor.indexOf('(') + 1;
        while (descriptor.charAt(i) != ')')
        {
            char c = descriptor.charAt(i);
            if (c == 'L')
            {
                out.add('L');
                i = descriptor.indexOf(';', i) + 1;
            }
            else if (c == '[')
            {
                i++;    // array: skip, dimension folds into element
            }
            else
            {
                out.add(c);
                i++;
            }
        }
        char[] a = new char[out.size()];
        for (int k = 0; k < a.length; k++)
        {
            a[k] = out.get(k);
        }
        return a;
    }
    private static char returnType(String descriptor)
    {
        return descriptor.charAt(descriptor.indexOf(')') + 1);
    }

    private boolean isNonLeaf(byte[] code)
    {
        int pos = 0;
        while (pos < code.length)
        {
            int op = code[pos] & 0xFF;
            if (op == 0xBB || op == 0xBC || op == 0xB6 || op == 0xB9 || op == 0xBF || op == 0xC0 || op == 0xC1)
            {
                return true;    // new/newarray/invokevirtual/invokeinterface/athrow/checkcast/instanceof
            }
            if (op == 0xB8)                                      // invokestatic
            {
                ClassFile.MemberRef ref = cf.memberRef(u2(code, pos + 1));
                if (!ref.owner().equals("magic/Magic"))
                {
                    return true;
                }
                if (ref.name().equals("gc") || ref.name().equals("call0") || ref.name().equals("call2"))
                {
                    return true;    // emit BL/BLR
                }
            }
            if (op == 0xB7)                                      // invokespecial
            {
                ClassFile.MemberRef ref = cf.memberRef(u2(code, pos + 1));
                if (!(ClassFile.isRoot(ref.owner()) && ref.name().equals("<init>")))
                {
                    return true;
                }
            }
            pos += opLen(op, code, pos);
        }
        return false;
    }

    /** Byte length of an opcode — only the ones this compiler emits appear here. */
    private static int opLen(int op, byte[] code, int pos)
    {
        return switch (op)
        {
        case 0x10, 0x12, 0x15, 0x16, 0x19, 0x36, 0x37, 0x3A, 0xBC -> 2; // …/aload/istore/lstore/astore/newarray
        case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E,
                     0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA7,
                     0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB,
                     0xC0, 0xC1 -> 3;                                 // get/putstatic/field/invoke*/new/checkcast/instanceof
        case 0xB9 -> 5;                                       // invokeinterface (idx, count, 0)
            default -> 1;
        };
    }

    private static void set64(CodeBuffer cb, int rd, long v)
    {
        cb.emitAll(A64.loadImm64(rd, v));
    }

    /** ldc/ldc_w: int constant, or a String literal interned as a byte[] object. */
    private void ldc(CodeBuffer cb, int cpIndex)
    {
        if (cf.isStringConst(cpIndex))
        {
            int r = pushReg();
            strRefs.add(new StrRef(cb.reserveAddr(r), r, cf.stringAt(cpIndex)));
        }
        else if (cf.isIntConst(cpIndex))
        {
            loadConst(cb, cf.intAt(cpIndex));
        }
        else
        {
            throw new UnsupportedOperationException("ldc of unsupported constant #" + cpIndex);
        }
    }

    private static int u2(byte[] b, int i)
    {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }
    private static int s2(byte[] b, int i)
    {
        return (short) u2(b, i);
    }
}
