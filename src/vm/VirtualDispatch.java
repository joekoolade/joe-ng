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
import classfile.ClassReader;
import compiler.Baseline;
import magic.Address;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Late virtual dispatch. Split out of {@link Loader}.
 *
 * <p>A virtual call whose class is not registered at compile time has no slot to bake into the instruction. Static
 * call sites solved this with link stubs ({@link DynamicLinker}); a virtual site cannot, because the target depends
 * on the RECEIVER. So the site records its (class,name,desc) here, emits the index in x17, and calls a trampoline
 * that resolves against the receiver's actual type at first execution, memoizing per site. VarHandle accessor sites
 * resolve through the same path.
 */
final class VirtualDispatch
{
    private VirtualDispatch()
    {
    }

    // ----- late virtual dispatch ------------------------------------------------------------------------
    // A virtual call whose class is not registered at compile time has no slot to bake into the instruction.
    // Static call sites solved this with link stubs (PR #192); a virtual site cannot, because the target
    // depends on the RECEIVER. So the site records its (class,name,desc) here, emits the index in x17, and
    // calls a trampoline that resolves against the receiver's actual type at first execution.
    private static final int MAXVSITE = 16384;
    private static final int VSHASH = 32768;             // power of two, > 2x MAXVSITE (open addressing)
    static AddressArray vsName;
    static AddressArray vsDesc;                // Utf8 ADDRESSES (blob base + offset), not offsets
    // The site's OWNER class, recorded for the same reason: a native has no bytecode and no vtable slot, so
    // when the receiver's Type is not in the class registry the native table is the only thing that can
    // answer -- and nativeBufAt is keyed by CLASS + name.
    private static AddressArray vsCls;
    static int vsCount;
    private static int[] vsBucket;                       // hash -> site index + 1 (0 = empty)
    static long virtualTrampAddr;
    static long virtualTrampLo, virtualTrampHi;   // the range over which its frame is live
    private static Address vsMemoType;                   // one-entry memo: the same site is usually monomorphic
    private static long vsMemoBuf;
    private static int vsMemoIdx = -1;
    // PER-SITE, WRITE-ONCE memo: the first receiver Type a site resolved for, and the body it resolved to.
    // The one-entry memo above is shared by EVERY site, so a loop alternating two late sites -- a VarHandle
    // `get` and `compareAndSet`, which is every CAS loop over an AtomicReference/Atomic*Array -- thrashed it
    // and paid a full lookup UNDER THE LOADER LOCK on every call. Four cores doing that serialised on the
    // lock: measured, a 4-thread x 20,000-iteration CAS loop did not finish in 180 s on QEMU, where the
    // Unsafe-backed AtomicInteger loop beside it finished at once.
    //
    // Read WITHOUT the lock (virtualResolve), which is the point. Sound because (1) an entry is written ONCE
    // per launch -- body first, a barrier, then the Type -- so a reader that sees the Type sees the body; and
    // (2) within a launch a (site, receiver Type) pair cannot change its answer: registered classes keep their
    // Types and registered bodies stay reachable. Cleared in resetLoader, where both of those stop holding.
    private static long[] vsSiteType;
    private static long[] vsSiteBuf;
    /**
     * Last tier of {@link #virtualResolve}: look for {@code nameU}/{@code descU} on the INTERFACES of the
     * receiver's class chain -- an interface default, which no class declares and so no chain walk can find.
     *
     * <p>Two levels (a class's own interfaces, then each of those interfaces' own), which covers the shapes
     * that occur in practice ({@code ArrayList -> List -> Collection}, {@code LinkedHashMap -> Map}) without
     * recursion or a worklist. A default inherited three interfaces deep still fails, and says so by name.
     *
     * <p>The interface NAME ADDRESSES are collected BEFORE any resolving: {@link DynamicLinker#resolveLinkTarget} parses
     * whatever class it is handed, which clobbers {@code gView}, so an offset read afterwards
     * would be against the wrong blob. Absolute addresses survive that; offsets do not.
     */
    /** What the LAST {@link #resolveViaInterfaces} walk saw -- printed only on a terminal failure. */
    static int viHops;
    static int viIfaceN;
    static AddressArray viNames;
    /** Hop cap on the superclass walk above -- a malformed or cyclic chain must not spin. */
    static final int MAXIFACECHAIN = 32;
    /**
     * Name the BAKE-STUB MEMO, if any, that is still handing out a stub carrying {@code idx}.
     *
     * <p>{@code VM.bakeResolve} memoizes its answer into the bake-stub table -- which lives in the IMAGE and
     * is therefore never reclaimed -- while {@code lzTab}/{@code lzN} ARE dropped and rebuilt by the reclaim.
     * A memo taken before a reclaim keeps returning a deferral stub whose baked index belongs to a table
     * generation that no longer exists. This walks the table and decodes each memo's first word: a deferral
     * stub opens {@code movz x17,<idx>}, so an entry whose memo carries the failing index IS the source, and
     * the entry's own class/name/descriptor names the baked method it belongs to.
     *
     * <p>Evidence, not inference: silence here means the stale index came from somewhere else.
     */
    /**
     * Drop every bake-stub memo that points at loader-emitted code, as part of the reclaim.
     *
     * <p>{@code VM.bakeResolve} caches its answer in the bake-stub table -- which the WRITER emits into the
     * IMAGE, so the memo is immortal -- while everything it can point at is mortal: the deferral stub, the
     * compiled body, and the {@code lzTab} entry that stub's baked index refers to are all dropped and
     * rebuilt by this reclaim. The two have independent lifetimes, and nothing reconciled them.
     *
     * <p>MEASURED, not argued: bake-stub entry 11, {@code java/lang/Integer.toString()}, kept returning a
     * stub carrying lazy index 3138 long after the table had been rebuilt to 2274 entries. {@code
     * lazyCompile} then rejected the index and returned 0, and the trampoline tail-branched to address 0 --
     * which, because {@code br} writes no x30, surfaced as a BOOT RE-ENTERED naming a healthy call in
     * {@code String.valueOf(Object)} rather than anything to do with this.
     *
     * <p>Only memos into the CODE ARENA are cleared. A memo naming a BAKED body points into image memory,
     * which no reclaim touches, so re-resolving those would be pure cost. Clearing is safe either way: the
     * next call re-resolves through the same three tiers and memoizes again.
     */
    /** The repair below, switchable so it has a NEGATIVE CONTROL: false restores the pre-fix behaviour
     *  exactly, and the suite must then die again at LispDemo. A fix whose control was never run is a
     *  change that correlates with a passing boot, not a demonstrated cause. */
    static final boolean BAKE_MEMO_REPAIR = true;
    /** How many bake-stub memos the reclaim has had to drop -- 0 would mean this repair never fires. */
    static int bakeMemosDropped;

