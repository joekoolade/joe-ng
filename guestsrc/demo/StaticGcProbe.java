package demo;

/**
 * Does a demand-loaded class's STATIC survive a collection?
 *
 * <p>A class loaded on the metal keeps its statics in a {@code Heap.allocData} block referenced from
 * {@code clTab[reg].statics} -- a {@code long} field, which precise tracing does not follow as a reference.
 * Under the old per-program {@code resetLoader} that was invisible: the whole demand heap was rewound between
 * programs anyway. Running programs back to back on ONE loader state (launchMain) makes it observable, because
 * a static written by one program has to survive whatever the next one collects.
 *
 * <p>The second print is the measurement AND the instrument: if {@code System.out} itself was swept, this
 * line NPEs instead of printing, which is the same failure the demo suite hits in PipDemo.
 */
public class StaticGcProbe
{
    static Object kept = new Object();
    static StringBuilder text = new StringBuilder("alive");
    static int[] table = new int[4];

    public static void main(String[] args)
    {
        table[0] = 42;
        System.out.println("before churn: kept=" + (kept != null) + " text=" + text + " table0=" + table[0]);
        int i = 0;
        while (i < 300000)
        {
            byte[] junk = new byte[256];
            junk[0] = (byte) i;
            i += 1;
        }
        System.out.println("after churn:  kept=" + (kept != null) + " text=" + text + " table0=" + table[0]);
        System.out.println("(a missing 'after' line, or an NPE here, means the statics block was swept)");
    }
}
