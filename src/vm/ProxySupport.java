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
import board.Uart;
import magic.Address;
import magic.DataReference;
import magic.DataReferenceArray;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;


/**
 * java.lang.reflect.Proxy instances, and the Object.clone helper their thunks share. Split out of {@link Loader}.
 *
 * <p>A proxy is synthesised like a lambda or an annotation instance: a Type the registry does not know, whose
 * itable directory names the proxied interfaces. Its SUPERCLASS is java/lang/reflect/Proxy, so the object carries
 * Proxy's {@code h} field and {@code instanceof Proxy} holds. Every interface method -- and equals/hashCode/toString,
 * through the Object vtable slots -- is one THUNK that spills the argument registers and calls
 * jdk/internal/misc/MetalProxies.dispatch(proxy, Method, argsAddr); dispatch boxes the arguments, asks the
 * InvocationHandler, and returns the unboxed raw result in x0.
 */
final class ProxySupport
{
    private ProxySupport()
    {
    }

    private static final int MAXPROXY = 256;
    private static final int PROXY_FRAME = 144;         // x30 at [sp], x1..x15 at [sp+16..sp+128], 16-aligned
    private static DataReferenceArray proxyTypes;
    private static DataReferenceArray proxyTibs;
    private static long[] proxyObjThunks;               // 3 per proxy: hashCode, equals, toString
    static int proxyN;
    static long proxyHelperBuf;
    static long objectCloneBuf;
    static DataReference objectCloneCls;                 // the stub's name runs, held in statics (a GC root) for
    static DataReference objectCloneName;                //   as long as the stub may still resolve through them
    static DataReference objectCloneDesc;

    /** MetalProxies.dispatch, compiled once; its class is loaded on first use. 0 if it will not compile. */
    private static long proxyHelper()
    {
        if (proxyHelperBuf != 0L)
        {
            return proxyHelperBuf;
        }
        if (ClassRegistry.classIndexByName(Magic.bytes("jdk/internal/misc/MetalProxies")) < 0)
        {
            Loader.loadClassIncremental(Magic.bytes("jdk/internal/misc/MetalProxies"));
            if (ClassRegistry.classIndexByName(Magic.bytes("jdk/internal/misc/MetalProxies")) < 0)
            {
                return 0L;
            }
        }
        proxyHelperBuf = Loader.helperBuf(Loader.utf8Blob(Magic.bytes("jdk/internal/misc/MetalProxies")).toAddress(), Magic.bytes("dispatch"),
                Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;J)J"));
        return proxyHelperBuf;
    }

    /**
     * The binding of the native Object.clone(): a LINK STUB for MetalObjects.clone, a static (Object)Object, so
     * the receiver of the instance native is already where its one argument goes. The stub loads and compiles
     * the helper on its first CALL, never here. MEASURED: when this loaded and compiled the helper, the baked
     * Locale.initDefault(Category) branched through a receiver of 0x100 (LocaleInitProbe) although the helper
     * itself never ran, and binding only the OTHER Object natives passed. The mechanism is INFERRED, not
     * measured: nativeBufAt is asked while a class's vtable is being filled, under the loader's single
     * in-progress-class context, and a nested load there overwrites it. 0 if the stub table is full, which
     * leaves the native unbound (a trap at the call, never a silent copy).
     */
    static long objectCloneHelper()
    {
        if (objectCloneBuf != 0L)
        {
            return objectCloneBuf;
        }
        objectCloneCls = Loader.utf8Blob(Magic.bytes("jdk/internal/misc/MetalObjects"));
        objectCloneName = Loader.utf8Blob(Magic.bytes("clone"));
        objectCloneDesc = Loader.utf8Blob(Magic.bytes("(Ljava/lang/Object;)Ljava/lang/Object;"));
        objectCloneBuf = DynamicLinker.linkStubFor(objectCloneCls.toAddress(), objectCloneName.toAddress(),
                objectCloneDesc.toAddress(), 0);                // MetalObjects.clone(Object) is static
        return objectCloneBuf;
    }

