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

import magic.Address;

import static vm.ClassBytes.u2;

/**
 * The per-blob method-table cache the closure walk consults: each blob's methods parsed once into
 * name/descriptor/code tables ({@code mt*}), and the override "hit" tables ({@code hv*}) that answer which
 * subclass method a virtual call can reach ({@code matchLevel}, {@code matchDefaults}). Split out of
 * {@link Loader}.
 */
final class MethodTableCache
{
    private MethodTableCache()
    {
    }

    /**
     * Match the current class level (as left by {@link Loader#parseForMethods}) against the virtual pends, by walking
     * the level's OWN methods and looking each up in {@link Closure#buildPendIndex}'s table.
     *
     * <p>The direction is the point. Asking "for each pend, does this class define it?" is
     * {@code pends x methods} string compares per level, and the level count is
     * {@code instantiated classes x chain depth} -- with 699 pends over 75 classes that pass alone was 57% of
     * a batch's whole reachability mark. Asking "for each method, is it pended?" is one hash probe per method:
     * {@code methods} instead of {@code pends x methods}.
     *
     * <p>Semantics are unchanged. Methods are visited in declaration order and {@code virtResolved} is set on
     * first match, so a pend still binds to the first definition carrying Code, and the chain walk still stops
     * at the nearest one rather than also marking an override-shadowed super definition.
     */
    // ---- per-blob method-table cache ---------------------------------------------------------------------
    // matchLevel is called once per superclass level per instantiated class per round, and every call
    // re-derived the same immutable facts: a full parseConstPool, a walk of the method table, and an FNV hash
    // over each method's name AND descriptor. A blob's method table never changes, so build it once.
    private static final int MAXMETH = 65536;
    private static int[] mtName, mtDesc, mtHash;         // per method: Utf8 offsets (blob-relative) + sig hash
    private static long[] mtCode;                        // ... and its Code address, 0 for abstract/native
    private static int[] mtStart, mtBuilt;               // per blob: slice start, and 0=no 1=yes 2=did not fit
    static int[] mtLen;                          // per blob: method count
    private static int mtN;                              // flat-array high-water
    /**
     * {@link #matchLevel} over the cached table. Methods with no Code are skipped outright, which is exactly
     * what the parsing form does: it probes the bucket but only consumes the pend ({@code virtResolved}) when
     * {@code findCode} answers non-zero, so an abstract declaration never shadows a superclass's real body.
     */
    /**
     * PER-LEVEL HIT LISTS: which pend entries a level's methods MATCH, cached for ever.
     *
     * <p>resolveVirtuals walks every instantiated class's superclass chain, so a level shared by many
     * subclasses is re-matched once per DESCENDANT. Measured on a launcher batch: 25,129 level visits, only
     * 1,624 of which (6.5%) mark anything, and 325,000 method examinations costing 13.4s -- essentially all
     * of `virt`. The per-method probe is already cheap (~68 cycles of real time); what is expensive is doing
     * it 325,000 times.
     *
     * <p>WHY A NAIVE LEVEL MEMO IS UNSOUND, and this is the trap the ONE previous attempt at `virt` fell
     * into (it claimed work could be SKIPPED, and silently UNDER-MARKED HALF THE CLOSURE, reach 1040 -> 449,
     * cause never identified). matchLevelCached is NOT a pure function of (level, pend range): it sets
     * {@code virtResolved[q] = virtStamp} -- "nearest def; don't also mark a super's shadowed one" -- keyed
     * on the STARTING CLASS. If C extends B extends A and B overrides m, walking from C marks B.m and skips
     * A.m as shadowed; walking from D extends A must still mark A.m. Skipping the level because "someone
     * matched it at this pend range" loses that.
     *
     * <p>SO SPLIT THE WORK BY WHAT IS ACTUALLY INVARIANT. Whether method i of level b matches pend entry q
     * by name+descriptor is IMMUTABLE BY CONSTRUCTION: mtName/mtDesc are fixed by the classfile and a pend
     * entry's name/descriptor never change once added. That half is cached here FOR THE BATCH, and extended
     * only over pend entries added since (the chains are descending, so the walk stops at the watermark).
     * Not longer: pend indices and method-table slots are both per-batch, so {@link #resetMethodTables}
     * empties the lists. (They used to live for the whole launch -- see the measured damage there.)
     * The path-dependent half -- the virtResolved shadow check -- still runs on EVERY visit, against every
     * cached hit. No marking is skipped; only the searching is.
     */
    static int[][] hvQ;                          // per blob: pend indices its methods match
    static int[][] hvCodeIdx;                    // ... and which method-table slot matched
    static int[] hvLen;                          // used length per blob
    static int[] hvTo;                           // pend count this blob's hits are complete to

