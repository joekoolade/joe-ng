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
import compiler.Baseline;
import compiler.Symbols;
import magic.Address;
import magic.AddressArray;
import magic.Magic;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * Dynamic linking (JikesRVM: {@code DynamicLinker} / {@code TableBasedDynamicLinker}): link stubs for call sites
 * RTA cannot see, resolving the stubs baked into the image, the phase-A dynamic-link cell index, and resolving a
 * method or static from a constant-pool reference. Split out of {@link Loader}.
 *
 * <p>RTA marks a method reachable, then walks its body to mark what IT calls. A method reached ONLY through
 * Method.invoke breaks that chain: it compiles on demand (the method registry has it), but nothing statically
 * reachable names it, so its OWN callees are never marked and their classes are never pulled. patchRelocs then finds
 * no target for each of its call sites. Those sites used to go straight to denylistTrap -- correct for a genuinely
 * pruned class, wrong for a class that is merely absent from a closure computed without ever reading this body. So
 * an unresolved site whose callee is NOT denylisted gets a link stub instead: on the FIRST call (i.e. only if the
 * site is actually reached) it demand-loads the class and resolves the method, exactly as resolveBakeStub does for
 * the baked world, and memoizes. A cold site costs one 32-byte stub and never runs; a genuinely unresolvable one
 * still lands in denylistTrap, with the trapwire index intact because the trampoline restores LR before
 * tail-branching.
 *
 * <p>The writer stashes {@code vm/DynamicLinker.resolveLinkStub(I)J} into {@code VM.resolveLinkStubAddr} BY NAME.
 */
final class DynamicLinker
{
    private DynamicLinker()
    {
    }

    /**
     * The phase-A cell for a method whose class, name and descriptor each live at a {@code (base, offset)}
     * pair, or 0. The three offset-keyed lookups differ only in where those runs come from — a reloc's ref
     * blob, the compile context plus a walked superclass, or the absolute utf8 runs of a bake stub — so they
     * all funnel through here. Returns the CELL address; callers read it for callable code (the lazy stub,
     * or the body once first-called) or hand it to an emitter to indirect through.
     */
    /**
     * Name-keyed hash index over the phase-A cell table, so {@link #dlCellOf} is a PROBE rather than a linear
     * scan comparing THREE Utf8 strings against every cell.
     *
     * <p>MEASURED, and this is where `patch` actually went. {@code dlStubByRef} is the LAST tier of
     * {@link #globalBufByRef}, so it catches every site that misses the direct and super-chain tiers -- AND
     * every {@code <init>}, which short-circuits straight to it because a constructor is never inherited.
     * Constructor call sites are everywhere. Those sites never memoise (see {@code rcReg}), so they
     * re-resolve on EVERY batch, and {@code dlN} grows with every class loaded: the scan is quadratic in the
     * load. Splitting the call-site loop three ways put 98% of it in the LOOKUP (lookT 11,949ms of callT
     * 12,169ms), which is itself ~95% of `patch` -- 1,542ms/batch at batch 165 on hardware.
     *
     * <p>Incremental like {@code rgBucket}: the table only ever grows within a batch and a cell's name never
     * changes, so entries already indexed stay valid. {@code resetLoader} nulls {@code dlTab} and zeroes
     * {@code dlN} together, and the index is rebuilt from scratch when it sees the count go backwards.
     */
    static final int DLTAB = 32768;              // power of two > MAXLAZY; chained
    static int[] dlBucket;                       // name hash -> first cell index, -1 when empty
    static int[] dlNext;                         // cell index -> next entry with the same name hash
    private static int dlIndexed;
    static DynLink[] dlTab;    // phase-A dynamic-linking table (reified: one DynLink per method cell)
    static int    dlN;
    // 256 was enough for every closure until the console launcher, whose picocli+JUnit graph exhausts it --
    // and running out is NOT a cap that merely limits an optimisation: the caller leaves the site pointing at
    // denylistTrap, so the program dies blaming a denylist the class is not on.
    private static final int MAXLINKSTUB = 4096;
    private static AddressArray lkClsU  = AddressArray.create(MAXLINKSTUB);   // absolute {u2 len}{bytes} runs, as resolveBakeStub takes
    private static AddressArray lkNameU = AddressArray.create(MAXLINKSTUB);
    private static AddressArray lkDescU = AddressArray.create(MAXLINKSTUB);
    private static long[] lkMemo  = new long[MAXLINKSTUB];   // resolved target, filled on first call
    private static long[] lkStub  = new long[MAXLINKSTUB];
    static int    lkCount;
    static long   linkTrampAddr;
    private static boolean lkFullReported;
    /**
     * Say WHY a link stub could not resolve, ONCE per class+method. Three very different failures shared one
     * silent {@code return 0} -- absent class, half-built class, and "loaded fine but no callable body" -- and
     * all three surface identically as a DENYLIST TRAP, whose message then blames a denylist that may have
     * nothing to do with it (ConcurrentHashMap is not denylisted at all). Each arm needs a different fix, so
     * each says which it is.
     */
    /**
     * The reason the LAST {@link #resolveLinkTarget} answered 0: 1 = class absent from the classDir, 2 = class
     * loaded but not instantiated, 3 = class fine but no body for that name+descriptor.
     *
     * <p>Recorded rather than printed at the point of failure because {@code resolveLinkTarget} IS CALLED IN A
     * LOOP: the late-virtual path walks the receiver's superclass chain and a 0 at one level is the normal way
     * of saying "not declared here, try the super". Printing there reported 26 failures on a boot where all 44
     * tests PASS -- a report that cries wolf on a green run is worse than none. Only the caller knows the walk
     * is over, so only the caller prints.
     */
    static int lnkFailWhy;
    private static long[] lnkFailSeen;
    private static int lnkFailSeenN;
    // Superclass-chain hop cap for the late virtual resolve. A registry chain is acyclic by construction,
    // so this only bounds the damage if one ever is not -- a spin here would hang mid-dispatch.
    static final int MAXCHAIN = 64;
    // M8 endgame: consult the writer's baked-method link table before lazy-compiling (default ON).
    static final boolean BAKED_LINK = true;

