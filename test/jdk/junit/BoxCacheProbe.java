/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-24
 */

/**
 * Which wrapper cache is LIVE -- the one the writer baked into the image, or one the metal
 * {@code <clinit>} built over the top?
 *
 * <p>WHY THE ADDRESS ANSWERS IT. joe-ng's identity hash IS the object's address; the image loads at
 * {@code 0x80000} and its baked objects sit just above it, while {@code Heap.BASE} is
 * {@code 0x0400_0000}. So a box drawn from the BAKED array reports an address below the heap and one
 * drawn from a freshly-built array reports an address inside it. Nothing is asserted -- it is measured.
 *
 * <p>WHAT IS AT STAKE. The writer bakes {@code IntegerCache.cache} and {@code LongCache.cache}, but
 * stock's initializer branches on {@code archivedCache}, NOT on {@code cache}:
 * {@code if (archivedCache == null) { build } else { adopt }}. With that field null the BUILD arm runs
 * and allocates 256 fresh boxes over the baked ones. Stock's own comment says what that costs -- "if
 * archive has Integer cache, we must use all instances from it, otherwise the identity checks between
 * archived Integers and runtime-cached Integers would fail."
 *
 * <p>{@code Integer} and {@code Long} run STOCK here -- there is no {@code guestsrc} overlay for
 * either -- so the JDK 26 source quoted above is the source that EXECUTES on the metal. That is what
 * makes reading it a measurement rather than an analogy.
 *
 * <p>CONTROLS, and they are the point. {@code Character}/{@code Byte}/{@code Short} must report
 * {@code heap} in BOTH states, and the reason is stronger than "not baked yet": those three ARE
 * overlaid, and each overlay DROPS the cache outright ("this overlay drops the cache -- valueOf just
 * boxes"), because the stock {@code <clinit>} sets {@code TYPE} through a native the loader blocks.
 * So they allocate fresh every call and cannot move for any reason; an arm that moved would mean the
 * change reached somewhere it has no business being. The above-cache arms ({@code valueOf(1000)}) must
 * stay {@code heap} in both states too: JLS 5.1.7 only mandates interning within [-128,127], and a
 * cache that had quietly widened would show there.
 */
public final class BoxCacheProbe
{
    private static final long HEAP_BASE = 0x0400_0000L;

    public static void main(String[] args)
    {
        System.out.println("-- cached+baked: Integer, Long.  overlaid with NO cache: Character, Byte, Short --");
        where("Integer.valueOf(5)   ", Integer.valueOf(5));
        where("Integer.valueOf(-128)", Integer.valueOf(-128));
        where("Integer.valueOf(127) ", Integer.valueOf(127));
        where("Long.valueOf(5)      ", Long.valueOf(5L));
        where("Character.valueOf(A) ", Character.valueOf('A'));
        where("Byte.valueOf(5)      ", Byte.valueOf((byte) 5));
        where("Short.valueOf(5)     ", Short.valueOf((short) 5));

        System.out.println("-- above the cache: must be heap in BOTH states (JLS 5.1.7 stops at 127) --");
        where("Integer.valueOf(1000)", Integer.valueOf(1000));
        where("Long.valueOf(1000)   ", Long.valueOf(1000L));

        // Interning WITHIN a world holds whichever arm ran, so these pass in both states and are
        // coverage rather than discrimination -- said plainly, because an arm that cannot fail is
        // not a control.
        System.out.println("  int  5 interned = " + (Integer.valueOf(5) == Integer.valueOf(5)) + " (want true)");
        System.out.println("  long 5 interned = " + (Long.valueOf(5L) == Long.valueOf(5L)) + " (want true)");
        System.out.println("  int  1000 fresh = " + (Integer.valueOf(1000) != Integer.valueOf(1000)) + " (want true)");

        // A merge/copy bug shows up as a wrong VALUE at an array END, not as a wrong address.
        // UNBOXED deliberately: joe-ng's concat lowering routes ANY reference argument to SC_STR,
        // which reads it as a String rather than calling toString (JLS 15.18.1) -- a pre-existing gap
        // this probe walked into and must not depend on. intValue()/longValue() test the same thing.
        System.out.println("  values -128/-1/0/127 = " + Integer.valueOf(-128).intValue()
                           + "/" + Integer.valueOf(-1).intValue()
                           + "/" + Integer.valueOf(0).intValue()
                           + "/" + Integer.valueOf(127).intValue() + " (want -128/-1/0/127)");
        System.out.println("  long   -128/127      = " + Long.valueOf(-128L).longValue()
                           + "/" + Long.valueOf(127L).longValue() + " (want -128/127)");

        // Autoboxing must reach the SAME cache a direct valueOf does -- a second cache would show here.
        Integer boxed = 5;
        System.out.println("  autobox == valueOf = " + (boxed == Integer.valueOf(5)) + " (want true)");

        System.out.println("BoxCacheProbe done");
    }

    /** An object's ADDRESS, and which region it came from. */
    private static void where(String what, Object o)
    {
        long addr = System.identityHashCode(o) & 0xFFFF_FFFFL;
        System.out.println("  " + what + " @0x" + Long.toHexString(addr)
                           + "  " + (addr < HEAP_BASE ? "image (ADOPTED)" : "heap (built fresh)"));
    }
}
