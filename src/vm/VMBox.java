package vm;

import magic.Magic;

/**
 * Boxing for a method reference whose referent returns a PRIMITIVE while its functional interface is
 * generic, so the erased SAM returns {@code Object}.
 *
 * <p>This never arises for a lambda BODY: javac generates the synthetic {@code lambda$...} method with the
 * instantiated signature and puts the {@code valueOf} inside it. It arises only for a method REFERENCE,
 * where the referent's descriptor is fixed and {@code LambdaMetafactory} is what inserts the conversion.
 * joe-ng synthesises the lambda class itself ({@link Loader#buildLambdaTib}), so the conversion has to be
 * emitted into its thunk. Without it the raw primitive travels in x0 where the caller expects a reference:
 * {@code Assertions.assertDoesNotThrow(st::hasMoreTokens)} handed back the boolean 1 AS AN ADDRESS, and the
 * first virtual call on the result threw NPE from inside JUnit -- a fault that looked like a
 * StringTokenizer bug and was nowhere near one.
 *
 * <p>Float and double are deliberately absent: their return value arrives in d0, not x0, so a thunk cannot
 * move it without FP support. {@link Loader} reports such a reference by name rather than emitting a
 * conversion that would quietly read the wrong register.
 */
final class VMBox
{
    private VMBox()
    {
    }

    /**
     * Box {@code v} — whose type is the JVMS descriptor char {@code kind} — and return its raw address.
     * The wrappers' own {@code valueOf} does the work, so a boxed value from a method reference is the same
     * object (cache and all) as one from an ordinary autobox.
     */
    static long box(long v, int kind)
    {
        if (kind == 'Z')
        {
            return Magic.addrOf(Boolean.valueOf(v != 0L));
        }
        if (kind == 'B')
        {
            return Magic.addrOf(Byte.valueOf((byte) v));
        }
        if (kind == 'C')
        {
            return Magic.addrOf(Character.valueOf((char) v));
        }
        if (kind == 'S')
        {
            return Magic.addrOf(Short.valueOf((short) v));
        }
        if (kind == 'I')
        {
            return Magic.addrOf(Integer.valueOf((int) v));
        }
        if (kind == 'J')
        {
            return Magic.addrOf(Long.valueOf(v));
        }
        return 0L;                          // unreachable: the loader emits no call for any other kind
    }
}
