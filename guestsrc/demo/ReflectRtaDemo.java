package demo;

import java.lang.reflect.Method;

/**
 * The RTA-through-reflection gap, pinned as a regression: identical code, one arm reached directly and one
 * reached only through {@code Method.invoke}.
 *
 * <p>RTA marks a method reachable, then walks its body to mark what IT calls. Reflection breaks that chain.
 * {@link #viaReflectionOnly()} is compiled on demand when {@code invoke} asks for it — the method registry
 * has every method of a loaded class — but nothing statically reachable names it, so its body was never
 * walked, {@code RtaUnseen} was never pulled, and its call site had no target. Before late link resolution
 * that site was patched to {@code VM.denylistTrap} and this demo ended in a DENYLIST TRAP naming
 * {@code RtaUnseen.tag}; the direct arm, whose callee RTA did see, passed in the same run.
 *
 * <p>The arms must name DIFFERENT callees: a shared callee would be pulled by the direct arm and the
 * reflective arm would resolve for the wrong reason. The third arm is the control — reflective too, but
 * calling into a class the direct arm already pulled, which resolves at patch time and needs no late link.
 */
public class ReflectRtaDemo
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("direct     = " + viaDirectCall());

        Method m = ReflectRtaDemo.class.getDeclaredMethod("viaReflectionOnly");
        m.setAccessible(true);
        System.out.println("reflective = " + m.invoke(null));

        Method h = ReflectRtaDemo.class.getDeclaredMethod("viaReflectionSameClass");
        h.setAccessible(true);
        System.out.println("samepruned = " + h.invoke(null));
    }

    /** Statically reachable from main: RTA walks this and pulls {@code RtaSeen}. */
    static String viaDirectCall()
    {
        return RtaSeen.tag();
    }

    /** Reached only through {@code Method.invoke}, so RTA never walks it and never pulls {@code RtaUnseen}. */
    static String viaReflectionOnly()
    {
        return RtaUnseen.tag();
    }

    /**
     * The control: also reached only reflectively, but its callee's class is one the direct arm pulled. It
     * resolves at patch time with no late linking at all (a registered class stubs every method), which is
     * what makes the arm above a test of the unpulled-CLASS case specifically rather than of reflection.
     */
    static String viaReflectionSameClass()
    {
        return RtaSeen.hidden();
    }
}
