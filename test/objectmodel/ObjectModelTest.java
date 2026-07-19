package objectmodel;

/**
 * Pins the decided object layout so an accidental offset change is loud (the
 * writer relocates against these numbers; a silent shift corrupts images).
 * Run: {@code java objectmodel.ObjectModelTest}
 */
public final class ObjectModelTest {
    private static int failures;

    public static void main(String[] args) {
        eq("WORD", 8, ObjectModel.WORD);
        eq("HEADER_SIZE", 16, ObjectModel.HEADER_SIZE);
        eq("TIB_OFFSET", 0, ObjectModel.TIB_OFFSET);
        eq("STATUS_OFFSET", 8, ObjectModel.STATUS_OFFSET);

        eq("field0 @16", 16, ObjectModel.fieldOffset(0));
        eq("field1 @24", 24, ObjectModel.fieldOffset(1));
        eq("scalar(0)=16", 16, ObjectModel.scalarSize(0));
        eq("scalar(3)=40", 40, ObjectModel.scalarSize(3));

        eq("array length @16", 16, ObjectModel.ARRAY_LENGTH_OFFSET);
        eq("array elem0 @24", 24, ObjectModel.ARRAY_BASE_OFFSET);
        eq("byte[5] elem3 @27", 27, ObjectModel.arrayElementOffset(3, 1));
        eq("byte[5] size=32", 32, ObjectModel.arraySize(5, 1));   // align(24+5)=32
        eq("long[2] size=40", 40, ObjectModel.arraySize(2, 8));   // 24+16=40

        eq("TIB type slot off 0", 0, ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT));
        eq("TIB vmethod0 slot 1", 1, ObjectModel.tibVMethodSlot(0));
        eq("TIB vmethod0 off 8", 8, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(0)));
        eq("tibSize(3)=32", 32, ObjectModel.tibSize(3));          // align((1+3)*8)=32

        System.out.printf("%n%s%n", failures == 0 ? "object-model layout OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    private static void eq(String name, int expected, int actual) {
        if (expected != actual) { failures++; System.out.printf("FAIL %-20s expected %d got %d%n", name, expected, actual); }
    }
}
