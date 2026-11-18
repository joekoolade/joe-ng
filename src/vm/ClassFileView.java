/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import magic.Address;

/**
 * One loaded classfile blob, parsed once: its heap copy and its constant-pool index (JikesRVM keeps the same
 * role in {@code ClassFileReader}'s output). Reifies the seven parallel {@code pc*} parse-cache arrays in
 * {@link Loader} into one object, the way {@link RVMMethod} and {@link RVMClass} reified theirs.
 *
 * <p>IMMUTABLE ONCE PUBLISHED, apart from {@link #litObj}'s slots (the per-entry {@code ldc} String cache, filled
 * as literals are interned). {@code parseConstPool} builds a view completely and only then stores the one
 * reference that makes it visible, so a reader on another core either finds no view or a whole one -- where the
 * parallel arrays needed six stores ordered ahead of the count.
 *
 * <p>Unlike the other reified loader classes this one holds ARRAY references. The writer bakes a precise
 * reference map for every image class ({@code ClassFile.refMap} marks each {@code [} and {@code L} field), so the
 * collector traces these fields exactly; {@code Loader.pcTab} is the GC root.
 */
final class ClassFileView
{
    Address base;     // the blob in memory (its Utf8 strings are read in place, at base + offset)
    byte[] bytes;     // heap copy of the blob, for the shared classfile.ClassReader
    int[] cp;         // byte offset of each constant-pool entry body
    int[] cpTag;      // tag of each constant-pool entry (7 = Class)
    long[] litObj;    // per-entry ldc-String intern cache: the one interned object for that entry, or 0
    int cpCount;      // constant-pool entry count
    int afterCp;      // byte offset just past the constant pool
}
