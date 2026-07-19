package vm;

import magic.Magic;

/**
 * The runtime entry points, written as ordinary Java and compiled to A64 by our
 * own baseline compiler (the metacircular point — PLAN.md §1). The seed JVM
 * never runs these on metal; the writer parses this class's bytecode and
 * compiles it into {@code kernel8.img}.
 *
 * Methods are added here as the baseline compiler's bytecode coverage grows,
 * milestone by milestone (CLAUDE.md working agreements).
 */
public final class VM {
    private VM() {}

    /**
     * Park loop — the first method compiled from real Java bytecode by our own
     * compiler (M1c step 1). Compiles to {@code wfe; b .-4}, identical to the M0
     * hand-emitted spin image.
     */
    public static void spin() {
        while (true) {
            Magic.wfe();
        }
    }
}
