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

import board.bcm2711.Uart;
import magic.Address;
import magic.AddressArray;
import magic.Magic;

import static vm.ClassBytes.u2;

/**
 * Virtual method tables (JikesRVM: the vtable half of {@code RVMClass.resolve()}): the flattened per-class vtable
 * layout (superclass slots first, overrides in place, new methods appended), the global slot registry
 * ({@code vt*}) and its hash index, slot lookup by name+descriptor, and the image/loader vtable parity checks.
 * Split out of {@link Loader}.
 */
final class VtableLayout
{
    private VtableLayout()
    {
    }

    // Vtable-slot registry: per virtual method of each class, its class/name/desc
    // (base+offset) and vtable slot, so a cross-class invokevirtual can find the
    // slot in the receiver class's vtable (dispatch itself uses the object's TIB).
    static final int MAXVT = 65536;
    static AddressArray vtClassBase;   // class the vtable belongs to (base + off)
    static int[] vtClassOff;
    static AddressArray vtNameBase;    // method signature blob (may be a superclass's)
    static int[] vtNameOff;
    static int[] vtDescOff;
    static int[] vtSlot;
    static long[] vtBuf;         // slot's implementation buffer
    static int vtCount;
    /** Copy the registered flattened vtable of the class named {@code superOff} into gv[]. */
    /**
     * Name-keyed index over the vtable registry, so {@link #inheritVtable} is a PROBE rather than a scan of
     * every entry ever registered.
     *
     * <p>MEASURED, and this is where `parse` actually went. Splitting phase B's parse three ways gave
     * {@code cp=9.9ms fields=313ms vtab=4773ms} -- `parseVtable` is 94% of it -- and inside parseVtable the
     * only thing whose cost grows is this: {@code vtCount} is cleared by {@code resetLoader} (per LAUNCH,
     * not per class), so it holds every vtable entry of every class loaded so far. At ~1342 classes with
     * ~20 virtuals each that is ~27,000 entries, string-compared once PER CLASS -- tens of millions of
     * utf8EqAt calls. Seventh instance of this file's most common defect: a per-item linear scan of a table
     * that grows all boot.
     *
     * <p>ASCENDING ORDER IS PRESERVED, and that is not caution for its own sake: the scan applies matching
     * entries in index order and a later entry writes the same {@code gvTab[slot]} as an earlier one, so
     * reversing the walk could silently install a different implementation in a slot. A bucket chain built
     * by the usual head-insertion yields NEWEST FIRST, so this one appends at the TAIL and keeps the walk
     * ascending -- the same care {@code defaultBySig}'s index needed for closure order.
     *
     * <p>Incremental like {@code rgBucket}/{@code dlBucket}: the registry only grows within a launch and an
     * entry's class name never changes, so entries already indexed stay valid; it is rebuilt from scratch
     * when the count is seen to go backwards (a {@code resetLoader}).
     */
    private static final int VTTAB = 8192;               // power of two; chained
    static int[] vtBucket;                       // class-name hash -> FIRST entry, -1 when empty
    private static int[] vtBucketTail;                   // ... and the last, so appends keep the chain ascending
    private static int[] vtNextIdx;                      // entry -> next entry with the same class-name hash
    private static int vtIndexed;
    /**
     * Vtable slot of a cross-class virtual/interface method. For invokevirtual the
     * ref's class is the (loaded) declaring class, so a class+name+descriptor match
     * finds the slot. For invokeinterface the ref's class is the interface (not a
     * loaded class), so that match fails and we fall back to name+descriptor — sound
     * while a single loaded class implements it.
     */
    static int logVtable;                               // #43 diagnostic: when != 0, log high-slot vtable resolutions
    /** Name every {@code invokevirtual} whose vtable slot could not be resolved at compile time (debug). Each
     *  one is a silent wrong dispatch through slot 0 -- see {@link #globalVtableSlot}. */
    private static final boolean VT_TRACE = false;
    static long gVtSigSlots;    // matched vtSig entry's slot-pair table (0 = class not baked)
    static int gVtSigCount;     // ... its slot count

