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

import static vm.ClassBytes.insnLen;
import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * The demand-load CLOSURE: which classes and methods a program can reach, computed on the metal before a batch
 * loads (rapid type analysis). {@code markReachable} runs the fixpoint -- seeds ({@code seed*}), the reach table,
 * the instantiated set, virtual resolution against instantiated receivers ({@code resolveVirtuals}), default
 * methods, the pending-reference queue and its indexes -- and the blobs it collects become the batch. Split out of
 * {@link Loader}; the per-blob method tables it reads are {@link MethodTableCache}.
 */
final class Closure
{
    private Closure()
    {
    }

    // ----- reachable-method compilation (M-B) --------------------------------
    // When a caller marks an entry point, loadAll compiles only the methods reachable from it (a call-graph
    // closure over the loaded blobs) instead of every method of every class. This lets a big real java.base
    // class load through the normal closure path without choking on its unreachable methods (toString,
    // parseInt's String.format paths, ...). Without an entry set, everything compiles (unchanged behaviour).
    /**
     * RTA's reachable-method cap. **It was 8192, and batch 1 of every launcher boot reported `reach=8192` --
     * the cap hit EXACTLY.** Raised, the same closure completes at **14,554**, so ~6,362 reachable methods
     * (44%) were being discarded on every boot, including every one that passed and exited 0.
     *
     * <p>{@link #addReach} dropped them SILENTLY -- no report, no counter -- which is the defect, not the
     * size. `MAXPEND` learned this exact lesson and the report it grew sits at the end of
     * {@link #markReachable}; this one never got it.
     *
     * <p>Two things make the silence worse than a normal cap. Marking is a FIXPOINT: an unmarked method
     * never contributes its refs, so a drop loses that method's whole transitive subtree -- which is why
     * raising this took `rounds` 26 -> 54 and `pend` 56,383 -> 96,158 rather than adding a few leaves. And
     * membership becomes LAYOUT-SENSITIVE: which 8,192 survive is insertion order, so any change to the
     * image moves the cut. Two statics were enough to move it, and the boot then failed on a path that had
     * simply never been reachable before.
     */
    static final int MAXREACH = 65536;
    private static byte[] gEntryName, gEntryDesc;        // entry method name/descriptor
    static long[] reachCode;                     // bytecode addresses of the reachable methods
    static final int REACHTAB = 262144;          // power of two > 2*MAXREACH: the set never fills
    static long[] reachTab;                      // ... and the same addresses as an open-addressed set,
                                                         //   because collectBlob asks isReach once per method of
                                                         //   every blob, every round -- a linear scan makes that
                                                         //   blobs x methods x reachN
    static int reachN;
    /**
     * The {@code reachTab} slot of each reached method and the {@code collectedTab} slot of each collected
     * one, so {@link #markReachable} empties only those instead of both whole tables per batch.
     * {@code markFullClear} is set whenever either table is reallocated -- allocation promises no zeroes --
     * and makes the next reset clear everything.
     */
    private static int[] reachSlotOf;
    private static int[] colSlotOf;
    static int colN;
    static boolean markFullClear = true;
    static int reachRefused;                             // refusals of an UNMARKED method past MAXREACH.
                                                         //   Not a distinct-method count (see addReach), so
                                                         //   it bounds the shortfall rather than naming it
    /** The classfile blobs of stoppable programs' classes, and their lengths ({@link #compilingStoppable}). */
    private static AddressArray stoppableBase = AddressArray.create(64);
    private static int[] stoppableLen = new int[64];
    private static int stoppableCount;
    /**
     * Mark every method reachable from the entry point: seed the entry (plus every {@code run()V}, which the
     * thread trampoline enters outside the call graph), then repeatedly follow each reachable method's calls
     * — invokestatic/special to the named class's method, invokevirtual/interface to that name+descriptor in
     * every loaded class (a receiver could be any of them) — until the set stops growing.
     */
    // RAISED 49152 -> 262144 (2026-09-12). The launcher closure overflowed the old cap in EVERY boot, at the
    // FIRST batch -- so RTA was silently incomplete and classes that are plainly referenced were never pulled.
    // That is not a capacity nuisance: a dropped ref means the class is never registered, so classRegByName
    // answers -1, superReg is pinned at -1, and its <clinit> is never enqueued -- symptoms that surface
    // arbitrarily far away and look like a dozen unrelated bugs. Running EVERY <clinit> widens the closure
    // further, so the old cap was never going to hold. ~36 bytes per entry across the parallel arrays, so
    // this is ~9.4 MB of a 96 MiB pre-zeroed span.
    static final int MAXPEND = 262144;
    private static final int PEND_PULL = 2;              // kind: pull the class only (field/type ref; no method)
    static AddressArray pendBase;                      // call-site refs of the round's reachable methods:
    static int[] pendClass, pendName, pendDesc, pendKind;   // (base, class/name/desc offsets, kind)
    static int pendN;
    private static int pendDropped;                      // refs RTA had to discard for want of room (0 = closure complete)
    // Rapid Type Analysis (RTA): a virtual/interface call's targets are only the methods that an INSTANTIATED
    // receiver could dispatch to -- not every loaded class carrying the name+desc (the old CHA over-approx,
    // which marked e.g. Float/Double.toString just because they were loaded, dragging in the FloatToDecimal ->
    // new String(...,Charset) -> charset closure). We track types created by `new` (0xbb) in reached methods;
    // a virtual pend then marks, for each instantiated class, the method it resolves up its own superclass chain.
    private static boolean[] pdInstantiated;             // pd blob is `new`'d in some reached method
    private static AddressArray instBase;                      // `new X` sites: (base, this-name Utf8 offset)
    private static int[] instOff;
    private static int instN;
    static long cumBytes;                                // bytes markReachable allocates, summed over batches
    /**
     * HOW MUCH OF `virt` IS RE-WALKING A LEVEL SOMEONE ELSE ALREADY MATCHED? resolveVirtuals walks each
     * INSTANTIATED class's superclass chain and matches every level's methods against the pend list, so a
     * level shared by many subclasses is matched once per DESCENDANT -- java/lang/Object's table by every
     * class that reaches it.
     *
     * <p>Whether that is worth fixing depends on how often a visit marks anything, and this file is explicit
     * about not guessing here: the ONE previous attempt at `virt` claimed work could be SKIPPED, measured
     * 2.76x, and silently UNDER-MARKED HALF THE CLOSURE (reach 1040 -> 449) with the cause never identified.
     * The attempt that worked cached facts immutable by construction and made no skipping claim. So: count
     * first, and let the ratio say whether a level memo is worth the soundness argument it would need.
     */
    static int vWalks;                           // outer classes whose chain was walked
    static long vLevels;                         // level visits across those walks
    static long vLevelsGrew;                     // ... that actually marked something new
    static int vCached;                          // level visits served by the method-table cache
    static int vParsed;                          // ... that had to parseForMethods instead
    static long vMethods;                        // methods examined across cached visits
    static long vCacheT;                         // time in the cached arm
    static long vParseT;                         // time in the parsing arm
    // Per-pass accumulators for LOAD_PROFILE, in raw CNTPCT ticks (converted only when printed).
    static int mrRounds;
    /**
     * The lowest UNSETTLED blob index of the current batch. Every per-round blob loop of the mark skips a settled
     * blob ({@link #markSettled}) and does nothing else for it, and settled is monotone -- {@code pdDoneB} goes
     * 0 -> 1 only in loadAll's phase B, after the mark, and new blobs are appended at {@code pdCount} -- so every
     * index below this one is skipped, in every round, and those loops can start here instead. MEASURED
     * (SslEngineProbe, QEMU 2026-11-07): 1,297 batches average ~1,888 blobs but ~21 unsettled per round, so eight
     * loops x 2,895 rounds visited 5.38M blobs to work on 27,776; the mark dropped 8.45 -> 7.16 s with the reached
     * and pended sets identical, and a checker found no blob below it unsettled at any round start.
     * Loops that touch settled blobs on purpose (computeInstantiated, probeAll, the name indexes, the per-batch
     * watermark clears) still start at 0.
     */
    static int mrLo;
    static long mrProbe, mrSeed, mrCollect, mrPull, mrStruct, mrInst, mrStatic, mrVirt, mrDflt;
    // The classpath-jar FETCH -- locating a class in the archive and INFLATING it -- measured where it
    // happens instead of being charged to whichever mark pass triggered it. It is a SUBSET of `pull` and
    // `struct`, not a sibling of them: both of those end in registerNameFromDir, so a single large class
    // arriving as somebody's superclass puts its whole inflate inside `struct`. That is how batch 188 of a
    // launcher boot reads `struct=565ms` against ~140us for its neighbours while adding two blobs, with
    // `pull=0us` -- and reading `struct` as structural work is exactly the mis-attribution this removes.
    static long mrFetch;
    /** Trace every functional interface RTA records for a pull -- the step between "the indy names it" and
     *  "the class is registered", which is where a lambda's interface can go missing without a word. */
    private static final boolean INDY_IFACE_WATCH = false;
    /** Append a pending ref (dedup-free; the pull/resolve phases idempotently re-check registration). */
    /** One-time report: the pend list filled, so RTA is silently incomplete from here on. */
    private static boolean pendFullReported;
    private static final int SUPER_NONE = -2;            // declares no superclass; permanent
    private static final int SUPER_UNLOADED = -1;        // names one, but that blob is not loaded YET
    static int[] pdSuperCache;                   // superPdOf memo, +3 biased so 0 means "not resolved"
    static int[] virtResolved;                   // per-pend: STAMP of the class that last dispatched it
    static int virtStamp;                        // current class's stamp (index+1, so 0 means "never")
    static int[] pvBucket;                       // bucket head -> pend index, -1 empty
    static int[] pvNext;                         // chain link, per pend
    private static int[] psBucket;                       // ... and the same for STATIC/special pends, keyed on the
    private static int[] psNext;                         //   callee CLASS name, for resolveBlob
    /**
     * Pends already in {@code psBucket}/{@code pvBucket}, and the bucket each one went into (-1 for a pend of
     * the other kind), so the start of the next batch can empty exactly those buckets.
     *
     * <p>BOTH INDEXES WERE CLEARED (65,536 buckets) AND REBUILT FROM PEND 0 ON EVERY CALL -- once per round
     * for the static one and twice per round for the virtual one -- although pends only APPEND within a batch
     * and an entry is never rewritten: {@code pendN} returns to 0 in exactly one place, the top of
     * {@link #markReachable}, which calls {@link #clearPendIndexes} first. MEASURED on SslEngineProbe
     * (2026-11-04): 2.7 s across 5,668 virtual and 2,834 static builds, while the batch's last round held
     * 15 pends. Indexing only the new pends in order head-inserts exactly as the rebuild did, so every chain
     * is identical.
     */
    private static int psIndexed;
    private static int pvIndexed;
    private static int[] psSlot;
    private static int[] pvSlot;
    // ---- incremental-mark watermarks -------------------------------------------------------------------
    // The round loop stays (it is what drives the fixpoint), but every pass now records how far through the
    // pend list it has already got, per blob / per class. A round then costs only the work that is NEW since
    // the last one, so each (blob, pend) and (class, pend) pair is processed exactly ONCE across the whole
    // mark instead of once per round. With 33 rounds over 24,826 pends that is the difference between
    // O(rounds x pends x blobs) and O(pends x blobs).
    //
    // Pends ACCUMULATE for this to work (the list used to be rebuilt from scratch each round). The total is
    // unchanged -- the old last round already collected every reachable method's refs -- because collectBlob
    // now collects each method once, tracked in collectedTab.
    static long[] collectedTab;                  // method code addresses whose refs are already pended
    static int[] pdPendTo;                       // per blob: pends resolved by resolveBlob
    static int[] pdVirtTo;                       // per class: pends dispatched by resolveVirtuals
    // ... and the blob count when that happened. A pend watermark alone is UNSOUND for these two passes,
    // because both walk the class's SUPERCLASS CHAIN and that chain GROWS as ancestors are pulled in later
    // rounds: a pend already considered for C can resolve to an inherited method that was not visible the
    // first time. Whenever the blob set has changed, the class reconsiders every pend. (Conservative -- any
    // new blob might be an ancestor -- but the rounds after class-pulling stops are the incremental ones,
    // and they are the majority.) markDefaults needs no epoch: an interface matches against its OWN methods.
    static int[] pdPendEpoch;
    static int[] pdVirtEpoch;
    static int[] pdDfltTo;                       // per blob: pends matched by markDefaults
    static int[] pdSeeded;                       // per blob: seedNativelyReached done
    static int[] pdSeedC;                        // per blob: seedClinits done
    static int pendPullTo;                       // pends already looked up in the class dir
    static int virtFrom;                         // matchLevel/matchDefaults: ignore pends below this

