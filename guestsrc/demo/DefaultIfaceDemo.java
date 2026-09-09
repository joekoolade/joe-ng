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

        System.out.println("done");
    }
}
