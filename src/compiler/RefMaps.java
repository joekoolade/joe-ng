/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-20
 */
package compiler;

import asm.CodeBuffer;
import classfile.ClassReader;

/**
 * Reference maps for a Baseline frame (frames/GC arc, S2): which frame slots hold a reference at each call site.
 *
 * <p>A forward dataflow over the bytecode gives every local and operand-stack slot a type at every reachable pc,
 * the way Jikes RVM's {@code BuildReferenceMaps} does: the method's descriptor seeds the locals, each instruction
 * moves types between the locals and the stack, and where control flow joins, two different types merge to
 * {@link #TOP} -- a slot no later instruction reads before writing it again. Exception edges carry the locals of
 * every instruction in a try range (before it, and after it for a store) into the handler.
 *
 * <p>The unboxed {@code magic} types are words in a register, not references: {@code Address}, {@code Word},
 * {@code Offset} and {@code Extent} are {@link #RAW}, and {@code ObjectReference} is {@link #OREF}, kept apart
 * because it usually HOLDS an object and is where the VM's hidden references are expected (S4's worklist).
 * {@code AddressArray} and {@code ObjectReferenceArray} are real {@code long[]} objects, so they are references.
 *
 * <p>The map is recorded at every {@code BL}/{@code BLR} in the compiled body, keyed by its return address,
 * which is what a stack walk finds in the callee's saved-LR slot. A call is attributed to the bytecode whose
 * machine range contains it. Its locals are that bytecode's ENTERING locals (no bytecode that stores a local
 * also calls), and its operand slots are the ones below what the bytecode pops: those are spilled at the call
 * and untouched by it. Slots the bytecode pops, and transients its lowering pushes, are not described -- a
 * reference held only there is reported by the S2 shadow check as an operand temporary.
 *
 * <p>The map is appended to the method's own code words, after the last instruction, so it lives and dies with
 * the code -- the image and the JIT need no new table, only the word offset (the third word of the old
 * callee-saved-locals table entry, unused since S1).
 */
public final class RefMaps
{
    static final byte TOP = 0;     // unknown, or two different types merged: not a reference
    static final byte REF = 1;     // a reference
    static final byte RAW = 2;     // a category-1 primitive, or an unboxed Address/Word/Offset/Extent
    static final byte OREF = 3;    // an unboxed ObjectReference: a raw word that usually holds an object
    static final byte WIDE = 4;    // a long or double: RAW in the map, but pop2/dup2 need its category

    /** First word of every map: "GMAP". */
    static final int MAGIC = 0x50414D47;
    /** Map header words: magic, sites, locals, op slots, locals base, op base, bit words, unique maps. Then the
     *  sites ({@link #SITE_WORDS} each, by return word), then the unique maps (bit words each, 2 bits a slot:
     *  0 dead, 1 reference, 2 raw, 3 ObjectReference; locals first, then operand slots). */
    static final int HEADER_WORDS = 8;
    /** Words per site: the return word, then unique-map index | live operand depth << 16 | entering depth << 24.
     *  A method whose site would not fit (65536 maps, 256 operand slots) gets no map. */
    static final int SITE_WORDS = 2;
    private static final int DEDUPE_WINDOW = 8;
    public static long statMethods;
    public static long statSites;
    public static long statUniqueWords;

    private final byte[] cls;
    private final int[] cpOff;
    private final int[] cpTag;
    private final byte[] code;
    private final int nLoc;
    private final int nStk;
    private final int width;
    private final int[] exStart;
    private final int[] exEnd;
    private final int[] exHandler;
    private final int exCount;

    private int[] headOf;          // pc -> block index, -1 when pc does not start a block
    private int[] headPc;
    private int nHeads;
    private byte[] headState;      // nHeads * width: entering locals then entering stack
    private int[] headDepth;       // -1 = not reached
    private boolean[] dirty;

    private byte[] loc;
    private byte[] stk;
    private int dep;
    private int survivors;         // the lowest depth the last exec reached (pop bookkeeping)
    private byte[] excStk;         // a handler's entering stack: the exception
    private boolean failed;

