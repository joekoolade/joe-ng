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
import compiler.Symbols;
import magic.Address;
import magic.AddressArray;
import magic.Magic;

import static vm.ClassBytes.u2;

/**
 * Relocation: patching cross-class references a class could not resolve when it compiled (JikesRVM does the
 * equivalent in the boot-image writer's fixups). Each unresolved call / tail call / static site is recorded
 * ({@code rc*}, {@code rs*}); {@code patchRelocs} resolves them once their targets exist, and the incremental
 * batch patch ({@code patchIncremental}, the {@code pc*} dirty lists) re-visits only the sites a batch can have
 * changed. Sites that stay unresolvable are wired to the denylist trap ({@code trapWire*}). Split out of
 * {@link Loader}.
 */
final class Relocator
{
    private Relocator()
    {
    }

    // ----- relocation: patch cross-class refs a class couldn't resolve when it compiled ---------
    // In a dependency cycle the loader force-loads one class before the other, so its cross-class
    // invokestatic/special (bl) and getstatic/putstatic (address load) to the not-yet-loaded class compile
    // to a stub. Each such site is recorded (address + the ref as base+offsets, which stay valid), and
    // patchRelocs() re-resolves + rewrites them after every class is loaded -- so no manual seed-ordering.
    static final int MAXRELOC = 24576;
    static int relocRecording;                  // 1 only during emitMethod (the real-base emit pass)
    static long[] rcAddr;                       // call sites: bl address, ref blob base,
    static AddressArray rcBase;
    static int[] rcClass, rcName, rcDesc;       //   class/name/descriptor Utf8 offsets
    static int[] rcTail;                        //   1 = a tail branch (b), not a call (bl) -- lambda thunks
    /**
     * PER-SITE MEMO FOR THE UNRESOLVED ARM, and the reason it is sound is that the arm's answer cannot change.
     * {@code patchRelocs} revisits every reloc site from 0 at each batch end -- deliberately, so a callee
     * that loads later resolves for real -- but a site that is STILL unresolved re-derives two things that
     * are fixed: whether its callee's class is denylisted (a pure function of the name bytes) and which link
     * stub serves that callee ({@link DynamicLinker#linkStubFor} already dedups by callee identity, so it can only return
     * the same stub). Recording the stub turns both into one array read.
     *
     * <p>{@link DynamicLinker#linkStubFor}'s own comment asked for exactly this and named the condition: "indexed during
     * the patch arc on the theory that this was hot; MEASURED, the index bought NOTHING ... Revisit only if a
     * measurement puts time here." A measurement does now -- `unresT` is 507.742ms of a 1,300.917ms `callT`,
     * 39% -- which is the third time this file has recorded that a function measuring cold is a statement
     * about that closure rather than about the code.
     *
     * <p>It also stops the TRAPWIRE table growing without bound: that arm re-recorded the same site on every
     * batch, and the table filling is what makes a fired trap report an EMPTY callee.
     *
     * <p>"Cannot change" holds only while an index names the same SITE, and it does not: the five compile
     * paths that pull and retry roll {@code rcCount} back to drop the abandoned body's sites, and the drain
     * between them can run the batch-end {@code patchRelocs} over those sites first. A doomed
     * {@code InetAddress.getByName} site settled here, the retry recorded {@code Set.of(E,E,E)} into the same
     * index, and {@code FileSystemProvider.<clinit>} called getByName with {@code StandardOpenOption.CREATE}
     * as the host. So recording a site clears this, as it does {@link #rcReg}.
     */
    static long[] rcStub;                       //   the link stub this site settled on, 0 = not yet
    /**
     * The target this site's branch was last WRITTEN to, 0 = never. With the live instruction word it lets
     * {@link #patchRelocsFrom} skip a site whose branch already says what this pass would write. Dies with the
     * sites it indexes, like rcStub.
     */
    static long[] rcDone;
    static long pcSkipped;                              // tail visits skipped: the branch was already in place
    static int rcCount;
    /**
     * Per-site memo: the method-registry index that resolved this site last time, or -1.
     *
     * <p>{@code patchRelocs} re-walks EVERY site at the end of EVERY batch, and it genuinely has to: a site
     * unresolved in one batch may resolve in the next, and {@link LazyCompiler#rememberLazyBody} MUTATES
     * {@code rgTab[i].buf} when a method is lazily compiled -- so a site's correct target really does change
     * over time and cannot simply be left alone once patched.
     *
     * <p>What it does NOT have to do is repeat the LOOKUP. Recording which registry entry answered lets a
     * later pass re-read {@code rgTab[idx].buf} directly, which is one array read instead of a hashed probe
     * plus three Utf8 comparisons -- and it still writes the CURRENT buffer, so a body that moved is picked
     * up exactly as before. Only the direct registry tier memoises; the super-chain and stub tiers set -1 and
     * re-resolve, since their answer is not a single registry entry.
     */
    static int[] rcReg;
    /**
     * WHICH PATH each reloc site takes on the batch-end re-walk, counted rather than inferred. `patch` is
     * 1,622ms/batch at batch 165 with the per-site work already O(1) (a memo read plus a bitmap set), so the
     * cost has to be in the sites that DO NOT take the memo -- and the refill taught this session that the
     * unmemoisable remainder is exactly where the time hides.
     */
    static long pcCallT, pcStatT, pcPubT;
    static long pcLookT, pcUnresT, pcTailT;
    /**
     * The PER-SITE split of patchRelocsFrom's call loop (lookT / unresT / tailT). OFF: the loop runs once per
     * site per batch -- ~2.1M visits on SslEngineProbe -- and six CNTPCT_EL0 reads per visit were most of the
     * loop on QEMU, where each read is a call into the host clock. MEASURED (BATCH_COST, solo, 2026-11-06):
     * callT 12.3 s with them, 2.1 s without; the cold TLS session 59.8 -> 52.7 s. The A72 reads the counter
     * cheaply, so on the Pi this is a smaller effect -- but every QEMU patch figure recorded with them on was
     * inflated by it. callT (one pair per pass) stays. Turn on to split the loop again; the line prints 0s off.
     */
    static final boolean PATCH_SITE_TIMERS = false;
    static int pcMemo;
    static int pcResolve;
    static int pcUnres;
    /**
     * A STANDING LOOKUP: per site, 1 while {@code rcGateRg}/{@code rcGateDl}/{@code rcGateCell} describe the
     * answer {@link DynamicLinker#globalBufByRef} last gave it through the constructor tier or a declaring-class cell, 0
     * otherwise. Cleared when a site is recorded, as {@code rcReg} is.
     *
     * <p>MEASURED (SslEngineProbe, 2026-11-04): of 819,614 batch-end re-resolves, 670,117 were constructor
     * lookups (3.6 s) and 103,456 declaring-class cell lookups (3.6 s), and 99.6% or more of both gave the
     * answer of the pass before. Both answers are a function of the bucket chains the lookup walked -- one
     * {@code rgBucket} chain per class level it probed, then one {@code dlBucket} chain -- plus inputs that do
     * not change for the life of the sites: a blob's name, superclass and declared methods, and a class's
     * lowest-index loaded blob (blobs append). Both indexes head-insert, so a chain whose head index is
     * unchanged holds the same entries and gives the same answer. The one live input is the cell's CONTENTS
     * (the lazy stub, then the body), and the gate re-reads the cell exactly as {@code cellStubAt} would.
     * {@code resetLoader} discards the sites with the tables, so a gate never outlives the chains it names.
     */
    static int[] rcGate;
    static long[] rcGateCell;                   // the cell the lookup answered from, 0 = it missed
    static long[] rcGateDl;                     // dlBucket index << 32 | its head at the lookup
    static long[] rcGateRg;                     // GATE_RG per site: rgBucket index << 32 | head, by level
    static final int GATE_RG = 3;               // class levels a gated lookup may have probed
    private static final long[] gwRg = new long[GATE_RG];
    private static long gwCell;
    static long[] rsAddr;                       // static sites: address-load site, ref blob base,
    static AddressArray rsBase;
    static int[] rsReg, rsClass, rsName;        //   destination reg, class/name Utf8 offsets
    static int rsCount;
    // #43 trap diagnostics: every call site rewritten to bl denylistTrap, recorded so denylistTrap can read x30
    // (the return address) and report WHICH pruned callee actually fired at runtime (vs the dead-branch refs).
    /** Name a call site left pointing at denylistTrap, once per class+method. */
    private static final byte[][] twNamed = new byte[64][];
    private static int twNamedN;
    private static final AddressArray twNamedBase = AddressArray.create(64);
    private static final int[] twNamedCls = new int[64];
    private static final int[] twNamedMth = new int[64];
    private static final int MAXTRAPWIRE = 512;
    private static long[] trapWireSite = new long[MAXTRAPWIRE];   // the bl call-site address
    // The callee's class+name Utf8 ADDRESSES (blob base + offset), so a fired trap can NAME what it denied.
    // Storing only the site meant the report said `index=126` and nothing else -- a number that identifies the
    // callee only if you happen to have the verbose patch-time dump from the SAME boot, which costs seconds of
    // UART and in practice starves the run it was meant to diagnose. The name is two words per site.
    private static AddressArray trapWireCls = AddressArray.create(MAXTRAPWIRE);
    private static AddressArray trapWireMth = AddressArray.create(MAXTRAPWIRE);
    private static int trapWireCount;
    // ---- incremental batch patch ----------------------------------------------------------------------------
    // The batch-end patch used to re-walk EVERY call site recorded since launch, and the table grows all boot.
    // MEASURED (Pi 4, HttpsDemo, 2026-11-07): the call loop cost 1,319ms over the run, 955ms of it after the
    // first SYN, for 1.75M visits of which 0.27% wrote anything (QEMU SslEngineProbe: 5,949 writes in 2.19M
    // visits). The visits split by what can change a site's answer:
    //   - a MEMOISED site (rcReg >= 0) reads rgTab[rcReg].buf, and the only thing that moves a registry entry's
    //     buf is rememberLazyBody repointing it from the deferral stub to the compiled body. So these are
    //     revisited only when their entry is marked dirty there -- through a per-entry list of its sites.
    //   - every other site (gated, re-resolved, unresolved, settled on a link stub) is still walked every
    //     batch, in ascending order, exactly as before: a class loaded later can still resolve it, and the
    //     #43 trap-site table is still rebuilt from it.
    //   - sites recorded since the last walk are walked as new.
    // The per-site code (patchSite) is unchanged; only which sites are visited is. The lists are rebuilt from a
    // full walk whenever they cannot be trusted: first use, resetLoader, and any truncation that drops sites
    // (a doomed body's sites may have been patched, and so linked, by a nested batch before being dropped).
    private static final int[] pcNoInts = new int[0];
    static boolean pcListsValid;                 // false -> next batch patch walks everything + rebuilds
    private static int pcSeenTo;                         // sites below this are on a list (non-memo) or linked
    private static int[] pcNm = pcNoInts;                // non-memoised sites, ascending
    private static int pcNmN;
    private static int[] pcRegHead = pcNoInts;           // per registry entry: first memoised site, -1 = none
    private static int[] pcRegNext = pcNoInts;           // per site: next memoised site of the same entry
    private static int[] pcDirty = pcNoInts;             // registry entries repointed since the last walk
    private static int pcDirtyN;
    private static boolean[] pcIsDirty = new boolean[0];
    static int[] pcWritten = pcNoInts;           // sites written by the current patch call
    private static int pcWrittenN;
    private static boolean pcPubAll;                     // pcWritten overflowed: publish every site in range

