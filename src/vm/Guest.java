package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 */
public class Guest {
    static int base;             // a static field, backed by the loader's statics block

    public static int answer() {
        int r = 0;
        int i = 0;
        while (i < 6) {          // computed at runtime (no constant folding): 6 * 7
            r = r + 7;
            i = i + 1;
        }
        base = r;                // putstatic
        base = base + 0;         // getstatic + putstatic
        return base;             // getstatic -> 42 = 0x2A = '*'
    }
}
