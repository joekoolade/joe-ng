/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-21
 */
package vm;

import magic.Address;
import magic.Uninterruptible;

/**
 * What a stack walk needs to know about one frame (frames/GC arc, S5): its size and reference map by return
 * address, the call site's slot types in a {@code compiler.RefMaps} map, and for a stub frame, which of its words
 * hold references. Shared by the precise root walk ({@link StackRoots}) and the shadow check ({@link
 * GcMapShadow}). Results are left in static fields: only the collector calls this, on one core, with the world
 * stopped.
 *
 * <p>Lookups are cached per collection by pc. The frame tables are searched linearly, and a stack repeats the same
 * few hundred return addresses, so the cache is what keeps a precise walk near the cost of a conservative scan.
 */
final class FrameMaps implements Uninterruptible
{
    private FrameMaps()
    {
    }

    // ---- the last lookup ------------------------------------------------------------------------------------
    static long fSize;           // frame size, 0 = none (frameless, or no entry)
    static long fMap;            // map address, a stub descriptor (StubFrames.isStubDesc), 0 = none, -1 = no entry
    static long fStart;          // start of the code range, 0 = none

    private static final int CACHE = 1024;
    private static long[] cPc;
    private static long[] cSize;
    private static long[] cMap;
    private static long[] cStart;
    static long lookups;
    static long misses;

    /** Allocate the cache (a collection must not allocate). Without it every lookup searches the tables. */
    static void init()
    {
        if (cPc == null)
        {
            cPc = new long[CACHE];
            cSize = new long[CACHE];
            cMap = new long[CACHE];
            cStart = new long[CACHE];
        }
    }

    /** Empty the cache: the tables may have changed since the last collection (compiles, sweeps, batch rewinds). */
    static void beginCollection()
    {
        if (cPc == null)
        {
            return;
        }
        int i = 0;
        while (i < CACHE)
        {
            cPc[i] = 0L;
            i += 1;
        }
    }

    /** Look up the frame covering {@code pc} (a return address minus 4) into {@link #fSize}/{@link #fMap}/{@link
     *  #fStart}. */
    static void lookup(long pc)
    {
        lookups += 1L;
        int h = (int) ((pc >>> 2) ^ (pc >>> 13)) & (CACHE - 1);
        if (cPc != null && cPc[h] == pc && pc != 0L)
        {
            fSize = cSize[h];
            fMap = cMap[h];
            fStart = cStart[h];
            return;
        }
        misses += 1L;
        Address a = Address.fromLong(pc);
        fSize = VM.frameSizeAt(a);
        fMap = VM.gcMapAt(a);
        fStart = VM.codeStartAt(a);
        if (cPc != null)
        {
            cPc[h] = pc;
            cSize[h] = fSize;
            cMap[h] = fMap;
            cStart[h] = fStart;
        }
    }

    // ---- a RefMaps map: the site for a return address --------------------------------------------------------
    static int hNLoc;
    static int hNStk;
    static long hLocBase;
    static long hOpBase;
    static long hOutBase;
    static long hOutEnd;
    static long sBits;           // the site's unique map (2 bits a slot: locals, then operand slots)
    static int sLive;            // operand slots the call's spill wrote
    static int sDepth;           // the bytecode's entering depth
    static int mapStatus;        // 0 = site found, 1 = bad magic, 2 = no site for the return address, 3 = bad index

    /**
     * Decode map {@code ma} (of the code starting at {@code start}) for return address {@code ret}. True when the
     * site is found; the header and the site's fields are then in the {@code h*}/{@code s*} fields, and {@link
     * #slotType} reads its bits. False with {@link #mapStatus} saying why.
     */
    static boolean site(long ma, long start, long ret)
    {
        Address map = Address.fromLong(ma);
        if (map.loadInt() != 0x50414D47)                       // RefMaps.MAGIC
        {
            mapStatus = 1;
            return false;
        }
        int nSites = map.plus(4L).loadInt();
        hNLoc = map.plus(8L).loadInt();
        hNStk = map.plus(12L).loadInt();
        hLocBase = map.plus(16L).loadInt();
        hOpBase = map.plus(20L).loadInt();
        int bitWords = map.plus(24L).loadInt();
        int nUnique = map.plus(28L).loadInt();
        hOutBase = map.plus(32L).loadInt();
        hOutEnd = hOutBase + map.plus(36L).loadInt() * 8L;
        int retWord = (int) ((ret - start) >> 2);
        Address sites = map.plus(40L);
        int lo = 0;
        int hi = nSites - 1;
        int found = -1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int r = sites.plus(mid * 8L).loadInt();
            if (r == retWord)
            {
                found = mid;
                break;
            }
            if (r < retWord)
            {
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }
        if (found < 0)
        {
            mapStatus = 2;
            return false;
        }
        int sw = sites.plus(found * 8L + 4L).loadInt();
        int idx = sw & 0xFFFF;
        sLive = (sw >>> 16) & 0xFF;
        sDepth = sw >>> 24;
        if (idx >= nUnique)
        {
            mapStatus = 3;
            return false;
        }
        sBits = sites.plus(nSites * 8L + (long) idx * bitWords * 4L).toLong();
        mapStatus = 0;
        return true;
    }

