package vm;

import classfile.ClassReader;
import magic.Magic;

/**
 * The metal writer's class model (PLAN.md §M5.5c step 1b, approach B): answers the image
 * builder's class-graph queries over the classes the writer embedded in the image (the
 * step-2a name-indexed table at {@link VM#classDir}), parsing raw {@code .class} bytes with
 * the shared {@link ClassReader} — the same reader the seed {@code classfile.ClassFile} and
 * the runtime {@link Loader} use. It mirrors {@code ClassFile}'s algorithms exactly so the
 * on-metal self-build reproduces the seed writer's image byte-for-byte at the fixpoint.
 *
 * <p>Stateless: each query re-loads and re-parses the class it needs. A one-shot self-build
 * does not care about the cost, and holding no global state keeps the queries composable
 * with the superclass-chain walks (vtable/interfaces/findImpl, still to come). This slice
 * covers the single-class leaf queries; the walks land next.
 */
final class MetalClassModel
{
    private MetalClassModel() {}

    private static final int ACC_STATIC = 0x0008;

    /** Class {@code name}'s bytes copied onto the heap, or {@code null} if not embedded (a root). */
    static byte[] bytesOf(byte[] name)
    {
        long i = 0L;
        while (i < VM.classCount)
        {
            long e = VM.classDir + i * 32L;            // {nameAddr, nameLen, bytesAddr, bytesLen}
            if (Magic.load64(e + 8L) == name.length && rawEqualsArray(Magic.load64(e), name))
            {
                return copy(Magic.load64(e + 16L), (int) Magic.load64(e + 24L));
            }
            i = i + 1L;
        }
        return null;
    }

    /** A class name is a root (uncompiled) when it names a JDK class — {@code java/...}. */
    static boolean isRoot(byte[] name)
    {
        return name.length >= 5 && name[0] == 'j' && name[1] == 'a' && name[2] == 'v'
               && name[3] == 'a' && name[4] == '/';
    }

    /** Whether {@code name}'s direct superclass is {@code wantSuper} (false for a root's super). */
    static boolean superIs(byte[] name, byte[] wantSuper)
    {
        byte[] b = bytesOf(name);
        int[] off = constPool(b);
        int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
        int superOff = ClassReader.superClassNameOff(b, off, afterCp);
        return superOff != 0 && utf8Is(b, superOff, wantSuper);
    }

    /** Count of {@code name}'s own non-static (instance) fields — the object's field slots. */
    static int instanceFieldCount(byte[] name)
    {
        byte[] b = bytesOf(name);
        int[] off = constPool(b);
        int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
        int p = ClassReader.fieldsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int count = 0;
        int i = 0;
        while (i < n)
        {
            if ((ClassReader.u2(b, p) & ACC_STATIC) == 0)
            {
                count += 1;
            }
            p = ClassReader.skipAttributes(b, p + 6);   // access, name, desc, then attributes
            i += 1;
        }
        return count;
    }

    /** Whether {@code name} declares a static initializer {@code <clinit>()V} (needs eager init). */
    static boolean hasClinit(byte[] name)
    {
        byte[] b = bytesOf(name);
        int[] off = constPool(b);
        int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
        byte[] clinit = Magic.bytes("<clinit>");
        byte[] voidDesc = Magic.bytes("()V");
        int p = ClassReader.methodsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            if (utf8Is(b, off[ClassReader.u2(b, p + 2)], clinit)
                    && utf8Is(b, off[ClassReader.u2(b, p + 4)], voidDesc))
            {
                return true;
            }
            p = ClassReader.skipAttributes(b, p + 6);
            i += 1;
        }
        return false;
    }

    /** A fresh constant-pool offset table sized for {@code b} (companion to {@code ClassReader.constantPool}). */
    private static int[] constPool(byte[] b)
    {
        return new int[ClassReader.cpCount(b)];
    }

    /** Whether the Utf8 entry at {@code b[off]} equals the plain bytes {@code want}. */
    private static boolean utf8Is(byte[] b, int off, byte[] want)
    {
        int len = ClassReader.u2(b, off);
        if (len != want.length)
        {
            return false;
        }
        int j = 0;
        while (j < len)
        {
            if (ClassReader.u1(b, off + 2 + j) != (want[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** Whether the {@code arr.length} raw bytes at {@code addr} equal {@code arr}. */
    private static boolean rawEqualsArray(long addr, byte[] arr)
    {
        int j = 0;
        while (j < arr.length)
        {
            if (Magic.load8(addr + j) != (arr[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** Copy {@code len} bytes from {@code addr} onto a fresh heap array (for the shared reader). */
    private static byte[] copy(long addr, int len)
    {
        byte[] b = new byte[len];
        int i = 0;
        while (i < len)
        {
            b[i] = (byte) Magic.load8(addr + i);
            i += 1;
        }
        return b;
    }
}
