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
import magic.Magic;

import static vm.ClassBytes.u2;

/**
 * The global method registry: one {@link RVMMethod} per method the VM knows about ({@code rgTab}), the BODYLESS
 * (abstract/native) side table, and the (class, name) hash index -- so a call in one class can link to a method
 * compiled in another (JikesRVM: the {@code MemberReference}/{@code RVMMethod} tables). Split out of
 * {@link Loader}.
 */
final class MethodRegistry
{
    private MethodRegistry()
    {
    }

    // Global method registry across all loaded classes, so a call in one class can
    // link to a method compiled in another. Each entry captures where its class /
    // name / descriptor Utf8 bytes live (all in that class's blob) plus its buffer.
    static final int MAXREG = 24576;
    static RVMMethod[] rgTab;   // global method registry (reified: one RVMMethod per compiled method)
    static RVMMethod[] bdTab;   // BODYLESS methods (abstract/native, no Code): reflection-only, never linked
    static int bdCount;
    /** A reflection index at or above this names {@code bdTab[idx - BODYLESS]} instead of a registry slot. */
    static final int BODYLESS = 1 << 24;
    static int rgCount;
    // ----- method-registry hash index ---------------------------------------
    /**
     * Name-keyed hash index over the method registry, so {@link DynamicLinker#globalBufByRef} is a PROBE rather than a
     * linear scan of every registered method.
     *
     * <p>MEASURED, not guessed: {@code patchRelocs} re-walks every reloc site at the end of every batch, and
     * each site called {@code globalBufByRef}, which compared three Utf8 strings against all {@code rgCount}
     * entries. Both factors grow with every class loaded, so the cost is quadratic in the load -- on a
     * launcher boot it went 0.49s -> 2.10s per batch between batches 25 and 100, by which point `patch` was
     * 130s of a 445s load for 1595 classes.
     *
     * <p>Keyed on the method NAME alone, and chained: names repeat ({@code <init>}, {@code run}, {@code get}),
     * so a chain walk still compares class+name+descriptor exactly as the scan did. The predicate is
     * UNCHANGED -- this only narrows what it is applied to, so a hit is the same entry the scan would have
     * found and a miss still falls through to the super-chain and stub tiers below.
     *
     * <p>Same fix, and the same reasoning, as the name index that took {@code pull} from 6,664ms to 1,539ms
     * in the demand-load arc.
     */
    static final int RGTAB = 32768;              // power of two > MAXREG; chained, so a full table only lengthens chains
    static int[] rgBucket;                       // name hash -> first registry index, -1 when empty
    static int[] rgNext;                         // registry index -> next entry with the same name hash
    static int rgIndexed;                        // entries already linked in (the registry only grows)
    static int gbMiss;                                  // count of unresolved cross-class calls (debug)

    /** Index into m*[] of the method whose bytecode is at {@code code}, or -1. */
    private static int methodIndexOf(long code)
    {
        int i = 0;
        while (i < Loader.mCount)
        {
            if (Loader.mCode[i] == code)
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Link every registry entry added since the last call into the name index. */
    static void buildRegIndex()
    {
        if (rgBucket == null)
        {
            rgBucket = new int[RGTAB];
            rgNext = new int[MAXREG];
            int i = 0;
            while (i < RGTAB)
            {
                rgBucket[i] = -1;
                i += 1;
            }
            rgIndexed = 0;
        }
        // INCREMENTAL: the registry only ever grows and an entry's name never changes, so already-indexed
        // entries stay valid. Rebuilding from 0 each batch would re-introduce the linear pass this exists to
        // remove. resetLoader zeroes rgCount and rgIndexed together (see there).
        while (rgIndexed < rgCount)
        {
            int h = Utf8.utf8Hash2(rgTab[rgIndexed].base, rgTab[rgIndexed].classOff,
                              rgTab[rgIndexed].base, rgTab[rgIndexed].nameOff) & (RGTAB - 1);
            rgNext[rgIndexed] = rgBucket[h];
            rgBucket[h] = rgIndexed;
            rgIndexed += 1;
        }
    }

    // ----- cross-class linking (global method registry) --------------------
    /** Register a compiled method so other classes can link to it by class+name+descriptor. */
    static void register(Address base, int classOff, int nameOff, int descOff, long buf, long lineTab, Address srcAddr, int access)
    {
        if (rgCount >= MAXREG) { Loader.capHalt(Magic.bytes("MAXREG-method"), rgCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        rgTab[rgCount] = new RVMMethod();
        rgTab[rgCount].base = base;
        rgTab[rgCount].classOff = classOff;
        rgTab[rgCount].nameOff = nameOff;
        rgTab[rgCount].descOff = descOff;
        rgTab[rgCount].buf = buf;
        rgTab[rgCount].line = lineTab;
        rgTab[rgCount].src = srcAddr;
        rgTab[rgCount].access = access;
        rgCount += 1;
    }

    /** Register every compiled method of the current class (walk its methods table). */
    static void registerAll()
    {
        Address p = Loader.gMethodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            long code = Loader.findCode(Loader.gView.base, p.plus(8), attrs);
            if (code != 0L)
            {
                int mi = methodIndexOf(code);
                if (mi >= 0)
                {
                    register(Loader.gView.base, Loader.gThisNameOff, Loader.gView.cp[u2(p.plus(2))], Loader.gView.cp[u2(p.plus(4))], Loader.mBuf[mi], Loader.mLine[mi], Loader.mSrc.get(mi), u2(p));
                }
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
    }

    /** Buffer of the method named by Methodref {@code idx} in another loaded class (registry lookup). */
    static long globalBuf(int idx)
    {
        int classOff = Loader.refClassNameOff(idx);
        int nameOff = Loader.mrefNameOff(idx);
        int descOff = Loader.mrefDescOff(idx);
        // The registry through its class+name index (rgBucket), as bufBySigU: the scan answered the LOWEST
        // matching index, and those chains are newest-first, so walk the whole chain and keep the oldest hit.
        // The scan was 4.0% of the busy core on InvokeGapProbe after #450 (qprof-tally.pl caller view).
        buildRegIndex();
        int best = -1;
        int i = rgBucket[Utf8.utf8Hash2(Loader.gView.base, classOff, Loader.gView.base, nameOff) & (RGTAB - 1)];
        while (i >= 0)
        {
            if (Loader.utf8EqAt(Loader.gView.base, classOff, rgTab[i].base, rgTab[i].classOff)
                    && Loader.utf8EqAt(Loader.gView.base, nameOff, rgTab[i].base, rgTab[i].nameOff)
                    && Loader.utf8EqAt(Loader.gView.base, descOff, rgTab[i].base, rgTab[i].descOff))
            {
                best = i;
            }
            i = rgNext[i];
        }
        if (best >= 0)
        {
            return rgTab[best].buf;
        }
        gbMiss += 1;
        if (Loader.reportUnresolved != 0)                      // probe mode: name the unembedded reference (surface)
        {
            Uart.write(Magic.bytes("    ? "));
            Loader.writeName(Loader.gView.base.plus(classOff + 2), u2(Loader.gView.base.plus(classOff)));
            Uart.putc(0x2E);                            // '.'
            Loader.writeName(Loader.gView.base.plus(nameOff + 2), u2(Loader.gView.base.plus(nameOff)));
            Uart.putc(0x0A);
        }
        return 0L;                                      // unresolved: an unloaded class or a native
    }
}
