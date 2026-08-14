package demo;

import java.util.Arrays;

import magic.Magic;

/** Validate the object-sort path end-to-end (Arrays.sort(Object[]) -> ComparableTimSort ->
 *  reflect/Array.newInstance temp array) on a small, fast array. */
public class SortProbe
{
    public static void main(String[] args)
    {
        Integer[] arr = { 5, 3, 8, 1, 9, 2, 7, 4, 6, 0, 5, 3 };
        Arrays.sort(arr);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < arr.length)
        {
            sb.append(arr[i].intValue());
            sb.append(' ');
            i += 1;
        }
        Magic.printStr("sorted: " + sb + "\n");   // 0 1 2 3 3 4 5 5 6 7 8 9
    }
}