    /** Empty the per-site memo (allocation, and every resetLoader). Type is cleared first: a reader keys on it. */
    static void clearSiteMemos()
    {
        if (vsSiteType == null)
        {
            return;
        }
        int z = 0;
        while (z < MAXVSITE)
        {
            vsSiteType[z] = 0L;
            vsSiteBuf[z] = 0L;
            z += 1;
        }
        vsMemoIdx = -1;
    }

    /**
     * The address of site {@code idx}'s memo word -- {@code vsSiteType[idx]}, or {@code vsSiteBuf[idx]} when
     * {@code body} -- for the INLINE CACHE a compiled late-virtual call site reads (Baseline.lateVirtualCall).
     * The arrays are allocated once, before the first site index is handed out, and never reallocated; the
     * collector does not move objects; so the address is good for the life of the image.
     */
    static long siteMemoAddr(int idx, boolean body)
    {
        long[] a = body ? vsSiteBuf : vsSiteType;
        return Magic.addrOf(a) + (long) ObjectModel.ARRAY_BASE_OFFSET + (long) idx * 8L;
    }

    /** Publish a site's first resolution, once: body, then a barrier, then the Type a lock-free reader keys on. */
    private static void publishSiteMemo(int idx, Address type, long buf)
    {
        if (vsSiteType != null && vsSiteType[idx] == 0L)
        {
            vsSiteBuf[idx] = buf;
            Magic.dsb();
            vsSiteType[idx] = type.toLong();
        }
    }

    /** Record an unresolved virtual site; returns its index (what the emitted code puts in x17). */
    static int virtualSiteIndex(int methodCp)
    {
        if (vsName == null)
        {
            vsName = AddressArray.create(MAXVSITE);
            vsDesc = AddressArray.create(MAXVSITE);
            vsCls = AddressArray.create(MAXVSITE);
            vsBucket = new int[VSHASH];                  // allocArray does NOT zero: fill it explicitly
            vsSiteType = new long[MAXVSITE];
            vsSiteBuf = new long[MAXVSITE];
            clearSiteMemos();
            int z = 0;
            while (z < VSHASH)
            {
                vsBucket[z] = 0;
                z += 1;
            }
        }
        return siteIndexFor(Loader.gView.base.plus(Loader.mrefNameOff(methodCp)), Loader.gView.base.plus(Loader.mrefDescOff(methodCp)),
                Loader.gView.base.plus(Loader.refClassNameOff(methodCp)));
    }

    /** {@link #virtualSiteIndex} keyed by absolute Utf8 addresses -- also how a linkToVirtual site is made. */
    static int siteIndexFor(Address nameAddr, Address descAddr, Address clsAddr)
    {
        if (vsName == null)
        {
            return -1;
        }
        // Dedup, because a site is now allocated for EVERY dispatch guard, and the compiler visits each site
        // more than once (size pass then emit pass). Without this the table would fill with duplicates and
        // the two passes would bake different indices for the same call.
        long nameKey = nameAddr.toLong();               // the dedup KEY is the three addresses, compared as longs
        long descKey = descAddr.toLong();
        long clsKey = clsAddr.toLong();
        int h = (int) ((nameKey * 31L + descKey) >> 3) & (VSHASH - 1);
        while (vsBucket[h] != 0)
        {
            int cand = vsBucket[h] - 1;
            if (vsName.get(cand).toLong() == nameKey && vsDesc.get(cand).toLong() == descKey && vsCls.get(cand).toLong() == clsKey)
            {
                return cand;                            // the CLASS joins the key: two classes can declare
            }                                           //   the same name+descriptor, and the native lookup
                                                        //   below would then use the wrong owner
            h = (h + 1) & (VSHASH - 1);
        }
        if (vsCount >= MAXVSITE)
        {
            Loader.capHalt(Magic.bytes("MAXVSITE"), vsCount);   // returning a stale index would MIS-RESOLVE silently
        }
        int idx = vsCount;
        vsName.set(idx, nameAddr);
        vsDesc.set(idx, descAddr);
        vsCls.set(idx, clsAddr);
        vsBucket[h] = idx + 1;
        vsCount += 1;
        return idx;
    }

    /**
     * Resolve an unresolved virtual site against the RECEIVER's dynamic type. Reuses
     * {@link DynamicLinker#resolveLinkTarget}, which demand-loads the class and walks the same tiers a link stub does --
     * the only difference is that the class name comes from the receiver rather than from the call site,
     * which is what makes it virtual dispatch.
     */
    /**
     * The template-generated accessor a signature-polymorphic VarHandle site resolves to, searched up the
     * RECEIVER's class chain -- which is where it has to be searched, because the access modes are split
     * across the generated hierarchy ({@code get} on {@code FieldInstanceReadOnly}, {@code set} on
     * {@code FieldInstanceReadWrite}).
     *
     * <p>The NAME is the site's unchanged: it IS the access mode. Only the descriptor is rebuilt.
     */
    private static long resolveVhAccessor(int ci, int idx)
    {
        byte[] d = vhAccessorDesc(vsDesc.get(idx));
        if (d == null)
        {
            return 0L;
        }
        long buf = vhChainResolve(ci, idx, d);
        if (buf == 0L && d.length > 2 && d[d.length - 2] == 0x29 && d[d.length - 1] == 0x56)
        {
            // A site that DISCARDS the result: javac types `vh.getAndAdd(a, i, v);` as returning void, while the
            // accessor returns the old value (VarHandleLongs$Array.getAndAdd is (LVarHandle;LObject;IJ)J). Stock
            // invocation lets a void site drop any return, and here dropping it costs nothing -- the value is
            // left in x0/d0 unread -- so the same parameters are tried with each return an accessor can have.
            // A class declares at most one accessor per name and parameter list, so the first hit is THE one.
            // MEASURED (InvokeGapProbe "array VarHandle"): VIRTUALRESOLVE FAILED ...getAndAdd([JIJ)V, a TRAP.
            byte[][] rets = { Magic.bytes("I"), Magic.bytes("J"), Magic.bytes("Z"), Magic.bytes("B"), Magic.bytes("S"),
                              Magic.bytes("C"), Magic.bytes("F"), Magic.bytes("D"), Magic.bytes("Ljava/lang/Object;") };
            int r = 0;
            while (buf == 0L && r < rets.length)
            {
                byte[] alt = new byte[d.length - 1 + rets[r].length];
                int k = 0;
                while (k < d.length - 1)
                {
                    alt[k] = d[k];
                    k += 1;
                }
                int j = 0;
                while (j < rets[r].length)
                {
                    alt[k + j] = rets[r][j];
                    j += 1;
                }
                buf = vhChainResolve(ci, idx, alt);
                r += 1;
            }
        }
        return buf;
    }

