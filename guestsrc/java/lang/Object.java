package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Object}: the root class. It exists so {@code hashCode()} and
 * {@code equals(Object)} get canonical vtable slots that subclasses ({@link String}) override — which is
 * what lets {@code java/util/HashMap} dispatch {@code key.hashCode()}/{@code key.equals()} (static type
 * Object) into a String key's real implementations. These root bodies are placeholders (String overrides
 * both); a real identity hash would need a native. Compiled as a {@code java.base} patch.
 */
public class Object
{
    public int hashCode()
    {
        return 0;
    }

    public boolean equals(Object o)
    {
        return this == o;
    }
}