    private static void reportTrapWired(Address base, int clsOff, int nameOff, int descOff)
    {
        int k = 0;
        while (k < twNamedN)
        {
            if (Loader.utf8EqAt(base, clsOff, twNamedBase.get(k), twNamedCls[k])
                    && Loader.utf8EqAt(base, nameOff, twNamedBase.get(k), twNamedMth[k]))
            {
                return;
            }
            k += 1;
        }
        if (twNamedN < 64)
        {
            twNamedBase.set(twNamedN, base);
            twNamedCls[twNamedN] = clsOff;
            twNamedMth[twNamedN] = nameOff;
            twNamedN += 1;
        }
        Uart.write(Magic.bytes("\n  TRAP-WIRED (call will halt if reached): "));
        Loader.printNameAt(base, clsOff);
        Uart.putc(0x2E);
        Loader.printNameAt(base, nameOff);
        Loader.printNameAt(base, descOff);
        Uart.write(Magic.bytes(Loader.isDenylisted(base, clsOff) ? " -- DENYLISTED" : " -- NOT denied: no stub could be minted"));
        Uart.putc(0x0A);
    }

    /** Record an unresolved cross-class call at {@code blAddr} (the ref names class/name/desc in the current cp). */
    static void recordCallReloc(long blAddr, int idx)
    {
        if (relocRecording == 0 || rcCount >= MAXRELOC)
        {
            return;
        }
        rcAddr[rcCount] = blAddr;
        rcBase.set(rcCount, Loader.gbase);
        rcClass[rcCount] = Loader.refClassNameOff(idx);
        rcName[rcCount] = Loader.mrefNameOff(idx);
        rcDesc[rcCount] = Loader.mrefDescOff(idx);
        rcTail[rcCount] = 0;
        rcReg[rcCount] = -1;                            // not yet resolved through the direct registry tier
        rcStub[rcCount] = 0L;                           // nor settled on a link stub: see rcStub
        rcDone[rcCount] = 0L;                           // nor written: see rcDone
        rcGate[rcCount] = 0;                            // nor gated on a standing lookup: see rcGate
        rcCount += 1;
    }

