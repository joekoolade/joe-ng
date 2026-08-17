package vm;

/**
 * M8 Stage 3 (reified loader): one deferred/lazily-compiled method -- everything {@code Loader.lazyCompile}
 * needs to compile it on first call, plus the offset cell / TIB slot to patch and the memoised buffer.
 * Reifies the eleven parallel {@code lz*} static arrays into one small object (the lazy-machinery half of the
 * move toward the RVMMethod model). JDK-free (primitive fields only) so it compiles into the image and the
 * loader can allocate it on the metal; the {@link Loader}'s {@code lzTab} static array is a GC root.
 */
final class LazyMethod
{
    long blob;       // class blob base (holds Utf8 strings)
    int  len;        // blob length (for parseConstPool on context restore)
    int  reg;        // class registry index (clStatics/clTib/clType restore)
    int  nameOff;    // method name Utf8 offset (0 => compile from captured code, not re-find)
    int  descOff;    // descriptor Utf8 offset
    long slot;       // &TIB[slot] / offset cell to patch with the fresh buffer (0 = memoise only)
    long code;       // captured bytecode address (0 => re-find by name/desc)
    int  codeLen;    // ... its length
    int  isStatic;   // ... 1 if static
    int  maxLocals;  // ... its max_locals
    long cache;      // memoised compiled buffer (0 = not yet compiled)
}
