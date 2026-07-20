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

    /**
     * {@code new} while a value is already on the operand stack: javac pushes
     * {@code x}, then allocates. {@code Heap.alloc} clobbers the operand registers,
     * so {@code x} must be spilled across that call and reloaded afterwards. This
     * shape used to be rejected outright, and blocked 14 of the compiler's own
     * methods from self-hosting (PLAN.md §M5.1).
     */
    public static int newWithLiveStack(int x)
    {
        return x + new FieldFixture().value;
    }
}
