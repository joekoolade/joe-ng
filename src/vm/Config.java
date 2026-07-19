package vm;

/**
 * Exercises a static initializer ({@code <clinit>}): {@code mark} is set by a
 * static block rather than defaulting to 0, so it only reads as {@code '7'} if
 * the writer's eager-init sequence ran the class's {@code <clinit>} at boot.
 */
public final class Config {
    static int mark;

    static {
        mark = 0x37;   // '7'
    }
}
