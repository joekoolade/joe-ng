package java.lang;

import java.util.Iterator;

/**
 * A JDK-free, mini {@code java/lang/Iterable}: just {@code iterator()}. The enhanced-for loop
 * ({@code for (x : coll)}) requires the collection's static type to be an {@code Iterable}, so the mini
 * {@code List} extends this; javac desugars the loop into {@code coll.iterator()} + {@code hasNext}/{@code next}.
 */
public interface Iterable
{
    Iterator iterator();
}
