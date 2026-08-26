package demo;

/**
 * String.replaceAll on metal -- the path TestAttrsNL:75 dies on with a NullPointerException inside
 * Matcher.appendExpandedReplacement.
 *
 * <p>The interesting frame's output sink is declared {@code Appendable} (an INTERFACE) while the object
 * actually passed is a StringBuilder. Appendable's {@code append(char)} returns Appendable and
 * StringBuilder's returns StringBuilder, so javac gives StringBuilder a BRIDGE method and the call is an
 * invokeinterface landing on it. The first probe isolates that shape from the regex engine entirely.
 */
public final class RegexReplaceDemo
{
    public static void main(String[] args)
    {
        StringBuilder sb = new StringBuilder();
        Appendable app = sb;
        try
        {
            app.append('x');
            app.append("yz");
            System.out.println("Appendable bridge append = " + sb);
        }
        catch (Exception e)
        {
            System.out.println("Appendable bridge THREW " + e);
        }

        String src = "a\r\nb\r\nc";
        System.out.println("src len = " + src.length());

        String r = src.replaceAll("\r\n", "\r");
        System.out.println("replaceAll(crlf->cr) len = " + r.length());

        String n = src.replaceAll("\r\n", "\n");
        System.out.println("replaceAll(crlf->lf) len = " + n.length());

        System.out.println("replace(char) = " + "a-b-c".replace('-', '+'));
        System.out.println("replaceAll(no match) = " + "abc".replaceAll("zz", "!"));
        System.out.println("done");
    }
}
