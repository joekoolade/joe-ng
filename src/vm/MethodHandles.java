/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import asm.A64Enc;
import board.bcm2711.Uart;
import magic.Address;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * java.lang.invoke on the metal: the MethodHandle signature-polymorphic call, MemberName/LambdaForm targets and
 * VarHandle field offsets. Split out of {@link Loader}.
 *
 * <p>{@code mh.invokeExact(a, b)}, {@code mh.invokeBasic(...)} and {@code MethodHandle.linkToStatic(..., mn)} have
 * no method of the site's descriptor to call: HotSpot links each to an intrinsic. Here every such site is compiled
 * as an ordinary call of its own descriptor -- the arguments land in x0.. exactly as they would for a real method
 * -- to the MH trampoline, with x17 = the site. The trampoline saves x0..x15, asks {@code mhResolve} for the target,
 * restores, and tail-branches, so the target returns straight to the site. What makes that work is the shape of a
 * LambdaForm method: it takes the handle as its FIRST parameter and the call's arguments after it, which is
 * precisely the register layout of {@code mh.invokeBasic(args)}.
 */
final class MethodHandles
{
    private MethodHandles()
    {
    }

    static long mhTrampAddr;
    static long mhTrampLo;
    static long mhTrampHi;
    /** Where the last {@link #buildSaveAreaTramp}'s frame is torn down (its unwind range ends there). */
    static long saveTrampHi;
    static long mhOffCsTarget = -1L;
    // Field offsets of the four objects a dispatch walks, looked up once against each DECLARING class (the field
    // registry keys a field by the class that declares it, so DirectMethodHandle would not find `form`).
    private static long mhOffForm = -1L;
    private static long mhOffVmentry = -1L;
    private static long mhOffMethod = -1L;
    private static long mhOffVmtarget = -1L;
    /** Each linker's reflection index, found once (it is compiled on demand: nothing in Java calls it). */
    private static int mhLinkExactIdx = -1;
    private static int mhLinkGenericIdx = -1;

    /** True if the *ref at {@code idx} is a MethodHandle signature-polymorphic method the VM links itself. */
    static boolean isMhSigPoly(int idx)
    {
        if (!Utf8.utf8IsAtBase(Loader.gbase, Loader.refClassNameOff(idx), Magic.bytes("java/lang/invoke/MethodHandle")))
        {
            return false;
        }
        int n = Loader.mrefNameOff(idx);
        return Utf8.utf8IsAtBase(Loader.gbase, n, Magic.bytes("invokeExact"))
                || Utf8.utf8IsAtBase(Loader.gbase, n, Magic.bytes("invoke"))
                || Utf8.utf8IsAtBase(Loader.gbase, n, Magic.bytes("invokeBasic"))
                || Utf8.utf8IsAtBase(Loader.gbase, n, Magic.bytes("linkToStatic"))
                || Utf8.utf8IsAtBase(Loader.gbase, n, Magic.bytes("linkToSpecial"))
                || Utf8.utf8IsAtBase(Loader.gbase, n, Magic.bytes("linkToVirtual"))
                || Utf8.utf8IsAtBase(Loader.gbase, n, Magic.bytes("linkToInterface"));
    }

    /**
     * The MH trampoline: {@link #virtualTramp}'s shape, with the resolver handed the SAVE AREA (x0 = the address
     * of the saved x0) rather than the receiver, because a linkTo* target is named by the LAST argument and an
     * invoke's handle may be REPLACED (invoke adapts it), so mhResolve has to read and write the saved registers.
     */
    static Address mhTramp()
    {
        if (mhTrampAddr == 0L)
        {
            long buf = buildSaveAreaTramp(Address.fromLong(VM.mhResolveAddr), 4).toLong();
            mhTrampLo = buf + 4L;
            mhTrampHi = saveTrampHi;
            mhTrampAddr = buf;
            Loader.registerTrampFrames();
        }
        return Address.fromLong(mhTrampAddr);
    }

