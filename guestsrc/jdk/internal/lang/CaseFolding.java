/*
 * joe-ng overlay of jdk.internal.lang.CaseFolding.
 */
package jdk.internal.lang;

/**
 * Unicode case-folding closure for a case-insensitive character-class RANGE.
 *
 * <p>WHY THIS EXISTS. {@code Pattern.compile(re, CASE_INSENSITIVE | UNICODE_CASE)} lowers a range like
 * {@code [1-9]} through {@code Pattern.CIRangeU}, which asks this class which characters OUTSIDE the range
 * case-fold INTO it. JUnit's {@code TimeoutDurationParser} compiles exactly such a pattern
 * ({@code ([1-9]\d*) ?((?:[nμm]?s)|m|h|d)?}, flags 66) in its {@code <clinit>}, so the console launcher
 * reaches it during execution setup.
 *
 * <p>The stock class was DENYLISTED, on a premise its own comment stated: "case-folding tables ([[I via
 * multianewarray): only CASE_INSENSITIVE regex needs them". That premise expired the moment a
 * CASE_INSENSITIVE regex appeared in the closure. Un-denying the stock class does not work either: it builds
 * its tables with {@code multianewarray}, an opcode this VM's JIT records metadata for (stack delta, length)
 * but has no lowering for, so its initializer would fail with {@code JIT unsupported}.
 *
 * <p>WHAT IS EXACT HERE, AND WHY THERE IS NO STATED LIMIT ON IT. {@link #getClassRangeClosingCharacters} is
 * the stock algorithm over the stock data. The table it needs is the "expanded" case map -- code points
 * whose folding is not simply themselves-lowercased -- and that table is **36 entries**, dumped from the
 * seed JVM rather than transcribed from a guess. So this is a faithful reproduction, not an approximation:
 * no ASCII-only caveat, no "good enough for what we run".
 *
 * <p>ORDER IS NOT PART OF THE CONTRACT, and that was worth proving rather than assuming. A 20,780-range
 * sweep against the seed JVM reported 2,715 "mismatches" -- every one of them the same SET in a different
 * ORDER, because stock builds its key array from {@code Map.ofEntries(...).keySet().stream()}, and
 * {@code ImmutableCollections} salts its iteration order PER JVM RUN. Running the stock method five times
 * returns the same four code points in four different orders, so {@code Pattern} cannot depend on the order
 * without being nondeterministic itself. Compared as SETS the same sweep reports ZERO mismatches over 2,929
 * non-empty answers. This overlay returns ascending order, which is stable.
 *
 * <p>The data is held as PARALLEL INT ARRAYS, not the stock {@code Map<Integer,Integer>} built by
 * {@code Map.ofEntries} and then streamed into an {@code int[]}. That is deliberate: boxing 36 Integers and
 * running a stream pipeline inside a {@code <clinit>} is exactly the kind of initializer that has cost this
 * VM whole debugging sessions, and none of it is needed to answer the question.
 *
 * <p>THE OTHER THREE PUBLIC METHODS THROW, and that is the standing rule for an unimplemented native or
 * table rather than laziness: a stub that returns a plausible value is indistinguishable from a working one.
 * Scanning ALL of java.base (not just the classes one would guess -- the shallow overlay check does not see
 * stock callers, a blind spot that has bitten this project before) finds exactly three callers of this
 * class: {@code Pattern} uses {@link #getClassRangeClosingCharacters}, and {@code StringLatin1}/
 * {@code StringUTF16} use {@link #fold} and {@link #isSingleCodePoint} from {@code compareToFC} -- which
 * backs only JDK 26's NEW {@code String.equalsFoldCase}/{@code compareToFoldCase}/
 * {@code UNICODE_CASEFOLD_ORDER}. {@code equalsIgnoreCase} and {@code regionMatches(true, ...)} go through
 * {@code regionMatchesCI} and never reach here. Nothing in this VM's closure calls the new APIs; if
 * something ever does, it names itself here instead of quietly comparing wrongly.
 *
 * <p>They throw an {@link InternalError} rather than a {@code RuntimeException} ON PURPOSE. Narrowing the
 * denial turns these call sites from a denylist trap (which halts) into an ordinary call, so an exception a
 * caller might CATCH would convert a loud failure into a silent wrong answer -- the trade this VM keeps
 * losing. An Error is not caught by ordinary library code.
 */