    /** Method buffer for a call ref given as blob base + Utf8 offsets (patchRelocs re-resolution), or 0. */
    static long globalBufByRef(Address refBase, int classOff, int nameOff, int descOff)
    {
        MethodRegistry.buildRegIndex();                                 // incremental: only entries added since last time
        Loader.rgHitIdx = -1;
        Loader.gwOk = false;                                    // see rcGate: only the tiers below that set it stand
        Loader.gwRgN = 0;
        // CLASS+NAME keys the index. Tier 2 wants the SAME name under an ANCESTOR class, so it re-hashes per
        // level rather than sharing this bucket -- which is the point: sharing a name-only bucket is what made
        // both tiers walk a chain one entry long per class declaring that name.
        int h = Utf8.utf8Hash2(refBase, classOff, refBase, nameOff) & (MethodRegistry.RGTAB - 1);
        boolean fits = Relocator.gateRg(h);
        int i = MethodRegistry.rgBucket[h];
        while (i >= 0)
        {
            Loader.t1Steps += 1;
            // The SAME predicate the linear scan used -- the index only narrows which entries it is applied
            // to. Name-keyed, so the chain still has to check class and descriptor: `<init>`/`run`/`get`
            // repeat across the registry constantly.
            if (Loader.utf8EqAt(refBase, classOff, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].classOff)
                    && Loader.utf8EqAt(refBase, nameOff, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].nameOff)
                    && Loader.utf8EqAt(refBase, descOff, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].descOff))
            {
                Loader.rgHitIdx = i;                            // memoisable: a single registry entry answered
                Loader.bufResolveLog(refBase, classOff, nameOff, descOff, MethodRegistry.rgTab[i].buf, 1);
                return MethodRegistry.rgTab[i].buf;
            }
            i = MethodRegistry.rgNext[i];
        }
        // Class-qualified miss: an INHERITED static/special method (invokestatic/invokespecial to a method the ref
        // names via a subclass but that is declared in a SUPERclass, e.g. `ArrayList.subListRangeCheck` really
        // AbstractList.subListRangeCheck). Walk the ref class's super chain and match each ancestor's registration.
        //
        // A CONSTRUCTOR is never inherited, so it must not walk: `<init>()V` on an unregistered class would
        // match `java/lang/Object.<init>()V` -- always registered, since Object is the one eagerly compiled
        // class -- and Object's <init> is a NO-OP. The call then resolves silently to it and the real
        // constructor never runs, leaving every field 0. That is how `new Formatter()` in a lazily compiled
        // `String.formatted` produced a Formatter whose buffer was null, and the NPE surfaced far away, deep
        // inside JUnit's message formatting. Falling through to the stub tier instead gives it a link stub,
        // which resolves the actual constructor on first call.
        if (Utf8.utf8IsAtBase(refBase, nameOff, Magic.bytes("<init>")))
        {
            long ib = Relocator.gatedCell(refBase, classOff, refBase, nameOff, descOff, fits);
            Loader.bufResolveLog(refBase, classOff, nameOff, descOff, ib, 3);
            return ib;
        }
        // THE NAMED CLASS FIRST, and every level after it the same way: a class that DECLARES the method
        // answers it even when its body is not registered yet (JVMS 5.4.3.3 -- resolution stops at the first
        // class declaring name+descriptor). The walk below used to test only the REGISTRY at each level, so a
        // declared but not-yet-compiled method was skipped and the call bound SILENTLY to the nearest compiled
        // ancestor: `super.close()` in ZipFile$ZipFileInflaterInputStream, naming InflaterInputStream.close,
        // ran FilterInputStream.close -- so `closed` was never set, the stream's Inflater was ended under it,
        // and the next read() spun for ever on an Inflater answering 0. A declaring level answers with its
        // static's phase-A cell, or 0, which sends the site to the link-stub tier: resolveLinkTarget compiles
        // the declared body on first call. (<init> above is the same rule, special-cased before this existed.)
        int pd = Loader.findPdByName(refBase, classOff);
        int dec = pd >= 0 ? blobDeclares(Loader.pdBase.get(pd), refBase, nameOff, refBase, descOff) : -1;
        if (dec == 1)
        {
            long own = Relocator.gatedCell(refBase, classOff, refBase, nameOff, descOff, fits);
            Loader.bufResolveLog(refBase, classOff, nameOff, descOff, own, 5);
            return own;
        }
        fits = fits && dec == 0;                         // -1 (unparsed) may read 1 next pass: no gate
        while (pd >= 0 && Loader.pdSuperOff[pd] != 0)
        {
            int spd = Loader.findPdByName(Loader.pdBase.get(pd), Loader.pdSuperOff[pd]);
            if (spd < 0)
            {
                break;
            }
            // PROBE, NOT SCAN -- and this tier, not tier 1, is where the time went. Tier 1 was indexed in
            // an earlier increment and this was left scanning ALL rgCount entries PER SUPERCLASS LEVEL, for
            // every site that misses tier 1. Those sites never memoise (see rcReg), so they re-resolve on
            // every batch: MEASURED at 98% of the whole call-site loop (lookT 11,949ms of callT 12,169ms),
            // which is itself ~95% of `patch`. The wanted name is the same one tier 1 hashed, so the bucket
            // is reused and only the CLASS comparison differs -- the predicate is otherwise identical.
            int hs = Utf8.utf8Hash2(Loader.pdBase.get(spd), Loader.pdNameOff[spd], refBase, nameOff) & (MethodRegistry.RGTAB - 1);
            fits = fits && Relocator.gateRg(hs);
            int j = MethodRegistry.rgBucket[hs];
            while (j >= 0)
            {
                Loader.t2Steps += 1;
                if (Loader.utf8EqAt(Loader.pdBase.get(spd), Loader.pdNameOff[spd], MethodRegistry.rgTab[j].base, MethodRegistry.rgTab[j].classOff)
                        && Loader.utf8EqAt(refBase, nameOff, MethodRegistry.rgTab[j].base, MethodRegistry.rgTab[j].nameOff)
                        && Loader.utf8EqAt(refBase, descOff, MethodRegistry.rgTab[j].base, MethodRegistry.rgTab[j].descOff))
                {
                    Loader.bufResolveLog(refBase, classOff, nameOff, descOff, MethodRegistry.rgTab[j].buf, 2);
                    return MethodRegistry.rgTab[j].buf;
                }
                j = MethodRegistry.rgNext[j];
            }
            int sdec = blobDeclares(Loader.pdBase.get(spd), refBase, nameOff, refBase, descOff);
            if (sdec == 1)
            {
                long declared = Relocator.gatedCell(Loader.pdBase.get(spd), Loader.pdNameOff[spd], refBase, nameOff, descOff, fits);
                Loader.bufResolveLog(refBase, classOff, nameOff, descOff, declared, 5);
                return declared;                         // this ancestor declares it: never climb past it
            }
            fits = fits && sdec == 0;
            pd = spd;
        }
        // Last tier: a METADATA-ONLY class's celled static is never eagerly compiled or registered, so both
        // scans above miss it. Its phase-A cell holds callable code -- the lazy stub, or the compiled body
        // once first-called -- so a `bl` may target that directly (the same tier bufBySigU uses for bake
        // stubs). Without this a reloc into a celled method traps: NioSocketImpl's `lambda$closerFor$0` is
        // the one that bites, since the lambda IS the Cleaner action run by close().
        long fb = dlStubByRef(refBase, classOff, nameOff, descOff);
        Loader.bufResolveLog(refBase, classOff, nameOff, descOff, fb, 4);
        return fb;
    }

    static void buildDlIndex()
    {
        if (dlBucket == null || dlIndexed > dlN)         // rebuilt when resetLoader zeroes the table
        {
            dlBucket = new int[DLTAB];
            dlNext = new int[LazyCompiler.MAXLAZY];
            int b = 0;
            while (b < DLTAB)
            {
                dlBucket[b] = -1;
                b += 1;
            }
            dlIndexed = 0;
        }
        while (dlIndexed < dlN)
        {
            int hh = Utf8.utf8Hash2(dlTab[dlIndexed].blob, dlTab[dlIndexed].classOff,
                               dlTab[dlIndexed].blob, dlTab[dlIndexed].nameOff) & (DLTAB - 1);
            dlNext[dlIndexed] = dlBucket[hh];
            dlBucket[hh] = dlIndexed;
            dlIndexed += 1;
        }
    }

    static long dlCellOf(Address clsBase, int clsOff, Address nameBase, int nameOff, Address descBase, int descOff)
    {
        buildDlIndex();
        // The SAME predicate the scan used -- the index only narrows what it is applied to, so a hit is the
        // same cell the scan would have found and a miss still answers 0.
        //
        // CLASS+NAME, not name alone. This is the tier every `<init>` short-circuits into (a constructor is
        // never inherited, so it skips the super-chain walk and lands here), and keyed on the name a bucket
        // held one cell per class declaring that name -- so the `<init>` chain was as long as the loaded
        // world. The identical defect the registry index had one tier up, still in place below it:
        // MEASURED at 259k chain steps on a launcher boot, the largest term left in `lookT` once that was
        // fixed. Any two cells matching this predicate share a class AND a name, so they stay in the same
        // bucket under either key and head-insertion order is unchanged -- the same cell still wins.
        //
        // AND OF THIS LOADER GENERATION. The table outlives a reset that does not rewind (the first one after
        // reclaim is armed only takes the watermark), so it can still hold cells for bodies compiled against
        // the registry that reset discarded -- with that registry's TIBs baked into them as immediates. Found
        // BY NAME, such a cell links a new caller into the old world, and the class is then never loaded
        // into the new one: stock OutputStreamWriter.<init> reached the previous generation's
        // StreamEncoder.forOutputStreamWriter, whose StreamEncoders belonged to a class the current registry
        // had never registered (DISPATCH ON UNREGISTERED TYPE at the next late-resolved call). A stale cell is
        // skipped, so the caller resolves as for an unloaded class and pulls it; anything ALREADY holding the
        // cell's address keeps working, because the cell itself is untouched.
        int hd = Utf8.utf8Hash2(clsBase, clsOff, nameBase, nameOff) & (DLTAB - 1);
        int k = dlBucket[hd];
        Loader.dlLastProbe = ((long) hd << 32) | (k & 0xFFFFFFFFL);   // for rcGate
        while (k >= 0)
        {
            Loader.dlSteps += 1;
            if (dlTab[k].gen == Loader.loaderGen
                    && Loader.utf8EqAt(clsBase, clsOff, dlTab[k].blob, dlTab[k].classOff)
                    && Loader.utf8EqAt(nameBase, nameOff, dlTab[k].blob, dlTab[k].nameOff)
                    && Loader.utf8EqAt(descBase, descOff, dlTab[k].blob, dlTab[k].descOff))
            {
                return dlTab[k].cell;
            }
            k = dlNext[k];
        }
        return 0L;
    }

    /** Current contents of the phase-A cell for a method ref given as blob base + Utf8 offsets, or 0. */
    /**
     * Whether the class whose blob is at {@code base} DECLARES a method named and typed like the Utf8 runs at
     * {@code (nameBase, nameOff)} / {@code (descBase, descOff)}: 1 yes, 0 no, -1 unknown. Read from the blob's
     * CACHED constant-pool parse ({@code pcBase}/{@code pcCp}) so the global parse context is untouched --
     * this is asked mid-patch, with the caller's blob current. A blob never parsed answers -1, and the
     * callers then keep their previous behaviour rather than guess.
     */
    private static int blobDeclares(Address base, Address nameBase, int nameOff, Address descBase, int descOff)
    {
        int n = Loader.pcN;
        int ci = 0;
        while (ci < n && Loader.pcBase.get(ci).NE(base))
        {
            ci += 1;
        }
        if (ci >= n)
        {
            return -1;
        }
        Magic.dsb();                                    // the count was read before the entry (parseConstPool)
        int[] cp = Loader.pcCp[ci];
        Address p = base.plus(Loader.pcAfterCp[ci]);
        p = p.plus(6);                                         // access_flags, this_class, super_class
        p = p.plus(2 + u2(p) * 2);                             // interfaces
        int fcount = u2(p);
        p = p.plus(2);
        int f = 0;
        while (f < fcount)
        {
            p = Loader.skipAttributes(p.plus(8), u2(p.plus(6)));
            f += 1;
        }
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            if (Loader.utf8EqAt(base, cp[u2(p.plus(2))], nameBase, nameOff) && Loader.utf8EqAt(base, cp[u2(p.plus(4))], descBase, descOff))
            {
                return 1;
            }
            p = Loader.skipAttributes(p.plus(8), u2(p.plus(6)));
            m += 1;
        }
        return 0;
    }

    /**
     * The callable contents of the phase-A cell for {@code name+desc} DECLARED by the class named at
     * {@code (clsBase, clsOff)}, or 0 if that class declares no such method (or has no cell table yet). Unlike
     * {@link #dlStubByRef} the class and the name/descriptor may live in different blobs -- the super-chain walk
     * names an ancestor out of its own blob while the method still comes from the call site's.
     */
    private static long cellStubAt(Address clsBase, int clsOff, Address refBase, int nameOff, int descOff)
    {
        if (dlTab == null)
        {
            return 0L;
        }
        long cell = dlCellOf(clsBase, clsOff, refBase, nameOff, refBase, descOff);
        return cell == 0L ? 0L : Address.fromLong(cell).loadLong();
    }

    private static long dlStubByRef(Address refBase, int classOff, int nameOff, int descOff)
    {
        if (dlTab == null)
        {
            return 0L;
        }
        long cell = dlCellOf(refBase, classOff, refBase, nameOff, refBase, descOff);
        return cell == 0L ? 0L : Address.fromLong(cell).loadLong();
    }

    /** Static-slot address for a field ref given as blob base + Utf8 offsets, or 0. */
    static long globalStaticByRef(Address refBase, int classOff, int nameOff)
    {
        Loader.psSites += 1;
        long addr = Statics.sgCellOf(refBase, classOff, nameOff);
        if (addr == 0L)
        {
            addr = Statics.inheritedStaticCell(refBase, classOff, refBase, nameOff, 0);
        }
        if (addr == 0L)
        {
            Loader.psMiss += 1;
        }
        return addr;
    }

    /**
     * The class an active use of {@code refCp} must INITIALIZE (JVMS 5.5). For a {@code getstatic}/{@code putstatic}
     * that is the class that DECLARES the resolved field -- not the class the reference names. They differ for an
     * inherited static: {@code getstatic Impl.NAMES}, where interface {@code Holder} declares NAMES. Initializing
     * Impl initializes its superCLASSES but never its superinterfaces, so Holder.<clinit> never ran and the
     * (correctly resolved) cell read null. Any other reference -- and a field not resolvable yet -- keeps the
     * named class.
     *
     * <p>STATED LIMIT: an inherited static METHOD ({@code invokestatic Sub.m()}, declared by Base) still guards
     * the named class, which initializes Sub as well as Base -- an over-initialization, not a missed one.
     */
    static Address initTargetNameAddr(int refCp)
    {
        Address named = Loader.refClassNameAddr(refCp);
        if (named.isZero() || Loader.gcpTag[refCp] != 9) // 9 = CONSTANT_Fieldref
        {
            return named;
        }
        int classOff = Loader.refClassNameOff(refCp);
        int nameOff = Loader.mrefNameOff(refCp);
        if (Statics.sgIndexOf(Loader.gbase, classOff, Loader.gbase, nameOff) >= 0)
        {
            return named;                               // declared by the named class itself
        }
        int k = Statics.inheritedStaticIndex(Loader.gbase, classOff, Loader.gbase, nameOff, 0);
        return k < 0 ? named : Statics.sgTab[k].base.plus((long) Statics.sgTab[k].classOff);
    }

    /**
     * The stub every unresolved-but-not-denylisted call site is patched to, deduped by callee identity so N
     * sites calling the same method share one. Returns 0 if the table is full, and the caller falls back to
     * the trap.
     */
    static long linkStubFor(Address clsU, Address nameU, Address descU)
    {
        // A LINEAR SCAN, and deliberately left one. It was indexed during the `patch` arc on the theory that
        // this was hot; MEASURED, the index bought NOTHING, and the cost turned out to be `dlCellOf` two
        // tiers away. This path is the `unres` arm, 541ms cumulative of an 11s callT on a launcher boot --
        // so the scan is not worth the table it would take to avoid. Revisit only if a measurement puts time
        // here.
        int k = 0;
        while (k < lkCount)
        {
            Loader.pcStubSteps += 1;
            if (Loader.utf8EqAt(lkClsU.get(k), 0, clsU, 0)
                    && Loader.utf8EqAt(lkNameU.get(k), 0, nameU, 0)
                    && Loader.utf8EqAt(lkDescU.get(k), 0, descU, 0))
            {
                return lkStub[k];
            }
            k += 1;
        }
        if (lkCount >= MAXLINKSTUB)
        {
            // SAY SO. Returning 0 here leaves patchRelocsFrom's target as denylistTrap, and the runtime then
            // reports `denied callee: <class>.<method>` for a class on NO denylist -- the exact
            // misattribution that has cost this project several debugging sessions. `java/util/LinkedHashSet
            // .<init>`, reached from picocli's CommandSpec.aliases, was one: shipped, allowed, and trapped.
            linkStubFull(clsU, nameU, descU);
            return 0L;
        }
        if (linkTrampAddr == 0L)
        {
            buildLinkTramp();
        }
        lkClsU.set(lkCount, clsU);
        lkNameU.set(lkCount, nameU);
        lkDescU.set(lkCount, descU);
        lkMemo[lkCount] = 0L;
        lkStub[lkCount] = buildLinkStub(lkCount).toLong();
        lkCount += 1;
        return lkStub[lkCount - 1];
    }

    /** Report the link-stub table overflowing, once -- the site it could not serve becomes a denylist trap. */
    private static void linkStubFull(Address clsU, Address nameU, Address descU)
    {
        if (lkFullReported)
        {
            return;
        }
        lkFullReported = true;
        Uart.write(Magic.bytes("\n  LINK STUB TABLE FULL at "));
        VM.printDec(MAXLINKSTUB);
        Uart.write(Magic.bytes(" -- this site becomes a DENYLIST TRAP for a class that is NOT denied: "));
        Loader.printUtf8Capped(clsU);
        Uart.putc(0x2E);
        Loader.printUtf8Capped(nameU);
        Uart.putc(0x20);
        Loader.printUtf8Capped(descU);
        Uart.putc(0x0A);
    }

    /** x17 = stub index, then jump to the shared link trampoline. Same shape as the lazy deferral stub, and
     *  x16/x17 for the same reason: x0.. are the call's arguments and must survive untouched. */
    private static Address buildLinkStub(int idx)
    {
        Address buf = Heap.allocCode(32);
        Heap.pinCodeAt(buf);                                 // only a patched `bl` displacement names it
        int w = 0;
        // A 0 here bakes `br 0` into the stub, and `br` writes no x30 -- so the failure would
        // surface as an untraceable reboot whenever this stub is finally entered. Fail now.
        VirtualDispatch.checkTramp(Address.fromLong(linkTrampAddr), idx, 2);
        buf.plus(w * 4L).store(A64Enc.movz(17, idx & 0xFFFF, 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (linkTrampAddr & 0xFFFF), 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((linkTrampAddr >> 16) & 0xFFFF), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((linkTrampAddr >> 32) & 0xFFFF), 2));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.br(16));
        w += 1;
        Heap.publishCode(buf, buf.plus(w * 4L));
        return buf;
    }

    /**
     * The shared arg-preserving trampoline for link stubs — the twin of {@link LazyCompiler#buildLazyTramp}, resolving
     * through {@code resolveLinkStub} instead of compiling. Preserving x0..x15 matters for the same reason it
     * does there: a whole demand-load runs between entry and the tail-branch, so every argument register the
     * callee will read must survive it. Restoring LR before the branch is what keeps the callee's return —
     * and, when resolution fails and the target is denylistTrap, what keeps that trap's x30-keyed trapwire
     * lookup and stack walk reading exactly as they do for a direct call.
     */
    private static void buildLinkTramp()
    {
        Address buf = Heap.allocCode(256);
        long ra = VM.resolveLinkStubAddr;
        int w = 0;
        buf.plus(w * 4L).store(A64Enc.subImm(StubFrames.STUB_SP, StubFrames.STUB_SP, StubFrames.STUB_FRAME)); // sub sp,sp,#144
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
        buf.plus(w * 4L).store(A64Enc.movReg(0, 17)); // x0 = idx (the stub put it in x17)
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
        // is on it (link-stub trampoline).
        w = VirtualDispatch.emitTailGuard(buf, w, 2);
        r = 0;
        while (r <= StubFrames.STUB_SAVE_HI)
        {
            buf.plus(w * 4L).store(A64Enc.ldrx(r, StubFrames.STUB_SP, StubFrames.STUB_SAVE0 + r * ObjectModel.WORD));
            w += 1;
            r += 1;
        }
        buf.plus(w * 4L).store(A64Enc.ldrx(StubFrames.STUB_LR, StubFrames.STUB_SP, StubFrames.STUB_LR_OFF)); // (see registerTrampFrames)
        w += 1;
        Address lo = buf.plus(4L);
        Address hi = buf.plus(w * 4L);
        buf.plus(w * 4L).store(A64Enc.addImm(StubFrames.STUB_SP, StubFrames.STUB_SP, StubFrames.STUB_FRAME)); // add sp,sp,#144
        w += 1;
        buf.plus(w * 4L).store(A64Enc.br(16)); // tail-call the resolved method
        w += 1;
        Heap.publishCode(buf, buf.plus(w * 4L));
        linkTrampAddr = buf.toLong();
        // Registered here only: linkTrampAddr is cleared by resetLoader, so this runs again every batch --
        // unlike the lazy/virtual trampolines, which outlive the table and are re-registered by resetLoader.
        VM.addJitFrame(lo, hi, StubFrames.STUB_FRAME, 0L);
    }

    /**
     * A link stub fired: the site really is reached, so resolve it now. Memoized, so the demand-load happens
     * once however many times the site is called. Returns {@code VM.denylistTrapAddr} when the callee cannot
     * be resolved after all — the site then behaves exactly as it did before this path existed, trap message
     * and backtrace included. Called only from the trampoline (via the stashed address).
     */
    static long resolveLinkStub(int idx)
    {
        if (idx < 0 || idx >= lkCount)
        {
            return VM.denylistTrapAddr;                 // dead force-reference (writer) / bad index
        }
        if (lkMemo[idx] != 0L)
        {
            return lkMemo[idx];
        }
        VM.loaderLock(VM.LOCK_DEMAND_LOAD);                                // demand-loads a class: one compiler at a time
        long buf;
        try
        {
        lnkFailWhy = 0;
        buf = resolveLinkTarget(lkClsU.get(idx), lkNameU.get(idx),
                lkDescU.get(idx));
        if (buf == 0L)
        {
            // JVMS 5.4.3.3 searches C, then C's SUPERCLASSES, then its maximally-specific SUPERINTERFACES.
            // The tiers inside resolveLinkTarget cover the first two -- bufBySigU's third tier resolves
            // through the class's FLATTENED vtable, which carries inherited methods -- but they cannot cover
            // the third, because a DEFAULT method has no vtable slot in any class: a class-typed receiver
            // reaches a default THROUGH THE ITABLE precisely because there is no slot for it.
            //
            // `super.cleanUp(context)` in JUnit's TestMethodTestDescriptor is exactly that shape. It is an
            // invokespecial naming MethodBasedTestDescriptor, which does NOT declare cleanUp; the method is a
            // `public default` on Node, implemented two classes up by JupiterTestDescriptor. Every tier
            // answered 0 and the call trapped as `LINK FAILED ... class OK but no body for that
            // name+descriptor` -- accurate about the CLASS and silent about where the body really lives.
            //
            // resolveViaInterfaces already performs this search for the late-VIRTUAL path (it walks the
            // superclass chain collecting interfaces, then their super-interfaces). Reusing it is what keeps
            // the two paths agreeing; a second walk over the same relation is the trap this file has paid for
            // repeatedly. It cannot be called from INSIDE resolveLinkTarget -- it calls resolveLinkTarget per
            // interface, so that would recurse -- which is why it belongs here, at the terminal caller.
            int ireg = regBySigU(lkClsU.get(idx));
            VirtualDispatch.viHops = 0;
            VirtualDispatch.viIfaceN = -1;                              // -1 distinguishes "tier skipped" from "walk found none"
            // STEP 2 OF JVMS 5.4.3.3: C's SUPERCLASSES, most-derived first. This is where the body usually is,
            // and it was the step missing here -- I added step 3 (superinterfaces) first on a reading that
            // `javap -s` then disproved. JUnit's `super.cleanUp(context)` is an invokespecial naming
            // MethodBasedTestDescriptor, which declares no cleanUp; JupiterTestDescriptor, its SUPERCLASS,
            // declares `cleanUp(JupiterEngineExecutionContext)V` with exactly the call site's descriptor.
            // Node.cleanUp is a red herring: being generic it erases to (EngineExecutionContext)V, a
            // DIFFERENT descriptor, so no interface search could ever have matched.
            //
            // resolveLinkTarget answers for ONE class -- bufBySigU, nativeBufAt and compileSigOnDemand all
            // work off that class's own blob and registry rows -- so an inherited method resolves nowhere.
            // virtualResolve already walks the chain this way for the late-VIRTUAL path; the link stub, which
            // is what an invokespecial/invokestatic site gets, never had one. Walking most-derived-first IS
            // what inheritance means.
            int c = ireg >= 0 ? ClassRegistry.superRegOf(ireg) : -1;
            int hops = 0;
            while (buf == 0L && c >= 0 && hops < VirtualDispatch.MAXIFACECHAIN)
            {
                int why = lnkFailWhy;
                buf = resolveLinkTarget(ClassRegistry.clTab[c].base.plus(ClassRegistry.clTab[c].nameOff),
                        lkNameU.get(idx), lkDescU.get(idx));
                if (buf == 0L)
                {
                    lnkFailWhy = why;                   // a miss at one level is "try the super", not a verdict
                }
                c = ClassRegistry.superRegOf(c);
                hops += 1;
            }
            if (buf == 0L && ireg >= 0)
            {
                int why = lnkFailWhy;                   // the CLASS's reason; the walk below overwrites it
                buf = VirtualDispatch.resolveViaInterfaces(ireg, lkNameU.get(idx), lkDescU.get(idx));
                if (buf == 0L)
                {
                    lnkFailWhy = why;                   // report why the CLASS failed, not the last interface
                }
            }
            if (buf == 0L)
            {
                // WHAT THE WALK ACTUALLY SAW. Two fixes for this failure were shipped on plausible readings
                // and neither moved it, which is this project's recorded way of saying the reading was never
                // checked. `ireg < 0` means the tier never ran (the callee class is not in the registry under
                // that name); `ifaces=0` means the chain walk collected nothing; a list that omits the
                // expected interface means the chain stopped before reaching the class that declares it.
                // Off-cost: this runs only where the call is already about to trap.
                reportIfaceWalk(ireg);
            }
        }
        if (buf == 0L)
        {
            // TERMINAL: this stub has no other tier to try, so the call really is about to trap. (The chain
            // walkers that also call resolveLinkTarget report through VIRTUALRESOLVE FAILED when THEIR whole
            // walk is over -- not per level.)
            //
            // A NATIVE THIS VM DOES NOT IMPLEMENT is checked first. CLAUDE.md rule 3: it THROWS, naming itself,
            // instead of halting the task on the trap -- which also reported it as a DENYLIST TRAP for a class
            // that is not denied (Net.setIntOption0, 2026-10-29). The throw makes it catchable and turns the
            // missing native into a worklist entry the program writes; the UnsatisfiedLinkError IS the report,
            // so nothing is printed.
            //
            // The named class can only INHERIT the native -- javac emits `invokestatic Sub.missing` for a call
            // through a subclass -- so the declaring class is searched up the chain, as virtualResolve does
            // (NativeMissingProbe "inherited static"; before this the call halted on LINK FAILED).
            long thunk = 0L;
            if (lnkFailWhy == 3)
            {
                int nreg = regBySigU(lkClsU.get(idx));
                if (nreg >= 0)
                {
                    thunk = NativeMethods.missingNativeOnChain(nreg, lkNameU.get(idx),
                            lkDescU.get(idx));
                }
                else if (NativeMethods.declaredNative(lkClsU.get(idx), lkNameU.get(idx),
                        lkDescU.get(idx)))
                {
                    thunk = NativeMethods.missingNativeThunk(lkClsU.get(idx), lkNameU.get(idx),
                            lkDescU.get(idx));
                }
            }
            if (thunk != 0L)
            {
                buf = thunk;
            }
            else if (lnkFailWhy == 1)
            {
                reportLinkFail(lkClsU.get(idx), lkNameU.get(idx), lkDescU.get(idx),
                        Magic.bytes("class not in the classDir (nothing can load it)"));
            }
            else if (lnkFailWhy == 2)
            {
                reportLinkFail(lkClsU.get(idx), lkNameU.get(idx), lkDescU.get(idx),
                        Magic.bytes("class loaded but NOT INSTANTIATED (half lifecycle)"));
            }
            else
            {
                reportLinkFail(lkClsU.get(idx), lkNameU.get(idx), lkDescU.get(idx),
                        Magic.bytes("class OK but no body for that name+descriptor"));
            }
            if (thunk == 0L)
            {
                buf = VM.denylistTrapAddr;
            }
        }
        lkMemo[idx] = buf;
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
        return buf;
    }

    /** Whether class {@code clsU} itself declares a method {@code name desc} -- any descriptor when {@code descU}
     *  is 0, and only a NATIVE one when {@code nativeOnly}. The classfile walk {@link NativeMethods#declaredNative} describes. */
    static boolean declaresMethod(Address clsU, Address nameU, Address descU, boolean nativeOnly)
    {
        int pd = Loader.findPdByName(clsU, 0);
        if (pd < 0)
        {
            return false;
        }
        Address base = Loader.pdBase.get(pd);
        int n = u2(base.plus(8L));                                // constant_pool_count
        int[] off = new int[n];
        Address p = base.plus(10L);
        int i = 1;
        while (i < n)
        {
            int tag = u1(p);
            off[i] = p.diff(base).toInt() + 1;                   // a Utf8's offset is its u2 length
            if (tag == 1)
            {
                p = p.plus(3L + u2(p.plus(1L)));
            }
            else if (tag == 5 || tag == 6)
            {
                p = p.plus(9L);
                i += 1;                                        // long/double take two slots
            }
            else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20)
            {
                p = p.plus(3L);
            }
            else if (tag == 15)
            {
                p = p.plus(4L);
            }
            else if (tag == 3 || tag == 4 || tag == 9 || tag == 10 || tag == 11 || tag == 12 || tag == 17
                    || tag == 18)
            {
                p = p.plus(5L);
            }
            else
            {
                return false;                                  // not a classfile we can read: keep the trap
            }
            i += 1;
        }
        p = p.plus(6L);                                               // access_flags, this_class, super_class
        p = p.plus(2L + u2(p) * 2L);                                  // interfaces
        int fcount = u2(p);
        p = p.plus(2L);
        int f = 0;
        while (f < fcount)
        {
            p = skipMemberAttrs(p.plus(8L), u2(p.plus(6L)));
            f += 1;
        }
        int mcount = u2(p);
        p = p.plus(2L);
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            if ((!nativeOnly || (access & 0x0100) != 0) && Utf8.utf8BlobSame(base, off[u2(p.plus(2L))], nameU)
                    && (descU.isZero() || Utf8.utf8BlobSame(base, off[u2(p.plus(4L))], descU)))
            {
                return true;
            }
            p = skipMemberAttrs(p.plus(8L), u2(p.plus(6L)));
            m += 1;
        }
        return false;
    }

    /** Skip {@code count} attributes starting at {@code p} (each u2 name, u4 length, then the bytes). */
    private static Address skipMemberAttrs(Address p, int count)
    {
        int a = 0;
        while (a < count)
        {
            long alen = ((long) u2(p.plus(2L)) << 16) | u2(p.plus(4L));
            p = p.plus(6L + alen);
            a += 1;
        }
        return p;
    }

    /** Name what the interface tier saw for a link stub that is about to trap -- see its call site. */
    private static void reportIfaceWalk(int ireg)
    {
        Uart.write(Magic.bytes("\n  iface tier: ireg="));
        VM.printDec(ireg);
        Uart.write(Magic.bytes(" chainHops="));
        VM.printDec(VirtualDispatch.viHops);
        Uart.write(Magic.bytes(" ifaces="));
        VM.printDec(VirtualDispatch.viIfaceN);
        int k = 0;
        while (k < VirtualDispatch.viIfaceN && k < 12 && VirtualDispatch.viNames != null)
        {
            Uart.write(Magic.bytes("\n    tried "));
            Loader.printNameAt(VirtualDispatch.viNames.get(k), 0);
            k += 1;
        }
        Uart.putc(0x0A);
    }

    private static void reportLinkFail(Address clsU, Address nameU, Address descU, byte[] why)
    {
        long key = clsU.plus((long) u2(nameU)).toLong();
        if (lnkFailSeen == null)
        {
            lnkFailSeen = new long[64];
            lnkFailSeenN = 0;
        }
        int k = 0;
        while (k < lnkFailSeenN)
        {
            if (lnkFailSeen[k] == key)
            {
                return;
            }
            k += 1;
        }
        if (lnkFailSeenN < 64)
        {
            lnkFailSeen[lnkFailSeenN] = key;
            lnkFailSeenN += 1;
        }
        Uart.write(Magic.bytes("\n  LINK FAILED: "));
        Loader.printNameAt(clsU, 0);
        Uart.putc(0x2E);
        Loader.printNameAt(nameU, 0);
        if (!descU.isZero())
        {
            Loader.printNameAt(descU, 0);                      // WHICH overload -- "no body for that descriptor"
        }                                               //   is unactionable without saying which descriptor
        Uart.write(Magic.bytes(" -- "));
        Uart.write(why);
        Uart.putc(0x0A);
    }

    /**
     * Demand-load the callee's class and find its callable buffer — the same three-tier lookup
     * {@link #resolveBakeStub} uses (registered body, celled static, TIB slot), plus the provided-native
     * fallback. Returns 0 rather than halting: an unresolvable callee here is a legitimate outcome (the class
     * is genuinely absent), and the caller turns it back into the denylist trap.
     */
    static long resolveLinkTarget(Address clsU, Address nameU, Address descU)
    {
        if (ClassRegistry.clTab == null)
        {
            return 0L;
        }
        if (Loader.LOAD_LOG)
        {
            Uart.write(Magic.bytes("  linkresolve "));
            Loader.printNameAt(clsU, 0);
            Uart.putc(0x2E);
            Loader.printNameAt(nameU, 0);
            Uart.putc(0x0A);
        }
        // VALIDATE BEFORE RENDERING ANYTHING AS TEXT. A stub firing with a bad clsU gives a garbage length,
        // and every attempt to name the subject from it -- printNameAt, or even `new byte[len]` filled and
        // written out -- streams the stub table onto the console and buries the failure. A class name is
        // never 512 bytes; a longer one means the POINTER is wrong, which is a different bug from a class
        // that will not load, and only hex can say so safely.
        int len = u2(clsU);
        // Length AND content. 512 was still far too generous: a garbage entry whose length happened to be a
        // few hundred sailed through and printed a few hundred bytes of the stub table. A class name is short
        // and entirely printable ASCII, so anything else means the POINTER is wrong -- a different bug from a
        // class that will not load, and one that must be reported in hex rather than rendered.
        boolean sane = len > 0 && len <= 255;
        int c = 0;
        while (sane && c < len)
        {
            int b = u1(clsU.plus(2 + c));
            if (b < 0x20 || b > 0x7E)
            {
                sane = false;
            }
            c += 1;
        }
        if (!sane)
        {
            Uart.write(Magic.bytes("\nBAD BAKE STUB ENTRY (pointer, not a missing class)\n  clsU="));
            VM.printHex(clsU.toLong());
            Uart.write(Magic.bytes(" nameU="));
            VM.printHex(nameU.toLong());
            Uart.write(Magic.bytes(" descU="));
            VM.printHex(descU.toLong());
            Uart.write(Magic.bytes("\n  u2(clsU)="));
            VM.printHex((long) len);
            Uart.write(Magic.bytes(" table="));
            VM.printHex(VM.bakeStubTable);
            Uart.write(Magic.bytes(" count="));
            VM.printHex(VM.bakeStubCount);
            Uart.putc(0x0A);
            Loader.capHalt(Magic.bytes("bakestub-entry"), len);
        }
        // The name is already a Utf8 run at clsU: ask the index directly, and build the byte[] copy only when
        // the class has to be LOADED -- this runs per execution of an unmemoised site.
        int reg = regBySigU(clsU);
        if (reg < 0)
        {
            byte[] slash = new byte[len];
            int i = 0;
            while (i < len)
            {
                slash[i] = (byte) u1(clsU.plus(2 + i));
                i += 1;
            }
            if (Loader.loadClassIncremental(slash).isZero())
            {
                lnkFailWhy = 1;                         // RECORD, do not print: see reportLinkFail
                Loader.linkResolveLog(clsU, nameU, descU, 0L, 0);
                return 0L;
            }
            reg = regBySigU(clsU);
        }
        if (reg >= 0 && ClassRegistry.clTab[reg].state < RVMClass.ST_INSTANTIATED)
        {
            // Loaded but not brought all the way up: its <clinit> was blocked or its structure never
            // registered. Branching in would run against half-built statics and a possibly empty vtable.
            lnkFailWhy = 2;
            Loader.linkResolveLog(clsU, nameU, descU, 0L, 0);
            return 0L;
        }
        long buf = bufBySigU(clsU, nameU, descU);
        int tier = 1;
        if (buf == 0L)
        {
            buf = Loader.nativeBufAt(clsU, 0, nameU, 0);       // a PROVIDED NATIVE has no bytecode to find
            tier = 2;
        }
        if (buf == 0L)
        {
            buf = LazyCompiler.compileSigOnDemand(clsU, nameU, descU);   // never compiled AND not dispatchable: JIT it now
            tier = 3;
        }
        if (buf == 0L && reg >= 0)
        {
            buf = inheritedLinkTarget(reg, nameU, descU);  // the named class only INHERITS it
            tier = 4;
        }
        if (buf != 0L && !plausibleCode(Address.fromLong(buf)))
        {
            // THE LATE PATHS WERE THE ONE UNGUARDED DISPATCH. Every inline call site gets
            // Baseline.dispatchTargetGuard, but a late-resolved target is TAIL-BRANCHED to by the trampoline
            // with nothing checking it -- so a tier that answers with a non-code word (tier 1 reads a static
            // cell's CONTENTS and a TIB slot; either can hold a stale or out-of-range value) branches straight
            // into whatever that word is. Read as an address, two adjacent instruction words look exactly like
            // one: that is how this surfaced, as a bare instruction abort at a pc no table claims, with the
            // caller nowhere in the report. Trap it by name instead.
            reportBadTarget(clsU, nameU, descU, buf, tier);
            return 0L;                                  // -> the caller substitutes denylistTrap
        }
        if (buf == 0L)
        {
            // Class is present and fully built, yet no tier produced a body: not registered, not a provided
            // native, and compileSigOnDemand found no Code for this exact name+descriptor. Usually an
            // abstract/native method with no VM helper, or a descriptor mismatch.
            lnkFailWhy = 3;
        }
        Loader.linkResolveLog(clsU, nameU, descU, buf, tier);
        return buf;
    }

    /**
     * A link-stub target the named class INHERITS: the first superclass that DECLARES name+descriptor (JVMS
     * 5.4.3.3), through the same three tiers {@link #resolveLinkTarget} tries on the named class. globalBufByRef
     * hands a site here when an ancestor declares the method but has not compiled it; the tiers above ask only
     * about the named class, so without this such a site could not resolve at all. Stops at the first
     * declaring class whatever it answers -- climbing past one is the silent mis-binding this exists to avoid.
     */
    private static long inheritedLinkTarget(int reg, Address nameU, Address descU)
    {
        int sreg = ClassRegistry.superRegOf(reg);
        int guard = 0;
        while (sreg >= 0 && guard < 64)
        {
            Address sU = ClassRegistry.clTab[sreg].base.plus(ClassRegistry.clTab[sreg].nameOff);
            long b = bufBySigU(sU, nameU, descU);
            if (b == 0L)
            {
                b = Loader.nativeBufAt(sU, 0, nameU, 0);
            }
            if (b == 0L)
            {
                b = LazyCompiler.compileSigOnDemand(sU, nameU, descU);
            }
            if (b != 0L)
            {
                return b;
            }
            if (blobDeclares(ClassRegistry.clTab[sreg].base, nameU, 0, descU, 0) == 1)
            {
                return 0L;                              // declared here and still not callable: report, do not climb
            }
            sreg = ClassRegistry.superRegOf(sreg);
            guard += 1;
        }
        return 0L;
    }

    /**
     * A callable target must be 4-aligned and below the code ceiling -- exactly the test the JIT's inline
     * {@link compiler.Baseline#dispatchTargetGuard} applies, which the late-resolution paths never had.
     *
     * <p>The range is IMAGE **and** code arena, not the arena alone: a lazily compiled body routinely links to
     * a BAKED java.base body, which lives in the image below {@code Heap.CODE_BASE}. Testing for the arena
     * only rejected `java/lang/String.getBytes` on its first real use -- an instrument that broke the boot it
     * was added to diagnose.
     */
    static boolean plausibleCode(Address buf)
    {
        return buf.toLong() > 0x1000L && (buf.toLong() >> 24) <= (long) Symbols.CODE_TOP_BYTE_MAX && (buf.toLong() & 3L) == 0L;
    }

    /** Name a late-resolved target that is not code, and which of the tiers produced it. */
    static void reportBadTarget(Address clsU, Address nameU, Address descU, long buf, int tier)
    {
        Uart.write(Magic.bytes("\n  BAD LINK TARGET tier="));
        VM.printDec(tier);
        Uart.write(Magic.bytes(" buf="));
        VM.printHex(buf);
        Uart.write(Magic.bytes(" for "));
        Loader.printNameAt(clsU, 0);
        Uart.putc(0x2E);
        Loader.printNameAt(nameU, 0);
        Loader.printNameAt(descU, 0);
        Uart.putc(0x0A);
    }

    /**
     * M8 object links: resolve a called bake stub — the writer couldn't compile this method, so
     * demand-load its class into the running program (the loaded world shares the baked world's
     * statics, Types, vtable numbering and itables, so the lazily-compiled code and the baked code
     * agree on everything) and return a callable buffer for the method. The three utf8 args are
     * absolute {u2 len}{bytes} runs from the stub table. Halts loudly if the class isn't embedded
     * or the method can't be found — a stub fired that the system genuinely cannot satisfy.
     */
    static long resolveBakeStub(Address clsU, Address nameU, Address descU)
    {
        if (ClassRegistry.clTab == null)
        {
            // Name the stub: which method fired is the whole diagnosis, and the halt alone does not say.
            Uart.write(Magic.bytes("\n  bake stub fired before the loader exists: "));
            Loader.printNameAt(clsU, 0);
            Uart.putc(0x2E);
            Loader.printNameAt(nameU, 0);
            Loader.printNameAt(descU, 0);
            Loader.capHalt(Magic.bytes("bakeresolve-early"), 0);   // a stub fired before the loader exists
        }
        if (Loader.LOAD_LOG)
        {
            Uart.write(Magic.bytes("  bakeresolve "));
            Loader.printNameAt(clsU, 0);
            Uart.putc(0x2E);
            Loader.printNameAt(nameU, 0);
            Uart.putc(0x0A);
        }
        int len = u2(clsU);
        byte[] slash = new byte[len];
        int i = 0;
        while (i < len)
        {
            slash[i] = (byte) u1(clsU.plus(2 + i));
            i += 1;
        }
        if (ClassRegistry.classIndexByName(slash) < 0)
        {
            if (Loader.loadClassIncremental(slash).isZero())
            {
                // NAME THE SUBJECT. `bakeresolve-load count=0` says only that some baked stub wanted a class
                // that would not load -- true and unactionable, and the count is not even a count.
                //
                // Printed from `slash`, the byte[] copied out BEFORE the load, not from the stub-table
                // pointers. `printNameAt(clsU, 0)` is the right idiom at the TOP of this method and the wrong
                // one here: loadClassIncremental has just run, and reading the length word afterwards gave a
                // huge value that walked hundreds of bytes of the stub table onto the console -- an instrument
                // that buried the failure it was added to name.
                Uart.write(Magic.bytes("\n  BAKERESOLVE CANNOT LOAD: "));
                Uart.write(slash);
                Uart.putc(0x0A);
                Loader.printWhyUnpulled(clsU);
                Loader.capHalt(Magic.bytes("bakeresolve-load"), 0);
            }
        }
        int lreg = ClassRegistry.classIndexByName(slash);
        if (lreg >= 0 && ClassRegistry.clTab[lreg].state < RVMClass.ST_INSTANTIATED)
        {
            // Lifecycle guard: baked code is about to CALL into this class, so its bodies/cells and
            // TIB must be in place (phase B). A short state here means the incremental load pipeline
            // returned a half-lifecycle class -- halt loudly rather than branch into a 0 buffer.
            Loader.capHalt(Magic.bytes("lifecycle-resolve"), lreg);
        }
        long buf = bufBySigU(clsU, nameU, descU);
        if (buf == 0L)
        {
            buf = Loader.nativeBufAt(clsU, 0, nameU, 0);       // a PROVIDED NATIVE has no bytecode to find: link the VM helper
        }
        if (buf == 0L)
        {
            // THE SAME FOURTH TIER THE LINK-STUB PATH HAS. bufBySigU answers only through a DISPATCH table --
            // a registered buffer, a static cell, or a TIB slot -- so a method RTA pruned (never compiled and
            // never given a slot) is invisible to all three even though its bytecode is right there in the
            // class's blob. resolveLinkTarget compiles it on demand for exactly this case; the bake path
            // halted instead.
            //
            // `java/lang/String.toUpperCase(Ljava/util/Locale;)` is the shape: a baked java.base body calls
            // it, nothing statically reachable does, so it has no buffer and no slot -- and the bake stub had
            // nowhere left to look.
            buf = LazyCompiler.compileSigOnDemand(clsU, nameU, descU);
        }
        if (buf == 0L)
        {
            // Class from `slash` (copied before the load); name and descriptor through the CAPPED printer,
            // since these pointers are read after loadClassIncremental has run.
            Uart.write(Magic.bytes("\n  BAKERESOLVE NO BODY: "));
            Uart.write(slash);
            Uart.putc(0x2E);
            Loader.printUtf8Capped(nameU);
            Uart.putc(0x20);
            Loader.printUtf8Capped(descU);
            Uart.putc(0x0A);
            Loader.capHalt(Magic.bytes("bakeresolve-find"), 0);
        }
        return buf;
    }

    /** A callable buffer for (class, name, desc) given as absolute utf8 runs: a registered compiled
     *  buffer, else a phase-A static cell's stub, else the class's TIB slot (virtual lazy stub) — any
     *  of which self-compiles on entry if still lazy. 0 if unknown. */
    static long bufBySigU(Address clsU, Address nameU, Address descU)
    {
        // The registry through its class+name index (rgBucket). The scan answered the LOWEST index with a
        // buffer, and those chains are newest-first, so walk the whole chain and keep the oldest hit.
        MethodRegistry.buildRegIndex();
        int best = -1;
        int k = MethodRegistry.rgBucket[Utf8.utf8Hash2(clsU, 0, nameU, 0) & (MethodRegistry.RGTAB - 1)];
        while (k >= 0)
        {
            if (MethodRegistry.rgTab[k].buf != 0L
                    && Loader.utf8EqAt(MethodRegistry.rgTab[k].base, MethodRegistry.rgTab[k].classOff, clsU, 0)
                    && Loader.utf8EqAt(MethodRegistry.rgTab[k].base, MethodRegistry.rgTab[k].nameOff, nameU, 0)
                    && Loader.utf8EqAt(MethodRegistry.rgTab[k].base, MethodRegistry.rgTab[k].descOff, descU, 0))
            {
                best = k;
            }
            k = MethodRegistry.rgNext[k];
        }
        if (best >= 0)
        {
            return MethodRegistry.rgTab[best].buf;
        }
        long cell = dlCellOf(clsU, 0, nameU, 0, descU, 0);   // tier 2: a celled static, callable via its cell
        if (cell != 0L)
        {
            return Address.fromLong(cell).loadLong();
        }
        // Every matching vtable entry names the same class, so regBySigU answers the same for all of them and
        // the first match decides, as it did in the scan.
        k = VtableLayout.vtEntryOf(clsU, 0, nameU, 0, descU, 0);
        if (k >= 0)
        {
            int r = regBySigU(clsU);
            if (r >= 0 && !ClassRegistry.clTab[r].tib.isZero())
            {
                return ClassRegistry.clTab[r].tib.plus(8).plus(VtableLayout.vtSlot[k] * 8L).loadLong();
            }
        }
        return 0L;
    }

    static int regBySigU(Address clsU)
    {
        return ClassRegistry.classRegAt(clsU, 0);                      // an absolute {u2 len}{bytes} run: offset 0 of clsU
    }

    /**
     * The writer-baked, loader-linkable compiled buffer for the CURRENT class's method with the given
     * (name, descriptor) Utf8 offsets in {@code gbase} -- or 0 (compile our own copy). Table entries are
     * {classUtf8, nameUtf8, descUtf8, code} quadruples of image addresses; the name runs are
     * {u2 len}{bytes}, the same shape as classfile Utf8s, so {@link Loader#utf8EqAt} compares them directly
     * (each run's own address with offset 0).
     */
    static long bakedBuf(int nameOff, int descOff)
    {
        if (nameOff == 0)
        {
            return 0L;
        }
        int n = (int) VM.bakedCount;
        int i = 0;
        while (i < n)
        {
            Address e = Address.fromLong(VM.bakedTable + (long) i * 32L);
            if (Loader.utf8EqAt(Loader.gbase, Loader.gThisNameOff, e.loadAddress(), 0)
                    && Loader.utf8EqAt(Loader.gbase, nameOff, e.plus(8L).loadAddress(), 0)
                    && Loader.utf8EqAt(Loader.gbase, descOff, e.plus(16L).loadAddress(), 0))
            {
                return e.plus(24L).loadLong();
            }
            i += 1;
        }
        return 0L;
    }
}
