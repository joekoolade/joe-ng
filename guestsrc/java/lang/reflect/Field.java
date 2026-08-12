package java.lang.reflect;

/**
 * Minimal {@code java.lang.reflect.Field} for joe-ng: carries the declaring {@link Class}, field name, access
 * flags, and the field's type as the first char of its JVM descriptor ('I','J','Z','L','[',...). Enough for
 * the access + type checks {@code Atomic*FieldUpdater} performs and for a name lookup.
 */
public final class Field
{
    private final Class<?> clazz;
    private final String name;
    private final int modifiers;
    private final int typeChar;

    public Field(Class<?> clazz, String name, int modifiers, int typeChar)
    {
        this.clazz = clazz;
        this.name = name;
        this.modifiers = modifiers;
        this.typeChar = typeChar;
    }

    public String getName()
    {
        return name;
    }

    public int getModifiers()
    {
        return modifiers;
    }

    public Class<?> getDeclaringClass()
    {
        return clazz;
    }

    /** First char of the field's JVM type descriptor: 'I' int, 'J' long, 'Z' boolean, 'L'/'[' reference. */
    public int getTypeChar()
    {
        return typeChar;
    }
}
