package compiler;

/**
 * A fixture with an instance field and same-class static accessors, so the
 * compiler tests can pin {@code getfield}/{@code putfield} lowering without a
 * class resolver (same-class field owners resolve to the class being compiled).
 * (Cross-class field access and {@code new} are exercised end-to-end in QEMU.)
 */
public final class FieldFixture
{
    int value;

    public static int  get(FieldFixture f)
    {
        return f.value;
    }
    public static void set(FieldFixture f, int v)
    {
        f.value = v;
    }

    public int val()
    {
        return value;    // virtual (vtable slot 0)
    }
    public static int callVal(FieldFixture f)
    {
        return f.val();    // invokevirtual dispatch
    }
}
