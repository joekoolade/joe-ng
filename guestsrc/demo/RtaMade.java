package demo;

/**
 * Instantiated only from a reflectively-reached method, so nothing pulls it: the {@code new} naming it is
 * unresolvable when that method is compiled, and has to be resolved when the site is actually reached.
 */
public class RtaMade
{
    public String tag()
    {
        return "made";
    }
}
