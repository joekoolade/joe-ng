package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 */
public class Guest {
    public static int answer() {
        int r = 0;
        int i = 0;
        while (i < 6) {          // computed at runtime (no constant folding): 6 * 7
            r = r + 7;
            i = i + 1;
        }
        return r;                // 42 = 0x2A = '*'
    }
}
