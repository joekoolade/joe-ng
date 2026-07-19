package vm;

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
    private static int gcodeLen;    // length of the located method's bytecode
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
    private static int[] gvName;    // vtable: virtual method name Utf8 offset (index = slot)
    private static int[] gvDesc;    // ... descriptor Utf8 offset
    private static long[] gvCode;   // ... bytecode address
    private static int[] gvLen;     // ... bytecode length
    private static int gvCount;     // number of virtual methods (vtable slots)
    private static long gTib;       // this class's TIB { Type, vtable... }, built before emit

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
            long unused = Magic.call0(compile(code, gcodeLen));   // run <clinit>; discard result
        }
    }

    /** Compile+run a two-int-arg static method matching the seek key, with args {@code a,b}. */
    private static long load2(long bytes, long a, long b)
    {
        parseConstPool(bytes);
        parseFields();
        long code = findMethod(bytes);
        if (code == 0L)
        {
            return 0L;
        }
        long buf = compile(code, gcodeLen);
        return Magic.call2(buf, a, b);
    }

    /**
     * M4/cross-class — load two classes and link them on the metal. Helper is
     * compiled and registered first (Guest depends on it); then Guest is compiled
     * with its cross-class {@code invokestatic Helper.*} resolved through the
     * registry, and {@code answer()} runs. Returns 42 = '*'.
     */
    static int loadGuest()
    {
        rgBase = new long[MAXREG];
        rgClassOff = new int[MAXREG];
        rgNameOff = new int[MAXREG];
        rgDescOff = new int[MAXREG];
        rgBuf = new long[MAXREG];
        rgCount = 0;
        setClass(VM.helperBytes);                      // dependency first (no cross-class cycles)
        compileClass(VM.helperBytes);
        registerAll();
        setClass(VM.guestBytes);
        runClinit(VM.guestBytes);                      // initialize Guest's statics
        compileClass(VM.guestBytes);                   // cross-class calls link via the registry
        registerAll();
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
        return (int) load2(VM.mathBytes, 0x4DL, 0x21L);
    }

    /** Walk the constant pool, recording each entry's body offset; leave {@code gp} just past it. */
    private static void parseConstPool(long base)
    {
        gbase = base;
        long p = base + 8;                              // skip magic(4) + minor/major(4)
        int cpCount = u2(p);
        p += 2;
        gcp = new int[cpCount];
        int i = 1;
        while (i < cpCount)
        {
            int tag = u1(p);
            p += 1;
            gcp[i] = (int) (p - base);                  // body starts right after the tag
            if (tag == 1)
            {
                p += 2 + u2(p);    // Utf8
            }
            else if (tag == 5 || tag == 6)
            {
                p += 8;    // Long/Double: two slots
                i += 1;
            }
            else if (tag == 15)
            {
                p += 3;    // MethodHandle
            }
            else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20)
            {
                p += 2;
            }
            else
            {
                p += 4;    // *ref / NameAndType / Dynamic
            }
            i += 1;
        }
        gp = p;
    }

    /** Parse the fields, assigning each static field a slot; allocate a zeroed statics block. */
    private static void parseFields()
    {
        long p = gp;
        int thisClass = u2(p + 2);                      // this_class -> Class -> name Utf8 offset
        gThisNameOff = gcp[u2(gbase + gcp[thisClass])];
        p += 6;                                         // access_flags, this_class, super_class
        p += 2 + u2(p) * 2;                             // interfaces
        int fcount = u2(p);
        p += 2;
        gsfName = new int[fcount + 1];
        gifName = new int[fcount + 1];
        int slot = 0;
        int islot = 0;
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
                gifName[islot] = gcp[nameIdx];   // instance field: assign the next slot
                islot += 1;
            }
            int attrs = u2(p);
            p += 2;
            p = skipAttributes(p, attrs);
            f += 1;
        }
        gsfCount = slot;
        gifCount = islot;
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
     * Assign a vtable slot to each virtual method (non-static, non-constructor,
     * non-private), in declaration order, recording its name/descriptor/Code so the
     * loader can build the TIB and resolve {@code invokevirtual}. No inheritance:
     * slots are this class's own virtual methods only (Object's aren't in the file).
     */
    private static void parseVtable(long bytes)
    {
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        gvName = new int[mcount + 1];
        gvDesc = new int[mcount + 1];
        gvCode = new long[mcount + 1];
        gvLen = new int[mcount + 1];
        int slot = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);                      // access, name, descriptor, attrs
            if (isVirtual(u2(p), gcp[u2(p + 2)]))
            {
                gvName[slot] = gcp[u2(p + 2)];
                gvDesc[slot] = gcp[u2(p + 4)];
                gvCode[slot] = findCode(bytes, p + 8, attrs);   // sets gcodeLen
                gvLen[slot] = gcodeLen;
                slot += 1;
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        gvCount = slot;
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
    private static long staticAddr(int idx)
    {
        int natIdx = u2(gbase + gcp[idx] + 2);          // Fieldref -> NameAndType
        int nameOff = gcp[u2(gbase + gcp[natIdx])];     // NameAndType -> name Utf8 offset
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
                gcodeLen = u4(p + 4);                   // after max_stack(2) + max_locals(2)
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

    // ----- on-metal bytecode -> A64 compiler (JVM local slot k -> x(1+k), operand
    //       stack -> x9..x15, result in x0). Two passes so branches can resolve. --
    private static long cbuf, cout;   // code buffer base / emit cursor
    private static int[] cbc;         // bytecode offset -> word index
    private static int[] cdepth;      // operand-stack depth at each branch target (-1 = unset)
    private static int csp;           // operand stack depth

    // A method and its callees form a small program; we assign every reachable
    // method a buffer before emitting any, so invokestatic's BL targets are known
    // without compiling nested-and-reentrant (the shared static compile state and
    // the writer-side 10-local ceiling both make on-the-fly recursion awkward).
    private static final int MAXM = 64;
    private static long[] mCode;      // each reachable method's bytecode address
    private static int[] mLen;        // ... and its length
    private static long[] mBuf;       // ... and the buffer assigned to it
    private static int mCount;

    /**
     * Compile the entry method and every static method it transitively calls,
     * then return the entry's buffer. Three flat passes — discover (BFS the call
     * graph), place (size each method and hand it a buffer), emit (now every BL
     * target address is known) — so no method's compile nests inside another's.
     * Scope: same-class static callees, no recursion/cycles beyond dedup.
     */
    private static long compile(long code, int len)
    {
        mCode = new long[MAXM];
        mLen = new int[MAXM];
        mBuf = new long[MAXM];
        mCount = 0;
        addMethod(code, len);
        int v = 0;
        while (v < gvCount)                             // seed all virtual methods (vtable must be
        {                                               // complete even if some aren't called)
            if (gvCode[v] != 0L)
            {
                addMethod(gvCode[v], gvLen[v]);
            }
            v += 1;
        }
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
        mCode = new long[MAXM];
        mLen = new int[MAXM];
        mBuf = new long[MAXM];
        mCount = 0;
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
                addMethod(code, gcodeLen);
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

    /** Establish the current-class context: constant pool, fields/statics, vtable. */
    private static void setClass(long bytes)
    {
        parseConstPool(bytes);
        parseFields();
        parseVtable(bytes);
    }

    /** Record a method (deduped by bytecode address; dedup also breaks cycles). */
    private static void addMethod(long code, int len)
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
        mCount += 1;
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
                long c = calleeCodeOf(u2(code + pc + 1));   // sets gcodeLen
                if (c != 0L)
                {
                    addMethod(c, gcodeLen);
                }
            }
            pc += opLen(op);
        }
    }

    /** Size method {@code i} (pass1) and allocate its buffer. */
    private static void sizeMethod(int i)
    {
        long code = mCode[i];
        int len = mLen[i];
        cbc = new int[len + 1];
        pass1(code, len);
        mBuf[i] = Heap.alloc((cbc[len] + 4) * 4);       // calls are big; size to the code
    }

    /** Emit method {@code i}'s A64 into its assigned buffer (pass2). */
    private static void emitMethod(int i)
    {
        long code = mCode[i];
        int len = mLen[i];
        cbc = new int[len + 1];
        cdepth = new int[len];
        int j = 0;
        while (j < len)
        {
            cdepth[j] = -1;
            j += 1;
        }
        pass1(code, len);                               // rebuild the branch-target map
        cbuf = mBuf[i];
        cout = mBuf[i];
        csp = 0;
        int pc = 0;
        while (pc < len)
        {
            if (cdepth[pc] >= 0)
            {
                csp = cdepth[pc];    // merge point: adopt the branch-edge depth
            }
            emitOp(code, pc);
            pc += opLen(u1(code + pc));
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
    private static long resolveCallBuf(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            return bufOf(calleeCodeOf(idx));            // same class: local buffer
        }
        return globalBuf(idx);                          // cross class: another loaded class
    }

    /**
     * Build this class's TIB in the heap: slot 0 is the Type (null — no
     * instanceof/checkcast on loaded objects yet), then one vtable entry per
     * virtual method pointing at its compiled buffer. {@code new} stores this into
     * each instance's header so {@code invokevirtual} can dispatch through it.
     */
    private static void buildTib()
    {
        gTib = Heap.alloc((1 + gvCount) * 8);
        Magic.store64(gTib, 0L);                         // TIB[0] = Type
        int s = 0;
        while (s < gvCount)
        {
            Magic.store64(gTib + 8 + s * 8, bufOf(gvCode[s]));   // TIB[1+slot] = method code
            s += 1;
        }
    }

    /** Vtable slot of the virtual method named by Methodref {@code idx}. */
    private static int vtableSlotOf(int idx)
    {
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        int s = 0;
        while (s < gvCount)
        {
            if (utf8Eq(gvName[s], nameOff) && utf8Eq(gvDesc[s], descOff))
            {
                return s;
            }
            s += 1;
        }
        return 0;
    }

    private static void rec(int tbc)
    {
        if (cdepth[tbc] < 0)
        {
            cdepth[tbc] = csp;
        }
    }

    /** First pass: map each bytecode offset to its A64 word index (for branch targets). */
    private static void pass1(long code, int len)
    {
        int pc = 0;
        int w = 0;
        while (pc < len)
        {
            cbc[pc] = w;
            int op = u1(code + pc);
            w += wordsFor(code, pc, op);
            pc += opLen(op);
        }
        cbc[len] = w;
    }

    private static void emit(int word)
    {
        Magic.store32(cout, word);
        cout += 4;
    }
    private static int curWord()
    {
        return (int) ((cout - cbuf) >> 2);
    }
    private static int target(long code, int pc)
    {
        return pc + (short) ((u1(code + pc + 1) << 8) | u1(code + pc + 2));
    }
    private static void emitBc(int cond, int tbc)
    {
        emit(0x54000000 | (((cbc[tbc] - curWord()) & 0x7FFFF) << 5) | cond);
    }

    private static void emitOp(long code, int pc)
    {
        int op = u1(code + pc);
        if (op >= 0x03 && op <= 0x08)
        {
            emit(movz(9 + csp, op - 0x03));    // iconst_0..5
            csp += 1;
        }
        else if (op == 0x10)
        {
            emit(movz(9 + csp, u1(code + pc + 1)));    // bipush (>=0)
            csp += 1;
        }
        else if (op == 0x11)
        {
            emit(movz(9 + csp, u2(code + pc + 1)));    // sipush
            csp += 1;
        }
        else if (op >= 0x1a && op <= 0x1d)
        {
            emit(mov(9 + csp, 1 + (op - 0x1a)));    // iload_0..3
            csp += 1;
        }
        else if (op == 0x15)
        {
            emit(mov(9 + csp, 1 + u1(code + pc + 1)));    // iload
            csp += 1;
        }
        else if (op >= 0x3b && op <= 0x3e)
        {
            csp -= 1;    // istore_0..3
            emit(mov(1 + (op - 0x3b), 9 + csp));
        }
        else if (op == 0x36)
        {
            csp -= 1;    // istore
            emit(mov(1 + u1(code + pc + 1), 9 + csp));
        }
        else if (op == 0x60)
        {
            csp -= 1;    // iadd
            emit(dp(0x8B000000, 9 + csp - 1, 9 + csp - 1, 9 + csp));
        }
        else if (op == 0x64)
        {
            csp -= 1;    // isub
            emit(dp(0xCB000000, 9 + csp - 1, 9 + csp - 1, 9 + csp));
        }
        else if (op == 0x68)
        {
            csp -= 1;    // imul
            emit(dp(0x9B007C00, 9 + csp - 1, 9 + csp - 1, 9 + csp));
        }
        else if (op == 0x84)
        {
            emitIinc(code, pc);    // iinc
        }
        else if (op >= 0x99 && op <= 0x9e)
        {
            csp -= 1;
            rec(target(code, pc));
            emit(0xF100001F | ((9 + csp) << 5));
            emitBc(ifCond(op), target(code, pc));
        }
        else if (op >= 0x9f && op <= 0xa4)
        {
            csp -= 2;
            rec(target(code, pc));
            emit(0xEB00001F | ((9 + csp + 1) << 16) | ((9 + csp) << 5));
            emitBc(icmpCond(op), target(code, pc));
        }
        else if (op == 0xa7)
        {
            rec(target(code, pc));    // goto
            emit(0x14000000 | (((cbc[target(code, pc)] - curWord()) & 0x3FFFFFF)));
        }
        else if (op == 0xac)
        {
            csp -= 1;    // ireturn
            emit(mov(0, 9 + csp));
            emit(0xD65F03C0);
        }
        else if (op == 0xb2)
        {
            emitAddr16(staticAddr(u2(code + pc + 1)));    // getstatic (ldrsw)
            emit(0xB9800000 | (16 << 5) | (9 + csp));
            csp += 1;
        }
        else if (op == 0xb3)
        {
            csp -= 1;    // putstatic (strw)
            emitAddr16(staticAddr(u2(code + pc + 1)));
            emit(0xB9000000 | (16 << 5) | (9 + csp));
        }
        else if (op == 0xb8)
        {
            emitInvokeStatic(code, pc);    // invokestatic (same class)
        }
        else if (op >= 0x2a && op <= 0x2d)
        {
            emit(mov(9 + csp, 1 + (op - 0x2a)));    // aload_0..3 (ref = 64-bit mov)
            csp += 1;
        }
        else if (op == 0x19)
        {
            emit(mov(9 + csp, 1 + u1(code + pc + 1)));    // aload
            csp += 1;
        }
        else if (op >= 0x4b && op <= 0x4e)
        {
            csp -= 1;    // astore_0..3
            emit(mov(1 + (op - 0x4b), 9 + csp));
        }
        else if (op == 0x3a)
        {
            csp -= 1;    // astore
            emit(mov(1 + u1(code + pc + 1), 9 + csp));
        }
        else if (op == 0x59)
        {
            emit(mov(9 + csp, 9 + csp - 1));    // dup
            csp += 1;
        }
        else if (op == 0xbb)
        {
            emitNew();    // new (same class)
        }
        else if (op == 0xb4)
        {
            csp -= 1;    // getfield: value = [obj + off]
            emit(ldrx(9 + csp, 9 + csp, fieldOffsetOf(u2(code + pc + 1))));
            csp += 1;
        }
        else if (op == 0xb5)
        {
            csp -= 1;    // putfield: pop value, then obj
            int vreg = 9 + csp;
            csp -= 1;
            emit(strx(vreg, 9 + csp, fieldOffsetOf(u2(code + pc + 1))));
        }
        else if (op == 0xb7)
        {
            emitInvokeSpecial(code, pc);    // invokespecial (constructor)
        }
        else if (op == 0xb6 || op == 0xb9)
        {
            emitInvokeVirtual(code, pc);    // invokevirtual / invokeinterface (TIB vtable dispatch)
        }
        else if (op == 0xb1)
        {
            emit(0xD65F03C0);    // return (void)
        }
        else if (op == 0xb0)
        {
            csp -= 1;    // areturn
            emit(mov(0, 9 + csp));
            emit(0xD65F03C0);
        }
    }

    /**
     * invokestatic to a same-class method whose buffer was assigned in the place
     * pass: emit a fixed-shape call — spill the caller's live registers (x30 +
     * x1..x15) to a 128-byte SP frame, move the args into x1.., {@code BL} the
     * callee's buffer, restore, and land its {@code x0} result on the operand
     * stack. The full spill keeps the emitted size independent of the operand
     * depth, so pass1 can size it.
     */
    private static void emitInvokeStatic(long code, int pc)
    {
        int idx = u2(code + pc + 1);
        emitCallSeq(idx, resolveCallBuf(idx), 0);       // same-class or cross-class (registry)
    }

    /**
     * invokespecial to a constructor: {@code Guest.<init>} (same class) is a real
     * call whose receiver is the {@code this} on the stack; {@code Object.<init>}
     * (and any other cross-class target) resolves to nothing here, so it is just a
     * pop of the receiver. No {@code super(...)} with args beyond {@code this} yet.
     */
    private static void emitInvokeSpecial(long code, int pc)
    {
        int idx = u2(code + pc + 1);
        long c = calleeCodeOf(idx);
        if (c == 0L)
        {
            csp -= 1;                                      // Object.<init>/unresolved: pop receiver
        }
        else
        {
            emitCallSeq(idx, bufOf(c), 1);                 // pass this as the extra leading arg
        }
    }

    /**
     * Emit the fixed spill / arg-move / {@code BL} / restore / result sequence.
     * {@code thisArg} is 1 for an instance call (the receiver is the arg below the
     * descriptor args), 0 for a static call.
     */
    private static void emitCallSeq(int idx, long calleeBuf, int thisArg)
    {
        int descOff = mrefDescOff(idx);
        int argc = argSlots(descOff) + thisArg;
        int hasRet = retSlots(descOff);
        emit(0xD1000000 | (128 << 10) | (31 << 5) | 31);   // sub sp, sp, #128
        emit(strx(30, 31, 0));                             // frame: [x30@0][x1..x15@8..120]
        int k = 1;
        while (k <= 15)
        {
            emit(strx(k, 31, k * 8));
            k += 1;
        }
        int t = 0;
        while (t < argc)                                   // args: stack top -> x1..x(argc)
        {
            emit(mov(1 + t, 9 + csp - argc + t));
            t += 1;
        }
        emitBl(calleeBuf);
        emit(ldrx(30, 31, 0));
        k = 1;
        while (k <= 15)
        {
            emit(ldrx(k, 31, k * 8));
            k += 1;
        }
        emit(0x91000000 | (128 << 10) | (31 << 5) | 31);   // add sp, sp, #128
        if (hasRet == 1)
        {
            emit(mov(9 + csp - argc, 0));                   // result replaces the args
        }
        csp = csp - argc + hasRet;
    }

    /**
     * new: allocate an instance by calling the image's {@code Heap.alloc} (whose
     * address the writer stashes in {@code VM.heapAlloc}). Same 128-byte spill as a
     * call — {@code Heap.alloc} clobbers the caller-saved value regs — then null the
     * TIB header (no on-metal vtable for a loaded class) and push the reference.
     * Fields are zero only on a fresh bump (Heap does not clear reused blocks).
     */
    private static void emitNew()
    {
        int size = 16 + gifCount * 8;                      // header(16) + one 8-byte slot per field
        emit(0xD1000000 | (128 << 10) | (31 << 5) | 31);   // sub sp, sp, #128
        emit(strx(30, 31, 0));
        int k = 1;
        while (k <= 15)
        {
            emit(strx(k, 31, k * 8));
            k += 1;
        }
        emit(movz(0, size));                               // x0 = size (Heap.alloc arg)
        emitBl(VM.heapAlloc);                              // x0 = object base
        emit(ldrx(30, 31, 0));
        k = 1;
        while (k <= 15)
        {
            emit(ldrx(k, 31, k * 8));
            k += 1;
        }
        emit(0x91000000 | (128 << 10) | (31 << 5) | 31);   // add sp, sp, #128
        emitAddr16(gTib);                                  // x16 = &TIB
        emit(strx(16, 0, 0));                              // header.tib = &TIB (vtable for dispatch)
        emit(mov(9 + csp, 0));                             // push the reference
        csp += 1;
    }

    /**
     * invokevirtual / invokeinterface: dispatch through the receiver's TIB vtable.
     * Same 128-byte spill / arg-move as a static call (receiver is the leading arg),
     * but instead of a fixed {@code BL} it loads the code address from
     * {@code [[this] + 8 + slot*8]} and {@code BLR}s it (x16 scratch). With a single
     * loaded class, an interface method resolves to that class's own vtable slot by
     * name+descriptor, so both opcodes share this path (a real per-interface itable
     * only matters once several classes implement the interface).
     */
    private static void emitInvokeVirtual(long code, int pc)
    {
        int idx = u2(code + pc + 1);
        int descOff = mrefDescOff(idx);
        int argc = argSlots(descOff) + 1;                  // + receiver
        int hasRet = retSlots(descOff);
        int slot = vtableSlotOf(idx);
        emit(0xD1000000 | (128 << 10) | (31 << 5) | 31);   // sub sp, sp, #128
        emit(strx(30, 31, 0));
        int k = 1;
        while (k <= 15)
        {
            emit(strx(k, 31, k * 8));
            k += 1;
        }
        int t = 0;
        while (t < argc)                                   // this + args -> x1..x(argc)
        {
            emit(mov(1 + t, 9 + csp - argc + t));
            t += 1;
        }
        emit(ldrx(16, 1, 0));                              // x16 = [this]        (TIB)
        emit(ldrx(16, 16, 8 + slot * 8));                  // x16 = [TIB + slot]  (code)
        emit(0xD63F0000 | (16 << 5));                      // blr x16
        emit(ldrx(30, 31, 0));
        k = 1;
        while (k <= 15)
        {
            emit(ldrx(k, 31, k * 8));
            k += 1;
        }
        emit(0x91000000 | (128 << 10) | (31 << 5) | 31);   // add sp, sp, #128
        if (hasRet == 1)
        {
            emit(mov(9 + csp - argc, 0));
        }
        csp = csp - argc + hasRet;
    }

    /** Instance-field byte offset for the field named by {@code *ref} index (same class). */
    private static int fieldOffsetOf(int idx)
    {
        int nameOff = mrefNameOff(idx);                    // Fieldref layout == Methodref layout
        int s = 0;
        while (s < gifCount)
        {
            if (gifName[s] == nameOff)
            {
                return 16 + s * 8;                         // ObjectModel: fields start at +16
            }
            s += 1;
        }
        return 16;
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
        int natIdx = u2(gbase + gcp[idx] + 2);          // Methodref -> NameAndType -> name
        return gcp[u2(gbase + gcp[natIdx])];
    }

    /** Class-name Utf8 offset of a {@code *ref} constant (Fieldref/Methodref layout). */
    private static int refClassNameOff(int idx)
    {
        int classIdx = u2(gbase + gcp[idx]);            // *ref -> class_index
        return gcp[u2(gbase + gcp[classIdx])];          // Class -> name Utf8 offset
    }

    /** Descriptor Utf8 offset of Methodref {@code idx}. */
    private static int mrefDescOff(int idx)
    {
        int natIdx = u2(gbase + gcp[idx] + 2);          // Methodref -> NameAndType -> descriptor
        return gcp[u2(gbase + gcp[natIdx] + 2)];
    }

    /** Argument slot count of a method descriptor at {@code descOff} (long/double = 2). */
    private static int argSlots(int descOff)
    {
        long q = gbase + descOff + 2 + 1;               // skip u2 length and '('
        int n = 0;
        while (u1(q) != 0x29)                           // ')'
        {
            int c = u1(q);
            if (c == 0x5b)                              // '[' — array prefix, not itself a slot
            {
                q += 1;
            }
            else if (c == 0x4c)                        // 'L...;' — object ref, one slot
            {
                while (u1(q) != 0x3b)
                {
                    q += 1;
                }
                q += 1;
                n += 1;
            }
            else if (c == 0x4a || c == 0x44)           // 'J' long / 'D' double — two slots
            {
                q += 1;
                n += 2;
            }
            else                                       // I B C S Z F — one slot
            {
                q += 1;
                n += 1;
            }
        }
        return n;
    }

    /** Return-slot count of a method descriptor at {@code descOff} (void = 0, else 1). */
    private static int retSlots(int descOff)
    {
        long q = gbase + descOff + 2;
        while (u1(q) != 0x29)                           // ')'
        {
            q += 1;
        }
        q += 1;
        if (u1(q) == 0x56)                              // 'V'
        {
            return 0;
        }
        return 1;
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
        return 0xF9000000 | (((off >> 3) & 0xFFF) << 10) | (rn << 5) | rt;
    }
    /** LDR Xt, [Xn, #off] — off a multiple of 8 (rn=31 = SP). */
    private static int ldrx(int rt, int rn, int off)
    {
        return 0xF9400000 | (((off >> 3) & 0xFFF) << 10) | (rn << 5) | rt;
    }
    /** BL to an absolute code address (relative to the current emit cursor). */
    private static void emitBl(long target)
    {
        int off = (int) ((target - cout) >> 2);
        emit(0x94000000 | (off & 0x03FFFFFF));
    }

    /** Load a &lt;4 GiB address into x16 (MOVZ low16 + MOVK bits16..31). */
    private static void emitAddr16(long addr)
    {
        emit(0xD2800000 | ((((int) addr) & 0xFFFF) << 5) | 16);
        emit(0xF2A00000 | ((((int) (addr >> 16)) & 0xFFFF) << 5) | 16);
    }

    private static void emitIinc(long code, int pc)
    {
        int rd = 1 + u1(code + pc + 1);
        int d = (byte) u1(code + pc + 2);
        if (d >= 0)
        {
            emit(0x91000000 | ((d & 0xFFF) << 10) | (rd << 5) | rd);
        }
        else
        {
            emit(0xD1000000 | (((-d) & 0xFFF) << 10) | (rd << 5) | rd);
        }
    }

    // encodings (pure int math — JDK-free)
    private static int movz(int rd, int imm)
    {
        return 0xD2800000 | ((imm & 0xFFFF) << 5) | rd;
    }
    private static int mov(int rd, int rm)
    {
        return 0xAA0003E0 | (rm << 16) | rd;
    }
    private static int dp(int base, int rd, int rn, int rm)
    {
        return base | (rm << 16) | (rn << 5) | rd;
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

    private static int wordsFor(long code, int pc, int op)
    {
        if (op == 0xb8)
        {
            int descOff = mrefDescOff(u2(code + pc + 1));   // invokestatic call sequence:
            return 35 + argSlots(descOff) + retSlots(descOff);   // spill 16 + args + bl + result
        }
        if (op == 0xb7)                                     // invokespecial
        {
            int idx = u2(code + pc + 1);
            if (calleeCodeOf(idx) == 0L)
            {
                return 0;    // Object.<init>/unresolved lowers to just a pop
            }
            int descOff = mrefDescOff(idx);
            return 35 + argSlots(descOff) + 1 + retSlots(descOff);   // + the this arg
        }
        if (op == 0xb6 || op == 0xb9)                      // invokevirtual / invokeinterface
        {
            int descOff = mrefDescOff(u2(code + pc + 1));
            return 38 + argSlots(descOff) + retSlots(descOff);   // spill 16 + args + ldr/ldr/blr + result
        }
        if (op == 0xbb)
        {
            return 40;    // new: 128-byte spill + movz size + bl + tib addr/store + push
        }
        if (op == 0xb2 || op == 0xb3)
        {
            return 3;    // get/putstatic: movz + movk + ldrsw/strw
        }
        if (op == 0xac)
        {
            return 2;    // ireturn: mov x0 + ret
        }
        if ((op >= 0x99 && op <= 0x9e) || (op >= 0x9f && op <= 0xa4))
        {
            return 2;    // if / if_icmp: cmp + b.cond
        }
        return 1;
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
                || op == 0xb4 || op == 0xb5 || op == 0xb6 || op == 0xb7 || op == 0xbb)
        {
            return 3;    // ... get/putstatic / invoke* / get/putfield / new
        }
        return 1;
    }
}