    /**
     * Build this class's flattened vtable: inherit the superclass's slots (copied
     * from the super's registered vtable — signature + already-compiled impl
     * buffer), then walk this class's own virtual methods, an override replacing the
     * inherited slot in place (so it keeps the super's index) and a new method
     * appending. buildTib later fills the TIB from these slots.
     */
    static void parseVtable(Address bytes)
    {
        Loader.gvCount = 0;
        int superReg = ClassRegistry.classRegByName(Loader.gSuperNameOff);
        if (superReg >= 0)
        {
            inheritVtable(Loader.gSuperNameOff);               // copy the super's flattened slots
        }
        Address p = Loader.gMethodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));                      // access, name, descriptor, attrs
            if (Loader.isVirtual(u2(p), Loader.gcp[u2(p.plus(2))]))
            {
                int slot = findVtSlot(Loader.gcp[u2(p.plus(2))], Loader.gcp[u2(p.plus(4))]);   // override an inherited slot?
                if (slot < 0)
                {
                    if (Loader.gvCount >= Loader.MAXMV) { Loader.capHalt(Magic.bytes("Loader.MAXMV-vt"), Loader.gvCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
                    slot = Loader.gvCount;                     // else append a new slot
                    Loader.gvCount += 1;
                }
                Loader.gvTab[slot].base = Loader.gbase;
                Loader.gvTab[slot].name = Loader.gcp[u2(p.plus(2))];
                Loader.gvTab[slot].desc = Loader.gcp[u2(p.plus(4))];
                Loader.gvTab[slot].implCode = Loader.findCode(bytes, p.plus(8), attrs);   // this class's own impl
                Loader.gvTab[slot].isSync = (u2(p) & 0x0020) != 0 ? 1 : 0;     // ACC_SYNCHRONIZED (for mintPrunedStub)
                Loader.gvTab[slot].implBuf = 0L;
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
    }

    private static void buildVtIndex()
    {
        if (vtBucket == null || vtIndexed > vtCount)      // resetLoader zeroed the registry: rebuild
        {
            vtBucket = new int[VTTAB];
            vtBucketTail = new int[VTTAB];
            vtNextIdx = new int[MAXVT];
            int b = 0;
            while (b < VTTAB)
            {
                vtBucket[b] = -1;
                vtBucketTail[b] = -1;
                b += 1;
            }
            vtIndexed = 0;
        }
        while (vtIndexed < vtCount)
        {
            int h = Utf8.utf8Hash(vtClassBase.get(vtIndexed), vtClassOff[vtIndexed]) & (VTTAB - 1);
            vtNextIdx[vtIndexed] = -1;
            if (vtBucketTail[h] < 0)
            {
                vtBucket[h] = vtIndexed;
            }
            else
            {
                vtNextIdx[vtBucketTail[h]] = vtIndexed;   // APPEND: keeps the chain in ascending index order
            }
            vtBucketTail[h] = vtIndexed;
            vtIndexed += 1;
        }
    }

    /**
     * The vtable-table entry of class {@code cls} (a Utf8 run) whose name and descriptor match, or -1 -- the
     * FIRST such entry in table order, which is what the front-to-back scans this replaces answered: the
     * class-name chains are kept ascending, so the first hit in the chain is that entry.
     *
     * <p>Those scans compared class, name and descriptor byte by byte across EVERY entry of every class, and
     * {@link #globalVtableSlot} repeated the scan once per superclass level for an inherited method. Profiled on
     * InvokeGapProbe (QEMU, 2026-10-28): byte compares were ~60% of the busy core, under
     * {@code lowerInvokeVirtual -> globalVtableSlot} and {@code virtualResolve -> bufBySigU}; guest code was
     * under 4%. A class's chain is its own flattened vtable plus hash neighbours.
     */
    static int vtEntryOf(Address clsBase, int clsOff, Address nameBase, int nameOff, Address descBase, int descOff)
    {
        buildVtIndex();
        int i = vtBucket[Utf8.utf8Hash(clsBase, clsOff) & (VTTAB - 1)];
        while (i >= 0)
        {
            // NAME FIRST, CLASS LAST. The chain is this class's own entries plus hash neighbours, so the class
            // compare SUCCEEDS -- byte by byte, over the whole name -- on almost every step, and only then did
            // the method name reject the entry. MEASURED (SslEngineProbe, QEMU, 2026-11-07): 109,025 calls
            // walking 4,017,803 entries, 3,753,438 of them full successful class-name compares. A method name
            // rejects on its length or its first bytes. The three tests are pure and ANDed, so only the order
            // of rejection changes, never the answer.
            if (Loader.utf8EqAt(nameBase, nameOff, vtNameBase.get(i), vtNameOff[i])
                    && Loader.utf8EqAt(descBase, descOff, vtNameBase.get(i), vtDescOff[i])
                    && Loader.utf8EqAt(clsBase, clsOff, vtClassBase.get(i), vtClassOff[i]))
            {
                return i;
            }
            i = vtNextIdx[i];
        }
        return -1;
    }

    private static void inheritVtable(int superOff)
    {
        buildVtIndex();                                  // incremental: only entries added since last time
        int i = vtBucket[Utf8.utf8Hash(Loader.gbase, superOff) & (VTTAB - 1)];
        while (i >= 0)
        {
            // The SAME predicate the scan used -- the index only narrows which entries it is applied to, so
            // a hit is the entry the scan would have found and a miss still applies nothing.
            if (Loader.utf8EqAt(Loader.gbase, superOff, vtClassBase.get(i), vtClassOff[i]))
            {
                int slot = vtSlot[i];
                Loader.gvTab[slot].base = vtNameBase.get(i);
                Loader.gvTab[slot].name = vtNameOff[i];
                Loader.gvTab[slot].desc = vtDescOff[i];
                Loader.gvTab[slot].implBuf = vtBuf[i];             // inherited (already-compiled) impl
                Loader.gvTab[slot].implCode = 0L;
                Loader.gvTab[slot].isSync = 0;                     // inherited: the buffer is already compiled
                if (slot + 1 > Loader.gvCount)
                {
                    Loader.gvCount = slot + 1;
                }
            }
            i = vtNextIdx[i];
        }
    }

    /** Flattened-vtable slot whose name+descriptor match, or -1 (used for override detection). */
    private static int findVtSlot(int nameOff, int descOff)
    {
        int s = 0;
        while (s < Loader.gvCount)
        {
            if (Loader.utf8EqAt(Loader.gbase, nameOff, Loader.gvTab[s].base, Loader.gvTab[s].name)
                    && Loader.utf8EqAt(Loader.gbase, descOff, Loader.gvTab[s].base, Loader.gvTab[s].desc))
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    /**
     * PHASE B: fill the vtable BUFFERS for class {@code reg}'s registered slots, now that its bodies are compiled
     * ({@code slotBuf} = this class's own compiled buffer, or -- for an inherited slot -- the super's, which
     * phase B filled first because it runs superclass-first). Subclasses then inherit real buffers via parseVtable.
     */
    static void fillClassVtBuf(int reg)
    {
        int start = ClassRegistry.clTab[reg].vtStart;
        int cnt = ClassRegistry.clTab[reg].vtCount;
        int v = 0;
        while (v < cnt)
        {
            vtBuf[start + v] = Loader.slotBuf(v);             // gv* is current (this class's phase-B parseVtable just ran)
            v += 1;
        }
    }

    /** Like {@link #findVtSlot} but for a name+descriptor living in another blob. */
    static int findVtSlotAt(Address base, int nameOff, int descOff)
    {
        int s = 0;
        while (s < Loader.gvCount)
        {
            if (Loader.utf8EqAt(base, nameOff, Loader.gvTab[s].base, Loader.gvTab[s].name)
                    && Loader.utf8EqAt(base, descOff, Loader.gvTab[s].base, Loader.gvTab[s].desc))
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    /**
     * The vtable slot for Methodref {@code idx} resolved ONLY against the class the ref names — no name+
     * descriptor fallback. -1 when that class has no such slot, which is the question
     * {@link InterfaceInvocation#defaultDispatch} needs answered honestly: {@link #globalVtableSlot}'s fallback would hand back
     * an unrelated class's slot rather than admit the miss.
     */
    static int exactVtableSlot(int idx)
    {
        if (Loader.utf8Eq(Loader.refClassNameOff(idx), Loader.gThisNameOff))     // the class being compiled: its own flattened vtable
        {
            int own = findVtSlot(Loader.mrefNameOff(idx), Loader.mrefDescOff(idx));
            if (own >= 0)
            {
                return own;
            }
        }
        int i = vtEntryOf(Loader.gbase, Loader.refClassNameOff(idx), Loader.gbase, Loader.mrefNameOff(idx), Loader.gbase, Loader.mrefDescOff(idx));
        return i >= 0 ? vtSlot[i] : -1;
    }

    static int globalVtableSlot(int idx)
    {
        int classOff = Loader.refClassNameOff(idx);
        int nameOff = Loader.mrefNameOff(idx);
        int descOff = Loader.mrefDescOff(idx);
        int i = vtEntryOf(Loader.gbase, classOff, Loader.gbase, nameOff, Loader.gbase, descOff);   // class-qualified (invokevirtual)
        if (i >= 0)
        {
            logVtableSlot(classOff, nameOff, descOff, vtSlot[i], 0x51);   // 'Q' class-qualified
            return vtSlot[i];
        }
        // INHERITED: walk the referenced class's OWN SUPERCLASS CHAIN, most-derived first. That is what
        // virtual dispatch means, and the slot is only meaningful within this chain's flattened numbering.
        int reg = ClassRegistry.classRegByName(classOff);
        int chain = reg;
        while (chain >= 0)
        {
            i = vtEntryOf(ClassRegistry.clTab[chain].base, ClassRegistry.clTab[chain].nameOff, Loader.gbase, nameOff, Loader.gbase, descOff);
            if (i >= 0)
            {
                logVtableSlot(classOff, nameOff, descOff, vtSlot[i], 0x48);   // 'H' cHain
                return vtSlot[i];
            }
            chain = ClassRegistry.superRegOf(chain);
        }
        // NO name+descriptor fallback over unrelated classes. A slot number is an index into ONE class's
        // flattened vtable, so matching some other class's method and returning ITS slot indexes whatever
        // happens to sit at that position in the receiver. That was a live bug: `ZipInputStream.close()V` and
        // `ZipOutputStream.close()V` both resolved to slot 18 -- at most one can be right, and in
        // ZipOutputStream slot 18 is setMethod(I)V, so closing the stream called setMethod with a header
        // value and threw "invalid compression method".
        //
        // This path serves invokevirtual ONLY (Baseline calls symbols.vtableSlot there; invokeinterface goes
        // through ifSlotOf), so nothing needs the cross-class guess. Falling through to -1 costs nothing: the
        // caller lowers a late-dispatch site that resolves against the RECEIVER's own class -- which is the
        // right answer precisely when the referenced class is not registered yet (a deferred `new`, a
        // reflectively reached body), the case the old fallback was silently guessing at.
        // A MISS IS ALWAYS A BUG: returning 0 dispatches through vtable slot 0 of whatever the receiver
        // happens to be -- one of java/lang/Object's nine virtuals -- so the call silently returns the wrong
        // thing instead of failing. It happens when the referenced class is not registered at COMPILE time,
        // which is routine for a method reached only reflectively: it is compiled on demand, and its callees'
        // classes were never pulled. Static call sites got late resolution (link stubs) in PR #192; virtual
        // ones still need it. VT_TRACE names the sites meanwhile.
        if (VT_TRACE)
        {
            Uart.write(Magic.bytes("  VTMISS "));
            Loader.writeName(Loader.gbase.plus(classOff + 2), u2(Loader.gbase.plus(classOff)));
            Uart.putc(0x2E);
            Loader.writeName(Loader.gbase.plus(nameOff + 2), u2(Loader.gbase.plus(nameOff)));
            Uart.putc(0x0A);
        }
        return -1;                                      // unresolved -> the caller lowers a late-dispatch site
    }

    /** #43: print a high-slot vtable resolution (class.name slot [Q|F]) so a garbage-slot wild-branch is traceable. */
    private static void logVtableSlot(int classOff, int nameOff, int descOff, int slot, int path)
    {
        if (Loader.METHOD_WATCH_ON
                && (Utf8.utf8IsAtBase(Loader.gbase, nameOff, Magic.bytes("success"))
                    || Utf8.utf8IsAtBase(Loader.gbase, nameOff, Magic.bytes("blockingFailure"))))
        {
            // Watch two SPECIFIC methods regardless of slot. picocli's GroupMatchContainer.validate calls
            // `validationResult.success()`; reflection proves success() answers TRUE on both SUCCESS
            // constants, while the direct invokevirtual behaves as though it answered false. If these two
            // no-arg booleans resolve to the SAME slot, success() is really running blockingFailure() --
            // which returns false for a SUCCESS and produces exactly the observed maybeThrow(null).
            Uart.write(Magic.bytes("  VW "));
            Loader.writeName(Loader.gbase.plus(classOff + 2), u2(Loader.gbase.plus(classOff)));
            Uart.putc(0x2E);
            Loader.writeName(Loader.gbase.plus(nameOff + 2), u2(Loader.gbase.plus(nameOff)));
            Uart.write(Magic.bytes(" slot "));
            VM.printDec(slot);
            Uart.putc(0x20);
            Uart.putc((byte) path);
            Uart.putc(0x0A);
        }
        if (logVtable == 0 || slot < 20) { return; }
        Uart.write(Magic.bytes("  V "));
        Loader.writeName(Loader.gbase.plus(classOff + 2), u2(Loader.gbase.plus(classOff)));
        Uart.putc(0x2E);
        Loader.writeName(Loader.gbase.plus(nameOff + 2), u2(Loader.gbase.plus(nameOff)));
        Uart.putc(0x20);
        VM.printDec(slot);
        Uart.putc(0x20);
        Uart.putc((byte) path);
        Uart.putc(0x0A);
    }

    /** Find the current class's vtSig/adoption entry: sets gAdoptType/gAdoptStatics + parity data. */
    static void findVtSig()
    {
        Loader.gAdoptType = Address.zero();
        gVtSigSlots = 0L;
        gVtSigCount = 0;
        Loader.gAdoptStatics = Address.zero();
        Loader.gAdoptStaticCount = 0;
        int n = (int) VM.vtSigCount;
        int i = 0;
        while (i < n)
        {
            Address e = Address.fromLong(VM.vtSigTable + (long) i * 48L);
            if (Loader.utf8EqAt(Loader.gbase, Loader.gThisNameOff, e.loadAddress(), 0))
            {
                gVtSigSlots = e.plus(8L).loadLong();
                gVtSigCount = (int) e.plus(16L).loadLong();
                Loader.gAdoptType = e.plus(24L).loadAddress();
                Loader.gAdoptStatics = e.plus(32L).loadAddress();
                Loader.gAdoptStaticCount = (int) e.plus(40L).loadLong();
                return;
            }
            i += 1;
        }
    }

    static void checkVtParity()
    {
        findVtSig();
        if (gVtSigSlots != 0L)
        {
            vtParityAt(gVtSigSlots, gVtSigCount);
        }
    }

    /** Compare the current class's gv flattening against writer slot signatures at {@code slots}. */
    private static void vtParityAt(long slots, int count)
    {
        if (Loader.LOAD_LOG)                                   // header only; every DIFF below prints its own
        {
            Uart.write(Magic.bytes("  vtparity "));
            Loader.printNameAt(Loader.gbase, Loader.gThisNameOff);
        }
        if (count != Loader.gvCount)
        {
            vtParityHeader();
            Uart.write(Magic.bytes(" DIFF count "));
            VM.printDec(count);
            Uart.putc(0x2F);
            VM.printDec(Loader.gvCount);
            Uart.putc(0x0A);
            int w = 0;                                  // name the writer slots the loader lacks: a bare count
                                                        //   mismatch says nothing, and the missing names ARE
                                                        //   the diagnosis (six Object virtuals = no Object)
            while (w < count)
            {
                Address q = Address.fromLong(slots + (long) w * 16L);
                int f = 0;
                int found = 0;
                while (f < Loader.gvCount)
                {
                    if (Loader.utf8EqAt(Loader.gvTab[f].base, Loader.gvTab[f].name, q.loadAddress(), 0)
                            && Loader.utf8EqAt(Loader.gvTab[f].base, Loader.gvTab[f].desc, q.plus(8L).loadAddress(), 0))
                    {
                        found = 1;
                        f = Loader.gvCount;
                    }
                    f += 1;
                }
                if (found == 0)
                {
                    Uart.write(Magic.bytes("    missing slot "));
                    VM.printDec(w);
                    Uart.putc(0x20);
                    Loader.writeName(q.loadAddress().plus(2L), u2(q.loadLong()));
                    Loader.writeName(q.plus(8L).loadAddress().plus(2L), u2(q.plus(8L).loadLong()));
                    Uart.putc(0x0A);
                }
                w += 1;
            }
            return;
        }
        int s = 0;
        while (s < count)
        {
            Address p = Address.fromLong(slots + (long) s * 16L);
            if (!Loader.utf8EqAt(Loader.gvTab[s].base, Loader.gvTab[s].name, p.loadAddress(), 0)
                    || !Loader.utf8EqAt(Loader.gvTab[s].base, Loader.gvTab[s].desc, p.plus(8L).loadAddress(), 0))
            {
                vtParityHeader();
                Uart.write(Magic.bytes(" DIFF slot "));
                VM.printDec(s);
                Uart.putc(0x0A);
                return;
            }
            s += 1;
        }
        if (Loader.LOAD_LOG)
        {
            Uart.write(Magic.bytes(" OK "));
            VM.printDec(count);
            Uart.putc(0x0A);
        }
    }

    /** Print the vtparity header a DIFF needs when {@link Loader#LOAD_LOG} left it out. */
    private static void vtParityHeader()
    {
        if (!Loader.LOAD_LOG)
        {
            Uart.write(Magic.bytes("  vtparity "));
            Loader.printNameAt(Loader.gbase, Loader.gThisNameOff);
        }
    }

    /**
     * Vtable slot of the virtual method named by Methodref {@code idx}. Same-class
     * calls use this class's own vtable; a call whose ref names another class (a
     * cross-class {@code invokevirtual}) or an interface resolves via the global
     * vtable registry against the receiver class's layout.
     */
    static int vtableSlotOf(int idx)
    {
        // VarHandle ops are signature-polymorphic: the call-site descriptor is the actual arg types
        // (e.g. getAndBitwiseOr:(Ljava/net/Socket;I)I), not any declared method's. This USED to resolve by
        // NAME ALONE against a hand-written VarHandle overlay's vtable -- which is why one reference-typed
        // `set` served every `set` whatever its descriptor, storing an int AS A REFERENCE.
        //
        // Nothing is matched here now, deliberately: with stock VarHandle the implementation is the
        // template-generated handle's STATIC method (`set(VarHandle,Object,int)`), which is not a VarHandle
        // virtual at all, so there is no slot to answer with. Falling through means vtableSlot answers -1
        // and lowerInvokeVirtual takes its existing late-resolve path -- whose register layout already
        // matches, since an invokevirtual passes (x0=handle, x1=holder, x2=value) and that IS the generated
        // static's signature. virtualResolve then resolves against the receiver's own class.
        if (Loader.utf8Eq(Loader.refClassNameOff(idx), Loader.gThisNameOff))
        {
            int s = findVtSlot(Loader.mrefNameOff(idx), Loader.mrefDescOff(idx));   // this class's flattened vtable
            if (s >= 0)
            {
                logVtableSlot(Loader.refClassNameOff(idx), Loader.mrefNameOff(idx), Loader.mrefDescOff(idx), s, 0x53);   // 'S' same-class
                return s;
            }
        }
        int g = globalVtableSlot(idx);
        if (g < 0 && Loader.isObjectPublicMethod(idx))
        {
            // An Object method named through an INTERFACE (`inner.hashCode()` on an annotation-typed receiver
            // is `invokeinterface Inner.hashCode`, routed here by lowerInvokeInterface). The interface has no
            // vtable and its chain never reaches Object's entries, so this used to answer -1 and the site went
            // to VIRTUAL_RESOLVE -- which, against a synthesised annotation object (no registry entry), fell
            // back to Object.hashCode: an IDENTITY hash. Object's own slot is in the prefix every vtable shares,
            // so it dispatches to the receiver's override exactly as an Object-typed call does.
            g = objectVtableSlot(Loader.mrefNameOff(idx), Loader.mrefDescOff(idx));
        }
        return g;
    }

    /** {@code java/lang/Object}'s vtable slot for name+descriptor (offsets in the current blob), or -1. */
    private static int objectVtableSlot(int nameOff, int descOff)
    {
        int i = 0;
        while (i < vtCount)
        {
            if (Utf8.utf8IsAtBase(vtClassBase.get(i), vtClassOff[i], Magic.bytes("java/lang/Object"))
                    && Loader.utf8EqAt(Loader.gbase, nameOff, vtNameBase.get(i), vtNameOff[i])
                    && Loader.utf8EqAt(Loader.gbase, descOff, vtNameBase.get(i), vtDescOff[i]))
            {
                return vtSlot[i];
            }
            i += 1;
        }
        return -1;
    }
}
