package objectmodel;

/**
 * The guest object model — the single source of truth for how joe2 lays objects
 * out in memory (PLAN.md "Decided", CLAUDE.md). Every offset and size lives here
 * so the model is a one-file change: the compiler and the boot-image writer ask
 * this class, never hardcode a {@code +8}. That keeps the layout auditable and
 * lets the writer's layout-dump/diff catch relocation bugs early (PLAN.md §6).
 *
 * <p><b>Decided shape.</b> References are direct 64-bit pointers, 8-byte aligned,
 * {@code null == 0}. The header is two words: TIB pointer, then a status word
 * (identity hash / GC state / thin-lock) reserved until threads and a moving GC
 * arrive (~M6). Objects reach their type through {@code header → TIB → Type}.
 */
public final class ObjectModel
{
    private ObjectModel() {}

    /** Machine word / reference size on AArch64. */
    public static final int WORD  = 8;
    /** Object alignment. 8-byte alignment also frees the 3 low bits of any ref. */
    public static final int ALIGN = 8;

    // ----- object header (two words) ---------------------------------------
    /** Header offset of the TIB pointer (type info block). */
    public static final int TIB_OFFSET    = 0;
    /** Header offset of the status word: identity hash / GC state / thin-lock. Unused until ~M6. */
    public static final int STATUS_OFFSET = WORD;         // 8
    /** Total header size; instance fields and array length begin here. */
    public static final int HEADER_SIZE   = 2 * WORD;     // 16

    // ----- scalar objects --------------------------------------------------
    /** Byte offset of instance field {@code index} (one 8-byte slot each for now). */
    public static int fieldOffset(int index)
    {
        return HEADER_SIZE + index * WORD;
    }
    /** Allocation size of a scalar with {@code fieldCount} fields, aligned. */
    public static int scalarSize(int fieldCount)
    {
        return align(HEADER_SIZE + fieldCount * WORD);
    }

    // ----- arrays: [header][length][elements...] ---------------------------
    /** Offset of the array length (kept in an 8-byte slot for alignment simplicity). */
    public static final int ARRAY_LENGTH_OFFSET = HEADER_SIZE;            // 16
    /** Offset of element 0. */
    public static final int ARRAY_BASE_OFFSET   = ARRAY_LENGTH_OFFSET + WORD; // 24
    /** Byte offset of array element {@code index} for an element of {@code elemSize} bytes. */
    public static int arrayElementOffset(int index, int elemSize)
    {
        return ARRAY_BASE_OFFSET + index * elemSize;
    }
    /** Allocation size of an array of {@code length} elements of {@code elemSize} bytes, aligned. */
    public static int arraySize(int length, int elemSize)
    {
        return align(ARRAY_BASE_OFFSET + length * elemSize);
    }

    // ----- Type object (pointed to by TIB[0]) ------------------------------
    /** Type field: instance size in bytes. */
    public static final int TYPE_INSTANCE_SIZE_OFFSET = 0;
    /** Type field: pointer to the superclass's Type (0 at the root / Object). */
    public static final int TYPE_SUPER_OFFSET = WORD;      // 8
    /** Type field: pointer to the itable directory ({interfaceType, itable} entries, 0-terminated). */
    public static final int TYPE_ITABLE_DIR_OFFSET = 2 * WORD; // 16
    /** Total Type size. */
    public static final int TYPE_SIZE = 3 * WORD;          // 24

    /** itable-directory entry: interface Type pointer, then the itable pointer. */
    public static final int ITABLE_ENTRY_IFACE_OFFSET = 0;
    public static final int ITABLE_ENTRY_TABLE_OFFSET = WORD;  // 8
    public static final int ITABLE_ENTRY_SIZE = 2 * WORD;      // 16

    // ----- TIB (a word array) ----------------------------------------------
    /** TIB slot 0 → the {@code Type} metadata object. */
    public static final int TIB_TYPE_SLOT    = 0;
    /** TIB slot of the first virtual method entry; the vtable is slots 1... */
    public static final int TIB_VTABLE_BASE  = 1;
    /** TIB slot holding the code address for virtual-method index {@code vindex}. */
    public static int tibVMethodSlot(int vindex)
    {
        return TIB_VTABLE_BASE + vindex;
    }
    /** Byte offset of TIB slot {@code slot} (the TIB is a plain word array). */
    public static int tibSlotOffset(int slot)
    {
        return slot * WORD;
    }
    /** TIB allocation size for a vtable of {@code vmethodCount} entries. */
    public static int tibSize(int vmethodCount)
    {
        return align((TIB_VTABLE_BASE + vmethodCount) * WORD);
    }

    /** Round {@code n} up to {@link #ALIGN}. */
    public static int align(int n)
    {
        return (n + (ALIGN - 1)) & ~(ALIGN - 1);
    }
}
