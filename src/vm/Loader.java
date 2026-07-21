package vm;

import asm.A64Enc;
import classfile.ClassReader;
import compiler.Baseline;
import compiler.Intrinsics;
import magic.Magic;

/**
 * A minimal classfile loader that runs <em>on the metal</em> (compiled into the
 * image by our own baseline compiler): it parses raw {@code .class} bytes it has
 * never seen, compiles a method's bytecode to A64 into a heap buffer, publishes
 * it, and executes it — M4, the metacircular goal (PLAN.md §4).
 *
 * <p>Deliberately tiny and JDK-free (no collections/strings — just primitive
 * arrays and {@code Magic} byte access), so the baseline compiler can compile it.
 * Shared parse state lives in statics because the baseline compiler keeps locals
 * in registers (≤10 slots per method). It parses just enough of JVMS §4 to find a
 * static method's Code and compiles only the bytecodes a {@code return &lt;const&gt;}
 * method uses; full parser/compiler self-hosting (M5) is a much larger effort.
 */
public final class Loader
{
    private Loader() {}

    private static long gbase;      // class blob base address
    private static long gp;         // parse cursor
    private static int[] gcp;       // byte offset of each constant-pool entry body
    private static byte[] gbytes;   // heap copy of the current class, for the shared ClassReader
    private static int[] gcpTag;    // tag of each entry (7 = Class — used for dependencies)
    private static int gcpCount;
    private static int gcodeLen;    // length of the located method's bytecode
    private static int gMaxLocals;  // ... and its max_locals (frame sizing)
    private static int gFoundDescOff;  // descriptor Utf8 offset of the last findMethod hit
    private static int gFoundStatic;   // 1 if that method is static
    private static int gFrameSize;     // frame size of the last method the core compiled
    private static long gnameP, gdescP;   // packed name/descriptor being searched for
    private static int gnameLen;
    private static int gdescLen;
    private static long gStatics;   // this class's statics block
    private static int[] gsfName;   // Utf8 offset of each static field's name (index = slot)
    private static int gsfCount;
    private static int[] gifName;   // Utf8 offset of each instance field's name (index = slot)
    private static int gifCount;
    private static int gThisNameOff;     // Utf8 offset of this class's own name
    private static long gMethodsStart;   // address of the methods_count (for callee lookup)
    // Flattened vtable of the class being built: superclass slots first, overrides
    // replacing in place, new methods appended. Each slot's signature may live in a
    // superclass's blob (gvBase), and its implementation is either an inherited
    // compiled buffer (gvImplBuf) or one of this class's own methods (gvImplCode).
    private static final int MAXMV = 64;
    private static long[] gvBase;   // blob holding this slot's name/descriptor
    private static int[] gvName;    // method name Utf8 offset (in gvBase)
    private static int[] gvDesc;    // descriptor Utf8 offset (in gvBase)
    private static long[] gvImplBuf;   // inherited impl buffer (0 => this class's own)
    private static long[] gvImplCode;  // this class's own method bytecode (0 => inherited)
    private static int gvCount;     // flattened vtable size
    private static long gTib;       // this class's TIB { Type, vtable... }, built before emit
    private static long gType;      // this class's Type { superType } — a metal instanceof chain node
    private static int gSuperNameOff;  // superclass name Utf8 offset (unloaded/Object => no inherit)
    private static int[] gImplIfName;  // name Utf8 offsets of the interfaces this class implements
    private static int gImplIfCount;

    // Global method registry across all loaded classes, so a call in one class can
    // link to a method compiled in another. Each entry captures where its class /
    // name / descriptor Utf8 bytes live (all in that class's blob) plus its buffer.
    private static final int MAXREG = 128;
    private static long[] rgBase;   // declaring class blob base (holds its Utf8 strings)
    private static int[] rgClassOff;   // class name Utf8 offset
    private static int[] rgNameOff;    // method name Utf8 offset
    private static int[] rgDescOff;    // descriptor Utf8 offset
    private static long[] rgBuf;    // compiled buffer address
    private static int rgCount;

    // Class registry: per loaded class, what another class needs to `new` it and
    // dispatch through it — its name (base+offset), TIB, and instance-field count.
    private static final int MAXCLASS = 32;
    private static long[] clBase;
    private static int[] clNameOff;
    private static long[] clTib;
    private static int[] clFieldCount;
    private static int[] clVtCount;      // flattened vtable size (so a subclass can copy it)
    private static long[] clType;        // each class's Type node (for instanceof/checkcast)
    private static int clCount;

    // Field registry: per instance field of each class, its class/name (base+offset)
    // and slot, so a cross-class get/putfield can find the offset.
    private static final int MAXFIELD = 128;
    private static long[] fldBase;
    private static int[] fldClassOff;
    private static int[] fldNameOff;
    private static int[] fldSlot;
    private static int fldCount;

    // Vtable-slot registry: per virtual method of each class, its class/name/desc
    // (base+offset) and vtable slot, so a cross-class invokevirtual can find the
    // slot in the receiver class's vtable (dispatch itself uses the object's TIB).
    private static final int MAXVT = 128;
    private static long[] vtClassBase;   // class the vtable belongs to (base + off)
    private static int[] vtClassOff;
    private static long[] vtNameBase;    // method signature blob (may be a superclass's)
    private static int[] vtNameOff;
    private static int[] vtDescOff;
    private static int[] vtSlot;
    private static long[] vtBuf;         // slot's implementation buffer
    private static int vtCount;

    // Interface-method registry. Every distinct interface method (name+descriptor)
    // gets a global index; each implementing class then carries an "imap" indexed by
    // it, holding that class's implementation. This is the itable: it decouples the
    // call site from where the method happens to sit in a given class's vtable, so
    // two classes implementing the same interface at different vtable slots both
    // dispatch correctly. Interfaces are loaded before their implementors.
    private static final int MAXIFM = 32;
    private static long[] ifBase;        // interface blob holding the signature
    private static int[] ifNameOff;
    private static int[] ifDescOff;
    private static int ifCount;
    private static boolean gIsInterface; // is the class being loaded an interface?

    // Blobs handed to the loader, plus the dependencies between them, so load order
    // is derived rather than hand-maintained. A class must be loaded after every
    // class it names — its superclass and interfaces (needed for field layout,
    // vtable flattening and itable indices) but also anything it instantiates,
    // calls or type-tests (needed by the class/method/field registries).
    private static final int MAXBLOB = 16;
    private static long[] pdBase;        // blob address
    private static int[] pdLen;          // blob length
    private static int[] pdNameOff;      // its own this_class name Utf8 offset
    private static int[] pdDone;         // 1 once loaded
    private static int pdCount;
    private static final int MAXDEP = 256;
    private static int[] dpOwner;        // index into pd* of the blob that has this dependency
    private static int[] dpOff;          // dependency's name Utf8 offset (in pdBase[dpOwner])
    private static int dpCount;

