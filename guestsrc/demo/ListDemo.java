package demo;

import java.util.ArrayList;
import magic.Magic;

/**
 * Verifies the real-shaped {@code java/util/ArrayList}: add past the initial capacity (exercises the
 * grow-via-arraycopy path) with a concat argument (receiver live on the stack below it), then read back
 * size/isEmpty/get. Elements are real {@code String} objects.
 */
public class ListDemo
{
    public static void main()
    {
        ArrayList list = new ArrayList();
        int i = 0;
        while (i < 10)                                  // 10 adds -> grows past the initial capacity of 8
        {
            list.add("item" + i);
            i = i + 1;
        }
        Magic.printStr("size=" + list.size() + " empty=" + (list.isEmpty() ? 1 : 0) + "\n");   // 10, 0
        Magic.printStr("first=" + (String) list.get(0) + " last=" + (String) list.get(9) + "\n");   // item0, item9
    }
}
