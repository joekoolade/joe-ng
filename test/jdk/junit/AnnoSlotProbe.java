/**
 * Pins that an annotation's element values are read BY NAME, not by the order they were written.
 *
 * <p>joe-ng stores an annotation instance's values in a flat array and reads element {@code s} from slot
 * {@code s}, where {@code s} is the element method's own itable slot. That identity is what makes an element
 * accessor two instructions -- and it is only correct if a WRITTEN pair is filed under the slot of the element
 * it NAMES. An annotation use writes only the elements it overrides, so two uses of the same annotation type
 * can write different subsets, and filing the n-th written pair into the n-th slot would silently return one
 * element's value for another.
 *
 * <p>The launcher is exactly that shape. Both of these are {@code @Option} on a field named helpRequested:
 * <pre>
 *   MainCommand:  @Option(names = {"-h","--help"}, help      = true)   // deprecated element
 *   BaseCommand:  @Option(names = {"-h","--help"}, usageHelp = true)
 * </pre>
 * so {@code usageHelp()} must be TRUE for one and FALSE for the other -- and picocli warns when more than one
 * option on a command claims {@code usageHelp}.
 *
 * <p>Deliberately reads the REAL picocli annotations rather than a local imitation: the local one would be
 * built by the same code with the same element ordering and could agree with a wrong implementation. The two
 * synthetic annotations below are the control, pinning the general rule independently of picocli.
 */
public class AnnoSlotProbe
{
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    public @interface Multi
    {
        String name() default "?";

        boolean first() default false;

        boolean second() default false;

        boolean third() default false;

        int size() default -1;
    }

    /** Writes the LAST boolean only: a positional filing would put it in `first`. */
    @Multi(name = "c", third = true)
    public static class OnlyThird
    {
    }

    /** Writes the MIDDLE boolean and skips the int. */
    @Multi(name = "b", second = true)
    public static class OnlySecond
    {
    }

    /** Writes them out of declaration order. */
    @Multi(size = 9, first = true, name = "a")
    public static class Reordered
    {
    }

    public static void main(String[] args) throws Exception
    {
        Multi t = OnlyThird.class.getAnnotation(Multi.class);
        System.out.println("OnlyThird  name=" + t.name() + " first=" + t.first() + " second=" + t.second()
                + " third=" + t.third() + " size=" + t.size() + "   (want c/false/false/true/-1)");

        Multi s = OnlySecond.class.getAnnotation(Multi.class);
        System.out.println("OnlySecond name=" + s.name() + " first=" + s.first() + " second=" + s.second()
                + " third=" + s.third() + " size=" + s.size() + "   (want b/false/true/false/-1)");

        Multi r = Reordered.class.getAnnotation(Multi.class);
        System.out.println("Reordered  name=" + r.name() + " first=" + r.first() + " second=" + r.second()
                + " third=" + r.third() + " size=" + r.size() + "   (want a/true/false/false/9)");

        // --- the real thing: the two @Option uses the launcher's duplicate warning turns on -------------
        show("org.junit.platform.console.command.MainCommand", "helpRequested", "want usageHelp=false");
        show("org.junit.platform.console.command.BaseCommand", "helpRequested", "want usageHelp=true");
        show("org.junit.platform.console.command.MainCommand", "versionRequested", "want versionHelp=true");
    }

    private static void show(String cn, String field, String want) throws Exception
    {
        Class<?> c = Class.forName(cn);
        java.lang.reflect.Field[] fs = c.getDeclaredFields();
        int i = 0;
        while (i < fs.length)
        {
            if (fs[i].getName().equals(field))
            {
                org.junit.platform.console.shadow.picocli.CommandLine.Option o =
                        fs[i].getAnnotation(org.junit.platform.console.shadow.picocli.CommandLine.Option.class);
                if (o == null)
                {
                    System.out.println(cn + "." + field + " -> NO @Option");
                    return;
                }
                System.out.println(cn.substring(cn.lastIndexOf('.') + 1) + "." + field
                        + " names0=" + o.names()[0]
                        + " usageHelp=" + o.usageHelp()
                        + " versionHelp=" + o.versionHelp()
                        + " help=" + o.help()
                        + " hidden=" + o.hidden()
                        + "   (" + want + ")");
                return;
            }
            i += 1;
        }
        System.out.println(cn + "." + field + " -> FIELD NOT FOUND");
    }
}