    /** Record an unresolved TAIL branch ({@code b}, not {@code bl}) at {@code bAddr} to Methodref {@code idx} --
     *  used by a lambda/method-ref thunk whose cross-class impl isn't registered yet at thunk-build time. */
    static void recordTailReloc(long bAddr, int idx)
    {
        if (relocRecording == 0 || rcCount >= MAXRELOC)
        {
            return;
        }
        rcAddr[rcCount] = bAddr;
        rcBase.set(rcCount, Loader.gbase);
        rcClass[rcCount] = Loader.refClassNameOff(idx);
        rcName[rcCount] = Loader.mrefNameOff(idx);
        rcDesc[rcCount] = Loader.mrefDescOff(idx);
        rcTail[rcCount] = 1;
        rcReg[rcCount] = -1;                            // see rcReg: 0 is a VALID registry index, so an unset
        rcStub[rcCount] = 0L;                           //   entry would memoise the site to rgTab[0]; see rcStub
        rcDone[rcCount] = 0L;                           //   ... and rcDone
        rcGate[rcCount] = 0;                            //   ... and rcGate
        rcCount += 1;
    }

    /** Record an unresolved cross-class static-field address load at {@code loadAddr} (2 words, into {@code reg}). */
    static void recordStaticReloc(long loadAddr, int reg, int idx)
    {
        if (relocRecording == 0 || rsCount >= MAXRELOC)
        {
            return;
        }
        rsAddr[rsCount] = loadAddr;
        rsReg[rsCount] = reg;
        rsBase.set(rsCount, Loader.gbase);
        rsClass[rsCount] = Loader.refClassNameOff(idx);
        rsName[rsCount] = Loader.mrefNameOff(idx);
        rsCount += 1;
    }