    private static int u1(long p)
    {
        return Magic.load8(p) & 0xFF;
    }
    private static int u2(long p)
    {
        return (u1(p) << 8) | u1(p + 1);
    }
    private static int u4(long p)
    {
        return (u2(p) << 16) | u2(p + 2);
    }

    private static void seek(long nameP, int nameLen, long descP, int descLen)
    {
        gnameP = nameP;
        gnameLen = nameLen;
        gdescP = descP;
        gdescLen = descLen;
    }

    /**
     * Run the class's static initializer if it has one. Called after
     * {@link #parseFields} (so the statics block exists) and before the entry
     * method, so {@code <clinit>}'s {@code putstatic}s land before first use. It is
     * just another method the loader compiles and runs; it dirties the seek key, so
     * the caller sets the entry seek afterwards.
     */
    private static void runClinit(long bytes)
    {
        seek(0x3C636C696E69743EL, 8, 0x282956L, 3);    // "<clinit>" "()V"
        long code = findMethod(bytes);
        if (code != 0L)
        {
            long unused = Magic.call0(compile(code, gcodeLen, gFoundDescOff, gFoundStatic));
        }
    }

    /** Compile+run a two-int-arg static method matching the seek key, with args {@code a,b}. */
    private static long load2(long bytes, int len, long a, long b)
    {
        parseConstPool(bytes, len);
        parseFields();
        long code = findMethod(bytes);
        if (code == 0L)
        {
            return 0L;
        }
        long buf = compile(code, gcodeLen, gFoundDescOff, gFoundStatic);   // two int args
        return Magic.call2(buf, a, b);
    }

    /**
     * Load a small class hierarchy on the metal and run it. Animal is loaded first,
     * then Dog (which inherits Animal's fields and flattened vtable and overrides a
     * method), then Guest (which {@code new}s a Dog and dispatches through an
     * Animal-typed reference). Returns 42 = '*'.
     */
    static int loadGuest()
    {
        rgBase = new long[MAXREG];
        rgClassOff = new int[MAXREG];
        rgNameOff = new int[MAXREG];
        rgDescOff = new int[MAXREG];
        rgBuf = new long[MAXREG];
        rgCount = 0;
        clBase = new long[MAXCLASS];
        clNameOff = new int[MAXCLASS];
        clTib = new long[MAXCLASS];
        clFieldCount = new int[MAXCLASS];
        clVtCount = new int[MAXCLASS];
        clType = new long[MAXCLASS];
        clCount = 0;
        fldBase = new long[MAXFIELD];
        fldClassOff = new int[MAXFIELD];
        fldNameOff = new int[MAXFIELD];
        fldSlot = new int[MAXFIELD];
        fldCount = 0;
        vtClassBase = new long[MAXVT];
        vtClassOff = new int[MAXVT];
        vtNameBase = new long[MAXVT];
        vtNameOff = new int[MAXVT];
        vtDescOff = new int[MAXVT];
        vtSlot = new int[MAXVT];
        vtBuf = new long[MAXVT];
        vtCount = 0;
        gvBase = new long[MAXMV];
        gvName = new int[MAXMV];
        gvDesc = new int[MAXMV];
        gvImplBuf = new long[MAXMV];
        gvImplCode = new long[MAXMV];
        ifBase = new long[MAXIFM];
        ifNameOff = new int[MAXIFM];
        ifDescOff = new int[MAXIFM];
        ifCount = 0;
        pdBase = new long[MAXBLOB];
        pdNameOff = new int[MAXBLOB];
        pdDone = new int[MAXBLOB];
        pdCount = 0;
        dpOwner = new int[MAXDEP];
        dpOff = new int[MAXDEP];
        // Handed over deliberately worst-first — Guest depends on all three, and the
        // implementors depend on the interface. loadAll derives the real order.
        pdLen = new int[MAXBLOB];
        addBlob(VM.guestBytes, (int) VM.guestLen);
        addBlob(VM.betaBytes, (int) VM.betaLen);
        addBlob(VM.alphaBytes, (int) VM.alphaLen);
        addBlob(VM.greeterBytes, (int) VM.greeterLen);
        addBlob(VM.myExcBytes, (int) VM.myExcLen);
        loadAll();
        seek(0x616e73776572L, 6, 0x282949L, 3);        // "answer" "()I"
        long code = findMethod(VM.guestBytes);
        if (code == 0L)
        {
            return 0;
        }
        return (int) Magic.call0(bufOf(code));
    }

    /** Load java.lang.Math from java.base and run Math.max(0x4D, 0x21) -> 'M'. */
    static int loadMath()
    {
        seek(0x6d6178L, 3, 0x2849492949L, 5);          // "max" "(II)I"
        return (int) load2(VM.mathBytes, (int) VM.mathLen, 0x4DL, 0x21L);
    }

    /**
     * Parse the constant pool with the <em>shared</em> {@link ClassReader} — the same
     * code the seed JVM runs, now compiled into the image by our own compiler (M5).
     * It reads a {@code byte[]}, so the embedded blob is copied onto the heap first;
     * its offsets are classfile-relative, so they still line up with the raw-address
     * access ({@code gbase + off}) the rest of the loader uses.
     */
    private static void parseConstPool(long base, int len)
    {
        gbase = base;
        gbytes = toBytes(base, len);
        gcpCount = ClassReader.cpCount(gbytes);
        gcp = new int[gcpCount];
        gcpTag = new int[gcpCount];
        gp = base + ClassReader.constantPool(gbytes, gcp, gcpTag);
    }

    /** Copy an embedded blob onto the heap so the shared reader can index it. */
    private static byte[] toBytes(long addr, int len)
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

