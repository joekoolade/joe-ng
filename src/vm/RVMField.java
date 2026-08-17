package vm;

/**
 * M8 Stage 3 (reified loader): one field in the loader's field registries. Reifies BOTH the static-field
 * registry ({@code sg*}: base/classOff/nameOff/addr -- so cross-class getstatic/putstatic resolves to the
 * same slot) and the instance-field registry ({@code fld*}: +slot/access/descOff -- field layout + reflective
 * getModifiers), each in its own array ({@code Loader.sgTab} / {@code Loader.fldTab}, both GC roots).
 * JDK-free (primitive fields only).
 */
final class RVMField
{
    long base;      // declaring class blob base (holds its Utf8 strings)
    int  classOff;  // class name Utf8 offset (in base)
    int  nameOff;   // field name Utf8 offset (in base)
    long addr;      // the field's static-slot address
    int  descOff;   // (instance field) type descriptor Utf8 offset
    int  access;    // (instance field) access_flags -- reflection getModifiers
    int  slot;      // (instance field) field slot index (offset = 16 + slot*8)
}
