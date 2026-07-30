package demo;

import java.util.HashMap;
import java.util.Map;
import magic.Magic;

/**
 * The stock {@code java.util.HashMap} DRIVEN THROUGH the {@link Map} interface (mini collections retired):
 * String keys hashed/compared via their real {@code hashCode}/{@code equals}, including a distinct-literal
 * (content, not identity) lookup and an overwrite -- every call {@code invokeinterface}. Covers
 * {@code put}/{@code get}/{@code containsKey}/{@code size}/{@code getOrDefault}/{@code remove}. (keySet/values
 * views, merge/computeIfAbsent/forEach and the stream pipeline are exercised elsewhere; kept out to bound the
 * closure.)
 */
public class MapDemo
{
    public static void main()
    {
        Map map = new HashMap();                        // static type is the interface: all calls invokeinterface
        map.put("one", "1");
        map.put("two", "2");
        map.put("three", "3");
        Magic.printStr("size=" + map.size() + "\n");                                 // 3
        Magic.printStr("two=" + (String) map.get("two") + "\n");                     // 2 (distinct literal, content match)
        Magic.printStr("three=" + (String) map.get("three") + "\n");                 // 3
        Magic.printStr("hasFour=" + (map.containsKey("four") ? 1 : 0) + "\n");       // 0
        Object prev = map.put("two", "22");                                          // overwrite
        Magic.printStr("prevTwo=" + (String) prev + " two2=" + (String) map.get("two") + " size=" + map.size() + "\n");   // 2, 22, 3

        // getOrDefault: present key -> its value; absent -> the default.
        Magic.printStr("getOrDefault(one,X)=" + (String) map.getOrDefault("one", "X")
                + " getOrDefault(four,X)=" + (String) map.getOrDefault("four", "X") + "\n");   // 1, X

        // remove: pull out "two", check the remaining keys are still found (probe chain intact).
        Object removed = map.remove("two");
        Magic.printStr("remove(two)=" + (String) removed + " size=" + map.size()
                + " hasTwo=" + (map.containsKey("two") ? 1 : 0) + "\n");                       // 22, 2, 0
        Magic.printStr("after remove: one=" + (String) map.get("one")
                + " three=" + (String) map.get("three")
                + " removeAbsent=" + (map.remove("nope") == null ? 1 : 0) + "\n");             // 1, 3, 1
    }
}
