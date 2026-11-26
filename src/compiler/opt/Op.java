/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-26
 */
package compiler.opt;

/**
 * The IR's instruction opcodes and their static properties. An instruction's {@code aux} (a long) and
 * {@code aux2} (an int) carry what the opcode needs: a constant's bits, a constant-pool index, a bytecode
 * opcode, a condition.
 */
public final class Op
{
    // ----- values -----
    /** A constant: aux = its bits (an int sign-extended, a float's raw bits zero-extended). */
    public static final int CONST = 0;
    /** The null reference. */
    public static final int NULL = 1;
    /** The method's parameter aux (receiver = 0), as the caller passed it. */
    public static final int PARAM = 2;
    /** ldc of a String, Class, MethodType, MethodHandle or dynamic constant: aux = cp index. */
    public static final int LDC = 3;
    /** Merge at a block head; in[i] comes from block.preds[i]. */
    public static final int PHI = 4;
    /** A handler's exception (the first instruction of a handler block). */
    public static final int CATCH = 5;
    /** Load a bytecode local's home frame slot: aux = slot. src = the values stored there at covered points. */
    public static final int LOAD_LOCAL = 6;
    /** Store in[0] to bytecode local aux's home frame slot. */
    public static final int STORE_LOCAL = 7;

    // ----- arithmetic: in[0] op in[1] (shifts: in[1] is an int count) -----
    public static final int ADD = 8;
    public static final int SUB = 9;
    public static final int MUL = 10;
    /** Integer divide/remainder throw ArithmeticException on a zero divisor. */
    public static final int DIV = 11;
    public static final int REM = 12;
    public static final int NEG = 13;
    public static final int SHL = 14;
    public static final int SHR = 15;
    public static final int USHR = 16;
    public static final int AND = 17;
    public static final int OR = 18;
    public static final int XOR = 19;
    /** lcmp, fcmpl, fcmpg, dcmpl, dcmpg: aux2 = the bytecode opcode. */
    public static final int CMP = 20;
    /** i2l .. i2s: aux2 = the bytecode opcode. */
    public static final int CONV = 21;

    // ----- memory and objects -----
    /** aux = Fieldref cp index. in[0] = object. */
    public static final int GETFIELD = 22;
    /** aux = Fieldref cp index. in = object, value. */
    public static final int PUTFIELD = 23;
    public static final int GETSTATIC = 24;
    /** in[0] = value. */
    public static final int PUTSTATIC = 25;
    /** aux2 = the xaload bytecode opcode. in = array, index. */
    public static final int ALOAD = 26;
    /** aux2 = the xastore bytecode opcode. in = array, index, value. */
    public static final int ASTORE = 27;
    public static final int ARRAYLENGTH = 28;
    /** aux = Class cp index. */
    public static final int NEW = 29;
    /** aux2 = atype. in[0] = length. */
    public static final int NEWARRAY = 30;
    /** aux = Class cp index of the element. in[0] = length. */
    public static final int ANEWARRAY = 31;
    /** aux = Class cp index of the array type, in = the dimensions. */
    public static final int MULTIANEWARRAY = 32;
    /** aux = Class cp index. in[0] = object; the result is the object. */
    public static final int CHECKCAST = 33;
    public static final int INSTANCEOF = 34;
    public static final int MONITORENTER = 35;
    public static final int MONITOREXIT = 36;
    /** aux = cp index (Methodref, InterfaceMethodref or InvokeDynamic), aux2 = the invoke bytecode opcode; in = the
     *  receiver (if any) then the arguments. */
    public static final int INVOKE = 37;

    // ----- terminators -----
    public static final int GOTO = 38;
    /** aux2 = the if bytecode opcode; in = one operand (compared with zero or null) or two. succs[0] = taken,
     *  succs[1] = fall-through. */
    public static final int IF = 39;
    /** keys = the case values; succs[0] = default, succs[1 + i] = case keys[i]. in[0] = the selector. */
    public static final int SWITCH = 40;
    /** in = the value, or nothing for a void return. */
    public static final int RETURN = 41;
    public static final int THROW = 42;

    public static final int COUNT = 43;

    private static final String[] NAMES = {
        "const", "null", "param", "ldc", "phi", "catch", "loadlocal", "storelocal",
        "add", "sub", "mul", "div", "rem", "neg", "shl", "shr", "ushr", "and", "or", "xor", "cmp", "conv",
        "getfield", "putfield", "getstatic", "putstatic", "aload", "astore", "arraylength",
        "new", "newarray", "anewarray", "multianewarray", "checkcast", "instanceof",
        "monitorenter", "monitorexit", "invoke",
        "goto", "if", "switch", "return", "throw",
    };

    private Op()
    {
    }

    public static String name(int op)
    {
        return NAMES[op];
    }

    public static boolean isTerminator(int op)
    {
        return op >= GOTO;
    }

    /** Whether the instruction can throw (and so carries a framestate). Integer DIV/REM are decided by kind. */
    public static boolean canThrow(int op, int kind)
    {
        if (op == DIV || op == REM)
        {
            return kind == Kind.I || kind == Kind.J;
        }
        return (op >= GETFIELD && op <= INVOKE) || op == THROW || op == LDC;
    }

    /** Whether the instruction must stay where it is and survive even if its value is unused. */
    public static boolean pinned(int op)
    {
        return op == STORE_LOCAL || op == PUTFIELD || op == PUTSTATIC || op == ASTORE || op == MONITORENTER
               || op == MONITOREXIT || op == INVOKE || op == CATCH || op == PARAM || isTerminator(op);
    }
}
