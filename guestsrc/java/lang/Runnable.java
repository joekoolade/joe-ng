package java.lang;

/**
 * joe-ng's JDK-free reimplementation of {@link java.lang.Runnable}, compiled into the boot image as a
 * raw blob (via {@code --patch-module java.base}) and loaded on the metal by {@code vm/Loader} when the
 * demand-loaded {@code demo/DiningPhilosophers} references it. Only what the demo needs — one method.
 */
public interface Runnable
{
    void run();
}