    /** #43 diagnostic: which trap-wired call site (by index, as printed at patch time) does return address {@code lr}
     *  belong to? Returns the TRAPWIRE index, or -1. The bl site is {@code lr - 4}. */
    /** Print the callee a fired trap denied, as {@code class.method}. No-op for an unknown index. */
    static void printTrapCallee(int k)
    {
        if (k < 0 || k >= trapWireCount || trapWireCls.get(k).isZero())
        {
            return;
        }
        Uart.write(Magic.bytes(" "));
        Loader.printNameAt(trapWireCls.get(k), 0);
        Uart.putc(0x2E);
        Loader.printNameAt(trapWireMth.get(k), 0);
    }

    /** Whether trap-wired callee {@code k}'s class is on the deny list (false for an unknown index). */
    static boolean trapCalleeDenied(int k)
    {
        if (k < 0 || k >= trapWireCount || trapWireCls.get(k).isZero())
        {
            return false;
        }
        return Loader.isDenylisted(trapWireCls.get(k), 0);
    }

    static int trapIndexFor(long lr)
    {
        long site = lr - 4L;
        int i = 0;
        while (i < trapWireCount)
        {
            if (trapWireSite[i] == site) { return i; }
            i += 1;
        }
        return -1;
    }

    /** Re-resolve and rewrite every recorded reloc site now that all classes are loaded. */
    static void patchRelocs()
    {
        trapWireCount = 0;                                     // #43: fresh trap-site table per batch
        patchRelocsFrom(0, 0);
    }

