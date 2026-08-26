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

        System.out.println("done");
    }
}
