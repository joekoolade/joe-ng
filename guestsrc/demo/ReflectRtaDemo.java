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

        RtaIface inst = () -> "iface-instance";        // pulls RtaIface, exactly as the zip harness pulls
        System.out.println("ifaceinst  = " + inst.describe());   //   Arguments through its signatures

        Method i = ReflectRtaDemo.class.getDeclaredMethod("viaReflectionIfaceStatic");
        i.setAccessible(true);
        System.out.println("ifacestat  = " + i.invoke(null));

        Method n = ReflectRtaDemo.class.getDeclaredMethod("viaReflectionNew");
        n.setAccessible(true);
        Object made = n.invoke(null);
        // getClass() is an Object slot, so it resolves normally; the NAME it reports is the real check --
        // the bug this replaced handed back an object carrying an unrelated class's TIB, which would answer
        // with the wrong name here rather than failing.
        System.out.println("newarm     = " + (made == null ? "NULL" : made.getClass().getName()));

        RtaSpeak sp = new RtaSpeaker();
        System.out.println("ifacecall  = " + sp.reached());     // statically reachable: itable entry filled

        Method p = ReflectRtaDemo.class.getDeclaredMethod("viaReflectionIfaceCall");
        p.setAccessible(true);
        System.out.println("ifaceprune = " + p.invoke(null));

        Method q = ReflectRtaDemo.class.getDeclaredMethod("viaReflectionIfaceLate");
        q.setAccessible(true);
        System.out.println("ifacelate  = " + q.invoke(null));

        Method r = ReflectRtaDemo.class.getDeclaredMethod("viaReflectionIfaceDefault");
        r.setAccessible(true);
        System.out.println("ifacedflt  = " + r.invoke(null));
    }

    /**
     * An {@code invokeinterface} whose INTERFACE is absent from the batch entirely — the twin of the
     * {@code newresolve}/{@code linkresolve} gaps. RTA runs at batch time and never walks this body, so
     * neither {@link RtaLate} nor {@link RtaLater} is pulled; the {@code new} defers (that half already
     * worked), and the call then scans an itable directory with no entry for the interface, against a
     * call-site interface Type of 0. Before the late path it fell off the 0-terminator as a bare NPE.
     *
     * <p>This is NOT what {@code ifaceprune} pins: there the interface IS present and only the slot's entry
     * was empty. The two guards even throw different exceptions — a directory miss is NPE, an implausible
     * slot is AIOOBE.
     */
    static String viaReflectionIfaceLate()
    {
        RtaLate x = new RtaLater();
        return x.late();
    }

    /**
     * The same miss, but for an interface DEFAULT — no class in the receiver's chain declares it, so only the
     * interface tier of the resolve can answer. A class-typed receiver reaches a default through the itable
     * (a class has no vtable slot for one), so this misses the directory exactly as the arm above does and
     * then needs the extra tier the arm above does not.
     */
    static String viaReflectionIfaceDefault()
    {
        RtaLater x = new RtaLater();
        return x.viaDefault();
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
     * A static method on a LOADED interface, named only from here. The interface is pulled (the lambda above
     * does it), but a static interface method lands in no dispatch table at all, so resolution has to fall
     * through to compiling the body from the class's own blob. This is the zip harness's {@code Arguments.of}.
     */
    static String viaReflectionIfaceStatic()
    {
        return RtaIface.tag();
    }

    /**
     * A {@code new} of a class nothing pulled — the other half of the gap. A {@code new} is resolved at
     * COMPILE time (it needs the instance size and TIB while emitting), so it cannot take a link stub the way
     * a call does; the site defers instead, and resolves when it is actually reached.
     *
     * <p>It returns the object rather than calling a method on it, and that restraint is deliberate: an
     * {@code invokevirtual} on a class that is unregistered AT COMPILE TIME is a SEPARATE and still-open bug —
     * {@code Loader.globalVtableSlot} answers 0 when it finds no match, so the call dispatches through vtable
     * slot 0 of whatever the receiver turns out to be. Mixing that in would make this arm test two things and
     * pin neither. The caller checks the object's class name instead, which is precisely what the old
     * wrong-TIB behaviour would get wrong.
     */
    static Object viaReflectionNew()
    {
        return new RtaMade();
    }

    /**
     * An INTERFACE call to a method nothing statically reachable names. This is the shape the zip run died on:
     * {@code EnumMap.getKeyUniverse}'s one {@code invokeinterface} threw a bare AIOOBE, which is
     * {@code dispatchTargetGuard} — the itable for the interface WAS found and the slot's entry was empty. If
     * the entry is empty because RTA pruned the impl, this arm reproduces it in ten lines instead of 400
     * classes.
     */
    static String viaReflectionIfaceCall()
    {
        RtaSpeak s = new RtaSpeaker();
        return s.pruned();
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
