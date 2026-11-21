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
import magic.DataReference;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u2;

/**
 * The class registry: every loaded class's {@link RVMClass} ({@code clTab}), and the indexes that find one by name
 * ({@code clBucket}), by Type node ({@code ctBucket}) and by blob -- what JikesRVM keeps as the {@code
 * TypeReference} table. {@code clTab} is a GC root (it is a static). Split out of {@link Loader}.
 */
final class ClassRegistry
{
    private ClassRegistry()
    {
    }

    // Class registry: per loaded class, what another class needs to `new` it and
    // dispatch through it — its name (base+offset), TIB, and instance-field count.
    static final int MAXCLASS = 4096;
    // The class registry, reified: one RVMClass per loaded class (base/nameOff/tib/type/statics/fieldCount/
    // vtCount/vtStart/superReg/modifiers/isIface). The direct-interface list (clIfaceReg/clIfaceRegN) and the
    // <clinit> dependency arrays (clDep*) stay separate.
    // Two-phase load (structure then bodies): phase A registers every class's structure (Type, TIB, fields,
    // vtable SLOT numbering, interface slots) in super/interface-first order (acyclic); phase B compiles all
    // method bodies + builds the TIBs, by which point every cross-class new/field/vtable/itable/cast target is
    // registered -> load order no longer matters (only method-CALL cycles remain, handled by patchRelocs).
    static RVMClass[] clTab;
    static int clCount;
    // WHERE the refill's time goes, as plain int counters rather than timers -- two readCNTPCT_EL0 per
    // itable entry was measured as noise against 19s and a visible share of 613ms, the lesson this file
    // already paid for once. These rank the three candidates inside a visit: the registry scan
    // (grows with everything loaded), the closure walk (bounded by the class's own hierarchy), and the
    // per-slot refill.
    static int rfTypeSteps;                             // regOfType/classRegByType registry comparisons
    // AND THE SECOND SCAN IN THE SAME LOOP, which the first cut of this instrument did not count -- exactly
    // the mistake the unres arm made one increment ago. Every MISS falls into `reportZeroCellBind`, whose
    // first act is `regBySigU`: a linear walk of all `clCount` classes with a utf8EqAt an entry, to decide
    // whether the miss is worth reporting. The suite says 100% of sites miss, so that walk runs on every
    // site of every batch -- a second growing table beside the first, and `psSteps` is blind to it.
    // Counted for ALL callers (there are nine); this loop is the one that runs per site per batch.
    static long rbCalls;                                // regBySigU calls (all callers)
    static long rbSteps;                                // ... class-registry entries compared inside them
    /** Class-registry index of the class whose name Utf8 is at {@code nameOff} in gView.base, or -1. */
    /** {@link #classRegByName} for a name in an ARBITRARY blob, not the one the parse cursor happens to be on.
     *  A clinit dependency is named in the DEPENDENT's constant pool, so its offset is against that blob. */
    /**
     * Name hash index over the CLASS registry, so "which registry entry is this class" is a PROBE rather
     * than a linear walk of all {@code clCount} entries with a Utf8 compare against each.
     *
     * <p>MEASURED, and by the increment that could not finish without it. `statT`'s residue after the static
     * registry was indexed is 189.764ms across 580 sites -- 327us a site, absurd for a hash probe -- because
     * every one of those misses calls {@link Loader#reportZeroCellBind}, whose FIRST act is this scan, run purely
     * to decide whether the miss is worth PRINTING. 580 sites x 1782 classes is ~1,034k utf8EqAt calls,
     * which at this boot's own measured ~0.18us a call is ~186ms against that 189.764ms. **The whole of
     * what is left in `statT` is a diagnostic gate.** Over all eight callers: `rb:n=5316 steps=3926k`.
     *
     * <p>THREE SPELLINGS OF ONE QUERY, and they each carried their own copy of the scan: {@code regBySigU}
     * (an absolute {@code {u2 len}{bytes}} run), {@code classRegByName} (an offset into {@code gView.base}) and
     * this one (base + offset). They differ only in how the key bytes are addressed, so all three are now
     * this body -- the same "one call site's fix, several timers" shape the phase-B publish and the static
     * index both had. ({@code classRegByNameBytes}, keyed on a VM-side {@code byte[]}, is left alone: a
     * different key shape, and its callers are the seeds, which latch.)
     *
     * <p>THE KEY IS ALREADY IN HAND AND COSTS NOTHING TO BUILD: {@code RVMClass.nameHash} is folded once at
     * registration -- it exists because the imap refill was re-folding it per probe -- so the index is built
     * from a field rather than by re-walking any name. The PROBE still folds its key, once, where the scan
     * folded nothing and compared everything.
     *
     * <p>Sound by construction rather than by a claim about when work may be skipped: {@code clTab[clCount]}
     * is written and {@code clCount} incremented immediately after, at BOTH registration sites (checked --
     * the interface path and {@code registerClassStructure}), and no entry is ever re-pointed. **THE LOWEST
     * MATCHING INDEX STILL WINS**: the scan returned the first match, head-insertion makes the chain
     * descending, so it is searched for the MINIMUM. Two entries can share a name only when a class is
     * registered twice -- the `lifecycle DIFF` shape this file already records -- and silently preferring
     * the later one would change which class every caller of this resolves to.
     *
     * <p>The watermark resets BESIDE the table in {@code resetLoader}, not only via the "went backwards"
     * check here: a watermark that outlives the table it indexes under-marks SILENTLY, and {@code clCount}
     * returning to the same value after a reset would slip past that check alone.
     */
    private static final int CLTAB = 8192;               // power of two > MAXCLASS; chained
    static int[] clBucket;                       // name hash -> first entry index, -1 when empty
    private static int[] clNext;                         // entry index -> next entry with the same hash
    static int clIndexed;
    private static final int CTMEMO = 4096;
    private static int[] ctMemo;                         // type hash -> clTab index, validated on every read
    // ---- Type -> class-registry index ---------------------------------------------------------------
    // Both spellings of this query (`regOfType` and `classRegByType`) walked all `clCount` entries, and
    // `clCount` grows with every class loaded all boot. `refillImaps` asks it once per itable-directory
    // ENTRY, per visited imap, per batch -- so it was O(everything loaded) on a path that runs every batch.
    // TENTH instance of this file's most common defect, and the FIRST one ranked by counters before being
    // believed: over a 65-batch suite the refill's four step counters read **type=264k against fill=39k,
    // hole=23k and clos=4k**, so this walk is 81% of the refill and the only one of the four that grows
    // with the registry.
    //
    // SOUND BY CONSTRUCTION, not by a claim about when work may be skipped -- the distinction that separates
    // this from the `virt` attempt that silently under-marked half a closure. `clTab[i].type` is written
    // exactly ONCE, at index `clCount`, immediately BEFORE `clCount` is incremented (checked at both
    // registration sites, the interface arm and the class arm). No entry is ever re-pointed at a different
    // Type, so an append-only index can never answer for bytes that have changed underneath it.
    private static final int CTTAB = 8192;              // power of two, > MAXCLASS so chains stay short
    static int[] ctBucket;                      // hash -> newest entry with that hash, -1 = empty
    private static int[] ctNext;                        // chain link, parallel to clTab
    private static int ctIndexed;                       // clTab[0, ctIndexed) is in the index