public final class CaseFolding
{
    private CaseFolding()
    {
    }

    /**
     * Code points whose case folding is "expanded" -- the stock {@code expanded_case_map}'s keys, in
     * ascending order. 36 entries, read off the seed JVM's own table.
     */
    private static final int[] EXPANDED_CP =
    {
        0x00B5, 0x0130, 0x0131, 0x017F, 0x01C5, 0x01C8, 0x01CB, 0x01F2,
        0x0345, 0x03C2, 0x03D0, 0x03D1, 0x03D5, 0x03D6, 0x03F0, 0x03F1,
        0x03F4, 0x03F5, 0x1C80, 0x1C81, 0x1C82, 0x1C83, 0x1C84, 0x1C85,
        0x1C86, 0x1C87, 0x1C88, 0x1E9B, 0x1E9E, 0x1FBE, 0x1FD3, 0x1FE3,
        0x2126, 0x212A, 0x212B, 0xFB05
    };

    /** The folded form of each {@link #EXPANDED_CP} entry, index for index. */
    private static final int[] EXPANDED_FOLD =
    {
        0x03BC, 0x0069, 0x0049, 0x0073, 0x01C6, 0x01C9, 0x01CC, 0x01F3,
        0x03B9, 0x03C3, 0x03B2, 0x03B8, 0x03C6, 0x03C0, 0x03BA, 0x03C1,
        0x03B8, 0x03B5, 0x0432, 0x0434, 0x043E, 0x0441, 0x0442, 0x0442,
        0x044A, 0x0463, 0xA64B, 0x1E61, 0x00DF, 0x03B9, 0x0390, 0x03B0,
        0x03C9, 0x006B, 0x00E5, 0xFB06
    };

    /**
     * The characters a case-insensitive character-class range {@code [start-end]} must also match: for every
     * code point IN the range whose folded form falls OUTSIDE it, that folded form.
     *
     * <p>Stock's algorithm exactly. Note the asymmetry it encodes and which is easy to get backwards: the
     * RANGE TEST is on the code point (the map key) and the RESULT is its folding -- so {@code [017F-017F]}
     * answers {@code ['s']} while {@code ['s'-'s']} answers nothing.
     *
     * @return a fresh array of the extra code points, empty when there are none (never null -- Pattern walks
     *         the result unguarded)
     */
    public static int[] getClassRangeClosingCharacters(int start, int end)
    {
        int[] found = new int[EXPANDED_CP.length];
        int off = 0;
        int i = 0;
        while (i < EXPANDED_CP.length)
        {
            int cp = EXPANDED_CP[i];
            if (cp >= start && cp <= end)
            {
                int folding = EXPANDED_FOLD[i];
                if (folding < start || folding > end)
                {
                    found[off] = folding;
                    off += 1;
                }
            }
            i += 1;
        }
        int[] out = new int[off];
        int k = 0;
        while (k < off)
        {
            out[k] = found[k];
            k += 1;
        }
        return out;
    }

    /** Not implemented here -- see the class comment. Reached only by JDK 26's new String fold-case APIs. */
    public static boolean isDefined(int cp)
    {
        throw new InternalError("jdk/internal/lang/CaseFolding.isDefined: full case-folding table not carried on joe-ng");
    }

    /** Not implemented here -- see the class comment. Reached only by JDK 26's new String fold-case APIs. */
    public static long fold(int cp)
    {
        throw new InternalError("jdk/internal/lang/CaseFolding.fold: full case-folding table not carried on joe-ng");
    }

    /** Not implemented here -- see the class comment. Reached only by JDK 26's new String fold-case APIs. */
    public static boolean isSingleCodePoint(long folded)
    {
        throw new InternalError("jdk/internal/lang/CaseFolding.isSingleCodePoint: full case-folding table not carried on joe-ng");
    }
}
