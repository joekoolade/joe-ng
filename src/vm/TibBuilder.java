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

import board.Uart;
import magic.Address;
import magic.DataReference;
import magic.DataReferenceArray;
import magic.AddressArray;
import magic.Magic;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Type Information Blocks (JikesRVM: {@code objectmodel/TIB} + {@code RVMClass.instantiate()}): allocating and
 * filling a class's TIB {Type, vtable...} and its reference map, java/lang/Object's vtable and filling it into
 * TIBs that inherit it, and the registry of SYNTHESISED TIBs -- lambdas, proxies and annotation instances -- whose
 * Object slots are refilled as more of java.base compiles. Split out of {@link Loader}.
 */
final class TibBuilder
{
    private TibBuilder()
    {
    }

    // GC ROOTS for synthesised lambda classes. A lambda's TIB/Type/itableDir/imap are built by finishLambdaClass
    // in the GC heap [BASE,PTR) and are referenced ONLY by (a) baked immediates in JIT code (not scanned) and
    // (b) its runtime instances' object headers (word 0, which the collector does not trace). Unlike loaded
    // CLASSES (rooted via clTib[]), array types (refArrTib[]) and string literals (litAnchor[]), lambda metadata
    // sat in no scanned root — so once a large closure (java.util.stream) fills the arena and GC runs during load
    // (before any instance exists), the lambda TIB was swept and a reused block scribbled its Type to garbage,
    // making a later Function.apply on it NPE. Holding each TIB here (a scanned static array) keeps it live.
    static final int MAXLAMBDATIB = 8192;
    static DataReferenceArray lambdaTibRoots;
    // Vtable CAPACITY of each synthesised TIB above, so refillSynthTibVtables can never write past one. The
    // capacity is whatever java/lang/Object's vtable count was WHEN THE TIB WAS BUILT, and Object may not have
    // been registered yet (objectVtableCount answers 0 then) -- refilling blind would then overflow an 8-byte
    // allocation once Object arrives with nine virtuals.
    static int[] lambdaTibVtCap;
    // FILLED TO CAPACITY ALREADY -- one bit per synthesised TIB, and the whole of the `synth` fix.
    //
    // MEASURED before it was believed: `refillSynthTibVtables` walked every synthesised TIB on EVERY batch
    // and re-copied Object's vtable into each, and the counters said **`chg=0`** -- across a whole suite
    // boot, not ONE of those writes stored a value different from the one already in the slot. The pass was
    // re-deriving an answer it already had, which is the `seeds` shape exactly (there: 148x).
    //
    // Sound by construction as well as by that measurement: `java/lang/Object` is THE ONE EAGERLY-COMPILED
    // CLASS (its nine virtuals are the prefix of every vtable in both worlds), so its slots hold real bodies
    // from registration and are never re-pointed by a lazy compile the way a deferred class's are. `chg=0`
    // is the empirical half of that argument and is kept on the batch line so a future boot can refute it.
    //
    // LATCHED ONLY ON A COMPLETE FILL (`n >= cap`), not merely on having run -- the rule the seeds fix had
    // to be corrected for. A TIB built while Object had fewer virtuals than the TIB has room for is filled
    // PARTIALLY; latching there would leave the rest zero for ever, and a 0 slot reached from baked code is
    // a wild branch rather than a named trap.
    static boolean[] lambdaTibFilled;
    // The HOST class of each synthesised TIB above -- the class whose method the lambda was compiled in -- as
    // its blob base + name Utf8 offset, so getName() can answer stock's `Host$$Lambda/0x...`. 0 for the
    // synthesised kinds that have no host (annotation instances, proxies), which keep the bare `$$Lambda/`.
    static AddressArray lambdaHostBase;
    static int[] lambdaHostNameOff;
    static int lambdaTibRootN;
    // `synth` -- `refillSynthTibVtables`, 191.718ms at batch 209 and the largest item left outside `callT`
    // and `imap`. It has never been split, which is the same starting position `seeds` (148x) and `statT`
    // (42.5x) were in. It walks every synthesised lambda/annotation TIB on EVERY batch and re-copies
    // Object's vtable into each, and `n:synth` grows all boot (511 at batch 59, 2276 at batch 209).
    //
    // READING NAMES TWO CANDIDATES AND CANNOT ORDER THEM, which is why they are counted first:
    //   (1) `fillObjectVtableUpTo` calls `objectClassIndex()` -- a linear walk of all `clCount` classes --
    //       ONCE PER TIB, so 2276 scans a batch. The remedy would be hoisting the lookup out of the loop,
    //       which is what `printFrameAt` needed in the demand-load arc.
    //   (2) The whole pass may be IDEMPOTENT. It exists to repair a TIB built before Object's body was
    //       compiled; once repaired, re-copying the same nine words changes nothing. That is the `seeds`
    //       shape exactly, and there a one-shot flag was worth 148x.
    // `syChanged` is what decides between them: it counts only the writes whose value actually DIFFERED
    // from what the slot already held. Near zero means the pass is re-deriving an answer it already has and
    // a watermark makes it free; large means the writes are real and only the hoist is available.
    //
    // The changed-check costs one extra load per slot, so this instrument slightly inflates the thing it
    // measures -- stated rather than glossed, and it is removed or kept per whichever fix lands.
    static long syTibs;                                 // synthesised TIBs visited (cumulative, all batches)
    static long sySlots;                                // ... vtable slots written
    static long syChanged;                              // ... of which actually CHANGED the stored word

