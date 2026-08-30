package demo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

/**
 * Runtime annotations, marker level. Before this, joe-ng read no annotations at all: a JUnit-style runner had
 * to be hand-written with the test method names spelled out, because nothing could ask "does this method carry
 * {@code @Test}".
 *
 * <p>The subtlety worth pinning: only {@code @Retention(RUNTIME)} annotations exist in the classfile as
 * RuntimeVISIBLEAnnotations. {@code notAnnotated} and the {@code @Deprecated}-only method are the controls --
 * a marker check must not answer true for a method that merely has SOME annotation.
 */
public class AnnoDemo
{
    /** Demo-local markers, so this VM-level demo needs no JUnit on the classpath. RUNTIME retention is the
     *  whole point: without it javac writes RuntimeINVISIBLEAnnotations and the VM sees nothing. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Test { }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface BeforeEach { }

    @Test
    public void aTest() { }

    @Test
    public void anotherTest() { }

    @BeforeEach
    public void setup() { }

    @Deprecated
    public void deprecatedOnly() { }

    public void notAnnotated() { }

    private static void show(String m)
    {
        try
        {
            Method mm = AnnoDemo.class.getDeclaredMethod(m);
            System.out.println("  " + m
                    + " @Test=" + mm.isAnnotationPresent(Test.class)
                    + " @BeforeEach=" + mm.isAnnotationPresent(BeforeEach.class));
        }
        catch (Exception e)
        {
            System.out.println("  " + m + " -> " + e);
        }
    }

    public static void main(String[] args)
    {
        System.out.println("runtime annotations:");
        System.out.println("  discovery (getDeclaredMethods + @Test):");
        java.lang.reflect.Method[] all = AnnoDemo.class.getDeclaredMethods();
        int tests = 0;
        int i = 0;
        while (i < all.length)
        {
            if (all[i].isAnnotationPresent(Test.class))
            {
                System.out.println("    found @Test: " + all[i].getName());
                tests += 1;
            }
            i += 1;
        }
        System.out.println("    declared=" + all.length + " tests=" + tests);
        show("aTest");
        show("anotherTest");
        show("setup");
        show("deprecatedOnly");
        show("notAnnotated");
    }
}
