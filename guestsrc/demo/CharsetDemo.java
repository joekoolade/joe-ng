package demo;

/**
 * The charset closure: STOCK {@code new String(byte[])} and {@code String.getBytes()} on metal. Both go
 * through {@code Charset.defaultCharset()} (overlay -> the {@code sun.nio.cs.UTF_8.INSTANCE} singleton),
 * whose identity pins stock String's pure-Java UTF-8 fast paths ({@code String.utf8} decode /
 * {@code encodeUTF8}); the deep {@code CharsetDecoder} fallback is denylisted, statically-unreachable
 * code. Covers ASCII (Latin1 copy path) and a 2-byte UTF-8 sequence (é -> the decode2/encode
 * non-ASCII path), round-tripping both. Ordinary Java, no VM hooks.
 */
public class CharsetDemo
{
    public static void main()
    {
        byte[] ascii = new byte[] { 104, 101, 108, 108, 111 };          // "hello"
        String s = new String(ascii);
        System.out.println("new String(ascii)=" + s + " len=" + s.length()
                + " eq=" + (s.equals("hello") ? 1 : 0));                // hello 5 1
        byte[] round = s.getBytes();
        boolean ok = round.length == ascii.length;
        int i = 0;
        while (ok && i < ascii.length)
        {
            if ((round[i] & 0xff) != (ascii[i] & 0xff))                 // mask BOTH sides (baload zero-extends)
            {
                ok = false;
            }
            i += 1;
        }
        System.out.println("getBytes len=" + round.length + " roundtrip=" + (ok ? 1 : 0));   // 5 1

        byte[] utf = new byte[] { (byte) 0xC3, (byte) 0xA9 };           // U+00E9 (e-acute) in UTF-8
        String e = new String(utf);
        System.out.println("utf8 len=" + e.length() + " char=" + (int) e.charAt(0));         // 1 233
        byte[] back = e.getBytes();
        System.out.println("utf8 back len=" + back.length
                + " b0=" + (back[0] & 0xff) + " b1=" + (back[1] & 0xff));                    // 2 195 169
    }
}
