/**
 * A CASE-INSENSITIVE Unicode regex -- the path that needs {@code jdk.internal.lang.CaseFolding}.
 *
 * <p>JUnit's {@code TimeoutDurationParser.<clinit>} compiles
 * {@code ([1-9]\d*) ?((?:[nμm]?s)|m|h|d)?} with flags 66 = CASE_INSENSITIVE | UNICODE_CASE. A RANGE in
 * such a pattern ({@code [1-9]}) makes {@code Pattern.CIRangeU} ask which characters outside the range
 * case-fold INTO it -- {@code CaseFolding.getClassRangeClosingCharacters}, which was DENYLISTED. The console
 * launcher reached it during execution setup and halted with a named denylist trap.
 *
 * <p>THE FIRST TWO ARM GROUPS WOULD PASS OVER AN EMPTY TABLE, and that is why the third exists. The JUnit
 * pattern's own range is {@code [1-9]}: digits have no case, so its closure is legitimately EMPTY, and a
 * stub returning "no extra characters" answers it perfectly while being wrong for everything else. The
 * closure arms use ranges whose answer is NON-EMPTY on a real JDK -- {@code [ž-ƀ]} contains
 * U+017F LATIN SMALL LETTER LONG S, which folds to {@code 's'}, and {@code [℩-Å]} contains
 * U+212A KELVIN SIGN, which folds to {@code 'k'} -- so they fail against an empty table and pass only if the
 * real 36-entry map is present.
 *
 * <p>Each closure arm carries its own NEGATIVE ({@code "t"} must NOT match), because an implementation that
 * answered "everything folds into this range" would satisfy the positive arm alone. An instrument that
 * cannot say no proves nothing.
 *
 * <p>Every expected value here was taken from a HOST JVM run of the same code, not predicted.
 */
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CaseFoldProbe
{
    public static void main(String[] args)
    {
        // JUnit's exact pattern and flags.
        Pattern p = Pattern.compile("([1-9]\\d*) ?((?:[nμm]?s)|m|h|d)?", 66);
        report(p, "10s", "10", "s");
        report(p, "5 m", "5", "m");
        report(p, "42", "42", null);
        report(p, "3ms", "3", "ms");
        report(p, "7H", "7", "H");

        // The closure itself: a range CONTAINING a code point whose folding lies OUTSIDE it.
        Pattern longS = Pattern.compile("[ž-ƀ]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        System.out.println("closure [017E-0180] matches s = " + longS.matcher("s").matches() + " (want true)");
        System.out.println("closure [017E-0180] matches t = " + longS.matcher("t").matches() + " (want false)");

        Pattern kelvin = Pattern.compile("[℩-Å]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        System.out.println("closure [2129-212B] matches k = " + kelvin.matcher("k").matches() + " (want true)");
        System.out.println("closure [2129-212B] matches t = " + kelvin.matcher("t").matches() + " (want false)");

        System.out.println("CaseFoldProbe done");
    }

    private static void report(Pattern p, String in, String g1, String g2)
    {
        Matcher m = p.matcher(in);
        boolean ok = m.matches();
        String got1 = ok ? m.group(1) : "<no match>";
        String got2 = ok ? m.group(2) : "<no match>";
        System.out.println("junit re \"" + in + "\" = " + ok + " g1=" + got1 + " g2=" + got2
                + " (want true " + g1 + " " + g2 + ")");
    }
}
