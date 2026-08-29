package demo;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

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
        show("aTest");
        show("anotherTest");
        show("setup");
        show("deprecatedOnly");
        show("notAnnotated");
    }
}
