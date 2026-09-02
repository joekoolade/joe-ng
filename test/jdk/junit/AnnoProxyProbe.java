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

    /** Every element DEFAULTED, so a use that writes none must still read the declared defaults. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Defaulted
    {
        String name() default "anon";

        int size() default 42;

        String[] tags() default { "x", "y" };
    }

    @Tag(value = "hello", count = 7, names = { "a", "b", "c" })
    public void tagged()
    {
    }

    public void untagged()
    {
    }

    /** Writes ONE element; the other two must come from AnnotationDefault. */
    @Defaulted(size = 9)
    public void partlyDefaulted()
    {
    }

    /** Three declared fields spanning access and static-ness, so a filter bug shows as a wrong COUNT. */
    public static class Fields
    {
        public int pub;
        private String priv;
        public static long stat;
    }

    /** Inherits `pub`/`stat` and adds one of its own, so getFields must walk the chain and skip `priv`. */
    public static class Sub extends Fields
    {
        public boolean own;
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

        // DEFAULTS: the use writes only size, so name/tags must come from the annotation TYPE's
        // AnnotationDefault attributes. A library that omits most elements (picocli's @Command) depends
        // entirely on this, and reads null without it.
        Defaulted d = AnnoProxyProbe.class.getDeclaredMethod("partlyDefaulted").getAnnotation(Defaulted.class);
        System.out.println("defaulted = " + (d != null));
        if (d != null)
        {
            System.out.println("d.size = " + d.size() + " (want 9, written)");
            System.out.println("d.name = " + d.name() + " (want anon, defaulted)");
            String[] tg = d.tags();
            System.out.println("d.tags = " + (tg == null ? "null" : tg.length + ":" + tg[0] + tg[1])
                    + " (want 2:xy, defaulted)");
        }

        // getDeclaredFields: counted AND named, because a walk that mis-steps its cursor still returns
        // plausible objects -- the count alone would not catch a field skipped or double-counted.
        // want 2, NOT the 3 stock would give: joe-ng omits STATIC fields, because a Field here reads through
        // an instance offset and would compute a meaningless address for one. Pinned so the divergence is
        // visible and cannot drift silently -- see Class.getDeclaredFields.
        java.lang.reflect.Field[] df = Fields.class.getDeclaredFields();
        System.out.println("declaredFields = " + df.length + " (want 2, stock 3: statics omitted)");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < df.length; i++)
        {
            b.append(df[i].getName()).append(' ');
        }
        System.out.println("names = " + b.toString().trim() + " (want pub priv)");

        // getFields: PUBLIC only, and up the chain -- so Sub's inherited `pub` appears and `priv` does not.
        java.lang.reflect.Field[] pf = Sub.class.getFields();
        System.out.println("fields = " + pf.length + " (want 2)");
        System.out.println("[probe done]");
    }
}
