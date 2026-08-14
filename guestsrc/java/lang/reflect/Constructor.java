package java.lang.reflect;

import magic.Magic;

/**
 * {@code java.lang.reflect.Constructor} for joe-ng: reflective instantiation of a loaded class. A Constructor
 * carries its declaring {@link Class}, the method-registry index of the matching {@code <init>}, access flags,
 * compiled-buffer address, and its descriptor's parameter type-chars (resolved once at construction via the
 * shared {@link #methodInfo0}). {@link #newInstance} allocates a fresh zeroed instance (header + TIB via
 * {@link #allocInstance0}), marshals the {@code Object[]} args into a register buffer (the new object in slot 0
 * as the {@code <init>} receiver, then one slot per parameter — primitives unboxed, references reinterpreted to
 * their address), runs {@code <init>} via {@link Magic#callN}, and returns the object. Overload resolution is by
 * arity only for now (no parameter-type matching). Access enforcement mirrors {@link Method}.
 */
public final class Constructor<T> extends AccessibleObject
{
    private final Class<T> clazz;
    private final int access;
    private final long buf;
    private final int paramCount;
    private final byte[] paramChars;   // first descriptor char of each parameter ('I'/'J'/'Z'/.../'L'/'[')

    private Constructor(Class<T> clazz, int access, long buf, int paramCount, byte[] paramChars)
    {
        this.clazz = clazz;
        this.access = access;
        this.buf = buf;
        this.paramCount = paramCount;
        this.paramChars = paramChars;
    }

    /** Resolve the {@code <init>} of {@code c} taking {@code paramCount} parameters (arity match, first found).
     *  Public so {@code java.lang.Class.getDeclaredConstructor} (a different package) can build it — an
     *  overlay-only entry point, not part of the stock {@code Constructor} API. */
    public static <T> Constructor<T> resolve(Class<T> c, int paramCount) throws NoSuchMethodException
    {
        int idx = ctorResolve0(c, paramCount);
        if (idx < 0)
        {
            throw new NoSuchMethodException("<init>");
        }
        byte[] pchars = new byte[8];
        long[] out = new long[3];                          // {buffer, access, returnChar} — returnChar is 'V'
        int n = methodInfo0(idx, pchars, out);
        return new Constructor<T>(c, (int) out[1], out[0], n, pchars);
    }

    private static native int ctorResolve0(Class c, int paramCount);
    private static native int methodInfo0(int rgIndex, byte[] paramChars, long[] out);
    private static native Object allocInstance0(Class c);

    public int getModifiers()
    {
        return access;
    }

    public Class<T> getDeclaringClass()
    {
        return clazz;
    }

    public int getParameterCount()
    {
        return paramCount;
    }

    /** Allocate a fresh instance of the declaring class and run this {@code <init>} with {@code args}. */
    public T newInstance(Object... args) throws IllegalAccessException, InvocationTargetException,
                                                InstantiationException
    {
        checkAccess(clazz, access, Magic.readLR());
        Object obj = allocInstance0(clazz);
        if (obj == null)
        {
            throw new InstantiationException();
        }
        long[] slots = new long[8];
        long base = Magic.addrOf(slots) + 24L;             // array elements (header 16 + length 8)
        Magic.store64(base, Magic.addrOf(obj));            // receiver in slot 0 (<init> is an instance method)
        int k = 0;
        while (k < paramCount)
        {
            Magic.store64(base + (1 + k) * 8L, marshal(paramChars[k], args[k]));
            k += 1;
        }
        Magic.callN(buf, base);                            // <init> returns void
        return (T) obj;
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
}
