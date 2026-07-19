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
public final class Loader {
    private Loader() {}

    private static long gp;         // parse cursor
    private static int[] gutf8;     // byte offset of each Utf8 constant-pool entry
    private static int gcodeLen;    // length of the located method's bytecode

    private static int u1(long p) { return Magic.load8(p) & 0xFF; }
    private static int u2(long p) { return (u1(p) << 8) | u1(p + 1); }
    private static int u4(long p) { return (u2(p) << 16) | u2(p + 2); }

    /** Parse the embedded Guest class, compile+run {@code answer()}, return its result. */
    static int loadGuest() {
        long base = VM.guestBytes;
        parseConstPool(base);
        long code = findAnswer(base);
        if (code == 0L) return 0;
        return compileAndRun(code, gcodeLen);
    }

    /** Walk the constant pool, recording Utf8 offsets; leave {@code gp} just past it. */
    private static void parseConstPool(long base) {
        long p = base + 8;                              // skip magic(4) + minor/major(4)
        int cpCount = u2(p); p += 2;
        gutf8 = new int[cpCount];
        int i = 1;
        while (i < cpCount) {
            int tag = u1(p); p += 1;
            if (tag == 1) { gutf8[i] = (int) (p - base); p += 2 + u2(p); }   // Utf8
            else if (tag == 5 || tag == 6) { p += 8; i += 1; }              // Long/Double: two slots
            else if (tag == 15) { p += 3; }                                 // MethodHandle
            else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) { p += 2; }
            else { p += 4; }                                                // *ref / NameAndType / Dynamic
            i += 1;
        }
        gp = p;
    }

    /** From {@code gp}, skip to the methods and return the address of answer()'s bytecode (0 if none). */
    private static long findAnswer(long base) {
        long p = gp;
        p += 6;                                         // access_flags, this_class, super_class
        p += 2 + u2(p) * 2;                             // interfaces
        p = skipMembers(p);                             // fields
        int mcount = u2(p); p += 2;
        int m = 0;
        while (m < mcount) {
            p += 2;                                     // access_flags
            int nameIdx = u2(p); p += 4;               // name_index, descriptor_index
            int attrs = u2(p); p += 2;
            if (isName(base, gutf8[nameIdx], 0x616e73776572L, 6)) {   // "answer"
                long code = findCode(base, p, attrs);
                if (code != 0L) return code;
            } else {
                p = skipAttributes(p, attrs);
            }
            m += 1;
        }
        return 0L;
    }

    /** Find the Code attribute among {@code attrs} at {@code p}; return the bytecode address. */
    private static long findCode(long base, long p, int attrs) {
        int a = 0;
        while (a < attrs) {
            int anIdx = u2(p); p += 2;
            int alen = u4(p); p += 4;
            if (isName(base, gutf8[anIdx], 0x436f6465L, 4)) {          // "Code"
                gcodeLen = u4(p + 4);                   // after max_stack(2) + max_locals(2)
                return p + 8;
            }
            p += alen;
            a += 1;
        }
        return 0L;
    }

    private static long skipMembers(long p) {
        int n = u2(p); p += 2;
        int k = 0;
        while (k < n) { p += 6; int attrs = u2(p); p += 2; p = skipAttributes(p, attrs); k += 1; }
        return p;
    }

    private static long skipAttributes(long p, int attrs) {
        int a = 0;
        while (a < attrs) { p += 2; int alen = u4(p); p += 4 + alen; a += 1; }
        return p;
    }

    /** Compare the Utf8 at {@code off} against {@code expected} (bytes packed big-endian, {@code len} bytes). */
    private static boolean isName(long base, int off, long expected, int len) {
        if (off == 0) return false;
        if (u2(base + off) != len) return false;
        long got = 0L;
        int j = 0;
        while (j < len) { got = (got << 8) | u1(base + off + 2 + j); j += 1; }
        return got == expected;
    }

    /** Compile {@code return &lt;const&gt;} bytecode to A64, publish it, and run it. */
    private static int compileAndRun(long code, int len) {
        long buf = Heap.alloc(64);                      // 8-aligned code buffer
        long out = buf;
        int pc = 0;
        while (pc < len) {
            int op = u1(code + pc);
            if (op == 0x10) { Magic.store32(out, movzX0(u1(code + pc + 1))); out += 4; pc += 2; }        // bipush
            else if (op == 0x11) { Magic.store32(out, movzX0(u2(code + pc + 1))); out += 4; pc += 3; }   // sipush
            else if (op >= 0x03 && op <= 0x08) { Magic.store32(out, movzX0(op - 0x03)); out += 4; pc += 1; } // iconst
            else if (op == 0xAC) { Magic.store32(out, 0xD65F03C0); out += 4; pc += 1; }                  // ireturn -> ret
            else { pc += 1; }
        }
        Magic.dsb();                                    // publish the code (caches are off)
        Magic.isb();
        return (int) Magic.call0(buf);
    }

    /** {@code MOVZ X0, #imm16} — the value a trivial answer() returns. */
    private static int movzX0(int imm) { return 0xD2800000 | ((imm & 0xFFFF) << 5); }
}
