package objectmodel;

import harness.T;

/**
 * Pins the decided object layout so an accidental offset change is loud (the
 * writer relocates against these numbers; a silent shift corrupts images).
 * Run: {@code java objectmodel.ObjectModelTest}
 */
public final class ObjectModelTest
{
    public static void main(String[] args)
    {
        T.eq("WORD", 8, ObjectModel.WORD);
        T.eq("HEADER_SIZE", 16, ObjectModel.HEADER_SIZE);
        T.eq("TIB_OFFSET", 0, ObjectModel.TIB_OFFSET);
        T.eq("STATUS_OFFSET", 8, ObjectModel.STATUS_OFFSET);

        T.eq("field0 @16", 16, ObjectModel.fieldOffset(0));
        T.eq("field1 @24", 24, ObjectModel.fieldOffset(1));
        T.eq("scalar(0)=16", 16, ObjectModel.scalarSize(0));
        T.eq("scalar(3)=40", 40, ObjectModel.scalarSize(3));

        T.eq("array length @16", 16, ObjectModel.ARRAY_LENGTH_OFFSET);
        T.eq("array elem0 @24", 24, ObjectModel.ARRAY_BASE_OFFSET);
        T.eq("byte[5] elem3 @27", 27, ObjectModel.arrayElementOffset(3, 1));
        T.eq("byte[5] size=32", 32, ObjectModel.arraySize(5, 1));   // align(24+5)=32
        T.eq("long[2] size=40", 40, ObjectModel.arraySize(2, 8));   // 24+16=40

        T.eq("TIB type slot off 0", 0, ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT));
        T.eq("TIB vmethod0 slot 1", 1, ObjectModel.tibVMethodSlot(0));
        T.eq("TIB vmethod0 off 8", 8, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(0)));
        T.eq("tibSize(3)=32", 32, ObjectModel.tibSize(3));          // align((1+3)*8)=32

        // GC reference map: bit 0 the "computed" marker, bit 1+slot the slot's pointer-bearing flag.
        T.eq("Type refMap @64", 64, ObjectModel.TYPE_REFMAP_OFFSET);
        T.eq("Type refMap word1 @72", 72, ObjectModel.TYPE_REFMAP_OFFSET + ObjectModel.WORD);
        T.eq("Type refMap covers 126 slots", 126, ObjectModel.TYPE_REFMAP_MAX_SLOT);
        T.eq("Type size 80", 80, ObjectModel.TYPE_SIZE);
        T.eq("array Type size = Type size", ObjectModel.TYPE_SIZE, ObjectModel.ARRAY_TYPE_SIZE);

        T.summary("object-model");
    }
}
