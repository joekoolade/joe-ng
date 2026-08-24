package vm;

import magic.Magic;
import board.bcm2711.Uart;

/**
 * The code -> code edge census: every direct branch from one code-arena buffer to another, recorded as a
 * {@code {siteAddr, targetAddr}} pair.
 *
 * <p><b>Why this exists.</b> The whole point of the code-lifetime arc (PRs #140-#147) was that <i>a {@code bl}
 * displacement is not a pointer</i>: once {@code A64Enc.bl} runs, the only record of the target is an encoded
 * offset inside the caller's instruction words, and nothing in the VM scans encodings. The collector
 * compensates with {@link Heap#pinCodeAt}, which keeps a branched-to buffer alive. Compaction needs the
 * strictly harder thing: not just "keep it alive" but "it moved -- rewrite every branch that names it". That
 * requires the edge SET, which does not exist anywhere today.
 *
 * <p><b>What is and is not an edge.</b> Only INTER-buffer branches matter. {@code Baseline} emits plenty of
 * {@code b} instructions for a method's own control flow, but those are self-relative: moving a buffer whole
 * preserves every displacement inside it, so they need no patching and are not recorded. Likewise a branch
 * whose target is in the image or a native (below {@link Heap#CODE_BASE}) can never move; those are counted
 * in {@link #nonArena} and not stored.
 *
 * <p>The four sites that create a real arena -> arena edge are exactly the four that call
 * {@code Heap.pinCodeAt} on a TARGET (the other two pin a deferral stub for reachability, which is not an
 * edge): {@code MetalSymbols.call}'s compile-time-resolved branch, {@code Loader.patchRelocsFrom}'s
 * {@code bl}/{@code b} rewrite, and the lambda thunk's {@code bl initBuf} and {@code b implBuf}.
 *
 * <p><b>This increment records and verifies only.</b> Nothing moves. The question it answers is whether the
 * census is COMPLETE and CORRECT -- every recorded site really holds a branch to the recorded target -- because
 * a census that silently misses an edge would, under compaction, produce exactly the #146 failure: a live
 * caller branching to where a buffer used to be. That bug was found only after five wrong theories, so the
 * table is checked before it is trusted, not after.
 */
final class CodeEdges
{
    /** Fixed scratch, in the free band left above {@code Loader.STUB_TAB} and below {@code CODE_ROOTS}. */
    static final long TAB = 0x030A_0000L;
    static final long TAB_END = 0x030C_0000L;            // 128 KiB = 8,192 entries at 16 bytes (obs. peak 3,182).
                                                         // Halved to make room for CodeCompact.PLAN_TAB above;
                                                         // `dropped` catches exhaustion if a workload grows.

    /** Edges recorded (arena -> arena only). */
    static long n;
    /** Edges that did not fit the table -- if this is ever non-zero the census is incomplete. */
    static long dropped;
    /** Branches whose target is image/native code, which never moves: counted, not stored. */
    static long nonArena;
    /** Entries superseded by a later patch of the same site (see {@link #note}). */
    static long retiredCount;
    /** Entries dropped because the sweep freed the block their site lived in (lifetime total). */
    static long prunedCount;

    /** Last {@link #verify} pass: sites whose decoded target still matches what was recorded. */
    static long okCount;
    /** Sites in a block the sweep has since freed: the edge is dead, and disagreement is expected. */
    static long staleCount;
    /**
     * Sites whose word is no longer an unconditional branch at all -- the sweep zeroed it, or the block was
     * freed and handed to a later allocation that wrote different code there. Also a dead edge: the caller no
     * longer exists. Distinct from {@link #staleCount} only in that the block registry now says ALLOCATED,
     * because a reused block is indistinguishable from a live one by state alone.
     */
    static long reusedCount;
    /**
     * Sites that DO hold a branch, to an address other than the one recorded.
     *
     * <p><b>Contaminated by block reuse, and not yet a bug signal.</b> {@link #reusedCount} catches a recycled
     * site only when its word is no longer a branch at all; when the code written there next happens to put a
     * branch at the same offset, the dead edge lands here instead. Measured across a suite run this counter
     * goes 0 for sixteen batches then {@code 1,1,1,1,2,4,49,23,4} -- it RISES AND FALLS, and a live mis-linked
     * edge cannot heal, so what it is counting is dead callers in reused memory.
     *
     * <p>Separating the two needs the census pruned when a block is freed, so dead edges never reach
     * {@link #verify} -- which compaction needs anyway, since it must not consult a dead edge. Until then,
     * read this counter as "dead edges not caught by the reused test", not as a fault.
     */
    static long wrongTargetCount;

