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

    /** Compile+run a no-arg static method matching the current seek key in {@code bytes}. */
    private static int load0(long bytes)
    {
        parseConstPool(bytes);
        parseFields();
        long code = findMethod(bytes);
        if (code == 0L)
        {
            return 0;
        }
        return compileAndRun(code, gcodeLen);
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

    /** M4: our own Guest class — compile+run answer() (returns 42 = '*'). */
    static int loadGuest()
    {
        seek(0x616e73776572L, 6, 0x282949L, 3);        // "answer" "()I"
        return load0(VM.guestBytes);
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
        p += 6;                                         // access_flags, this_class, super_class
        p += 2 + u2(p) * 2;                             // interfaces
        int fcount = u2(p);
        p += 2;
        gsfName = new int[fcount + 1];
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
            int attrs = u2(p);
            p += 2;
            p = skipAttributes(p, attrs);
            f += 1;
        }
        gsfCount = slot;
        gStatics = Heap.alloc(slot * 8 + 8);
        int z = 0;
        while (z < slot)
        {
            Magic.store64(gStatics + z * 8, 0L);    // statics default to 0
            z += 1;
        }
        gp = p;
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

    private static int compileAndRun(long code, int len)
    {
        return (int) Magic.call0(compile(code, len));
    }

    /** Compile bytecode [code, code+len) to A64 in a fresh heap buffer; return the buffer. */
    private static long compile(long code, int len)
    {
        long buf = Heap.alloc(256);
        cbuf = buf;
        cout = buf;
        csp = 0;
        cbc = new int[len + 1];
        cdepth = new int[len];
        int j = 0;
        while (j < len)
        {
            cdepth[j] = -1;
            j += 1;
        }
        pass1(code, len);
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
        Magic.dsb();                                    // publish the code (caches are off)
        Magic.isb();
        return buf;
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
            w += wordsFor(op);
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

    private static int wordsFor(int op)
    {
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
        if (op == 0x10 || op == 0x15 || op == 0x36)
        {
            return 2;    // bipush/iload/istore
        }
        if (op == 0x11 || op == 0x84 || (op >= 0x99 && op <= 0xa4) || op == 0xa7
                || op == 0xb2 || op == 0xb3)
        {
            return 3;    // ... / get/putstatic
        }
        return 1;
    }
}
