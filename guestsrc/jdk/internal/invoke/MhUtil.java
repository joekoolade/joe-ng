/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package jdk.internal.invoke;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import magic.Magic;

/**
 * Minimal name-winning {@code jdk.internal.invoke.MhUtil} overlay for joe-ng. Stock {@code findVarHandle}
 * reflects a field into a real {@code VarHandle} through the Lookup's {@code MemberName} machinery, which
 * needs the MethodHandle runtime this VM does not carry; joe-ng builds the same TEMPLATE-GENERATED handle
 * directly (see {@code MethodHandles.forInstanceField}).
 *
 * <p>BOTH FORMS ARE REACHED, and the no-receiver one is reached FAR more often -- MEASURED over java.base:
 * 30 call sites for {@code (Lookup,String,Class)} against 4 for {@code (Lookup,Class,String,Class)},
 * including {@code java/net/Socket}, {@code Phaser}, {@code Exchanger}, {@code FutureTask},
 * {@code Striped64}, {@code CompletableFuture} and {@code SubmissionPublisher}. An earlier cut of this file
 * threw from the no-receiver form on the theory it was the rare one; it is the common one.
 */
public final class MhUtil
{
    private MhUtil()
    {
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classAtPc}): Class mirror of the method containing {@code pc}. */
    private static native Object callerClass0(long pc);

    /**
     * No receiver Class: stock resolves the field against {@code lookup.lookupClass()}. joe-ng's Lookup is a
     * singleton and carries none, so the declaring class comes from the CALLER instead -- which is the same
     * answer, because every one of the 30 sites is a class binding a handle to one of ITS OWN fields from
     * its own {@code <clinit>}.
     *
     * <p>{@code Magic.readLR()} must be the FIRST statement: it reads the live link register, so anything
     * ahead of it can clobber the return address. Same requirement, and the same comment, as
     * {@code AtomicIntegerFieldUpdater.newUpdater}, which resolves its caller the same way.
     */
    public static VarHandle findVarHandle(MethodHandles.Lookup lookup, String name, Class type)
    {
        long callerPc = Magic.readLR();                    // FIRST: return address into the caller (getCallerClass)
        Object caller = callerClass0(callerPc);
        if (caller == null)
        {
            // Refuse rather than guess. A handle built against the wrong declaring class would resolve to
            // the wrong field OFFSET and then read and write a neighbouring field for ever -- silent, and
            // exactly the class of wrong answer this overlay was rewritten to remove.
            throw new UnsupportedOperationException("MhUtil.findVarHandle: caller class unresolvable for " + name);
        }
        return findVarHandle(lookup, (Class) caller, name, type);
    }

    /**
     * Stock wraps the Lookup's checked {@code ReflectiveOperationException} in an {@code InternalError} --
     * that unchecked-ing IS why this class exists, so callers can bind a handle in a {@code <clinit>}
     * without declaring throws. Kept verbatim: an {@code Error} rather than a RuntimeException matters here
     * for the reason {@code CaseFolding} records, since a broad {@code catch (Exception)} must not turn a
     * failed field binding into a silent wrong answer.
     */
    public static VarHandle findVarHandle(MethodHandles.Lookup lookup, Class recv, String name, Class type)
    {
        try
        {
            return lookup.findVarHandle(recv, name, type);
        }
        catch (ReflectiveOperationException e)
        {
            throw new InternalError(e);
        }
    }
}