    /** Parse the fields, assigning each static field a slot; allocate a zeroed statics block. */
    private static void parseFields()
    {
        long p = gp;
        gIsInterface = (u2(p) & 0x0200) != 0;             // ACC_INTERFACE
        gThisNameOff = gcp[u2(gbase + gcp[u2(p + 2)])];   // this_class -> Class -> name Utf8 offset
        gSuperNameOff = u2(p + 4) == 0 ? 0 : gcp[u2(gbase + gcp[u2(p + 4)])];   // super_class -> name
        int islot = superFieldCount();                  // own instance fields sit after inherited ones
        p += 6;                                         // access_flags, this_class, super_class
        gImplIfCount = u2(p);                           // interfaces this class implements
        gImplIfName = new int[gImplIfCount];
        int ii = 0;
        while (ii < gImplIfCount)
        {
            int ci = u2(p + 2 + ii * 2);                // Class entry index
            gImplIfName[ii] = gcp[u2(gbase + gcp[ci])]; // -> interface name Utf8 offset
            ii += 1;
        }
        p += 2 + gImplIfCount * 2;                      // interfaces
        int fcount = u2(p);
        p += 2;
        gsfName = new int[fcount + 1];
        gifName = new int[fcount + islot + 1];
        int slot = 0;
        int f = 0;
        while (f < fcount)
        {
            int access = u2(p);
            int nameIdx = u2(p + 2);
            p += 6;                                     // access, name, descriptor
            if ((access & 0x0008) != 0)
            {
                gsfName[slot] = gcp[nameIdx];    // ACC_STATIC
                slot += 1;
            }
            else
            {
                gifName[islot] = gcp[nameIdx];   // instance field at the next inherited+own slot
                islot += 1;
            }
            int attrs = u2(p);
            p += 2;
            p = skipAttributes(p, attrs);
            f += 1;
        }
        gsfCount = slot;
        gifCount = islot;                               // total: inherited + own
        gvCount = 0;                                    // no vtable unless parseVtable runs
        gMethodsStart = p;                              // methods_count follows the fields
        gStatics = Heap.alloc(slot * 8 + 8);
        int z = 0;
        while (z < slot)
        {
            Magic.store64(gStatics + z * 8, 0L);    // statics default to 0
            z += 1;
        }
        gp = p;
    }

    /**
     * Build this class's flattened vtable: inherit the superclass's slots (copied
     * from the super's registered vtable — signature + already-compiled impl
     * buffer), then walk this class's own virtual methods, an override replacing the
     * inherited slot in place (so it keeps the super's index) and a new method
     * appending. buildTib later fills the TIB from these slots.
     */
    private static void parseVtable(long bytes)
    {
        gvCount = 0;
        int superReg = classRegByName(gSuperNameOff);
        if (superReg >= 0)
        {
            inheritVtable(gSuperNameOff);               // copy the super's flattened slots
        }
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);                      // access, name, descriptor, attrs
            if (isVirtual(u2(p), gcp[u2(p + 2)]))
            {
                int slot = findVtSlot(gcp[u2(p + 2)], gcp[u2(p + 4)]);   // override an inherited slot?
                if (slot < 0)
                {
                    slot = gvCount;                     // else append a new slot
                    gvCount += 1;
                }
                gvBase[slot] = gbase;
                gvName[slot] = gcp[u2(p + 2)];
                gvDesc[slot] = gcp[u2(p + 4)];
                gvImplCode[slot] = findCode(bytes, p + 8, attrs);   // this class's own impl
                gvImplBuf[slot] = 0L;
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
    }

    /** Copy the registered flattened vtable of the class named {@code superOff} into gv[]. */
    private static void inheritVtable(int superOff)
    {
        int i = 0;
        while (i < vtCount)
        {
            if (utf8EqAt(gbase, superOff, vtClassBase[i], vtClassOff[i]))
            {
                int slot = vtSlot[i];
                gvBase[slot] = vtNameBase[i];
                gvName[slot] = vtNameOff[i];
                gvDesc[slot] = vtDescOff[i];
                gvImplBuf[slot] = vtBuf[i];             // inherited (already-compiled) impl
                gvImplCode[slot] = 0L;
                if (slot + 1 > gvCount)
                {
                    gvCount = slot + 1;
                }
            }
            i += 1;
        }
    }

    /** Flattened-vtable slot whose name+descriptor match, or -1 (used for override detection). */
    private static int findVtSlot(int nameOff, int descOff)
    {
        int s = 0;
        while (s < gvCount)
        {
            if (utf8EqAt(gbase, nameOff, gvBase[s], gvName[s])
                    && utf8EqAt(gbase, descOff, gvBase[s], gvDesc[s]))
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    /** Instance-field count of the superclass (inherited fields), 0 if super is Object/unloaded. */
    private static int superFieldCount()
    {
        int r = classRegByName(gSuperNameOff);
        return r >= 0 ? clFieldCount[r] : 0;
    }

    /** A method goes in the vtable if it is instance, non-private, and not a constructor. */
    private static boolean isVirtual(int access, int nameOff)
    {
        if ((access & 0x0008) != 0 || (access & 0x0002) != 0)
        {
            return false;                               // static or private
        }
        if (isName(gbase, nameOff, 0x3C696E69743EL, 6))
        {
            return false;                               // "<init>"
        }
        if (isName(gbase, nameOff, 0x3C636C696E69743EL, 8))
        {
            return false;                               // "<clinit>"
        }
        return true;
    }

    /** Absolute address of the static field referenced by constant-pool Fieldref {@code idx}. */
    static long staticAddr(int idx)
    {
        int nameOff = ClassReader.refNameOff(gbytes, gcp, idx);  // Fieldref -> name Utf8 offset
        int s = 0;
        while (s < gsfCount)
        {
            if (gsfName[s] == nameOff)
            {
                return gStatics + s * 8;
            }
            s += 1;
        }
        return gStatics;
    }

    /** From {@code gp} (at the methods), return the bytecode address of the sought method. */
    private static long findMethod(long base)
    {
        long p = gp;
        gMethodsStart = p;                              // remember for on-demand callee lookup
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            p += 2;                                     // access_flags
            int nameIdx = u2(p);
            int descIdx = u2(p + 2);
            p += 4;
            int attrs = u2(p);
            p += 2;
            if (isName(base, gcp[nameIdx], gnameP, gnameLen)
                    && isName(base, gcp[descIdx], gdescP, gdescLen))
            {
                long code = findCode(base, p, attrs);
                if (code != 0L)
                {
                    gFoundDescOff = gcp[descIdx];       // for the shared core's prologue
                    gFoundStatic = (access & 0x0008) != 0 ? 1 : 0;
                    return code;
                }
            }
            else
            {
                p = skipAttributes(p, attrs);
            }
            m += 1;
        }
        return 0L;
    }

    /** Find the Code attribute among {@code attrs} at {@code p}; return the bytecode address. */
    private static long findCode(long base, long p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p += 2;
            int alen = u4(p);
            p += 4;
            if (isName(base, gcp[anIdx], 0x436f6465L, 4))              // "Code"
            {
                gMaxLocals = u2(p + 2);                 // after max_stack(2)
                gcodeLen = u4(p + 4);
                return p + 8;
            }
            p += alen;
            a += 1;
        }
        return 0L;
    }

