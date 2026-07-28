package java.util;

/**
 * A JDK-free, mini {@code java/util/Iterator}: the two methods the enhanced-for desugaring calls through the
 * interface. {@code it.hasNext()}/{@code it.next()} on an {@code Iterator}-typed variable are invokeinterface
 * against {@code Iterator}, so the concrete iterator's itable directory keys on this type.
 */
public interface Iterator
{
    boolean hasNext();

    Object next();
}