    /** Discard the method-table cache: blob addresses and indices are per batch, so the offsets go stale. */
    static void resetMethodTables()
    {
        if (mtName == null)                              // the flat arrays are scratch -- allocate once, reuse
        {
            mtName = new int[MAXMETH];
            mtDesc = new int[MAXMETH];
            mtHash = new int[MAXMETH];
            mtCode = new long[MAXMETH];
            mtStart = new int[Loader.MAXBLOB];
            mtLen = new int[Loader.MAXBLOB];
        }
        mtBuilt = new int[Loader.MAXBLOB];                      // only the per-blob flags need clearing (1024 ints)
        Closure.pdSuperCache = new int[Loader.MAXBLOB];
        mtN = 0;
        // THE VIRT HIT LISTS DIE WITH THE TABLE. A hit records a method-table SLOT (an index into the arrays
        // just reset) and a PEND index (pendN restarts at 0 every batch), so both halves are per-batch facts;
        // they were dropped only per LAUNCH (resetLoader). MEASURED on main (SslEngineProbe, QEMU 2026-11-07):
        // of 5,227,096 hits applied, 1,142,255 named a slot outside the blob's current slice -- addReach of
        // some other method's code -- and 53,741 more named a method whose signature no longer matched the
        // pend; 3,995 visits had hvTo above pendN, so the list was never extended. Silent because RTA
        // over-marking only compiles more and a missed target resolves late. Reset here: 0 of each.
        if (hvLen != null)
        {
            int k = 0;
            while (k < hvLen.length)
            {
                hvLen[k] = 0;
                hvTo[k] = 0;
                k += 1;
            }
        }
    }

