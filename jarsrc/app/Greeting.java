package app;

/**
 * A second class in the jar, so the on-metal loader has to resolve a jar class FROM another jar class — the
 * cross-class link that proves the classpath jar is a real class source and not a one-off entry point.
 */
public class Greeting
{
    private final String who;

    public Greeting(String who)
    {
        this.who = who;
    }

    public String text()
    {
        return "hello, " + who;
    }

    /** A little work, so the class is more than a data holder: the greeting's letters, vowels excluded. */
    public int consonants()
    {
        int n = 0;
        int i = 0;
        String t = text();
        while (i < t.length())
        {
            char c = t.charAt(i);
            if (c >= 'a' && c <= 'z' && c != 'a' && c != 'e' && c != 'i' && c != 'o' && c != 'u')
            {
                n += 1;
            }
            i += 1;
        }
        return n;
    }
}