    /** The registry index of the TARGET handle's class when {@code recv} (of registered class {@code ci}) is a
     *  LazyInitializingVarHandle, else -1. */
    private static int lazyVhTargetReg(ObjectReference recv, int ci)
    {
        byte[] lazy = Magic.bytes("java/lang/invoke/LazyInitializingVarHandle");
        if (!Utf8.utf8IsAtBase(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, lazy))
        {
            return -1;
        }
        long off = FieldRegistry.instanceFieldOffset(ci, Magic.bytes("target"));
        if (off < 0L)
        {
            return -1;
        }
        long target = recv.toAddress().plus(off).loadLong();
        if (target == 0L || !receiverAddress(target))
        {
            return -1;
        }
        long tib = Address.fromLong(target).plus(ObjectModel.TIB_OFFSET).loadLong();
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return -1;
        }
        return ClassRegistry.regOfType(Address.fromLong(tib).loadAddress());
    }

    /**
     * Whether {@code nameU} is a VarHandle ACCESS MODE (one of VarHandle's own signature-polymorphic natives) that
     * no class of handle class {@code hc}'s chain below VarHandle declares under ANY descriptor.
     */
    private static boolean vhModeUnsupported(int hc, Address nameU)
    {
        byte[] vh = Magic.bytes("java/lang/invoke/VarHandle");
        int c = hc;
        int hops = 0;
        while (c >= 0 && hops < DynamicLinker.MAXCHAIN)
        {
            Address clsU = ClassRegistry.clTab[c].base.plus(ClassRegistry.clTab[c].nameOff);
            if (Utf8.utf8IsAtBase(ClassRegistry.clTab[c].base, ClassRegistry.clTab[c].nameOff, vh))
            {
                return DynamicLinker.declaresMethod(clsU, nameU, Address.zero(), true);   // the mode IS an access mode
            }
            if (DynamicLinker.declaresMethod(clsU, nameU, Address.zero(), false))
            {
                return false;
            }
            c = ClassRegistry.superRegOf(c);
            hops += 1;
        }
        return false;
    }

    /** The VarHandle accessor named by site {@code idx} with descriptor {@code d}, up {@code ci}'s chain, or 0. */
    private static long vhChainResolve(int ci, int idx, byte[] d)
    {
        Address descU = Loader.utf8Blob(d);
        long buf = 0L;
        int c = ci;
        int hops = 0;
        while (c >= 0 && buf == 0L && hops < DynamicLinker.MAXCHAIN)
        {
            if (ClassRegistry.clTab[c] != null)
            {
                buf = DynamicLinker.resolveLinkTarget(ClassRegistry.clTab[c].base.plus(ClassRegistry.clTab[c].nameOff),
                        vsName.get(idx), descU);
            }
            c = ClassRegistry.superRegOf(c);
            hops += 1;
        }
        return buf;
    }

    /**
     * Rebuild a polymorphic VarHandle site's descriptor as the generated accessor's. ONE uniform rule,
     * verified against the generated classes rather than assumed: prepend the handle, generalise EVERY
     * reference AND array to {@code Ljava/lang/Object;}, and keep primitives verbatim.
     *
     * <pre>
     *   site (Lfoo/Bar;I)V  -&gt;  (Ljava/lang/invoke/VarHandle;Ljava/lang/Object;I)V   field int set
     *   site (Lfoo/Bar;)I   -&gt;  (Ljava/lang/invoke/VarHandle;Ljava/lang/Object;)I    field int get
     *   site ([II)I         -&gt;  (Ljava/lang/invoke/VarHandle;Ljava/lang/Object;I)I   int[] view get
     * </pre>
     *
     * <p>THE ARRAY COORDINATE GENERALISES TOO, which is why one rule covers field and array-view handles
     * alike -- {@code VarHandleInts$Array.get} really is {@code (LVarHandle;LObject;I)I}, read off the
     * generated class with {@code javap -s}, not inferred from the field case.
     *
     * <p>KEEPING PRIMITIVES VERBATIM IS THE WHOLE POINT: it is what makes the resolve WIDTH-TYPED, so a
     * site storing an {@code int} cannot bind to the {@code long} or reference accessor. The retired
     * hand-written overlay resolved these by NAME ALONE, which is how one reference-typed {@code set} came
     * to serve every width and stored an int as a reference.
     */
    private static byte[] vhAccessorDesc(Address siteDesc)
    {
        int len = u2(siteDesc);
        if (len < 3 || u1(siteDesc.plus(2)) != 0x28)        // must start '('
        {
            return null;
        }
        byte[] out = new byte[40 + 20 * len];           // worst case: every token becomes Ljava/lang/Object;
        int n = vhPut(out, 0, Magic.bytes("(Ljava/lang/invoke/VarHandle;"));
        int i = 1;                                      // past the site's own '('
        while (i < len && u1(siteDesc.plus(2 + i)) != 0x29) // up to ')'
        {
            int ch = u1(siteDesc.plus(2 + i));
            if (ch == 0x5B || ch == 0x4C)               // '[' or 'L' -> a reference, whatever its shape
            {
                i = vhSkipType(siteDesc, len, i);
                n = vhPut(out, n, Magic.bytes("Ljava/lang/Object;"));
            }
            else                                        // a primitive: verbatim, which is what types the resolve
            {
                out[n] = (byte) ch;
                n += 1;
                i += 1;
            }
        }
        if (i >= len)
        {
            return null;                                // no ')': not a method descriptor
        }
        out[n] = 0x29;                                  // ')'
        n += 1;
        i += 1;
        int r = u1(siteDesc.plus(2 + i));
        if (r == 0x5B || r == 0x4C)
        {
            n = vhPut(out, n, Magic.bytes("Ljava/lang/Object;"));
        }
        else
        {
            out[n] = (byte) r;                          // a primitive, or 'V'
            n += 1;
        }
        byte[] res = new byte[n];
        int k = 0;
        while (k < n)
        {
            res[k] = out[k];
            k += 1;
        }
        return res;
    }

    /** Index just past the type token starting at {@code i} in a descriptor ({@code [[Lfoo/Bar;}, {@code I}). */
    private static int vhSkipType(Address base, int len, int i)
    {
        while (i < len && u1(base.plus(2 + i)) == 0x5B)     // '['
        {
            i += 1;
        }
        if (i < len && u1(base.plus(2 + i)) == 0x4C)        // 'L' ... ';'
        {
            while (i < len && u1(base.plus(2 + i)) != 0x3B)
            {
                i += 1;
            }
        }
        return i + 1;
    }

    /** Append {@code src} to {@code dst} at {@code at}; returns the new end. */
    private static int vhPut(byte[] dst, int at, byte[] src)
    {
        int i = 0;
        while (i < src.length)
        {
            dst[at + i] = src[i];
            i += 1;
        }
        return at + src.length;
    }

    static long virtualResolve(ObjectReference recv, int idx)
    {
        // THE LAST TRAMPOLINE WITHOUT A LOCK -- AND NOT, AS THIS COMMENT FIRST CLAIMED, THE LAST UNLOCKED
        // PATH. It called this "the SIXTH unlocked path into the loader's static context", which reads as a
        // closed enumeration and is wrong by a factor of four: a reachability pass over the natives finds
        // TWENTY MORE `Loader.X` entry points called from `VMNatives` that reach `parseConstPool` with no
        // lock anywhere on the path -- essentially the whole Field/Method/Class reflection surface, and
        // `VMNatives` itself contains not one `loaderLock` call. `fieldTypeMirror` IS the native behind
        // `Field.getType`, which this file records picocli calling for every option field; it re-points
        // gView and then walks the cursor with nothing holding it still. See
        // /tmp/joe-ng-handoff/FINDING-reflection-natives-unlocked.md and the CLAUDE.md card.
        //
        // The correction matters because the wrong number was the reassuring one: "sixth of six" says the
        // surface is closed, and it is not. What follows is still right for THIS path.
        // This is entered straight from the late-virtual dispatch trampoline via VMNatives, and it reuses
        // resolveLinkTarget -- which demand-loads the class and calls parseConstPool, re-pointing gView.base /
        // gView.cp / gView.bytes at a different blob. Every sibling trampoline already locks (lazyCompile LOCK_LAZY,
        // resolveLinkStub LOCK_DEMAND_LOAD, resolveUnresolvedNew LOCK_NEW_RESOLVE, bakeResolve
        // LOCK_BAKE_RESOLVE); this one never did, so a task resolving a virtual call could re-point the
        // parse context under a task walking it. CLAUDE.md hypothesises exactly this shape -- "a FIFTH
        // unlocked path, or a lock that does not cover the whole context" -- for the contended failure the
        // compile-side fix did not close.
        //
        // THE BODY IS A SEPARATE METHOD because the wrapper must stay SHALLOW: this file's build-time guard
        // forbids an exception handler in a method whose operand stack passes OP_MAX, and virtualResolve is
        // large. Same shape as clinitEntryOf and compileMethodOnDemand, for the same reason.
        //
        // AND IT WIDENS A DEBT RATHER THAN CREATING ONE, stated because it should not be discovered later:
        // this path can run a <clinit> (its own doc below says so), so the lock is now held across guest
        // code here too -- exactly as it already is for lazyCompile's drain and loadAll's batch.
        long hit = siteMemoHit(recv, idx);              // FAST PATH, NO LOCK -- see vsSiteType
        if (hit != 0L)
        {
            return hit;
        }
        VM.loaderLock(VM.LOCK_VIRT_RESOLVE);
        long r;
        try
        {
            r = virtualResolveLocked(recv, idx);
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
        return r;
    }

    /**
     * The site's write-once memo, read without the loader lock (see {@code vsSiteType}); 0 = miss. A helper
     * rather than inline so {@link #virtualResolve}, which carries a try/finally, stays SHALLOW. The receiver
     * checks are the ones virtualResolveLocked makes, so a garbage receiver still reaches its reporting path.
     */
    private static long siteMemoHit(ObjectReference recv, int idx)
    {
        if (vsSiteType == null || idx < 0 || idx >= vsCount)
        {
            return 0L;
        }
        if (!receiverAddress(recv.toLong()))
        {
            return 0L;
        }
        long memoType = vsSiteType[idx];
        if (memoType == 0L)
        {
            return 0L;
        }
        Magic.dsb();                                    // the Type was read before the body
        long memoBuf = vsSiteBuf[idx];
        long tib = recv.toAddress().plus(ObjectModel.TIB_OFFSET).loadLong();
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB || Address.fromLong(tib).loadLong() != memoType)
        {
            return 0L;
        }
        return memoBuf;
    }

    /**
     * Whether {@code a} can be a dispatch receiver: 8-aligned, and in the managed heap OR in the boot IMAGE. The
     * image half was missing, so a writer-BAKED object -- the Integer cache, a string literal -- was reported as
     * "BAD RECEIVER" and dispatched to the denylist trap. MEASURED: one linkToVirtual handle on Object.toString
     * worked for a heap object and trapped for Integer.valueOf(5) (MhInvokeProbe). Before method handles a baked
     * receiver reached this resolver only through a vtable slot found null at dispatch; linkToVirtual sends it
     * every receiver.
     */
    private static boolean receiverAddress(long a)
    {
        if ((a & 7L) != 0L)
        {
            return false;
        }
        if (a >= Heap.BASE && a < Heap.managedTop().toLong())
        {
            return true;
        }
        return a >= VM.STACK_TOP && a < Heap.IMAGE_CEILING;
    }

    private static long virtualResolveLocked(ObjectReference recv, int idx)
    {
        if (recv.isNull() || idx < 0 || idx >= vsCount || ClassRegistry.clTab == null)
        {
            return VM.denylistTrapAddr;
        }
        if (!receiverAddress(recv.toLong()))
        {
            // NOT AN OBJECT AT ALL. The bound is managedTop(), NOT LARGE_LIMIT: that is only where the
            // SECONDARIES' arenas begin, so LARGE_LIMIT rejects every object cores 1-3 allocate -- which is
            // most of what a spawned thread touches, and it turned a WORKING call into a denylist trap. Image code carries no implicit checks, so dereferencing this would data-
            // abort INSIDE the fault handler's own unwind -- a NESTED FAULT report naming only this method,
            // with the caller nowhere in it. The site's name+descriptor is right here, so say which dispatch
            // was handed the garbage; that is the one fact the fault report cannot recover.
            Uart.write(Magic.bytes("\n  BAD RECEIVER at dispatch: recv="));
            VM.printHex(recv.toLong());
            if (vsName != null && idx >= 0 && idx < vsCount)
            {
                Uart.write(Magic.bytes(" site="));
                Loader.printNameAt(vsName.get(idx), 0);
                Loader.printNameAt(vsDesc.get(idx), 0);
            }
            Uart.putc(0x0A);
            return VM.denylistTrapAddr;
        }
        long tib = recv.toAddress().plus(ObjectModel.TIB_OFFSET).loadLong();
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return VM.denylistTrapAddr;                 // a raw array has no Type to dispatch through
        }
        Address type = Address.fromLong(tib).loadAddress();
        if (idx == vsMemoIdx && type.EQ(vsMemoType) && vsMemoBuf != 0L)
        {
            return vsMemoBuf;                           // monomorphic site: skip the whole lookup
        }
        int ci = ClassRegistry.classRegByType(type);
        if (ci < 0 || ci >= ClassRegistry.clCount || ClassRegistry.clTab[ci] == null || vsName == null)
        {
            // A receiver whose Type is not in the class registry -- a writer-baked class the loader never
            // adopted, say -- has no blob to resolve against. Image code carries no implicit null check, so
            // the missing entry has to be tested here or the dereference reads from a wild address.
            //
            // NAME IT. Silent, this is the worst report the VM produces: `DENYLIST TRAP` with an EMPTY callee
            // and `TRAPWIRE index=-1`, blaming a denylist that has nothing to do with it, because no
            // patch-time site was ever recorded for a late-resolved call. The method and descriptor are in
            // hand here and cost one line to print.
            // A PROVIDED NATIVE needs no registry entry: it has no bytecode and no vtable slot, so the
            // class registry has nothing to say about it and the native table is the only thing that can
            // answer. `Properties.<init>` calling `Unsafe.storeFence()` is exactly that -- the UNSAFE
            // receiver is a writer-BAKED object whose Type the loader never adopted, so the chain walk below
            // could never run, and the call surfaced as a DENYLIST TRAP with an EMPTY callee blaming a
            // denylist that has nothing to do with a native.
            if (vsCls != null && idx >= 0 && idx < vsCount)
            {
                long nb = Loader.nativeBufAt(vsCls.get(idx), 0, vsName.get(idx), 0);
                if (nb != 0L)
                {
                    return nb;
                }
                // A DEFAULT METHOD ON A LAMBDA. The receiver has no registry entry, but its Type carries an
                // itable directory naming the interface it satisfies -- and that interface is where the
                // default body lives. Try it before reporting; this is the whole reason a stream pipeline
                // could not call Sink.begin on a lambda downstream.
                long ib = InterfaceInvocation.resolveViaItableDir(type, vsName.get(idx), vsDesc.get(idx));
                if (ib != 0L && DynamicLinker.plausibleCode(Address.fromLong(ib)))
                {
                    vsMemoIdx = idx;
                    vsMemoType = type;
                    vsMemoBuf = ib;
                    publishSiteMemo(idx, type, ib);
                    return ib;
                }
                Uart.write(Magic.bytes("\n  DISPATCH ON UNREGISTERED TYPE (receiver's class not in the registry): "));
                Loader.printNameAt(vsName.get(idx), 0);
                Loader.printNameAt(vsDesc.get(idx), 0);
                // WHAT the receiver is, not just what was called on it. Unregistered means the name is not
                // recoverable from the class registry, so print the SHAPE instead: a synthesised lambda or
                // annotation Type has superType == Object and no display, a writer-baked adopted Type has a
                // real display, and an array Type carries an element type. Those are distinguishable, and
                // without them this report says only "something".
                Uart.write(Magic.bytes("\n    recv="));
                VM.printHex(recv.toLong());
                Uart.write(Magic.bytes(" tib="));
                VM.printHex(tib);
                Uart.write(Magic.bytes(" type="));
                VM.printHex(type);
                Uart.write(Magic.bytes("\n    size="));
                VM.printHex(type.plus(0).loadLong());
                Uart.write(Magic.bytes(" super="));
                VM.printHex(type.plus(ObjectModel.TYPE_SUPER_OFFSET).loadLong());
                Uart.write(Magic.bytes(" itableDir="));
                VM.printHex(type.plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).loadLong());
                Uart.write(Magic.bytes("\n    depth="));
                VM.printHex(type.plus(ObjectModel.TYPE_DEPTH_OFFSET).loadLong());
                Uart.write(Magic.bytes(" display="));
                VM.printHex(type.plus(ObjectModel.TYPE_DISPLAY_OFFSET).loadLong());
                Uart.write(Magic.bytes(" elem="));
                VM.printHex(type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadLong());
                Uart.write(Magic.bytes(" objectType="));
                VM.printHex(TibBuilder.objectTypeAddr());
                Uart.write(Magic.bytes("\n    synthesised(lambda/anno TIB)="));
                VM.printDec(TibBuilder.isSynthesisedTib(Address.fromLong(tib)));
                Uart.putc(0x0A);
            }
            return VM.denylistTrapAddr;
        }
        // Walk the receiver's superclass chain, most-derived first -- which IS virtual dispatch: the
        // implementation is whichever class nearest the receiver declares it. resolveLinkTarget answers only
        // for the class it is handed (bufBySigU, nativeBufAt and compileSigOnDemand all work off that class's
        // own blob), so an INHERITED method resolves nowhere without this. `AssertionFailedError` inheriting
        // `getStackTrace` from `Throwable` is the case that found it: the dispatch guard fired on the pruned
        // slot, the resolve looked only at AssertionFailedError, and the call landed in denylistTrap.
        long buf = 0L;
        int c = ci;
        int hops = 0;
        while (c >= 0 && buf == 0L && hops < DynamicLinker.MAXCHAIN)
        {
            if (ClassRegistry.clTab[c] != null)
            {
                buf = DynamicLinker.resolveLinkTarget(ClassRegistry.clTab[c].base.plus(ClassRegistry.clTab[c].nameOff),
                        vsName.get(idx), vsDesc.get(idx));
            }
            c = ClassRegistry.superRegOf(c);
            hops += 1;
        }
        if (buf == 0L)
        {
            // Nothing in the receiver's CLASS chain declares it, which for an interface call is the DEFAULT
            // METHOD case: the body lives on an interface, and a class gets one without declaring anything.
            // (Reached both from an itable directory miss and from a null vtable slot -- neither can see a
            // default, which is exactly why defaultDispatch routes such a call through the itable at all.)
            buf = resolveViaInterfaces(ci, vsName.get(idx), vsDesc.get(idx));
            if (buf != 0L)
            {
                // The line whose ABSENCE hid this whole class of bug: an interface reached only from a body
                // compiled after its batch is never pulled, and every trace of the call vanished with it.
                if (Loader.LOAD_LOG)
                {
                    Uart.write(Magic.bytes("  ifaceresolve "));
                    Loader.printNameAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff);
                    Uart.putc(0x2E);
                    Loader.printNameAt(vsName.get(idx), 0);
                    Uart.putc(0x0A);
                }
            }
        }
        if (buf == 0L && vsCls != null && Utf8.utf8IsAtBase(vsCls.get(idx), 0, Magic.bytes("java/lang/invoke/VarHandle")))
        {
            // A SIGNATURE-POLYMORPHIC VarHandle OP. The site's declared owner is VarHandle and nothing in
            // the receiver's chain matches its descriptor -- which is expected, not a failure: the site
            // descriptor is the ACTUAL argument types (set:(Lfoo/Bar;I)V) while the implementation is the
            // template-generated handle's STATIC accessor (set:(LVarHandle;LObject;I)V). In HotSpot the 31
            // access-mode methods are natives the JVM intrinsifies and never actually invokes; here the
            // call site is redirected to the generated static instead, which needs no argument shuffling --
            // an invokevirtual already passes (x0=handle, x1=holder, x2=value), and that IS its signature.
            buf = resolveVhAccessor(ci, idx);
            int ti = buf == 0L ? lazyVhTargetReg(recv, ci) : -1;
            int hc = ti >= 0 ? ti : ci;
            if (ti >= 0)
            {
                // A LazyInitializingVarHandle: what findStaticVarHandle answers for a class not initialized yet
                // (SSLContext's VH_DEFAULT_CONTEXT). It shares its TARGET's vform and declares no accessor, so
                // the access mode resolves against the TARGET's class -- and the handle passed stays the
                // WRAPPER, because every static accessor opens with `(FieldStaticReadWrite) ob.target()`, which
                // is the wrapper's hook for running the class's initializer on first access. Resolving against
                // the target and passing the target instead would skip that initializer.
                //
                // The site memo below stays right: the accessor is chosen by the descriptor alone (its
                // primitives pick the VarHandleX family; read modes live on FieldStaticReadOnly, which
                // FieldStaticReadWrite extends), so every wrapper reaching this site resolves the same way.
                buf = resolveVhAccessor(ti, idx);
            }
            if (buf == 0L && vhModeUnsupported(hc, vsName.get(idx)))
            {
                // An ACCESS MODE this handle does not support -- `set` on a final static's FieldStaticReadOnly.
                // Stock throws UnsupportedOperationException naming the mode (VarForm.getMemberName), and so
                // does this. Only when NO accessor of that name exists anywhere up the handle's chain: one that
                // exists under another descriptor is a resolution gap in this VM, and keeps the named trap.
                buf = NativeMethods.missingNativeThunk(Address.zero(), vsName.get(idx), Address.zero());
            }
        }
        if (buf == 0L)
        {
            buf = NativeMethods.missingNativeOnChain(ci, vsName.get(idx),
                    vsDesc.get(idx));   // an unimplemented native THROWS
        }
        if (buf == 0L)
        {
            // Name it. A bare denylistTrap from here reports TRAPWIRE index=-1 (no patch-time site was
            // recorded for a late-resolved call), which says nothing at all about what failed.
            Uart.write(Magic.bytes("  VIRTUALRESOLVE FAILED "));
            Loader.printNameAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff);
            Uart.putc(0x2E);
            Loader.printNameAt(vsName.get(idx), 0);
            Loader.printNameAt(vsDesc.get(idx), 0);
            Uart.putc(0x0A);
            return VM.denylistTrapAddr;
        }
        if (!DynamicLinker.plausibleCode(Address.fromLong(buf)))
        {
            DynamicLinker.reportBadTarget(ClassRegistry.clTab[ci].base.plus(ClassRegistry.clTab[ci].nameOff), vsName.get(idx),
                    vsDesc.get(idx), buf, 0);
            return VM.denylistTrapAddr;                 // and do NOT memoize it
        }
        vsMemoIdx = idx;
        vsMemoType = type;
        vsMemoBuf = buf;
        publishSiteMemo(idx, type, buf);
        return buf;
    }

    static long resolveViaInterfaces(int ci, Address nameU, Address descU)
    {
        AddressArray names = AddressArray.create(InterfaceInvocation.MAXIFACEFALLBACK);
        // WALK THE SUPERCLASS CHAIN, not just the receiver's own interfaces. A default method is declared by
        // an interface that ANY class in the chain may implement, and the receiver itself often implements
        // nothing: SuiteEngineDescriptor extends EngineDescriptor extends AbstractTestDescriptor implements
        // TestDescriptor, and `accept` is a DEFAULT on TestDescriptor -- two classes up. Collecting only the
        // receiver's own interfaces searched an EMPTY list and reported the method missing.
        //
        // A lookup that does not walk the chain has now been the bug three times here (globalVtableSlot's
        // slot numbering, vhFieldOffset's field offsets, and this). The names collected are ABSOLUTE Utf8
        // addresses, which is what makes the walk safe: collectIfaceNames re-parses each class's constant
        // pool and moves the cursor, and an offset would then be read against the wrong blob.
        int n = 0;
        int c = ci;
        int hops = 0;
        while (c >= 0 && hops < MAXIFACECHAIN)
        {
            n = collectIfaceNames(c, names, n);
            c = ClassRegistry.superRegOf(c);
            hops += 1;
        }
        // The superinterface CLOSURE, every level (JVMS 5.4.3.3 searches all superinterfaces). This walked ONE
        // level above the class chain's own interfaces, so a default declared three interfaces up was never
        // found. MEASURED: java.lang.classfile's StringEntryImpl -> StringEntry -> ConstantValueEntry ->
        // LoadableConstantEntry, whose default typeKind() failed late dispatch (VIRTUALRESOLVE FAILED) inside
        // SwitchBootstraps' generated switch (IndyDbg). Each interface is expanded once: names are per-blob
        // addresses, so the same interface can appear twice and is told apart by its registry index.
        int[] expanded = new int[InterfaceInvocation.MAXIFACEFALLBACK];
        int ne = 0;
        int i = 0;
        while (i < n)
        {
            int r = DynamicLinker.regBySigU(names.get(i));
            boolean seen = r < 0;
            int e = 0;
            while (!seen && e < ne)
            {
                seen = expanded[e] == r;
                e += 1;
            }
            if (!seen && ne < InterfaceInvocation.MAXIFACEFALLBACK)
            {
                expanded[ne] = r;
                ne += 1;
                n = collectIfaceNames(r, names, n);
            }
            i += 1;
        }
        viHops = hops;
        viIfaceN = n;
        viNames = names;
        // The MAXIMALLY SPECIFIC declarer first (JVMS 5.4.3.3): the collection order is the receiver's own
        // interfaces before its superclasses', which picks the less specific default whenever a class
        // implements the super-interface directly and inherits the overriding sub-interface.
        int[] regs = new int[n];
        int q = 0;
        while (q < n)
        {
            regs[q] = DynamicLinker.regBySigU(names.get(q));
            q += 1;
        }
        int decl = InterfaceInvocation.mostSpecificDeclarer(regs, n, nameU, descU);
        if (decl >= 0)
        {
            long d = DynamicLinker.resolveLinkTarget(ClassRegistry.clTab[decl].base.plus(ClassRegistry.clTab[decl].nameOff),
                    nameU, descU);
            if (d != 0L)
            {
                return d;
            }
        }
        int k = 0;
        while (k < n)
        {
            long buf = DynamicLinker.resolveLinkTarget(names.get(k), nameU,
                    descU);
            if (buf != 0L)
            {
                return buf;
            }
            k += 1;
        }
        return 0L;
    }

    /** Append class {@code ci}'s directly-declared interface name Utf8 ADDRESSES to {@code out}; new length. */
    private static int collectIfaceNames(int ci, AddressArray out, int n)
    {
        if (ClassRegistry.clTab == null || ci < 0 || ci >= ClassRegistry.clCount || ClassRegistry.clTab[ci] == null)
        {
            return n;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseConstPool(base, Loader.blobLenOf(base));
        int count = u2(base.plus(Loader.gView.afterCp + 6));            // interfaces_count (ClassReader.afterInterfaces layout)
        int k = 0;
        while (k < count && n < InterfaceInvocation.MAXIFACEFALLBACK)
        {
            int cpi = u2(base.plus(Loader.gView.afterCp + 8 + k * 2));
            if (cpi > 0)
            {
                out.set(n, base.plus(Loader.gView.cp[u2(base.plus(Loader.gView.cp[cpi]))]));   // CONSTANT_Class -> name_index -> Utf8 offset
                n += 1;
            }
            k += 1;
        }
        return n;
    }

    static void invalidateBakeMemos()
    {
        if (!BAKE_MEMO_REPAIR)
        {
            return;
        }
        if (VM.bakeStubTable == 0L || VM.bakeStubCount <= 0L)
        {
            return;
        }
        long i = 0L;
        while (i < VM.bakeStubCount)
        {
            if (Address.fromLong(VM.bakeStubTable).plus(i * 32L).plus(24L).loadLong() >= Heap.CODE_BASE)
            {
                Address.fromLong(VM.bakeStubTable).plus(i * 32L).plus(24L).store(0L);
                bakeMemosDropped += 1;
            }
            i += 1L;
        }
    }

    static void reportBakeMemoFor(int idx)
    {
        if (VM.bakeStubTable == 0L || VM.bakeStubCount <= 0L)
        {
            return;
        }
        long i = 0L;
        while (i < VM.bakeStubCount)
        {
            Address memo = Address.fromLong(VM.bakeStubTable).plus(i * 32L).plus(24L).loadAddress();
            if (!memo.isZero() && LazyCompiler.stubIdxAt(memo) == (long) idx)
            {
                Uart.write(Magic.bytes("  ... and the BAKE-STUB MEMO still returns it: entry="));
                VM.printDec((int) i);
                Uart.write(Magic.bytes(" stub=0x"));
                VM.printHex(memo);
                Uart.write(Magic.bytes(" for "));
                Loader.printUtf8Capped(Address.fromLong(VM.bakeStubTable).plus(i * 32L).loadAddress());
                Uart.putc(0x2E);
                Loader.printUtf8Capped(Address.fromLong(VM.bakeStubTable).plus(i * 32L).plus(8L).loadAddress());
                Loader.printUtf8Capped(Address.fromLong(VM.bakeStubTable).plus(i * 32L).plus(16L).loadAddress());
                Uart.putc(0x0A);
            }
            i += 1L;
        }
    }

    /**
     * Refuse to bake a stub whose tail-branch target is ZERO.
     *
     * <p>Four stub emitters read a trampoline address straight out of a static and {@code movz/movk} it into
     * x16 ahead of {@code br x16}. Nothing checked it. A zero there bakes {@code br 0} into the stub, and
     * because {@code br} does not write x30 the failure is invisible until that stub is finally entered --
     * at which point the firmware shim re-enters the image entry and the fault report names whatever healthy
     * call ran last. Two arcs of this project have been spent inside reports built that way.
     *
     * <p>So the check goes at EMIT time, not at branch time: the stub is permanently broken the moment it is
     * written, and here the method it stands for is still in hand. A report thousands of batches later is
     * not the same information.
     *
     * @param kind 1 = a lazy/deferral stub (idx indexes lzTab), 2 = a link stub
     */
    static void checkTramp(Address tramp, int idx, int kind)
    {
        if (!tramp.isZero())
        {
            return;
        }
        Uart.write(Magic.bytes("\n*** STUB WOULD BRANCH TO 0: the "));
        if (kind == 1)
        {
            Uart.write(Magic.bytes("lazy-compile"));
        }
        else
        {
            Uart.write(Magic.bytes("link"));
        }
        Uart.write(Magic.bytes(" trampoline is not built yet ***\n    stub idx="));
        VM.printDec(idx);
        if (kind == 1 && LazyCompiler.lzTab != null && idx >= 0 && idx < LazyCompiler.lzTab.length
                && LazyCompiler.lzTab[idx] != null && !LazyCompiler.lzTab[idx].blob.isZero() && LazyCompiler.lzTab[idx].nameOff != 0)
        {
            Uart.write(Magic.bytes(" for "));
            Loader.printUtf8Capped(LazyCompiler.lzTab[idx].blob.plus(LazyCompiler.lzTab[idx].nameOff));   // Utf8-shaped: u2 length, then bytes
        }
        Uart.write(Magic.bytes("\n    Halting HERE, where the stub is still nameable. `br` does not write x30,"));
        Uart.write(Magic.bytes("\n    so a 0 baked now is an untraceable reboot whenever this stub is entered.\n"));
        while (true)
        {
            Magic.wfe();
        }
    }

    /**
     * Emit the zero-target guard that must precede a resolve trampoline's {@code br x16}.
     *
     * <p>All three trampolines end by tail-branching to whatever the resolver handed back. A {@code br} does
     * NOT write x30, so a zero target branches to address 0 and the firmware shim re-enters the image entry
     * carrying a STALE x30 -- the wild branch erases its own provenance, and every frame the fault report
     * then derives is an artifact. This project has lost two arcs to exactly that, the second one to a report
     * that named a perfectly healthy {@code blr} in {@code String.valueOf(Object)}.
     *
     * <p>Emitted HERE, immediately after {@code x16 = target} and while the frame is still up, because that
     * is the only point where the caller's return address is still on the stack at {@code STUB_LR_OFF}: the
     * guard can therefore name the CALL SITE exactly instead of guessing it from a register the branch
     * destroys. Costs one {@code cbnz} on the path that works.
     *
     * <p>Emits nothing if the helper was never stashed -- a call to address 0 is the very failure this
     * exists to prevent, and an instrument that causes it would be worse than none (see the {@code watchRecv}
     * stash that shipped emitting exactly that).
     *
     * @param kind 1 = lazy-compile, 2 = link-stub, 3 = late-virtual
     */
    static int emitTailGuard(Address buf, int w, int kind)
    {
        long ha = VM.badTailTargetAddr;
        if (ha == 0L)
        {
            return w;
        }
        buf.plus(w * 4L).store(A64Enc.cbnz(16, 7));                   // target ok: skip
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movz(0, kind, 0));                   // x0 = which trampoline
        w += 1;
        buf.plus(w * 4L).store(A64Enc.ldrx(1, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF));                   // x1 = the call site
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (ha & 0xFFFF), 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ha >> 16) & 0xFFFF), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ha >> 32) & 0xFFFF), 2));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.blr(16));                   // halts; no return
        w += 1;
        return w;
    }

    /**
     * The late-dispatch trampoline: the twin of {@link DynamicLinker#buildLinkTramp}, but the receiver is already in x0 and
     * the site index arrives in x17. Saves x0..x15 + LR so the resolve cannot disturb the arguments, then
     * tail-branches to the target -- so the callee returns straight to the original call site.
     */
    static Address virtualTramp()
    {
        if (virtualTrampAddr != 0L)
        {
            return Address.fromLong(virtualTrampAddr);
        }
        Address buf = Heap.allocCode(256);
        long ra = VM.virtualResolveAddr;
        int w = 0;
        buf.plus(w * 4L).store(A64Enc.subImm(StubFrames.STUB_SP, StubFrames.STUB_SP, StubFrames.STUB_FRAME));
        w += 1;
        int r = 0;
        while (r <= StubFrames.STUB_SAVE_HI)
        {
            buf.plus(w * 4L).store(A64Enc.strx(r, StubFrames.STUB_SP, StubFrames.STUB_SAVE0 + r * ObjectModel.WORD));
            w += 1;
            r += 1;
        }
        buf.plus(w * 4L).store(A64Enc.strx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF)); // LR at [sp+0]: what VM.unwind reads to pop this frame
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movReg(1, 17)); // x1 = site idx (x0 IS the receiver)
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (ra & 0xFFFF), 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ra >> 16) & 0xFFFF), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((ra >> 32) & 0xFFFF), 2));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.blr(16)); // x0 = resolved target
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movReg(16, 0)); // x16 = target (outside the restore set)
        w += 1;
        // A zero target here would `br` to address 0 -- and `br` does not write x30, so the wild
        // branch would erase its own provenance. Check while the frame is still up: the call site
        // is on it (late-virtual trampoline).
        w = emitTailGuard(buf, w, 3);
        r = 0;
        while (r <= StubFrames.STUB_SAVE_HI)
        {
            buf.plus(w * 4L).store(A64Enc.ldrx(r, StubFrames.STUB_SP, StubFrames.STUB_SAVE0 + r * ObjectModel.WORD));
            w += 1;
            r += 1;
        }
        buf.plus(w * 4L).store(A64Enc.ldrx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF));
        w += 1;
        virtualTrampHi = buf.toLong() + w * 4L;                  // the `add sp` below tears the frame down
        buf.plus(w * 4L).store(A64Enc.addImm(StubFrames.STUB_SP, StubFrames.STUB_SP, StubFrames.STUB_FRAME));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.br(16)); // tail-call: returns to the call site
        w += 1;
        Heap.publishCode(buf, buf.plus(w * 4L));
        virtualTrampAddr = buf.toLong();
        virtualTrampLo = buf.toLong() + 4L;                      // the `sub sp` above establishes it
        StubFrames.registerTrampFrames();
        return buf;
    }
}
