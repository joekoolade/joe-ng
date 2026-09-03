/**
 * Pins reflective field get/set when the SAME FIELD NAME is declared by two unrelated classes at different
 * slots, and when the field is reached on an instance of a SUBCLASS.
 *
 * <p>This is the one step a {@code @Mixin} needs and a {@code @Option} does not: picocli reads the mixin
 * field's VALUE ({@code member.getter().get()}), while an option field is only ever inspected for its
 * annotation while the spec is built. On the metal, building a spec from ListTestEnginesCommand silently
 * loses {@code --disable-ansi-colors}, and the mixin is the only member whose value is read -- so a field
 * read landing on the wrong slot would produce exactly that, with no exception.
 *
 * <p>The launcher's classes collide by name on purpose here: {@code BaseCommand} and {@code MainCommand} BOTH
 * declare {@code ansiColorOption}, {@code helpRequested}, {@code versionRequested} and {@code commandSpec},
 * and they sit at DIFFERENT positions in the two classes. A lookup keyed on the name alone cannot tell them
 * apart, and reading one through the other's offset returns a plausible value rather than failing.
 *
 * <p>Each arm round-trips a value it just wrote, because a read alone cannot distinguish a correct null from
 * a wrong slot that also happens to hold null.
 */
public class FieldSlotProbe
{
    /** Two classes sharing field NAMES at different positions -- the shape, independent of picocli. */
    public static class Base
    {
        Object first = "base-first";
        Object shared = "base-shared";
        int n = 11;
    }

    public static class Sub extends Base
    {
        Object extra = "sub-extra";
    }

    public static class Other
    {
        Object shared = "other-shared";
        Object first = "other-first";
        int n = 22;
    }

    public static void main(String[] args) throws Exception
    {
        // --- synthetic control: same names, different slots, read through a SUBCLASS instance -----------
        // Nothing is passed to String.valueOf: a wrong slot yields a non-null GARBAGE reference, and
        // printing it dispatches toString() on it -- which faults and hides the reading that produced it.
        // Each value is reported by identity against the constant it should be, inside a guard.
        Sub sub = new Sub();
        Base plainBase = new Base();
        Other other = new Other();

        java.lang.reflect.Field bShared = Base.class.getDeclaredField("shared");
        java.lang.reflect.Field oShared = Other.class.getDeclaredField("shared");
        bShared.setAccessible(true);
        oShared.setAccessible(true);

        // The control that separates the two candidate faults: the SAME Field object, read on an instance
        // of the DECLARING class and on an instance of a SUBCLASS. If only the subclass read is wrong, the
        // fault is inherited-field offset resolution; if both are, it is reflective object reads generally.
        show("Base.shared  on Base ", bShared, plainBase, "base-shared");
        show("Base.shared  on Sub  ", bShared, sub, "base-shared");
        show("Other.shared on Other", oShared, other, "other-shared");
        show("Base.first   on Sub  ", accessible(Base.class, "first"), sub, "base-first");
        show("Sub.extra    on Sub  ", accessible(Sub.class, "extra"), sub, "sub-extra");

        try
        {
            bShared.set(sub, "written");
            Object back = bShared.get(sub);
            System.out.println("round-trip   on Sub   = " + ("written".equals(back) ? "written  OK" : "WRONG"));
            Object oth = oShared.get(other);
            System.out.println("Other untouched       = "
                    + ("other-shared".equals(oth) ? "other-shared  OK" : "CLOBBERED"));
        }
        catch (Throwable t)
        {
            System.out.println("round-trip THREW " + t);
        }

        // --- the real collision the launcher hits -------------------------------------------------------
        Class<?> base = Class.forName("org.junit.platform.console.command.BaseCommand");
        Class<?> main = Class.forName("org.junit.platform.console.command.MainCommand");
        Class<?> engines = Class.forName("org.junit.platform.console.command.ListTestEnginesCommand");
        Class<?> mixinType = Class.forName("org.junit.platform.console.options.AnsiColorOptionMixin");

        Object engineCmd = newOf(engines);
        Object mainCmd = newOf(main);
        Object mixinVal = newOf(mixinType);

        roundTrip("BaseCommand.ansiColorOption on ListTestEnginesCommand",
                field(base, "ansiColorOption"), engineCmd, mixinVal);
        roundTrip("MainCommand.ansiColorOption on MainCommand",
                field(main, "ansiColorOption"), mainCmd, newOf(mixinType));
    }

    /** Reports a reflective read by IDENTITY against the constant the field was initialised with -- never
     *  by printing the value, since a wrong slot returns a reference whose toString() faults. */
    private static void show(String label, java.lang.reflect.Field f, Object inst, String want)
    {
        try
        {
            Object v = f.get(inst);
            if (v == null)
            {
                System.out.println(label + " = NULL              (want " + want + ")  <== WRONG");
                return;
            }
            // equals, not ==: joe-ng does not intern a literal to one object across use sites, so identity would
            // report a correct read as wrong. (It did, on the first run of this probe.)
            boolean same = want.equals(v);
            String cls;
            try
            {
                cls = v.getClass().getName();
            }
            catch (Throwable t)
            {
                cls = "<getClass THREW>";
            }
            System.out.println(label + " = " + (same ? want + "  OK" : "type " + cls + "  <== WRONG SLOT")
                    + (same ? "" : " (want " + want + ")"));
        }
        catch (Throwable t)
        {
            System.out.println(label + " THREW " + t);
        }
    }

    private static java.lang.reflect.Field accessible(Class<?> c, String n) throws Exception
    {
        java.lang.reflect.Field f = c.getDeclaredField(n);
        f.setAccessible(true);
        return f;
    }

    private static java.lang.reflect.Field field(Class<?> c, String n) throws Exception
    {
        java.lang.reflect.Field[] fs = c.getDeclaredFields();
        int i = 0;
        while (i < fs.length)
        {
            if (fs[i].getName().equals(n))
            {
                fs[i].setAccessible(true);
                return fs[i];
            }
            i += 1;
        }
        throw new NoSuchFieldException(n);
    }

    private static Object newOf(Class<?> c) throws Exception
    {
        try
        {
            java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        }
        catch (Throwable t)
        {
            Class<?> fac = Class.forName("org.junit.platform.console.command.ConsoleTestExecutor$Factory");
            java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor(fac);
            k.setAccessible(true);
            return k.newInstance(new Object[] { null });
        }
    }

    private static void roundTrip(String label, java.lang.reflect.Field f, Object inst, Object val)
    {
        try
        {
            Object before = f.get(inst);
            f.set(inst, val);
            Object after = f.get(inst);
            System.out.println("RT " + label);
            System.out.println("   before=" + (before == null ? "null" : before.getClass().getName())
                    + " afterIsWhatWeWrote=" + (after == val)
                    + " after=" + (after == null ? "null" : after.getClass().getName())
                    + ((after == val) ? "  OK" : "  <== WRONG SLOT"));
        }
        catch (Throwable t)
        {
            System.out.println("RT " + label + " THREW " + t);
        }
    }
}
