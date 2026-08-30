/*
 * Probe: is the REAL JUnit API loadable at runtime from the RAMFS jar?
 *
 * Nothing here is baked into the image except this class itself -- org.junit.jupiter.* comes only from
 * /lib/junit.jar, reached through /etc/init's `classpath=` (vm/JarFs). Deliberately tiny: the zip suite's
 * closure is ~500 classes, and a small probe is the difference between a one-minute QEMU answer and a
 * hardware-only one.
 */
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JUnitApiProbe {

    @Test
    public void tagged() { }

    public static void main(String[] args) throws Exception {
        System.out.println("junit api probe:");

        // 1. Can the annotation TYPE be loaded from the jar at all?
        Class<?> t = Test.class;
        System.out.println("  Test.class = " + (t == null ? "NULL" : t.getName()));

        // 2. Is it visible on our method, i.e. does the real annotation carry RUNTIME retention as expected?
        java.lang.reflect.Method m = JUnitApiProbe.class.getDeclaredMethod("tagged");
        System.out.println("  tagged @Test = " + m.isAnnotationPresent(Test.class));

        // 3. Does a real Assertions call run? This is the heavy one -- the stub was ours, this is theirs.
        Assertions.assertEquals(2, 1 + 1);
        System.out.println("  assertEquals(2,1+1) ok");

        // catch(Throwable) alone proves nothing -- it cannot tell a real assertion failure from the VM
        // falling over. Name what was actually thrown.
        String thrown = "NOTHING";
        try { Assertions.assertEquals(3, 1 + 1); }
        catch (Throwable e) { thrown = e.getClass().getName(); e.printStackTrace(); }
        System.out.println("  assertEquals(3,1+1) threw " + thrown);
    }
}