    /** Class-registry index of the class with this exact name, or -1. */
    static int classRegByNameBytes(byte[] name)
    {
        int r = 0;
        while (r < clCount)
        {
            if (clTab[r] != null && Utf8.utf8IsAtBase(clTab[r].base.toAddress(), clTab[r].nameOff, name))
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /** Class-registry index of the class whose Type node is {@code type}, or -1. */
    static int classRegByType(Address type)
    {
        if (type.isZero())
        {
            return -1;
        }
        return ctProbe(type);
    }

    /** Class-registry index of the class loaded from blob {@code base}, or -1. */
    static int classRegByBlob(Address base)
    {
        int r = 0;
        while (r < clCount)
        {
            if (clTab[r] != null && clTab[r].base.EQ(DataReference.fromAddress(base)))
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /** Registered class-record count, for {@link CodeCompact}'s precise reference enumeration. */
    static int classRegCount()
    {
        return clCount;
    }

    /** The class record at {@code i}, or null. Package-visible for the same reason. */
    static RVMClass classRegAt(int i)
    {
        return clTab == null || i < 0 || i >= clTab.length ? null : clTab[i];
    }

    private static void buildClIndex()
    {
        if (clBucket == null || clIndexed > clCount)     // rebuilt when resetLoader zeroes the table
        {
            clBucket = new int[CLTAB];
            clNext = new int[MAXCLASS];
            int b = 0;
            while (b < CLTAB)
            {
                clBucket[b] = -1;                        // allocArray does NOT zero its elements on this VM,
                b += 1;                                  //   and 0 is a valid entry index -- fill explicitly
            }
            clIndexed = 0;
        }
        while (clIndexed < clCount)
        {
            int hh = clTab[clIndexed].nameHash & (CLTAB - 1);   // folded once at registration; see RVMClass
            clNext[clIndexed] = clBucket[hh];
            clBucket[hh] = clIndexed;
            clIndexed += 1;
        }
    }

    /** Registry index of the class named at {@code base + nameOff}, or -1. The one body behind all three. */
    static int classRegAt(DataReference base, int nameOff)
    {
        if (nameOff == 0 && base.isZero())
        {
            return -1;
        }
        rbCalls += 1;
        buildClIndex();
        // The SAME predicate the scan used -- the index only narrows what it is applied to, so a hit is the
        // same entry the scan would have found and a miss still answers -1.
        int best = -1;
        int k = clBucket[Utf8.utf8Hash(base.toAddress(), nameOff) & (CLTAB - 1)];
        while (k >= 0)
        {
            rbSteps += 1;
            if (Loader.utf8EqAt(base.toAddress(), nameOff, clTab[k].base.toAddress(), clTab[k].nameOff))
            {
                if (best < 0 || k < best)
                {
                    best = k;                            // lowest index wins, as the scan's first match did
                }
            }
            k = clNext[k];
        }
        return best;
    }

    static int classRegByNameAt(Address base, int nameOff)
    {
        if (nameOff == 0)
        {
            return -1;
        }
        return classRegAt(DataReference.fromAddress(base), nameOff);
    }

    static int classRegByName(int nameOff)
    {
        if (nameOff == 0)
        {
            return -1;
        }
        return classRegAt(Loader.gView.base, nameOff);               // Loader.gView.base + offset: the same query, one spelling of three
    }

    /**
     * The clTab index of the class whose Type is {@code type}, or {@code clCount} if none -- what the linear
     * scans it replaces left in {@code ci}. A validated memo: an entry is used only if that class's type IS
     * {@code type}, so a stale or torn slot costs a scan, never a wrong answer while each Type names one class.
     */
    static int classIndexByType(Address type)
    {
        long bits = type.toLong();
        int h = (int) ((bits >>> 4) ^ (bits >>> 16)) & (CTMEMO - 1);
        int[] m = ctMemo;
        if (m != null)
        {
            int c = m[h];
            if (c >= 0 && c < clCount && clTab[c].type.EQ(DataReference.fromAddress(type)))
            {
                return c;
            }
        }
        int ci = 0;
        while (ci < clCount && clTab[ci].type.NE(DataReference.fromAddress(type)))
        {
            ci += 1;
        }
        if (ci < clCount)
        {
            if (m == null)
            {
                int[] fresh = new int[CTMEMO];
                int k = 0;
                while (k < CTMEMO)
                {
                    fresh[k] = -1;
                    k += 1;
                }
                ctMemo = fresh;
                m = fresh;
            }
            m[h] = ci;
        }
        return ci;
    }

    /** Class-registry index of the class named by a {@code new}/type {@code Class} entry, or -1. */
    static int classRegOf(int classIdx)
    {
        int nameOff = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[classIdx]))];   // Class entry -> name Utf8 offset
        // The class-name index (classRegAt): the same predicate as the scan this was, and the LOWEST matching
        // index, which is what the scan's first match answered. Profiled on InvokeGapProbe after #450 with
        // scripts/qprof-tally.pl's caller view: the scan was 6.1% of the busy core.
        return classRegAt(Loader.gView.base, nameOff);
    }

    /**
     * The superclass registry index of {@code reg}, RE-RESOLVED BY NAME when registration left it -1.
     *
     * <p>{@code superReg} is resolved ONCE, at registration, and is -1 for ever if the superclass was not
     * registered yet -- the ordinary case for a demand-loaded pair, where the subclass arrives first. Every
     * walker that read the field raw therefore STOPPED AT THE FIRST SUCH LINK, and a chain walk that stops
     * early fails exactly like one that was never written: it reports the member missing.
     *
     * <p>THIS HAD BEEN FIXED IN ONE PLACE AND LEFT EVERYWHERE ELSE. `ensureClinit` grew the by-name
     * re-resolution when a skipped superclass initializer was traced to it; the other seven chain walks --
     * vtable slot numbering, the late-virtual walk, the interface collection, field offsets, the clinit-dep
     * drain and both interface-closure walks -- kept the raw read. It surfaced as
     * `LINK FAILED MethodBasedTestDescriptor.cleanUp`: the interface walk collected that class's OWN
     * interfaces, stopped at a stale -1, and never reached `Node` on its superclass, where the default body
     * lives. A lookup that does not walk the whole chain has now been the bug FOUR times in this file.
     *
     * <p>The blob does not move, so the stored name offset stays valid; the result is cached, so a chain is
     * re-resolved at most once per class. Self-reference answers -1 rather than looping.
     */
    static int superRegOf(int reg)
    {
        if (clTab == null || reg < 0 || reg >= clCount || clTab[reg] == null)
        {
            return -1;
        }
        int sup = clTab[reg].superReg;
        if (sup < 0 && clTab[reg].superNameOff != 0)
        {
            sup = classRegByNameAt(clTab[reg].base.toAddress(), clTab[reg].superNameOff);
            clTab[reg].superReg = sup;
        }
        return sup == reg ? -1 : sup;
    }

    /** Registry index of {@code obj}'s class, or -1 (null, or a raw array carrying no Type). */
    static int regOfObject(ObjectReference obj)
    {
        if (obj.isNull())
        {
            return -1;
        }
        long tib = obj.toAddress().plus(0L).loadLong();
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return -1;                                  // raw array: no Type node to key on
        }
        return regOfType(Address.fromLong(tib).loadAddress());            // TIB[0] = Type
    }