    /**
     * {@link Closure#findMethodByRef} for pd blob {@code b}, through its {@link #ensureMethodTable} slice: the first
     * method in declaration order whose name and descriptor equal the ref's and that carries Code. Same answer
     * by construction -- the slice is the blob's method table in order, {@code mtCode} is the same
     * {@code findCode}, and the signature hash only rejects entries whose bytes differ. MEASURED (SslEngineProbe,
     * QEMU 2026-11-07): the parsing form was 1.6 s of the static pass over 108,227 matches, two Utf8 compares
     * and a skipAttributes per method walked; this is 0.48 s, and a checker running both found 0 mismatches.
     * Falls back to the parsing form when the flat arrays are full; the caller has {@code b} parsed.
     */
    static long findDeclaredCached(int b, Address refBase, int nameOff, int descOff)
    {
        if (!ensureMethodTable(b))
        {
            return Closure.findMethodByRef(refBase, nameOff, descOff);
        }
        int h = Utf8.sigHash(refBase, nameOff, descOff);
        Address blob = Loader.pdBase.get(b);
        int i = mtStart[b];
        int end = i + mtLen[b];
        while (i < end)
        {
            if (mtHash[i] == h && mtCode[i] != 0L
                    && Loader.utf8EqAt(refBase, nameOff, blob, mtName[i]) && Loader.utf8EqAt(refBase, descOff, blob, mtDesc[i]))
            {
                return mtCode[i];
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Build blob {@code b}'s method-table slice if absent. False if the flat arrays are full, in which case the
     * caller must fall back to parsing -- a cap that degrades to today's behaviour rather than failing.
     */
    static boolean ensureMethodTable(int b)
    {
        if (mtBuilt[b] == 1)
        {
            return true;
        }
        if (mtBuilt[b] == 2)
        {
            return false;
        }
        Loader.parseForMethods(Loader.pdBase.get(b), Loader.pdLen[b]);
        Address p = Loader.gMethodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        if (mtN + mcount > MAXMETH)
        {
            mtBuilt[b] = 2;
            return false;
        }
        mtStart[b] = mtN;
        mtLen[b] = mcount;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            int nameOff = Loader.gView.cp[u2(p.plus(2))];
            int descOff = Loader.gView.cp[u2(p.plus(4))];
            mtName[mtN] = nameOff;
            mtDesc[mtN] = descOff;
            mtHash[mtN] = Utf8.sigHash(Loader.gView.base, nameOff, descOff);
            mtCode[mtN] = Loader.findCode(Loader.gView.base, p.plus(8), attrs);
            mtN += 1;
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        mtBuilt[b] = 1;
        return true;
    }

    private static void ensureHitTables()
    {
        if (hvQ == null || hvQ.length < Loader.pdCount)
        {
            int[][] nq = new int[Loader.pdCount + 64][];
            int[][] nc = new int[Loader.pdCount + 64][];
            int[] nl = new int[Loader.pdCount + 64];
            int[] nt = new int[Loader.pdCount + 64];
            if (hvQ != null)
            {
                int k = 0;
                while (k < hvQ.length)
                {
                    nq[k] = hvQ[k];
                    nc[k] = hvCodeIdx[k];
                    nl[k] = hvLen[k];
                    nt[k] = hvTo[k];
                    k += 1;
                }
            }
            hvQ = nq;
            hvCodeIdx = nc;
            hvLen = nl;
            hvTo = nt;
        }
    }

    /** Record one (method-slot, pend) match for blob {@code b}, growing its hit list as needed. */
    private static void addHit(int b, int methIdx, int q)
    {
        if (hvQ[b] == null)
        {
            hvQ[b] = new int[8];
            hvCodeIdx[b] = new int[8];
            hvLen[b] = 0;
        }
        if (hvLen[b] >= hvQ[b].length)
        {
            int[] gq = new int[hvQ[b].length * 2];
            int[] gc = new int[hvQ[b].length * 2];
            int k = 0;
            while (k < hvLen[b])
            {
                gq[k] = hvQ[b][k];
                gc[k] = hvCodeIdx[b][k];
                k += 1;
            }
            hvQ[b] = gq;
            hvCodeIdx[b] = gc;
        }
        hvQ[b][hvLen[b]] = q;
        hvCodeIdx[b][hvLen[b]] = methIdx;
        hvLen[b] += 1;
    }

    static boolean matchLevelCached(int b)
    {
        ensureHitTables();
        if (hvTo[b] < Closure.pendN)                             // extend over pend entries added since last time
        {
            extendHits(b);
            hvTo[b] = Closure.pendN;
        }
        boolean grew = false;
        int h = 0;
        int n = hvLen[b];
        while (h < n)
        {
            int q = hvQ[b][h];
            // The path-dependent half, unchanged and still per VISIT: a nearer definition already claimed
            // this signature for this walk, or it is below this walk's watermark.
            if (q >= Closure.virtFrom && Closure.virtResolved[q] != Closure.virtStamp)
            {
                Closure.virtResolved[q] = Closure.virtStamp;             // nearest def; don't also mark a super's shadowed one
                grew = Closure.addReach(mtCode[hvCodeIdx[b][h]]) || grew;
            }
            h += 1;
        }
        return grew;
    }

    /** The old search, run ONCE per blob per pend growth, recording matches instead of applying them. */
    private static void extendHits(int b)
    {
        int from = hvTo[b];
        Address base = Loader.pdBase.get(b);
        int i = mtStart[b];
        int end = i + mtLen[b];
        while (i < end)
        {
            long code = mtCode[i];
            if (code != 0L)
            {
                int q = Closure.pvBucket[mtHash[i] & (Loader.PVTAB - 1)];
                while (q >= 0)
                {
                    // Early termination on a SORTED list: buildPendIndex head-inserts while iterating p
                    // ascending, so every chain is in DESCENDING q order. Once one entry is below the
                    // range already covered, every remaining entry in this chain is too.
                    if (q < from)
                    {
                        break;
                    }
                    if (Loader.utf8EqAt(Closure.pendBase.get(q), Closure.pendName[q], base, mtName[i])
                            && Loader.utf8EqAt(Closure.pendBase.get(q), Closure.pendDesc[q], base, mtDesc[i]))
                    {
                        addHit(b, i, q);                 // fixed for the batch: slot i and pend q are per-batch
                    }
                    q = Closure.pvNext[q];
                }
            }
            i += 1;
        }
    }

    static boolean matchLevel()
    {
        boolean grew = false;
        Address p = Loader.gp;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            int nameOff = Loader.gView.cp[u2(p.plus(2))];
            int descOff = Loader.gView.cp[u2(p.plus(4))];
            int q = Closure.pvBucket[Utf8.sigHash(Loader.gView.base, nameOff, descOff) & (Loader.PVTAB - 1)];
            while (q >= 0)
            {
                if (q >= Closure.virtFrom && Closure.virtResolved[q] != Closure.virtStamp
                        && Loader.utf8EqAt(Closure.pendBase.get(q), Closure.pendName[q], Loader.gView.base, nameOff)
                        && Loader.utf8EqAt(Closure.pendBase.get(q), Closure.pendDesc[q], Loader.gView.base, descOff))
                {
                    long code = Loader.findCode(Loader.gView.base, p.plus(8), attrs);
                    if (code != 0L)
                    {
                        Closure.virtResolved[q] = Closure.virtStamp;     // nearest def; don't also mark a super's shadowed one
                        grew = Closure.addReach(code) || grew;
                    }
                }
                q = Closure.pvNext[q];
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return grew;
    }

    /**
     * The {@link #matchLevel} inversion for {@link Closure#markDefaults}: mark every concrete method of the current
     * interface whose name+descriptor is pended. No nearest-definition rule here -- a default is marked
     * wherever it is found, and {@link Closure#addReach} dedupes -- so there is no {@code virtResolved} to consult.
     */
    static boolean matchDefaults()
    {
        boolean grew = false;
        Address p = Loader.gp;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            int nameOff = Loader.gView.cp[u2(p.plus(2))];
            int descOff = Loader.gView.cp[u2(p.plus(4))];
            int q = Closure.pvBucket[Utf8.sigHash(Loader.gView.base, nameOff, descOff) & (Loader.PVTAB - 1)];
            while (q >= 0)
            {
                if (q >= Closure.virtFrom
                        && Loader.utf8EqAt(Closure.pendBase.get(q), Closure.pendName[q], Loader.gView.base, nameOff)
                        && Loader.utf8EqAt(Closure.pendBase.get(q), Closure.pendDesc[q], Loader.gView.base, descOff))
                {
                    long code = Loader.findCode(Loader.gView.base, p.plus(8), attrs);
                    if (code != 0L)
                    {
                        grew = Closure.addReach(code) || grew;
                        break;                           // marked; other pends on the same method add nothing
                    }
                }
                q = Closure.pvNext[q];
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return grew;
    }
}
