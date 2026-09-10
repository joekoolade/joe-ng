package demo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;

/**
 * Reproducer for the imap fault the dispatch-target guard localised to {@code TestAttrsNL.test:115}.
 *
 * <p>The shape that matters is an INTERFACE DEFAULT method reached on more than one implementor.
 * {@code java.util.jar.Attributes} implements {@code Map} but does NOT override {@code forEach}, so
 * {@code attrs.forEach(...)} dispatches to {@code Map}'s default body through the itable. In the failing
 * test, {@code Map.forEach} had already been compiled for a different implementor
 * ({@code ImmutableCollections.MapN}) nine lines earlier — so this drives the default on one implementor
 * first, then on a second, which is the ordering under suspicion.
 *
 * <p>{@code LinkedHashMap} is here as the control: it OVERRIDES {@code forEach}, so it goes through its own
 * vtable slot and should be unaffected either way.
 */
public class DefaultIfaceDemo
{
    public static void main(String[] args)
    {
        System.out.println("start");

        Map<String, String> imm = Map.of("a", "1");
        imm.forEach((k, v) -> System.out.println("  imm " + k + "=" + v));
        System.out.println("immutable forEach ok");

        Map<Object, Object> lhm = new LinkedHashMap<>();
        lhm.put("b", "2");
        lhm.forEach((k, v) -> System.out.println("  lhm " + k + "=" + v));
        System.out.println("linked forEach ok (overrides forEach)");

        Attributes attrs = new Attributes();
        attrs.putValue("Key", "val");
        System.out.println("attrs size=" + attrs.size());
        attrs.forEach((k, v) -> System.out.println("  attrs " + k + "=" + v));
        System.out.println("attributes forEach ok (inherits Map.forEach)");

        // java/lang/Object's PUBLIC methods, called through an INTERFACE-typed receiver. javac emits these as
        // an invokeinterface whose owner is the INTERFACE (JVMS 5.4.3.4 makes interface resolution search
        // Object too), and no such method has an itable slot -- so the directory walk used to index a slot
        // holding a REAL interface method and call that, returning a plausible, stable, wrong value instead
        // of failing. Compared against the same calls on an Object-typed reference, which always worked.
        Object obj = lhm;
        System.out.println("iface getClass ok = " + (lhm.getClass() == obj.getClass() ? 1 : 0) + " (want 1)");
        System.out.println("iface hashCode ok = " + (lhm.hashCode() == obj.hashCode() ? 1 : 0) + " (want 1)");
        System.out.println("iface toString ok = " + (lhm.toString().equals(obj.toString()) ? 1 : 0) + " (want 1)");
        System.out.println("iface equals   ok = " + (lhm.equals(obj) ? 1 : 0) + " (want 1)");
        System.out.println("iface getClass    = " + lhm.getClass().getName() + " (want java.util.LinkedHashMap)");

        // Object's methods on a SYNTHESISED LAMBDA receiver. A lambda has no classfile, so it is in no class
        // registry -- exactly as a real JVM's HIDDEN classes are undiscoverable by Class.forName -- and its
        // TIB used to be one word, with no vtable at all. equals/hashCode/toString then resolved NOWHERE:
        // not in the registry, and not in the itable directory, which holds only interface methods.
        // JVMS 5.4.6 says selection walks the receiver's hierarchy, which is rooted at Object, so these must
        // select Object's implementations -- identity semantics, which LambdaMetafactory specifies as
        // unpredictable-but-identity for a captured function object.
        Runnable lam = () -> { };
        Object lamObj = lam;
        System.out.println("lambda equals self = " + (lam.equals(lamObj) ? 1 : 0) + " (want 1)");
        System.out.println("lambda equals othr = " + (lam.equals(new Object()) ? 1 : 0) + " (want 0)");
        System.out.println("lambda hash stable = " + (lam.hashCode() == lam.hashCode() ? 1 : 0) + " (want 1)");
        System.out.println("lambda toString ok = " + (lam.toString() != null ? 1 : 0) + " (want 1)");
        System.out.println("lambda getClass ok = " + (lam.getClass() != null ? 1 : 0) + " (want 1)");

        // The launcher's own trigger is a lambda comparator through Stream.sorted(), whose SortedOps$OfRef
        // constructor calls equals on it. That arm is NOT here, and deliberately: it drags the whole stream
        // pipeline into every suite boot and then dies in an UNRELATED, PRE-EXISTING gap --
        // StreamOpFlag.<clinit> -> EnumMap.<init> -> getKeyUniverse NPEs, i.e.
        // SharedSecrets.getJavaLangAccess() reads null in the suite's SHARED loader state. The same statement
        // passes when this demo is launched ALONE, which is the "works in one closure, broken in another"
        // signature rather than anything about lambda dispatch. The five arms above test the fix directly.

        // A DEFAULT method declared by an interface implemented TWO CLASSES UP the chain, invoked on a
        // class-typed receiver that implements nothing itself. That is JUnit's shape:
        // SuiteEngineDescriptor extends EngineDescriptor extends AbstractTestDescriptor implements
        // TestDescriptor, with accept() a default on TestDescriptor. The late-resolution fallback used to
        // collect only the RECEIVER's own interfaces -- an empty list here -- and report the method missing.
        System.out.println("chain default      = " + new Leaf().greet() + " (want hi from Greets)");
        System.out.println("chain dflt via mid = " + ((Mid) new Leaf()).greet() + " (want hi from Greets)");

        System.out.println("done");
    }

    /** The default lives HERE, on an interface no subclass re-declares. */
    interface Greets
    {
        default String greet()
        {
            return "hi from Greets";
        }
    }

    /** Implements the interface... */
    static class Base implements Greets
    {
    }

    /** ...and these two do not, so a receiver-only interface search finds nothing. */
    static class Mid extends Base
    {
    }

    static class Leaf extends Mid
    {
    }
}