    /**
     * A resolve trampoline whose resolver is handed the SAVE AREA: save x0..x15 + LR (LR at [sp+0], where VM.unwind
     * reads it), call {@code ra(&saved x0, x17)}, restore and tail-branch to what it answered -- so the target
     * returns straight to the call site and an exception thrown by the resolver unwinds through this frame.
     */
    static Address buildSaveAreaTramp(Address ra, int guardKind)
    {
        Address buf = Heap.allocCode(256);
        int w = 0;
        buf.plus(w * 4L).store(A64Enc.subImm(Loader.STUB_SP, Loader.STUB_SP, Loader.STUB_FRAME));
        w += 1;
        int r = 0;
        while (r <= Loader.STUB_SAVE_HI)
        {
            buf.plus(w * 4L).store(A64Enc.strx(r, Loader.STUB_SP, Loader.STUB_SAVE0 + r * ObjectModel.WORD));
            w += 1;
            r += 1;
        }
        buf.plus(w * 4L).store(A64Enc.strx(Loader.STUB_LR, Loader.STUB_SP, Loader.STUB_LR_OFF)); // LR at [sp+0] for VM.unwind
        w += 1;
        buf.plus(w * 4L).store(A64Enc.addImm(0, Loader.STUB_SP, Loader.STUB_SAVE0)); // x0 = &saved x0
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movReg(1, 17)); // x1 = site idx
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (ra.toLong() & 0xFFFF), 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ra.toLong() >> 16) & 0xFFFF), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ra.toLong() >> 32) & 0xFFFF), 2));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.blr(16)); // x0 = target
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movReg(16, 0));
        w += 1;
        w = Loader.emitTailGuard(buf, w, guardKind);
        r = 0;
        while (r <= Loader.STUB_SAVE_HI)
        {
            buf.plus(w * 4L).store(A64Enc.ldrx(r, Loader.STUB_SP, Loader.STUB_SAVE0 + r * ObjectModel.WORD));
            w += 1;
            r += 1;
        }
        buf.plus(w * 4L).store(A64Enc.ldrx(Loader.STUB_LR, Loader.STUB_SP, Loader.STUB_LR_OFF));
        w += 1;
        saveTrampHi = buf.toLong() + w * 4L;
        buf.plus(w * 4L).store(A64Enc.addImm(Loader.STUB_SP, Loader.STUB_SP, Loader.STUB_FRAME));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.br(16)); // tail-call
        w += 1;
        Heap.publishCode(buf, buf.plus(w * 4L));
        return buf;
    }

    /** Pull java/lang/invoke/MethodHandleNatives in when a dynamic site is the program's first use of it. */
    static void ensureMhnLoaded()
    {
        VM.loaderLock(VM.LOCK_REFLECT);
        try
        {
            Address t = Loader.loadClassIncremental(Magic.bytes("java/lang/invoke/MethodHandleNatives"));
            if (!t.isZero())
            {
                Loader.ensureInitOfType(t);
            }
        }
        finally
        {
            VM.loaderUnlock();
        }
    }

    /** {@code MethodHandleNatives.classBytes0}: a guest copy of the class file the class was loaded from, or 0. */
    static long classFileCopy(Address type)
    {
        int reg = Loader.regOfType(type);
        if (reg < 0 || Loader.clTab[reg] == null)
        {
            return 0L;
        }
        Address base = Loader.clTab[reg].base;
        if (base.isZero() || u4(base) != 0xCAFEBABE)
        {
            return 0L;
        }
        return Loader.guestBytes(VM.heapBytes(base, classFileLength(base)));
    }

    /** The length of the class file at {@code base}, by walking its structure (JVMS 4.1). */
    private static int classFileLength(Address base)
    {
        int count = u2(base.plus(8L));
        Address p = base.plus(10L);
        int i = 1;
        while (i < count)
        {
            int tag = u1(p);
            if (tag == 1)
            {
                p = p.plus(3L + u2(p.plus(1L)));
            }
            else if (tag == 5 || tag == 6)
            {
                p = p.plus(9L);
                i += 1;                                 // a long or double takes two slots
            }
            else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20)
            {
                p = p.plus(3L);
            }
            else if (tag == 15)
            {
                p = p.plus(4L);
            }
            else
            {
                p = p.plus(5L);
            }
            i += 1;
        }
        p = p.plus(6L);                                        // access_flags, this_class, super_class
        p = p.plus(2L + 2L * u2(p));                           // interfaces
        int f = u2(p);
        p = p.plus(2L);
        while (f > 0)
        {
            p = Loader.skipAttributes(p.plus(8L), u2(p.plus(6L)));
            f -= 1;
        }
        int m = u2(p);
        p = p.plus(2L);
        while (m > 0)
        {
            p = Loader.skipAttributes(p.plus(8L), u2(p.plus(6L)));
            m -= 1;
        }
        p = Loader.skipAttributes(p.plus(2L), u2(p));
        return p.diff(base).toInt();
    }

    /**
     * The MH trampoline's resolver: the code {@code save}'s call should run. {@code save} is the trampoline's save
     * area (x_r at {@code save + r*8}); the site's NAME is the kind.
     *
     * <ul>
     *   <li>{@code invokeBasic}: the handle's LambdaForm -- {@code mh.form.vmentry}, a MemberName.
     *   <li>{@code linkToStatic}/{@code linkToSpecial}: the trailing MemberName itself. The callee ignores the
     *       extra register it arrives in, which is HotSpot's "drop the trailing argument" for free.
     *   <li>{@code linkToVirtual}/{@code linkToInterface}: the MemberName's method dispatched on the receiver in
     *       x0, through the late-virtual resolver (a site registered for that method).
     *   <li>{@code invokeExact}/{@code invoke}: the guest linker first -- it throws WrongMethodTypeException, or
     *       for invoke returns an adapted handle, which replaces x0 in the save area -- then as invokeBasic.
     * </ul>
     *
     * Answers 0 after naming what failed; the trampoline's tail guard then halts at the call site.
     */
    static long mhResolve(long save, int idx)
    {
        if (save == 0L || Loader.vsName == null || idx < 0 || idx >= Loader.vsCount)
        {
            return 0L;                                  // boot force-compile passes 0
        }
        Address nameU = Loader.vsName.get(idx);
        if (Utf8.utf8IsAtBase(nameU, 0, Magic.bytes("invokeBasic")))
        {
            return lambdaFormTarget(Address.fromLong(save).loadObjectReference());
        }
        boolean exact = Utf8.utf8IsAtBase(nameU, 0, Magic.bytes("invokeExact"));
        if (exact || Utf8.utf8IsAtBase(nameU, 0, Magic.bytes("invoke")))
        {
            long linked = mhLink(Address.fromLong(save).loadLong(), Loader.vsDesc.get(idx), exact);
            Address.fromLong(save).store(linked);                // invoke may have adapted the handle: run the adapted one
            return lambdaFormTarget(ObjectReference.fromLong(linked));
        }
        int last = descParamCountAt(Loader.vsDesc.get(idx)) - 1;   // linkTo* is static: the MemberName is the last argument
        if (last < 0 || last > Loader.STUB_SAVE_HI - 1)
        {
            Uart.write(Magic.bytes("\n  MH LINK: unsupported arity for "));
            Loader.printNameAt(nameU, 0);
            Loader.printNameAt(Loader.vsDesc.get(idx), 0);
            Uart.putc(0x0A);
            return 0L;
        }
        ObjectReference mn = Address.fromLong(save).plus(last * 8L).loadObjectReference();
        if (memberNameEntryQuiet(mn) == -10L)
        {
            // The MemberName IS MethodHandle.invokeBasic (a handle whose target is that intrinsic): run
            // invokeBasic on the receiver, which is the handle in x0 -- the trailing MemberName is dropped.
            return lambdaFormTarget(Address.fromLong(save).loadObjectReference());
        }
        if (Utf8.utf8IsAtBase(nameU, 0, Magic.bytes("linkToStatic")) || Utf8.utf8IsAtBase(nameU, 0, Magic.bytes("linkToSpecial")))
        {
            return memberNameTarget(mn);
        }
        return memberNameDispatch(Address.fromLong(save).loadObjectReference(), mn);
    }

    /** The code of {@code mh}'s LambdaForm: {@code mh.form.vmentry}'s method. */
    static long lambdaFormTarget(ObjectReference mh)
    {
        if (mh.isNull())
        {
            return 0L;
        }
        if (mhOffForm < 0L)
        {
            mhOffForm = declaredFieldOffset(Magic.bytes("java/lang/invoke/MethodHandle"), Magic.bytes("form"));
            mhOffVmentry = declaredFieldOffset(Magic.bytes("java/lang/invoke/LambdaForm"), Magic.bytes("vmentry"));
        }
        ObjectReference form = mh.toAddress().plus(mhOffForm).loadObjectReference();
        if (form.isNull())
        {
            Uart.write(Magic.bytes("\n  MH LINK: a method handle with no LambdaForm\n"));
            return 0L;
        }
        return memberNameTarget(form.toAddress().plus(mhOffVmentry).loadObjectReference());
    }

    /** The current body of the method a resolved MemberName names: its ResolvedMethodName's registry index. */
    private static long memberNameTarget(ObjectReference mn)
    {
        long e = memberNameEntry(mn);
        if (e < 0L)
        {
            return 0L;
        }
        RVMMethod m = Loader.reflEntry((int) e);
        if (m == null || m.buf == 0L)
        {
            Uart.write(Magic.bytes("\n  MH LINK: no body for "));
            if (m != null)
            {
                Loader.printNameAt(m.base.plus(m.classOff), 0);
                Uart.putc(0x2E);
                Loader.printNameAt(m.base.plus(m.nameOff), 0);
                Loader.printNameAt(m.base.plus(m.descOff), 0);
            }
            Uart.putc(0x0A);
            return 0L;
        }
        return m.buf;
    }

    /** The reflection index a MemberName's ResolvedMethodName carries, or -1 after naming why there is none. */
    private static long memberNameEntry(ObjectReference mn)
    {
        if (mhOffMethod < 0L)
        {
            mhOffMethod = declaredFieldOffset(Magic.bytes("java/lang/invoke/MemberName"), Magic.bytes("method"));
            mhOffVmtarget = declaredFieldOffset(Magic.bytes("java/lang/invoke/ResolvedMethodName"), Magic.bytes("vmtarget"));
        }
        ObjectReference rmn = mn.isNull() ? ObjectReference.nullReference() : mn.toAddress().plus(mhOffMethod).loadObjectReference();
        long vt = rmn.isNull() ? -1L : rmn.toAddress().plus(mhOffVmtarget).loadLong();
        if (vt < 0L)
        {
            Uart.write(Magic.bytes("\n  MH LINK: an unresolved MemberName (no vmtarget)\n"));
            return -1L;
        }
        return vt;
    }

    /** A MemberName's vmtarget without any report: a registry index, a negative intrinsic code, or -1. */
    private static long memberNameEntryQuiet(ObjectReference mn)
    {
        if (mhOffMethod < 0L)
        {
            mhOffMethod = declaredFieldOffset(Magic.bytes("java/lang/invoke/MemberName"), Magic.bytes("method"));
            mhOffVmtarget = declaredFieldOffset(Magic.bytes("java/lang/invoke/ResolvedMethodName"), Magic.bytes("vmtarget"));
        }
        ObjectReference rmn = mn.isNull() ? ObjectReference.nullReference() : mn.toAddress().plus(mhOffMethod).loadObjectReference();
        return rmn.isNull() ? -1L : rmn.toAddress().plus(mhOffVmtarget).loadLong();
    }

    /** linkToVirtual/linkToInterface: the MemberName's method as the RECEIVER's class runs it. */
    private static long memberNameDispatch(ObjectReference recv, ObjectReference mn)
    {
        long e = memberNameEntry(mn);
        RVMMethod m = e < 0L ? null : Loader.reflEntry((int) e);
        if (m == null || recv.isNull())
        {
            return 0L;
        }
        int site;
        VM.loaderLock(VM.LOCK_REFLECT);                 // the site table is the compiler's, written under the lock
        try
        {
            site = Loader.siteIndexFor(m.base.plus(m.nameOff), m.base.plus(m.descOff), m.base.plus(m.classOff));
        }
        finally
        {
            VM.loaderUnlock();
        }
        return site < 0 ? 0L : Loader.virtualResolve(recv, site);
    }

    /** Run the guest linker (MethodHandleNatives.linkExact / linkGeneric) on {@code mh} and the site's descriptor. */
    private static long mhLink(long mh, Address descU, boolean exact)
    {
        int li = exact ? mhLinkExactIdx : mhLinkGenericIdx;
        if (li < 0)
        {
            int reg = Loader.classRegByNameBytes(Magic.bytes("java/lang/invoke/MethodHandleNatives"));
            if (reg < 0)
            {
                Uart.write(Magic.bytes("\n  MH LINK: java/lang/invoke/MethodHandleNatives is not loaded\n"));
                return 0L;
            }
            li = Loader.methodResolveExact(Loader.clTab[reg].type,
                    ObjectReference.fromLong(Loader.guestBytes(exact ? Magic.bytes("linkExact") : Magic.bytes("linkGeneric"))),
                    ObjectReference.fromLong(Loader.guestBytes(Magic.bytes("(Ljava/lang/invoke/MethodHandle;Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;"))));
            if (exact)
            {
                mhLinkExactIdx = li;
            }
            else
            {
                mhLinkGenericIdx = li;
            }
        }
        RVMMethod m = li < 0 ? null : Loader.reflEntry(li);
        if (m == null || m.buf == 0L)
        {
            Uart.write(Magic.bytes("\n  MH LINK: no MethodHandleNatives linker\n"));
            return 0L;
        }
        long desc = Loader.guestString(VM.heapBytes(descU.plus(2L), u2(descU)));
        return Magic.call2(m.buf, mh, desc);            // may throw WrongMethodTypeException: unwinds to the site
    }

    /** Byte offset of the instance field the named class itself declares, or -1. */
    static long declaredFieldOffset(byte[] cls, byte[] field)
    {
        int reg = Loader.classRegByNameBytes(cls);
        if (reg < 0)
        {
            return -1L;
        }
        long g = Loader.guestBytes(field);
        return Loader.fieldOffsetOfType(Loader.clTab[reg].type, Address.fromLong(g).plus(24L), field.length);
    }

    /** The number of parameters in the absolute {@code {u2 len}(...)ret} descriptor at {@code descU}. */
    static int descParamCountAt(Address descU)
    {
        Address p = descU.plus(3L);                            // past the u2 length and '('
        int n = 0;
        while (u1(p) != 0x29)                           // ')'
        {
            while (u1(p) == 0x5B)                       // '[' prefixes
            {
                p = p.plus(1);
            }
            if (u1(p) == 0x4C)                          // 'L' ... ';'
            {
                while (u1(p) != 0x3B)
                {
                    p = p.plus(1);
                }
            }
            p = p.plus(1);
            n += 1;
        }
        return n;
    }

    /** VarHandle shim: byte offset of instance field {@code fname} (raw bytes at {@code fnBase..+fnLen}) within
     *  the class whose TIB is {@code tib}. Uses the class registry (TIB->class) + field registry (class+name->
     *  slot). Returns -1 if unresolved.
     *
     *  <p>WALKS THE SUPERCLASS CHAIN, most-derived first. The field registry records a field against the class
     *  that DECLARES it, so matching only against the object's own class finds nothing for an INHERITED field
     *  -- and this is reached with the object's class, not the field's. It returned -1 for every such field,
     *  and {@code Field.addr} adds the result to the object's address, so a reflective get read 8 bytes at
     *  {@code obj - 1} (straddling the header) and a reflective SET wrote there. Silent heap corruption, and
     *  the read looked like a plausible reference.
     *
     *  <p>The declaring class's slot is the right answer for a subclass instance because layout is chain-aware
     *  -- inherited fields are laid out FIRST ({@code ClassFile.chainFieldBase}) -- so a field keeps its slot
     *  in every subclass. Same shape as {@code globalVtableSlot}'s chain tier, and for the same reason. */
    static long vhFieldOffset(Address fnBase, int fnLen, Address tib)
    {
        int start = -1;
        int ci = 0;
        while (ci < Loader.clCount)
        {
            if (Loader.clTab[ci] != null && Loader.clTab[ci].tib.EQ(tib))
            {
                start = ci;
                ci = Loader.clCount;
            }
            else
            {
                ci += 1;
            }
        }
        int chain = start;
        while (chain >= 0 && Loader.clTab[chain] != null)
        {
            int j = 0;
            while (j < Loader.fldCount)
            {
                if (Loader.utf8EqAt(Loader.clTab[chain].base, Loader.clTab[chain].nameOff, Loader.fldTab[j].base, Loader.fldTab[j].classOff)
                        && Utf8.rawEqUtf8(fnBase, fnLen, Loader.fldTab[j].base, Loader.fldTab[j].nameOff))
                {
                    return 16L + Loader.fldTab[j].slot * 8L;
                }
                j += 1;
            }
            chain = Loader.superRegOf(chain);
        }
        return -1L;
    }
}
