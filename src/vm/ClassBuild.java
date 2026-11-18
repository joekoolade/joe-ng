/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package vm;

import magic.Address;

/**
 * The field layout of the class the loader is building or compiling: what {@code Loader.parseFields} derives from
 * the blob AND the loader's state (the inherited instance-field count comes from the registered superclass), so --
 * unlike the header facts on {@link ClassFileView} -- it is not a fixed fact about the blob and is rebuilt per
 * class. {@code Loader.gBuild} is the current one.
 *
 * <p>Before increment P2-4b these were nine separate statics ({@code gsfName}, {@code gsfAccess}, {@code gsfCount},
 * {@code gifName}, {@code gifAccess}, {@code gifDescOff}, {@code gifCount}, {@code gStatics},
 * {@code gAbsentSuperOff}); {@code parseFields} now builds one object and publishes it with one store.
 *
 * <p>Mutable on purpose in one field: {@link #statics} starts as a fresh zeroed block and is REPLACED by the class's
 * phase-A block when a later phase rebuilds the context ({@code LazyCompiler.restoreCtxForCompile}, phase B) or by
 * the writer's dense block ({@code adoptStatics}).
 */
final class ClassBuild
{
    // Static fields, index = slot.
    int[] sfName;      // Utf8 offset of each static field's name
    int[] sfAccess;    // access_flags of each static field: ACC_VOLATILE for the JIT
    int sfCount;

    // Instance fields, index = slot; inherited slots come first (name 0, access 0).
    int[] ifName;      // Utf8 offset of each own instance field's name
    int[] ifAccess;    // access_flags of each own instance field: reflection's getModifiers / access checks
    int[] ifDescOff;   // Utf8 offset of each own instance field's type descriptor
    int ifCount;       // total: inherited + own

    Address statics;   // this class's statics block
    int absentSuperOff; // Utf8 offset (in the blob) of a direct supertype that can never load, or 0
}
