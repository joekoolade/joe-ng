package vm;

/**
 * A minimal class with mutable static state, exercising {@code getstatic}/
 * {@code putstatic} against the image's statics area. {@code count} defaults to 0
 * (the statics area is zero-initialized, matching JVM semantics) — no static
 * initializer, so no {@code <clinit>} is needed yet.
 */
public final class Counter {
    static int count;

    public static void bump() {
        count = count + 1;
    }

    public static int get() {
        return count;
    }
}