    /**
     * Patch the reloc sites recorded from {@code rcStart}/{@code rsStart} onward, leaving earlier ones (and
     * the #43 trap-site table) alone. A LAZY compile runs long after its batch's {@link #patchRelocs}, so its
     * own unresolved sites would otherwise stay {@code bl 0} forever and branch to address 0 — which the
     * firmware's low-memory shim turns into a re-entry of the image entry point ("BOOT RE-ENTERED"). The
     * eager path never hit this because every body was emitted before the batch-end patch. Re-resolution here
     * is also what supplies {@link DynamicLinker#globalBufByRef}'s super-chain walk for an INHERITED static/special call
     * (e.g. {@code ArrayList.subList} calling {@code subListRangeCheck}, declared on {@code AbstractList}),
     * which the compile-time {@link Loader#resolveCallBuf} cannot see.
     */
    static void patchRelocsFrom(int rcStart, int rsStart)
    {
        long tA0 = Magic.readCNTPCT_EL0();
        pcWrittenN = 0;
        pcPubAll = false;
        int pubFrom = rcStart;
        if (rcStart == 0 && pcListsValid)
        {
            pubFrom = patchIncremental();
        }
        else
        {
            int i = rcStart;
            while (i < rcCount)
            {
                patchSite(i);
                i += 1;
            }
            if (rcStart == 0)
            {
                rebuildPatchLists();
            }
        }
        long tB0 = Magic.readCNTPCT_EL0();
        pcCallT += tB0 - tA0;
        int j = rsStart;
        while (j < rsCount)
        {
            long addr = DynamicLinker.globalStaticByRef(rsBase.get(j), rsClass[j], rsName[j]);
            if (addr == 0L)
            {
                // Unresolved: the class is not loaded YET. Point it at the permanently-zero cell rather than
                // leaving the emitted `movz 0` in place -- otherwise the access reads ADDRESS 0, the firmware's
                // low-memory shim, and hands instruction words back as if they were the field's value.
                //
                // Not a final verdict and deliberately silent: patchRelocs() revisits every site from 0 at each
                // batch end, so a class loaded LATER still patches this site properly. The cell only decides
                // what happens if it never is -- a null read and an ordinary NPE, instead of a wild branch.
                // NAME IT WHEN THE CLASS IS ALREADY LOADED. Binding to the zero cell is legitimate while the
                // class simply has not arrived yet -- patchRelocs revisits every site at each batch end, so a
                // later batch resolves it properly, and reporting then would cry wolf on a healthy boot. But
                // if the class IS REGISTERED and the field still has no cell, no later batch will fix it: the
                // access reads null for the life of the VM, and nothing says a word.
                //
                // That silence is what hid the launcher's failure. TestMethodTestDescriptor.<clinit> stores
                // defaultInterceptorCall correctly (both putstatics resolve, and the boot log shows their
                // cells), but the CONSTRUCTOR's getstatic of the same field appears in no resolver log at
                // all -- it came through here, bound to the zero cell, and read null. Three hops later that
                // null is a lambda capture, and the NPE names a JUnit lambda rather than this.
                Loader.reportZeroCellBind(rsBase.get(j), rsClass[j], rsName[j]);
                addr = Loader.unresolvedStaticCell();
            }
            if (Loader.STATIC_ADDR_LOG)
            {
                // THE PATCH PATH LOGGED NOTHING, success or failure, and that ambiguity cost a boot: the
                // absence of a line for a field was read as "bound to the zero cell" when it only ever meant
                // "resolved here, where nothing prints". A site's cell is only comparable against the one its
                // <clinit> stored through if BOTH paths say which address they chose.
                Uart.write(Magic.bytes("  patchstatic "));
                Loader.printNameAt(rsBase.get(j), rsClass[j]);
                Uart.putc(0x2E);
                Loader.printNameAt(rsBase.get(j), rsName[j]);
                Uart.write(Magic.bytes(" -> "));
                VM.printHex(addr);
                Uart.putc(0x0A);
            }
            Address.fromLong(rsAddr[j]).store(A64Enc.movz(rsReg[j], (int) (addr & 0xFFFFL), 0));   // rewrite the movz+movk
            Address.fromLong(rsAddr[j]).plus(4L).store(A64Enc.movk(rsReg[j], (int) ((addr >>> 16) & 0xFFFFL), 1));
            j += 1;
        }
        long tC0 = Magic.readCNTPCT_EL0();
        pcStatT += tC0 - tB0;
        if (pcPubAll)
        {
            pcWrittenN = 0;                              // the range below then covers every site
            pubFrom = rcStart;
        }
        // PUBLISH THE WORDS THIS ACTUALLY WROTE, not the whole arena.
        //
        // It was `publishCode(CODE_BASE, <arena pointer>)` -- cache maintenance over EVERY line of the code
        // arena, on every patch, while the arena grows all boot and the number of patched words does not.
        // Ninth instance of this file's most common defect, and the last one left in `patch`: MEASURED at
        // 12,680ms cumulative on a launcher boot, larger than everything else remaining there put together.
        //
        // The writes are exactly one instruction per call site (the `bl`/`b` above) and two per static site
        // (the movz+movk), so the lines that need maintenance are known precisely: the call sites patchSite
        // actually WROTE (pcWritten), every site recorded since the last walk (new code), and the static
        // sites, which are rewritten unconditionally. An old call site that was not rewritten was published
        // when it last was. Two passes keep the range version's ordering exactly: every clean reaches unified
        // memory BEFORE any invalidate is issued, and the barriers are paid ONCE rather than per site.
        //
        // linkStubFor and the deferral/thunk minters publish their own buffers, so nothing else written
        // between two of these calls depends on the wide sweep this replaces.
        int w = 0;
        while (w < pcWrittenN)
        {
            Heap.publishClean(Address.fromLong(rcAddr[pcWritten[w]]), Address.fromLong(rcAddr[pcWritten[w]] + 4L));
            w += 1;
        }
        int p = pubFrom;
        while (p < rcCount)
        {
            Heap.publishClean(Address.fromLong(rcAddr[p]), Address.fromLong(rcAddr[p] + 4L));
            p += 1;
        }
        int q = rsStart;
        while (q < rsCount)
        {
            Heap.publishClean(Address.fromLong(rsAddr[q]), Address.fromLong(rsAddr[q] + 8L));
            q += 1;
        }
        Heap.publishMid();
        w = 0;
        while (w < pcWrittenN)
        {
            Heap.publishInval(Address.fromLong(rcAddr[pcWritten[w]]), Address.fromLong(rcAddr[pcWritten[w]] + 4L));
            w += 1;
        }
        p = pubFrom;
        while (p < rcCount)
        {
            Heap.publishInval(Address.fromLong(rcAddr[p]), Address.fromLong(rcAddr[p] + 4L));
            p += 1;
        }
        q = rsStart;
        while (q < rsCount)
        {
            Heap.publishInval(Address.fromLong(rsAddr[q]), Address.fromLong(rsAddr[q] + 8L));
            q += 1;
        }
        Heap.publishEnd();
        pcPubT += Magic.readCNTPCT_EL0() - tC0;
    }

    /** Drop the reloc sites at and above {@code mark}; distrust the patch lists if any were actually dropped. */
    static void truncateRelocs(int mark)
    {
        if (mark < rcCount)
        {
            pcListsValid = false;
        }
        rcCount = mark;
    }

    /** rememberLazyBody moved registry entry {@code rg}'s buf: its memoised sites must be revisited. */
    static void markRegDirty(int rg)
    {
        if (!pcListsValid || rg < 0 || rg >= pcIsDirty.length || pcIsDirty[rg])
        {
            return;                                      // invalid lists: the next walk visits everything
        }
        pcIsDirty[rg] = true;
        pcDirty[pcDirtyN] = rg;
        pcDirtyN += 1;
    }

