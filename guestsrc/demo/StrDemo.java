package demo;

import magic.Magic;

/**
 * Verifies the real-shaped {@code java/lang/String} + {@code java/lang/StringBuilder}: build a string with
 * an append-chain (int, boolean, String), then call String methods (length, charAt, equals, hashCode).
 * String literals are now real String objects (the JIT wraps them once String is loaded).
 */
public class StrDemo
{
    public static void main(String[] args)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("count=").append(42).append(" ok=").append(true);
        String s = sb.toString();                       // "count=42 ok=true"
        Magic.printStr(s);
        Magic.printStr("\n");

        Magic.printStr("length=" + s.length() + " charAt(6)=" + s.charAt(6) + "\n");   // 16, '4'

        String x = "hello";                             // a String OBJECT (literal), methods work on it
        int eq = x.equals("hello") ? 1 : 0;
        int hc = x.hashCode();
        Magic.printStr("equals=" + eq + " hash=" + hc + "\n");               // 1, 99162322

        // Array Types: arrays carry a real Type, so instanceof/checkcast against an array class resolve
        // (primitive arrays are invariant; reference arrays are covariant on the element). Tested against
        // CharSequence (String implements it, and it is loaded) — `instanceof Object` would need Object loaded,
        // which these lean batches deliberately skip (true for regular objects here too).
        Object oa = new int[3];                         // int[] viewed as Object
        Object os = new String[2];                      // String[] viewed as Object
        int flags = (oa instanceof int[]           ? 1 : 0)      // 1: exact primitive-array match
                  | (oa instanceof long[]          ? 0 : 2)      // 2: int[] is NOT long[] (primitive invariant)
                  | (((int[]) oa).length == 3      ? 4 : 0)      // 4: checkcast [I passes -> length 3
                  | (os instanceof String[]        ? 8 : 0)      // 8: exact reference-array match
                  | (os instanceof Comparable[]    ? 16 : 0)     // 16: String[] is a Comparable[] (covariance)
                  | (os instanceof int[]           ? 0 : 32)     // 32: String[] is NOT int[]
                  | (x instanceof Comparable       ? 64 : 0);    // 64: element check the covariance reduces to
        Magic.printStr("arraytypes=" + flags + "\n");            // expect 127 (all bits set)
    }
}
