import jdk.internal.misc.Unsafe;

/**
 * {@code Unsafe.getAndBitwiseOrInt} -- the member stock {@code ForkJoinTask.setDone} needs.
 *
 * <p>WHY THIS EXISTS: the overlay shipped {@code getAndBitwiseOrLong} and not this, so the Int form CEASED
 * TO EXIST -- an overlay wins the name, so a member it omits resolves nowhere and surfaces as a
 * {@code DENYLIST TRAP} blaming a list this class is not on. The caller is stock
 * {@code ForkJoinTask.setDone}, reached through {@code BigDecimal.<clinit>}; only
 * {@code make overlaycheck-deep} sees it, because that caller is stock java.base rather than anything we ship.
 *
 * <p>THE SIBLINGS ARE DELIBERATELY NOT HERE. The And/Xor forms and the Long/Acquire/Release variants are
 * written and host-verified, and adding all eighteen ABORTS THE DEMO SUITE -- a latent layout sensitivity,
 * bisected: HEAD passes, +1 passes, +18 fails whether appended or in place. Adding the surface is this
 * file\'s standing advice and it is not free here, so the rest waits on that defect.
 *
 * <p>THE ARMS ARE CHOSEN SO A PLAUSIBLE WRONG IMPLEMENTATION FAILS, not so a working one passes:
 * <ul>
 *   <li><b>Every arm prints the RETURN VALUE and the FIELD.</b> These are {@code getAnd*}: the return is the
 *       value BEFORE the update. An implementation returning the new value is correct in the field and wrong
 *       in the result, and an arm checking only the field cannot tell.</li>
 *   <li><b>Or/And/Xor are given a start and mask where all three answers differ</b> ({@code 0b1100} with
 *       {@code 0b1010} gives 14 / 8 / 6). A shared start of 0, or a mask of 0, makes two of the three agree
 *       and tests nothing about which operator ran.</li>
 *   <li><b>The sign-bit arm does a SECOND op on the same field</b>, and that is the joe-ng-specific one. An
 *       int lives in an 8-byte slot kept sign-extended (Baseline.canonInt); if a store left the slot
 *       non-canonical the value still reads back correctly through one {@code (int)} cast, and the NEXT
 *       CAS's expected word no longer matches what is stored -- so the retry loop spins for ever. That
 *       failure is a HANG, not a wrong number, which is exactly why one op is not enough to find it.</li>
 *   <li><b>The long arm uses a mask above 2^32</b>, so a long path that truncated to 32 bits is visible.</li>
 *   <li><b>Acquire/Release are compared against plain</b> on the same start, since they share its body.</li>
 * </ul>
 *
 * <p>Run the HOST CONTROL first and diff -- it needs
 * {@code --add-exports java.base/jdk.internal.misc=ALL-UNNAMED}. Every number below is a real JVM's answer,
 * not one derived from this overlay.
 */
public class BitwiseRmwProbe
{
    // Instance FIELDS, deliberately: these methods take an 8-byte field slot here. An int ARRAY element is
    // 4 bytes (ARRAY_INT_INDEX_SCALE), and joe-ng has no 32-bit CAS to address one with -- see the note on
    // the read-modify-write family in the Unsafe overlay.
    int i;

    public static void main(String[] args) throws Exception
    {
        Unsafe u = Unsafe.getUnsafe();
        long io = u.objectFieldOffset(BitwiseRmwProbe.class.getDeclaredField("i"));

        BitwiseRmwProbe p = new BitwiseRmwProbe();

        // Start and mask chosen so OR is distinguishable from AND (8) and XOR (6) and from the start (12):
        // a shared start of 0, or a mask of 0, would pass whichever operator ran.
        p.i = 12;
        int r = u.getAndBitwiseOrInt(p, io, 10);
        System.out.println("orInt   ret=" + r + " field=" + p.i + " (want ret=12 field=14)");

        // Already-set bits must not double-count, and the return is still the OLD value.
        p.i = 14;
        r = u.getAndBitwiseOrInt(p, io, 10);
        System.out.println("orIdem  ret=" + r + " field=" + p.i + " (want ret=14 field=14)");

        // THE SIGN-EXTENSION ARM. The SECOND op is the test; the first only sets it up. An int lives in an
        // 8-byte slot kept sign-extended (Baseline.canonInt); a store that left the slot non-canonical still
        // reads back correctly through one (int) cast, and the next CAS's expected word no longer matches
        // what is stored -- so the retry loop spins for ever. That failure is a HANG, not a wrong number,
        // which is exactly why one op cannot find it.
        p.i = 1;
        r = u.getAndBitwiseOrInt(p, io, 0x80000000);
        System.out.println("signOr  ret=" + r + " field=" + p.i + " (want ret=1 field=-2147483647)");
        r = u.getAndBitwiseOrInt(p, io, 0x00000002);
        System.out.println("signOr2 ret=" + r + " field=" + p.i + " (want ret=-2147483647 field=-2147483645)");

        // The shape the blocker actually has: ForkJoinTask.setDone ORs a status bit and reads the old value.
        // The bit is printed as an INT, not a boolean: joe-ng renders a boolean concat argument as 1/0
        // rather than true/false (Baseline.appendArg routes 'Z' to SC_INT), which would diverge from the
        // host control for a reason that has nothing to do with this method. Separate bug, separate fix.
        p.i = 0;
        int prev = u.getAndBitwiseOrInt(p, io, 0x40000000);
        System.out.println("setDone prev=" + prev + " bit=" + (p.i & 0x40000000) + " (want prev=0 bit=1073741824)");

        System.out.println("BitwiseRmwProbe done");
    }
}
