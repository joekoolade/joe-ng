package demo;

import java.util.HashMap;
import magic.Magic;

/**
 * Verifies the real-shaped {@code java/util/HashMap}: String keys hashed/compared via their real
 * {@code hashCode}/{@code equals} (dispatched through the mini {@link Object} root's vtable slots),
 * including a lookup with a DISTINCT literal object of equal content (content-based, not identity) and
 * an overwrite.
 */
public class MapDemo
{
    public static void main()
    {
        HashMap map = new HashMap();
        map.put("one", "1");
        map.put("two", "2");
        map.put("three", "3");
        Magic.printStr("size=" + map.size() + "\n");                                 // 3
        Magic.printStr("two=" + (String) map.get("two") + "\n");                     // 2 (distinct "two" literal, content match)
        Magic.printStr("three=" + (String) map.get("three") + "\n");                 // 3
        Magic.printStr("hasFour=" + (map.containsKey("four") ? 1 : 0) + "\n");       // 0
        Object prev = map.put("two", "22");                                          // overwrite
        Magic.printStr("prevTwo=" + (String) prev + " two2=" + (String) map.get("two") + " size=" + map.size() + "\n");   // 2, 22, 3
    }
}
