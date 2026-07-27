package demo;

import magic.Magic;

/**
 * Verifies the real-shaped {@code java/lang/String} + {@code java/lang/StringBuilder}: build a string with
 * an append-chain (int, boolean, String), then call String methods (length, charAt, equals, hashCode).
 * String literals are now real String objects (the JIT wraps them once String is loaded).
 */
public class StrDemo
{
    public static void main()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("count=").append(42).append(" ok=").append(true);
        String s = sb.toString();                       // "count=42 ok=true"
        Magic.printStr(s);
        Magic.printStr("\n");

        Magic.printStr("length=" + s.length() + " charAt(6)=" + s.charAt(6) + "\n");   // 16, '4'

        String x = "hello";                             // a String OBJECT (literal), methods work on it
        int eq = x.equals("hello") ? 1 : 0;
        Magic.printStr("equals=" + eq + " hash=" + x.hashCode() + "\n");               // 1, 99162322
    }
}
