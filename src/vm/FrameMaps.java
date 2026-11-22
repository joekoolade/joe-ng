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
import magic.Magic;
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

    /** Argument-register OBJECT-reference mask of a call to descriptor {@code descU} ({u2 len}{bytes}): bit r = x_r.
     *  Exact kinds (frames/GC arc S6d-2), as {@code ClassFile.fieldKind} decides a field's: a Java reference, {@code
     *  magic.ObjectReference} and the unboxed arrays are references; {@code magic.DataReference} goes in {@link
     *  #argDataMask} instead; the other {@code magic} types ({@code Address}, {@code Word}, {@code Offset}, {@code
     *  Extent}, {@code CodeAddress}) and primitives are raw. Sets {@link #argN} to the number of registers. */
    static long argRefMask(Address descU, boolean receiver)
    {
        long mask = 0L;
        long data = 0L;
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
            if (c == 0x5B)                                     // '[': an array, whatever its element
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
                    p = skipClassName(p, end);
                }
            }
            else if (c == 0x4C)                                // 'L'
            {
                int k = classKind(p.plus(1), end);
                if (r < 64 && k == 1)
                {
                    mask = mask | (1L << r);
                }
                else if (r < 64 && k == 3)
                {
                    data = data | (1L << r);
                }
                p = skipClassName(p, end);
            }
            p = p.plus(1);
            r += 1;
        }
        argN = r;
        argDataMask = data;
        return mask;
    }

    /** The {@code magic.DataReference} arguments of the last {@link #argRefMask}: bit r = x_r, marked one header
     *  below the payload. */
    static long argDataMask;

    /** From the 'L' at {@code p}, to its ';'. */
    private static Address skipClassName(Address p, Address end)
    {
        while (p.LT(end) && p.loadUnsignedByte() != 0x3B)
        {
            p = p.plus(1);
        }
        return p;
    }

    /** The kind of the class named at {@code p} (up to ';'): 1 = an object reference, 3 = a data reference, 0 = a raw
     *  {@code magic} word. */
    private static int classKind(Address p, Address end)
    {
        if (!namePrefix(p, end, Magic.bytes("magic/")))
        {
            return 1;
        }
        if (nameIs(p, end, Magic.bytes("magic/DataReference")))
        {
            return 3;
        }
        if (nameIs(p, end, Magic.bytes("magic/ObjectReference")) || nameIs(p, end, Magic.bytes("magic/AddressArray"))
            || nameIs(p, end, Magic.bytes("magic/ObjectReferenceArray"))
            || nameIs(p, end, Magic.bytes("magic/DataReferenceArray")) || nameIs(p, end, Magic.bytes("magic/CodeArray")))
        {
            return 1;
        }
        return 0;
    }

    private static boolean namePrefix(Address p, Address end, byte[] want)
    {
        int i = 0;
        while (i < want.length)
        {
            if (!p.plus(i).LT(end) || p.plus(i).loadUnsignedByte() != (want[i] & 0xFF))
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    private static boolean nameIs(Address p, Address end, byte[] want)
    {
        return namePrefix(p, end, want) && p.plus(want.length).LT(end) && p.plus(want.length).loadUnsignedByte() == 0x3B;
    }

    static int argN;

    /**
     * Type the saved arguments of a resolve trampoline of {@code kind} whose index word is {@code idx}, into {@link
     * #tMask} (object references), {@link #tData} (data references) and {@link #tN}. Exact since frames/GC arc
     * S6d-2: a link stub records whether its callee takes a receiver ({@link DynamicLinker#linkRecv}); an MH site's
     * {@code linkTo*} is static and every other one takes the handle in x0; the indy resolver sets {@link
     * #INDY_SHIFTED} in the index word once it has moved the arguments up and put the target in x0.
     */
    static void trampArgs(int kind, long idx)
    {
        tUntyped = false;
        tData = 0L;
        Address d = Address.zero();
        if (kind == StubFrames.K_LAZY)
        {
            LazyMethod[] tab = LazyCompiler.lzTab;
            if (tab != null && idx >= 0L && idx < tab.length && tab[(int) idx] != null && tab[(int) idx].descOff != 0)
            {
                LazyMethod lm = tab[(int) idx];
                tMask = argRefMask(lm.blob.toAddress().plus(lm.descOff), lm.isStatic == 0);
                tData = argDataMask;
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
                    tData = argDataMask;
                    tN = argN;
                    return;
                }
            }
        }
        else if (kind == StubFrames.K_LINK)
        {
            d = DynamicLinker.linkDesc((int) idx);
            int recv = DynamicLinker.linkRecv((int) idx);
            if (!d.isZero() && recv >= 0)
            {
                tMask = argRefMask(d, recv != 0);
                tData = argDataMask;
                tN = argN;
                return;
            }
        }
        else if (kind == StubFrames.K_MH)
        {
            if (VirtualDispatch.vsDesc != null && idx >= 0L && idx < VirtualDispatch.vsCount)
            {
                d = VirtualDispatch.vsDesc.get((int) idx);
                if (!d.isZero())
                {
                    // linkToStatic/Special/Virtual/Interface are static (the MemberName is the last argument);
                    // invokeBasic/invokeExact/invoke take the handle as a receiver (mhResolve keeps x0 a handle)
                    Address n = VirtualDispatch.vsName.get((int) idx);
                    Address nEnd = n.plus(2L + (long) n.loadUnsignedByte() * 256L + n.plus(1L).loadUnsignedByte());
                    boolean linkTo = namePrefix(n.plus(2L), nEnd, Magic.bytes("linkTo"));
                    tMask = argRefMask(d, !linkTo);
                    tData = argDataMask;
                    tN = argN;
                    return;
                }
            }
        }
        else if (kind == StubFrames.K_INDY)
        {
            boolean shifted = (idx & INDY_SHIFTED) != 0L;
            d = Indy.siteDesc((int) (idx & ~INDY_SHIFTED));
            if (!d.isZero())
            {
                tMask = argRefMask(d, shifted);                // after the shift: the target handle in x0
                tData = argDataMask;
                tN = argN;
                return;
            }
        }
        tUntyped = true;                                       // no descriptor: every saved register may be one
        tMask = 0xFFFFL;
        tN = 16;
    }

    /** Which saved argument registers hold data references ({@code magic.DataReference}): bit r = x_r. */
    static long tData;

    /** Set in an indy trampoline's index word ({@link StubFrames#STUB_ID_OFF}) once {@code Indy.indyResolve} has
     *  shifted the arguments one register up and stored the target in x0. */
    static final long INDY_SHIFTED = 1L << 62;

    /**
     * For {@code Indy.indyResolve}: shift an indy trampoline's {@code n} saved arguments at {@code save} (its saved
     * x0) one register up, put {@code target} in x0, and say so in the frame's index word. Here, in a class with no
     * polls, so no collection sees the save area half shifted or shifted without the flag.
     */
    static void shiftIndyArgs(long save, int n, long target)
    {
        Address a = Address.fromLong(save);
        int r = n;
        while (r > 0)
        {
            a.plus(r * 8L).store(a.plus((r - 1) * 8L).loadLong());
            r -= 1;
        }
        a.store(target);
        Address id = a.plus((long) (StubFrames.STUB_ID_OFF - StubFrames.STUB_SAVE0));
        id.store(id.loadLong() | INDY_SHIFTED);
    }
}
