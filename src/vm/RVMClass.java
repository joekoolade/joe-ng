package vm;

/**
 * M8 Stage 3 (reified loader): one loaded class -- its TIB, {@code Type} node, static block, field/vtable
 * counts, superclass link and modifiers. Reifies the eleven scalar per-class {@code cl*} static arrays in
 * {@link Loader} into one object (an RVMClass). The direct-interface list ({@code clIfaceReg}/{@code
 * clIfaceRegN}, a flattened 2D side-table) and the {@code <clinit>} dependency arrays stay separate.
 * JDK-free (primitive fields only); {@code Loader.clTab} is a GC root.
 */
final class RVMClass
{
    long    base;        // class blob base (holds Utf8 strings)
    int     nameOff;     // this class's own name Utf8 offset (in base)
    long    tib;         // its TIB { Type, vtable... }
    long    type;        // its Type node (for instanceof/checkcast)
    long    statics;     // its static block base (gStatics), reused across load phases
    int     fieldCount;  // instance-field count (for subclass field layout)
    int     vtCount;     // flattened vtable size (for subclass vtable copy)
    int     vtStart;     // start index of its slots in the vt registry
    int     superReg;    // superclass registry index (-1 = none), for the full-chain itable closure
    int     modifiers;   // Class.getModifiers() value, computed at load (ACC_SUPER stripped)
    boolean isIface;     // interface? (phase B compiles only its default/static bodies, no TIB fill)
    int     ifmStart;    // interfaces only: start of the FLATTENED per-interface method run in ifBase/ifNameOff/ifDescOff
    int     ifmCount;    // ... its length = this interface's itable slot count (0 for classes)
}
