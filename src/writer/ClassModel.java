package writer;

import classfile.ClassFile;
import util.StrSet;
import util.Vec;

/**
 * The class-model queries {@link ImageBuilder}'s layout needs — flattened vtables,
 * interface methods, field counts, the superclass chain — abstracted from *how* a class
 * is represented. On the seed JVM that is {@link SeedClassModel} over the JDK
 * {@link ClassFile}; on metal it will be a {@code Loader}-registry impl over byte-offset
 * registries (the M5.5c class-model unification, PLAN.md §M5.5c step 1). Establishing
 * the seam now — with only the seed impl — is byte-identical and lets the metal impl
 * slot in unchanged when the writer itself runs on metal.
 *
 * <p>Return types still name {@code ClassFile.VSlot}/{@code Method} (owner/name/desc
 * tuples) and {@code String}; the representation-independent form comes with the metal
 * impl, when identities become byte offsets.
 */
interface ClassModel
{
    /** A class we don't compile (JDK {@code java/*}) — a chain root, like Object. */
    boolean isRoot(String cls);

    /** {@code cls}'s superclass internal name (null for Object). */
    String superClassName(String cls);

    /** Number of instance (non-static) fields — the object's field-slot count. */
    int instanceFieldCount(String cls);

    /** Whether {@code cls} declares a {@code <clinit>} (needs eager init). */
    boolean hasClinit(String cls);

    /** The flattened vtable of {@code cls} (superclass slots first, overrides in place). */
    Vec<ClassFile.VSlot> vtable(String cls);

    /** {@code cls}'s interface methods, in declaration order = itable slots. */
    Vec<ClassFile.Method> interfaceMethods(String cls);

    /** The class providing {@code cls}'s implementation of {@code name+desc} (walk supers). */
    String findImpl(String cls, String name, String desc);

    /** All interfaces {@code cls} implements, directly or via superclasses. */
    StrSet allInterfaces(String cls);
}
