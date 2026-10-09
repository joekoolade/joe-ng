/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-30
 */
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
 * <p>FLOAT AND DOUBLE ARE ABSENT, AND NOT FOR THE REASON THIS USED TO GIVE. It said their result "arrives in
 * d0, not x0"; joe-ng keeps a float/double as raw bits in an X register and returns it with the same
 * {@code mov x0} as an int ({@code freturn}/{@code dreturn} are bit-preserving), so x0 does hold the bits. The
 * real obstacle is THIS CLASS: it is image code, and naming {@code Double}/{@code Float} here pulls them into
 * the boot set, where {@code Double.<clinit>} ({@code TYPE = Class.getPrimitiveClass("double")}) faults in
 * {@code bakeResolve} before the VM is up -- MEASURED, it was tried. Boxing them needs the guest-compiled
 * {@code valueOf} resolved at run time instead. Until then {@link Loader} REPORTS such a reference by name.
 *
 * <p>The ARGUMENT direction ({@link #unboxNull}) needs no helper for a non-null value: every wrapper's only
 * instance field is {@code value}, in slot 0, so the thunk unboxes with one load.
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

    /**
     * A method reference's thunk is unboxing a NULL argument for a primitive parameter: throw what
     * {@code LambdaMetafactory}'s adapter throws. The thunk TAIL-branches here with its own frame (if any)
     * already dropped, so the exception appears to come from the SAM call site -- the frame the unwinder knows.
     * Without the check the load reads whatever sits at address 16, which is mapped RAM on this board: a silent
     * wrong value, not a fault.
     *
     * <p>{@code marker} 0 is {@code VM.forceCompile}'s touch and returns; the thunk passes 1.
     */
    static long unboxNull(long marker)
    {
        if (marker == 0L)
        {
            return 0L;
        }
        throw new NullPointerException();
    }
}
