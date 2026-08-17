package vm;

/**
 * M8 Stage 3 (reified loader): one static field in the global static-field registry -- keyed by declaring
 * class + name, with its allocated static-slot address (so a cross-class getstatic/putstatic resolves to the
 * same slot). Reifies the four parallel {@code sg*} static arrays in {@link Loader} into one object (an
 * RVMField). JDK-free (primitive fields only); {@code Loader.sgTab} is a GC root.
 */
final class RVMField
{
    long base;      // declaring class blob base (holds its Utf8 strings)
    int  classOff;  // class name Utf8 offset (in base)
    int  nameOff;   // field name Utf8 offset (in base)
    long addr;      // the field's static-slot address
}