    /** Load a 48-bit absolute address into {@code rd}. */
    private static int emitMov48(Address t, int w, int rd, long v)
    {
        w = Loader.emitAt(t, w, A64Enc.movz(rd, (int) (v & 0xFFFF), 0));
        w = Loader.emitAt(t, w, A64Enc.movk(rd, (int) ((v >> 16) & 0xFFFF), 1));
        w = Loader.emitAt(t, w, A64Enc.movk(rd, (int) ((v >> 32) & 0xFFFF), 2));
        return w;
    }

    /**
     * One proxy method's thunk. The receiver (the proxy) is already in x0. x1..x15 -- every register an argument
     * can arrive in, including x15's pointer to the rest past sixteen -- go to the frame, and dispatch gets the
     * frame's address in x2 and the Method in x1. The frame is registered with the unwinder, so a throw from the
     * handler unwinds through it to the caller like any other.
     */
    private static Address proxyThunk(long method, long helper, long slotMask)
    {
        Address t = Heap.allocCode(32 * 4);
        Heap.pinCodeAt(t);                                                // only an itable or vtable names it
        int w = 0;
        w = Loader.emitAt(t, w, A64Enc.subImm(StubFrames.STUB_SP, StubFrames.STUB_SP, PROXY_FRAME));
        w = Loader.emitAt(t, w, A64Enc.strx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF));
        int r = 1;
        while (r <= 15)
        {
            w = Loader.emitAt(t, w, A64Enc.strx(r, StubFrames.STUB_SP, 8 + r * 8));
            r += 1;
        }
        w = emitMov48(t, w, 1, method);
        w = Loader.emitAt(t, w, A64Enc.addImm(2, StubFrames.STUB_SP, 16));
        w = emitMov48(t, w, 16, helper);
        w = Loader.emitAt(t, w, A64Enc.blr(16));
        w = Loader.emitAt(t, w, A64Enc.ldrx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF));
        Address thi = t.plus(w * 4L);
        w = Loader.emitAt(t, w, A64Enc.addImm(StubFrames.STUB_SP, StubFrames.STUB_SP, PROXY_FRAME));
        w = Loader.emitAt(t, w, A64Enc.ret());
        Heap.publishCode(t, t.plus(w * 4L));
        // live from just after the `sub sp`; slotMask types the saved arguments for the GC walk (StackRoots)
        VM.addJitFrame(t.plus(4L), thi, PROXY_FRAME, StubFrames.stubDesc(StubFrames.K_MASK, slotMask));
        return t;
    }

    /**
     * The proxy thunk frame's reference words for an interface method of descriptor {@code descU}: x_r is saved at
     * [sp + 8 + 8r], frame slot r + 1, for r = 1..15 (x0, the proxy, is passed on to dispatch, not saved). Past 15
     * parameters x15 is a pointer to the caller's out-args, which this mask cannot describe: said once, by name.
     */
    private static long argSlotMask(Address descU)
    {
        long regs = FrameMaps.argRefMask(descU, true);
        if (FrameMaps.argN <= 16)
        {
            return (regs & 0xFFFEL) << 1;
        }
        // Past sixteen registers x15 (slot 16) is a pointer to the caller's out-args: arguments 15.. typed there.
        if (FrameMaps.argN > 15 + 30)
        {
            Uart.write(Magic.bytes("  PROXY THUNK: more than 44 parameters, the rest untyped for the GC walk: "));
            Loader.printUtf8Capped(descU);
            Uart.putc(0x0A);
        }
        long over = (regs >>> 15) & 0x3FFF_FFFFL;
        return ((regs & 0x7FFEL) << 1) | (over << StubFrames.MASK_OVERFLOW_BIT0) | StubFrames.MASK_OVERFLOW;
    }

    /** Put proxy {@code i}'s equals/hashCode/toString thunks into its TIB's Object vtable slots. */
    static void patchProxyObject(int i)
    {
        int oi = Loader.objectClassIndex();
        if (oi < 0)
        {
            return;
        }
        int st = ClassRegistry.clTab[oi].vtStart;
        int nv = ClassRegistry.clTab[oi].vtCount;
        int k = 0;
        while (k < nv)
        {
            Address b = VtableLayout.vtNameBase.get(st + k);
            int no = VtableLayout.vtNameOff[st + k];
            int dof = VtableLayout.vtDescOff[st + k];
            long thunk = 0L;
            if (Utf8.utf8IsAtBase(b, no, Magic.bytes("hashCode")) && Utf8.utf8IsAtBase(b, dof, Magic.bytes("()I")))
            {
                thunk = proxyObjThunks[i * 3];
            }
            else if (Utf8.utf8IsAtBase(b, no, Magic.bytes("equals")) && Utf8.utf8IsAtBase(b, dof, Magic.bytes("(Ljava/lang/Object;)Z")))
            {
                thunk = proxyObjThunks[i * 3 + 1];
            }
            else if (Utf8.utf8IsAtBase(b, no, Magic.bytes("toString")) && Utf8.utf8IsAtBase(b, dof, Magic.bytes("()Ljava/lang/String;")))
            {
                thunk = proxyObjThunks[i * 3 + 2];
            }
            if (thunk != 0L)
            {
                proxyTibs.get(i).toAddress().plus(8L).plus((long) k * 8L).store(thunk);
            }
            k += 1;
        }
    }

    /** The proxy index of TIB {@code tib}, or -1. */
    static int proxyIndexOfTib(Address tib)
    {
        int i = 0;
        while (i < proxyN)
        {
            if (proxyTibs.get(i).toAddress().EQ(tib))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * {@code Proxy.proxyType0}: build a proxy class for the interface CLOSURE {@code ifacesArr} (Class[]).
     * {@code methodsArr}/{@code namesArr}/{@code descsArr} are parallel: every non-static method of the closure,
     * its name and its descriptor as raw byte[]s; a slot is given the FIRST method matching its name and
     * descriptor, in the order the guest listed them. {@code objArr} holds Object's hashCode, equals, toString.
     * Returns the new class's mirror, or 0.
     */
    static long proxyType(long ifacesArr, long methodsArr, long namesArr, long descsArr, long objArr)
    {
        if (ifacesArr <= 0x1000L || methodsArr <= 0x1000L || namesArr <= 0x1000L || descsArr <= 0x1000L
                || objArr <= 0x1000L)
        {
            return 0L;
        }
        int pr = ClassRegistry.classIndexByName(Magic.bytes("java/lang/reflect/Proxy"));
        long helper = proxyHelper();
        if (pr < 0 || helper == 0L)
        {
            return 0L;
        }
        if (proxyTypes == null)
        {
            proxyTypes = DataReferenceArray.create(MAXPROXY);
            proxyTibs = DataReferenceArray.create(MAXPROXY);
            proxyObjThunks = new long[MAXPROXY * 3];
        }
        if (proxyN >= MAXPROXY)
        {
            Uart.write(Magic.bytes("\n  PROXY TABLE FULL: no more proxy classes (MAXPROXY)\n"));
            return 0L;
        }
        int ni = (int) Address.fromLong(ifacesArr).plus(16L).loadLong();
        int nm = (int) Address.fromLong(methodsArr).plus(16L).loadLong();
        DataReference dir = Heap.allocDataRefs((ni + 1) * 16);
        int e = 0;
        while (e < ni)
        {
            Address mirror = Address.fromLong(ifacesArr).plus(24L).plus(e * 8L).loadAddress();
            Address ity = mirror.isZero() ? Address.zero() : mirror.plus(16L).loadAddress();
            int ir = ClassRegistry.classRegByType(ity);
            int cn = ir >= 0 ? ClassRegistry.clTab[ir].ifmCount : 0;
            DataReference cit = Heap.allocCodeTable(cn > 0 ? cn * 8 : 8);
            int z = 0;
            while (z < cn)
            {
                int m = ClassRegistry.clTab[ir].ifmStart + z;
                Address thunk = Address.zero();
                int j = 0;
                while (j < nm && thunk.isZero())
                {
                    Address nb = Address.fromLong(namesArr).plus(24L).plus(j * 8L).loadAddress();
                    Address db = Address.fromLong(descsArr).plus(24L).plus(j * 8L).loadAddress();
                    if (Utf8.rawEqUtf8(nb.plus(24L), (int) nb.plus(16L).loadLong(), InterfaceInvocation.ifBase.get(m), InterfaceInvocation.ifNameOff[m])
                            && Utf8.rawEqUtf8(db.plus(24L), (int) db.plus(16L).loadLong(), InterfaceInvocation.ifBase.get(m), InterfaceInvocation.ifDescOff[m]))
                    {
                        thunk = proxyThunk(Address.fromLong(methodsArr).plus(24L).plus(j * 8L).loadLong(), helper,
                                argSlotMask(InterfaceInvocation.ifBase.get(m).plus(InterfaceInvocation.ifDescOff[m])));
                    }
                    j += 1;
                }
                cit.plus(z * 8L).store(thunk);
                z += 1;
            }
            dir.plus(e * 16L).store(ity);
            dir.plus(e * 16L).plus(8L).store(cit);
            e += 1;
        }
        dir.plus(ni * 16L).store(0L);              // sentinel: interfaceType 0 ends the directory
        dir.plus(ni * 16L).plus(8L).store(0L);
        DataReference type = Heap.allocType();
        type.plus(0L).store((long) (16 + ClassRegistry.clTab[pr].fieldCount * 8));   // Proxy's instance: header + h
        type.plus(ObjectModel.TYPE_SUPER_OFFSET).store(ClassRegistry.clTab[pr].type);
        type.plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).store(dir);
        TibBuilder.copyMaps(type, ClassRegistry.clTab[pr].type);   // a proxy instance IS a Proxy's layout (S6b)
        int onv = TibBuilder.objectVtableCount();
        DataReference tib = Heap.allocTib(8 + onv * 8);
        tib.plus(0L).store(type);
        TibBuilder.fillObjectVtable(tib.toAddress());
        int i = proxyN;
        proxyTypes.set(i, type);
        proxyTibs.set(i, tib);
        int k = 0;
        while (k < 3)
        {
            // equals/hashCode/toString: only equals takes an argument, an Object in x1 (slot 2). Typing x1 for all three
            // is a safe over-approximation: a word that is no block base is never marked.
            proxyObjThunks[i * 3 + k] = proxyThunk(Address.fromLong(objArr).plus(24L).plus(k * 8L).loadLong(), helper, 1L << 2).toLong();
            k += 1;
        }
        proxyN += 1;
        patchProxyObject(i);
        if (TibBuilder.lambdaTibRoots != null && TibBuilder.lambdaTibRootN < TibBuilder.MAXLAMBDATIB)
        {
            TibBuilder.lambdaTibRoots.set(TibBuilder.lambdaTibRootN, tib);             // the same GC root a lambda's TIB has: nothing scanned
            TibBuilder.lambdaTibVtCap[TibBuilder.lambdaTibRootN] = onv;       //   would otherwise reach it
            TibBuilder.lambdaTibFilled[TibBuilder.lambdaTibRootN] = false;
            TibBuilder.lambdaHostBase.set(TibBuilder.lambdaTibRootN, Address.zero());        // a proxy has no host class
            TibBuilder.lambdaHostNameOff[TibBuilder.lambdaTibRootN] = 0;
            TibBuilder.lambdaTibRootN += 1;
        }
        return ClassMirrors.classMirror(type.toAddress());
    }

    /** {@code Proxy.proxyAlloc0}: a zeroed instance of proxy class {@code mirror}, or 0 if it is not one. */
    static long proxyAlloc(ObjectReference mirror)
    {
        if (mirror.toLong() <= 0x1000L || proxyTypes == null)
        {
            return 0L;
        }
        DataReference type = mirror.toAddress().plus(16L).loadDataReference();
        int i = 0;
        while (i < proxyN)
        {
            if (proxyTypes.get(i).EQ(type))
            {
                ObjectReference obj = Heap.alloc((int) type.loadLong()).toObjectReference();
                obj.toAddress().plus(ObjectModel.TIB_OFFSET).store(proxyTibs.get(i).toAddress());
                return obj.toLong();
            }
            i += 1;
        }
        return 0L;
    }
}
