package demo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal reproducer for the wild branch {@code java/util/jar/Attributes/TestAttrsNL} hits. The lazy-compile
 * trace ends at {@code java/util/LinkedHashMap.get(Object)}, and the faulting address sits in the DATA heap —
 * the signature of a virtual call reading past the end of a TIB rather than a slot that is merely empty.
 *
 * <p>{@code Attributes} stores its entries in a {@code LinkedHashMap}, so {@code Attributes.get} lands on
 * {@code LinkedHashMap.get}, which calls {@code getNode} — a method it INHERITS from {@code HashMap}.
 */
public class LinkedMapDemo
{
    public static void main(String[] args)
    {
        System.out.println("start");

        LinkedHashMap<Object, Object> lhm = new LinkedHashMap<>();
        lhm.put("a", "1");
        lhm.put("b", "2");
        System.out.println("put ok size=" + lhm.size());

        System.out.println("get(a)=" + lhm.get("a"));          // LinkedHashMap.get -> inherited HashMap.getNode
        System.out.println("get(zz)=" + lhm.get("zz"));
        System.out.println("containsKey(b)=" + lhm.containsKey("b"));

        Map<Object, Object> asMap = lhm;                        // and through the interface
        System.out.println("iface get(b)=" + asMap.get("b"));

        System.out.println("done");
    }
}
