import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

/**
 * Pins the annotation-instance runtime: {@code getAnnotation} must return an object that IS the annotation
 * interface, so calling an element method on it is an ordinary interface dispatch.
 *
 * <p>Covers what the first increment supports -- a String element, an int element, and a String[] element --
 * and asserts the values rather than merely that something non-null came back: a proxy that returns the wrong
 * slot would still be non-null, and that is exactly the failure the slot-index==value-index identity could
 * produce if it were wrong.
 */
public class AnnoProxyProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Tag
    {
        String value();

        int count();

        String[] names();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker
    {
    }

    @Tag(value = "hello", count = 7, names = { "a", "b", "c" })
    public void tagged()
    {
    }

    public void untagged()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Method tagged = AnnoProxyProbe.class.getDeclaredMethod("tagged");
        Tag t = tagged.getAnnotation(Tag.class);
        System.out.println("present = " + (t != null));
        if (t != null)
        {
            System.out.println("value = " + t.value() + " (want hello)");
            System.out.println("count = " + t.count() + " (want 7)");
            String[] n = t.names();
            System.out.println("names.length = " + (n == null ? -1 : n.length) + " (want 3)");
            if (n != null && n.length == 3)
            {
                System.out.println("names = " + n[0] + n[1] + n[2] + " (want abc)");
            }
        }
        Method untagged = AnnoProxyProbe.class.getDeclaredMethod("untagged");
        System.out.println("absent = " + (untagged.getAnnotation(Tag.class) == null) + " (want true)");
        System.out.println("[probe done]");
    }
}