    RefMaps(byte[] cls, int[] cpOff, int[] cpTag, byte[] code, int nLoc, int nStk,
            int[] exStart, int[] exEnd, int[] exHandler, int exCount)
    {
        this.cls = cls;
        this.cpOff = cpOff;
        this.cpTag = cpTag;
        this.code = code;
        this.nLoc = nLoc;
        this.nStk = nStk;
        this.width = nLoc + nStk;
        this.exStart = exStart;
        this.exEnd = exEnd;
        this.exHandler = exHandler;
        this.exCount = exCount;
    }

    /** Run the dataflow to its fixpoint. False when the method is outside the model (jsr/ret, an unknown opcode,
     *  a stack deeper than {@code nStk}, depths that disagree at a join); such a method gets no map. */
    boolean solve(int descOff, boolean isStatic)
    {
        if (code.length == 0 || nStk < 1)
        {
            return false;
        }
        findHeads();
        headState = new byte[nHeads * width];
        headDepth = new int[nHeads];
        dirty = new boolean[nHeads];
        for (int h = 0; h < nHeads; h++)
        {
            headDepth[h] = -1;
        }
        loc = new byte[nLoc];
        stk = new byte[nStk];
        excStk = new byte[nStk];
        excStk[0] = REF;
        seedEntry(descOff, isStatic);
        if (failed)
        {
            return false;
        }
        dep = 0;
        merge(0, stk, 0);
        boolean any = true;
        while (any && !failed)
        {
            any = false;
            for (int h = 0; h < nHeads && !failed; h++)
            {
                if (dirty[h])
                {
                    dirty[h] = false;
                    any = true;
                    run(h);
                }
            }
        }
        return !failed;
    }

    // ----- blocks ------------------------------------------------------------------------------------------