    /** Declare the method loadAll should treat as the reachability root (call before addBlob/loadAll). */
    static void entryPoint(long blobBytes, byte[] name, byte[] desc)
    {
        Loader.gEntryBlob = blobBytes;
        gEntryName = name;
        gEntryDesc = desc;
    }

    /**
     * Mark a whole blob as a reachability ROOT: every method it declares is seeded, and the class counts as
     * instantiated. This is what {@code ClassLoader.defineClass} means -- the bytes come from the program, so
     * nothing already loaded calls into the class and RTA has no way to see who will. Marked from the entry
     * method only, the closure reaches NONE of it, {@link Loader#compileClass} prunes every method, and
     * {@link TibBuilder#fillTib} then fills a vtable of zeros: the class loads, reflection into it works (that resolves
     * through the method registry), and the first virtual call inside it hits the null-vtable guard as a
     * bare {@code ArrayIndexOutOfBoundsException}.
     */
    static void rootBlob(long blobBytes)
    {
        Loader.gRootBlob = blobBytes;
    }

    static void addStoppableBlob(Address blob, int len)
    {
        if (stoppableCount == stoppableBase.length())
        {
            AddressArray nb = AddressArray.create(stoppableCount * 2);
            int[] nl = new int[stoppableCount * 2];
            int i = 0;
            while (i < stoppableCount)
            {
                nb.set(i, stoppableBase.get(i));
                nl[i] = stoppableLen[i];
                i += 1;
            }
            stoppableBase = nb;
            stoppableLen = nl;
        }
        stoppableBase.set(stoppableCount, blob);
        stoppableLen[stoppableCount] = len;
        stoppableCount += 1;
    }

