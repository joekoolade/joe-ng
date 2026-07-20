package classfile;

/**
 * The first piece of M5 self-hosting: a classfile reader written to run in
 * <em>both</em> worlds — on the seed JVM (where the writer uses it) and on bare
 * metal (compiled into the image by our own baseline compiler).
 *
 * <p>That dual life dictates the style. It is strictly <b>JDK-free</b>: no String,
 * no collections, no streams, no exceptions — only primitive arrays and int
 * arithmetic, because none of the rest exists on the metal. Results are written
 * into caller-supplied arrays rather than returned as objects, and each method
 * stays under the baseline compiler's ten-local-slot ceiling.
 *
 * <p>It reads a {@code byte[]} rather than a raw address, which is the one
 * representation both sides can supply: the writer hands it
 * {@code Files.readAllBytes}, the loader hands it a heap copy of an embedded blob.
 *
 * <p>Note {@code u1} masks with {@code 0xFF} deliberately: the JVM sign-extends
 * {@code baload} while joe2's compiler zero-extends it, and the mask makes both
 * agree.
 */
public final class ClassReader
{
    private ClassReader() {}

    /** Constant-pool tags we need to size entries and spot classes. */
    public static final int TAG_UTF8 = 1;
    public static final int TAG_CLASS = 7;

    public static int u1(byte[] b, int i)
    {
        return b[i] & 0xFF;
    }

    public static int u2(byte[] b, int i)
    {
        return (u1(b, i) << 8) | u1(b, i + 1);
    }

    public static int u4(byte[] b, int i)
    {
        return (u2(b, i) << 16) | u2(b, i + 2);
    }

    /** Number of constant-pool slots (index 0 unused, longs/doubles take two). */
    public static int cpCount(byte[] b)
    {
        return u2(b, 8);                                 // after magic(4) + minor/major(4)
    }

    /**
     * Walk the constant pool, recording each entry's body offset in {@code off} and
     * its tag in {@code tag}; return the offset just past the pool.
     */
    public static int constantPool(byte[] b, int[] off, int[] tag)
    {
        int n = cpCount(b);
        int p = 10;
        int i = 1;
        while (i < n)
        {
            int t = u1(b, p);
            p += 1;
            off[i] = p;                                  // body starts right after the tag
            tag[i] = t;
            if (t == TAG_UTF8)
            {
                p += 2 + u2(b, p);
            }
            else if (t == 5 || t == 6)                   // Long/Double occupy two slots
            {
                p += 8;
                i += 1;
            }
            else if (t == 15)                            // MethodHandle
            {
                p += 3;
            }
            else if (t == TAG_CLASS || t == 8 || t == 16 || t == 19 || t == 20)
            {
                p += 2;
            }
            else
            {
                p += 4;                                  // *ref / NameAndType / Dynamic
            }
            i += 1;
        }
        return p;
    }

    /** Utf8 offset of the name of the {@code Class} entry at pool index {@code idx}. */
    public static int classNameOff(byte[] b, int[] off, int idx)
    {
        return off[u2(b, off[idx])];
    }

    /** Utf8 offset of this class's own name. {@code afterCp} is {@link #constantPool}'s result. */
    public static int thisClassNameOff(byte[] b, int[] off, int afterCp)
    {
        return classNameOff(b, off, u2(b, afterCp + 2));
    }

    /** Utf8 offset of the superclass's name, or 0 for {@code java/lang/Object} itself. */
    public static int superClassNameOff(byte[] b, int[] off, int afterCp)
    {
        int idx = u2(b, afterCp + 4);
        return idx == 0 ? 0 : classNameOff(b, off, idx);
    }

    /** True if this class is an interface (ACC_INTERFACE). */
    public static boolean isInterface(byte[] b, int afterCp)
    {
        return (u2(b, afterCp) & 0x0200) != 0;
    }

    /** Offset of {@code fields_count}, i.e. just past the interfaces table. */
    public static int fieldsStart(byte[] b, int afterCp)
    {
        return afterCp + 8 + u2(b, afterCp + 6) * 2;     // access, this, super, interfaces
    }

    /** Offset of {@code methods_count}, i.e. just past the fields table. */
    public static int methodsStart(byte[] b, int afterCp)
    {
        return skipMembers(b, fieldsStart(b, afterCp));
    }

    /**
     * Skip a fields or methods table: {@code count} entries of
     * {@code access, name, descriptor, attributes}. {@code p} points at the count.
     */
    public static int skipMembers(byte[] b, int p)
    {
        int n = u2(b, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            p = skipAttributes(b, p + 6);
            i += 1;
        }
        return p;
    }

    /** Skip an attribute table; {@code p} points at the attribute count. */
    public static int skipAttributes(byte[] b, int p)
    {
        int n = u2(b, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            p += 6 + u4(b, p + 2);                       // name(2) + length(4) + body
            i += 1;
        }
        return p;
    }

    /** Compare two Utf8 entries by length and bytes; they may be in different classfiles. */
    public static boolean utf8Eq(byte[] a, int offA, byte[] c, int offC)
    {
        int la = u2(a, offA);
        if (la != u2(c, offC))
        {
            return false;
        }
        int j = 0;
        while (j < la)
        {
            if (u1(a, offA + 2 + j) != u1(c, offC + 2 + j))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }
}
