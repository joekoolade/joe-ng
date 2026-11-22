/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-22
 */
package vm;

import magic.Address;
import magic.Uninterruptible;
import objectmodel.ObjectModel;

/**
 * The precise heap trace (frames/GC arc S6c): how the collector scans a marked block once every word's kind is
 * knowable (S6a/S6b). A scalar's slots by its Type's kind bitmaps -- an object reference marked at {@code w}, a data
 * reference at {@code w-16}, a code address only in the code bitmap -- an array's elements by its Type's element
 * kind, an {@code allocData} struct by its layout word; a byte block, a raw array and a word array with no element
 * Type hold no pointer and are not read at all. A block that says nothing a precise trace can read (a Type with no
 * kinds, a header that is no TIB) is scanned conservatively, as every block was before, and counted in {@link
 * #fallbacks}.
 *
 * <p>On by default; {@code gcprecise=0} in {@code /etc/init} restores the conservative heap trace. The heap shadow
 * ({@code gcheap=2}) runs this as its first phase, so what it reports is what this trace loses. Roots are not
 * this class's business: the statics region and the stack fallbacks stay conservative until S6d.
 */
final class PreciseTrace implements Uninterruptible
{
    private PreciseTrace()
    {
    }

    /** 1 = the collector traces the heap precisely (the default); 0 = conservatively ({@code gcprecise=0}). */
    static int on = 1;

    /** Blocks scanned conservatively because nothing in them could be read precisely, over the boot. */
    static long fallbacks;

    /**
     * Scan marked block {@code o} following only words of an exactly known kind -- or, where the block says nothing
     * a precise trace can read, conservatively, so nothing is ever under-described.
     */
    static void scanBlock(Address o, long size)
    {
        long tibw = o.plus(ObjectModel.TIB_OFFSET).loadLong();
        if (isLayout(tibw))
        {
            VMGc.census(VMGc.HC_DATA, size, 0L);
            scanLayout(o, size, tibw);                 // an allocData struct that says what it holds (S6b-2)
            return;
        }
        if (tibw == 0L)
        {
            VMGc.census(VMGc.HC_DATA, size, 0L);       // a byte-only allocData block: no pointers
            return;
        }
        if (tibw <= (long) ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            VMGc.census(VMGc.HC_RAW_ARRAY, size, 0L);  // a raw array: its header is its element size, no Type
            return;
        }
        if (tibw < VMGc.META_LOW || tibw >= VMGc.META_HIGH || (tibw & 7L) != 0L)
        {
            conservative(VMGc.HC_OTHER, o, size);      // a header word that is none of the above
            return;
        }
        VMGc.markExact(tibw - 16L);                    // the object's own TIB (a data reference): see VMGc.scanBlock
        long type = Address.fromLong(tibw).plus(ObjectModel.TIB_TYPE_SLOT * ObjectModel.WORD).loadLong();
        if (type < VMGc.META_LOW || type >= VMGc.META_HIGH || (type & 7L) != 0L)
        {
            conservative(VMGc.HC_OTHER, o, size);
            return;
        }
        Address t = Address.fromLong(type);
        long isz = t.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong();
        if ((isz & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG)
        {
            boolean refs = t.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadLong() != 0L
                           || (isz & ObjectModel.ARRAY_REF_ELEMS) != 0L;   // element class unresolved: still refs
            VMGc.census((isz & 0xFFFFL) < (long) ObjectModel.WORD ? VMGc.HC_NARROW_ARRAY
                        : refs ? VMGc.HC_REF_ARRAY : VMGc.HC_WORD_ARRAY, size, 0L);
            if ((isz & 0xFFFFL) == (long) ObjectModel.WORD && refs)
            {
                boolean data = (isz & ObjectModel.ARRAY_DATA_ELEMS) != 0L;   // a magic.DataReferenceArray
                boolean code = (isz & ObjectModel.ARRAY_CODE_ELEMS) != 0L;   // a magic.CodeArray
                Address p = o.plus(ObjectModel.ARRAY_BASE_OFFSET);
                Address end = arrayEnd(o, size);
                while (p.LT(end))
                {
                    long w = p.loadLong();
                    if (code)
                    {
                        markCode(w);
                    }
                    else if (!data)
                    {
                        VMGc.markExact(w);
                    }
                    else if (w != 0L)
                    {
                        VMGc.markExact(w - 16L);
                    }
                    p = p.plus(8);
                }
            }
            return;
        }
        long r0 = t.plus(ObjectModel.TYPE_REFKIND_OFFSET).loadLong();
        if ((r0 & 1L) == 0L)
        {
            conservative(VMGc.HC_UNMAPPED, o, size);   // no exact kinds (too wide, or an unmapped super)
            return;
        }
        VMGc.census(VMGc.HC_MAPPED, size, 0L);
        long r1 = t.plus(ObjectModel.TYPE_REFKIND_OFFSET + 8).loadLong();
        long d0 = t.plus(ObjectModel.TYPE_DATAKIND_OFFSET).loadLong();
        long d1 = t.plus(ObjectModel.TYPE_DATAKIND_OFFSET + 8).loadLong();
        long slots = (size - (long) ObjectModel.HEADER_SIZE) >> 3;
        long i = 0;
        while (i < slots && i <= (long) ObjectModel.TYPE_REFMAP_MAX_SLOT)
        {
            int sh = (int) (i < 63L ? i + 1L : i - 63L);
            long rb = i < 63L ? r0 >>> sh : r1 >>> sh;
            long db = i < 63L ? d0 >>> sh : d1 >>> sh;
            long w = o.plus(ObjectModel.HEADER_SIZE).plus(i * 8L).loadLong();
            if ((rb & db & 1L) != 0L)
            {
                markCode(w);                           // both bits: a magic.CodeAddress (S6b-3)
            }
            else if ((rb & 1L) != 0L)
            {
                VMGc.markExact(w);
            }
            else if ((db & 1L) != 0L && w != 0L)
            {
                VMGc.markExact(w - 16L);
            }
            i += 1;
        }
    }

    /** Whether header word {@code tibw} is an allocData layout word ({@link ObjectModel#LAYOUT_TAG}). */
    static boolean isLayout(long tibw)
    {
        return tibw > (long) ObjectModel.MAX_RAW_ARRAY_TIB && (tibw & ObjectModel.LAYOUT_TAG_MASK) == ObjectModel.LAYOUT_TAG;
    }

    /** The kind field of layout word {@code layout}. */
    static int layoutKind(long layout)
    {
        return (int) ((layout >>> 3) & 31L);
    }

    /** Follow the words of allocData struct {@code o} its layout word names: data references at {@code w-16}, code
     *  addresses at {@code w} (a code-arena hit is recorded in the code bitmap, nothing else is marked). */
    private static void scanLayout(Address o, long size, long layout)
    {
        int kind = layoutKind(layout);
        Address p = o.plus(ObjectModel.HEADER_SIZE);
        Address end = o.plus(size);
        if (kind == ObjectModel.LAYOUT_TYPE)
        {
            markData(p.plus(ObjectModel.TYPE_SUPER_OFFSET).loadLong());
            markData(p.plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).loadLong());
            markData(p.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadLong());
            markData(p.plus(ObjectModel.TYPE_DISPLAY_OFFSET).loadLong());
            return;
        }
        if (kind == ObjectModel.LAYOUT_TIB)
        {
            markData(p.loadLong());
            p = p.plus(8);
            while (p.LT(end))
            {
                markCode(p.loadLong());
                p = p.plus(8);
            }
            return;
        }
        if (kind == ObjectModel.LAYOUT_DATA_REFS)
        {
            while (p.LT(end))
            {
                markData(p.loadLong());
                p = p.plus(8);
            }
            return;
        }
        if (kind == ObjectModel.LAYOUT_CODE)
        {
            while (p.LT(end))
            {
                markCode(p.loadLong());
                p = p.plus(8);
            }
            return;
        }
        if (kind == ObjectModel.LAYOUT_STATICS)
        {
            int n = (int) (layout >>> 8);
            int words = ObjectModel.staticsMapWords(n);
            Address refs = p.plus((n + 1) * 8L);
            Address datas = refs.plus(words * 8L);
            if (datas.plus(words * 8L).GT(end))
            {
                return;                                // cannot be: the block is smaller than its own layout
            }
            int s = 0;
            while (s < n)
            {
                long bit = 1L << (s & 63);
                long w = p.plus(s * 8L).loadLong();
                boolean r = (refs.plus((s >> 6) * 8L).loadLong() & bit) != 0L;
                boolean d = (datas.plus((s >> 6) * 8L).loadLong() & bit) != 0L;
                if (r && d)
                {
                    markCode(w);
                }
                else if (r)
                {
                    VMGc.markExact(w);
                }
                else if (d)
                {
                    markData(w);
                }
                s += 1;
            }
        }
    }