    /**
     * LIVE edges whose TARGET sits in a block the sweep has freed. This is the #146 signature exactly -- a
     * caller still branching to code that was reclaimed under it -- and should always be 0, because every one
     * of the four recording sites pins its target. A non-zero reading is a pin that did not hold.
     */
    static long danglingCount;

    /** First wrong-target site of the last pass, for investigation ({@code 0} if there was none). */
    static long wtSite;
    static long wtWant;
    static long wtGot;

    /**
     * Record a branch at {@code site} to {@code target}. Called just BEFORE the branch is encoded, so
     * {@code site} is where the instruction will land.
     *
     * <p><b>Keyed by site, not append-only.</b> A call site can be patched more than once:
     * {@code patchRelocsFrom(0, 0)} re-walks the reloc table from index 0 on every batch, so a site that
     * resolved to an arena buffer in one batch can be re-resolved later -- to a different buffer, or to
     * {@code VM.denylistTrapAddr} in the image when the callee's class is no longer registered. An earlier
     * entry left in the table then describes the site WRONGLY, and a compactor moving the old target would
     * rewrite a branch that legitimately points somewhere else now.
     *
     * <p>So a repeat of the same site overwrites its entry, and a re-patch to a non-arena target RETIRES it
     * (target 0) rather than being ignored.
     *
     * <p>This is DEFENSIVE, not a fix for an observed fault: {@code retiredCount} was 0 across a whole suite
     * run, so no re-patch actually changed an arena target in practice. It is kept because the mechanism is
     * structurally live -- {@code patchRelocs()} calls {@code patchRelocsFrom(0, 0)} and runs three times per
     * batch -- and an append-only census would describe such a site wrongly if it ever fired.
     */
    static void note(long site, long target)
    {
        long t = target;
        if (t < Heap.CODE_BASE || t >= Heap.CODE_LIMIT)
        {
            nonArena += 1;                               // image/native: cannot move, nothing to patch
            t = 0L;
        }
        long at = findSite(site);
        if (at >= 0L)
        {
            Magic.store64(at + 8L, t);                   // supersede: last patch wins, 0 = retired
            return;
        }
        if (t == 0L)
        {
            return;                                      // never was an arena edge, nothing to supersede
        }
        if (TAB + n * 16L + 16L > TAB_END)
        {
            dropped += 1;
            return;
        }
        Magic.store64(TAB + n * 16L, site);
        Magic.store64(TAB + n * 16L + 8L, t);
        n += 1;
    }

    /**
     * Drop every edge whose SITE lies in {@code [lo,hi)} -- called as the sweep frees that block, which is the
     * one moment the range is known exactly.
     *
     * <p>Pruning at free time rather than classifying at verify time is what makes the answer sound. After the
     * fact a freed block is indistinguishable from a live one once it has been reallocated: the registry says
     * ALLOCATED either way, so a dead edge whose memory now holds unrelated code cannot be told apart from a
     * live edge that is genuinely mis-linked. That ambiguity is what inc 1 measured as a {@code WRONGTGT}
     * count that rose and fell (0 for sixteen batches, then 1,1,1,1,2,4,49,23,4) instead of a fault.
     *
     * <p>Compaction needs this regardless: a compactor must never consult a dead edge, or it will rewrite a
     * branch inside a buffer that no longer belongs to the caller that recorded it.
     */
    static void pruneRange(long lo, long hi)
    {
        long i = 0;
        while (i < n)
        {
            long e = TAB + i * 16L;
            long site = Magic.load64(e);
            if (site >= lo && site < hi)
            {
                Magic.store64(e, 0L);                    // site 0 = dead slot; verify and findSite skip it
                Magic.store64(e + 8L, 0L);
                prunedCount += 1;
            }
            i += 1;
        }
    }