    private void findHeads()
    {
        headOf = new int[code.length];
        for (int i = 0; i < code.length; i++)
        {
            headOf[i] = -1;
        }
        mark(0);
        for (int k = 0; k < exCount; k++)
        {
            mark(exHandler[k]);
        }
        int pc = 0;
        while (pc < code.length && !failed)
        {
            int op = code[pc] & 0xFF;
            int len = Baseline.opLen(op, code, pc);
            if ((op >= 0x99 && op <= 0xA8) || op == 0xC6 || op == 0xC7)
            {
                mark(pc + s2(pc + 1));
            }
            else if (op == 0xC8 || op == 0xC9)
            {
                mark(pc + Baseline.s4(code, pc + 1));
            }
            else if (op == 0xAA)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                mark(pc + Baseline.s4(code, p));
                int lo = Baseline.s4(code, p + 4);
                int hi = Baseline.s4(code, p + 8);
                for (int i = 0; i <= hi - lo; i++)
                {
                    mark(pc + Baseline.s4(code, p + 12 + i * 4));
                }
            }
            else if (op == 0xAB)
            {
                int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                mark(pc + Baseline.s4(code, p));
                int n = Baseline.s4(code, p + 4);
                for (int i = 0; i < n; i++)
                {
                    mark(pc + Baseline.s4(code, p + 8 + i * 8 + 4));
                }
            }
            if (endsBlock(op) && pc + len < code.length)
            {
                mark(pc + len);
            }
            pc += len;
        }
        nHeads = 0;
        for (int i = 0; i < code.length; i++)
        {
            if (headOf[i] == -2)
            {
                nHeads += 1;
            }
        }
        headPc = new int[nHeads];
        int h = 0;
        for (int i = 0; i < code.length; i++)
        {
            if (headOf[i] == -2)
            {
                headOf[i] = h;
                headPc[h] = i;
                h += 1;
            }
        }
    }

    private void mark(int pc)
    {
        if (pc < 0 || pc >= code.length)
        {
            failed = true;
            return;
        }
        headOf[pc] = -2;
    }

    private static boolean endsBlock(int op)
    {
        return (op >= 0x99 && op <= 0xB1) || op == 0xBF || (op >= 0xC6 && op <= 0xC9);
    }

    private static boolean falls(int op)
    {
        return !(op == 0xA7 || op == 0xA8 || op == 0xA9 || op == 0xAA || op == 0xAB || (op >= 0xAC && op <= 0xB1)
                || op == 0xBF || op == 0xC8 || op == 0xC9);
    }

    /** Replay block {@code h} from its entering state, merging into every successor. */
    private void run(int h)
    {
        load(h);
        int pc = headPc[h];
        while (!failed)
        {
            int op = code[pc] & 0xFF;
            int len = Baseline.opLen(op, code, pc);
            handlerEdges(pc);
            exec(pc, op);
            if (failed)
            {
                return;
            }
            if (writesLocal(op))
            {
                handlerEdges(pc);              // the handler can also see the value just stored
            }
            branchEdges(pc, op);
            if (!falls(op))
            {
                return;
            }
            pc += len;
            if (pc >= code.length)
            {
                failed = true;                 // fell off the end
                return;
            }
            if (headOf[pc] >= 0)
            {
                merge(headOf[pc], stk, dep);
                return;
            }
        }
    }

    private static boolean writesLocal(int op)
    {
        return (op >= 0x36 && op <= 0x4E) || op == 0x84 || op == 0xC4;
    }

    private void handlerEdges(int pc)
    {
        for (int k = 0; k < exCount; k++)
        {
            if (pc >= exStart[k] && pc < exEnd[k])
            {
                merge(headOf[exHandler[k]], excStk, 1);
            }
        }
    }

    private void branchEdges(int pc, int op)
    {
        if ((op >= 0x99 && op <= 0xA7) || op == 0xC6 || op == 0xC7)
        {
            merge(headOf[pc + s2(pc + 1)], stk, dep);
        }
        else if (op == 0xC8)
        {
            merge(headOf[pc + Baseline.s4(code, pc + 1)], stk, dep);
        }
        else if (op == 0xAA)
        {
            int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            merge(headOf[pc + Baseline.s4(code, p)], stk, dep);
            int lo = Baseline.s4(code, p + 4);
            int hi = Baseline.s4(code, p + 8);
            for (int i = 0; i <= hi - lo; i++)
            {
                merge(headOf[pc + Baseline.s4(code, p + 12 + i * 4)], stk, dep);
            }
        }
        else if (op == 0xAB)
        {
            int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            merge(headOf[pc + Baseline.s4(code, p)], stk, dep);
            int n = Baseline.s4(code, p + 4);
            for (int i = 0; i < n; i++)
            {
                merge(headOf[pc + Baseline.s4(code, p + 8 + i * 8 + 4)], stk, dep);
            }
        }
    }

    private void load(int h)
    {
        int base = h * width;
        for (int i = 0; i < nLoc; i++)
        {
            loc[i] = headState[base + i];
        }
        dep = headDepth[h];
        for (int i = 0; i < dep; i++)
        {
            stk[i] = headState[base + nLoc + i];
        }
    }

    /** Merge the current locals, and stack {@code s} of depth {@code d}, into block {@code h}. */
    private void merge(int h, byte[] s, int d)
    {
        if (h < 0)
        {
            failed = true;
            return;
        }
        int base = h * width;
        if (headDepth[h] < 0)
        {
            for (int i = 0; i < nLoc; i++)
            {
                headState[base + i] = loc[i];
            }
            for (int i = 0; i < d; i++)
            {
                headState[base + nLoc + i] = s[i];
            }
            headDepth[h] = d;
            dirty[h] = true;
            return;
        }
        if (headDepth[h] != d)
        {
            failed = true;
            return;
        }
        for (int i = 0; i < nLoc; i++)
        {
            byte o = headState[base + i];
            if (o != TOP && o != loc[i])
            {
                headState[base + i] = TOP;
                dirty[h] = true;
            }
        }
        for (int i = 0; i < d; i++)
        {
            byte o = headState[base + nLoc + i];
            if (o != TOP && o != s[i])
            {
                headState[base + nLoc + i] = TOP;
                dirty[h] = true;
            }
        }
    }

    // ----- the entry state ---------------------------------------------------------------------------------

    private void seedEntry(int descOff, boolean isStatic)
    {
        int slot = 0;
        if (!isStatic)
        {
            setLocal(0, REF);
            slot = 1;
        }
        int p = descOff + 2 + 1;                         // past u2 length and '('
        while (ClassReader.u1(cls, p) != ')' && !failed)
        {
            byte t = typeAt(p);
            if (t == WIDE)
            {
                setLocal(slot, WIDE);
                slot += 2;
            }
            else
            {
                setLocal(slot, t);
                slot += 1;
            }
            p = skipType(p);
        }
    }

    private void setLocal(int n, byte t)
    {
        if (n < 0 || n >= nLoc)
        {
            failed = true;
            return;
        }
        loc[n] = t;
        if (t == WIDE && n + 1 < nLoc)
        {
            loc[n + 1] = TOP;
        }
        if (n > 0 && loc[n - 1] == WIDE)
        {
            loc[n - 1] = TOP;                            // its second half was just overwritten
        }
    }

    /** Offset just past the field type at {@code p}. */
    private int skipType(int p)
    {
        while (ClassReader.u1(cls, p) == '[')
        {
            p += 1;
        }
        if (ClassReader.u1(cls, p) == 'L')
        {
            while (ClassReader.u1(cls, p) != ';')
            {
                p += 1;
            }
        }
        return p + 1;
    }

    /** The slot type of the field type at {@code p} ('V' answers TOP). */
    private byte typeAt(int p)
    {
        int c = ClassReader.u1(cls, p);
        if (c == 'J' || c == 'D')
        {
            return WIDE;
        }
        if (c == '[')
        {
            return REF;
        }
        if (c == 'V')
        {
            return TOP;
        }
        if (c != 'L')
        {
            return RAW;
        }
        long h = 0xcbf29ce484222325L;                     // Unboxed.hash over the class name
        int q = p + 1;
        while (ClassReader.u1(cls, q) != ';')
        {
            h = h ^ (ClassReader.u1(cls, q) & 0xFF);
            h = h * 0x100000001b3L;
            q += 1;
        }
        int k = Unboxed.kindOfHash(h);
        if (k == Unboxed.ADDRESS || k == Unboxed.WORD || k == Unboxed.OFFSET || k == Unboxed.EXTENT)
        {
            return RAW;
        }
        if (k == Unboxed.OBJECT_REFERENCE)
        {
            return OREF;
        }
        return REF;
    }

    private byte fieldType(int cp)
    {
        return typeAt(ClassReader.refDescOff(cls, cpOff, cp) + 2);
    }

    private byte returnType(int descOff)
    {
        int p = descOff + 2 + 1;
        while (ClassReader.u1(cls, p) != ')')
        {
            p += 1;
        }
        return typeAt(p + 1);
    }

    private byte ldcType(int ci)
    {
        int tag = cpTag[ci];
        if (tag == 3 || tag == 4)
        {
            return RAW;
        }
        if (tag == 5 || tag == 6)
        {
            return WIDE;
        }
        if (tag == 17)
        {
            return fieldType(ci);                        // a dynamic constant: its NameAndType descriptor
        }
        return REF;                                      // Class, String, MethodHandle, MethodType
    }

    // ----- the transfer function ---------------------------------------------------------------------------

    private void push(byte t)
    {
        if (dep >= nStk)
        {
            failed = true;
            return;
        }
        stk[dep] = t;
        dep += 1;
    }

    private byte pop()
    {
        if (dep <= 0)
        {
            failed = true;
            return TOP;
        }
        dep -= 1;
        if (dep < survivors)
        {
            survivors = dep;
        }
        return stk[dep];
    }

    private void popN(int n)
    {
        for (int i = 0; i < n; i++)
        {
            pop();
        }
    }

    private byte localType(int n)
    {
        if (n < 0 || n >= nLoc)
        {
            failed = true;
            return TOP;
        }
        return loc[n];
    }

    /** Apply the instruction at {@code pc} to the current state. */
    private void exec(int pc, int op)
    {
        survivors = dep;
        if (op == 0x00)
        {
            return;
        }
        if (op == 0x01)
        {
            push(REF);
            return;
        }
        if ((op >= 0x02 && op <= 0x08) || (op >= 0x0B && op <= 0x0D) || op == 0x10 || op == 0x11)
        {
            push(RAW);
            return;
        }
        if (op == 0x09 || op == 0x0A || op == 0x0E || op == 0x0F || op == 0x16 || op == 0x18)
        {
            push(WIDE);
            return;
        }
        if (op == 0x12)
        {
            push(ldcType(code[pc + 1] & 0xFF));
            return;
        }
        if (op == 0x13 || op == 0x14)
        {
            push(ldcType(s2(pc + 1) & 0xFFFF));
            return;
        }
        if (op == 0x15 || op == 0x17)
        {
            push(RAW);
            return;
        }
        if (op == 0x19)
        {
            push(localType(code[pc + 1] & 0xFF));
            return;
        }
        if (op >= 0x1A && op <= 0x2D)
        {
            int g = (op - 0x1A) / 4;
            int n = (op - 0x1A) % 4;
            if (g == 4)
            {
                push(localType(n));
            }
            else
            {
                push(g == 1 || g == 3 ? WIDE : RAW);
            }
            return;
        }
        if (op >= 0x2E && op <= 0x35)
        {
            popN(2);
            push(op == 0x2F || op == 0x31 ? WIDE : (op == 0x32 ? REF : RAW));
            return;
        }
        if (op >= 0x36 && op <= 0x3A)
        {
            store(code[pc + 1] & 0xFF, op - 0x36);
            return;
        }
        if (op >= 0x3B && op <= 0x4E)
        {
            store((op - 0x3B) % 4, (op - 0x3B) / 4);
            return;
        }
        if (op >= 0x4F && op <= 0x56)
        {
            popN(3);
            return;
        }
        if (op == 0x57)
        {
            pop();
            return;
        }
        if (op == 0x58)
        {
            if (pop() != WIDE)
            {
                pop();
            }
            return;
        }
        if (op >= 0x59 && op <= 0x5F)
        {
            stackOp(op);
            return;
        }
        if (op >= 0x60 && op <= 0x73)
        {
            int k = (op - 0x60) % 4;
            popN(2);
            push(k == 1 || k == 3 ? WIDE : RAW);
            return;
        }
        if (op >= 0x74 && op <= 0x77)
        {
            int k = (op - 0x74) % 4;
            pop();
            push(k == 1 || k == 3 ? WIDE : RAW);
            return;
        }
        if (op >= 0x78 && op <= 0x83)
        {
            popN(2);
            push((op & 1) != 0 ? WIDE : RAW);
            return;
        }
        if (op == 0x84)
        {
            setLocal(code[pc + 1] & 0xFF, RAW);
            return;
        }
        if (op >= 0x85 && op <= 0x93)
        {
            pop();
            push(op == 0x85 || op == 0x87 || op == 0x8A || op == 0x8C || op == 0x8D || op == 0x8F ? WIDE : RAW);
            return;
        }
        if (op >= 0x94 && op <= 0x98)
        {
            popN(2);
            push(RAW);
            return;
        }
        if ((op >= 0x99 && op <= 0x9E) || op == 0xAA || op == 0xAB || (op >= 0xAC && op <= 0xB0) || op == 0xB3
                || op == 0xBF || op == 0xC2 || op == 0xC3 || op == 0xC6 || op == 0xC7)
        {
            pop();
            return;
        }
        if ((op >= 0x9F && op <= 0xA6) || op == 0xB5)
        {
            popN(2);
            return;
        }
        if (op == 0xA7 || op == 0xB1 || op == 0xC8)
        {
            return;
        }
        if (op == 0xB2)
        {
            push(fieldType(s2(pc + 1) & 0xFFFF));
            return;
        }
        if (op == 0xB4)
        {
            pop();
            push(fieldType(s2(pc + 1) & 0xFFFF));
            return;
        }
        if (op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xB9 || op == 0xBA)
        {
            int cp = s2(pc + 1) & 0xFFFF;
            int descOff = ClassReader.refDescOff(cls, cpOff, cp);
            int n = ClassReader.descParamCount(cls, descOff) + (op == 0xB8 || op == 0xBA ? 0 : 1);
            popN(n);
            byte r = returnType(descOff);
            if (r != TOP)
            {
                push(r);
            }
            return;
        }
        if (op == 0xBB)
        {
            push(REF);
            return;
        }
        if (op == 0xBC || op == 0xBD)
        {
            pop();
            push(REF);
            return;
        }
        if (op == 0xBE || op == 0xC1)
        {
            pop();
            push(RAW);
            return;
        }
        if (op == 0xC0)
        {
            push(pop());
            return;
        }
        if (op == 0xC4)
        {
            wide(pc);
            return;
        }
        if (op == 0xC5)
        {
            popN(code[pc + 3] & 0xFF);
            push(REF);
            return;
        }
        failed = true;                                   // jsr, ret, jsr_w, or an opcode this model does not know
    }

    /** xstore into local {@code n}; {@code g} is 0 i, 1 l, 2 f, 3 d, 4 a. */
    private void store(int n, int g)
    {
        byte t = pop();
        if (g == 1 || g == 3)
        {
            setLocal(n, WIDE);
        }
        else if (g == 4)
        {
            setLocal(n, t);
        }
        else
        {
            setLocal(n, RAW);
        }
    }

    private void wide(int pc)
    {
        int wop = code[pc + 1] & 0xFF;
        int n = s2(pc + 2) & 0xFFFF;
        if (wop == 0x84)
        {
            setLocal(n, RAW);
        }
        else if (wop == 0x15 || wop == 0x17)
        {
            push(RAW);
        }
        else if (wop == 0x16 || wop == 0x18)
        {
            push(WIDE);
        }
        else if (wop == 0x19)
        {
            push(localType(n));
        }
        else if (wop >= 0x36 && wop <= 0x3A)
        {
            store(n, wop - 0x36);
        }
        else
        {
            failed = true;                               // wide ret
        }
    }

    /** dup, dup_x1, dup_x2, dup2, dup2_x1, dup2_x2, swap: one slot per value, so the forms follow categories. */
    private void stackOp(int op)
    {
        byte v1 = pop();
        if (op == 0x59)
        {
            push(v1);
            push(v1);
            return;
        }
        if (op == 0x5F)
        {
            byte v2 = pop();
            push(v1);
            push(v2);
            return;
        }
        if (op == 0x5A)
        {
            byte v2 = pop();
            push(v1);
            push(v2);
            push(v1);
            return;
        }
        if (op == 0x5B)
        {
            byte v2 = pop();
            if (v2 == WIDE)
            {
                push(v1);
                push(v2);
                push(v1);
                return;
            }
            byte v3 = pop();
            push(v1);
            push(v3);
            push(v2);
            push(v1);
            return;
        }
        if (op == 0x5C)
        {
            if (v1 == WIDE)
            {
                push(v1);
                push(v1);
                return;
            }
            byte v2 = pop();
            push(v2);
            push(v1);
            push(v2);
            push(v1);
            return;
        }
        if (op == 0x5D)
        {
            byte v2 = pop();
            if (v1 == WIDE)
            {
                push(v1);
                push(v2);
                push(v1);
                return;
            }
            byte v3 = pop();
            push(v2);
            push(v1);
            push(v3);
            push(v2);
            push(v1);
            return;
        }
        byte v2 = pop();                                 // dup2_x2
        if (v1 == WIDE)
        {
            if (v2 == WIDE)
            {
                push(v1);
                push(v2);
                push(v1);
                return;
            }
            byte v3 = pop();
            push(v1);
            push(v3);
            push(v2);
            push(v1);
            return;
        }
        byte v3 = pop();
        if (v3 == WIDE)
        {
            push(v2);
            push(v1);
            push(v3);
            push(v2);
            push(v1);
            return;
        }
        byte v4 = pop();
        push(v2);
        push(v1);
        push(v4);
        push(v3);
        push(v2);
        push(v1);
    }

    private int s2(int i)
    {
        return (short) (((code[i] & 0xFF) << 8) | (code[i + 1] & 0xFF));
    }

    // ----- emitting the map --------------------------------------------------------------------------------

    private static boolean isCall(int w)
    {
        return (w & 0xFC000000) == 0x94000000 || (w & 0xFFFFFC1F) == 0xD63F0000;
    }

    private static int code2(byte t)
    {
        if (t == REF)
        {
            return 1;
        }
        if (t == OREF)
        {
            return 3;
        }
        if (t == RAW || t == WIDE)
        {
            return 2;
        }
        return 0;
    }

    private int nSites;
    private int[] sRet;
    private int[] sLive;
    private int[] sIdx;
    private int[] sDepth;
    private int nUnique;
    private int[] uBits;
    private int bitWords;
    private int[] cur;             // the bytecode's entering locals and operand slots
    private int[] site;            // cur, cut to the operand slots one call really holds
    private int[] spW;
    private int[] spD;
    private int spN;
    private int spAt;              // the next spill record not yet passed

    /**
     * Append the map for the body's {@code codeWords} words to {@code cb}; answer the word index of its header,
     * or 0 when the body makes no call. {@code bcToWord} is the compiler's bytecode-to-word table; locals live at
     * {@code localsBase + slot*8} and operand slots at {@code opBase + i*8}, both from SP. {@code spillW}/{@code
     * spillD} are the compiler's spills ({word, depth}, in word order): the depth of the last spill before a call
     * in the same bytecode is how many operand slots that call's frame holds live.
     */
    int emitMap(CodeBuffer cb, int codeWords, int[] bcToWord, int localsBase, int opBase,
                int[] spillW, int[] spillD, int spillN)
    {
        bitWords = (2 * width + 31) / 32;
        nSites = 0;
        sRet = new int[16];
        sLive = new int[16];
        sIdx = new int[16];
        sDepth = new int[16];
        nUnique = 0;
        uBits = new int[bitWords * 8];
        cur = new int[bitWords];
        site = new int[bitWords];
        spW = spillW;
        spD = spillD;
        spN = spillN;
        spAt = 0;
        load(0);
        int first = bcToWord[0];
        if (hasCall(cb, 0, first))
        {
            bitsFromState(0);
            addSites(cb, 0, first, 0);
        }
        boolean live = false;
        int pc = 0;
        while (pc < code.length && !failed)
        {
            int op = code[pc] & 0xFF;
            int len = Baseline.opLen(op, code, pc);
            int h = headOf[pc];
            if (h >= 0)
            {
                live = headDepth[h] >= 0;
                if (live)
                {
                    load(h);
                }
            }
            if (live)
            {
                int w0 = bcToWord[pc];
                int w1 = pc + len < code.length ? bcToWord[pc + len] : codeWords;
                if (w0 >= 0 && w1 > w0 && hasCall(cb, w0, w1))
                {
                    bitsFromState(dep);
                    addSites(cb, w0, w1, dep);
                }
                exec(pc, op);
                if (!falls(op))
                {
                    live = false;
                }
            }
            pc += len;
        }
        if (failed || nSites == 0)
        {
            return 0;
        }
        statMethods += 1;
        statSites += nSites;
        statUniqueWords += nUnique * bitWords;
        int header = cb.emit(MAGIC);
        cb.emit(nSites);
        cb.emit(nLoc);
        cb.emit(nStk);
        cb.emit(localsBase);
        cb.emit(opBase);
        cb.emit(bitWords);
        cb.emit(nUnique);
        for (int i = 0; i < nSites; i++)
        {
            cb.emit(sRet[i]);
            cb.emit(sIdx[i] | (sLive[i] << 16) | (sDepth[i] << 24));
        }
        for (int i = 0; i < nUnique * bitWords; i++)
        {
            cb.emit(uBits[i]);
        }
        return header;
    }

    private static boolean hasCall(CodeBuffer cb, int w0, int w1)
    {
        for (int i = w0; i < w1; i++)
        {
            if (isCall(cb.get(i)))
            {
                return true;
            }
        }
        return false;
    }

    /** {@link #cur} = the current locals and operand slots {@code [0, d)}. */
    private void bitsFromState(int d)
    {
        for (int i = 0; i < bitWords; i++)
        {
            cur[i] = 0;
        }
        for (int s = 0; s < nLoc; s++)
        {
            setSlot(s, code2(loc[s]));
        }
        for (int i = 0; i < d && i < nStk; i++)
        {
            setSlot(nLoc + i, code2(stk[i]));
        }
    }

    private void setSlot(int s, int v)
    {
        int bit = 2 * s;
        cur[bit >> 5] = cur[bit >> 5] | (v << (bit & 31));
    }

    /** The depth of the last spill at or before word {@code w} and not before {@code w0}; 0 when none. */
    private int liveAt(int w0, int w)
    {
        while (spAt < spN && spW[spAt] <= w)
        {
            spAt += 1;
        }
        int k = spAt - 1;
        if (k >= 0 && spW[k] >= w0)
        {
            return spD[k];
        }
        return 0;
    }

    /**
     * One site per call word in {@code [w0, w1)} of one bytecode (entering depth {@code d0}): its
     * operand slots are the ones the last spill wrote, typed from the entering stack below {@code d0} -- the
     * slots at or above it are the lowering's transients, live but untyped, which the shadow check reports.
     */
    private void addSites(CodeBuffer cb, int w0, int w1, int d0)
    {
        for (int i = w0; i < w1; i++)
        {
            if (isCall(cb.get(i)))
            {
                int liveD = liveAt(w0, i);
                int typed = liveD < d0 ? liveD : d0;
                for (int k = 0; k < bitWords; k++)
                {
                    site[k] = cur[k];
                }
                for (int k = typed; k < nStk; k++)
                {
                    int bit = 2 * (nLoc + k);
                    site[bit >> 5] = site[bit >> 5] & ~(3 << (bit & 31));
                }
                if (nSites == sRet.length)
                {
                    sRet = grow(sRet);
                    sLive = grow(sLive);
                    sIdx = grow(sIdx);
                    sDepth = grow(sDepth);
                }
                sRet[nSites] = i + 1;
                sLive[nSites] = liveD;
                sIdx[nSites] = unique();
                sDepth[nSites] = d0;
                if (liveD > 255 || d0 > 255 || sIdx[nSites] > 0xFFFF)
                {
                    failed = true;                       // does not fit the packed site word: no map
                }
                nSites += 1;
            }
        }
    }

    /** The index of {@link #site} among the last few unique maps, adding it when it is new. */
    private int unique()
    {
        int from = nUnique > DEDUPE_WINDOW ? nUnique - DEDUPE_WINDOW : 0;
        for (int u = nUnique - 1; u >= from; u--)
        {
            boolean same = true;
            for (int i = 0; i < bitWords && same; i++)
            {
                if (uBits[u * bitWords + i] != site[i])
                {
                    same = false;
                }
            }
            if (same)
            {
                return u;
            }
        }
        if ((nUnique + 1) * bitWords > uBits.length)
        {
            int[] b = new int[uBits.length * 2 + bitWords];
            for (int i = 0; i < nUnique * bitWords; i++)
            {
                b[i] = uBits[i];
            }
            uBits = b;
        }
        for (int i = 0; i < bitWords; i++)
        {
            uBits[nUnique * bitWords + i] = site[i];
        }
        nUnique += 1;
        return nUnique - 1;
    }

    private static int[] grow(int[] a)
    {
        int[] b = new int[a.length * 2];
        for (int i = 0; i < a.length; i++)
        {
            b[i] = a[i];
        }
        return b;
    }
}
