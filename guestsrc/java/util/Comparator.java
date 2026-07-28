package java.util;

/**
 * A JDK-free, mini {@code java/util/Comparator}: a functional interface (single abstract method
 * {@code compare}) so a {@code (a, b) -> ...} lambda targets it. Its SAM takes TWO Object args -- the first
 * two-arg reference SAM the lambda machinery drives (prior lambdas were zero-arg Runnable / one-arg IntOp).
 * {@code Collections.sort(List, Comparator)} calls it to order elements without needing them to be Comparable.
 */
public interface Comparator
{
    int compare(Object a, Object b);
}