    /**
     * Loaded-class-registry index of the class named {@code want}, or -1 if it isn't loaded.
     *
     * <p>THROUGH THE CLASS-NAME INDEX, not a scan. This was a linear walk of every registered class (~3,000 in a
     * TLS program) with a byte compare at each, and it is on per-EXECUTION paths: resolveUnresolvedNew (a
     * deferred `new`) and resolveLinkTarget both called it twice every time. MEASURED (qprof, SslEngineProbe,
     * 2026-11-06): those two were the top callers of the byte-compare leaves in a warm TLS session. The hash is
     * the FNV-1a fold {@link Utf8#utf8Hash} applies to a Utf8 run, so it lands in the bucket {@link RVMClass#nameHash}
     * filed the class in, and the chain walk keeps the LOWEST matching index -- the entry the scan returned.
     */
    static int classIndexByName(byte[] want)
    {
        if (clTab == null || clCount == 0)
        {
            return -1;
        }
        buildClIndex();
        int h = 0x811C9DC5;
        int j = 0;
        while (j < want.length)
        {
            h = (h ^ (want[j] & 0xFF)) * 0x01000193;
            j += 1;
        }
        int best = -1;
        int k = clBucket[h & (CLTAB - 1)];
        while (k >= 0)
        {
            if ((best < 0 || k < best) && Utf8.utf8IsAtBase(clTab[k].base.toAddress(), clTab[k].nameOff, want))
            {
                best = k;
            }
            k = clNext[k];
        }
        return best;
    }

