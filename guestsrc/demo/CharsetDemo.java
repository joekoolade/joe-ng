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

        // UTF-8 OUTPUT: println now encodes through stock getBytes(), so non-ASCII text leaves the UART as
        // real UTF-8. Printed DIRECTLY (not concatenated -- the metal concat intrinsic is Latin1-only):
        // a Latin1 string (é) and a UTF16 string (€, char 0x20AC > 0xFF -> 3-byte UTF-8 sequence).
        System.out.print("out latin1: ");
        System.out.println(e);                                          // wire bytes: C3 A9
        byte[] euroUtf = new byte[] { (byte) 0xE2, (byte) 0x82, (byte) 0xAC };   // U+20AC euro sign
        String euro = new String(euroUtf);
        System.out.println("euro len=" + euro.length() + " char=" + (int) euro.charAt(0));   // 1 8364
        System.out.print("out utf16: ");
        System.out.println(euro);                                       // wire bytes: E2 82 AC
    }
}