    private static void linkMemoSite(int i)
    {
        int rg = rcReg[i];
        pcRegNext[i] = pcRegHead[rg];
        pcRegHead[rg] = i;
    }

    /** After a FULL walk: file every site on the non-memo list or its registry entry's list. */
    private static void rebuildPatchLists()
    {
        if (pcRegHead.length != MethodRegistry.MAXREG)
        {
            pcRegHead = new int[MethodRegistry.MAXREG];
            pcRegNext = new int[MAXRELOC];
            pcNm = new int[MAXRELOC];
            pcDirty = new int[MethodRegistry.MAXREG];
            pcIsDirty = new boolean[MethodRegistry.MAXREG];
        }
        int r = 0;
        while (r < MethodRegistry.MAXREG)
        {
            pcRegHead[r] = -1;
            pcIsDirty[r] = false;
            r += 1;
        }
        pcDirtyN = 0;
        pcNmN = 0;
        int i = 0;
        while (i < rcCount)
        {
            if (rcReg[i] >= 0)
            {
                linkMemoSite(i);
            }
            else
            {
                pcNm[pcNmN] = i;
                pcNmN += 1;
            }
            i += 1;
        }
        pcSeenTo = rcCount;
        pcListsValid = true;
    }

    /**
     * The batch walk over the lists: the non-memoised sites, the new ones, and the memoised sites of every
     * repointed registry entry. Returns the first new site, from which publishing must cover every site.
     */
    private static int patchIncremental()
    {
        int from = pcSeenTo;
        if (from > rcCount)                              // shrunk without truncateRelocs: trust nothing
        {
            int i = 0;
            while (i < rcCount)
            {
                patchSite(i);
                i += 1;
            }
            rebuildPatchLists();
            return 0;
        }
        int k = 0;
        while (k < pcNmN)
        {
            patchSite(pcNm[k]);
            k += 1;
        }
        int i = from;
        while (i < rcCount)
        {
            patchSite(i);
            i += 1;
        }
        // DRAINED, not walked once: a lookup above can lazily compile a body and so repoint an entry DURING
        // this walk (measured: 14 times on SslEngineProbe). Popping until empty patches those sites now; the
        // old full walk only caught a mid-walk repoint for sites at a higher index than the one in hand.
        while (pcDirtyN > 0)
        {
            pcDirtyN -= 1;
            int rg = pcDirty[pcDirtyN];
            pcIsDirty[rg] = false;                       // cleared FIRST: a repoint during this list re-queues it
            int j = pcRegHead[rg];
            while (j >= 0)
            {
                patchSite(j);
                j = pcRegNext[j];
            }
        }
        // Re-file: a non-memo site that memoised this walk moves to its entry's list; new sites are filed.
        int kept = 0;
        k = 0;
        while (k < pcNmN)
        {
            int s0 = pcNm[k];
            if (rcReg[s0] >= 0)
            {
                linkMemoSite(s0);
            }
            else
            {
                pcNm[kept] = s0;
                kept += 1;
            }
            k += 1;
        }
        pcNmN = kept;
        i = from;
        while (i < rcCount)
        {
            if (rcReg[i] >= 0)
            {
                linkMemoSite(i);
            }
            else
            {
                pcNm[pcNmN] = i;
                pcNmN += 1;
            }
            i += 1;
        }
        pcSeenTo = rcCount;
        return from;
    }

