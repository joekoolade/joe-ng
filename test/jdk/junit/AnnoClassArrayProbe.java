import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Pins {@code Class}-valued annotation elements, singly and as arrays, written and defaulted.
 *
 * <p>The console launcher stops at {@code Could not instantiate null} inside picocli's
 * {@code DefaultFactory.createConverter}, reached from {@code ArgSpec.Builder.<init>} while building an
 * ordinary {@code @Option}. picocli does
 *
 * <pre>for (Class&lt;? extends ITypeConverter&lt;?&gt;&gt; c : option.converter()) { createConverter(factory, c); }</pre>
 *
 * and {@code converter()} defaults to {@code {}} -- so on a correct VM the loop body never runs. Reaching
 * {@code create(null)} means the DEFAULTED empty {@code Class[]} came back non-empty with a null in it.
 *
 * <p>Element LENGTH is what is reported, not just the values: an array of the right contents but the wrong
 * length is exactly the failure here, and a probe that only printed elements would have shown nothing wrong.
 */
public class AnnoClassArrayProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Conv
    {
        Class<?>[] empty() default {};

        Class<?>[] oneDefault() default { String.class };

        Class<?>[] twoDefault() default { String.class, Integer.class };

        Class<?> single() default Object.class;

        String name() default "?";
    }

    /** Writes nothing: every element must come from its DEFAULT. */
    @Conv
    public static class AllDefaulted
    {
    }

    /** Writes an EXPLICITLY EMPTY array over a non-empty default -- the inverse of the launcher's case. */
    @Conv(oneDefault = {}, name = "w")
    public static class WrittenEmpty
    {
    }

    /** WRITES a non-empty Class[]. This is the launcher's actual shape -- JUnit's discovery options declare
     *  {@code @Option(converter = SelectorConverter.Xxx.class)} -- and it is the arm the first version of this
     *  probe was missing, which is why the first run pointed at the defaulted case alone. */
    @Conv(empty = { Integer.class }, oneDefault = { Long.class, Short.class }, name = "wn")
    public static class WrittenNonEmpty
    {
    }

    public static void main(String[] args) throws Exception
    {
        report("AllDefaulted", AllDefaulted.class.getAnnotation(Conv.class));
        report("WrittenEmpty", WrittenEmpty.class.getAnnotation(Conv.class));
        report("WrittenNonEmp", WrittenNonEmpty.class.getAnnotation(Conv.class));

        // The real element the launcher trips on, read off a real @Option.
        Class<?> base = Class.forName("org.junit.platform.console.command.BaseCommand");
        java.lang.reflect.Field[] fs = base.getDeclaredFields();
        int i = 0;
        while (i < fs.length)
        {
            org.junit.platform.console.shadow.picocli.CommandLine.Option o =
                    fs[i].getAnnotation(org.junit.platform.console.shadow.picocli.CommandLine.Option.class);
            if (o != null)
            {
                System.out.println("opt " + fs[i].getName()
                        + " converter.len=" + o.converter().length + " (want 0)"
                        + " type.len=" + o.type().length + " (want 0)"
                        + " consumer=" + nm(o.parameterConsumer())
                        + " preproc=" + nm(o.preprocessor())
                        + " candidates=" + nm(o.completionCandidates())
                        + ((o.converter().length == 0 && o.type().length == 0) ? "  OK" : "  <== WRONG"));
            }
            i += 1;
        }
    }

    private static String nm(Class<?> c)
    {
        return c == null ? "NULL" : c.getName();
    }

    private static void report(String label, Conv c)
    {
        System.out.println(label
                + " empty.len=" + c.empty().length + " (want 0)"
                + " oneDefault.len=" + c.oneDefault().length
                + " twoDefault.len=" + c.twoDefault().length + " (want 2)"
                + " single=" + nm(c.single())
                + " name=" + c.name());
        System.out.println("   empty[has null]=" + hasNull(c.empty())
                + " oneDefault=" + join(c.oneDefault())
                + " twoDefault=" + join(c.twoDefault()));
    }

    private static boolean hasNull(Class<?>[] a)
    {
        int i = 0;
        while (i < a.length)
        {
            if (a[i] == null)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    private static String join(Class<?>[] a)
    {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        while (i < a.length)
        {
            if (i > 0)
            {
                sb.append(",");
            }
            sb.append(nm(a[i]));
            i += 1;
        }
        return sb.append("]").toString();
    }
}
