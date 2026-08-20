package objectmodel;

/**
 * The guest object model — the single source of truth for how joe-ng lays objects
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
    // (offset 3*WORD is ARRAY_TYPE_ELEMENT_OFFSET below -- scalar Types leave it 0)
    /** Type field: superclass-chain depth (Object = 0); -1 for interface Types (never in a chain). */
    public static final int TYPE_DEPTH_OFFSET = 4 * WORD;  // 32
    /** Type field: pointer to the superclass DISPLAY -- an array of depth+1 Type addrs, display[d] =
     *  this chain's ancestor at depth d (display[depth] = self). O(1) subclass test: T is assignable
     *  from S iff S.depth >= T.depth and S.display[T.depth] == T. 0 = no display (walk fallback). */
    public static final int TYPE_DISPLAY_OFFSET = 5 * WORD; // 40
    /** Type field, dual-purpose by kind (a receiver's Type is never an interface, so no conflict):
     *  - class/array Type: doesImplement BITMAP, two 64-bit words (this word + the next). Bit 0 =
     *    "bitmap computed" marker; bit i (i = 1..127) = implements the interface with ID i. O(1)
     *    interface test: S implements I iff S.bitmap[I.id] (definitive only for NUMBERED targets;
     *    unnumbered interfaces keep the itable-dir walk).
     *  - interface Type: its global interface ID (1..127; 0 = unnumbered). Writer-baked interfaces
     *    get build-time IDs; the loader numbers new ones from the shared VM.ifaceIdNext counter. */
    public static final int TYPE_IMPLEMENTS_OFFSET = 6 * WORD; // 48 (+56 = bitmap word 1)
    /** Type field: the REFERENCE MAP — which of an instance's field slots the collector must scan.
     *  Two 64-bit words (this word + the next). Bit 0 = "map computed" marker; bit {@code 1+slot} = slot
     *  {@code slot} may hold a pointer. A Type with bit 0 clear (a zeroed node, a lambda Type, a class
     *  with more than 126 slots) means "no map" and the collector scans that object's whole payload
     *  conservatively — so an absent map is always safe.
     *
     *  <p>"May hold a pointer" is wider than "is a Java reference": it covers descriptors {@code L} and
     *  {@code [} AND {@code J}. The VM's own reified objects ({@code RVMClass.tib}/{@code type}/{@code
     *  statics}, {@code RVMMethod}, {@code DynLink}) keep raw addresses in {@code long} fields — those
     *  words are the ONLY root a metal-built TIB, Type or statics block has, and a map that skipped them
     *  would sweep the live metadata out from under the running program. Slots of every other kind
     *  ({@code I}/{@code Z}/{@code C}/{@code B}/{@code S}/{@code F}/{@code D}) are skipped: an {@code int}
     *  in [0x04000000,0x10000000) — a size, an offset, a hash — is a plausible false root today, and this
     *  is what stops it being one. */
    public static final int TYPE_REFMAP_OFFSET = 8 * WORD; // 64 (+72 = map word 1)
    /** Highest instance-field slot the two-word reference map can describe (bit 0 is the marker). */
    public static final int TYPE_REFMAP_MAX_SLOT = 126;
    /** Total Type size (one uniform record for class AND array Types). */
    public static final int TYPE_SIZE = 10 * WORD;         // 80

    // ----- array Type (a Type node whose objects are arrays) ---------------
    // An array's header TIB slot (@0) holds either a small element size (1/2/4/8 — a raw, untyped array, the
    // boot-time default) OR a pointer to an array TIB whose TIB[0] is an array Type. Discriminate by magnitude:
    // a real TIB is a heap pointer (large); a raw element size is <= WORD. An array Type reuses the class-Type
    // {instanceSize, super, itableDir} prefix (super = Object, so `arr instanceof Object` walks correctly), plus
    // an element-Type field; its instanceSize slot carries a tag (so it is recognisable) with the element size.
    /** Tag in an array Type's instanceSize slot (high bits); the low 16 bits hold the element size. */
    public static final long ARRAY_TYPE_TAG = 0xA55A0000L;
    /** Mask to read the tag (identify an array Type). */
    public static final long ARRAY_TYPE_TAG_MASK = 0xFFFF0000L;
    /** Array Type field: the element's Type (0 for a primitive element); used for reference-array covariance. */
    public static final int ARRAY_TYPE_ELEMENT_OFFSET = 3 * WORD;   // 24
    /** Total array Type size (= TYPE_SIZE: one uniform record, arrays fill the element word). */
    public static final int ARRAY_TYPE_SIZE = TYPE_SIZE;   // = TYPE_SIZE (arrays leave the refMap zero:
                                                           //   element size drives array scanning)
    /** A TIB slot value at or below this is a raw array's element size, not a TIB pointer. */
    public static final int MAX_RAW_ARRAY_TIB = WORD;      // 8

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
