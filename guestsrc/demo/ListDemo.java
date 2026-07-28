package demo;

import java.util.ArrayList;
import java.util.Collection;
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

        // Transitive-interface dispatch: hold the ArrayList by Iterable (super-interface of List). The
        // enhanced-for emits invokeinterface java/lang/Iterable.iterator -- resolvable only because the
        // itable directory now carries the transitively-implemented Iterable, not just the direct List.
        Iterable iterable = list;
        int itCount = 0;
        for (Object o : iterable)
        {
            itCount = itCount + 1;
        }
        Magic.printStr("iterableForEach count=" + itCount + "\n");                // 10

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

        // contains/indexOf: search with distinct literals (content-equal, not the same object) -> real equals.
        Magic.printStr("indexOf(\"item3\")=" + list.indexOf("item3")             // 3
                + " indexOf(\"nope\")=" + list.indexOf("nope") + "\n");          // -1
        Magic.printStr("contains(\"item7\")=" + (list.contains("item7") ? 1 : 0) // 1
                + " contains(\"xyz\")=" + (list.contains("xyz") ? 1 : 0) + "\n");// 0
        Magic.printStr("ll.indexOf(\"node2\")=" + ll.indexOf("node2")            // 2
                + " ll.contains(\"node4\")=" + (ll.contains("node4") ? 1 : 0)    // 1
                + " ll.contains(\"node9\")=" + (ll.contains("node9") ? 1 : 0) + "\n");   // 0

        // remove (mutation) -- kept last so the assertions above still hold. remove(int) shifts/relinks;
        // remove(Object) reuses the equals search. list=[item0..item9], ll=[node0..node4].
        Object gone = list.remove(5);                                            // "item5"; tail shifts down
        Magic.printStr("remove(5)=" + (String) gone + " size=" + list.size()     // item5, 9
                + " get(5)=" + (String) list.get(5) + "\n");                      // item6 (shifted)
        int r1 = list.remove("item0") ? 1 : 0;                                   // head, by content -> 1
        int r2 = list.remove("nope") ? 1 : 0;                                    // absent -> 0
        Magic.printStr("remove(\"item0\")=" + r1 + " remove(\"nope\")=" + r2
                + " size=" + list.size() + " first=" + (String) list.get(0) + "\n");   // 1,0,8,item1

        Object llGone = ll.remove(0);                                            // head "node0"
        int llr = ll.remove("node4") ? 1 : 0;                                    // tail, by content -> 1
        Magic.printStr("ll.remove(0)=" + (String) llGone + " ll.remove(\"node4\")=" + llr
                + " ll.size=" + ll.size() + " ll.first=" + (String) ll.get(0)
                + " ll.last=" + (String) ll.get(ll.size() - 1) + "\n");          // node0,1,3,node1,node3

        // Collection supertype (List extends Collection extends Iterable): drive through the MIDDLE interface.
        Collection coll = new ArrayList();
        coll.add("x");
        coll.add("y");
        coll.add("z");
        Magic.printStr("coll.size=" + coll.size() + " contains(\"y\")=" + (coll.contains("y") ? 1 : 0)
                + " isEmpty=" + (coll.isEmpty() ? 1 : 0) + " iter=" + countVia(coll) + "\n");   // 3,1,0,3
        coll.remove("y");                                                        // Collection.remove(Object)
        Magic.printStr("after remove(\"y\"): size=" + coll.size()
                + " contains(\"y\")=" + (coll.contains("y") ? 1 : 0) + "\n");    // 2,0

        // Polymorphic countVia(Collection) -- Collection-typed for-each on both concrete impls.
        Collection cll = new LinkedList();
        cll.add("a");
        cll.add("b");
        Magic.printStr("countVia(ArrayList)=" + countVia(coll)                   // 2
                + " countVia(LinkedList)=" + countVia(cll) + "\n");              // 2
    }

    /** Collection-typed param: the enhanced-for emits invokeinterface Collection.iterator (via Iterable). */
    private static int countVia(Collection c)
    {
        int n = 0;
        for (Object o : c)
        {
            n = n + 1;
        }
        return n;
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
