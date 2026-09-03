/**
 * Pins why an inherited {@code @Mixin} field contributes no options on the metal.
 *
 * <p>Building a spec from the real {@code ListTestEnginesCommand} yields {@code --disable-banner}, {@code
 * --help} and {@code --version} but NOT {@code --disable-ansi-colors}; building one from {@code MainCommand}
 * yields the ansi option fine. The difference is where the {@code @Mixin AnsiColorOptionMixin} field is
 * DECLARED -- MainCommand declares its own, ListTestEnginesCommand inherits BaseCommand's -- even though the
 * plain {@code @Option} fields on that same superclass do come through.
 *
 * <p>picocli reaches a mixin in three steps, so all three are measured separately instead of inferring which
 * failed from a missing option: the field must be enumerated by {@code getDeclaredFields}, it must answer
 * {@code isAnnotationPresent(Mixin.class)}, and its type must then build a CommandSpec of its own carrying the
 * option. Any one of them failing looks identical from outside.
 */
public class MixinProbe
{
    public static void main(String[] args) throws Exception
    {
        Class<?> mixinAnno = Class.forName(
                "org.junit.platform.console.shadow.picocli.CommandLine$Mixin");
        Class<?> optAnno = Class.forName(
                "org.junit.platform.console.shadow.picocli.CommandLine$Option");

        fields("org.junit.platform.console.command.BaseCommand", mixinAnno, optAnno);
        fields("org.junit.platform.console.command.MainCommand", mixinAnno, optAnno);
        fields("org.junit.platform.console.command.ListTestEnginesCommand", mixinAnno, optAnno);

        // Step 3 on its own: does the mixin TYPE build a spec carrying its option?
        Class<?> mc = Class.forName("org.junit.platform.console.options.AnsiColorOptionMixin");
        java.lang.reflect.Constructor<?> k = mc.getDeclaredConstructor();
        k.setAccessible(true);
        Object inst = k.newInstance();
        org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec s =
                org.junit.platform.console.shadow.picocli.CommandLine.Model.CommandSpec
                        .forAnnotatedObject(inst);
        System.out.println("mixin spec options=" + s.options().size() + " (want 1)");
        for (org.junit.platform.console.shadow.picocli.CommandLine.Model.OptionSpec o : s.options())
        {
            System.out.println("   o " + o.longestName());
        }
    }

    /** The seven annotations picocli's TypedMember.createIfAnnotated tests, in its own order. A field that
     *  answers false to all seven is SKIPPED SILENTLY -- no exception, just an option that never appears,
     *  which is exactly what the missing --disable-ansi-colors looks like. */
    private static final String[] PICOCLI_ANNOS = {
        "Option", "Parameters", "ArgGroup", "Unmatched", "Mixin", "Spec", "ParentCommand"
    };

    @SuppressWarnings("unchecked")
    private static void fields(String cn, Class<?> mixinAnno, Class<?> optAnno) throws Exception
    {
        Class<?> c = Class.forName(cn);
        java.lang.reflect.Field[] fs = c.getDeclaredFields();
        System.out.println("class " + cn.substring(cn.lastIndexOf('.') + 1) + " declaredFields=" + fs.length);
        int i = 0;
        while (i < fs.length)
        {
            boolean isMixin = fs[i].isAnnotationPresent(
                    (Class<? extends java.lang.annotation.Annotation>) mixinAnno);
            boolean isOpt = fs[i].isAnnotationPresent(
                    (Class<? extends java.lang.annotation.Annotation>) optAnno);
            Object got = fs[i].getAnnotation(
                    (Class<? extends java.lang.annotation.Annotation>) mixinAnno);
            // getGenericType() is what TypedMember needs to build its ITypeInfo, and it is the newest code
            // on this path -- so it is reported rather than assumed, along with the full seven-annotation
            // verdict that decides whether the field is classified at all.
            String gen;
            try
            {
                Object g = fs[i].getGenericType();
                gen = (g == null) ? "NULL" : g.toString();
            }
            catch (Throwable t)
            {
                gen = "THREW " + t;
            }
            StringBuilder present = new StringBuilder();
            int a = 0;
            while (a < PICOCLI_ANNOS.length)
            {
                Class<?> ac = Class.forName(
                        "org.junit.platform.console.shadow.picocli.CommandLine$" + PICOCLI_ANNOS[a]);
                if (fs[i].isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) ac))
                {
                    present.append(PICOCLI_ANNOS[a]).append(" ");
                }
                a += 1;
            }
            System.out.println("   f " + fs[i].getName()
                    + " type=" + fs[i].getType().getName()
                    + " generic=" + gen
                    + " mixinPresent=" + isMixin
                    + " mixinGet=" + (got != null)
                    + " optPresent=" + isOpt
                    + " anns=[" + present.toString().trim() + "]");
            i += 1;
        }
    }
}
