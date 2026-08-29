package demo;

/**
 * Class literals for ARRAY types. Before this demo's fix, {@code String[].class} produced a mirror whose
 * {@code isArray()} was hardcoded false and whose {@code getName()} was empty: the literal already resolved
 * to a real array Type (the loader has had those since the reflection arc), but {@code Class} was never
 * wired to it and {@code classNameString} only looked in the CLASS registry, which array Types are not in.
 *
 * <p>The interesting cases are the ones a size-only test cannot tell apart ({@code byte[]} vs
 * {@code boolean[]}, {@code int[]} vs {@code float[]} -- same element size) and the nested one, whose name
 * is built by recursing into the element Type rather than by re-reading a descriptor.
 */
public class ClassLitDemo
{

    private static void show(String what, Class<?> c)
    {
        if (c == null)
        {
            System.out.println("  " + what + " = NULL");
            return;
        }
        System.out.println("  " + what + " isArray=" + c.isArray() + " name=" + c.getName());
    }

    public static void main(String[] args)
    {
        System.out.println("class literals:");
        show("String.class  ", String.class);
        show("String[].class", String[].class);
        show("int[].class   ", int[].class);
        show("byte[].class  ", byte[].class);
        show("boolean[].class", boolean[].class);
        show("float[].class ", float[].class);
        show("long[].class  ", long[].class);
        show("int[][].class ", int[][].class);
        show("String[][].class", String[][].class);

        // getClass() on a real instance must agree with the literal, and be the SAME mirror object.
        String[] a = new String[2];
        show("new String[2] ", a.getClass());
        System.out.println("  literal == getClass(): " + (String[].class == a.getClass()));
        System.out.println("  componentType of String[]: " + String[].class.getComponentType().getName());
    }
}
