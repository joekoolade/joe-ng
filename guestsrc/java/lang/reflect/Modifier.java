package java.lang.reflect;

/**
 * Mini {@code java/lang/reflect/Modifier} — the JVMS access-flag constants + predicates + {@code toString}.
 * JDK-free (no collections/streams): {@code toString} builds the modifier list with a {@link StringBuilder}.
 */
public class Modifier
{
    public static final int PUBLIC       = 0x0001;
    public static final int PRIVATE      = 0x0002;
    public static final int PROTECTED    = 0x0004;
    public static final int STATIC       = 0x0008;
    public static final int FINAL        = 0x0010;
    public static final int SYNCHRONIZED = 0x0020;
    public static final int VOLATILE     = 0x0040;
    public static final int TRANSIENT    = 0x0080;
    public static final int NATIVE       = 0x0100;
    public static final int INTERFACE    = 0x0200;
    public static final int ABSTRACT     = 0x0400;
    public static final int STRICT       = 0x0800;

    public static boolean isPublic(int m)       { return (m & PUBLIC) != 0; }
    public static boolean isPrivate(int m)      { return (m & PRIVATE) != 0; }
    public static boolean isProtected(int m)    { return (m & PROTECTED) != 0; }
    public static boolean isStatic(int m)       { return (m & STATIC) != 0; }
    public static boolean isFinal(int m)        { return (m & FINAL) != 0; }
    public static boolean isSynchronized(int m) { return (m & SYNCHRONIZED) != 0; }
    public static boolean isVolatile(int m)     { return (m & VOLATILE) != 0; }
    public static boolean isTransient(int m)    { return (m & TRANSIENT) != 0; }
    public static boolean isNative(int m)       { return (m & NATIVE) != 0; }
    public static boolean isInterface(int m)    { return (m & INTERFACE) != 0; }
    public static boolean isAbstract(int m)     { return (m & ABSTRACT) != 0; }
    public static boolean isStrict(int m)       { return (m & STRICT) != 0; }

    /** Modifiers legal on a class/interface declaration. */
    public static int classModifiers()
    {
        return PUBLIC | PROTECTED | PRIVATE | ABSTRACT | STATIC | FINAL | STRICT;
    }

    /** Space-separated modifier keywords in canonical (JLS) order — matches the stock {@code toString}. */
    public static String toString(int mod)
    {
        StringBuilder sb = new StringBuilder();
        if ((mod & PUBLIC) != 0)       { sb.append("public ");       }
        if ((mod & PROTECTED) != 0)    { sb.append("protected ");    }
        if ((mod & PRIVATE) != 0)      { sb.append("private ");      }
        if ((mod & ABSTRACT) != 0)     { sb.append("abstract ");     }
        if ((mod & STATIC) != 0)       { sb.append("static ");       }
        if ((mod & FINAL) != 0)        { sb.append("final ");        }
        if ((mod & TRANSIENT) != 0)    { sb.append("transient ");    }
        if ((mod & VOLATILE) != 0)     { sb.append("volatile ");     }
        if ((mod & SYNCHRONIZED) != 0) { sb.append("synchronized "); }
        if ((mod & NATIVE) != 0)       { sb.append("native ");       }
        if ((mod & STRICT) != 0)       { sb.append("strictfp ");     }
        if ((mod & INTERFACE) != 0)    { sb.append("interface ");    }
        int len = sb.length();
        if (len > 0)
        {
            return sb.toString().substring(0, len - 1);   // trim the trailing space
        }
        return "";
    }
}