    /** Patch call site {@code i}: re-resolve or re-read its target, and rewrite its branch if that changed. */
    private static void patchSite(int i)
    {
        // MEMOISED: re-read the entry that answered last time. rgTab[].buf is live, so a body that was
        // lazily compiled since (rememberLazyBody) is picked up exactly as a fresh lookup would -- this
        // skips the LOOKUP, never the patch.
        long target;
        long tL0 = 0L;
        if (PATCH_SITE_TIMERS)
        {
            tL0 = Magic.readCNTPCT_EL0();
        }
        if (rcReg[i] >= 0)
        {
            pcMemo += 1;
            target = MethodRegistry.rgTab[rcReg[i]].buf;
        }
        else if (gateStands(i))
        {
            pcResolve += 1;                          // counted as the lookup it replaces: see pcUnres
            target = rcGateCell[i] == 0L ? 0L : Address.fromLong(rcGateCell[i]).loadLong();
        }
        else
        {
            pcResolve += 1;
            target = DynamicLinker.globalBufByRef(rcBase.get(i), rcClass[i], rcName[i], rcDesc[i]);
            if (target != 0L)
            {
                rcReg[i] = Loader.rgHitIdx;                 // -1 for the super-chain/stub tiers: re-resolve those
            }
            recordGate(i);
        }
        long tU0 = 0L;
        if (PATCH_SITE_TIMERS)
        {
            pcLookT += Magic.readCNTPCT_EL0() - tL0;
            tU0 = Magic.readCNTPCT_EL0();
        }
        if (target == 0L && rcStub[i] != 0L)
        {
            // SETTLED ALREADY: this site was unresolved on an earlier batch and took a link stub, and
            // neither half of that decision can change (see rcStub). One array read instead of sixty
            // prefix compares plus a scan of every stub minted so far.
            //
            // `pcUnres` COUNTS THIS SITE TOO, and that is not bookkeeping pedantry: `memo/res/unres` is
            // the triple every entry in this file gates identity on -- "the same sites resolved through
            // the same tiers to the same answers". Incrementing only the new counter made `unres` read
            // 463 against 2193 for the same work, which looks exactly like a closure that changed. A
            // counter used as an invariant must keep meaning the same thing.
            pcUnres += 1;
            Loader.pcUnresMemo += 1;
            target = rcStub[i];
        }
        if (target == 0L)
        {
            pcUnres += 1;
            // Unresolved. Two very different causes, and they want opposite treatment:
            //   - the callee's class is DENYLISTED (a metal-absent subtree on a cold branch) -> trap, as
            //     before; resolving it would demand-load exactly what the denylist exists to keep out.
            //   - the class is merely ABSENT from a closure that was computed without ever reading this
            //     body -- the RTA-through-reflection gap -> a link stub, which resolves on first call.
            // The trapwire site is recorded either way, so a link stub that fails to resolve still lands
            // in denylistTrap with its index intact.
            target = VM.denylistTrapAddr;
            if (trapWireCount < MAXTRAPWIRE)
            {
                if (Loader.logTrapWire != 0)                      // verbose per-call dump (index -> callee); off by default
                {
                    Uart.write(Magic.bytes("  TRAPWIRE["));   // matched at runtime by x30 in denylistTrap
                    VM.printDec(trapWireCount);
                    Uart.write(Magic.bytes("] "));
                    Loader.writeName(rcBase.get(i).plus(rcClass[i] + 2), u2(rcBase.get(i).plus(rcClass[i])));   // callee class Utf8
                    Uart.putc(0x2E);                       // '.'
                    Loader.writeName(rcBase.get(i).plus(rcName[i] + 2), u2(rcBase.get(i).plus(rcName[i])));     // callee method Utf8
                    Uart.putc(0x0A);
                }
                trapWireSite[trapWireCount] = rcAddr[i];   // ALWAYS recorded: denylistTrap looks up the fired
                trapWireCls.set(trapWireCount, rcBase.get(i).plus(rcClass[i]));   // site by x30, so the table must exist
                trapWireMth.set(trapWireCount, rcBase.get(i).plus(rcName[i]));    //   even when quiet -- and now it
                trapWireCount += 1;                                    //   carries the NAME as well
            }
            Loader.pcUnresDeny += 1;
            if (!Loader.isDenylisted(rcBase.get(i), rcClass[i]))
            {
                long stub = DynamicLinker.linkStubFor(rcBase.get(i).plus(rcClass[i]), rcBase.get(i).plus(rcName[i]),
                        rcBase.get(i).plus(rcDesc[i]));
                if (stub != 0L)
                {
                    target = stub;
                    rcStub[i] = stub;               // settled: the arm above answers from here on
                }
            }
            if (target == VM.denylistTrapAddr)
            {
                // NAME IT HERE, where the information exists. A site left pointing at the trap reports
                // `TRAPWIRE index=-1` and an EMPTY callee at runtime once trapWireCount passes
                // MAXTRAPWIRE -- and with launchMain sharing loader state across every program, those
                // sites now ACCUMULATE where they used to reset per launch, so the table fills and the
                // name is the first thing lost. A trap that cannot say what it was calling is
                // unactionable; this is the same "report a checked cause" the static and link paths
                // already do.
                reportTrapWired(rcBase.get(i), rcClass[i], rcName[i], rcDesc[i]);
            }
        }
        long tT0 = 0L;
        if (PATCH_SITE_TIMERS)
        {
            pcUnresT += Magic.readCNTPCT_EL0() - tU0;
            tT0 = Magic.readCNTPCT_EL0();
        }
        if (target != 0L)
        {
            if (target >= Heap.BASE)                       // DIAGNOSTIC: a call "resolved" into the DATA heap
            {                                              //   (blob/object, not code) -> name the callee
                Uart.write(Magic.bytes("  BADPATCH "));
                Loader.writeName(rcBase.get(i).plus(rcClass[i] + 2), u2(rcBase.get(i).plus(rcClass[i])));
                Uart.putc(0x2E);
                Loader.writeName(rcBase.get(i).plus(rcName[i] + 2), u2(rcBase.get(i).plus(rcName[i])));
                Uart.write(Magic.bytes(" -> "));
                VM.printHex(target);
                Uart.putc(0x0A);
            }
            long d = target - rcAddr[i];                   // A64 bl reaches +-128 MiB (26-bit word offset)
            if (d > 0x07FFFFFFL || d < -0x08000000L)
            {
                VM.jitFail(Symbols.FAIL_BL_RANGE, (int) (rcAddr[i] >> 12), (int) (target >> 12));
                for (;;) { }
            }
            int off = (int) (d >> 2);
            int word = rcTail[i] != 0 ? A64Enc.b(off) : A64Enc.bl(off);
            // ALREADY WRITTEN, AND STILL THERE: skip the pin, the edge note and the store. All three are
            // idempotent for an unchanged target -- the pin bitmap is sticky (zeroed only at init), note()
            // overwrites the same (site, target) entry, the store rewrites the same word -- yet every batch
            // re-walks EVERY site from 0, so they ran sites x batches times. MEASURED (BATCH_COST,
            // SslEngineProbe, QEMU 2026-11-06): patch was 8.8 s of the cold TLS session's 16.0 s of
            // batch work, and this tail (tailT) 5.1 s of the call loop's 14.2 s over the run. The test reads
            // the LIVE word, not only rcDone, so a site something else rewrote is patched as before.
            if (rcDone[i] == target && Address.fromLong(rcAddr[i]).loadInt() == word)
            {
                pcSkipped += 1;
                if (PATCH_SITE_TIMERS)
                {
                    pcTailT += Magic.readCNTPCT_EL0() - tT0;
                }
                return;
            }
            rcDone[i] = target;
            Heap.pinCodeAt(Address.fromLong(target));                       // CODE->CODE edge: after this store the only
                                                          //   record of `target` is a displacement inside
                                                          //   the caller's instructions, which nothing
                                                          //   scans -- pin it or the sweep may free it
            CodeEdges.note(rcAddr[i], target);            // ... and record the edge, so compaction can
                                                          //   find and rewrite it when `target` moves
            Address.fromLong(rcAddr[i]).store(word);                // bl target, or b for a lambda/method-ref thunk
            if (pcWrittenN < pcWritten.length)             // publish exactly the words written
            {
                pcWritten[pcWrittenN] = i;
                pcWrittenN += 1;
            }
            else
            {
                pcPubAll = true;                           // an entry re-queued mid-drain can rewrite a
            }                                              //   site twice: fall back to publishing all
        }
        if (PATCH_SITE_TIMERS)
        {
            pcTailT += Magic.readCNTPCT_EL0() - tT0;
        }
    }

