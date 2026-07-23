package vm;

import asm.A64Enc;
import asm.CodeBuffer;
import classfile.ClassReader;
import compiler.Intrinsics;
import compiler.Symbols;
import magic.Magic;
import objectmodel.ObjectModel;

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

    private final int[] staticSite = new int[MAX];  // getstatic/putstatic address-load site
    private final int[] staticReg = new int[MAX];   // ... its destination register
    private final int[] staticClassOff = new int[MAX];  // owner class-name Utf8 offset (in classBytes)
    private final int[] staticNameOff = new int[MAX];   // field-name Utf8 offset
    private int staticN;

    private final int[] tibSite = new int[MAX];     // `new`: TIB address-load site
    private final int[] tibReg = new int[MAX];      // ... its destination register
    private final int[] tibClassOff = new int[MAX]; // ... the class-name Utf8 offset
    private int tibN;

    private final int[] typeSite = new int[MAX];    // instanceof/checkcast: Type address-load site
    private final int[] typeReg = new int[MAX];
    private final int[] typeClassOff = new int[MAX];
    private int typeN;

    private final int[] strSite = new int[MAX];     // ldc-string: interned byte[] address-load site
    private final int[] strReg = new int[MAX];
    private final int[] strUtf8Off = new int[MAX];  // ... the literal's Utf8 body offset (in classBytes)
    private int strN;

    private final int[] ifSite = new int[MAX];      // invokeinterface: interface-Type address-load site
    private final int[] ifReg = new int[MAX];
    private final int[] ifClassOff = new int[MAX];  // ... the interface's class-name Utf8 offset
    private int ifN;

    private final int[] excSite = new int[MAX];     // athrow/catch: in-flight-exception slot address-load site
    private final int[] excReg = new int[MAX];
    private int excN;

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
        tibSite[tibN] = cb.reserveAddr(reg);
        tibReg[tibN] = reg;
        tibClassOff[tibN] = ClassReader.classNameOff(classBytes, cpOff, classCp);
        tibN += 1;
    }
    public void type(CodeBuffer cb, int reg, int classCp)
    {
        typeSite[typeN] = cb.reserveAddr(reg);
        typeReg[typeN] = reg;
        typeClassOff[typeN] = ClassReader.classNameOff(classBytes, cpOff, classCp);
        typeN += 1;
    }
    public void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp)
    {
        ifSite[ifN] = cb.reserveAddr(reg);
        ifReg[ifN] = reg;
        ifClassOff[ifN] = ClassReader.refClassNameOff(classBytes, cpOff, ifaceMethodCp);
        ifN += 1;
    }
    public void staticField(CodeBuffer cb, int reg, int fieldCp)
    {
        staticSite[staticN] = cb.reserveAddr(reg);
        staticReg[staticN] = reg;
        staticClassOff[staticN] = ClassReader.refClassNameOff(classBytes, cpOff, fieldCp);
        staticNameOff[staticN] = ClassReader.refNameOff(classBytes, cpOff, fieldCp);
        staticN += 1;
    }
    public void string(CodeBuffer cb, int reg, int stringCp)
    {
        strSite[strN] = cb.reserveAddr(reg);
        strReg[strN] = reg;
        strUtf8Off[strN] = ClassReader.stringUtf8Off(classBytes, cpOff, stringCp);
        strN += 1;
    }
    public void exceptionSlot(CodeBuffer cb, int reg)
    {
        excSite[excN] = cb.reserveAddr(reg);
        excReg[excN] = reg;
        excN += 1;
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
        byte[] owner = utf8Copy(ClassReader.refClassNameOff(classBytes, cpOff, fieldCp));
        byte[] field = utf8Copy(ClassReader.refNameOff(classBytes, cpOff, fieldCp));
        return MetalClassModel.instanceFieldOffset(owner, field);
    }
    public int objectSize(int classCp)
    {
        byte[] cls = utf8Copy(ClassReader.classNameOff(classBytes, cpOff, classCp));
        return ObjectModel.scalarSize(MetalClassModel.instanceFieldCount(cls));
    }
    public int vtableSlot(int methodCp)
    {
        byte[] recv = utf8Copy(ClassReader.refClassNameOff(classBytes, cpOff, methodCp));
        byte[] name = utf8Copy(ClassReader.refNameOff(classBytes, cpOff, methodCp));
        byte[] desc = utf8Copy(ClassReader.refDescOff(classBytes, cpOff, methodCp));
        return MetalClassModel.vtableSlot(recv, name, desc);
    }
    public int interfaceSlot(int ifaceMethodCp)
    {
        byte[] iface = utf8Copy(ClassReader.refClassNameOff(classBytes, cpOff, ifaceMethodCp));
        byte[] name = utf8Copy(ClassReader.refNameOff(classBytes, cpOff, ifaceMethodCp));
        byte[] desc = utf8Copy(ClassReader.refDescOff(classBytes, cpOff, ifaceMethodCp));
        return MetalClassModel.interfaceMethodSlot(iface, name, desc);
    }
    public boolean isIntrinsicCall(int methodCp)
    {
        return utf8Is(ClassReader.refClassNameOff(classBytes, cpOff, methodCp), Magic.bytes("magic/Magic"));
    }
    public boolean intrinsicEmitsCall(int methodCp)
    {
        return false;   // the memory intrinsics this writer resolves emit no BL/BLR
    }
    public int intrinsicId(int methodCp)
    {
        int n = ClassReader.refNameOff(classBytes, cpOff, methodCp);   // the seven memory intrinsics
        if (utf8Is(n, Magic.bytes("bytes")))
        {
            return Intrinsics.BYTES;
        }
        if (utf8Is(n, Magic.bytes("load8")))
        {
            return Intrinsics.LOAD8;
        }
        if (utf8Is(n, Magic.bytes("load32")))
        {
            return Intrinsics.LOAD32;
        }
        if (utf8Is(n, Magic.bytes("load64")))
        {
            return Intrinsics.LOAD64;
        }
        if (utf8Is(n, Magic.bytes("store8")))
        {
            return Intrinsics.STORE8;
        }
        if (utf8Is(n, Magic.bytes("store32")))
        {
            return Intrinsics.STORE32;
        }
        if (utf8Is(n, Magic.bytes("store64")))
        {
            return Intrinsics.STORE64;
        }
        return 0;
    }
    public boolean isSkippableInit(int methodCp)
    {
        // The super()/root-class <init> (e.g. Object.<init>) emits no call; a real
        // same-image ctor (Cell.<init>) is a normal call to place.
        return MetalClassModel.isRoot(utf8Copy(ClassReader.refClassNameOff(classBytes, cpOff, methodCp)));
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
    int callClassOff(int i)
    {
        return callClassOff[i];
    }
    int callNameOff(int i)
    {
        return callNameOff[i];
    }
    int callDescOff(int i)
    {
        return callDescOff[i];
    }
    int staticCount()
    {
        return staticN;
    }
    int staticSiteWord(int i)
    {
        return staticSite[i];
    }
    int staticReg(int i)
    {
        return staticReg[i];
    }
    int staticClassOff(int i)
    {
        return staticClassOff[i];
    }
    int staticNameOff(int i)
    {
        return staticNameOff[i];
    }
    int tibCount()
    {
        return tibN;
    }
    int tibSiteWord(int i)
    {
        return tibSite[i];
    }
    int tibReg(int i)
    {
        return tibReg[i];
    }
    int tibClassOff(int i)
    {
        return tibClassOff[i];
    }
    int typeCount()
    {
        return typeN;
    }
    int typeSiteWord(int i)
    {
        return typeSite[i];
    }
    int typeReg(int i)
    {
        return typeReg[i];
    }
    int typeClassOff(int i)
    {
        return typeClassOff[i];
    }
    int strCount()
    {
        return strN;
    }
    int strSiteWord(int i)
    {
        return strSite[i];
    }
    int strReg(int i)
    {
        return strReg[i];
    }
    int strUtf8Off(int i)
    {
        return strUtf8Off[i];
    }
    int ifCount()
    {
        return ifN;
    }
    int ifSiteWord(int i)
    {
        return ifSite[i];
    }
    int ifReg(int i)
    {
        return ifReg[i];
    }
    int ifClassOff(int i)
    {
        return ifClassOff[i];
    }
    int excCount()
    {
        return excN;
    }
    int excSiteWord(int i)
    {
        return excSite[i];
    }
    int excReg(int i)
    {
        return excReg[i];
    }
    int helperCount()
    {
        return helperN;
    }
    int helperSiteWord(int i)
    {
        return helperSite[i];
    }
    int helperId(int i)
    {
        return helperId[i];
    }
    boolean callNameIs(int i, byte[] want)
    {
        return utf8Is(callNameOff[i], want);
    }
    boolean failed()
    {
        return failed;
    }

    /** Copy the Utf8 entry at {@code classBytes[off]} onto a fresh heap byte array. */
    private byte[] utf8Copy(int off)
    {
        int len = ClassReader.u2(classBytes, off);
        byte[] out = new byte[len];
        int j = 0;
        while (j < len)
        {
            out[j] = (byte) ClassReader.u1(classBytes, off + 2 + j);
            j += 1;
        }
        return out;
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
