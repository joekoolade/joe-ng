package demo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import magic.Magic;

/**
 * Verifies the mini {@code java/util/HashMap} DRIVEN THROUGH the {@link Map} interface: String keys hashed/
 * compared via their real {@code hashCode}/{@code equals} (dispatched through the mini {@code Object} root),
 * including a distinct-literal (content, not identity) lookup and an overwrite -- every call invokeinterface.
 * Then iterates {@code keySet()}/{@code values()} (returned as {@link List}) with the enhanced-for. Iteration
 * order is open-addressing-dependent, so the checks are order-independent (counts + total char lengths).
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

        // keySet()/values() through the Map interface, iterated with a List enhanced-for.
        List keys = map.keySet();
        int nkeys = 0;
        int keyChars = 0;
        for (Object k : keys)
        {
            nkeys = nkeys + 1;
            keyChars = keyChars + ((String) k).length();
        }
        Magic.printStr("nkeys=" + nkeys + " keyChars=" + keyChars + "\n");           // 3, 11 (one+two+three)

        List vals = map.values();
        int nvals = 0;
        int valChars = 0;
        for (Object v : vals)
        {
            nvals = nvals + 1;
            valChars = valChars + ((String) v).length();
        }
        Magic.printStr("nvals=" + nvals + " valChars=" + valChars + "\n");           // 3, 4 (1+22+3, post-overwrite)
    }
}
