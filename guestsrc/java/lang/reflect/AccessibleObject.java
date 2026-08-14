package java.lang.reflect;

/**
 * Base of {@code Field}/{@code Method}/{@code Constructor} — the {@code accessible} override flag that
 * {@code setAccessible(true)} sets to bypass member-access enforcement, plus the enforcement itself
 * ({@link #checkAccess}). Java's protection on joe-ng is language-level (verification + these checks), not
 * hardware rings, so reflective get/set/invoke MUST honour member access or the boundary is void.
 */
public class AccessibleObject
{
    static final int ACC_PUBLIC    = 0x0001;
    static final int ACC_PRIVATE   = 0x0002;
    static final int ACC_PROTECTED = 0x0004;

    boolean override;

    public void setAccessible(boolean flag)
    {
        this.override = flag;
    }

    public boolean isAccessible()
    {
        return override;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classAtPc}): Class of the method containing frame PC {@code pc}. */
    static native Object callerClass0(long pc);

    /**
     * Enforce Java member access for a member with modifiers {@code mods} declared by {@code declaringClass},
     * from the caller whose frame return-address is {@code callerPc} — unless {@code setAccessible(true)} was
     * called. Rules: public → always; same declaring class → always; private → only the declaring class;
     * protected → same package or a subclass of the declaring class; package-private → same package. Throws
     * {@code IllegalAccessException} otherwise.
     */
    protected final void checkAccess(Class<?> declaringClass, int mods, long callerPc) throws IllegalAccessException
    {
        if (override || (mods & ACC_PUBLIC) != 0)
        {
            return;
        }
        Object c = callerClass0(callerPc);
        Class<?> caller = (Class<?>) c;
        if (caller == declaringClass)
        {
            return;                                        // same class sees all of its own members
        }
        if ((mods & ACC_PRIVATE) != 0)
        {
            throw denied(caller, declaringClass, "private");
        }
        if ((mods & ACC_PROTECTED) != 0)
        {
            if (caller != null && (samePackage(caller, declaringClass) || declaringClass.isAssignableFrom(caller)))
            {
                return;
            }
            throw denied(caller, declaringClass, "protected");
        }
        // package-private (no access bit): same package only
        if (caller != null && samePackage(caller, declaringClass))
        {
            return;
        }
        throw denied(caller, declaringClass, "package-private");
    }

    /** True if {@code a} and {@code b} are declared in the same package (by binary-name package prefix). */
    private static boolean samePackage(Class<?> a, Class<?> b)
    {
        return a.getPackageName().equals(b.getPackageName());
    }

    private static IllegalAccessException denied(Class<?> caller, Class<?> declaring, String kind)
    {
        String who = (caller == null) ? "<unknown>" : caller.getName();
        return new IllegalAccessException("Class " + who + " can not access a " + kind
                + " member of class " + declaring.getName());
    }
}