    /** Address of the entry for {@code site}, or -1. Linear; n stays in the low thousands. */
    private static long findSite(long site)
    {
        long i = 0;
        while (i < n)
        {
            long e = TAB + i * 16L;
            if (Magic.load64(e) == site && site != 0L)
            {
                return e;
            }
            i += 1;
        }
        return -1L;
    }

    /**
     * The target of the {@code bl}/{@code b} at {@code site}, or 0 if that word is not an unconditional
     * immediate branch. A64: {@code bl} is opcode 0b100101, {@code b} is 0b000101, both with a signed 26-bit
     * word displacement.
     */
    private static long decodeTarget(long site)
    {
        int w = Magic.load32(site);
        int op = w >>> 26;
        if (op != 0x25 && op != 0x05)
        {
            return 0L;
        }
        int imm = w & 0x03FFFFFF;
        if ((imm & 0x02000000) != 0)
        {
            imm = imm | 0xFC000000;                      // sign-extend bit 25
        }
        return site + (long) imm * 4L;
    }

    /**
     * Re-decode every recorded site and compare against the recorded target. O(1) per edge; the block-registry
     * lookup that separates "stale" from "wrong" is linear, so it runs only for the sites that disagree.
     */
    static void verify()
    {
        okCount = 0;
        retiredCount = 0;
        danglingCount = 0;
        staleCount = 0;
        reusedCount = 0;
        wrongTargetCount = 0;
        wtSite = 0L;
        long i = 0;
        while (i < n)
        {
            long site = Magic.load64(TAB + i * 16L);
            long want = Magic.load64(TAB + i * 16L + 8L);
            long got = site == 0L ? 0L : decodeTarget(site);
            if (site == 0L)
            {
                i += 1;                                  // pruned slot: its block was freed, edge is gone
                continue;
            }
            if (want == 0L)
            {
                retiredCount += 1;                       // superseded by a later patch of the same site
            }
            else if (got == want)
            {
                okCount += 1;
                if (Heap.codeBlockFreeAt(want) == 1)
                {
                    danglingCount += 1;                  // live caller -> freed callee: the #146 signature
                }
            }
            else if (Heap.codeBlockFreeAt(site) == 1)
            {
                staleCount += 1;                         // the caller's own buffer was swept: edge is dead
            }
            else if (got == 0L)
            {
                reusedCount += 1;                        // not a branch any more: zeroed, or block reused
            }
            else
            {
                wrongTargetCount += 1;                   // a branch, elsewhere -- the only alarming case
                if (wtSite == 0L)
                {
                    wtSite = site;
                    wtWant = want;
                    wtGot = got;
                }
            }
            i += 1;
        }
    }

    /** One census line: the edge count, the verification split, and whether the table held. */
    static void report()
    {
        verify();
        Uart.write(Magic.bytes("  edges n="));
        VM.printDec((int) n);
        Uart.write(Magic.bytes(" ok="));
        VM.printDec((int) okCount);
        Uart.write(Magic.bytes(" pruned="));
        VM.printDec((int) prunedCount);
        Uart.write(Magic.bytes(" DANGLING="));
        VM.printDec((int) danglingCount);
        Uart.write(Magic.bytes(" retired="));
        VM.printDec((int) retiredCount);
        Uart.write(Magic.bytes(" stale="));
        VM.printDec((int) staleCount);
        Uart.write(Magic.bytes(" reused="));
        VM.printDec((int) reusedCount);
        Uart.write(Magic.bytes(" WRONGTGT="));
        VM.printDec((int) wrongTargetCount);
        Uart.write(Magic.bytes(" nonArena="));
        VM.printDec((int) nonArena);
        Uart.write(Magic.bytes(" dropped="));
        VM.printDec((int) dropped);
        Uart.putc(0x0A);
        if (wtSite != 0L)
        {
            Uart.write(Magic.bytes("    wrongTgt site="));
            VM.printHex(wtSite);
            Uart.write(Magic.bytes(" want="));
            VM.printHex(wtWant);
            Uart.write(Magic.bytes(" got="));
            VM.printHex(wtGot);
            Uart.putc(0x0A);
        }
    }
}
