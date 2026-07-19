package harness;

import java.util.Arrays;

/**
 * A tiny, dependency-free test harness — joe2's "only two external seeds" rule
 * (CLAUDE.md, PLAN.md §0) keeps third-party test frameworks out, and a
 * `main`-based assertion style also stays portable to running on joe2 itself
 * later. Each test class has a {@code main} that calls these assertions and ends
 * with {@link #summary}; {@code build.sh} runs them and a non-zero exit fails
 * the build.
 *
 * <p>Assertions accumulate into shared counters (each test runs in its own JVM,
 * so there's nothing to reset). Failures print inline; passes are quiet except
 * {@link #eqWords}, which echoes the words so compiled output is visible.
 */
public final class T {
    private T() {}

    private static int checks;
    private static int failures;

    /** Assert a 32-bit instruction word, formatted in hex. */
    public static void eqWord(String name, int expected, int actual) {
        checks++;
        if (expected != actual) {
            failures++;
            System.out.printf("FAIL %-24s expected 0x%08X got 0x%08X%n", name, expected, actual);
        }
    }

    /** Assert a general integer/offset, formatted in decimal (and hex). */
    public static void eq(String name, long expected, long actual) {
        checks++;
        if (expected != actual) {
            failures++;
            System.out.printf("FAIL %-24s expected %d (0x%X) got %d (0x%X)%n", name, expected, expected, actual, actual);
        }
    }

    /** Assert a word sequence; echoes the words on pass (handy for compiled code). */
    public static void eqWords(String name, int[] expected, int[] actual) {
        checks++;
        if (Arrays.equals(expected, actual)) {
            System.out.printf("PASS %-14s %s%n", name, hex(actual));
        } else {
            failures++;
            System.out.printf("FAIL %-14s%n  want %s%n  got  %s%n", name, hex(expected), hex(actual));
        }
    }

    /** Assert a byte sequence, formatted in hex. */
    public static void eqBytes(String name, byte[] expected, byte[] actual) {
        checks++;
        if (!Arrays.equals(expected, actual)) {
            failures++;
            System.out.printf("FAIL %-24s expected %s got %s%n", name, hex(expected), hex(actual));
        }
    }

    /** Assert that {@code r} throws {@link IllegalArgumentException}. */
    public static void throwsIAE(String name, Runnable r) {
        checks++;
        try {
            r.run();
            failures++;
            System.out.printf("FAIL %-24s expected IllegalArgumentException%n", name);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** Print the tally and exit non-zero if anything failed. */
    public static void summary(String label) {
        System.out.printf("%n%s: %d checks, %d failures%n", label, checks, failures);
        if (failures > 0) System.exit(1);
    }

    private static String hex(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i > 0 ? ", " : "").append(String.format("0x%08X", a[i]));
        return sb.append("]").toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }
}
