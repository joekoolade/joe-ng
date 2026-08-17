package vm;

/**
 * M8 Stage 3 (reified loader): one slot of the class currently being built's flattened vtable -- its method
 * name/descriptor (in some blob) plus either an inherited compiled buffer or this class's own bytecode.
 * Reifies the five parallel {@code gv*} arrays in {@link Loader} into one object. Unlike the registries this
 * is transient build scratch (reused per class), so {@code Loader} pre-allocates one VtSlot per slot once and
 * overwrites the fields each class. JDK-free (primitive fields only).
 */
final class VtSlot
{
    long base;      // blob holding this slot's name/descriptor
    int  name;      // method name Utf8 offset (in base)
    int  desc;      // descriptor Utf8 offset (in base)
    long implBuf;   // inherited impl buffer (0 => this class's own)
    long implCode;  // this class's own method bytecode (0 => inherited)
}
