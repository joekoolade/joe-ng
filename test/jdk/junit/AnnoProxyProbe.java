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

    public enum Colour
    {
        RED, GREEN, BLUE
    }

    /** Class- and enum-valued elements, both written and defaulted -- the phase-3 resolution path. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Typed
    {
        Class<?> type() default String.class;

        Class<?> prim() default int.class;

        Colour colour() default Colour.RED;
    }

    /** Every element DEFAULTED, so a use that writes none must still read the declared defaults. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Defaulted
    {
        String name() default "anon";

        int size() default 42;

        String[] tags() default { "x", "y" };
    }

    /** A CLASS-level annotation, for the Class-side getAnnotation/getDeclaredAnnotation pair. */
    @Tag(value = "onclass", count = 3, names = { "x" })
    static class Annotated
    {
    }

    /** Deliberately un-annotated, so the absent arm can say NO. */
    static class Bare
    {
    }

    /** Member classes of several access levels, plus a LOCAL and an ANONYMOUS class that must NOT appear:
     *  JVMS 4.7.6 gives both a zero outer_class_info_index, which is the field the filter keys on. */
    public static class Outer
    {
        public static class PubMember
        {
        }

        private static class PrivMember
        {
        }

        interface IfaceMember
        {
        }

        static Object makeLocalAndAnon()
        {
            class LocalOnly
            {
            }
            Runnable anon = new Runnable()
            {
                public void run()
                {
                }
            };
            return new Object[] { new LocalOnly(), anon };
        }
    }

    /** Declares no member class at all -- the arm that catches an enumeration inventing entries. */
    static class NoMembers
    {
    }

    /** A member class declared in a SUPER-interface: getClasses must inherit it transitively, which a walk
     *  of the direct interfaces alone would miss. */
    public interface TopIface
    {
        public static class FromIface
        {
        }
    }

    public interface MidIface extends TopIface
    {
    }

    public static class ImplBase implements MidIface
    {
    }

    public static class Impl extends ImplBase
    {
    }

    @Tag(value = "hello", count = 7, names = { "a", "b", "c" })
    public void tagged()
    {
    }

    public void untagged()
    {
    }

    /** TWO annotations on one method: an enumeration that stops after the first, or mis-steps the element
     *  pairs of the first, reports 1 here. A single-annotation method cannot tell those apart. */
    @Tag(value = "twice", count = 2, names = { "p" })
    @Marker
    public void doubleTagged()
    {
    }

    /** Writes ONE element; the other two must come from AnnotationDefault. */
    @Defaulted(size = 9)
    public void partlyDefaulted()
    {
    }

    /** `type` and `colour` written, `prim` defaulted -- so phase 3 must run for BOTH sources. */
    @Typed(type = Integer.class, colour = Colour.BLUE)
    public void typed()
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
            // The array must be a REAL String[], not an Object[] that merely holds Strings: picocli does
            // `cmd.customSynopsis().clone()` then `checkcast [Ljava/lang/String;`, and an Object[] fails it.
            // instanceof is the same test the cast makes, without needing to catch.
            System.out.println("names instanceof String[] = " + (((Object) n) instanceof String[])
                    + " (want true)");
            Object cloned = n.clone();
            System.out.println("clone instanceof String[] = " + (cloned instanceof String[]) + " (want true)");
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

        // CLASS and ENUM elements -- resolved in phase 3, after every classfile walk is finished, because
        // resolving demand-loads and a load clobbers the cursor a walk stands on. Written AND defaulted are
        // both checked: they arrive from different blobs.
        Typed ty = AnnoProxyProbe.class.getDeclaredMethod("typed").getAnnotation(Typed.class);
        System.out.println("typed = " + (ty != null));
        if (ty != null)
        {
            Class<?> tc = ty.type();
            Class<?> pr = ty.prim();
            Colour c = ty.colour();
            System.out.println("ty.type = " + (tc == null ? "null" : tc.getName()) + " (want java.lang.Integer)");
            System.out.println("ty.type==Integer.class = " + (tc == Integer.class) + " (want true)");
            System.out.println("ty.prim = " + (pr == null ? "null" : pr.getName()) + " (want int, defaulted)");
            System.out.println("ty.colour = " + (c == null ? "null" : c.name()) + " (want BLUE)");
            System.out.println("ty.colour==BLUE = " + (c == Colour.BLUE) + " (want true)");
        }

        // SUPERCLASS CHAIN: picocli collects annotated members by walking `cls = cls.getSuperclass()` until
        // null, so a chain that revisits or fails to terminate collects the same fields repeatedly -- which is
        // what "Multiple options [--help, --help, --help, --help]" would look like. The host JVM prints no
        // such warning for the same command, so it is joe-ng's.
        StringBuilder chain = new StringBuilder();
        Class<?> c = Sub.class;
        int hops = 0;
        while (c != null && hops < 8)
        {
            chain.append(c.getName()).append(' ');
            c = c.getSuperclass();
            hops++;
        }
        System.out.println("chain = " + chain.toString().trim());
        System.out.println("chain hops = " + hops + " (want 3: Sub, Fields, Object)");

        // And the same fields enumerated twice must agree -- a stateful walk would drift.
        int n1 = Fields.class.getDeclaredFields().length;
        int n2 = Fields.class.getDeclaredFields().length;
        System.out.println("repeat = " + n1 + "," + n2 + " (want 2,2)");

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
        // getDeclaredAnnotation on a CLASS -- the method JUnit reaches through AnnotatedElement, and the one
        // that stopped the launcher in AnnotationSupport.isAnnotated. It must AGREE with getAnnotation here,
        // because the native reads this class's own RuntimeVisibleAnnotations for both.
        java.lang.annotation.Annotation da = Annotated.class.getDeclaredAnnotation(Tag.class);
        System.out.println("class declared    = " + (da != null ? 1 : 0) + " (want 1)");
        System.out.println("declared value    = " + ((Tag) da).value() + " (want onclass)");
        System.out.println("getAnno  value    = " + Annotated.class.getAnnotation(Tag.class).value()
                + " (want onclass)");
        // NOT asserted: `da == getAnnotation(...)`. Stock caches annotation instances so identity happens to
        // hold there, but the API does not specify it, and an arm asserting it would be testing an accident.
        //
        // What IS specified is Annotation.equals -- "an instance of the same annotation interface ... all of
        // whose members are equal" -- and joe-ng's annotation objects inherit Object's IDENTITY equals, so
        // two instances with equal members compare unequal. That is a real gap, and this probe finding it is
        // how it got recorded rather than papered over. Implementing it needs element ENUMERATION (equals,
        // hashCode and toString all do), which the annotation runtime does not have: it can find one element
        // by name, not walk them.
        System.out.println("distinct instances= "
                + (da == Annotated.class.getAnnotation(Tag.class) ? "same" : "fresh (see note)"));

        // getDeclaredAnnotations: the PLURAL, which JUnit's findAnnotation walks to reach META-annotations.
        // An empty answer would not fail -- it would silently report that nothing is annotated -- so the
        // count and the type are both pinned.
        java.lang.annotation.Annotation[] all = Annotated.class.getDeclaredAnnotations();
        System.out.println("declaredAnnos len = " + all.length + " (want 1)");
        System.out.println("declaredAnnos[0]  = " + (all[0] instanceof Tag ? "Tag" : "OTHER <== BUG")
                + " (want Tag)");
        System.out.println("declaredAnnos val = " + ((Tag) all[0]).value() + " (want onclass)");
        System.out.println("getAnnotations len= " + Annotated.class.getAnnotations().length + " (want 1)");
        System.out.println("bare declaredLen  = " + Bare.class.getDeclaredAnnotations().length + " (want 0)");
        // A FRESH array each call, as stock specifies the caller may modify what it gets back.
        System.out.println("fresh array       = "
                + (Annotated.class.getDeclaredAnnotations() != all ? 1 : 0) + " (want 1)");

        // getInterfaces: DIRECTLY declared, in order -- what findAnnotation recurses into. Sub implements
        // none; a class that does is checked below so "empty" cannot pass for "works".
        System.out.println("bare interfaces   = " + Bare.class.getInterfaces().length + " (want 0)");
        Class<?>[] ifs = java.util.ArrayList.class.getInterfaces();
        System.out.println("ArrayList ifaces  = " + ifs.length + " (want 4)");
        boolean sawList = false;
        for (int q = 0; q < ifs.length; q++)
        {
            if (ifs[q] == java.util.List.class)
            {
                sawList = true;
            }
        }
        System.out.println("ArrayList has List= " + (sawList ? 1 : 0) + " (want 1)");

        // getMethods: PUBLIC including INHERITED, deduplicated -- what ReflectionUtils.getDefaultMethods
        // walks. The arms that could be quietly wrong are the inherited one (a receiver-only enumeration
        // would miss it), the dedupe (an override must appear ONCE), and the exclusions.
        java.lang.reflect.Method[] ms = Sub.class.getMethods();
        boolean sawInherited = false;
        boolean sawCtor = false;
        int toStringCount = 0;
        for (int q = 0; q < ms.length; q++)
        {
            String mn = ms[q].getName();
            if (mn.equals("toString"))
            {
                toStringCount += 1;
            }
            if (mn.equals("hashCode"))
            {
                sawInherited = true;          // from java.lang.Object, two hops up
            }
            if (mn.startsWith("<"))
            {
                sawCtor = true;
            }
        }
        System.out.println("getMethods nonEmpty= " + (ms.length > 0 ? 1 : 0) + " (want 1)");
        System.out.println("getMethods inherit = " + (sawInherited ? 1 : 0) + " (want 1, Object.hashCode)");
        System.out.println("getMethods noCtor  = " + (sawCtor ? 0 : 1) + " (want 1)");
        System.out.println("getMethods dedupe  = " + toStringCount + " (want 1, not once per class)");
        // An INTERFACE default must be reachable through an implementing class.
        java.lang.reflect.Method[] am = java.util.ArrayList.class.getMethods();
        boolean sawForEach = false;
        for (int q = 0; q < am.length; q++)
        {
            if (am[q].getName().equals("forEach"))
            {
                sawForEach = true;
            }
        }
        System.out.println("getMethods default = " + (sawForEach ? 1 : 0) + " (want 1, Iterable.forEach)");
        System.out.println("declared on bare  = "
                + (Bare.class.getDeclaredAnnotation(Tag.class) == null ? "null" : "NON-NULL <== BUG")
                + " (want null)");

        // ---- Method.getDeclaredAnnotation / getDeclaredAnnotations / getAnnotations ----
        // findAnnotation calls exactly these three on an AnnotatedElement (verified with javap on the jar).
        java.lang.reflect.Method mt = AnnoProxyProbe.class.getDeclaredMethod("tagged");
        java.lang.reflect.Method mu = AnnoProxyProbe.class.getDeclaredMethod("untagged");
        java.lang.reflect.Method md = AnnoProxyProbe.class.getDeclaredMethod("doubleTagged");
        java.lang.reflect.Method mp = AnnoProxyProbe.class.getDeclaredMethod("partlyDefaulted");

        java.lang.annotation.Annotation mda = mt.getDeclaredAnnotation(Tag.class);
        System.out.println("m.declared present= " + (mda != null ? 1 : 0) + " (want 1)");
        System.out.println("m.declared value  = " + (mda == null ? "NULL" : ((Tag) mda).value())
                + " (want hello)");
        System.out.println("m.declared count  = " + (mda == null ? -1 : ((Tag) mda).count()) + " (want 7)");
        System.out.println("m.declared absent = "
                + (mu.getDeclaredAnnotation(Tag.class) == null ? "null" : "NON-NULL <== BUG") + " (want null)");

        java.lang.annotation.Annotation[] ma = mt.getDeclaredAnnotations();
        System.out.println("m.annos len       = " + ma.length + " (want 1)");
        System.out.println("m.annos[0] is Tag = " + (ma.length == 1 && ma[0] instanceof Tag ? 1 : 0)
                + " (want 1)");
        System.out.println("m.annos[0] value  = " + (ma.length == 1 ? ((Tag) ma[0]).value() : "NONE")
                + " (want hello)");

        // An un-annotated method must answer an EMPTY array, never null: findAnnotation walks it unguarded.
        java.lang.annotation.Annotation[] ua = mu.getDeclaredAnnotations();
        System.out.println("m.annos empty     = " + (ua != null && ua.length == 0 ? 1 : 0) + " (want 1)");

        // Two annotations on one method -- order is not specified, so check membership, not position.
        java.lang.annotation.Annotation[] dda = md.getDeclaredAnnotations();
        boolean sawTag = false;
        boolean sawMarker = false;
        for (int i = 0; i < dda.length; i++)
        {
            if (dda[i] instanceof Tag && ((Tag) dda[i]).value().equals("twice"))
            {
                sawTag = true;
            }
            if (dda[i] instanceof Marker)
            {
                sawMarker = true;
            }
        }
        System.out.println("m.two len         = " + dda.length + " (want 2)");
        System.out.println("m.two both        = " + (sawTag && sawMarker ? 1 : 0) + " (want 1)");

        // getAnnotations coincides with declared for a method (@Inherited does not reach methods).
        System.out.println("m.getAnnotations  = " + mt.getAnnotations().length + " (want 1)");

        // A fresh array each call: stock lets the caller modify what it gets back.
        System.out.println("m.annos fresh     = " + (mt.getDeclaredAnnotations() != ma ? 1 : 0) + " (want 1)");

        // DEFAULTS through the array path, not just the by-descriptor one: the element walk and the default
        // walk are separate phases, so the plural path can be wrong where the singular is right.
        java.lang.annotation.Annotation[] pa = mp.getDeclaredAnnotations();
        Defaulted dd = pa.length == 1 && pa[0] instanceof Defaulted ? (Defaulted) pa[0] : null;
        System.out.println("m.dflt size       = " + (dd == null ? -1 : dd.size()) + " (want 9, written)");
        System.out.println("m.dflt name       = " + (dd == null ? "NULL" : dd.name()) + " (want anon)");
        System.out.println("m.dflt tags       = " + (dd == null ? -1 : dd.tags().length) + " (want 2)");

        // ---- Class.getDeclaredClasses / getClasses ----
        // The blocker: ReflectionUtils.visitAllNestedClasses needs the MEMBER classes of a class.
        Outer.makeLocalAndAnon();                        // force the local+anon classes to exist
        Class<?>[] dc = Outer.class.getDeclaredClasses();
        boolean pub = false;
        boolean priv = false;
        boolean ifc = false;
        boolean localOrAnon = false;
        for (int i = 0; i < dc.length; i++)
        {
            String nm = dc[i].getName();
            if (nm.endsWith("$PubMember")) { pub = true; }
            if (nm.endsWith("$PrivMember")) { priv = true; }
            if (nm.endsWith("$IfaceMember")) { ifc = true; }
            int dollar = nm.lastIndexOf('$');
            if (dollar >= 0 && dollar + 1 < nm.length() && nm.charAt(dollar + 1) >= '0' && nm.charAt(dollar + 1) <= '9')
            {
                localOrAnon = true;                      // Outer$1 / Outer$1LocalOnly
            }
        }
        System.out.println("declClasses len   = " + dc.length + " (want 3)");
        System.out.println("declClasses pub   = " + (pub ? 1 : 0) + " (want 1)");
        System.out.println("declClasses priv  = " + (priv ? 1 : 0) + " (want 1, private IS declared)");
        System.out.println("declClasses iface = " + (ifc ? 1 : 0) + " (want 1)");
        System.out.println("declClasses noLoc = " + (localOrAnon ? 0 : 1)
                + " (want 1, local/anonymous excluded)");

        // A class declaring none must answer EMPTY, never null and never someone else's members.
        Class<?>[] nm2 = NoMembers.class.getDeclaredClasses();
        System.out.println("declClasses empty = " + (nm2 != null && nm2.length == 0 ? 1 : 0) + " (want 1)");

        // A NESTED class must not report its SIBLINGS: its own InnerClasses table lists them all, so this is
        // what proves the outer_class_info_index filter is doing the work rather than the table order.
        System.out.println("nested sees none  = " + (Outer.PubMember.class.getDeclaredClasses().length == 0
                ? 1 : 0) + " (want 1)");

        // Mirrors are the SAME objects the rest of the VM uses.
        boolean ident = false;
        for (int i = 0; i < dc.length; i++)
        {
            if (dc[i] == Outer.PubMember.class) { ident = true; }
        }
        System.out.println("declClasses ident = " + (ident ? 1 : 0) + " (want 1)");
        System.out.println("declClasses fresh = " + (Outer.class.getDeclaredClasses() != dc ? 1 : 0)
                + " (want 1)");

        // getClasses: PUBLIC members only.
        Class<?>[] gc = Outer.class.getClasses();
        boolean gpub = false;
        boolean gpriv = false;
        for (int i = 0; i < gc.length; i++)
        {
            if (gc[i].getName().endsWith("$PubMember")) { gpub = true; }
            if (gc[i].getName().endsWith("$PrivMember")) { gpriv = true; }
        }
        System.out.println("getClasses pub    = " + (gpub ? 1 : 0) + " (want 1)");
        System.out.println("getClasses noPriv = " + (gpriv ? 0 : 1) + " (want 1)");

        // getClasses inherits a member declared in a SUPER-interface (Impl -> ImplBase -> MidIface -> TopIface).
        Class<?>[] gi = Impl.class.getClasses();
        boolean deep = false;
        for (int i = 0; i < gi.length; i++)
        {
            if (gi[i].getName().endsWith("$FromIface")) { deep = true; }
        }
        System.out.println("getClasses ifaceDeep = " + (deep ? 1 : 0)
                + " (want 1, inherited through 2 interfaces)");

        System.out.println("[probe done]");
    }
}