    /**
     * Whether site {@code i}'s standing lookup still stands: every bucket chain it was decided over has the
     * head it had then (see {@code rcGate}). Indexes both tables first, so an entry added but not yet indexed
     * is seen -- the lookup it replaces would index it too.
     */
    private static boolean gateStands(int i)
    {
        if (rcGate[i] == 0 || DynamicLinker.dlTab == null)
        {
            return false;
        }
        MethodRegistry.buildRegIndex();
        DynamicLinker.buildDlIndex();
        long dl = rcGateDl[i];
        if (DynamicLinker.dlBucket[(int) (dl >>> 32)] != (int) dl)
        {
            return false;
        }
        int n = rcGate[i];
        int b = i * GATE_RG;
        int k = 0;
        while (k < n)
        {
            long rg = rcGateRg[b + k];
            if (MethodRegistry.rgBucket[(int) (rg >>> 32)] != (int) rg)
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    /** Keep what the {@link DynamicLinker#globalBufByRef} just made for site {@code i} walked, if it can stand. */
    private static void recordGate(int i)
    {
        if (!Loader.gwOk)
        {
            rcGate[i] = 0;
            return;
        }
        int b = i * GATE_RG;
        int k = 0;
        while (k < Loader.gwRgN)
        {
            rcGateRg[b + k] = gwRg[k];
            k += 1;
        }
        rcGateDl[i] = Loader.dlLastProbe;
        rcGateCell[i] = gwCell;
        rcGate[i] = Loader.gwRgN;                              // >= 1: the direct tier always probes first
    }

    /** Note an {@code rgBucket} probe for the gate; false once there are more levels than a gate holds. */
    static boolean gateRg(int h)
    {
        if (Loader.gwRgN >= GATE_RG)
        {
            return false;
        }
        gwRg[Loader.gwRgN] = ((long) h << 32) | (MethodRegistry.rgBucket[h] & 0xFFFFFFFFL);
        Loader.gwRgN += 1;
        return true;
    }

    /**
     * The callable contents of the cell {@link DynamicLinker#dlCellOf} finds, or 0 -- {@link DynamicLinker#cellStubAt} with the cell
     * kept for the gate, which stands only if {@code fits} (every probe so far was recorded and every
     * level skipped was decided, not unknown).
     */
    static long gatedCell(Address clsBase, int clsOff, Address refBase, int nameOff, int descOff, boolean fits)
    {
        if (DynamicLinker.dlTab == null)
        {
            return 0L;
        }
        long cell = DynamicLinker.dlCellOf(clsBase, clsOff, refBase, nameOff, refBase, descOff);
        gwCell = cell;
        Loader.gwOk = fits;
        return cell == 0L ? 0L : Address.fromLong(cell).loadLong();
    }
}
