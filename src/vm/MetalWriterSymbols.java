package vm;

import asm.A64Enc;
import asm.CodeBuffer;
import classfile.ClassReader;
import compiler.Symbols;
import magic.Magic;

/**
 * The metal writer's {@link Symbols} seam (PLAN.md §M5.5c step 3) — the on-metal twin of
 * {@code compiler.WriterSymbols}. Where {@link MetalSymbols} (the JIT) resolves every
 * reference to a live address <em>now</em> and records nothing, AOT layout needs the
 * opposite: emit a fixed-width placeholder and <em>record the relocation site</em> so the
 * layout driver can patch it once addresses are assigned. Same {@code Baseline} core drives
 * both — only resolution differs.
 *
 * <p>Unlike {@link MetalSymbols}, this reads its cp references from the {@code (classBytes,
 * cpOff)} it is constructed with (via the shared {@link ClassReader}), not from Loader's
 * current-compilation globals — the writer compiles many classes without loading them.
 *
 * <p>This slice records call sites with their callee identity (offsets into
 * {@code classBytes}); address-load sites are reserved at the right width but their identity
 * resolution is fleshed out with the layout driver.
 */
final class MetalWriterSymbols implements Symbols
{
    private static final int MAX = 256;

    private final byte[] classBytes;
    private final int[] cpOff;

    private final int[] callSite = new int[MAX];    // word index of each recorded BL placeholder
    private final int[] callClassOff = new int[MAX];// callee's class-name Utf8 offset (in classBytes)
    private final int[] callNameOff = new int[MAX]; // callee's method-name Utf8 offset
    private final int[] callDescOff = new int[MAX]; // callee's descriptor Utf8 offset
    private int callN;

    private final int[] helperSite = new int[MAX];  // BL placeholders to synthesised helpers
    private final int[] helperId = new int[MAX];
    private int helperN;

    private final int[] addrSite = new int[MAX];    // 2-word address-load placeholders
    private final int[] addrReg = new int[MAX];
    private int addrN;

    private boolean failed;

    MetalWriterSymbols(byte[] classBytes, int[] cpOff)
    {
        this.classBytes = classBytes;
        this.cpOff = cpOff;
    }

    // ----- emit: a fixed-width placeholder + a recorded site (vs MetalSymbols' resolve) -----

    public void call(CodeBuffer cb, int methodCp)
    {
        callSite[callN] = cb.emit(A64Enc.bl(0));
        callClassOff[callN] = ClassReader.refClassNameOff(classBytes, cpOff, methodCp);
        callNameOff[callN] = ClassReader.refNameOff(classBytes, cpOff, methodCp);
        callDescOff[callN] = ClassReader.refDescOff(classBytes, cpOff, methodCp);
        callN += 1;
    }
    public void callHelper(CodeBuffer cb, int helper)
    {
        helperSite[helperN] = cb.emit(A64Enc.bl(0));
        helperId[helperN] = helper;
        helperN += 1;
    }
    public void tib(CodeBuffer cb, int reg, int classCp)
    {
        reserve(cb, reg);
    }
    public void type(CodeBuffer cb, int reg, int classCp)
    {
        reserve(cb, reg);
    }
    public void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp)
    {
        reserve(cb, reg);
    }
    public void staticField(CodeBuffer cb, int reg, int fieldCp)
    {
        reserve(cb, reg);
    }
    public void string(CodeBuffer cb, int reg, int stringCp)
    {
        reserve(cb, reg);
    }
    public void exceptionSlot(CodeBuffer cb, int reg)
    {
        reserve(cb, reg);
    }

    /** Reserve a 2-word address-load placeholder (MOVZ+MOVK width), record its site. */
    private void reserve(CodeBuffer cb, int reg)
    {
        addrSite[addrN] = cb.reserveAddr(reg);
        addrReg[addrN] = reg;
        addrN += 1;
    }

    // ----- queries: resolve from classBytes; the writer's identity work lands with layout -----

    public int fieldOffset(int fieldCp)
    {
        return 0;
    }
    public int objectSize(int classCp)
    {
        return 0;
    }
    public int vtableSlot(int methodCp)
    {
        return 0;
    }
    public int interfaceSlot(int ifaceMethodCp)
    {
        return 0;
    }
    public boolean isIntrinsicCall(int methodCp)
    {
        return utf8Is(ClassReader.refClassNameOff(classBytes, cpOff, methodCp), Magic.bytes("magic/Magic"));
    }
    public boolean intrinsicEmitsCall(int methodCp)
    {
        return false;
    }
    public int intrinsicId(int methodCp)
    {
        return 0;
    }
    public boolean isSkippableInit(int methodCp)
    {
        return false;
    }
    public void fail(int reason, int a, int b)
    {
        failed = true;   // record, don't hang: the marker asserts a clean compile
    }

    // ----- accessors for the marker / (later) the layout driver -----

    int callCount()
    {
        return callN;
    }
    int callSiteWord(int i)
    {
        return callSite[i];
    }
    boolean callNameIs(int i, byte[] want)
    {
        return utf8Is(callNameOff[i], want);
    }
    boolean failed()
    {
        return failed;
    }

    /** Whether the Utf8 entry at {@code classBytes[off]} equals the plain bytes {@code want}. */
    private boolean utf8Is(int off, byte[] want)
    {
        int len = ClassReader.u2(classBytes, off);
        if (len != want.length)
        {
            return false;
        }
        int j = 0;
        while (j < len)
        {
            if (ClassReader.u1(classBytes, off + 2 + j) != (want[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }
}