    /** Slot {@code s}'s 2-bit type in the site's map: 0 dead, 1 reference, 2 raw, 3 data reference. */
    static int slotType(int s)
    {
        int bit = 2 * s;
        int word = Address.fromLong(sBits).plus((long) (bit >> 5) * 4L).loadInt();
        return (word >>> (bit & 31)) & 3;
    }

    // ---- stub frames ----------------------------------------------------------------------------------------
    /** Which saved argument registers of a resolve trampoline hold references: bit r = x_r (r < 64). */
    static long tMask;
    /** How many argument registers the call uses. Past 16, x15 points at the caller's out-args (args 15..). */
    static int tN;
    /** True when the descriptor is unknown and every register is treated as a possible reference. */
    static boolean tUntyped;

    /** Argument-register reference mask of a call to descriptor {@code descU} ({u2 len}{bytes}): bit r = x_r. Sets
     *  {@link #argN} to the number of registers. */
    static long argRefMask(Address descU, boolean receiver)
    {
        long mask = 0L;
        int r = 0;
        if (receiver)
        {
            mask = 1L;
            r = 1;
        }
        Address p = descU.plus(3L);                            // past the u2 length and '('
        Address end = descU.plus(2L + (long) descU.loadUnsignedByte() * 256L + descU.plus(1L).loadUnsignedByte());
        while (p.LT(end) && p.loadUnsignedByte() != 0x29)      // ')'
        {
            int c = p.loadUnsignedByte();
            if (c == 0x5B || c == 0x4C)                        // '[' or 'L': a reference
            {
                if (r < 64)
                {
                    mask = mask | (1L << r);
                }
                while (p.LT(end) && p.loadUnsignedByte() == 0x5B)
                {
                    p = p.plus(1);
                }
                if (p.LT(end) && p.loadUnsignedByte() == 0x4C)
                {
                    while (p.LT(end) && p.loadUnsignedByte() != 0x3B)
                    {
                        p = p.plus(1);
                    }
                }
            }
            p = p.plus(1);
            r += 1;
        }
        argN = r;
        return mask;
    }

    static int argN;

    /**
     * Type the saved arguments of a resolve trampoline of {@code kind} whose index is {@code idx}, into {@link
     * #tMask}/{@link #tN}. Where the index cannot say whether the callee takes a receiver (a link stub names a
     * method, not an invoke) or the resolver reorders the save area while it runs (indy inserts the target in
     * front), both readings are taken: a word marked that holds no reference is refused by the marker's
     * block-base check, so the union costs at most a little retention, never a wrong mark.
     */
    static void trampArgs(int kind, long idx)
    {
        tUntyped = false;
        Address d = Address.zero();
        if (kind == StubFrames.K_LAZY)
        {
            LazyMethod[] tab = LazyCompiler.lzTab;
            if (tab != null && idx >= 0L && idx < tab.length && tab[(int) idx] != null && tab[(int) idx].descOff != 0)
            {
                LazyMethod lm = tab[(int) idx];
                tMask = argRefMask(lm.blob.toAddress().plus(lm.descOff), lm.isStatic == 0);
                tN = argN;
                return;
            }
        }
        else if (kind == StubFrames.K_VIRTUAL)
        {
            if (VirtualDispatch.vsDesc != null && idx >= 0L && idx < VirtualDispatch.vsCount)
            {
                d = VirtualDispatch.vsDesc.get((int) idx);
                if (!d.isZero())
                {
                    tMask = argRefMask(d, true);
                    tN = argN;
                    return;
                }
            }
        }
        else if (kind == StubFrames.K_LINK || kind == StubFrames.K_MH)
        {
            if (kind == StubFrames.K_LINK)
            {
                d = DynamicLinker.linkDesc((int) idx);
            }
            else if (VirtualDispatch.vsDesc != null && idx >= 0L && idx < VirtualDispatch.vsCount)
            {
                d = VirtualDispatch.vsDesc.get((int) idx);
            }
            if (!d.isZero())
            {
                long a = argRefMask(d, false);                 // static: x0 is the first parameter
                long b = argRefMask(d, true);                  // ... or an instance method: x0 is the receiver
                tMask = a | b;
                tN = argN;
                return;
            }
        }
        else if (kind == StubFrames.K_INDY)
        {
            d = Indy.siteDesc((int) idx);
            if (!d.isZero())
            {
                long a = argRefMask(d, false);                 // the site's arguments, as the resolver finds them
                long b = argRefMask(d, true);                  // ... and shifted up, the target in x0, after
                tMask = a | b;
                tN = argN;
                return;
            }
        }
        tUntyped = true;                                       // no descriptor: every saved register may be one
        tMask = 0xFFFFL;
        tN = 16;
    }
}