    static int isSynthesisedTib(Address tib)
    {
        if (lambdaTibRoots == null)
        {
            return 0;
        }
        int i = 0;
        while (i < lambdaTibRoots.length())
        {
            if (lambdaTibRoots.get(i).toAddress().EQ(tib))
            {
                return 1;
            }
            i += 1;
        }
        return 0;
    }

    /**
     * Build this class's Type (a one-word node holding its superclass's Type, so
     * {@code instanceof} can walk the chain) and its TIB: slot 0 is the Type, then
     * one vtable entry per flattened slot. {@code new} stores the TIB into each
     * instance's header, so an object reaches both its vtable and its Type.
     */
    static void buildTib()
    {
        allocTib();
        fillTib();
    }

    /**
     * Allocate this class's Type + TIB and set the fixed parts (Type node, TIB[0]=Type). The vtable slots and
     * itable directory are filled later by {@link #fillTib} once the methods are placed. Splitting the alloc
     * out lets loadOne register the class and pin a stable {@code gBuild.tib} address BEFORE compiling its
     * {@code <clinit>}, so a {@code new Self()} in that initializer (e.g. {@code Unsafe.theUnsafe = new Unsafe()})
     * bakes the correct TIB rather than the previous class's stale TIB. The <clinit> runs deferred
     * (after patchRelocs), by which point {@link #fillTib} has populated the vtable.
     */
    static void allocTib()
    {
        int sr = ClassRegistry.classRegByName(Loader.gView.superNameOff);
        // ObjectModel layout: Type = { instanceSize@0, superType@8, itableDir@16 } (24
        // bytes), so VM.instanceOf and the shared Baseline core read JIT'd objects the
        // same way they read image objects (M5.4.e). The itableDir slot currently holds
        // the flat imap; step 2 replaces it with a proper itable directory.
        // M8 Type adoption: a baked class ADOPTS the writer's Type node instead of allocating its
        // own -- one Type per class across both worlds. The two stores below are then idempotent
        // (instanceSize matches by field-layout parity; superType matches because supers adopt
        // too, so clTab[sr].type IS the writer's super node); itableDir stays loader-owned
        // (buildTib overwrites it with this world's imap dir -- the writer side never reads it).
        if (!Loader.gBuild.adoptType.isZero())
        {
            Loader.gBuild.type = Loader.gBuild.adoptType;
            if (Loader.LOAD_LOG)
            {
                Uart.write(Magic.bytes("  typeadopt "));
                Loader.printNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
                Uart.putc(0x0A);
            }
        }
        else
        {
            Loader.gBuild.type = Heap.allocData(ObjectModel.TYPE_SIZE);
        }
        Loader.gBuild.type.plus(0).store((long) (16 + Loader.gBuild.ifCount * 8));       // TYPE_INSTANCE_SIZE_OFFSET
        Loader.gBuild.type.plus(8).store(sr >= 0 ? ClassRegistry.clTab[sr].type : DataReference.zero());   // TYPE_SUPER_OFFSET (0 at Object)
        if (Loader.gBuild.adoptType.isZero())
        {
            // O(1) type checks: metal-built class Types get a depth + display like the writer's
            // (adopted nodes already carry the writer's -- never overwrite with batch-heap arrays).
            Loader.gBuild.type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).store(0L);
            Loader.gBuild.type.plus(ObjectModel.TYPE_DEPTH_OFFSET).store(0L);
            Loader.gBuild.type.plus(ObjectModel.TYPE_DISPLAY_OFFSET).store(0L);
            Loader.gBuild.type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).store(0L);       // bitmap built at fillTib
            Loader.gBuild.type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).plus(8L).store(0L);
            DynamicTypeCheck.buildDisplay(Loader.gBuild.type.toAddress(), sr >= 0 ? ClassRegistry.clTab[sr].type.toAddress() : Address.zero());
            buildRefMap(Loader.gBuild.type.toAddress(), sr >= 0 ? ClassRegistry.clTab[sr].type.toAddress() : Address.zero());   // GC reference map (adopted nodes carry
        }                                                        //   the writer's -- never overwrite it)
        Loader.gBuild.tib = Heap.allocData((1 + VtableLayout.gvCount) * 8);
        Loader.gBuild.tib.store(Loader.gBuild.type);                      // TIB[0] = Type (slots filled by fillTib)
    }

    /**
     * Build this class's GC reference map into its Type node: the superclass's map (already built — phase A
     * runs super-first) plus one bit per OWN slot whose descriptor may hold a pointer. Same shape as
     * {@link DynamicTypeCheck#buildDisplay}/{@code buildImplBitmap}, and the same numbering the writer uses, so an adopted
     * Type and a metal-built one describe an instance identically.
     *
     * <p>Degrades to "no map" (both words 0, so the collector scans conservatively) when the super has no
     * map or the class is wider than the two words can describe — never to a map that under-describes an
     * object, which would free live data.
     */
    private static void buildRefMap(Address type, Address superType)
    {
        long w0 = 1L;                                   // bit 0: this map is computed
        long w1 = 0L;
        long r0 = 1L;                                   // S6 exact kinds (ObjectModel.TYPE_REFKIND_OFFSET):
        long r1 = 0L;                                   //   bit 0 of r0 = the kinds are computed
        long d0 = 0L;
        long d1 = 0L;
        if (!superType.isZero())
        {
            w0 = superType.plus(ObjectModel.TYPE_REFMAP_OFFSET).loadLong();
            w1 = superType.plus(ObjectModel.TYPE_REFMAP_OFFSET).plus(8L).loadLong();
            if ((w0 & 1L) == 0L)
            {
                clearMaps(type);                        // super is unmapped: so are we
                return;
            }
            r0 = superType.plus(ObjectModel.TYPE_REFKIND_OFFSET).loadLong();
            r1 = superType.plus(ObjectModel.TYPE_REFKIND_OFFSET).plus(8L).loadLong();
            d0 = superType.plus(ObjectModel.TYPE_DATAKIND_OFFSET).loadLong();
            d1 = superType.plus(ObjectModel.TYPE_DATAKIND_OFFSET).plus(8L).loadLong();
        }
        int s = Loader.superFieldCount();                      // own fields start after the inherited ones
        while (s < Loader.gBuild.ifCount)
        {
            if (s > ObjectModel.TYPE_REFMAP_MAX_SLOT)
            {
                clearMaps(type);                        // too wide to describe
                return;
            }
            int off = Loader.gBuild.ifDescOff[s];
            if (mayHoldPointer(off))
            {
                long bit = s < 63 ? 1L << (s + 1) : 1L << (s - 63);
                if (s < 63)
                {
                    w0 = w0 | bit;
                }
                else
                {
                    w1 = w1 | bit;
                }
                int kind = fieldKind(off);
                if (kind == 1)
                {
                    if (s < 63)
                    {
                        r0 = r0 | bit;
                    }
                    else
                    {
                        r1 = r1 | bit;
                    }
                }
                else if (kind == 3)
                {
                    if (s < 63)
                    {
                        d0 = d0 | bit;
                    }
                    else
                    {
                        d1 = d1 | bit;
                    }
                }
            }
            s += 1;
        }
        type.plus(ObjectModel.TYPE_REFMAP_OFFSET).store(w0);
        type.plus(ObjectModel.TYPE_REFMAP_OFFSET).plus(8L).store(w1);
        type.plus(ObjectModel.TYPE_REFKIND_OFFSET).store(r0);
        type.plus(ObjectModel.TYPE_REFKIND_OFFSET).plus(8L).store(r1);
        type.plus(ObjectModel.TYPE_DATAKIND_OFFSET).store(d0);
        type.plus(ObjectModel.TYPE_DATAKIND_OFFSET).plus(8L).store(d1);
    }

    // ---- maps for SYNTHESISED Types (lambdas, annotation instances, proxies): frames/GC arc S6b ----------------

    /** Start an empty, computed map + kinds on {@code type} (no slot may hold a pointer yet). */
    static void beginMaps(DataReference type)
    {
        Address t = type.toAddress();
        t.plus(ObjectModel.TYPE_REFMAP_OFFSET).store(1L);
        t.plus(ObjectModel.TYPE_REFMAP_OFFSET).plus(8L).store(0L);
        t.plus(ObjectModel.TYPE_REFKIND_OFFSET).store(1L);
        t.plus(ObjectModel.TYPE_REFKIND_OFFSET).plus(8L).store(0L);
        t.plus(ObjectModel.TYPE_DATAKIND_OFFSET).store(0L);
        t.plus(ObjectModel.TYPE_DATAKIND_OFFSET).plus(8L).store(0L);
    }

    /** Describe field slot {@code slot} of {@code type}: a value of JVMS descriptor character {@code c} ({@code L} or
     *  {@code [} = a reference, {@code J} = a raw long the old map still calls "may hold a pointer", anything else
     *  nothing). A slot past the maps' reach drops the whole map: the object is scanned conservatively. */
    static void mapSlot(DataReference type, int slot, int c)
    {
        Address t = type.toAddress();
        if (slot > ObjectModel.TYPE_REFMAP_MAX_SLOT)
        {
            clearMaps(t);
            return;
        }
        if (c != 0x4C && c != 0x5B && c != 0x4A)
        {
            return;
        }
        long word = slot < 63 ? 0L : 8L;
        long bit = slot < 63 ? 1L << (slot + 1) : 1L << (slot - 63);
        Address m = t.plus(ObjectModel.TYPE_REFMAP_OFFSET).plus(word);
        m.store(m.loadLong() | bit);
        if (c != 0x4A)
        {
            Address r = t.plus(ObjectModel.TYPE_REFKIND_OFFSET).plus(word);
            r.store(r.loadLong() | bit);
        }
    }

    /** Copy {@code from}'s map and kinds onto {@code to} (an object laid out exactly as {@code from}'s instances). */
    static void copyMaps(DataReference to, DataReference from)
    {
        int w = 0;
        while (w < 6)
        {
            long off = (long) ObjectModel.TYPE_REFMAP_OFFSET + w * 8L;
            to.toAddress().plus(off).store(from.toAddress().plus(off).loadLong());
            w += 1;
        }
    }

    /** No map and no kinds: the collector scans the object conservatively. */
    private static void clearMaps(Address type)
    {
        type.plus(ObjectModel.TYPE_REFMAP_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_REFMAP_OFFSET).plus(8L).store(0L);
        type.plus(ObjectModel.TYPE_REFKIND_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_REFKIND_OFFSET).plus(8L).store(0L);
        type.plus(ObjectModel.TYPE_DATAKIND_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_DATAKIND_OFFSET).plus(8L).store(0L);
    }

    /** The exact kind of the field whose descriptor Utf8 sits at {@code descOff}, as {@code ClassFile.fieldKind}
     *  decides it on the host: 1 = object reference, 3 = data reference, 0 = neither (primitive or raw). */
    private static int fieldKind(int descOff)
    {
        Address b = Loader.gView.base.toAddress();
        int k = u1(b.plus(descOff + 2));
        if (k == 0x5B)
        {
            return 1;                                   // '[': an array
        }
        if (k != 0x4C)
        {
            return 0;
        }
        if (Utf8.utf8IsAtBase(b, descOff, Magic.bytes("Lmagic/DataReference;")))
        {
            return 3;
        }
        if (Utf8.utf8IsAtBase(b, descOff, Magic.bytes("Lmagic/Address;"))
            || Utf8.utf8IsAtBase(b, descOff, Magic.bytes("Lmagic/Word;"))
            || Utf8.utf8IsAtBase(b, descOff, Magic.bytes("Lmagic/Offset;"))
            || Utf8.utf8IsAtBase(b, descOff, Magic.bytes("Lmagic/Extent;")))
        {
            return 0;
        }
        return 1;
    }

    /** Whether the field whose descriptor Utf8 sits at {@code descOff} may hold a pointer the collector
     *  must follow: a reference ({@code L}/{@code [}) or a {@code long} — this VM keeps raw TIB/Type/
     *  statics addresses in {@code long} fields ({@link RVMClass}), and skipping those would sweep the
     *  live metadata. A stored Utf8 offset points at the length {@code u2}, so the text starts at +2. */
    private static boolean mayHoldPointer(int descOff)
    {
        int k = u1(Loader.gView.base.plus(descOff + 2));
        return k == 0x4C || k == 0x5B || k == 0x4A;     // 'L' object, '[' array, 'J' long
    }

    /** Whether vtable slot {@code s} of the class being built is Object's hashCode, equals or toString. */
    private static boolean isBakedDispatchSlot(int s)
    {
        DataReference b = VtableLayout.gvTab[s].base;
        int n = VtableLayout.gvTab[s].name;
        int d = VtableLayout.gvTab[s].desc;
        return (Utf8.utf8IsAtBase(b.toAddress(), n, Magic.bytes("hashCode")) && Utf8.utf8IsAtBase(b.toAddress(), d, Magic.bytes("()I")))
                || (Utf8.utf8IsAtBase(b.toAddress(), n, Magic.bytes("equals")) && Utf8.utf8IsAtBase(b.toAddress(), d, Magic.bytes("(Ljava/lang/Object;)Z")))
                || (Utf8.utf8IsAtBase(b.toAddress(), n, Magic.bytes("toString")) && Utf8.utf8IsAtBase(b.toAddress(), d, Magic.bytes("()Ljava/lang/String;")));
    }

    /** Fill the vtable slots (this class's + inherited impl code) and the itable directory. */
    static void fillTib()
    {
        int s = 0;
        while (s < VtableLayout.gvCount)
        {
            long b = Loader.slotBuf(s);
            // A HOLE IN hashCode/equals/toString IS A WILD BRANCH, not a trap. Those three are what BAKED code
            // dispatches on -- String.valueOf(Object) calls toString, Objects.equals calls equals, HashMap
            // calls hashCode -- and baked code carries no dispatchTargetGuard (implicitChecks is false for the
            // writer, by design). So a 0 there is `blr 0`, which the firmware's low-memory shim turns into
            // re-entry of the image entry point: `BOOT RE-ENTERED`, with no exception and no name.
            //
            // The slot goes empty when RTA never saw the class instantiated -- a NATIVE instantiation, or a
            // path the batch's reachability pass cannot follow -- so its overrides were pruned and never got a
            // deferral stub. StackTraceElement was special-cased for exactly this (nativelyInstantiated); this
            // is the general form, and it is why that comment ends "this is the list to add to".
            //
            // Scope is deliberate. Minting EVERY vtable slot costs 3-4x the code arena (measured), and the
            // universal holes here are the wait/wait(J)/notify/notifyAll slots, which hole for all 891
            // classes in a launcher closure, are final on Object, and dispatch through the monitor intrinsics
            // rather than the vtable. Minting those would be ~3,500 stubs that can never be reached. The three
            // hole for 55 classes in that same closure, and are the three baked code actually calls.
            //
            // They are matched by NAME, not by slot number. The test was `s >= 1 && s <= 3` while the
            // hand-written Object declared hashCode, equals, toString first; the stock Object puts clone
            // between equals and toString, so toString moved to slot 4, its hole went unminted, and baked
            // String.valueOf branched to 0 (AtomicUpdaters, ByteViewProbe, DeflaterProbe).
            if (b == 0L && isBakedDispatchSlot(s))
            {
                b = Loader.mintPrunedStub(s);
            }
            Loader.gBuild.tib.plus(8).plus(s * 8).store(b);            // TIB[1+slot] = impl code
            s += 1;
        }
        long dir = InterfaceInvocation.buildItableDir();                    // M8 itables: per-interface tables (writer numbering)
        if (dir != 0L && InterfaceInvocation.instImaps != null && InterfaceInvocation.instImapN < InterfaceInvocation.instImaps.length())
        {
            InterfaceInvocation.instImaps.set(InterfaceInvocation.instImapN, DataReference.fromLong(dir));                // capture the DIR for the post-phase-B default refill
            InterfaceInvocation.instImapReg[InterfaceInvocation.instImapN] = ClassRegistry.classRegByName(Loader.gView.thisNameOff);
            InterfaceInvocation.instImapN += 1;
        }
        Loader.gBuild.type.plus(16).store(dir);                 // TYPE_ITABLE_DIR_OFFSET
        DynamicTypeCheck.buildImplBitmap(Loader.gBuild.type.toAddress());                         // O(1) interface checks: super bits | dir bits
    }

    static DataReference objectTypeAddr()
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff, Magic.bytes("java/lang/Object")))
            {
                return ClassRegistry.clTab[i].type;
            }
            i += 1;
        }
        return DataReference.zero();
    }

    /** Number of vtable slots a SYNTHESISED TIB reserves (= java/lang/Object's flattened vtable count), or 0.
     *  Used by array, lambda and annotation TIBs alike -- none of them has a classfile, and all of them are
     *  nonetheless ordinary objects whose equals/hashCode/toString must select Object's implementations. */
    static int objectVtableCount()
    {
        int oi = Loader.objectClassIndex();
        return oi >= 0 ? ClassRegistry.clTab[oi].vtCount : 0;
    }

    /** Copy java/lang/Object's vtable slots into the array TIB {@code tib} (after its Type slot). Object's slots
     *  are only FILLED when Object's body is compiled (fillTib), which can happen AFTER an array TIB is first
     *  created (e.g. a string literal interns a byte[] before Object compiles) -- so a freshly-made array TIB may
     *  copy zeros. {@link ArrayTypes#refillArrayTibVtables} re-runs this over every cached array TIB once Object is done. */
    /** {@link #fillObjectVtable} bounded by the TIB's own slot capacity (see {@code lambdaTibVtCap}). */
    /**
     * {@link #fillObjectVtable} bounded by the TIB's own slot capacity. Answers whether the TIB is now FULL
     * -- filled to every slot it has room for -- which is the only condition under which the caller may
     * latch it and stop revisiting.
     */
    private static boolean fillObjectVtableUpTo(Address tib, int cap)
    {
        int oi = Loader.objectClassIndex();
        if (oi < 0)
        {
            return false;                                // Object not registered yet: nothing filled, no latch
        }
        syTibs += 1;
        int nv = ClassRegistry.clTab[oi].vtCount;
        int n = nv < cap ? nv : cap;
        int k = 0;
        while (k < n)
        {
            long want = ClassRegistry.clTab[oi].tib.plus(8L).plus((long) k * 8L).loadLong();
            sySlots += 1;
            if (tib.plus(8L).plus((long) k * 8L).loadLong() != want)
            {
                syChanged += 1;                          // the write that was actually NECESSARY. This read
            }                                            //   is the evidence the latch below rests on: it
            tib.plus(8L).plus((long) k * 8L).store(want);//   must stay ~= the first-fill count, never grow
            k += 1;
        }
        return n >= cap;                                 // COMPLETE, not merely attempted -- see lambdaTibFilled
    }

    /** Re-copy Object's (now-filled) vtable into every synthesised lambda/annotation TIB, bounded by the
     *  capacity each was built with. Same repair as {@link ArrayTypes#refillArrayTibVtables} and for the same reason:
     *  a TIB built before Object's body was compiled copied zeros, and a 0 slot is a wild branch from baked
     *  code (which carries no dispatch guard) rather than a named trap. */
    static void refillSynthTibVtables()
    {
        if (lambdaTibRoots == null)
        {
            return;
        }
        int i = 0;
        while (i < lambdaTibRootN)
        {
            if (!lambdaTibRoots.get(i).toAddress().isZero() && !lambdaTibFilled[i])
            {
                if (fillObjectVtableUpTo(lambdaTibRoots.get(i).toAddress(), lambdaTibVtCap[i]))
                {
                    lambdaTibFilled[i] = true;           // complete: nothing more can ever be written here
                }
                if (AnnotationInstances.isAnnoTib(lambdaTibRoots.get(i).toAddress()))   // the refill copies Object's slots back over the
                {                                       //   annotation contract: put it back
                    AnnotationInstances.patchAnnoContract(lambdaTibRoots.get(i).toAddress(), lambdaTibVtCap[i], Address.zero());
                }
                int px = ProxySupport.proxyIndexOfTib(lambdaTibRoots.get(i).toAddress());
                if (px >= 0)                            // ... and over a proxy's equals/hashCode/toString
                {
                    ProxySupport.patchProxyObject(px);
                }
            }
            i += 1;
        }
    }

    static void fillObjectVtable(Address tib)
    {
        int oi = Loader.objectClassIndex();
        if (oi < 0)
        {
            return;
        }
        int nv = ClassRegistry.clTab[oi].vtCount;
        int k = 0;
        while (k < nv)
        {
            tib.plus(8L).plus((long) k * 8L).store(ClassRegistry.clTab[oi].tib.plus(8L).plus((long) k * 8L).loadLong());
            k += 1;
        }
    }
}
