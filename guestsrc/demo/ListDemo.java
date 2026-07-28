package demo;

import java.util.ArrayList;
import java.util.List;
import magic.Magic;

/**
 * The {@code java/util} collection probe: hold an {@code ArrayList} by the {@link List} interface and drive it
 * entirely through {@code invokeinterface} -- {@code add} (grow past the initial capacity of 8 via arraycopy,
 * with a live concat receiver on the stack), {@code size}, {@code isEmpty}, {@code get}. Unlike the zero-arg
 * {@code Runnable}, {@code List} has a multi-method itable whose methods take args and return values. A helper
 * takes the interface as a *parameter* so dispatch also happens on a value the JIT can't see the concrete type of.
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
