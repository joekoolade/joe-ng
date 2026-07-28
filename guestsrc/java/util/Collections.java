package java.util;

/**
 * A JDK-free, mini {@code java/util/Collections}: just a static {@code sort(List)} for the probe. Operates on
 * any {@link List} through the interface (get/set/size are invokeinterface, so it sorts an ArrayList or a
 * LinkedList alike), comparing elements with the mini {@code String}'s real {@code compareTo} (invokevirtual)
 * -- the real Collections.sort is generic over {@code Comparable}; the mini String isn't Comparable-shaped, so
 * this specialises to String, which is enough to tie the collection stack back to the String surface. Plain
 * bubble sort (small demand-loaded lists; O(n^2) get/set, fine here).
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
                String a = (String) list.get(j);
                String b = (String) list.get(j + 1);
                if (a.compareTo(b) > 0)                 // out of order -> swap through the List interface
                {
                    list.set(j, b);
                    list.set(j + 1, a);
                }
                j = j + 1;
            }
            i = i + 1;
        }
    }
}
