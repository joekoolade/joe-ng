/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-22
 */
package writer;

import util.StrSet;
import util.Vec;

/**
 * The class-model queries {@link ImageBuilder}'s layout needs — flattened vtables,
 * interface methods, field counts, the superclass chain — abstracted from *how* a class
 * is represented. On the seed JVM that is {@link SeedClassModel} over the JDK
 * {@code classfile.ClassFile}; on metal it will be a {@code MetalClassModel} over the
 * embedded class table + shared {@code ClassReader} (the M5.5c class-model unification,
 * PLAN.md §M5.5c step 1b). Establishing the seam keeps {@link ImageBuilder} independent
 * of either representation.
 *
 * <p>Return types are writer-owned tuples ({@link VSlot}/{@link Method}) and plain
 * {@code String}s — no {@code ClassFile} type escapes here, so a metal impl (which has no
 * {@code ClassFile}) can satisfy the interface. The representation-independent identity
 * (byte offsets, the key migration) comes with the metal impl and steps 3/4.
 */
interface ClassModel
{
    /** One flattened-vtable slot: the class providing the impl, plus name/descriptor. */
    record VSlot(String owner, String name, String descriptor) {}

    /** One interface method: name + descriptor (the itable-slot identity). */
    record Method(String name, String descriptor) {}

    /** A class we don't compile (JDK {@code java/*}) — a chain root, like Object. */
    boolean isRoot(String cls);

    /** {@code cls}'s superclass internal name (null for Object). */
    String superClassName(String cls);

    /** Number of instance (non-static) fields — the object's field-slot count. */
    int instanceFieldCount(String cls);

    /** Whether {@code cls} declares a {@code <clinit>} (needs eager init). */
    boolean hasClinit(String cls);

    /** The flattened vtable of {@code cls} (superclass slots first, overrides in place). */
    Vec<VSlot> vtable(String cls);

    /** {@code cls}'s interface methods, in declaration order = itable slots. */
    Vec<Method> interfaceMethods(String cls);

    /** The class providing {@code cls}'s implementation of {@code name+desc} (walk supers). */
    String findImpl(String cls, String name, String desc);

    /** All interfaces {@code cls} implements, directly or via superclasses. */
    StrSet allInterfaces(String cls);
}
