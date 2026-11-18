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
import magic.Address;
import magic.AddressArray;
import magic.Magic;

import static vm.ClassBytes.u2;

/**
 * Natives this VM does not implement: each gets a thunk that throws {@code UnsatisfiedLinkError} naming the class
 * and method the moment it is reached (CLAUDE.md rule 3: a stub THROWS rather than answering). An entry with
 * {@code mnCls} 0 is instead an UNSUPPORTED VarHandle ACCESS MODE ({@code mnName} the mode, {@code mnDesc} unused),
 * whose thunk throws {@code UnsupportedOperationException} naming the mode, as stock does. Split out of
 * {@link Loader}.
 */
final class NativeMethods
{
    private NativeMethods()
    {
    }

    private static final int MAXMISSINGNATIVE = 128;
    private static final AddressArray mnCls = AddressArray.create(MAXMISSINGNATIVE);     // the callee's Utf8 ADDRESSES (u2 len + bytes)
    private static final AddressArray mnName = AddressArray.create(MAXMISSINGNATIVE);
    private static final AddressArray mnDesc = AddressArray.create(MAXMISSINGNATIVE);
    private static final long[] mnThunk = new long[MAXMISSINGNATIVE];
    private static int mnCount;

    /**
     * A branch target for a call to the native {@code cls.name desc} that throws {@code UnsatisfiedLinkError}: a
     * thunk {@code movz x0,#k ; br VM.nativeMissing}, one per method and shared by every site that calls it. A
     * {@code br} writes no x30, so {@link VM#nativeMissing} sees the CALLER's return address and the error unwinds
     * as if the native itself had thrown. 0 when the table is full or the helper was not baked: the caller keeps
     * the trap.
     */
    static long missingNativeThunk(Address clsU, Address nameU, Address descU)
    {
        int k = 0;
        while (k < mnCount)
        {
            if (clsU.isZero() ? mnCls.get(k).isZero() && Utf8.utf8Same(mnName.get(k), nameU)
                    : !mnCls.get(k).isZero() && Utf8.utf8Same(mnCls.get(k), clsU)
                            && Utf8.utf8Same(mnName.get(k), nameU)
                            && Utf8.utf8Same(mnDesc.get(k), descU))
            {
                return mnThunk[k];
            }
            k += 1;
        }
        long t = VM.nativeMissingAddr;
        if (mnCount >= MAXMISSINGNATIVE || t == 0L)
        {
            return 0L;
        }
        Address buf = Heap.allocCode(32);
        Heap.pinCodeAt(buf);                                 // only a memoised link target names it
        int w = 0;
        buf.plus(w * 4L).store(A64Enc.movz(0, mnCount, 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (t & 0xFFFF), 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((t >> 16) & 0xFFFF), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((t >> 32) & 0xFFFF), 2));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.br(16));
        w += 1;
        Heap.publishCode(buf, buf.plus(w * 4L));
        mnCls.set(mnCount, clsU);
        mnName.set(mnCount, nameU);
        mnDesc.set(mnCount, descU);
        mnThunk[mnCount] = buf.toLong();
        mnCount += 1;
        return buf.toLong();
    }

    /**
     * The missing-native thunk for {@code name desc} when class {@code ci} or a superclass DECLARES it native, or
     * 0 -- the late-virtual twin of the link-stub check (an instance native is dispatched, not linked). The nearest
     * declaring class names it, as an inherited method's own class would.
     */
    static long missingNativeOnChain(int ci, Address nameU, Address descU)
    {
        int c = ci;
        int hops = 0;
        while (c >= 0 && hops < 64)
        {
            Address clsU = ClassRegistry.clTab[c].base.plus(ClassRegistry.clTab[c].nameOff);
            if (declaredNative(clsU, nameU, descU))
            {
                return missingNativeThunk(clsU, nameU, descU);
            }
            c = ClassRegistry.superRegOf(c);
            hops += 1;
        }
        return 0L;
    }

    /** Whether thunk {@code k} is an unsupported VarHandle access mode rather than a missing native. */
    static boolean missingNativeUnsupported(int k)
    {
        return mnCls.get(k).isZero();
    }

    /** The {@code UnsupportedOperationException} for unsupported access mode {@code k}, its message the mode's
     *  name ({@code set}) exactly as stock's VarForm gives it. Loads the class first, as missingNativeError does. */
    private static long unsupportedModeError(int k)
    {
        byte[] name = Magic.bytes("java/lang/UnsupportedOperationException");
        VM.loaderLock(VM.LOCK_REFLECT);
        try
        {
            if (ClassRegistry.classIndexByName(name) < 0)
            {
                Loader.loadClassIncremental(name);
            }
        }
        finally
        {
            VM.loaderUnlock();
        }
        byte[] msg = new byte[u2(mnName.get(k))];
        Loader.putUtf8(msg, 0, mnName.get(k));
        return RuntimeEntrypoints.newExcWithMessage(name, msg);
    }

    /**
     * The {@code UnsatisfiedLinkError} for missing native {@code k}, its message naming the method: {@code joe-ng:
     * native sun.nio.ch.Net.setIntOption0(Ljava/io/FileDescriptor;ZIIIZ)V is not implemented}. Loads the error
     * class first if no program has used it yet -- {@link RuntimeEntrypoints#newExc} of an unloaded class gives an exception with no
     * Type, which nothing can catch.
     */
    static long missingNativeError(int k)
    {
        if (missingNativeUnsupported(k))
        {
            return unsupportedModeError(k);
        }
        byte[] name = Magic.bytes("java/lang/UnsatisfiedLinkError");
        VM.loaderLock(VM.LOCK_REFLECT);
        try
        {
            if (ClassRegistry.classIndexByName(name) < 0)
            {
                Loader.loadClassIncremental(name);
            }
        }
        finally
        {
            VM.loaderUnlock();
        }
        byte[] pre = Magic.bytes("joe-ng: native ");
        byte[] post = Magic.bytes(" is not implemented");
        int len = pre.length + u2(mnCls.get(k)) + 1 + u2(mnName.get(k)) + u2(mnDesc.get(k)) + post.length;
        byte[] msg = new byte[len];
        int p = 0;
        int i = 0;
        while (i < pre.length)
        {
            msg[p] = pre[i];
            p += 1;
            i += 1;
        }
        p = Loader.putDotted(msg, p, mnCls.get(k));
        msg[p] = (byte) '.';
        p += 1;
        p = Loader.putUtf8(msg, p, mnName.get(k));
        p = Loader.putUtf8(msg, p, mnDesc.get(k));
        i = 0;
        while (i < post.length)
        {
            msg[p] = post[i];
            p += 1;
            i += 1;
        }
        return RuntimeEntrypoints.newExcWithMessage(name, msg);
    }

    /**
     * Whether class {@code clsU} itself DECLARES {@code name desc} as a native method. Reads the classfile bytes
     * directly -- its own constant-pool walk, never the shared parse context ({@code gbase}/{@code gcp}), which a
     * failing link must not disturb. Only the named class is consulted: a native inherited from a superclass
     * keeps the trap.
     */
    static boolean declaredNative(Address clsU, Address nameU, Address descU)
    {
        return Loader.declaresMethod(clsU, nameU, descU, true);
    }
}
