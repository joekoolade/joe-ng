package demo;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import magic.Magic;

/**
 * The {@code java/util} collection probe: hold a list by the {@link List} interface and drive it entirely
 * through {@code invokeinterface} -- {@code add} (ArrayList grows past its cap-8 backing array via arraycopy),
 * {@code size}, {@code isEmpty}, {@code get}, plus an enhanced-for ({@code iterator()}/{@code hasNext}/
 * {@code next}). {@code forEachLen(List)} is then called on BOTH an {@code ArrayList} and a {@code LinkedList}
 * -- the same interface call sites dispatch polymorphically into the two impls (and their two iterators).
 */
public class ListDemo
{
    public static void main()
    {
        List list = new ArrayList();                    // static type is the interface: all calls are invokeinterface
        int i = 0;
        while (i < 10)                                  // 10 adds -> grows past the initial capacity of 8
        {
            list.add("item" + i);
            i = i + 1;
        }
        Magic.printStr("size=" + list.size() + " empty=" + (list.isEmpty() ? 1 : 0) + "\n");        // 10, 0
        Magic.printStr("first=" + (String) list.get(0) + " last=" + (String) list.get(9) + "\n");   // item0, item9
        Magic.printStr("sumLenViaIface=" + totalLen(list) + "\n");                                   // 50 (10 x "itemN")

        // Enhanced-for: javac desugars to list.iterator() + hasNext()/next(), all invokeinterface.
        String joined = "";
        int count = 0;
        for (Object o : list)
        {
            joined = joined + (String) o + " ";
            count = count + 1;
        }
        Magic.printStr("forEach count=" + count + " joined=" + joined + "\n");   // 10, "item0 ... item9 "

        // Second List impl: a singly-linked LinkedList driven through the SAME interface + helpers.
        List ll = new LinkedList();
        int j = 0;
        while (j < 5)
        {
            ll.add("node" + j);
            j = j + 1;
        }
        Magic.printStr("ll.size=" + ll.size() + " ll.empty=" + (ll.isEmpty() ? 1 : 0)
                + " ll.first=" + (String) ll.get(0) + " ll.last=" + (String) ll.get(4) + "\n");   // 5,0,node0,node4
        String lj = "";
        for (Object o : ll)                             // node-walking iterator, same for-each shape
        {
            lj = lj + (String) o + " ";
        }
        Magic.printStr("ll.forEach=" + lj + "\n");                                // node0 node1 node2 node3 node4

        // Polymorphism through one call site: forEachLen(List) on each concrete type.
        Magic.printStr("forEachLen(ArrayList)=" + forEachLen(list)                // 50
                + " forEachLen(LinkedList)=" + forEachLen(ll) + "\n");            // 25 (5 x "nodeN")
    }

    /** Interface-typed param: {@code size()}/{@code get()} dispatch via invokeinterface on the passed-in List. */
    private static int totalLen(List l)
    {
        int n = 0;
        int i = 0;
        while (i < l.size())
        {
            n = n + ((String) l.get(i)).length();
            i = i + 1;
        }
        return n;
    }

    /** Same enhanced-for on a List param -- iterator() dispatches to whichever impl was passed. */
    private static int forEachLen(List l)
    {
        int n = 0;
        for (Object o : l)
        {
            n = n + ((String) o).length();
        }
        return n;
    }
}
