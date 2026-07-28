package java.lang;

/**
 * A JDK-free, real-shaped {@code java/lang/Comparable<T>}: generic, exactly like the real one, so that a class
 * implementing {@code Comparable<Self>} with a {@code compareTo(Self)} makes javac synthesise a
 * {@code compareTo(Object)} BRIDGE method (checkcast + invokevirtual the typed one). That bridge is how
 * {@code invokeinterface Comparable.compareTo(Object)} (e.g. from a generic {@code Collections.sort}) reaches
 * the element's typed {@code compareTo} -- the first bridge-method dispatch the loader exercises.
 */
public interface Comparable<T>
{
    int compareTo(T o);
}
