package writer;

import classfile.ClassFile;
import compiler.Baseline;

import java.nio.file.Path;

/**
 * HOST-ONLY diagnostic (#43): recompute the actual peak operand-stack depth for every method of a class using
 * the SAME per-opcode {@code stackDelta} the compiler's pre-pass uses, and flag any method whose computed peak
 * exceeds the classfile's DECLARED {@code max_stack}. A correct depth analysis can never exceed max_stack, so
 * any hit pinpoints an over-counting opcode (which would wrongly flip a shallow method onto the deep-spill path).
 *
 * <p>Usage: {@code java -cp out writer.DepthScan <binary/class/Name> [classesDir]}
 */
public final class DepthScan
{
    private DepthScan() {}
    static boolean TRACE = System.getenv("TRACE") != null;
    static final String METH = System.getenv("METH");

    public static void main(String[] args) throws Exception
    {
        String cls = args.length > 0 ? args[0] : "java/util/HashMap";
        Path dir = Path.of(args.length > 1 ? args[1] : "out");
        ClassRegistry reg = BuildRuntimeImage.populateRegistry(dir);
        ClassFile cf = reg.resolve(cls);
        System.out.println("class " + cls);
        for (ClassFile.Method m : cf.methods())
        {
            if (m.code == null) { continue; }
            if (METH != null && !m.name.equals(METH)) { continue; }
            if (TRACE) { System.out.println("  --- " + m.name + m.descriptor + " (maxStack=" + m.maxStack + ") ---"); }
            int uf;
            try
            {
                int[] rd = reachDepths(cf, m.code, m.exceptions);
                int pk = peak(cf, m.code);
                if (pk > 7) { System.out.println("  DEEP " + m.name + m.descriptor + "  peak=" + pk); }
                uf = firstLinearUnderflow(cf, m.code, rd);
                if (uf >= 0)
                {
                    boolean dead = rd[uf] < 0;
                    System.out.println("  UNDERFLOW " + m.name + m.descriptor + "  at offset " + uf
                            + "  op=0x" + Integer.toHexString(m.code[uf] & 0xFF) + (dead ? "  (dead code)" : "  (REACHABLE)"));
                }
            }
            catch (RuntimeException e) { System.out.println("  " + m.name + m.descriptor + "  (scan error: " + e + ")"); }
        }
    }

