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
 * <p>Cached: each embedded class is copied off the {@link VM#classDir} table and its constant
 * pool parsed once, then memoised (the table is immutable for the image's life). Whole-image
 * queries hammer a handful of classes thousands of times — {@code bytesOf} used to re-copy the
 * entire {@code .class} and every query re-parsed the pool, which dominated the self-build's
 * runtime; the cache turns that back into one parse per class. The parsed vtable is memoised
 * on top. Results are identical to the stateless form (the bytes and parse are the same).
 */
final class MetalClassModel
{
    private MetalClassModel() {}

    private static final int ACC_STATIC = 0x0008;

    // ----- per-class cache: bytes + parsed constant pool, keyed by name (classDir is immutable) -----
    private static final int CACHE_MAX = 512;
    private static final byte[][] cName = new byte[CACHE_MAX][];   // class name bytes
    private static final byte[][] cBytes = new byte[CACHE_MAX][];  // its .class bytes (copied once)
    private static final int[][] cOff = new int[CACHE_MAX][];      // its parsed constant-pool offset table
    private static final int[] cAfterCp = new int[CACHE_MAX];      // byte offset just past the constant pool
    private static int cCount;

    /** Cache slot for {@code name} — loading + parsing it once, or {@code -1} if not embedded (a root). */
    private static int classSlot(byte[] name)
    {
        int i = 0;
        while (i < cCount)
        {
            if (bytesEq(cName[i], name))
            {
                return i;
            }
            i += 1;
        }
        byte[] b = loadBytes(name);
        if (b == null)
        {
            return -1;
        }
        int[] off = new int[ClassReader.cpCount(b)];
        cAfterCp[cCount] = ClassReader.constantPool(b, off, new int[off.length]);
        cName[cCount] = name;
        cBytes[cCount] = b;
        cOff[cCount] = off;
        return cCount++;
    }

    /** Class {@code name}'s bytes copied off the {@link VM#classDir} table, or {@code null} (a root). */
    private static byte[] loadBytes(byte[] name)
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

    /** Class {@code name}'s bytes (cached), or {@code null} if not embedded (a root). */
    static byte[] bytesOf(byte[] name)
    {
        int s = classSlot(name);
        return s < 0 ? null : cBytes[s];
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
        int s = classSlot(name);
        byte[] b = cBytes[s];
        int[] off = cOff[s];
        int superOff = ClassReader.superClassNameOff(b, off, cAfterCp[s]);
        return superOff != 0 && utf8Is(b, superOff, wantSuper);
    }

    /** Count of {@code name}'s own non-static (instance) fields — the object's field slots. */
    static int instanceFieldCount(byte[] name)
    {
        int s = classSlot(name);
        byte[] b = cBytes[s];
        int afterCp = cAfterCp[s];
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
        int s = classSlot(clsName);
        byte[] b = cBytes[s];
        int[] off = cOff[s];
        int p = ClassReader.fieldsStart(b, cAfterCp[s]);
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
    static byte[] superName(byte[] clsName)
    {
        int s = classSlot(clsName);
        return superNameOf(cBytes[s], cOff[s], cAfterCp[s]);
    }

    /** Whether {@code name} declares a static initializer {@code <clinit>()V} (needs eager init). */
    static boolean hasClinit(byte[] name)
    {
        int s = classSlot(name);
        byte[] b = cBytes[s];
        int[] off = cOff[s];
        int afterCp = cAfterCp[s];
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

    private static final int MAX_SLOTS = 128;   // > the largest flattened vtable (Baseline ~60 incl. private)
    private static byte[][] vName;    // flattened-vtable slot: method name bytes (points at the memo below)
    private static byte[][] vDesc;    // ... descriptor bytes
    private static byte[][] vOwner;   // ... owning class name bytes (most-derived declarer)
    private static int vCount;
    private static final byte[][][] mvName = new byte[CACHE_MAX][][];   // memoised flattened vtable per class slot
    private static final byte[][][] mvDesc = new byte[CACHE_MAX][][];
    private static final byte[][][] mvOwner = new byte[CACHE_MAX][][];
    private static final int[] mvCount = new int[CACHE_MAX];
    private static final boolean[] mvBuilt = new boolean[CACHE_MAX];

    /** Number of slots in {@code name}'s flattened vtable. */
    static int vtableSize(byte[] name)
    {
        buildVtable(name);
        return vCount;
    }

    /** The method name / descriptor at flattened-vtable {@code slot} — valid right after
     *  {@link #vtableSize} for the same class (reads the shared build scratch). */
    static byte[] vtableSlotName(int slot)
    {
        return vName[slot];
    }
    static byte[] vtableSlotDesc(int slot)
    {
        return vDesc[slot];
    }
    static byte[] vtableSlotOwner(int slot)
    {
        return vOwner[slot];
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

    /** Point the vtable scratch at {@code name}'s flattened vtable, building + memoising it once. */
    private static void buildVtable(byte[] name)
    {
        int s = classSlot(name);
        if (mvBuilt[s])
        {
            vName = mvName[s];             // reuse the memoised arrays (immutable class bytes)
            vDesc = mvDesc[s];
            vOwner = mvOwner[s];
            vCount = mvCount[s];
            return;
        }
        vName = new byte[MAX_SLOTS][];
        vDesc = new byte[MAX_SLOTS][];
        vOwner = new byte[MAX_SLOTS][];
        vCount = 0;
        fillVtable(name);
        mvName[s] = vName;
        mvDesc[s] = vDesc;
        mvOwner[s] = vOwner;
        mvCount[s] = vCount;
        mvBuilt[s] = true;
    }

    private static void fillVtable(byte[] name)
    {
        int s = classSlot(name);
        byte[] b = cBytes[s];
        int[] off = cOff[s];
        int afterCp = cAfterCp[s];
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

    /** The interface method name / descriptor at {@code slot} (declaration order), for itable build. */
    static byte[] interfaceMethodNameAt(byte[] iface, int slot)
    {
        return interfaceMethodAt(iface, slot, true);
    }
    static byte[] interfaceMethodDescAt(byte[] iface, int slot)
    {
        return interfaceMethodAt(iface, slot, false);
    }
    private static byte[] interfaceMethodAt(byte[] iface, int slot, boolean wantName)
    {
        int cs = classSlot(iface);
        byte[] b = cBytes[cs];
        int[] off = cOff[cs];
        int afterCp = cAfterCp[cs];
        byte[] init = Magic.bytes("<init>");
        byte[] clinit = Magic.bytes("<clinit>");
        int p = ClassReader.methodsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int s = 0;
        int i = 0;
        while (i < n)
        {
            int access = ClassReader.u2(b, p);
            int nameOff = off[ClassReader.u2(b, p + 2)];
            int descOff = off[ClassReader.u2(b, p + 4)];
            if ((access & ACC_STATIC) == 0 && !utf8Is(b, nameOff, init) && !utf8Is(b, nameOff, clinit))
            {
                if (s == slot)
                {
                    return utf8Bytes(b, wantName ? nameOff : descOff);
                }
                s += 1;
            }
            p = ClassReader.skipAttributes(b, p + 6);
            i += 1;
        }
        return null;
    }

    /**
     * Slot index of interface method {@code mName+mDesc} in {@code iface} (declaration order),
     * or, when {@code mName} is {@code null}, the interface's method count.
     */
    static int interfaceMethodSlot(byte[] iface, byte[] mName, byte[] mDesc)
    {
        int s = classSlot(iface);
        byte[] b = cBytes[s];
        int[] off = cOff[s];
        int afterCp = cAfterCp[s];
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
            int s = classSlot(c);
            byte[] b = cBytes[s];
            int[] off = cOff[s];
            int afterCp = cAfterCp[s];
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
            int s = classSlot(c);
            byte[] b = cBytes[s];
            int[] off = cOff[s];
            int afterCp = cAfterCp[s];
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