    private static long skipAttributes(long p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            p += 2;
            int alen = u4(p);
            p += 4 + alen;
            a += 1;
        }
        return p;
    }

    /** Compare the Utf8 at {@code off} against {@code expected} (bytes packed big-endian, {@code len} bytes). */
    private static boolean isName(long base, int off, long expected, int len)
    {
        if (off == 0)
        {
            return false;
        }
        if (u2(base + off) != len)
        {
            return false;
        }
        long got = 0L;
        int j = 0;
        while (j < len)
        {
            got = (got << 8) | u1(base + off + 2 + j);
            j += 1;
        }
        return got == expected;
    }

    // ----- on-metal JIT: drive the shared compiler/Baseline core over each method --
    // A method and its callees form a small program; we assign every reachable
    // method a buffer before emitting any, so invokestatic's BL targets are known
    // without compiling nested-and-reentrant (the shared static compile state and
    // the writer-side 10-local ceiling both make on-the-fly recursion awkward).
    private static final int MAXM = 64;
    private static long[] mCode;      // each reachable method's bytecode address
    private static int[] mLen;        // ... and its length
    private static long[] mBuf;       // ... and the buffer assigned to it
    private static int[] mLocals;     // ... its max_locals
    private static int[] mDescOff;    // ... its descriptor Utf8 offset (for the shared core's prologue)
    private static int[] mStatic;     // ... 1 if static
    private static int mCount;

    /** The on-metal symbol seam: resolves the shared Baseline core's references to addresses. */
    private static final MetalSymbols METAL_SYMBOLS = new MetalSymbols();
    private static long gExcSlot;   // one heap word holding the in-flight exception during athrow

    /** Address of the metal in-flight-exception slot (the writer's vm/VM.$exception). */
    static long exceptionSlotAddr()
    {
        if (gExcSlot == 0L)
        {
            gExcSlot = Heap.alloc(8);
        }
        return gExcSlot;
    }





    /**
     * Compile the entry method and every static method it transitively calls,
     * then return the entry's buffer. Three flat passes — discover (BFS the call
     * graph), place (size each method and hand it a buffer), emit (now every BL
     * target address is known) — so no method's compile nests inside another's.
     * Scope: same-class static callees, no recursion/cycles beyond dedup.
     */
    private static long compile(long code, int len, int descOff, int isStatic)
    {
        allocMethodTables();
        addMethod(code, len, gMaxLocals, descOff, isStatic);
        int i = 0;
        while (i < mCount)                              // discover
        {
            scanCallees(mCode[i], mLen[i]);
            i += 1;
        }
        i = 0;
        while (i < mCount)                              // place
        {
            sizeMethod(i);
            i += 1;
        }
        buildTib();                                     // vtable now that all buffers are placed
        i = 0;
        while (i < mCount)                              // emit
        {
            emitMethod(i);
            i += 1;
        }
        Magic.dsb();                                    // publish all buffers (caches are off)
        Magic.isb();
        return mBuf[0];
    }

    /**
     * Compile <em>every</em> method of the current class into its own buffer (in
     * this class's context), then publish. Used for cross-class loading: each class
     * is compiled whole so its methods can be registered and linked from others.
     * Cross-class calls in the body resolve through the global registry, so classes
     * a method depends on must be compiled+registered first (no cross-class cycles).
     */
    private static void compileClass(long bytes)
    {
        allocMethodTables();
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)                              // seed all of the class's methods
        {
            int attrs = u2(p + 6);
            long code = findCode(bytes, p + 8, attrs);
            if (code != 0L)
            {
                int isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                addMethod(code, gcodeLen, gMaxLocals, gcp[u2(p + 4)], isStatic);
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        int i = 0;
        while (i < mCount)                              // place
        {
            sizeMethod(i);
            i += 1;
        }
        buildTib();
        i = 0;
        while (i < mCount)                              // emit
        {
            emitMethod(i);
            i += 1;
        }
        Magic.dsb();
        Magic.isb();
    }

    /**
     * Load one class end to end: parse it, run its {@code <clinit>}, build its
     * flattened vtable against the (already-loaded) superclass, compile every
     * method, and register it. Classes must be loaded superclass/dependency-first
     * so the registries are populated when a subclass or user is compiled.
     */
    /** Hand the loader a class blob; {@link #loadAll} works out when to load it. */
    private static void addBlob(long bytes, int len)
    {
        pdBase[pdCount] = bytes;
        pdLen[pdCount] = len;
        pdDone[pdCount] = 0;
        pdCount += 1;
    }

    /**
     * Load every recorded blob, dependencies first. Repeatedly loads any blob whose
     * dependencies are all satisfied (already loaded, or not among the blobs at all
     * — {@code java/lang/Object} and other unloaded names never block). Stops if a
     * pass makes no progress, which means a cycle or a missing class.
     */
    private static void loadAll()
    {
        probeAll();
        int remaining = pdCount;
        while (remaining > 0)
        {
            int progress = 0;
            int i = 0;
            while (i < pdCount)
            {
                if (pdDone[i] == 0 && ready(i))
                {
                    loadOne(pdBase[i], pdLen[i]);
                    pdDone[i] = 1;
                    remaining -= 1;
                    progress = 1;
                }
                i += 1;
            }
            if (progress == 0)
            {
                remaining = 0;                          // cycle / missing dependency: give up
            }
        }
    }

    /** Record each blob's own name and every class it names (its {@code Class} entries). */
    private static void probeAll()
    {
        dpCount = 0;
        int i = 0;
        while (i < pdCount)
        {
            parseConstPool(pdBase[i], pdLen[i]);
            pdNameOff[i] = gcp[u2(gbase + gcp[u2(gp + 2)])];   // this_class -> name
            int c = 1;
            while (c < gcpCount)
            {
                if (gcpTag[c] == 7)                     // CONSTANT_Class
                {
                    addDep(i, gcp[u2(gbase + gcp[c])]);
                }
                c += 1;
            }
            i += 1;
        }
    }

    private static void addDep(int owner, int nameOff)
    {
        dpOwner[dpCount] = owner;
        dpOff[dpCount] = nameOff;
        dpCount += 1;
    }

    /** True if no dependency of blob {@code i} names a blob that is still unloaded. */
    private static boolean ready(int i)
    {
        int d = 0;
        while (d < dpCount)
        {
            if (dpOwner[d] == i && blocked(i, dpOff[d]))
            {
                return false;
            }
            d += 1;
        }
        return true;
    }

    /** True if some other still-unloaded blob declares the class named at {@code off}. */
    private static boolean blocked(int i, int off)
    {
        int j = 0;
        while (j < pdCount)
        {
            if (j != i && pdDone[j] == 0
                    && utf8EqAt(pdBase[i], off, pdBase[j], pdNameOff[j]))
            {
                return true;
            }
            j += 1;
        }
        return false;
    }

    private static void loadOne(long bytes, int len)
    {
        parseConstPool(bytes, len);
        parseFields();                                  // hierarchy-aware field layout
        if (gIsInterface)
        {
            registerInterface();                        // give its methods global itable indices
            // Give the interface a Type and register it, so implementors' itable
            // directories can key on it and the core's interfaceType resolves (M5.4.e).
            gType = Heap.alloc(24);
            Magic.store64(gType + 0, 0L);               // instanceSize (not instantiated)
            Magic.store64(gType + 8, 0L);               // superType
            Magic.store64(gType + 16, 0L);              // no itableDir
            clBase[clCount] = gbase;
            clNameOff[clCount] = gThisNameOff;
            clTib[clCount] = 0L;
            clType[clCount] = gType;
            clFieldCount[clCount] = 0;
            clVtCount[clCount] = 0;
            clCount += 1;
            return;                                     // nothing to compile: all methods abstract
        }
        runClinit(bytes);                               // gvCount==0 here (vtable not built yet)
        parseVtable(bytes);                             // flatten against the superclass
        compileClass(bytes);                            // compile all methods; buildTib fills TIB+imap
        registerAll();                                  // methods
        registerClass();                                // class + fields + flattened vtable
    }

    /** Record a method (deduped by bytecode address; dedup also breaks cycles). */
    private static void addMethod(long code, int len, int maxLocals, int descOff, int isStatic)
    {
        int i = 0;
        while (i < mCount)
        {
            if (mCode[i] == code)
            {
                return;
            }
            i += 1;
        }
        mCode[mCount] = code;
        mLen[mCount] = len;
        mLocals[mCount] = maxLocals;
        mDescOff[mCount] = descOff;
        mStatic[mCount] = isStatic;
        mCount += 1;
    }

    /** Allocate the per-batch method tables (discover/place/emit work set). */
    private static void allocMethodTables()
    {
        mCode = new long[MAXM];
        mLen = new int[MAXM];
        mBuf = new long[MAXM];
        mLocals = new int[MAXM];
        mDescOff = new int[MAXM];
        mStatic = new int[MAXM];
        mCount = 0;
    }

    /** Copy method {@code i}'s bytecode out of the current class blob into a heap byte[]. */
    private static byte[] extractCode(int i)
    {
        int off = (int) (mCode[i] - gbase);
        int len = mLen[i];
        byte[] c = new byte[len];
        int k = 0;
        while (k < len)
        {
            c[k] = gbytes[off + k];
            k += 1;
        }
        return c;
    }

    /** Add every same-class static method {@code code} calls to the work set. */
    private static void scanCallees(long code, int len)
    {
        int pc = 0;
        while (pc < len)
        {
            int op = u1(code + pc);
            if (op == 0xb8 || op == 0xb7)                   // invokestatic / invokespecial
            {
                int idx = u2(code + pc + 1);
                long c = calleeCodeOf(idx);                 // sets gcodeLen / gMaxLocals
                if (c != 0L)
                {
                    addMethod(c, gcodeLen, gMaxLocals, mrefDescOff(idx), op == 0xb8 ? 1 : 0);
                }
            }
            pc += opLen(op);
        }
    }

    /** Compile method {@code i} with the shared core (returns its A64 at {@code base}). */
    private static int[] compileMethod(int i, long base)
    {
        Baseline b = new Baseline(gbytes, gcp, gcpTag, METAL_SYMBOLS);
        // The exception_table follows the bytecode: u2 count, then {start,end,handler,
        // catch} u2s. catch=0 is a catch-all; else it's a Class cp index (as the writer's).
        long ex = mCode[i] + mLen[i];
        int n = u2(ex);
        int[] es = new int[n];
        int[] ee = new int[n];
        int[] eh = new int[n];
        int[] ec = new int[n];
        int k = 0;
        while (k < n)
        {
            long e = ex + 2 + k * 8;
            es[k] = u2(e);
            ee[k] = u2(e + 2);
            eh[k] = u2(e + 4);
            ec[k] = u2(e + 6);
            k += 1;
        }
        b.setExceptionTable(es, ee, eh, ec, n);
        int[] words = b.compileBody(extractCode(i), mDescOff[i], mStatic[i] != 0, mLocals[i], base, false);
        gFrameSize = b.frameSize();
        return words;
    }

    /**
     * Size method {@code i} and allocate its buffer. The shared core emits fixed-width
     * address loads, so a method's word count is placement-independent — compiling at a
     * dummy base gives the exact size to reserve before the real emit pass.
     */
    private static void sizeMethod(int i)
    {
        mBuf[i] = Heap.alloc(compileMethod(i, 0L).length * 4);
    }

    /** Emit method {@code i}'s A64 (from the shared core) into its assigned buffer. */
    private static void emitMethod(int i)
    {
        int[] words = compileMethod(i, mBuf[i]);        // real base -> resolved addresses
        long out = mBuf[i];
        int k = 0;
        while (k < words.length)
        {
            Magic.store32(out, words[k]);
            out += 4;
            k += 1;
        }
        if (gFrameSize > 0)                             // let VM.unwind pop this JIT'd frame
        {
            VM.addJitFrame(mBuf[i], out, gFrameSize);
        }
    }

    /** Buffer assigned to the method whose bytecode is at {@code code}. */
    private static long bufOf(long code)
    {
        int i = 0;
        while (i < mCount)
        {
            if (mCode[i] == code)
            {
                return mBuf[i];
            }
            i += 1;
        }
        return 0L;                                       // not one of this class's compiled methods
    }

    // ----- cross-class linking (global method registry) --------------------
    /** Register a compiled method so other classes can link to it by class+name+descriptor. */
    private static void register(long base, int classOff, int nameOff, int descOff, long buf)
    {
        rgBase[rgCount] = base;
        rgClassOff[rgCount] = classOff;
        rgNameOff[rgCount] = nameOff;
        rgDescOff[rgCount] = descOff;
        rgBuf[rgCount] = buf;
        rgCount += 1;
    }

    /** Register every compiled method of the current class (walk its methods table). */
    private static void registerAll()
    {
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            long code = findCode(gbase, p + 8, attrs);
            if (code != 0L)
            {
                long buf = bufOf(code);
                if (buf != 0L)
                {
                    register(gbase, gThisNameOff, gcp[u2(p + 2)], gcp[u2(p + 4)], buf);
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
    }

    /** Buffer of the method named by Methodref {@code idx} in another loaded class (registry lookup). */
    private static long globalBuf(int idx)
    {
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        int i = 0;
        while (i < rgCount)
        {
            if (utf8EqAt(gbase, classOff, rgBase[i], rgClassOff[i])
                    && utf8EqAt(gbase, nameOff, rgBase[i], rgNameOff[i])
                    && utf8EqAt(gbase, descOff, rgBase[i], rgDescOff[i]))
            {
                return rgBuf[i];
            }
            i += 1;
        }
        return 0L;
    }

    /** Buffer to BL for a static/special call: this class's own method, else the registry. */
    static long resolveCallBuf(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            return bufOf(calleeCodeOf(idx));            // same class: local buffer
        }
        return globalBuf(idx);                          // cross class: another loaded class
    }

    /** Register the current class (its TIB + instance-field layout) for cross-class new/fields. */
    private static void registerClass()
    {
        clBase[clCount] = gbase;
        clNameOff[clCount] = gThisNameOff;
        clTib[clCount] = gTib;
        clType[clCount] = gType;
        clFieldCount[clCount] = gifCount;
        clVtCount[clCount] = gvCount;
        clCount += 1;
        int s = 0;
        while (s < gifCount)
        {
            if (gifName[s] != 0)                        // skip inherited slots (registered by the super)
            {
                fldBase[fldCount] = gbase;
                fldClassOff[fldCount] = gThisNameOff;
                fldNameOff[fldCount] = gifName[s];
                fldSlot[fldCount] = s;
                fldCount += 1;
            }
            s += 1;
        }
        int v = 0;
        while (v < gvCount)                            // register the whole flattened vtable
        {
            vtClassBase[vtCount] = gbase;
            vtClassOff[vtCount] = gThisNameOff;
            vtNameBase[vtCount] = gvBase[v];           // signature blob (a super's, for inherited slots)
            vtNameOff[vtCount] = gvName[v];
            vtDescOff[vtCount] = gvDesc[v];
            vtSlot[vtCount] = v;
            vtBuf[vtCount] = slotBuf(v);
            vtCount += 1;
            v += 1;
        }
    }

    /**
     * Give every method of the interface being loaded a global interface-method
     * index (deduped by name+descriptor). Implementors later build an imap indexed
     * by it, and {@code invokeinterface} resolves a call site to the same index.
     */
    private static void registerInterface()
    {
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (isVirtual(u2(p), gcp[u2(p + 2)])
                    && ifIndexOf(gbase, gcp[u2(p + 2)], gcp[u2(p + 4)]) < 0)
            {
                ifBase[ifCount] = gbase;
                ifNameOff[ifCount] = gcp[u2(p + 2)];
                ifDescOff[ifCount] = gcp[u2(p + 4)];
                ifCount += 1;
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
    }

    /** Global interface-method index for an InterfaceMethodref call site (0 if unknown). */
    static int ifSlotOf(int idx)
    {
        int g = ifIndexOf(gbase, mrefNameOff(idx), mrefDescOff(idx));
        return g >= 0 ? g : 0;
    }

    /** Global interface-method index for a name+descriptor in {@code base}, or -1. */
    private static int ifIndexOf(long base, int nameOff, int descOff)
    {
        int i = 0;
        while (i < ifCount)
        {
            if (utf8EqAt(base, nameOff, ifBase[i], ifNameOff[i])
                    && utf8EqAt(base, descOff, ifBase[i], ifDescOff[i]))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * Build this class's imap: for each known interface method, the buffer of this
     * class's implementation (matched against the flattened vtable by name+
     * descriptor), or 0 if it does not implement it. Fixed size so an interface
     * loaded later cannot leave an earlier class's imap short.
     */
    private static long buildImap()
    {
        long imap = Heap.alloc(MAXIFM * 8);
        int g = 0;
        while (g < MAXIFM)
        {
            long buf = 0L;
            if (g < ifCount)
            {
                int s = findVtSlotAt(ifBase[g], ifNameOff[g], ifDescOff[g]);
                if (s >= 0)
                {
                    buf = slotBuf(s);
                }
            }
            Magic.store64(imap + g * 8, buf);
            g += 1;
        }
        return imap;
    }

    /** Like {@link #findVtSlot} but for a name+descriptor living in another blob. */
    private static int findVtSlotAt(long base, int nameOff, int descOff)
    {
        int s = 0;
        while (s < gvCount)
        {
            if (utf8EqAt(base, nameOff, gvBase[s], gvName[s])
                    && utf8EqAt(base, descOff, gvBase[s], gvDesc[s]))
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    /** Class-registry index of the class whose name Utf8 is at {@code nameOff} in gbase, or -1. */
    private static int classRegByName(int nameOff)
    {
        if (nameOff == 0)
        {
            return -1;
        }
        int i = 0;
        while (i < clCount)
        {
            if (utf8EqAt(gbase, nameOff, clBase[i], clNameOff[i]))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * Vtable slot of a cross-class virtual/interface method. For invokevirtual the
     * ref's class is the (loaded) declaring class, so a class+name+descriptor match
     * finds the slot. For invokeinterface the ref's class is the interface (not a
     * loaded class), so that match fails and we fall back to name+descriptor — sound
     * while a single loaded class implements it.
     */
    private static int globalVtableSlot(int idx)
    {
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        int i = 0;
        while (i < vtCount)                             // class-qualified (invokevirtual)
        {
            if (utf8EqAt(gbase, classOff, vtClassBase[i], vtClassOff[i])
                    && utf8EqAt(gbase, nameOff, vtNameBase[i], vtNameOff[i])
                    && utf8EqAt(gbase, descOff, vtNameBase[i], vtDescOff[i]))
            {
                return vtSlot[i];
            }
            i += 1;
        }
        i = 0;
        while (i < vtCount)                             // name+descriptor (invokeinterface / inherited)
        {
            if (utf8EqAt(gbase, nameOff, vtNameBase[i], vtNameOff[i])
                    && utf8EqAt(gbase, descOff, vtNameBase[i], vtDescOff[i]))
            {
                return vtSlot[i];
            }
            i += 1;
        }
        return 0;
    }

    /** Class-registry index of the class named by a {@code new}/type {@code Class} entry, or -1. */
    private static int classRegOf(int classIdx)
    {
        int nameOff = gcp[u2(gbase + gcp[classIdx])];   // Class entry -> name Utf8 offset
        int i = 0;
        while (i < clCount)
        {
            if (utf8EqAt(gbase, nameOff, clBase[i], clNameOff[i]))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** True if the class of {@code *ref} {@code idx} is a loaded (registered) class. */
    private static boolean refClassRegistered(int idx)
    {
        int classOff = refClassNameOff(idx);
        int i = 0;
        while (i < clCount)
        {
            if (utf8EqAt(gbase, classOff, clBase[i], clNameOff[i]))
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** invokespecial is a real call (not an Object.&lt;init&gt; pop) if its class is loaded. */
    static boolean isRealSpecial(int idx)
    {
        return utf8Eq(refClassNameOff(idx), gThisNameOff) || refClassRegistered(idx);
    }

    /**
     * Instance-field offset for a Fieldref in another class (or an inherited field
     * named through a subclass). A class-qualified match wins; failing that (the ref
     * names a subclass for a field its superclass declares), a name-only match finds
     * the inherited field's slot — the flattened layout keeps it consistent.
     */
    private static int globalFieldOffset(int idx)
    {
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int i = 0;
        while (i < fldCount)                            // class-qualified
        {
            if (utf8EqAt(gbase, classOff, fldBase[i], fldClassOff[i])
                    && utf8EqAt(gbase, nameOff, fldBase[i], fldNameOff[i]))
            {
                return 16 + fldSlot[i] * 8;
            }
            i += 1;
        }
        i = 0;
        while (i < fldCount)                            // name-only (inherited field via subclass)
        {
            if (utf8EqAt(gbase, nameOff, fldBase[i], fldNameOff[i]))
            {
                return 16 + fldSlot[i] * 8;
            }
            i += 1;
        }
        return 16;
    }

    /**
     * Build this class's Type (a one-word node holding its superclass's Type, so
     * {@code instanceof} can walk the chain) and its TIB: slot 0 is the Type, then
     * one vtable entry per flattened slot. {@code new} stores the TIB into each
     * instance's header, so an object reaches both its vtable and its Type.
     */
    private static void buildTib()
    {
        int sr = classRegByName(gSuperNameOff);
        // ObjectModel layout: Type = { instanceSize@0, superType@8, itableDir@16 } (24
        // bytes), so VM.instanceOf and the shared Baseline core read JIT'd objects the
        // same way they read image objects (M5.4.e). The itableDir slot currently holds
        // the flat imap; step 2 replaces it with a proper itable directory.
        gType = Heap.alloc(24);
        Magic.store64(gType + 0, 16 + gifCount * 8);       // TYPE_INSTANCE_SIZE_OFFSET
        Magic.store64(gType + 8, sr >= 0 ? clType[sr] : 0L);   // TYPE_SUPER_OFFSET (0 at Object)
        gTib = Heap.alloc((1 + gvCount) * 8);
        Magic.store64(gTib, gType);                      // TIB[0] = Type
        int s = 0;
        while (s < gvCount)
        {
            Magic.store64(gTib + 8 + s * 8, slotBuf(s));   // TIB[1+slot] = impl code
            s += 1;
        }
        Magic.store64(gType + 16, buildItableDir(buildImap()));   // TYPE_ITABLE_DIR_OFFSET
    }

    /**
     * itable directory (ObjectModel): one {@code {interfaceType, itable}} entry per
     * implemented interface. Every entry shares this class's flat imap as its itable —
     * the metal indexes the imap by the interface method's <em>global</em> slot
     * ({@link #ifSlotOf}), so one table serves all interfaces. The directory adds the
     * interfaceType-keyed lookup the shared core's {@code invokeinterface} searches for.
     */
    private static long buildItableDir(long imap)
    {
        if (gImplIfCount == 0)
        {
            return 0L;
        }
        long dir = Heap.alloc((gImplIfCount + 1) * 16);  // {interfaceType@0, itable@8} + sentinel
        int k = 0;
        while (k < gImplIfCount)
        {
            int r = classRegByName(gImplIfName[k]);
            Magic.store64(dir + k * 16 + 0, r >= 0 ? clType[r] : 0L);   // interfaceType
            Magic.store64(dir + k * 16 + 8, imap);                      // itable (the shared imap)
            k += 1;
        }
        Magic.store64(dir + k * 16 + 0, 0L);             // sentinel: interfaceType 0 ends the directory
        Magic.store64(dir + k * 16 + 8, 0L);
        return dir;
    }

    /** Compiled buffer for flattened slot {@code s}: inherited (pre-resolved) or this class's own. */
    private static long slotBuf(int s)
    {
        if (gvImplBuf[s] != 0L)
        {
            return gvImplBuf[s];                         // inherited from a superclass
        }
        return bufOf(gvImplCode[s]);                     // this class's own method
    }

    /**
     * Vtable slot of the virtual method named by Methodref {@code idx}. Same-class
     * calls use this class's own vtable; a call whose ref names another class (a
     * cross-class {@code invokevirtual}) or an interface resolves via the global
     * vtable registry against the receiver class's layout.
     */
    static int vtableSlotOf(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            int s = findVtSlot(mrefNameOff(idx), mrefDescOff(idx));   // this class's flattened vtable
            if (s >= 0)
            {
                return s;
            }
        }
        return globalVtableSlot(idx);
    }





    /** Instance-field byte offset for the field named by {@code *ref} index. */
    static int fieldOffsetOf(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            int nameOff = mrefNameOff(idx);                // Fieldref layout == Methodref layout
            int s = 0;
            while (s < gifCount)
            {
                if (gifName[s] == nameOff)
                {
                    return 16 + s * 8;                     // this class's own field (ObjectModel: +16)
                }
                s += 1;
            }
        }
        return globalFieldOffset(idx);                     // another class, or an inherited field
    }

    /** Resolve Methodref {@code idx} to its (same-class) method's bytecode; set {@code gcodeLen}. */
    private static long calleeCodeOf(int idx)
    {
        if (!utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            return 0L;                                  // not this class (Object.<init>, JDK, ...)
        }
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);                      // access, name, descriptor, attrs
            if (utf8Eq(gcp[u2(p + 2)], nameOff) && utf8Eq(gcp[u2(p + 4)], descOff))
            {
                long c = findCode(gbase, p + 8, attrs);
                if (c != 0L)
                {
                    return c;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return 0L;
    }

    /** Name Utf8 offset of Methodref {@code idx}. */
    private static int mrefNameOff(int idx)
    {
        return ClassReader.refNameOff(gbytes, gcp, idx);
    }

    /** Class-name Utf8 offset of a {@code *ref} constant (Fieldref/Methodref layout). */
    private static int refClassNameOff(int idx)
    {
        return ClassReader.refClassNameOff(gbytes, gcp, idx);
    }

    /** Descriptor Utf8 offset of Methodref {@code idx}. */
    private static int mrefDescOff(int idx)
    {
        return ClassReader.refDescOff(gbytes, gcp, idx);
    }


    /** Compare two Utf8 entries in the current class by length + bytes. */
    private static boolean utf8Eq(int offA, int offB)
    {
        return utf8EqAt(gbase, offA, gbase, offB);
    }

    /** Compare a Utf8 entry in {@code baseA} against one in {@code baseB} (may be different classes). */
    private static boolean utf8EqAt(long baseA, int offA, long baseB, int offB)
    {
        if (baseA == baseB && offA == offB)
        {
            return true;
        }
        int la = u2(baseA + offA);
        if (la != u2(baseB + offB))
        {
            return false;
        }
        int j = 0;
        while (j < la)
        {
            if (u1(baseA + offA + 2 + j) != u1(baseB + offB + 2 + j))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** STR Xt, [Xn, #off] — off a multiple of 8 (rn=31 = SP, rt=31 = xzr). */
    private static int strx(int rt, int rn, int off)
    {
        return A64Enc.strx(rt, rn, off);
    }
    /** LDR Xt, [Xn, #off] — off a multiple of 8 (rn=31 = SP). */
    private static int ldrx(int rt, int rn, int off)
    {
        return A64Enc.ldrx(rt, rn, off);
    }



    /** Type node of the class named by {@code Class} entry {@code classIdx}, or 0 if unloaded. */
    static long typeOfClass(int classIdx)
    {
        int r = classRegOf(classIdx);
        return r >= 0 ? clType[r] : 0L;
    }

    // ----- resolvers the on-metal MetalSymbols shares with emit* (M5.4.c) -----

    /** TIB of the class at {@code Class} entry {@code classIdx} (its own if not yet registered). */
    static long tibOfClass(int classIdx)
    {
        int r = classRegOf(classIdx);
        return r >= 0 ? clTib[r] : gTib;
    }

    /** Scalar instance size (header + one 8-byte slot per field) of class {@code classIdx}. */
    static int objectSizeOf(int classIdx)
    {
        int r = classRegOf(classIdx);
        int fields = r >= 0 ? clFieldCount[r] : gifCount;
        return 16 + fields * 8;
    }

    /** Type node of the interface owning InterfaceMethodref {@code idx}. */
    static long ifaceTypeOfMethod(int idx)
    {
        int classIdx = u2(gbase + gcp[idx]);            // *ref.class_index -> Class entry
        return typeOfClass(classIdx);
    }

    // ----- the on-metal MetalSymbols stubs made real: strings + magic intrinsics -----

    /**
     * Intern the String literal at cp index {@code stringCp} as a heap {@code byte[]}
     * (the writer's [null TIB][status][length][bytes] layout, i.e. an ordinary array)
     * and return its address, so the shared core's {@code ldc} loads a real ref.
     */
    static long internString(int stringCp)
    {
        int off = ClassReader.stringUtf8Off(gbytes, gcp, stringCp);   // Utf8 body offset
        int len = u2(gbase + off);
        long arr = Heap.allocArray(len, 1);             // byte[]: TIB=0, length set
        int i = 0;
        while (i < len)
        {
            Magic.store8(arr + 24 + i, u1(gbase + off + 2 + i));   // ARRAY_BASE_OFFSET = 24
            i += 1;
        }
        return arr;
    }

    /** Whether the {@code *ref} at {@code idx} names owner {@code magic/Magic}. */
    static boolean isMagicOwner(int idx)
    {
        long p = gbase + ClassReader.refClassNameOff(gbytes, gcp, idx);
        if (u2(p) != 11)                                // "magic/Magic"
        {
            return false;
        }
        p += 2;
        return u1(p) == 'm' && u1(p + 1) == 'a' && u1(p + 2) == 'g' && u1(p + 3) == 'i'
            && u1(p + 4) == 'c' && u1(p + 5) == '/' && u1(p + 6) == 'M' && u1(p + 7) == 'a'
            && u1(p + 8) == 'g' && u1(p + 9) == 'i' && u1(p + 10) == 'c';
    }

    /**
     * {@link Intrinsics} id of the {@code magic/Magic} method at {@code idx}, or -1 if
     * not one this JIT recognises. It handles the memory + string-bytes ops a JIT'd
     * class might plausibly call; the boot-only register/barrier ops (msr, eret,
     * write*…) are only ever writer-compiled, never JIT'd, so they are not recognised.
     */
    static int magicId(int idx)
    {
        int n = mrefNameOff(idx);
        if (isName(gbase, n, 0x6279746573L, 5))    { return Intrinsics.BYTES; }    // "bytes"
        if (isName(gbase, n, 0x6C6F616438L, 5))    { return Intrinsics.LOAD8; }    // "load8"
        if (isName(gbase, n, 0x6C6F61643332L, 6))  { return Intrinsics.LOAD32; }   // "load32"
        if (isName(gbase, n, 0x6C6F61643634L, 6))  { return Intrinsics.LOAD64; }   // "load64"
        if (isName(gbase, n, 0x73746F726538L, 6))  { return Intrinsics.STORE8; }   // "store8"
        if (isName(gbase, n, 0x73746F72653332L, 7)) { return Intrinsics.STORE32; } // "store32"
        if (isName(gbase, n, 0x73746F72653634L, 7)) { return Intrinsics.STORE64; } // "store64"
        return -1;
    }




    // encodings (pure int math — JDK-free)
    private static int movz(int rd, int imm)
    {
        return A64Enc.movz(rd, imm, 0);
    }
    private static int mov(int rd, int rm)
    {
        return A64Enc.movReg(rd, rm);
    }

    // condition codes (EQ=0 NE=1 GE=10 LT=11 GT=12 LE=13)
    private static int ifCond(int op)
    {
        return code6(op - 0x99);
    }
    private static int icmpCond(int op)
    {
        return code6(op - 0x9f);
    }
    private static int code6(int k)
    {
        if (k == 0)
        {
            return 0;    // eq
        }
        if (k == 1)
        {
            return 1;    // ne
        }
        if (k == 2)
        {
            return 11;    // lt
        }
        if (k == 3)
        {
            return 10;    // ge
        }
        if (k == 4)
        {
            return 12;    // gt
        }
        return 13;              // le
    }


    private static int opLen(int op)
    {
        if (op == 0x10 || op == 0x15 || op == 0x36 || op == 0x19 || op == 0x3a)
        {
            return 2;    // bipush/iload/istore/aload/astore
        }
        if (op == 0xb9)
        {
            return 5;    // invokeinterface: index(2) + count(1) + zero(1)
        }
        if (op == 0x11 || op == 0x84 || (op >= 0x99 && op <= 0xa4) || op == 0xa7
                || op == 0xb2 || op == 0xb3 || op == 0xb8
                || op == 0xb4 || op == 0xb5 || op == 0xb6 || op == 0xb7 || op == 0xbb
                || op == 0xc0 || op == 0xc1)
        {
            return 3;    // ... get/putstatic / invoke* / get/putfield / new / cast / instanceof
        }
        return 1;
    }
}
