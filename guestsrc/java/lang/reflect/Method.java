package java.lang.reflect;

import magic.Magic;

/**
 * {@code java.lang.reflect.Method} for joe-ng: reflective invocation of a loaded method. A Method carries its
 * declaring {@link Class}, name, method-registry index, access flags, compiled-buffer address, and its
 * descriptor's parameter type-chars + return type-char (resolved once at construction via {@link #methodInfo0}).
 * {@link #invoke} marshals the {@code Object[]} args into an 8-slot register buffer (receiver in slot 0 for an
 * instance method, then one slot per parameter — primitives unboxed, references reinterpreted to their address),
 * calls the target via {@link Magic#callN}, and boxes the return per the return type. Access enforcement
 * ({@code setAccessible} + member-access rules) lands in reflection arc M2; virtual override dispatch is direct
 * (the resolved method's buffer) for now.
 */
public final class Method extends AccessibleObject
{
    private final Class<?> clazz;
    private final String name;
    private final int access;
    private final long buf;
    private final int paramCount;
    private final byte[] paramChars;   // first descriptor char of each parameter ('I'/'J'/'Z'/.../'L'/'[')
    private final int returnChar;
    private final int rgIndex;         // this method's slot in the VM's method registry (annotation lookup key)

    private Method(Class<?> clazz, String name, int access, long buf, int paramCount, byte[] paramChars, int returnChar,
            int rgIndex)
    {
        this.rgIndex = rgIndex;
        this.clazz = clazz;
        this.name = name;
        this.access = access;
        this.buf = buf;
        this.paramCount = paramCount;
        this.paramChars = paramChars;
        this.returnChar = returnChar;
    }

    /** Resolve the method named {@code name} declared by {@code c} (first match, no overload resolution yet).
     *  Public so {@code java.lang.Class.getDeclaredMethod} (a different package) can build the Method — this is
     *  an overlay-only entry point, not part of the stock {@code Method} API. */
    public static Method resolve(Class<?> c, String name) throws NoSuchMethodException
    {
        int idx = methodResolve0(c, name.getBytes());
        if (idx < 0)
        {
            throw new NoSuchMethodException(name);
        }
        byte[] pchars = new byte[8];
        long[] out = new long[3];                          // {buffer, access, returnChar}
        int n = methodInfo0(idx, pchars, out);
        return new Method(c, name, (int) out[1], out[0], n, pchars, (int) out[2], idx);
    }

    /**
     * Resolve the method with this name AND descriptor. A class may declare two methods of the same name, and
     * then the name alone identifies neither: {@link #resolve(Class,String)} answers with whichever the registry
     * scan reaches first, for both. Callers that HAVE the descriptor (enumeration via
     * {@code Class.getDeclaredMethods}) must use this, or every overload in the array is the same Method.
     */
    public static Method resolve(Class<?> c, String name, String desc) throws NoSuchMethodException
    {
        int idx = methodResolveDesc0(c, name.getBytes(), desc == null ? null : desc.getBytes());
        if (idx < 0)
        {
            throw new NoSuchMethodException(name);
        }
        byte[] pchars = new byte[8];
        long[] out = new long[3];                          // {buffer, access, returnChar}
        int n = methodInfo0(idx, pchars, out);
        return new Method(c, name, (int) out[1], out[0], n, pchars, (int) out[2], idx);
    }

    /** VM native: registry index of the method with this name AND descriptor, or -1. */
    private static native int methodResolveDesc0(Class c, byte[] name, byte[] desc);

    /**
     * True if this method carries the given annotation. Marker level: presence only, no element values.
     *
     * <p>Only annotations declared {@code @Retention(RUNTIME)} are visible -- javac writes anything else into
     * RuntimeINVISIBLEAnnotations, i.e. an annotation without RUNTIME retention is not merely unreadable here,
     * it is absent from the classfile.
     */
    public boolean isAnnotationPresent(Class<?> anno)
    {
        if (anno == null)
        {
            return false;
        }
        return annoPresent0(rgIndex, descriptorOf(anno)) != 0;
    }

