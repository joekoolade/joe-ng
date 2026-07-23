package vm;

import classfile.ClassReader;
import magic.Magic;
import objectmodel.ObjectModel;

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

    /** Byte offset of instance field {@code fieldName} in {@code clsName} (inherited fields first). */
    static int instanceFieldOffset(byte[] clsName, byte[] fieldName)
    {
        return ObjectModel.fieldOffset(superFieldCount(clsName) + ownFieldIndex(clsName, fieldName));
    }

    /** Number of instance fields declared in {@code clsName}'s superclasses. */
    private static int superFieldCount(byte[] clsName)
    {
        int n = 0;
        byte[] c = superName(clsName);
        while (c != null && !isRoot(c))
        {
            n += instanceFieldCount(c);
            c = superName(c);
        }
        return n;
    }

    /** Position of {@code fieldName} among {@code clsName}'s own non-static fields, or -1. */
    private static int ownFieldIndex(byte[] clsName, byte[] fieldName)
    {
        byte[] b = bytesOf(clsName);
        int[] off = constPool(b);
        int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
        int p = ClassReader.fieldsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int idx = 0;
        int i = 0;
        while (i < n)
        {
            int access = ClassReader.u2(b, p);
            int nameOff = off[ClassReader.u2(b, p + 2)];
            if ((access & ACC_STATIC) == 0)
            {
                if (utf8Is(b, nameOff, fieldName))
                {
                    return idx;
                }
                idx += 1;
            }
            p = ClassReader.skipAttributes(b, p + 6);
            i += 1;
        }
        return -1;
    }

    /** {@code clsName}'s superclass name bytes, or null if it has none (a root). */
    private static byte[] superName(byte[] clsName)
    {
        byte[] b = bytesOf(clsName);
        int[] off = constPool(b);
        int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
        return superNameOf(b, off, afterCp);
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

    // ----- superclass-chain walks (mirror classfile.ClassFile's recursion) ------------

    private static final int MAX_SLOTS = 32;
    private static byte[][] vName;    // flattened-vtable slot: method name bytes
    private static byte[][] vDesc;    // ... descriptor bytes
    private static byte[][] vOwner;   // ... owning class name bytes (most-derived declarer)
    private static int vCount;

    /** Number of slots in {@code name}'s flattened vtable. */
    static int vtableSize(byte[] name)
    {
        buildVtable(name);
        return vCount;
    }

    /** Slot index of {@code mName+mDesc} in {@code name}'s flattened vtable, or -1. */
    static int vtableSlot(byte[] name, byte[] mName, byte[] mDesc)
    {
        buildVtable(name);
        return findSlot(mName, mDesc);
    }

    /** Whether {@code name}'s vtable {@code slot} is declared/overridden by {@code wantOwner}. */
    static boolean vtableOwnerIs(byte[] name, int slot, byte[] wantOwner)
    {
        buildVtable(name);
        return slot >= 0 && slot < vCount && bytesEq(vOwner[slot], wantOwner);
    }

    /** Build {@code name}'s flattened vtable into the scratch arrays; superclass slots first. */
    private static void buildVtable(byte[] name)
    {
        vName = new byte[MAX_SLOTS][];
        vDesc = new byte[MAX_SLOTS][];
        vOwner = new byte[MAX_SLOTS][];
        vCount = 0;
        fillVtable(name);
    }

    private static void fillVtable(byte[] name)
    {
        byte[] b = bytesOf(name);
        int[] off = constPool(b);
        int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
        byte[] sup = superNameOf(b, off, afterCp);
        if (sup != null && !isRoot(sup))
        {
            fillVtable(sup);                       // parent slots first (shared indices)
        }
        byte[] init = Magic.bytes("<init>");
        int p = ClassReader.methodsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            int access = ClassReader.u2(b, p);
            int nameOff = off[ClassReader.u2(b, p + 2)];
            int descOff = off[ClassReader.u2(b, p + 4)];
            if ((access & ACC_STATIC) == 0 && !utf8Is(b, nameOff, init))
            {
                byte[] mName = utf8Bytes(b, nameOff);
                byte[] mDesc = utf8Bytes(b, descOff);
                int idx = findSlot(mName, mDesc);
                if (idx < 0)
                {
                    idx = vCount;                  // a new virtual method: append
                    vCount += 1;
                }
                vName[idx] = mName;                // else override the inherited slot in place
                vDesc[idx] = mDesc;
                vOwner[idx] = name;                // owner = the class at this recursion level
            }
            p = ClassReader.skipAttributes(b, p + 6);
            i += 1;
        }
    }

    private static int findSlot(byte[] mName, byte[] mDesc)
    {
        int i = 0;
        while (i < vCount)
        {
            if (bytesEq(vName[i], mName) && bytesEq(vDesc[i], mDesc))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Count of {@code iface}'s interface methods (non-static, not {@code <init>}/{@code <clinit>}). */
    static int interfaceMethodCount(byte[] iface)
    {
        return interfaceMethodSlot(iface, null, null);   // no match -> returns the total count
    }

    /**
     * Slot index of interface method {@code mName+mDesc} in {@code iface} (declaration order),
     * or, when {@code mName} is {@code null}, the interface's method count.
     */
    static int interfaceMethodSlot(byte[] iface, byte[] mName, byte[] mDesc)
    {
        byte[] b = bytesOf(iface);
        int[] off = constPool(b);
        int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
        byte[] init = Magic.bytes("<init>");
        byte[] clinit = Magic.bytes("<clinit>");
        int p = ClassReader.methodsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int slot = 0;
        int i = 0;
        while (i < n)
        {
            int access = ClassReader.u2(b, p);
            int nameOff = off[ClassReader.u2(b, p + 2)];
            int descOff = off[ClassReader.u2(b, p + 4)];
            if ((access & ACC_STATIC) == 0 && !utf8Is(b, nameOff, init) && !utf8Is(b, nameOff, clinit))
            {
                if (mName != null && utf8Is(b, nameOff, mName) && utf8Is(b, descOff, mDesc))
                {
                    return slot;
                }
                slot += 1;
            }
            p = ClassReader.skipAttributes(b, p + 6);
            i += 1;
        }
        return mName == null ? slot : -1;
    }

    /** Whether {@code cls} implements {@code ifaceName}, directly or via a superclass. */
    static boolean implementsInterface(byte[] cls, byte[] ifaceName)
    {
        byte[] c = cls;
        while (c != null && !isRoot(c))
        {
            byte[] b = bytesOf(c);
            int[] off = constPool(b);
            int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
            int ifaceCount = ClassReader.u2(b, afterCp + 6);   // interfaces_count
            int base = afterCp + 8;
            int k = 0;
            while (k < ifaceCount)
            {
                int classIdx = ClassReader.u2(b, base + k * 2);
                if (utf8Is(b, ClassReader.classNameOff(b, off, classIdx), ifaceName))
                {
                    return true;
                }
                k += 1;
            }
            c = superNameOf(b, off, afterCp);
        }
        return false;
    }

    /** Whether the class providing {@code cls}'s impl of {@code mName+mDesc} is {@code wantOwner}. */
    static boolean findImplIs(byte[] cls, byte[] mName, byte[] mDesc, byte[] wantOwner)
    {
        byte[] c = cls;
        while (c != null && !isRoot(c))
        {
            byte[] b = bytesOf(c);
            int[] off = constPool(b);
            int afterCp = ClassReader.constantPool(b, off, new int[off.length]);
            byte[] code = Magic.bytes("Code");
            int p = ClassReader.methodsStart(b, afterCp);
            int n = ClassReader.u2(b, p);
            p += 2;
            int i = 0;
            while (i < n)
            {
                int access = ClassReader.u2(b, p);
                int nameOff = off[ClassReader.u2(b, p + 2)];
                int descOff = off[ClassReader.u2(b, p + 4)];
                int attrs = p + 6;
                if ((access & ACC_STATIC) == 0 && utf8Is(b, nameOff, mName) && utf8Is(b, descOff, mDesc)
                        && methodHasCode(b, off, attrs, code))
                {
                    return bytesEq(c, wantOwner);   // first class up the chain with a real impl
                }
                p = ClassReader.skipAttributes(b, attrs);
                i += 1;
            }
            c = superNameOf(b, off, afterCp);
        }
        return false;
    }

    /** Whether the method whose attribute table starts at {@code attrs} carries a Code attribute. */
    private static boolean methodHasCode(byte[] b, int[] off, int attrs, byte[] code)
    {
        int n = ClassReader.u2(b, attrs);
        int q = attrs + 2;
        int i = 0;
        while (i < n)
        {
            if (utf8Is(b, off[ClassReader.u2(b, q)], code))
            {
                return true;
            }
            q += 6 + ClassReader.u4(b, q + 2);       // attr name(2) + length(4) + body
            i += 1;
        }
        return false;
    }

    /** {@code name}'s superclass name bytes, or {@code null} if it has none (a root). */
    private static byte[] superNameOf(byte[] b, int[] off, int afterCp)
    {
        int superOff = ClassReader.superClassNameOff(b, off, afterCp);
        return superOff == 0 ? null : utf8Bytes(b, superOff);
    }

    /** Copy the Utf8 entry at {@code b[off]} onto a fresh heap byte array. */
    private static byte[] utf8Bytes(byte[] b, int off)
    {
        int len = ClassReader.u2(b, off);
        byte[] out = new byte[len];
        int j = 0;
        while (j < len)
        {
            out[j] = (byte) ClassReader.u1(b, off + 2 + j);
            j += 1;
        }
        return out;
    }

    /** Whether two heap byte arrays are equal in length and content. */
    private static boolean bytesEq(byte[] a, byte[] c)
    {
        if (a.length != c.length)
        {
            return false;
        }
        int i = 0;
        while (i < a.length)
        {
            if (a[i] != c[i])
            {
                return false;
            }
            i += 1;
        }
        return true;
    }
}
