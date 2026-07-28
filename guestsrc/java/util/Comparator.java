package java.util;

/**
 * A JDK-free, mini {@code java/util/Comparator}: a functional interface (single abstract method
 * {@code compare}) so a {@code (a, b) -> ...} lambda targets it. Its SAM takes TWO Object args -- the first
 * two-arg reference SAM the lambda machinery drives (prior lambdas were zero-arg Runnable / one-arg IntOp).
 * {@code Collections.sort(List, Comparator)} calls it to order elements without needing them to be Comparable.
 * Generic ({@code Comparator<T>}) like the real one, so an UNBOUND instance method reference whose receiver is
 * the first SAM arg -- e.g. {@code String::compareTo} as a {@code Comparator<String>} -- typechecks; the erased
 * SAM stays {@code compare(Object,Object)}. Raw usage (lambdas, static method refs) is unaffected.
 */
public interface Comparator<T>
{
    int compare(T a, T b);
}