    /** "org.junit.jupiter.api.Test" -> the field descriptor "Lorg/junit/jupiter/api/Test;" the classfile holds. */
    private static byte[] descriptorOf(Class<?> anno)
    {
        String n = anno.getName();
        byte[] out = new byte[n.length() + 2];
        out[0] = (byte) 'L';
        int i = 0;
        while (i < n.length())
        {
            char c = n.charAt(i);
            out[i + 1] = (byte) (c == '.' ? '/' : c);
            i += 1;
        }
        out[n.length() + 1] = (byte) ';';
        return out;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.annoPresent}): registry index + descriptor -> 0/1. */
    private static native int annoPresent0(int rgIndex, byte[] descriptor);

    private static native int methodResolve0(Class c, byte[] name);
    private static native int methodInfo0(int rgIndex, byte[] paramChars, long[] out);

    public String getName()
    {
        return name;
    }

    public int getModifiers()
    {
        return access;
    }

    public Class<?> getDeclaringClass()
    {
        return clazz;
    }

    /**
     * The access-flag predicates. Each is one bit of the method's own {@code access_flags}, which this Method
     * already carries, so leaving them undeclared bought nothing and cost a DENYLIST TRAP on the day one was
     * reached -- JUnit filters candidate test methods with exactly these ({@code isSynthetic} and
     * {@code isBridge} exclude the compiler-generated ones).
     *
     * <p>ACC_BRIDGE and ACC_VARARGS share their bit values with ACC_VOLATILE/ACC_TRANSIENT, which is why they
     * are only meaningful on a METHOD -- reading them off a field would be nonsense.
     */
    public boolean isBridge()
    {
        return (access & 0x0040) != 0;                  // ACC_BRIDGE
    }

    public boolean isVarArgs()
    {
        return (access & 0x0080) != 0;                  // ACC_VARARGS
    }

    public boolean isSynthetic()
    {
        return (access & 0x1000) != 0;                  // ACC_SYNTHETIC
    }

    /**
     * A DEFAULT method: declared in an interface, neither abstract nor static. The interface test comes from
     * the declaring class, since the flag itself is an absence rather than a bit.
     */
    public boolean isDefault()
    {
        return clazz != null && clazz.isInterface()
                && (access & 0x0408) == 0;              // not ACC_ABSTRACT (0x0400), not ACC_STATIC (0x0008)
    }

    public int getParameterCount()
    {
        return paramCount;
    }

    /** Invoke this method: {@code recv} is the receiver (ignored/null for a static method), {@code args} the
     *  boxed arguments. Returns the boxed result (null for a {@code void} method). */
    public Object invoke(Object recv, Object... args) throws IllegalAccessException, InvocationTargetException
    {
        checkAccess(clazz, access, Magic.readLR());
        long[] slots = new long[8];
        long base = Magic.addrOf(slots) + 24L;             // array elements (header 16 + length 8)
        boolean isStatic = (access & 0x0008) != 0;
        int s = 0;
        if (!isStatic)
        {
            Magic.store64(base, recv == null ? 0L : Magic.addrOf(recv));
            s = 1;
        }
        int k = 0;
        while (k < paramCount)
        {
            Magic.store64(base + (s + k) * 8L, marshal(paramChars[k], args[k]));
            k += 1;
        }
        long ret = Magic.callN(buf, base);
        return box(returnChar, ret);
    }

    /** Unbox/reinterpret one argument to its raw 64-bit register value per its declared type char. */
    private static long marshal(int tc, Object a)
    {
        if (tc == 'I') { return ((Integer) a).intValue(); }
        if (tc == 'J') { return ((Long) a).longValue(); }
        if (tc == 'Z') { return ((Boolean) a).booleanValue() ? 1L : 0L; }
        if (tc == 'B') { return ((Byte) a).byteValue(); }
        if (tc == 'C') { return ((Character) a).charValue(); }
        if (tc == 'S') { return ((Short) a).shortValue(); }
        return a == null ? 0L : Magic.addrOf(a);           // 'L' / '[' reference
    }

    /** Box the raw 64-bit return value per the return type char (null for 'V'). */
    private static Object box(int tc, long ret)
    {
        if (tc == 'V') { return null; }
        if (tc == 'I') { return Integer.valueOf((int) ret); }
        if (tc == 'J') { return Long.valueOf(ret); }
        if (tc == 'Z') { return Boolean.valueOf(ret != 0L); }
        if (tc == 'B') { return Byte.valueOf((byte) ret); }
        if (tc == 'C') { return Character.valueOf((char) ret); }
        if (tc == 'S') { return Short.valueOf((short) ret); }
        return Magic.fromAddr(ret);                        // 'L' / '[' reference
    }
}