    /**
     * Whether the method being compiled belongs to a stoppable program: its class's methods table
     * ({@code gMethodsStart}) lies inside a blob {@link Loader#defineFromBytes} recorded for one. Asked by MetalSymbols
     * for every compile, so it is a short scan over just those blobs.
     */
    static boolean compilingStoppable()
    {
        long at = Loader.gMethodsStart.toLong();
        int i = 0;
        while (i < stoppableCount)
        {
            if (at >= stoppableBase.get(i).toLong() && at < stoppableBase.get(i).plus(stoppableLen[i]).toLong())
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /**
     * Give a blob's own virtual methods DEFERRAL STUBS even where reachability analysis prunes them, so its
     * vtable has no holes — without marking them reachable, which is the part that matters.
     *
     * <p>This is the weaker sibling of {@link #rootBlob}, and the difference is the whole point.
     * {@code Class.forName} loads by NAME into a live program, so RTA marks nothing in the class (it seeds
     * from a {@code <clinit>} most classes do not have) and {@link Loader#compileClass} skips every method — leaving
     * the class's own vtable slots 0 while REFLECTION still worked, because that resolves through the method
     * registry and compiles on demand. A virtual call inside such a class then hit the null-vtable guard.
     *
     * <p>Seeding them reachable instead, as {@code rootBlob} does, is what an earlier attempt did and it
     * "pulled a huge closure into the 2nd (incremental) batch and corrupted the heap" — an arbitrary
     * java.base class named at runtime drags in everything it mentions. A deferral stub pulls NOTHING: it is
     * a few instructions that route the first call into {@link LazyCompiler#lazyCompile}, which compiles the body then
     * and resolves its own relocs, exactly as reflection's on-demand path already does.
     */
    static void stubBlob(long blobBytes)
    {
        Loader.gStubBlob = blobBytes;
    }

    /** Record that {@code code}'s refs have been pended; false if they already were. */
    private static boolean addCollected(long code)
    {
        int i = (int) ((code >> 3) * 0x9E3779B1L) & (REACHTAB - 1);
        while (collectedTab[i] != 0L && collectedTab[i] != code)
        {
            i = (i + 1) & (REACHTAB - 1);
        }
        if (collectedTab[i] == code)
        {
            return false;
        }
        collectedTab[i] = code;
        colSlotOf[colN] = i;                            // for the sparse clear in markReachable
        colN += 1;
        return true;
    }

    /**
     * Slot for {@code code} in {@link #reachTab}: its index if present, else the first free slot. Open
     * addressing with linear probing over a power-of-two table twice {@code MAXREACH}, so it can never fill
     * and the probe always terminates. Addresses are 8-aligned, so the low three bits carry nothing --
     * multiply before masking or every key lands in one eighth of the table.
     */
    private static int reachSlot(long code)
    {
        int i = (int) ((code >> 3) * 0x9E3779B1L) & (REACHTAB - 1);
        while (reachTab[i] != 0L && reachTab[i] != code)
        {
            i = (i + 1) & (REACHTAB - 1);
        }
        return i;
    }

    /** True if {@code code} (a method's bytecode address) was marked reachable. */
    static boolean isReach(long code)
    {
        return reachTab[reachSlot(code)] == code;
    }

    /** Add {@code code} to the reachable set if new; returns true if it was newly added. */
    static boolean addReach(long code)
    {
        if (code == 0L)
        {
            return false;
        }
        if (reachN >= MAXREACH)
        {
            // PROBE BEFORE COUNTING. Marking is a fixpoint, so addReach is called for the same method in
            // every round; a method ALREADY in the set is an ordinary no-op, not a loss. Counting those too
            // made the first cut of this report say "2,384,018 methods dropped" on a closure short by ~6,000
            // and advise a cap of 2.4 million -- a diagnostic asserting something it had not measured, which
            // is the failure mode this file records three times. This counts REFUSALS OF UNMARKED METHODS,
            // still over-counting a genuinely-new method once per round, and the wording says so.
            if (reachTab[reachSlot(code)] != code)
            {
                reachRefused += 1;
            }
            return false;
        }
        int i = reachSlot(code);
        if (reachTab[i] == code)
        {
            return false;
        }
        reachTab[i] = code;
        reachCode[reachN] = code;
        reachSlotOf[reachN] = i;                        // for the sparse clear in markReachable
        reachN += 1;
        return true;
    }

    /**
     * True if blob {@code b} was compiled by an EARLIER batch, so the mark can skip it. Phase B is guarded by
     * the same flag ({@code pdDoneB[i] == 0}), which is the whole argument: a settled blob is never recompiled
     * and its TIB never rebuilt, so marking one of its methods now cannot compile anything. All the mark can
     * still do for it is re-derive references its own batch already followed. On an incremental load that is
     * ~97% of every pass (75 settled blobs to pull one new class), and it is repeated once per round.
     *
     * <p>What a settled blob's UNMARKED method costs instead is one late resolution at the site that reaches
     * it -- a deferral stub in the vtable, or a link stub at the call -- which is the machinery the reflection
     * arc built and validated. Within the first batch nothing is settled, so nothing here changes.
     */
    private static boolean markSettled(int b)
    {
        return Loader.pdDoneB[b] != 0;
    }

    static void markReachable()
    {
        long ts0 = Magic.readCNTPCT_EL0();
        MethodTableCache.resetMethodTables();
        Loader.mrReset += Magic.readCNTPCT_EL0() - ts0;
        long ts1 = Magic.readCNTPCT_EL0();
        // ALLOCATED ONCE, NOT PER BATCH. These are fixed-size scratch, and rebuilding them every batch cost
        // `MAXPEND * 36` bytes a batch by this method's own accounting below -- ~9.4MB, which Heap.alloc then
        // ZEROES, on every one of a launcher boot's 165 batches. That was ~50% of `mark` and invisible: the
        // nine sub-timers all live inside the round loop, and this runs before it. Raising MAXPEND
        // 49,152 -> 262,144 for the RTA closure fix multiplied it 5.3x.
        //
        // WHICH TABLES NEED CLEARING IS NOT UNIFORM, and getting it wrong is silent: `pend*` and `inst*` are
        // bounded by `pendN`/`instN`, which reset to 0 here, so nothing ever reads a stale slot and they need
        // no clearing at all -- that is the whole 9.4MB. The rest DO need it: `reachTab`/`collectedTab` are
        // open-addressed and probe until they hit a 0, and the `pd*` watermarks must start at 0 so a blob
        // added later still considers every pend once (the comment those lines carried). ~348KB cleared
        // against ~9.4MB allocated and zeroed.
        if (pendBase == null)                            // resetLoader nulls these per LAUNCH to free them
        {
            markFullClear = true;
            cumBytes += (long) MAXPEND * 36L + 16384L * 16L + (long) Loader.MAXBLOB * 21L;   // see loadCostReport
            reachTab = new long[REACHTAB];
            collectedTab = new long[REACHTAB];
            pdPendTo = new int[Loader.MAXBLOB];
            pdVirtTo = new int[Loader.MAXBLOB];
            pdDfltTo = new int[Loader.MAXBLOB];
            pdSeeded = new int[Loader.MAXBLOB];
            pdSeedC = new int[Loader.MAXBLOB];
            pendBase = AddressArray.create(MAXPEND);
            pendClass = new int[MAXPEND];
            pendName = new int[MAXPEND];
            pendDesc = new int[MAXPEND];
            pendKind = new int[MAXPEND];
            pdInstantiated = new boolean[Loader.MAXBLOB];
            instBase = AddressArray.create(MAXPEND);
            instOff = new int[MAXPEND];
        }
        if (reachSlotOf == null)
        {
            reachSlotOf = new int[MAXREACH];
            colSlotOf = new int[MAXREACH];
            markFullClear = true;
        }
        if (markFullClear)
        {
            int z = 0;
            while (z < REACHTAB)                         // open-addressed: a probe walks until it finds a 0
            {
                reachTab[z] = 0L;
                collectedTab[z] = 0L;
                z += 1;
            }
            z = 0;
            while (z < Loader.MAXBLOB)                          // watermarks: 0 = "has considered no pend yet"
            {
                pdPendTo[z] = 0;
                pdVirtTo[z] = 0;
                pdDfltTo[z] = 0;
                pdSeeded[z] = 0;
                pdSeedC[z] = 0;
                pdInstantiated[z] = false;
                z += 1;
            }
            markFullClear = false;
        }
        else
        {
            // ONLY THE SLOTS THIS LAUNCH'S LAST BATCH FILLED. Both tables are 262,144 longs and were zeroed in
            // full at the start of EVERY batch, while a batch late in a run marks a handful of methods.
            // MEASURED on SslEngineProbe (2026-11-04): 6.9 s for this reset over 1,275 batches. A slot is
            // filled only by addReach / addCollected, which record it; reachN and colN bound the lists, and
            // collected methods are reached ones, so neither list can outgrow MAXREACH. A table reallocated
            // by resetLoader (no promise of zeroes) is cleared in full first: see markFullClear.
            int z = 0;
            while (z < reachN)
            {
                reachTab[reachSlotOf[z]] = 0L;
                z += 1;
            }
            z = 0;
            while (z < colN)
            {
                collectedTab[colSlotOf[z]] = 0L;
                z += 1;
            }
            z = 0;
            while (z < Loader.pdCount)                          // watermarks: only blobs exist below Loader.pdCount, and it
            {                                            //   only grows within a launch (a reset clears in full)
                pdPendTo[z] = 0;
                pdVirtTo[z] = 0;
                pdDfltTo[z] = 0;
                pdSeeded[z] = 0;
                pdSeedC[z] = 0;
                pdInstantiated[z] = false;
                z += 1;
            }
        }
        reachN = 0;                                     // AFTER the clear above, which walks the old list
        colN = 0;
        clearPendIndexes();                             // BEFORE pendN returns to 0: it empties by pend
        pendPullTo = 0;
        pendN = 0;
        instN = 0;
        Loader.mrAlloc += Magic.readCNTPCT_EL0() - ts1;
        mrLo = 0;
        while (mrLo < Loader.pdCount && Loader.pdDoneB[mrLo] != 0)
        {
            mrLo += 1;
        }
        long ts2 = Magic.readCNTPCT_EL0();
        Address entryBlob = Address.fromLong(Loader.gEntryBlob);
        Loader.parseForMethods(entryBlob, Loader.blobLenOf(entryBlob));
        addReach(Loader.findMethodByBytes(Loader.gbase, gEntryName, gEntryDesc));
        Loader.mrEntry += Magic.readCNTPCT_EL0() - ts2;
        long ts3 = Magic.readCNTPCT_EL0();
        seedRootBlob();                                 // a defineClass'd class: all of it is a root
        seedAllNamed(Magic.bytes("run"), Magic.bytes("()V"));   // trampoline entry (Runnable.run)
        Loader.mrRoot += Magic.readCNTPCT_EL0() - ts3;
        // Each round is two bounded passes over the blobs (so the const pool is parsed O(blobs) times, not
        // per-ref): collect the call-site refs of every reachable method, then mark each ref's target(s).
        // Reachability-gated closure: each round (a) collects the class/method refs of every reachable
        // method, (b) PULLS any referenced class not yet loaded from the embedded dir + its super/interfaces,
        // (c) marks the invoke targets now resolvable. So a program pulls only its reachable closure -- the
        // basis for embedding all of java.base without dragging every class's full constant-pool closure in.
        boolean grew = true;
        mrRounds = 0;
        mrProbe = 0L;
        mrSeed = 0L;
        mrCollect = 0L;
        mrPull = 0L;
        mrStruct = 0L;
        mrFetch = 0L;
        mrInst = 0L;
        mrStatic = 0L;
        mrVirt = 0L;
        vWalks = 0;
        vLevels = 0L;
        vLevelsGrew = 0L;
        vCached = 0;
        vParsed = 0;
        vMethods = 0L;
        vCacheT = 0L;
        vParseT = 0L;
        mrDflt = 0L;
        while (grew)
        {
            grew = false;
            mrRounds += 1;
            long tp = Magic.readCNTPCT_EL0();
            Loader.probeAll();                                 // set pdNameOff for all (incl. just-pulled) + dep list
            buildNameIndex();                           // ... which is what makes the names hashable
            mrProbe += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = seedNativelyReached() || grew;       // the five signatures RTA cannot infer (see the method)
            grew = seedClinits() || grew;               // runnable <clinit>s: pull the classes an initializer calls
            mrSeed += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            int b = mrLo;
            while (b < Loader.pdCount)                         // collect refs of NEWLY reachable methods
            {
                if (!markSettled(b))
                {
                    collectBlob(Loader.pdBase.get(b), Loader.pdLen[b]);
                }
                b += 1;
            }
            mrCollect += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            int r = pendPullTo;                         // pull referenced classes not yet loaded. Only the new
            while (r < pendN)                           //   pends: an older one either pulled its class already
            {                                           //   or named one the dir does not have, and neither
                if (!nameRegistered(pendBase.get(r), pendClass[r])   // answer changes by being asked again.
                        && !closureDeferred(pendBase.get(r), pendClass[r])
                        && registerNameFromDir(pendBase.get(r), pendClass[r]) != 0L)
                {
                    grew = true;
                }
                r += 1;
            }
            pendPullTo = pendN;
            mrPull += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            b = mrLo;
            while (b < Loader.pdCount)                         // pull each loaded class's super + interfaces
            {
                if (!markSettled(b))
                {
                    grew = pullStructural(Loader.pdBase.get(b), Loader.pdLen[b]) || grew;
                }
                b += 1;
            }
            mrStruct += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = computeInstantiated() || grew;       // RTA: flag the pd blobs `new`'d by a reached method
            mrInst += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            b = mrLo;
            buildStaticPendIndex();
            while (b < Loader.pdCount)                         // resolve static/special targets (class-qualified, precise)
            {
                if (!markSettled(b) && (pdPendTo[b] < pendN || pdPendEpoch[b] != Loader.pdCount))
                {
                    virtFrom = pdPendEpoch[b] == Loader.pdCount ? pdPendTo[b] : 0;   // chain may have grown -> redo all
                    grew = resolveBlob(b, Loader.pdBase.get(b), Loader.pdLen[b]) || grew;
                    pdPendTo[b] = pendN;
                    pdPendEpoch[b] = Loader.pdCount;
                }
                b += 1;
            }
            mrStatic += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = resolveVirtuals() || grew;           // RTA: virtual/interface targets, in instantiated types only
            mrVirt += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = markDefaults() || grew;              // RTA: interface DEFAULT methods (resolveVirtuals only walks
            mrDflt += Magic.readCNTPCT_EL0() - tp;
        }                                               //   class hierarchies, so an unoverridden default is missed)
        Loader.markActive = 1;
        // UNGATED, because a truncated closure is a FAILURE and failures are never gated here. "Full" on its
        // own cannot say whether the cap is short by ten refs or ten thousand, so sizing it was a guess
        // repeated once per boot; the count makes one boot answer it.
        if (pendDropped != 0)
        {
            Uart.write(Magic.bytes("  RTA CLOSURE INCOMPLETE: "));
            VM.printDec(pendDropped);
            Uart.write(Magic.bytes(" refs dropped past MAXPEND="));
            VM.printDec(MAXPEND);
            Uart.write(Magic.bytes(" -- those classes are NEVER PULLED; raise MAXPEND above "));
            VM.printDec(MAXPEND + pendDropped);
            Uart.putc(0x0A);
        }
        // THE SAME REPORT FOR THE METHOD SET, which never had one. A dropped METHOD is worse than a dropped
        // ref: marking is a fixpoint, so the method never contributes its own refs and its whole transitive
        // subtree is lost with it. It is also silent at the failure site -- the method simply gets no
        // dispatch stub, and the first sign is a `VIRTUALRESOLVE FAILED` in a class that looks unrelated.
        // Ungated, for the reason stated above: a truncated closure is a failure.
        if (reachRefused != 0)
        {
            Uart.write(Magic.bytes("  RTA CLOSURE INCOMPLETE: MAXREACH="));
            VM.printDec(MAXREACH);
            Uart.write(Magic.bytes(" FULL, "));
            VM.printDec(reachRefused);
            Uart.write(Magic.bytes(" refusals -- those methods get NO dispatch stub, and marking is a\n"));
            Uart.write(Magic.bytes("  FIXPOINT so their callees are lost with them. Closure membership is now\n"));
            Uart.write(Magic.bytes("  ORDER-DEPENDENT: any change to the image moves which methods survive.\n"));
            // DELIBERATELY NO SUGGESTED SIZE. The refusal count is not a distinct-method count, and the true
            // closure size is UNKNOWABLE from a run that truncated it -- the only way to learn it is to raise
            // the cap and re-read `reach`. Printing a target here would be inventing one.
            Uart.write(Magic.bytes("  Raise MAXREACH and re-read `reach=` to learn the true size.\n"));
        }
    }

    /**
     * RTA for interface DEFAULT methods: {@link #resolveVirtuals} only marks targets found by walking an
     * instantiated class's SUPERCLASS chain, so a called interface method that no class overrides (it uses the
     * interface's own default body, e.g. {@code EnumMap} inheriting {@code Map.putIfAbsent}) is never compiled --
     * its imap slot then stays 0 and the invokeinterface wild-branches. Mark any concrete (default) method in a
     * loaded interface whose name+descriptor matches a pending interface-call (kind 1). Over-marking is harmless
     * (only compiles a bit more); pends only hold actually-called signatures, so this is targeted. Marking a
     * default reachable triggers another round that pulls its own callees (putIfAbsent -> get/put), so the
     * default's transitive closure compiles too.
     */
    private static boolean markDefaults()
    {
        boolean grew = false;
        buildPendIndex();
        int b = mrLo;
        while (b < Loader.pdCount)
        {
            if (markSettled(b) || pdDfltTo[b] >= pendN)
            {
                b += 1;
                continue;
            }
            Loader.parseConstPool(Loader.pdBase.get(b), Loader.pdLen[b]);
            boolean iface = (u2(Loader.gp) & 0x0200) != 0;      // ACC_INTERFACE (access_flags right after the constant pool)
            if (iface)
            {
                virtFrom = pdDfltTo[b];
                Loader.parseForMethods(Loader.pdBase.get(b), Loader.pdLen[b]);
                grew = MethodTableCache.matchDefaults() || grew;          // same inversion as resolveVirtuals: per method, not per pend
            }
            pdDfltTo[b] = pendN;
            b += 1;
        }
        return grew;
    }

    /** Pull {@code base}'s superclass + interfaces from the dir (needed for its TIB/itable), if not loaded. */
    private static boolean pullStructural(Address base, int len)
    {
        Loader.parseConstPool(base, len);
        Address p = Loader.gp;
        boolean grew = false;
        int superIdx = u2(p.plus(4));
        if (superIdx != 0)
        {
            int nameOff = Loader.gcp[u2(Loader.gbase.plus(Loader.gcp[superIdx]))];
            if (!nameRegistered(Loader.gbase, nameOff) && registerNameFromDir(Loader.gbase, nameOff) != 0L)
            {
                grew = true;
            }
        }
        int ifCount = u2(p.plus(6));
        int k = 0;
        while (k < ifCount)
        {
            int nameOff = Loader.gcp[u2(Loader.gbase.plus(Loader.gcp[u2(p.plus(8 + k * 2))]))];
            if (!nameRegistered(Loader.gbase, nameOff) && registerNameFromDir(Loader.gbase, nameOff) != 0L)
            {
                grew = true;
            }
            k += 1;
        }
        return grew;
    }

    /** Collect the call-site refs of every already-reachable method in blob {@code base} into the pending set. */
    private static void collectBlob(Address base, int len)
    {
        Loader.parseForMethods(base, len);
        Indy.findBootstrapMethods();                         // set gBsmOff so collectRefs can resolve lambda indys
        Address p = Loader.gMethodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            long code = Loader.findCode(base, p.plus(8), attrs);
            if (code != 0L && isReach(code) && addCollected(code))
            {
                collectRefs(base, code, Loader.gcodeLen);      // ONCE: a method's refs never change, and the pend
            }                                           //   list now accumulates instead of being rebuilt
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
    }

    /** Append each invoke in {@code [code, code+len)} to the pending-ref set (offsets index the cp of {@code base}). */
    /**
     * Record every class a reachable method references, so reachability-gated closure pulls only what a
     * reached method actually touches (not the whole class's constant pool): invoke targets (mark the method
     * + pull its class), field owners, and {@code new}/{@code checkcast}/{@code instanceof}/{@code anewarray}
     * types (pull-only). {@code gcp}/{@code gbase} are parsed for {@code base} by the caller.
     */
    private static void collectRefs(Address base, long code, int len)
    {
        int pc = 0;
        while (pc < len && pendN < MAXPEND)
        {
            int op = u1(code + pc);
            // Dead-branch pruning for metal-invariant flags: a getstatic of such a flag immediately guarding a
            // branch is never-taken code on metal, yet reachability would still walk it and pull its whole
            // closure. `getstatic jfrTracing; ifeq L` (JFR off -> the ThrowableTracer/JFR arm is dead, which is
            // what otherwise drags jdk/internal/event + AtomicLong + reflect out of stock Throwable/Error), and
            // `getstatic $assertionsDisabled; ifne L` (assertions off -> the assert arm is dead): skip to L so
            // the guarded arm's refs aren't collected. The compiler still emits the dead call as an unresolved
            // bl, which never executes.
            if (op == 0xb2)                                          // getstatic
            {
                int fidx = u2(code + pc + 1);
                int nextOp = u1(code + pc + 3);
                if ((nextOp == 0x99 && Utf8.utf8IsAtBase(base, Loader.mrefNameOff(fidx), Magic.bytes("jfrTracing")))
                        || (nextOp == 0x9a && Utf8.utf8IsAtBase(base, Loader.mrefNameOff(fidx), Magic.bytes("$assertionsDisabled"))))
                {
                    int off = u2(code + pc + 4);
                    if (off >= 0x8000) { off -= 0x10000; }
                    int target = pc + 3 + off;                       // the taken (dead-arm-skipping) branch target
                    if (target > pc + 6 && target <= len)
                    {
                        pc = target;
                        continue;
                    }
                }
            }
            if (op == 0xb8 || op == 0xb7 || op == 0xb6 || op == 0xb9)   // invoke static/special/virtual/interface
            {
                int idx = u2(code + pc + 1);
                addPend(base, Loader.refClassNameOff(idx), Loader.mrefNameOff(idx), Loader.mrefDescOff(idx),
                        (op == 0xb8 || op == 0xb7) ? 0 : 1);            // class-qualified vs name+desc
            }
            else if (op == 0xb2 || op == 0xb3 || op == 0xb4 || op == 0xb5)   // get/put static|field: pull owner
            {
                addPend(base, Loader.refClassNameOff(u2(code + pc + 1)), 0, 0, PEND_PULL);
            }
            else if (op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1)   // new/anewarray/checkcast/instanceof
            {
                int cnOff = classCpNameOff(u2(code + pc + 1));
                addPend(base, cnOff, 0, 0, PEND_PULL);
                if (op == 0xbb)                          // RTA: `new C` makes C an instantiated receiver type
                {
                    addInst(base, cnOff);
                }
            }
            else if (op == 0x12 && Loader.gcpTag[u1(code + pc + 1)] == 7)           // ldc of a CONSTANT_Class (X.class
            {                                                               // literal): pull the class so its Type/
                addPend(base, classCpNameOff(u1(code + pc + 1)), 0, 0, PEND_PULL);   // mirror exists for reflection
            }
            else if (op == 0x13 && Loader.gcpTag[u2(code + pc + 1)] == 7)           // ldc_w of a CONSTANT_Class literal
            {
                addPend(base, classCpNameOff(u2(code + pc + 1)), 0, 0, PEND_PULL);
            }
            else if (op == 0xba && Indy.isLambdaIndy(u2(code + pc + 1)))          // lambda/method-ref: mark its impl body
            {
                int idx = u2(code + pc + 1);
                int mref = LambdaFactory.lambdaImplMref(idx);
                int mk = LambdaFactory.lambdaImplKind(idx);
                addPend(base, Loader.refClassNameOff(mref), Loader.mrefNameOff(mref), Loader.mrefDescOff(mref),
                        (mk == 5 || mk == 9) ? 1 : 0);   // invokeVirtual/Interface -> name+desc; else class-qualified
                pendIndyIface(idx);                      // ... and the FUNCTIONAL INTERFACE, which nothing else names
                if (mk == 8)                             // constructor ref (X::new) instantiates X, like `new X`:
                {                                        // mark it a receiver type so RTA pulls its overridden
                    addInst(base, Loader.refClassNameOff(mref));//   virtual methods (hashCode/equals/...) and fills its vtable
                }
            }
            pc += insnLen(code, pc);
        }
    }

    /**
     * Pull the functional interface of the {@code invokedynamic} at {@code idx}.
     *
     * <p>It is named ONLY as the return type inside the indy's own descriptor -- never as a
     * {@code CONSTANT_Class} -- so neither the tag-7 scan nor the ref collection above sees it, and RTA
     * happily builds a closure with the lambda's impl but not its interface. {@code buildLambdaTib} then
     * resolves it to Type 0, which is also the itable directory's END SENTINEL: the lambda satisfies no
     * interface at all and every dispatch on it throws NPE from the directory miss, inside whatever library
     * received it. That is what made the stock NextTokenWithNullDelimTest fail while a hand-written replay of
     * the identical calls passed -- the replay's batch happened to contain the interface already.
     */
    static void pendIndyIface(int idx)
    {
        Address p = Loader.gbase.plus(Loader.mrefDescOff(idx) + 2);
        while (u1(p) != ')')
        {
            p = p.plus(1);
        }
        p = p.plus(1);
        if (u1(p) != 'L')
        {
            return;                                      // a primitive return names no class
        }
        Address start = p.plus(1);
        Address q = start;
        while (u1(q) != ';')
        {
            q = q.plus(1);
        }
        int off = Loader.internIndyNameAt(start, q.diff(start).toInt());
        if (DynamicLinker.regBySigU(Loader.indyNameBuf.plus(off)) >= 0)
        {
            return;                                      // already registered: re-pending it every round
        }                                                //   would grow the pend list for nothing
        if (INDY_IFACE_WATCH)
        {
            Uart.write(Magic.bytes("  pendiface "));
            Loader.printNameAt(Loader.indyNameBuf, off);
            Uart.putc(0x0A);
        }
        addPend(Loader.indyNameBuf, off, 0, 0, PEND_PULL);
    }

    /** Name Utf8 offset of a {@code CONSTANT_Class} at cp index {@code idx} (for the current {@code gcp}/{@code gbase}). */
    static int classCpNameOff(int idx)
    {
        return Loader.gcp[u2(Loader.gbase.plus(Loader.gcp[idx]))];
    }

    /**
     * The security subsystem beyond its {@link #securityCore}: the JCA -- providers, engines, keys, certificates,
     * keystores, TLS -- under java/security, sun/security, javax/ and java.base's com/sun/crypto and com/sun/security.
     * Ordinary programs NAME it from cold arms (Class.getProtectionDomain, URLClassLoader's CodeSource,
     * SecureRandom in a hash seed) and almost never run it, so it is pulled when reached. MEASURED: un-denied but
     * eager, SbProbe's first batch went +797 blobs / pend=30347 / clinits=189 -> +1373 / 53032 / 350.
     */
    private static boolean securityDeferred(Address base, int off)
    {
        if (Loader.utf8HasPrefix(base, off, Magic.bytes("javax/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("com/sun/crypto/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("com/sun/security/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/jar/JarVerifier")))   // only a SIGNED jar
        {
            return true;
        }
        return (Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/security/")))
                && !securityCore(base, off);
    }

    /**
     * The part of java/security and sun/security that ordinary programs RUN, so it stays in the eager closure: the
     * permission and marker layer, ProtectionDomain/CodeSource/SecureClassLoader (every built-in loader is a
     * SecureClassLoader), the exception hierarchy, and the security-PROPERTIES store (Security plus the three
     * sun/security/util classes its initializer and stock InetAddress/Exceptions reach). These were the classes
     * narrowed out of the old deny prefixes, one measured need at a time; prefix matching admits each class's
     * nested and package-private companions (BasicPermission -> BasicPermissionCollection).
     */
    private static boolean securityCore(Address base, int off)
    {
        return Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/Permission"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/BasicPermission"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/AllPermission"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/SecurityPermission"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/UnresolvedPermission"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/Guard"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/Principal"))
                || Utf8.utf8IsAtBase(base, off, Magic.bytes("java/security/ProtectionDomain"))
                || Utf8.utf8IsAtBase(base, off, Magic.bytes("java/security/CodeSource"))
                || Utf8.utf8IsAtBase(base, off, Magic.bytes("java/security/CodeSigner"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/SecureClassLoader"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/Privileged"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/GeneralSecurityException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/NoSuchAlgorithmException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/NoSuchProviderException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/DigestException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/InvalidKeyException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/InvalidParameterException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/InvalidAlgorithmParameterException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/KeyException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/SignatureException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/ProviderException"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/Security"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/security/util/SecurityConstants"))
                || Utf8.utf8IsAtBase(base, off, Magic.bytes("sun/security/util/Debug"))
                || Utf8.utf8IsAtBase(base, off, Magic.bytes("sun/security/util/SecurityProperties"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/security/util/PropertyExpander"));
    }

    /**
     * Classes the batch closure does NOT pull because a reached method names them -- left to late resolution,
     * which pulls a class the first time compiled code actually reaches it (link stubs, the late static and
     * type-check paths). A superclass or interface of a pulled class is still pulled ({@link #pullStructural}).
     *
     * <p>WHY. RTA is flow-insensitive: a method counts as reached if any reached method names it, whatever arm it
     * sits in. Un-denying the locale-provider, text and ICU subsystems (2026-10-24/25) therefore pulled them into
     * EVERY program's first batch through cold arms -- Pattern.normalizeSlice (CANON_EQ) brings all of ICU,
     * Formatter$FormatSpecifier.localizedMagnitude brings NumberFormat/DecimalFormat and the provider layer,
     * Locale.getDisplayName brings MessageFormat and the date formats, ConditionalSpecialCasing brings
     * BreakIterator. MEASURED by naming each pull's referring method, then by deferring: SbProbe's batch 1 went
     * +1457 blobs / clinits=416 / pend=68877 -> +1096 / 305 / 45767, below its count before those subsystems were
     * opened (+1267 / 350), with 20 locale/format/text probes still matching the host.
     *
     * <p>Only subsystems whose entry points are COLD for most programs belong here: a class most programs run
     * would just pay the late pull on every boot instead of once in the batch.
     */
    private static boolean closureDeferred(Address base, int off)
    {
        return securityDeferred(base, off)
                || Loader.utf8HasPrefix(base, off, Magic.bytes("jdk/internal/icu/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/text/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/text/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/util/locale/provider/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/util/cldr/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/util/resources/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/spi/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/util/spi/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/lang/Character$Unicode"))
                || Utf8.utf8IsAtBase(base, off, Magic.bytes("java/lang/CharacterName"))
                // ... and the date/time, generic-signature and logger subsystems (2026-10-25): reached in every
                // first batch only through Formatter's %t arm (java/time, Calendar/Date/TimeZone and their
                // sun/util/calendar engine), generic-signature reflection (sun/reflect/generics) and
                // PlatformLogger (jdk/internal/logger).
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/time/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/util/calendar/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/reflect/generics/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("jdk/internal/logger/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/Calendar"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/GregorianCalendar"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/JapaneseImperialCalendar"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/TimeZone"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/SimpleTimeZone"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/util/Date"))
                // ... and stock MethodType (java.lang.invoke arc inc 0): named from cold arms of the base
                // closure (+22 blobs / +12 clinits on SbProbe's batch 1 when it was simply un-denied).
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/MethodType"))
                // ... and the stock class-loader stack (the loader arc, 2026-10-26): Class.getClassLoader0 and
                // ClassLoader.getSystemClassLoader name jdk.internal.loader.ClassLoaders, whose initializer builds
                // the built-in loaders, URLClassPath and the URL handlers -- reached by every first batch through
                // those two calls and MEASURED at +567 blobs / clinits 184 -> 362 on HelloDemo, launch ~2.6x on
                // QEMU. Installing the system loader on first use did NOT help by itself (1331 -> 1329 blobs):
                // the cost is the closure, which RTA computes from what is NAMED, not from what runs.
                || Loader.utf8HasPrefix(base, off, Magic.bytes("jdk/internal/loader/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("sun/net/www/"))
                // ... and jdk/internal/module/ (2026-10-30, un-denied): Class, Module, System and StackTraceElement
                // name ModuleBootstrap/Modules/ModuleHashes from module-graph arms a VM with no boot layer never
                // takes. ServicesCatalog and Resources were here already (built-in loaders).
                || Loader.utf8HasPrefix(base, off, Magic.bytes("jdk/internal/module/"))
                || Loader.utf8HasPrefix(base, off, Magic.bytes("java/security/SecureClassLoader"))
                || Utf8.utf8IsAtBase(base, off, Magic.bytes("java/security/CodeSource"))
                || (Loader.utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/"))
                        && !Loader.utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/VarHandle")));
    }

    private static void addPend(Address base, int classOff, int nameOff, int descOff, int kind)
    {
        if (pendN >= MAXPEND)
        {
            // SAY SO. Every other loader table capHalts on overflow ("halt with a clear message rather than
            // OOB-corrupt"); this one just stops recording, which makes RTA silently incomplete -- a class
            // that IS referenced simply never gets pulled, and the failure surfaces arbitrarily far away as
            // an unresolved type. It is not halted because a truncated closure still boots, and halting an
            // image that merely brushes the cap would be worse than finishing with a named gap.
            pendDropped += 1;                       // COUNT them: "full" alone cannot say whether the cap is
                                                    // short by ten or by ten thousand, so sizing it was a
                                                    // guess repeated per boot. The total is reported below.
            if (!pendFullReported)
            {
                pendFullReported = true;
                Uart.write(Magic.bytes("\n  PEND LIST FULL: RTA is now INCOMPLETE -- refs past this point are"
                        + " dropped and their classes never pulled. MAXPEND="));
                VM.printDec(MAXPEND);
                Uart.putc(0x0A);
            }
            return;
        }
        pendBase.set(pendN, base);
        pendClass[pendN] = classOff;
        pendName[pendN] = nameOff;
        pendDesc[pendN] = descOff;
        pendKind[pendN] = kind;
        pendN += 1;
    }

    /** For pd blob {@code b} at {@code base}, mark every pending-ref target it defines. True if any method was newly marked. */
    private static boolean resolveBlob(int b, Address base, int len)
    {
        Loader.parseForMethods(base, len);
        boolean grew = false;
        // Only static/special calls (kind 0) resolve here: they name a concrete class, so mark that method iff
        // this blob IS that class. PEND_PULL pulls a class only; virtual/interface (kind 1) go through
        // resolveVirtuals (RTA over instantiated types), NOT "mark in every class carrying the name+desc".
        // Reached through the class-name index rather than by scanning every pend per blob -- that guard was
        // a string compare per (blob, pend), which is pdCount x pendN of them per round.
        int r = psBucket[Utf8.utf8Hash(Loader.gbase, Loader.gThisNameOff) & (Loader.PVTAB - 1)];
        while (r >= 0)
        {
            if (r >= virtFrom && Loader.utf8EqAt(pendBase.get(r), pendClass[r], Loader.gbase, Loader.gThisNameOff))
            {
                long code = MethodTableCache.findDeclaredCached(b, pendBase.get(r), pendName[r], pendDesc[r]);
                if (code != 0L)
                {
                    grew = addReach(code) || grew;
                }
                else
                {
                    // ref names THIS class but it does not declare the method: an INHERITED static/special
                    // (declared in a superclass, e.g. ArrayList.subListRangeCheck -> AbstractList's). Walk up.
                    grew = markInheritedStatic(pendBase.get(r), pendName[r], pendDesc[r], base, len) || grew;
                }
            }
            r = psNext[r];
        }
        return grew;
    }

    /** Reachability for an inherited static/special call: walk the ref class's (== current blob) super chain and
     *  mark the declaring ancestor's method. Restores the current blob's parse state before returning. */
    private static boolean markInheritedStatic(Address refBase, int nameOff, int descOff, Address curBase, int curLen)
    {
        boolean grew = false;
        int pd = Loader.findPdByName(Loader.gbase, Loader.gThisNameOff);        // current blob's pd (the ref class)
        while (pd >= 0 && Loader.pdSuperOff[pd] != 0)
        {
            int spd = Loader.findPdByName(Loader.pdBase.get(pd), Loader.pdSuperOff[pd]);
            if (spd < 0)
            {
                break;
            }
            Loader.parseForMethods(Loader.pdBase.get(spd), Loader.pdLen[spd]);      // search the ancestor's method table
            long code = findMethodByRef(refBase, nameOff, descOff);
            if (code != 0L)
            {
                grew = addReach(code);
                break;
            }
            pd = spd;
        }
        Loader.parseForMethods(curBase, curLen);                  // restore resolveBlob's current blob
        return grew;
    }

    /** Record {@code base}'s {@code new C} target (Utf8 name at {@code off}) as an instantiated type (deduped). */
    private static void addInst(Address base, int off)
    {
        int i = 0;
        while (i < instN)
        {
            if (Loader.utf8EqAt(instBase.get(i), instOff[i], base, off))
            {
                return;
            }
            i += 1;
        }
        if (instN >= MAXPEND)
        {
            return;
        }
        instBase.set(instN, base);
        instOff[instN] = off;
        instN += 1;
    }

    /** Flag every pd blob that was {@code new}'d by a reached method; returns true if a new one was flagged. */
    private static boolean computeInstantiated()
    {
        boolean grew = false;
        int k = 0;
        while (k < instN)
        {
            int pd = Loader.findPdByName(instBase.get(k), instOff[k]);
            if (pd >= 0 && !pdInstantiated[pd])
            {
                pdInstantiated[pd] = true;
                grew = true;
            }
            k += 1;
        }
        // Instances the VM creates WITHOUT a bytecode `new`, so RTA can't see the site: string literals + concat
        // materialize a String (intern / the concat's scEnd); the JIT's null/bounds/div/cast checks throw these
        // exceptions via VM.newHelper. Flag them instantiated whenever loaded so their virtual methods resolve.
        grew = flagInstByName(Magic.bytes("java/lang/String")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/NullPointerException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/ArrayIndexOutOfBoundsException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/ArithmeticException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/ClassCastException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/NegativeArraySizeException")) || grew;
        // System.out/err are stock PrintStreams built by MetalStdStreams.newStream, which compiles ON DEMAND in
        // seedSystemStreams -- after the batch, so RTA never walks its `new`. Flag the class so its virtuals are
        // marked and its vtable fills in the batch that loads it.
        grew = flagInstByName(Magic.bytes("java/io/PrintStream")) || grew;
        // M4: Class mirrors (Loader.classMirror) and the boot task's lazy Thread (Loader.allocThreadObj) are
        // VM-alloc'd too -> flag them so getName/isInstance/... and getName/run compile + their vtables fill.
        grew = flagInstByName(Magic.bytes("java/lang/Class")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/Thread")) || grew;
        // The metal JavaLangAccess (seeded into SharedSecrets by seedJavaLangAccess, Heap.alloc, no bytecode
        // `new`) -> flag it so its getEnumConstantsShared interface method compiles + its itable fills (else
        // EnumMap.getKeyUniverse's invokeinterface hits an empty imap slot).
        grew = flagInstByName(Magic.bytes("jdk/internal/access/MetalJavaLangAccess")) || grew;
        return grew;
    }

    /**
     * Flag the loaded blob named {@code name} instantiated (a VM-created type with no `new` site); true if newly.
     *
     * <p>PROBED THROUGH {@code pnBucket}, not scanned. {@link #computeInstantiated} asks this for eleven fixed
     * names on EVERY ROUND of EVERY BATCH, and the scan compared each name against every blob not yet flagged
     * -- thousands of compares per name per round, most of them for a class that is already flagged or never
     * loads. MEASURED on SslEngineProbe (2026-11-04): `inst` was 6.9 s of a 25.4 s mark over 1,275 batches.
     *
     * <p>THE SAME BLOB THE SCAN FOUND: the lowest index that is unflagged and named {@code name}. The indexed
     * half is searched for the minimum (the chain runs descending, as {@link Loader#findPdByName} explains), and
     * blobs added since the index was built -- all above {@code pnIndexed} -- are scanned only if it misses.
     * Called only from the round loop, after that round's {@link #buildNameIndex}.
     */
    private static boolean flagInstByName(byte[] name)
    {
        int best = -1;
        int from = 0;
        if (Loader.pnBucket != null)
        {
            int k = Loader.pnBucket[Utf8.bytesHash(name) & (Loader.PVTAB - 1)];
            while (k >= 0)
            {
                if ((best < 0 || k < best) && !pdInstantiated[k] && Utf8.utf8IsAtBase(Loader.pdBase.get(k), Loader.pdNameOff[k], name))
                {
                    best = k;
                }
                k = Loader.pnNext[k];
            }
            from = Loader.pnIndexed;
        }
        int i = from;
        while (best < 0 && i < Loader.pdCount)
        {
            if (!pdInstantiated[i] && Utf8.utf8IsAtBase(Loader.pdBase.get(i), Loader.pdNameOff[i], name))
            {
                best = i;
            }
            i += 1;
        }
        if (best < 0)
        {
            return false;
        }
        pdInstantiated[best] = true;
        return true;
    }

    /** pd index of {@code pd}'s superclass, or -1 (Object / super not loaded). */
    private static int superPdOf(int pd)
    {
        Loader.parseConstPool(Loader.pdBase.get(pd), Loader.pdLen[pd]);
        int superIdx = u2(Loader.gp.plus(4));                       // Loader.gp -> access_flags; this(+2), super(+4)
        if (superIdx == 0)
        {
            return SUPER_NONE;                           // java/lang/Object
        }
        return Loader.findPdByName(Loader.gbase, Loader.gcp[u2(Loader.gbase.plus(Loader.gcp[superIdx]))]);
    }

    /**
     * {@link #superPdOf} memoized. Two of its three answers are PERMANENT: a class's superclass NAME is fixed
     * by its classfile, so once that blob is loaded the link never changes, and "declares no superclass" never
     * changes. Only {@link #SUPER_UNLOADED} is retried.
     *
     * <p>Measured at ZERO on its own -- the chain walk's cost was `parseForMethods`, not this. It pays only
     * now that {@link MethodTableCache#ensureMethodTable} has removed the bigger parse and left this one exposed.
     */
    private static int cachedSuperOf(int pd)
    {
        int v = pdSuperCache[pd];
        if (v != 0)
        {
            return v - 3;
        }
        int r = superPdOf(pd);
        if (r != SUPER_UNLOADED)
        {
            pdSuperCache[pd] = r + 3;
        }
        return r;
    }

    /**
     * RTA: mark each virtual/interface call's real targets — for every instantiated receiver type, the method
     * it resolves up its own superclass chain (nearest definition wins). Replaces the CHA "mark in every class
     * carrying name+desc". Structured class-outer / level-once: each (instantiated class, superclass-chain
     * level) is parsed ONCE and matched against every virtual pend, instead of re-parsing the whole chain per
     * pend — the naive per-pend form re-parsed (and re-copied via toBytes/parseConstPool) tens of thousands of
     * times, churning ~90 MiB of heap and blowing the demand-load arena past the A64 bl reach.
     */
    private static boolean resolveVirtuals()
    {
        boolean grew = false;
        if (virtResolved == null || virtResolved.length < pendN)
        {
            virtResolved = new int[pendN + 64];
        }
        buildPendIndex();
        int c = mrLo;
        while (c < Loader.pdCount)
        {
            if (pdInstantiated[c] && !markSettled(c)
                    && (pdVirtTo[c] < pendN || pdVirtEpoch[c] != Loader.pdCount))
            {                                            // a settled receiver's vtable is built and frozen, and
                                                         // a class with no NEW pends has nothing to dispatch --
                                                         // skipping it skips the whole superclass chain walk,
                                                         // which is what this pass actually spends its time on.
                // "No pend dispatched yet for this class" by STAMP rather than by clearing the array: the
                // clear was pendN writes per instantiated class, and pendN reaches 24,826 here.
                virtStamp = c + 1;                       // +1: the array starts at 0, and 0 is a valid class index
                virtFrom = pdVirtEpoch[c] == Loader.pdCount ? pdVirtTo[c] : 0;   // chain may have grown -> redo all
                pdVirtTo[c] = pendN;
                pdVirtEpoch[c] = Loader.pdCount;
                int cur = c;
                int guard = 0;
                vWalks += 1;
                while (cur >= 0 && guard < 64)           // walk C's superclass chain, parsing each level once
                {
                    vLevels += 1;
                    boolean lvlGrew;
                    long vt0 = Magic.readCNTPCT_EL0();
                    if (MethodTableCache.ensureMethodTable(cur))
                    {
                        vCached += 1;
                        vMethods += MethodTableCache.mtLen[cur];
                        lvlGrew = MethodTableCache.matchLevelCached(cur);
                        vCacheT += Magic.readCNTPCT_EL0() - vt0;
                    }
                    else
                    {
                        vParsed += 1;
                        Loader.parseForMethods(Loader.pdBase.get(cur), Loader.pdLen[cur]);
                        lvlGrew = MethodTableCache.matchLevel();
                        vParseT += Magic.readCNTPCT_EL0() - vt0;
                    }
                    if (lvlGrew)
                    {
                        vLevelsGrew += 1;
                    }
                    grew = lvlGrew || grew;
                    cur = cachedSuperOf(cur);
                    guard += 1;
                }
            }
            c += 1;
        }
        return grew;
    }

    /** Grow a per-pend link array to hold {@code n}, KEEPING the links already indexed. */
    private static int[] growPendLinks(int[] a, int n)
    {
        if (a != null && a.length >= n)
        {
            return a;
        }
        int[] b = new int[n + 64];
        if (a != null)
        {
            int i = 0;
            while (i < a.length)
            {
                b[i] = a[i];
                i += 1;
            }
        }
        return b;
    }

    /** Fresh bucket table: every bucket empty (-1). Allocation does not promise zeroes, nor would 0 do. */
    private static int[] emptyBuckets()
    {
        int[] b = new int[Loader.PVTAB];
        int i = 0;
        while (i < Loader.PVTAB)
        {
            b[i] = -1;
            i += 1;
        }
        return b;
    }

    /** Empty both pend indexes before {@code pendN} returns to 0: only the buckets their pends went into. */
    private static void clearPendIndexes()
    {
        int p = 0;
        while (p < psIndexed)
        {
            if (psSlot[p] >= 0)
            {
                psBucket[psSlot[p]] = -1;
            }
            p += 1;
        }
        p = 0;
        while (p < pvIndexed)
        {
            if (pvSlot[p] >= 0)
            {
                pvBucket[pvSlot[p]] = -1;
            }
            p += 1;
        }
        psIndexed = 0;
        pvIndexed = 0;
    }

    /** Index the STATIC/special pends (kind 0) added since the last call by callee class name, for {@link #resolveBlob}. */
    private static void buildStaticPendIndex()
    {
        if (psBucket == null)
        {
            psBucket = emptyBuckets();
            psIndexed = 0;
        }
        psNext = growPendLinks(psNext, pendN);
        psSlot = growPendLinks(psSlot, pendN);
        while (psIndexed < pendN)
        {
            int p = psIndexed;
            if (pendKind[p] == 0)
            {
                int h = Utf8.utf8Hash(pendBase.get(p), pendClass[p]) & (Loader.PVTAB - 1);
                psNext[p] = psBucket[h];
                psBucket[h] = p;
                psSlot[p] = h;
            }
            else
            {
                psNext[p] = -1;
                psSlot[p] = -1;
            }
            psIndexed += 1;
        }
    }

    /** Index the VIRTUAL pends (kind 1) added since the last call by name+descriptor hash, for {@link MethodTableCache#matchLevel}. */
    private static void buildPendIndex()
    {
        if (pvBucket == null)
        {
            pvBucket = emptyBuckets();
            pvIndexed = 0;
        }
        pvNext = growPendLinks(pvNext, pendN);
        pvSlot = growPendLinks(pvSlot, pendN);
        while (pvIndexed < pendN)
        {
            int p = pvIndexed;
            if (pendKind[p] == 1)
            {
                int h = Utf8.sigHash(pendBase.get(p), pendName[p], pendDesc[p]) & (Loader.PVTAB - 1);
                pvNext[p] = pvBucket[h];
                pvBucket[h] = p;
                pvSlot[p] = h;
            }
            else
            {
                pvNext[p] = -1;
                pvSlot[p] = -1;
            }
            pvIndexed += 1;
        }
    }

    /**
     * Classes the VM instantiates ITSELF, outside the call graph, and then hands to arbitrary code. Their
     * vtables must have no holes, so their virtuals get deferral stubs even where RTA prunes them.
     *
     * <p>The failure a hole causes here is the worst kind. BAKED code carries no {@code dispatchTargetGuard}
     * -- {@code implicitChecks()} is false for the writer, deliberately -- and baked
     * {@code String.valueOf(Object)} does {@code obj.toString()} on whatever it is given. A pruned
     * {@code toString} slot therefore becomes {@code blr 0}, which the firmware's low-memory shim turns into a
     * re-entry of the image entry: "BOOT RE-ENTERED", no exception, no name, nothing to chase. That is what
     * printing a {@code StackTraceElement} from guest code did -- {@code getStackTrace()} itself worked and
     * returned five frames; the crash was `"at " + element`.
     *
     * <p>{@code seedNativelyReached} already marks ONE method here ({@code getMethodName}) for exactly this
     * reason. Marking one method of a class the VM hands out whole is arbitrary -- any of its methods can be
     * the one called -- so stub them all. Stubs pull nothing: a few instructions routing the first call into
     * lazyCompile.
     *
     * <p>{@code java/lang/Class} is the other native instantiation ({@code classMirror}) and is NOT listed:
     * its mirrors work today and widening this without a failing case to check against would be a change with
     * no evidence behind it. If a mirror ever wild-branches the same way, this is the list to add it to.
     */
    static boolean nativelyInstantiated(Address base, int nameOff)
    {
        return Utf8.utf8IsAtBase(base, nameOff, Magic.bytes("java/lang/StackTraceElement"));
    }

    /**
     * Seed the signatures no call site names, in ONE pass over the blobs. Each is a method some NATIVE or
     * out-of-graph path enters, so RTA prunes it and its vtable slot stays 0:
     * <ul>
     *   <li>{@code run()V} -- the thread trampoline enters a Runnable outside the call graph.</li>
     *   <li>{@code getAndBitwiseOr}/{@code compareAndSet} -- the VarHandle overlay's ops. Their call sites are
     *       signature-polymorphic ({@code getAndBitwiseOr:(LSocket;I)I}), so they never match the overlay's
     *       own descriptor and normal invoke-target marking misses them.</li>
     *   <li>{@code getMethodName} -- StackTraceElement is instantiated natively by {@code frameToElement}.</li>
     *   <li>{@code getEnumConstants} -- Class mirrors are instantiated natively by {@code classMirror}, the
     *       same blind spot. Stock code reaches this one through a mirror it did not create:
     *       {@code EnumMap.getKeyUniverse -> getJavaLangAccess().getEnumConstantsShared(k) ->
     *       k.getEnumConstants()}. Pruned, the call lands in dispatchTargetGuard as a bare AIOOBE -- which is
     *       where StreamOpFlag's initializer died.</li>
     * </ul>
     *
     * <p>One pass, not five: {@link Loader#parseForMethods} re-parses the whole constant pool, and doing that five
     * times per blob per round made these seeds a tenth of the entire mark.
     */
    private static boolean seedNativelyReached()
    {
        boolean grew = false;
        int b = mrLo;
        while (b < Loader.pdCount)
        {
            if (!markSettled(b) && pdSeeded[b] == 0)
            {
                pdSeeded[b] = 1;                         // these signatures do not change; one look each
                // ONE parse, many scans: findMethodByBytes reads gp/gcp and never advances them, so the
                // constant pool this sets up is good for every lookup below.
                Loader.parseForMethods(Loader.pdBase.get(b), Loader.pdLen[b]);
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("run"), Magic.bytes("()V"))) || grew;
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("getAndBitwiseOr"),
                        Magic.bytes("(Ljava/lang/Object;I)I"))) || grew;
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("compareAndSet"),
                        Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"))) || grew;
                // The REFERENCE accessors java.util.concurrent drives its structures through. Seeded for the
                // same reason as the two above: their call sites are signature-polymorphic, so RTA never sees
                // them named and would prune the bodies, leaving a 0 vtable slot behind the by-name resolve.
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("set"),
                        Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;)V"))) || grew;
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("get"),
                        Magic.bytes("(Ljava/lang/Object;)Ljava/lang/Object;"))) || grew;
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("setRelease"),
                        Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;)V"))) || grew;
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("weakCompareAndSet"),
                        Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"))) || grew;
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("getMethodName"),
                        Magic.bytes("()Ljava/lang/String;"))) || grew;
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, Magic.bytes("getEnumConstants"),
                        Magic.bytes("()[Ljava/lang/Object;"))) || grew;
            }
            b += 1;
        }
        return grew;
    }

    /** Add the {@code name/desc} method of every loaded blob that defines it (for run()V trampoline seeds). */
    private static boolean seedAllNamed(byte[] name, byte[] desc)
    {
        boolean grew = false;
        int b = mrLo;
        while (b < Loader.pdCount)
        {
            if (!markSettled(b))
            {
                Loader.parseForMethods(Loader.pdBase.get(b), Loader.pdLen[b]);
                grew = addReach(Loader.findMethodByBytes(Loader.gbase, name, desc)) || grew;
            }
            b += 1;
        }
        return grew;
    }

    /**
     * Seed every method of the {@link #rootBlob root blob} (a class defined from supplied bytes), and flag it
     * instantiated. Both halves are needed: seeding the methods is what gives them deferral stubs and so a
     * filled vtable, and the instantiated flag is what lets {@link #resolveVirtuals} mark the INHERITED
     * virtuals it calls up its superclass chain -- RTA infers instantiation from a `new` site, and the only
     * `new` for a defined class is a later reflective one it cannot see.
     */
    private static boolean seedRootBlob()
    {
        if (Loader.gRootBlob == 0L)
        {
            return false;
        }
        boolean grew = false;
        Address rootBlob = Address.fromLong(Loader.gRootBlob);
        Loader.parseForMethods(rootBlob, Loader.blobLenOf(rootBlob));
        Address p = Loader.gMethodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            grew = addReach(Loader.findCode(Loader.gbase, p.plus(8), attrs)) || grew;   // 0 for a native/abstract method
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        int i = 0;
        while (i < Loader.pdCount)
        {
            if (Loader.pdBase.get(i).toLong() == Loader.gRootBlob && !pdInstantiated[i])
            {
                pdInstantiated[i] = true;
                grew = true;
            }
            i += 1;
        }
        return grew;
    }

    /**
     * Mark every runnable {@code <clinit>} reachable, so {@link #collectRefs} pulls the classes an initializer
     * calls. A {@code <clinit>} is never the target of an invoke bytecode (the loader runs it implicitly at
     * class load), so without this its callees are never in the reachable closure — e.g.
     * {@code ArraysSupport.<clinit>} calls {@code SharedSecrets.getJavaLangAccess()}, and an unpulled
     * {@code SharedSecrets} leaves that {@code invokestatic} unresolved (bl to an unpatched site → wild
     * branch when the initializer runs). Only clinits that will ACTUALLY run are seeded: {@link ClassInitializer#clinitBlocked}
     * (native/property-reading) ones are skipped, as are {@link ClassInitializer#clinitCompilable} rejects (an unrunnable
     * initializer whose refs would needlessly grow the closure) — matching exactly what {@link ClassInitializer#runClinit} executes.
     */
    private static boolean seedClinits()
    {
        boolean grew = false;
        int b = mrLo;
        while (b < Loader.pdCount)
        {
            // Settled blobs skipped for the same reason as everywhere else: their initializer was marked and
            // compiled by their own batch, and phase B will not compile it again. Re-marking it here only
            // re-parsed the constant pool -- and reported `grew`, buying an extra round for nothing. Once per
            // blob for the same reason: a class's initializer, and whether it is blocked, do not change.
            if (!markSettled(b) && pdSeedC[b] == 0)
            {
                pdSeedC[b] = 1;
                Loader.parseForMethods(Loader.pdBase.get(b), Loader.pdLen[b]);
                if (!ClassInitializer.clinitBlocked())
                {
                    long code = Loader.findMethodByBytes(Loader.gbase, Magic.bytes("<clinit>"), Magic.bytes("()V"));
                    if (code != 0L && ClassInitializer.clinitCompilable(code, Loader.gcodeLen))
                    {
                        grew = addReach(code) || grew;
                    }
                }
            }
            b += 1;
        }
        return grew;
    }

    /** Find the method matching a name/descriptor given as cross-blob offsets ({@code refBase}), in the current cp. */
    static long findMethodByRef(Address refBase, int nameOff, int descOff)
    {
        Address p = Loader.gp;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (Loader.utf8EqAt(refBase, nameOff, Loader.gbase, Loader.gcp[u2(p.plus(2))]) && Loader.utf8EqAt(refBase, descOff, Loader.gbase, Loader.gcp[u2(p.plus(4))]))
            {
                long code = Loader.findCode(Loader.gbase, p.plus(8), attrs);
                if (code != 0L)
                {
                    return code;
                }
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return 0L;
    }

    /**
     * Repeatedly probe the registered blobs for class references and register any that are embedded in
     * {@link VM#classDir} but not yet registered, until the transitive closure is complete. References
     * to unembedded roots ({@code java/lang/Object}, {@code magic/Magic}) are simply not found and skipped.
     */
    private static void resolveClosureFromDir()
    {
        boolean grew = true;
        while (grew)
        {
            grew = false;
            Loader.probeAll();                                // (re)builds pdNameOff + the dep list
            int d = 0;
            while (d < Loader.dpCount)
            {
                Address base = Loader.dpBase.get(d);                 // referenced class name (Utf8 in this blob)
                int off = Loader.dpOff[d];
                if (!nameRegistered(base, off) && registerNameFromDir(base, off) != 0L)
                {
                    grew = true;
                }
                d += 1;
            }
        }
    }

    /** True if some registered blob's own name equals the class name at {@code off} in {@code base}. */
    private static boolean nameRegistered(Address base, int off)
    {
        if (Loader.pnBucket != null)
        {
            int j = Loader.pnBucket[Utf8.utf8Hash(base, off) & (Loader.PVTAB - 1)];
            while (j >= 0)
            {
                if (Loader.utf8EqAt(base, off, Loader.pdBase.get(j), Loader.pdNameOff[j]))
                {
                    return true;
                }
                j = Loader.pnNext[j];
            }
        }
        // Blobs added since the index was built -- this round's own pulls. Their pdNameOff is not set until
        // the next probeAll, so they cannot be hashed; scan them. That is exactly what the whole loop used to
        // do for them, and it stays correct for the same reason: registerNameFromDir dedupes by ADDRESS
        // (alreadyBlob), so an unreliable answer here costs a re-check, never a duplicate blob.
        int k = Loader.pnIndexed;
        while (k < Loader.pdCount)
        {
            if (Loader.utf8EqAt(base, off, Loader.pdBase.get(k), Loader.pdNameOff[k]))
            {
                return true;
            }
            k += 1;
        }
        return false;
    }

    /**
     * Index the registered class names, for {@link #nameRegistered}. Built right after {@link Loader#probeAll}, which
     * is what fills {@code pdNameOff} -- before that a blob has no name to hash.
     *
     * <p>The pull loop asks {@code nameRegistered} once per pend, and a linear scan made that
     * {@code pends x blobs} string compares per round. With 24,826 pends over 448 blobs it was 11M per round,
     * 33 rounds -- 54% of the entire mark on hardware, and the largest single item left after the first two
     * rounds of this work.
     */
    private static void buildNameIndex()
    {
        // INCREMENTAL, like every other index here. It was cleared (65,536 buckets) and rebuilt from blob 0
        // on EVERY ROUND of EVERY BATCH, although a blob's name never changes once probeAll has set it
        // (pdNameOff is written once, behind pdProbedTo) and blobs only append within a launch. MEASURED on
        // SslEngineProbe (2026-11-04): 6.5 s over 2,834 rounds, the largest single item in `mark`.
        // Appending in index order head-inserts exactly as the full rebuild did, so every chain is identical.
        // resetLoader nulls pnBucket with the blobs it indexes; a count that went BACKWARDS rebuilds too.
        if (Loader.pnBucket == null || Loader.pnIndexed > Loader.pdCount)
        {
            if (Loader.pnBucket == null)
            {
                Loader.pnBucket = new int[Loader.PVTAB];
                Loader.pnNext = new int[Loader.MAXBLOB];
            }
            int i = 0;
            while (i < Loader.PVTAB)
            {
                Loader.pnBucket[i] = -1;
                i += 1;
            }
            Loader.pnIndexed = 0;
        }
        while (Loader.pnIndexed < Loader.pdCount)
        {
            int h = Utf8.utf8Hash(Loader.pdBase.get(Loader.pnIndexed), Loader.pdNameOff[Loader.pnIndexed]) & (Loader.PVTAB - 1);
            Loader.pnNext[Loader.pnIndexed] = Loader.pnBucket[h];
            Loader.pnBucket[h] = Loader.pnIndexed;
            Loader.pnIndexed += 1;
        }
    }

    /** Register the class named at Utf8 offset {@code off} in {@code base} if it's embedded; returns its base or 0. */
    static long registerNameFromDir(Address base, int off)
    {
        Address namePtr = base.plus(off + 2);                 // skip the u2 length prefix
        int len = u2(base.plus(off));
        // java/lang/Object is every class's superclass, so auto-pulling it from the dir would drag it into
        // EVERY closure (changing every vtable). It's an implicit root: loaded only when a demo explicitly
        // seeds it (the HashMap closure does, so String there inherits its hashCode/equals slots).
        if (Utf8.utf8IsAtBase(base, off, Magic.bytes("java/lang/Object")))
        {
            return 0L;
        }
        if (Loader.isDenylisted(base, off))                   // metal-absent subtree: prune (a call to it traps, see patchRelocs)
        {
            return 0L;
        }
        long tf = Magic.readCNTPCT_EL0();              // the FETCH: a jar miss inflates the whole classfile here
        long bytes = VM.dirBytes(namePtr, len).toLong();
        mrFetch += Magic.readCNTPCT_EL0() - tf;
        if (bytes == 0L || Loader.alreadyBlob(bytes))
        {
            return 0L;                                 // unembedded root (Object/Magic), or already pulled this pass
        }
        if (!Loader.LOAD_TRACE)
        {
            tf = Magic.readCNTPCT_EL0();
            int dlen = (int) VM.dirLen(namePtr, len);
            mrFetch += Magic.readCNTPCT_EL0() - tf;
            Loader.addBlob(bytes, dlen);
            return bytes;
        }
        long t0 = Magic.readCNTPCT_EL0();
        Loader.addBlob(bytes, (int) VM.dirLen(namePtr, len));
        long us = Loader.elapsedUs(t0);
        Uart.write(Magic.bytes("  load "));
        Loader.writeName(namePtr, len);
        Uart.putc(0x20);
        Loader.printDur(us);
        Uart.putc(0x0A);
        return bytes;
    }
}
