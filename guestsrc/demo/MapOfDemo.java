package demo;

import java.util.Map;

/**
 * Isolates the {@code Map.of} arity at which metal breaks. {@code demo/MapCopyDemo} already covers the
 * one-pair form ({@code ImmutableCollections.Map1}) and {@code Map.copyOf} (which reaches {@code MapN}
 * through {@code ofEntries}), and both pass — but the stock test {@code java/util/jar/Attributes/TestAttrsNL}
 * uses the FOUR-pair {@code Map.of(k,v,k,v,k,v,k,v)} overload and wild-branches into the data heap.
 *
 * <p>Each arity prints before and after, so the failing one names itself rather than having to be inferred
 * from where the output stops.
 */
public class MapOfDemo
{
    public static void main(String[] args)
    {
        System.out.println("start");

        Map<String, String> m1 = Map.of("a", "1");
        System.out.println("m1 size=" + m1.size() + " get(a)=" + m1.get("a"));

        Map<String, String> m2 = Map.of("a", "1", "b", "2");
        System.out.println("m2 size=" + m2.size() + " get(b)=" + m2.get("b"));

        Map<String, String> m3 = Map.of("a", "1", "b", "2", "c", "3");
        System.out.println("m3 size=" + m3.size() + " get(c)=" + m3.get("c"));

        Map<String, String> m4 = Map.of("a", "1", "b", "2", "c", "3", "d", "4");
        System.out.println("m4 size=" + m4.size() + " get(d)=" + m4.get("d"));

        System.out.println("done");
    }
}