    /** Registry index of the loaded class/interface whose Type node is {@code type}, or -1. */
    static int regOfType(Address type)
    {
        return ctProbe(type);
    }

    /** Hash a Type NODE ADDRESS. Types come from {@code allocData}, so the low bits are always 0 -- hashing
     *  the raw address would drop every entry into a handful of buckets. */
    private static int typeHashOf(Address type)
    {
        int h = (int) (type.toLong() >> 3);
        h = h * 0x9E3779B1;
        h = h ^ (h >>> 15);
        return h & (CTTAB - 1);
    }

    /** Bring the index up to {@code clCount}. Append-only: a class is indexed once, when it is first seen. */
    private static void ctIndexTo()
    {
        if (ctBucket == null)
        {
            ctBucket = new int[CTTAB];
            ctNext = new int[MAXCLASS];
            // allocArray does NOT zero its elements on this VM (QEMU hands out zeroed DRAM and a Pi at cold
            // power-on does not), so the empty marker is written explicitly -- garbage here would send a
            // probe down a chain of arbitrary indices rather than reporting an empty bucket.
            int b = 0;
            while (b < CTTAB)
            {
                ctBucket[b] = -1;
                b += 1;
            }
            ctIndexed = 0;
        }
        while (ctIndexed < clCount)
        {
            int h = typeHashOf(clTab[ctIndexed] != null ? clTab[ctIndexed].type.toAddress() : Address.zero());
            ctNext[ctIndexed] = ctBucket[h];
            ctBucket[h] = ctIndexed;
            ctIndexed += 1;
        }
    }

    /** The registry index the linear scan would have returned for {@code type}, or -1. */
    private static int ctProbe(Address type)
    {
        ctIndexTo();
        int best = -1;
        int i = ctBucket[typeHashOf(type)];
        while (i >= 0)
        {
            rfTypeSteps += 1;
            // The LOWEST matching index still wins, which the scan gave for free and a chain does not:
            // head-insertion makes the chain DESCENDING, so it is searched for the minimum rather than
            // stopped at the first hit. Same care `findPdByName`'s index needed.
            if (clTab[i] != null && clTab[i].type.EQ(DataReference.fromAddress(type)) && (best < 0 || i < best))
            {
                best = i;
            }
            i = ctNext[i];
        }
        return best;
    }
}
