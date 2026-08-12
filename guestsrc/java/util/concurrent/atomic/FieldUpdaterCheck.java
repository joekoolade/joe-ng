package java.util.concurrent.atomic;

/**
 * Shared access/type/volatile validation for the {@code Atomic*FieldUpdater} overlays -- the checks stock
 * {@code newUpdater} performs. The target field is found reflectively ({@link Class#getDeclaredField}); a
 * private field is accessible only from its declaring class, so the caller's class (captured by the updater
 * via {@code Magic.readLR()} and resolved by {@link #callerClass0}) must equal the target class.
 */
final class FieldUpdaterCheck
{
    static final int ACC_PRIVATE = 0x0002;
    static final int ACC_VOLATILE = 0x0040;

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classAtPc}): Class mirror of the method containing {@code pc}. */
    static native Object callerClass0(long pc);

    /**
     * Validate an updater for {@code tclass.fieldName}: the field must exist, be {@code volatile}, have the
     * expected type ({@code wantType}: 'I'/'J', or 'L' meaning a reference), and be accessible from the caller
     * whose frame PC is {@code callerPc}. Throws (RuntimeException / IllegalArgumentException) on any failure,
     * exactly where stock {@code newUpdater} would.
     */
    static void validate(Class<?> tclass, String fieldName, long callerPc, int wantType)
    {
        int mods = tclass.fieldModifiers(fieldName);
        if (mods < 0)
        {
            throw new RuntimeException("no such field: " + fieldName);
        }
        int tc = tclass.fieldTypeChar(fieldName);
        boolean typeOk = (wantType == 'L') ? (tc == 'L' || tc == '[') : (tc == wantType);
        if (!typeOk)
        {
            throw new IllegalArgumentException("Must be " + (wantType == 'I' ? "integer" : wantType == 'J' ? "long" : "reference") + " type");
        }
        if ((mods & ACC_VOLATILE) == 0)
        {
            throw new IllegalArgumentException("Must be volatile type");
        }
        if ((mods & ACC_PRIVATE) != 0)
        {
            Object caller = callerClass0(callerPc);
            if (caller != tclass)
            {
                throw new RuntimeException("Class " + (caller == null ? "?" : ((Class<?>) caller).getName())
                        + " can not access a private member of class " + tclass.getName());
            }
        }
    }
}
