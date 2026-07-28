package java.util;

/**
 * A JDK-free, mini {@code java/util/Collections}: just a static {@code sort(List)} for the probe. Operates on
 * any {@link List} through the interface (get/set/size are invokeinterface, so it sorts an ArrayList or a
 * LinkedList alike), comparing elements GENERICALLY via {@link Comparable} -- {@code ((Comparable) x).compareTo(y)}
 * is invokeinterface {@code Comparable.compareTo(Object)}, which for a String element dispatches through the
 * synthetic {@code compareTo(Object)} bridge into the typed {@code compareTo(String)}. Plain bubble sort (small
 * demand-loaded lists; O(n^2) get/set, fine here).
 */
public final class Collections
{
    public static void sort(List list)
    {
        int n = list.size();
        int i = 0;
        while (i < n - 1)
        {
            int j = 0;
            while (j < n - 1 - i)
            {
                Object x = list.get(j);
                Object y = list.get(j + 1);
                if (((Comparable) x).compareTo(y) > 0)  // generic compare -> String's bridge -> compareTo(String)
                {
                    list.set(j, y);                     // out of order -> swap through the List interface
                    list.set(j + 1, x);
                }
                j = j + 1;
            }
            i = i + 1;
        }
    }
}