    /**
     * Forward data-flow, tracking the depth ENTERING each op and checking that the op's pop count does not
     * exceed it (pop-first semantics -- the compiler's popReg fails mid-op, which a net-delta peak misses).
     * Returns the offset of the first underflowing op, or -1 if none. Uses the same worklist as {@link #peak}.
     */
    static int firstUnderflow(ClassFile cf, byte[] code)
    {
        int[] depth = new int[code.length];
        int[] work = new int[code.length + 8];
        int wc = 0;
        for (int k = 0; k < code.length; k++) { depth[k] = -1; }
        depth[0] = 0; work[wc++] = 0;
        while (wc > 0)
        {
            int pc = work[--wc];
            int d = depth[pc];
            int op = code[pc] & 0xFF;
            if (d - pops(cf, op, code, pc) < 0) { return pc; }
            int after = d + delta(cf, op, code, pc);
            int len = Baseline.opLen(op, code, pc);
            if (op == 0xC8 || op == 0xC9) { len = 5; }
            boolean fall = !(op == 0xA7 || op == 0xC8 || (op >= 0xAC && op <= 0xB1) || op == 0xBF
                          || op == 0xAA || op == 0xAB || op == 0xA9);
            if (fall && pc + len < code.length && depth[pc + len] < 0) { depth[pc + len] = after; work[wc++] = pc + len; }
            if ((op >= 0x99 && op <= 0x9E) || (op >= 0x9F && op <= 0xA6) || op == 0xC6 || op == 0xC7 || op == 0xA7)
            {
                wc = seed(depth, work, wc, pc + s2(code, pc + 1), after);
            }
            else if (op == 0xC8) { wc = seed(depth, work, wc, pc + s4(code, pc + 1), after); }
            else if (op == 0xAA)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                int lo = s4(code, p + 4), hi = s4(code, p + 8);
                wc = seed(depth, work, wc, pc + s4(code, p), after);
                for (int i = 0; i <= hi - lo; i++) { wc = seed(depth, work, wc, pc + s4(code, p + 12 + i * 4), after); }
            }
            else if (op == 0xAB)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                int n = s4(code, p + 4);
                wc = seed(depth, work, wc, pc + s4(code, p), after);
                for (int i = 0; i < n; i++) { wc = seed(depth, work, wc, pc + s4(code, p + 8 + i * 8 + 4), after); }
            }
        }
        return -1;
    }

    /**
     * Mimic the compiler's LINEAR walk: {@code sp} is reset to the branch-edge depth only at branch targets
     * (where recordDepth set bcDepth), and otherwise carries from the previous op -- so UNREACHABLE dead code
     * (after a goto/return that no branch targets) inherits a stale {@code sp} and can underflow on a pop, which
     * the worklist (reachable-only) analysis never sees. Returns {offset, isDeadCode} of the first underflow.
     */
    static int firstLinearUnderflow(ClassFile cf, byte[] code, int[] reachDepth)
    {
        int sp = 0;
        int pc = 0;
        while (pc < code.length)
        {
            int op = code[pc] & 0xFF;
            if (reachDepth[pc] >= 0) { sp = reachDepth[pc]; }   // branch target / handler: adopt the edge depth
            if (sp - pops(cf, op, code, pc) < 0) { return pc; }
            sp += delta(cf, op, code, pc);
            int len = Baseline.opLen(op, code, pc);
            if (op == 0xC8 || op == 0xC9) { len = 5; }
            pc += len < 1 ? 1 : len;
        }
        return -1;
    }

    /** Reachable depth[] (worklist), exposed so firstLinearUnderflow can tell targets from dead fall-through. */
    static int[] reachDepths(ClassFile cf, byte[] code, ClassFile.ExceptionEntry[] ex)
    {
        int[] depth = new int[code.length];
        int[] work = new int[code.length + 8 + (ex == null ? 0 : ex.length)];
        int wc = 0;
        for (int k = 0; k < code.length; k++) { depth[k] = -1; }
        depth[0] = 0; work[wc++] = 0;
        if (ex != null)
        {
            for (ClassFile.ExceptionEntry e : ex)   // handler entry: the caught exception (depth 1)
            {
                if (depth[e.handlerPc()] < 0) { depth[e.handlerPc()] = 1; work[wc++] = e.handlerPc(); }
            }
        }
        while (wc > 0)
        {
            int pc = work[--wc];
            int d = depth[pc];
            int op = code[pc] & 0xFF;
            int after = d + delta(cf, op, code, pc);
            int len = Baseline.opLen(op, code, pc);
            if (op == 0xC8 || op == 0xC9) { len = 5; }
            boolean fall = !(op == 0xA7 || op == 0xC8 || (op >= 0xAC && op <= 0xB1) || op == 0xBF
                          || op == 0xAA || op == 0xAB || op == 0xA9);
            if (fall && pc + len < code.length && depth[pc + len] < 0) { depth[pc + len] = after; work[wc++] = pc + len; }
            if ((op >= 0x99 && op <= 0x9E) || (op >= 0x9F && op <= 0xA6) || op == 0xC6 || op == 0xC7 || op == 0xA7)
            {
                wc = seed(depth, work, wc, pc + s2(code, pc + 1), after);
            }
            else if (op == 0xC8) { wc = seed(depth, work, wc, pc + s4(code, pc + 1), after); }
            else if (op == 0xAA)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                int lo = s4(code, p + 4), hi = s4(code, p + 8);
                wc = seed(depth, work, wc, pc + s4(code, p), after);
                for (int i = 0; i <= hi - lo; i++) { wc = seed(depth, work, wc, pc + s4(code, p + 12 + i * 4), after); }
            }
            else if (op == 0xAB)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                int n = s4(code, p + 4);
                wc = seed(depth, work, wc, pc + s4(code, p), after);
                for (int i = 0; i < n; i++) { wc = seed(depth, work, wc, pc + s4(code, p + 8 + i * 8 + 4), after); }
            }
        }
        return depth;
    }

    /** How many operands op pops (the compiler's popReg count). */
    private static int pops(ClassFile cf, int op, byte[] code, int pos)
    {
        if (op >= 0x2E && op <= 0x35) { return 2; }        // *aload: arrayref+index
        if (op >= 0x36 && op <= 0x4E) { return 1; }        // *store
        if (op >= 0x4F && op <= 0x56) { return 3; }        // *astore
        if (op == 0x57) { return 1; }                      // pop
        if (op == 0x58) { return 2; }                      // pop2
        if (op == 0x59) { return 1; }                      // dup
        if (op == 0x5A || op == 0x5B) { return op - 0x58; }// dup_x1 peeks 2, dup_x2 peeks 3
        if (op == 0x5C) { return 2; }                      // dup2 (cat-1)
        if (op == 0x5F) { return 2; }                      // swap
        if (op >= 0x60 && op <= 0x73) { return 2; }        // add/sub/mul/div/rem
        if (op >= 0x74 && op <= 0x77) { return 1; }        // neg
        if (op >= 0x78 && op <= 0x83) { return 2; }        // shifts/and/or/xor
        if (op >= 0x85 && op <= 0x93) { return 1; }        // conversions
        if (op >= 0x94 && op <= 0x98) { return 2; }        // lcmp/fcmp/dcmp
        if (op >= 0x99 && op <= 0x9E) { return 1; }        // if<cond>
        if (op >= 0x9F && op <= 0xA6) { return 2; }        // if_icmp/if_acmp
        if (op >= 0xAC && op <= 0xB0) { return 1; }        // *return (value)
        if (op == 0xB3) { return 1; }                      // putstatic
        if (op == 0xB4) { return 1; }                      // getfield (objref)
        if (op == 0xB5) { return 2; }                      // putfield
        if (op == 0xB6 || op == 0xB7 || op == 0xB9) { ClassFile.MemberRef r = cf.memberRef(u2(code, pos + 1)); return params(r.descriptor()) + 1; }
        if (op == 0xB8) { ClassFile.MemberRef r = cf.memberRef(u2(code, pos + 1)); return params(r.descriptor()); }
        if (op == 0xBC || op == 0xBD || op == 0xBE) { return 1; }   // newarray/anewarray/arraylength (count/ref)
        if (op == 0xBF) { return 1; }                      // athrow
        if (op == 0xC0 || op == 0xC1) { return 1; }        // checkcast/instanceof
        if (op == 0xC2 || op == 0xC3) { return 1; }        // monitorenter/exit
        if (op == 0xC5) { return code[pos + 3] & 0xFF; }   // multianewarray dims
        if (op == 0xC6 || op == 0xC7) { return 1; }        // ifnull/ifnonnull
        return 0;
    }

    /** Forward data-flow peak, mirroring Baseline.computeDepths. */
    private static int peak(ClassFile cf, byte[] code)
    {
        int[] depth = new int[code.length];
        int[] work = new int[code.length + 8];
        int wc = 0;
        for (int k = 0; k < code.length; k++) { depth[k] = -1; }
        depth[0] = 0; work[wc++] = 0;
        int peak = 0;
        while (wc > 0)
        {
            int pc = work[--wc];
            int d = depth[pc];
            int op = code[pc] & 0xFF;
            int after = d + delta(cf, op, code, pc);
            if (TRACE) { System.out.println("    pc=" + pc + " op=0x" + Integer.toHexString(op) + " d=" + d + " -> after=" + after); }
            if (after > peak) { peak = after; }
            int len = Baseline.opLen(op, code, pc);
            if (op == 0xC8 || op == 0xC9) { len = 5; }
            boolean fall = !(op == 0xA7 || op == 0xC8 || (op >= 0xAC && op <= 0xB1) || op == 0xBF
                          || op == 0xAA || op == 0xAB || op == 0xA9);
            if (fall && pc + len < code.length && depth[pc + len] < 0) { depth[pc + len] = after; work[wc++] = pc + len; }
            if ((op >= 0x99 && op <= 0x9E) || (op >= 0x9F && op <= 0xA6) || op == 0xC6 || op == 0xC7 || op == 0xA7)
            {
                wc = seed(depth, work, wc, pc + s2(code, pc + 1), after);
            }
            else if (op == 0xC8) { wc = seed(depth, work, wc, pc + s4(code, pc + 1), after); }
            else if (op == 0xAA)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                int lo = s4(code, p + 4), hi = s4(code, p + 8);
                wc = seed(depth, work, wc, pc + s4(code, p), after);
                for (int i = 0; i <= hi - lo; i++) { wc = seed(depth, work, wc, pc + s4(code, p + 12 + i * 4), after); }
            }
            else if (op == 0xAB)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                int n = s4(code, p + 4);
                wc = seed(depth, work, wc, pc + s4(code, p), after);
                for (int i = 0; i < n; i++) { wc = seed(depth, work, wc, pc + s4(code, p + 8 + i * 8 + 4), after); }
            }
        }
        return peak;
    }

    private static int seed(int[] depth, int[] work, int wc, int t, int d)
    {
        if (t >= 0 && t < depth.length && depth[t] < 0) { depth[t] = d; work[wc++] = t; }
        return wc;
    }

    /** A copy of Baseline.stackDelta (invoke param/return counts from the MemberRef descriptor). */
    private static int delta(ClassFile cf, int op, byte[] code, int pos)
    {
        if (op == 0x01) { return 1; }
        if (op >= 0x02 && op <= 0x2D) { return 1; }
        if (op >= 0x2E && op <= 0x35) { return -1; }
        if (op >= 0x36 && op <= 0x4E) { return -1; }
        if (op >= 0x4F && op <= 0x56) { return -3; }
        if (op == 0x57) { return -1; }
        if (op == 0x58) { return -2; }
        if (op == 0x59 || op == 0x5A || op == 0x5B) { return 1; }
        if (op == 0x5C || op == 0x5D || op == 0x5E) { return 2; }
        if (op == 0x5F) { return 0; }
        if (op >= 0x60 && op <= 0x73) { return -1; }
        if (op >= 0x74 && op <= 0x77) { return 0; }
        if (op >= 0x78 && op <= 0x83) { return -1; }
        if (op == 0x84) { return 0; }
        if (op >= 0x85 && op <= 0x93) { return 0; }
        if (op >= 0x94 && op <= 0x98) { return -1; }
        if (op >= 0x99 && op <= 0x9E) { return -1; }
        if (op >= 0x9F && op <= 0xA6) { return -2; }
        if (op == 0xA7 || op == 0xC8) { return 0; }
        if (op == 0xAA || op == 0xAB) { return -1; }
        if (op >= 0xAC && op <= 0xB0) { return -1; }
        if (op == 0xB1) { return 0; }
        if (op == 0xB2) { return 1; }
        if (op == 0xB3) { return -1; }
        if (op == 0xB4) { return 0; }
        if (op == 0xB5) { return -2; }
        if (op == 0xB6 || op == 0xB7 || op == 0xB9)
        {
            ClassFile.MemberRef r = cf.memberRef(u2(code, pos + 1));
            return (retVal(r.descriptor()) ? 1 : 0) - params(r.descriptor()) - 1;
        }
        if (op == 0xB8)
        {
            ClassFile.MemberRef r = cf.memberRef(u2(code, pos + 1));
            return (retVal(r.descriptor()) ? 1 : 0) - params(r.descriptor());
        }
        if (op == 0xBA) { return 1; }   // invokedynamic: approximate (concat/lambda -> 1 result); not deep-relevant here
        if (op == 0xBB) { return 1; }
        if (op == 0xBC || op == 0xBD || op == 0xBE) { return 0; }
        if (op == 0xBF) { return -1; }
        if (op == 0xC0 || op == 0xC1) { return 0; }
        if (op == 0xC2 || op == 0xC3) { return -1; }
        if (op == 0xC4) { int w = code[pos + 1] & 0xFF; return w == 0x84 ? 0 : (w >= 0x36 ? -1 : 1); }
        if (op == 0xC5) { return 1 - (code[pos + 3] & 0xFF); }
        if (op == 0xC6 || op == 0xC7) { return -1; }
        if (op == 0xC9 || op == 0xA8) { return 1; }
        return 0;
    }

    private static boolean retVal(String desc) { return desc.charAt(desc.indexOf(')') + 1) != 'V'; }

    private static int params(String desc)
    {
        int n = 0, i = 1;
        while (desc.charAt(i) != ')')
        {
            char c = desc.charAt(i);
            if (c == '[') { i++; continue; }
            if (c == 'L') { while (desc.charAt(i) != ';') { i++; } }
            n++; i++;
        }
        return n;
    }

    private static int u2(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static int s2(byte[] b, int i) { return (short) (((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF)); }
    private static int s4(byte[] b, int i) { return (b[i] << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF); }
}
