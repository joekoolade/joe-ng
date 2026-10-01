/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-30
 */
package java.lang.invoke;

import jdk.internal.misc.Unsafe;

/**
 * Name-winning {@code java.lang.invoke.MethodHandleStatics} overlay, carrying the members STOCK
 * {@code VarHandle} and the template-generated handles actually read.
 *
 * <p>WHY AN OVERLAY AND NOT THE STOCK CLASS, which is the rule: stock's own {@code <clinit>} cannot run
 * here. It does {@code CLASSFILE_VERSION = ClassFileFormatVersion.latest().major()} -- an enum in the DENIED
 * {@code java/lang/reflect/} whose initializer builds one constant per class-file version -- and
 * {@code DUMP_CLASS_FILES = new ClassFileDumper(...)}, which writes class files to a filesystem this VM does
 * not have. Neither is reachable from a VarHandle access; both are in the one initializer, so the class
 * cannot be loaded without them.
 *
 * <p>THE SURFACE IS MEASURED, NOT GUESSED. Scanning every {@code VarHandle*} and {@code MethodHandles*}
 * class in java.base for references to this class gives exactly SEVEN distinct members -- the three below
 * plus the four exception helpers -- and not one of them needs {@code ClassFileFormatVersion},
 * {@code ClassFileDumper} or {@code LambdaForm}. Everything stock declares beyond those seven is
 * MethodHandle-runtime configuration (compile thresholds, LambdaForm tracing, profiling) for machinery this
 * VM does not carry, so dropping it cannot be reached rather than being merely unlikely.
 *
 * <p>The two flags keep stock's own DEFAULTS, read from its property reads rather than chosen here: both
 * default false. The four helpers are stock's bodies verbatim.
 */
class MethodHandleStatics
{
    private MethodHandleStatics()
    {
    }

    static final Unsafe UNSAFE = Unsafe.getUnsafe();

    /** Stock: {@code props.getProperty("java.lang.invoke.VarHandle.VAR_HANDLE_IDENTITY_ADAPT", "false")}. */
    static final boolean VAR_HANDLE_IDENTITY_ADAPT = false;

    /** Stock: {@code props.getProperty("java.lang.invoke.VarHandle.VAR_HANDLE_SEGMENT_FORCE_EXACT", "false")}. */
    static final boolean VAR_HANDLE_SEGMENT_FORCE_EXACT = false;

    static InternalError newInternalError(String message)
    {
        return new InternalError(message);
    }

    static RuntimeException newIllegalArgumentException(String message)
    {
        return new IllegalArgumentException(message);
    }

    static RuntimeException newIllegalArgumentException(String message, Object obj)
    {
        return new IllegalArgumentException(message(message, obj));
    }

    static RuntimeException newIllegalArgumentException(String message, Object obj, Object obj2)
    {
        return new IllegalArgumentException(message(message, obj, obj2));
    }

    private static String message(String message, Object obj)
    {
        if (obj != null)
        {
            message = message + ": " + obj;
        }
        return message;
    }

    private static String message(String message, Object obj, Object obj2)
    {
        if (obj != null || obj2 != null)
        {
            message = message + ": " + obj + ", " + obj2;
        }
        return message;
    }
}
