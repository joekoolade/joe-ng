package demo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import magic.Magic;

/**
 * The {@code java/util} collection probe on the UNMODIFIED stock {@code java.util.ArrayList} (mini collections
 * retired). Hold the list by the {@link List} interface and drive it through {@code invokeinterface} --
 * {@code add} (grows its Object[] backing via {@code Arrays.copyOf}/{@code System.arraycopy}), {@code size},
 * {@code isEmpty}, {@code get} ({@code Objects.checkIndex}), plus an enhanced-for ({@code iterator()}/
 * {@code hasNext}/{@code next} -> {@code ArrayList$Itr}), {@code contains}/{@code indexOf} (real
 * {@code String.equals}), and {@code remove}. Also drives it through the super-interfaces {@link Iterable}
 * and {@link Collection}. (Sort/stream/LinkedList are exercised elsewhere; kept out here to bound the closure.)
 */
public class ListDemo
{
    public static void main()
    {
        List list = new ArrayList();                    // static type is the interface: all calls are invokeinterface
        int i = 0;
        while (i < 10)                                  // 10 adds -> grows past the initial capacity
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

        // Transitive-interface dispatch: hold the ArrayList by Iterable (super-interface of List).
        Iterable iterable = list;
        int itCount = 0;
        for (Object o : iterable)
        {
            itCount = itCount + 1;
        }
        Magic.printStr("iterableForEach count=" + itCount + "\n");                // 10

        // contains/indexOf: search with distinct literals (content-equal, not the same object) -> real equals.
        Magic.printStr("indexOf(\"item3\")=" + list.indexOf("item3")             // 3
                + " indexOf(\"nope\")=" + list.indexOf("nope") + "\n");          // -1
        Magic.printStr("contains(\"item7\")=" + (list.contains("item7") ? 1 : 0) // 1
                + " contains(\"xyz\")=" + (list.contains("xyz") ? 1 : 0) + "\n");// 0

        // remove (mutation), kept last so the assertions above still hold. remove(int) shifts via arraycopy.
        Object gone = list.remove(5);                                            // "item5"; tail shifts down
        Magic.printStr("remove(5)=" + (String) gone + " size=" + list.size()     // item5, 9
                + " get(5)=" + (String) list.get(5) + "\n");                      // item6 (shifted)
        int r1 = list.remove("item0") ? 1 : 0;                                   // head, by content -> 1
        int r2 = list.remove("nope") ? 1 : 0;                                    // absent -> 0
        Magic.printStr("remove(\"item0\")=" + r1 + " remove(\"nope\")=" + r2
                + " size=" + list.size() + " first=" + (String) list.get(0) + "\n");   // 1,0,8,item1

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

        // subList view (#34): a live window over the backing ArrayList (java.util.ArrayList$SubList). get/size
        // read through to the parent. Now that the mini List overlay is retired, subList host-compiles.
        List base = new ArrayList();
        base.add("p");
        base.add("q");
        base.add("r");
        base.add("s");
        List sub = base.subList(1, 3);                                           // [q, r]
        Magic.printStr("subList(1,3).size=" + sub.size()
                + " sub[0]=" + (String) sub.get(0)
                + " sub[1]=" + (String) sub.get(1) + "\n");                      // 2, q, r

        // stock LinkedList (#34): a SECOND List impl (mini LinkedList retired) -> the same List-typed call
        // sites (totalLen / enhanced-for) dispatch polymorphically across ArrayList and stock LinkedList.
        List ll = new LinkedList();
        ll.add("aa");
        ll.add("bbb");
        ll.add("c");
        Magic.printStr("linkedList size=" + ll.size()
                + " get(1)=" + (String) ll.get(1)                               // bbb
                + " totalLenViaIface=" + totalLen(ll) + "\n");                  // 2+3+1 = 6
        int llCount = 0;
        for (Object o : ll)                                                     // LinkedList$ListItr via Iterable
        {
            llCount += 1;
        }
        Magic.printStr("linkedList forEach count=" + llCount + "\n");           // 3
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
}
