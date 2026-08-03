package java.lang;

import magic.Magic;

/**
 * Bare-metal {@code java/lang/Class} mirror. The VM materialises one Class instance per loaded VM Type (on an
 * {@code ldc} class-literal or {@code Object.getClass()}, in {@code Loader.classMirror}) and stores the raw
 * Type-node pointer in {@link #typeAddr}; the mirror is cached per Type, so {@code X.class} and
 * {@code obj.getClass()} return the SAME identity — which is what stock code compares (e.g.
 * {@code Arrays.copyOf}'s {@code newType == Object[].class}). This override keeps the huge stock
 * {@code java.lang.Class} (and its reflection/CDS closure) out of the image; instance methods
 * ({@code getName}/{@code getComponentType}/{@code isInstance}/...) are added on demand as the code that runs
 * on metal reaches them. The VM allocates the object directly (bypassing this ctor).
 */
public final class Class
{
    private long typeAddr;      // the VM Type node this Class mirrors (set by the VM at materialisation)

    private Class()
    {
    }

    /**
     * Assertions are off on metal (no -ea). Stock {@code <clinit>}s read this into their {@code $assertionsDisabled}
     * flag (e.g. {@code java.util.regex.Pattern.<clinit>} does {@code ldc X.class; desiredAssertionStatus()}); with
     * this it can run to completion and initialise its static nodes instead of being skipped.
     */
    public boolean desiredAssertionStatus()
    {
        return false;
    }

    /** The class's binary name with dots (M4), built by the VM from the loader registry's name bytes. */
    public String getName()
    {
        return getName0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classNameOf}): mirror -> a fresh name String. */
    private static native String getName0(Class c);

    /** True if {@code obj} is non-null and assignable to this type (the {@code instanceof} walk). */
    public boolean isInstance(Object obj)
    {
        return isInstance0(obj, typeAddr);
    }

    /** VM native (maps straight onto the JIT's {@code VM.instanceOf(JJ)I} helper: obj in x0, Type in x1). */
    private static native boolean isInstance0(Object obj, long type);

    /**
     * True if {@code other}'s type equals this type or has it on its superclass chain — a pure-Java walk of
     * the Type nodes ({@code superType} at Type+8) via the {@code Magic.load64} intrinsic. Interface
     * assignability (itables) is not consulted; extend when reached code needs it.
     */
    public boolean isAssignableFrom(Class other)
    {
        long t = other.typeAddr;
        while (t != 0L)
        {
            if (t == typeAddr)
            {
                return true;
            }
            t = Magic.load64(t + 8L);                   // Type.superType
        }
        return false;
    }

    /** The superclass's mirror (cached per Type, so {@code getSuperclass() == Super.class}), or null. */
    public Class getSuperclass()
    {
        return superclass0(this);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.superclassOf}): super Type -> its (cached) mirror. */
    private static native Class superclass0(Class c);
}
