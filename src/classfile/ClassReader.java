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
 * {@code baload} while joe-ng's compiler zero-extends it, and the mask makes both
 * agree.
 */
public final class ClassReader
{
    private ClassReader() {}

    /** Constant-pool tags we need to size entries, spot classes and decode constants. */
    public static final int TAG_UTF8 = 1;
    public static final int TAG_INTEGER = 3;
    public static final int TAG_LONG = 5;
    public static final int TAG_CLASS = 7;
    public static final int TAG_STRING = 8;

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

    // ----- *ref entries (Fieldref/Methodref/InterfaceMethodref: {class, NameAndType}) -----
    // The single place that knows this layout, so the writer's parser and the metal
    // loader stop each carrying their own copy (M5.4.3).

    /** Utf8 offset of the declaring class's name for the {@code *ref} at {@code idx}. */
    public static int refClassNameOff(byte[] b, int[] off, int idx)
    {
        return classNameOff(b, off, u2(b, off[idx]));              // *ref.class_index -> Class -> name
    }

    /** Utf8 offset of the member name for the {@code *ref} at {@code idx}. */
    public static int refNameOff(byte[] b, int[] off, int idx)
    {
        int nat = u2(b, off[idx] + 2);                            // *ref.name_and_type_index
        return off[u2(b, off[nat])];                              // NameAndType.name_index -> Utf8
    }

    /** Utf8 offset of the member descriptor for the {@code *ref} at {@code idx}. */
    public static int refDescOff(byte[] b, int[] off, int idx)
    {
        int nat = u2(b, off[idx] + 2);
        return off[u2(b, off[nat] + 2)];                          // NameAndType.descriptor_index -> Utf8
    }

    /** Utf8 offset of the string body of the {@code String} entry at {@code idx}. */
    public static int stringUtf8Off(byte[] b, int[] off, int idx)
    {
        return off[u2(b, off[idx])];                              // String.string_index -> Utf8
    }

    /** Value of the {@code Integer} entry at {@code idx}. */
    public static int intValue(byte[] b, int[] off, int idx)
    {
        return u4(b, off[idx]);
    }

    /** Value of the {@code Long} entry at {@code idx} (two u4 words, high then low). */
    public static long longValue(byte[] b, int[] off, int idx)
    {
        long hi = u4(b, off[idx]) & 0xFFFFFFFFL;
        long lo = u4(b, off[idx] + 4) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    // ----- method descriptors -----
    // A descriptor's Utf8 body is at descOff: [u2 length][chars], chars starting
    // "(params)ret". Each parameter maps to one argument register (a long/double
    // still one register), so we count parameters, not slots. Internal class names
    // contain no ')', so scanning raw for the params terminator is safe.

    /** Parameter count of the method descriptor whose Utf8 body is at {@code descOff}. */
    public static int descParamCount(byte[] b, int descOff)
    {
        int p = descOff + 2 + 1;                    // past u2 length and '('
        int count = 0;
        while (u1(b, p) != ')')
        {
            int c = u1(b, p);
            if (c == 'L')
            {
                count += 1;
                while (u1(b, p) != ';')
                {
                    p += 1;
                }
                p += 1;
            }
            else if (c == '[')
            {
                p += 1;                             // array prefix folds into its element
            }
            else
            {
                count += 1;
                p += 1;
            }
        }
        return count;
    }

    /** Return-type kind char ('V','I','J',...) of the descriptor at {@code descOff}. */
    public static char descReturnKind(byte[] b, int descOff)
    {
        int p = descOff + 2 + 1;
        while (u1(b, p) != ')')
        {
            p += 1;
        }
        return (char) u1(b, p + 1);
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