    /** Whether {@link #scanLayout} follows the payload word at byte {@code off} of struct {@code o}. */
    static boolean layoutExact(Address o, long size, long layout, long off)
    {
        int kind = layoutKind(layout);
        if (kind == ObjectModel.LAYOUT_TYPE)
        {
            return off == (long) ObjectModel.TYPE_SUPER_OFFSET || off == (long) ObjectModel.TYPE_ITABLE_DIR_OFFSET
                   || off == (long) ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET || off == (long) ObjectModel.TYPE_DISPLAY_OFFSET;
        }
        if (kind == ObjectModel.LAYOUT_TIB || kind == ObjectModel.LAYOUT_DATA_REFS || kind == ObjectModel.LAYOUT_CODE)
        {
            return true;
        }
        if (kind == ObjectModel.LAYOUT_STATICS)
        {
            int n = (int) (layout >>> 8);
            int s = (int) (off >> 3);
            if (s >= n)
            {
                return false;
            }
            int words = ObjectModel.staticsMapWords(n);
            Address refs = o.plus(ObjectModel.HEADER_SIZE).plus((n + 1) * 8L);
            long bit = 1L << (s & 63);
            return (refs.plus((s >> 6) * 8L).loadLong() & bit) != 0L
                   || (refs.plus((words + (s >> 6)) * 8L).loadLong() & bit) != 0L;
        }
        return false;
    }

    /** A code address the trace follows: recorded in the code bitmap; never a heap mark. */
    private static void markCode(long w)
    {
        if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT)
        {
            VMGc.markExact(w);
        }
    }

    /** The whole payload of {@code o}, every word at {@code w} and {@code w-16}: what the collector did before S6c. */
    private static void conservative(int cat, Address o, long size)
    {
        VMGc.census(cat, size, size);
        fallbacks = fallbacks + 1L;
        VMGc.markWords(o.plus(ObjectModel.HEADER_SIZE), o.plus(size));
    }

    private static void markData(long w)
    {
        if (w != 0L)
        {
            VMGc.markExact(w - 16L);
        }
    }

    static Address arrayEnd(Address o, long size)
    {
        Address end = o.plus(ObjectModel.ARRAY_BASE_OFFSET)
                       .plus(o.plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadLong() * (long) ObjectModel.WORD);
        if (end.GT(o.plus(size)) || end.LT(o))
        {
            end = o.plus(size);
        }
        return end;
    }
}
