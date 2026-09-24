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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Did {@code ImmutableCollections.<clinit>} ADOPT the image's archived subgraph, or build its own?
 *
 * <p>WHY THIS CAN BE ANSWERED FROM GUEST CODE AT ALL. The five singletons are private, so nothing
 * here can name them -- but their ADDRESS says which arm ran. joe-ng's identity hash IS the object's
 * address, the image is loaded at {@code 0x80000} and its baked objects sit just above it, while
 * {@code Heap.BASE} is {@code 0x0400_0000}. So an adopted singleton reports an address BELOW the heap
 * and a freshly-built one reports an address inside it. The two arms are never ambiguous.
 *
 * <p>WHAT DISCRIMINATES. Every arm is a NEGATIVE CONTROL of itself: before the writer baked
 * {@code archivedObjects} the field read null, the initializer took the BUILD arm, and every one of
 * these reports {@code heap}. There is no state in which a wrong implementation prints {@code image}
 * for the wrong reason -- the address is measured, not asserted.
 *
 * <p>{@code Set.of()} and {@code Map.of()} matter most: those two singletons are the ones no baked
 * code references, so before this change their classes were not in the image AT ALL (no Type, no TIB)
 * and only the array forced them in. An adopted {@code SetN} whose TIB were 0 would not print an
 * address -- it would wild-branch on the first virtual call, which is what {@code toString} is here
 * to provoke.
 */
public final class ArchiveProbe
{
    private static final long HEAP_BASE = 0x0400_0000L;

    public static void main(String[] args)
    {
        arm("List.of()      ", List.of());
        arm("List.of(a)     ", List.of("a"));
        arm("Set.of()       ", Set.of());
        arm("Map.of()       ", Map.of());

        // The singletons are SHARED, which is the property the two-writers hazard threatened: two
        // calls must hand back the SAME object whichever arm the initializer took.
        System.out.println("  List.of() stable = " + (List.of() == List.of()) + " (want true)");
        System.out.println("  Set.of()  stable = " + (Set.of() == Set.of()) + " (want true)");
        System.out.println("  Map.of()  stable = " + (Map.of() == Map.of()) + " (want true)");

        // And they must still BEHAVE: a wild TIB shows up here rather than as a wrong number.
        System.out.println("  sizes = " + List.of().size() + "/" + Set.of().size()
                           + "/" + Map.of().size() + " (want 0/0/0)");
        System.out.println("  empty = " + List.of().isEmpty() + "/" + Set.of().isEmpty()
                           + "/" + Map.of().isEmpty() + " (want true/true/true)");
        System.out.println("  toString = " + List.of() + Set.of() + Map.of() + " (want [][]{})");

        System.out.println("ArchiveProbe done");
    }

    private static void arm(String what, Object o)
    {
        long a = System.identityHashCode(o) & 0xFFFF_FFFFL;
        System.out.println("  " + what + " @0x" + Long.toHexString(a)
                           + "  " + (a < HEAP_BASE ? "image (ADOPTED)" : "heap (built fresh)"));
    }
}
