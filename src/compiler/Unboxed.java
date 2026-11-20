/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-15
 */
package compiler;

/**
 * Which {@link Intrinsics} id a method of an UNBOXED type -- {@code magic.Address}, {@code magic.Word},
 * {@code magic.Offset}, {@code magic.Extent}, {@code magic.ObjectReference} -- lowers to. ONE copy of the rules, called by every world's
 * {@link Symbols} (the seed writer and the metal JIT each hand over their own view of the owner, name and
 * descriptor), so the two can never map a method differently.
 *
 * <p>The rules key on the method NAME and the shape of its descriptor, not on a table of every overload:
 * {@code plus(int)}, {@code plus(long)}, {@code plus(Offset)} are all one add, because every value lives in one
 * 64-bit register and an int arrives sign-extended. A load or store with an {@code Offset} argument is the
 * {@code _OFF} form. Loads and stores without one reuse {@code Magic}'s own ids, so converted code is the same
 * machine code as the {@code Magic.load*}/{@code store*} it replaces.
 *
 * <p>Names are matched by FNV-1a hash -- this class runs on the metal too, where a String switch is not available.
 * {@code UnboxedTest} recomputes every constant and checks that every public method of the types classifies.
 */
public final class Unboxed
{
    private Unboxed()
    {
    }

    /** A UTF-8 name as the caller holds it: a host String, or bytes in a classfile on the metal. */
    public interface Utf8
    {
        int length();

        int byteAt(int i);
    }

    /** {@link #ownerKind}: not an unboxed type. */
    public static final int NONE = 0;

    public static final int ADDRESS = 1;

    public static final int WORD = 2;

    public static final int OFFSET = 3;

    public static final int EXTENT = 4;

    /** {@code magic/AddressArray}: a {@code long[]} at run time; its ops are the array bytecodes' lowerings. */
    public static final int ADDRESS_ARRAY = 5;

    /** {@code magic/ObjectReference}: an object's address as a raw word; its ops are moves and compares. */
    public static final int OBJECT_REFERENCE = 6;

    /** {@code magic/ObjectReferenceArray}: a {@code long[]} at run time, lowered exactly as {@link #ADDRESS_ARRAY}. */
    public static final int OBJECT_REFERENCE_ARRAY = 7;

    private static final long O_ADDRESS = 7816296518595377735L; // "magic/Address"
    private static final long O_WORD = 2502753236447134729L;  // "magic/Word"
    private static final long O_OFFSET = -5453143413838101434L; // "magic/Offset"
    private static final long O_EXTENT = -5385282434997603451L; // "magic/Extent"
    private static final long O_ADDRESS_ARRAY = -2760638129083984252L; // "magic/AddressArray"
    private static final long O_OBJECT_REFERENCE = -5959478901542360285L; // "magic/ObjectReference"
    private static final long O_OBJECT_REFERENCE_ARRAY = -3793534773957816616L; // "magic/ObjectReferenceArray"
    private static final long N_CREATE = -626457835795418115L; // "create"
    private static final long N_GET = -3106811833996379657L;  // "get"
    private static final long N_SET = -9062501282150740445L;  // "set"
    private static final long N_LENGTH = -1580386065683472715L; // "length"
    private static final long N_ZERO = 2461754175573238659L;  // "zero"
    private static final long N_ONE = 1875936269717626031L;   // "one"
    private static final long N_MAX = 580772045163143161L;    // "max"
    private static final long N_FROM_LONG = -2337336603467960285L; // "fromLong"
    private static final long N_FROM_INT_SIGN_EXTEND = -2728128197532351725L; // "fromIntSignExtend"
    private static final long N_FROM_INT_ZERO_EXTEND = -6540996148938465196L; // "fromIntZeroExtend"
    private static final long N_FROM_OBJECT = -683916174055950582L; // "fromObject"
    private static final long N_TO_OBJECT = -8478613555224475821L; // "toObject"
    private static final long N_TO_LONG = 6089913159063583250L; // "toLong"
    private static final long N_TO_INT = 3078381310305587645L; // "toInt"
    private static final long N_TO_WORD = -3548672568172380628L; // "toWord"
    private static final long N_TO_ADDRESS = 3340780673232283588L; // "toAddress"
    private static final long N_TO_OFFSET = -4944248131659823137L; // "toOffset"
    private static final long N_TO_EXTENT = -4864762652956595076L; // "toExtent"
    private static final long N_TO_OBJECT_REFERENCE = -1624544305862165288L; // "toObjectReference"
    private static final long N_NULL_REFERENCE = 87203128043989017L; // "nullReference"
    private static final long N_IS_NULL = 2193725045587114196L; // "isNull"
    private static final long N_IS_ZERO = -3454911920138359469L; // "isZero"
    private static final long N_IS_MAX = -6807841772394724727L; // "isMax"
    private static final long N_PLUS = -6503099946484209483L; // "plus"
    private static final long N_MINUS = -9126922452879442769L; // "minus"
    private static final long N_DIFF = -3892017830806024102L; // "diff"
    private static final long N_AND = -1803806992022637946L;  // "and"
    private static final long N_OR = 626092740288339108L;     // "or"
    private static final long N_XOR = -4635978518811437506L;  // "xor"
    private static final long N_NOT = 2403468754648211274L;   // "not"
    private static final long N_LSH = 1338805048274651008L;   // "lsh"
    private static final long N_RSHL = -693907150306466618L;  // "rshl"
    private static final long N_RSHA = -693892856655299875L;  // "rsha"
    private static final long N_EQ = 647211060127224835L;     // "EQ"
    private static final long N_NE = 657577255755916668L;     // "NE"
    private static final long N_LT = 655682797220887565L;     // "LT"
    private static final long N_LE = 655664105523207978L;     // "LE"
    private static final long N_GT = 649118712801792470L;     // "GT"
    private static final long N_GE = 649102220127369305L;     // "GE"
    private static final long N_S_LT = -9025291609636203558L; // "sLT"
    private static final long N_S_LE = -9025308102310626723L; // "sLE"
    private static final long N_S_GT = -9033804028660110095L; // "sGT"
    private static final long N_S_GE = -9033787535985686930L; // "sGE"
    private static final long N_LOAD_BYTE = -8941170378885795213L; // "loadByte"
    private static final long N_LOAD_UNSIGNED_BYTE = 1076040628777129072L; // "loadUnsignedByte"
    private static final long N_LOAD_CHAR = -7553091970669530703L; // "loadChar"
    private static final long N_LOAD_SHORT = 7447887881088282465L; // "loadShort"
    private static final long N_LOAD_INT = 3977252922930089562L; // "loadInt"
    private static final long N_LOAD_LONG = 4361257629773139175L; // "loadLong"
    private static final long N_LOAD_ADDRESS = -2245672408505575529L; // "loadAddress"
    private static final long N_LOAD_WORD = 3647669981763934105L; // "loadWord"
    private static final long N_LOAD_OBJECT_REFERENCE = 4613837479075637683L; // "loadObjectReference"
    private static final long N_STORE = 4967000543033605518L; // "store"
    private static final long N_ATTEMPT = 7198468255150783982L; // "attempt"

    /** FNV-1a, 64-bit. */
    public static long hash(Utf8 s)
    {
        long h = 0xcbf29ce484222325L;
        int n = s.length();
        for (int i = 0; i < n; i++)
        {
            h = h ^ (s.byteAt(i) & 0xFF);
            h = h * 0x100000001b3L;
        }
        return h;
    }

    /** Which unboxed type an internal class name (e.g. {@code magic/Address}) is, or {@link #NONE}. */
    public static int ownerKind(Utf8 owner)
    {
        return kindOfHash(hash(owner));
    }

    /** {@link #ownerKind} from the name's {@link #hash} -- for a caller holding the bytes in a class file. */
    public static int kindOfHash(long h)
    {
        if (h == O_ADDRESS)
        {
            return ADDRESS;
        }
        if (h == O_WORD)
        {
            return WORD;
        }
        if (h == O_OFFSET)
        {
            return OFFSET;
        }
        if (h == O_EXTENT)
        {
            return EXTENT;
        }
        if (h == O_ADDRESS_ARRAY)
        {
            return ADDRESS_ARRAY;
        }
        if (h == O_OBJECT_REFERENCE)
        {
            return OBJECT_REFERENCE;
        }
        if (h == O_OBJECT_REFERENCE_ARRAY)
        {
            return OBJECT_REFERENCE_ARRAY;
        }
        return NONE;
    }

    /**
     * The {@link Intrinsics} id of method {@code name}{@code desc} of unboxed type {@code kind}, or -1 for a method
     * these rules do not know (the caller fails loudly: a missing lowering must not become a call to a body that
     * throws).
     */
    public static int op(int kind, Utf8 name, Utf8 desc)
    {
        long h = hash(name);
        int params = paramCount(desc);
        if (kind == ADDRESS_ARRAY || kind == OBJECT_REFERENCE_ARRAY)
        {
            return arrayOp(h, params);              // both ARE a long[]: the same four array-bytecode lowerings
        }
        if (kind == OBJECT_REFERENCE)
        {
            return referenceOp(h);
        }
        if (h == N_FROM_LONG || h == N_TO_LONG || h == N_TO_WORD || h == N_TO_ADDRESS || h == N_TO_OFFSET
                || h == N_TO_EXTENT || h == N_TO_OBJECT_REFERENCE)
        {
            return Intrinsics.UB_MOVE;
        }
        if (h == N_FROM_INT_SIGN_EXTEND)
        {
            return Intrinsics.UB_SXTW;              // an overflowed int is not sign-extended in its register
        }
        if (h == N_FROM_OBJECT)
        {
            return Intrinsics.ADDR_OF;
        }
        if (h == N_TO_OBJECT)
        {
            return Intrinsics.FROM_ADDR;
        }
        if (h == N_FROM_INT_ZERO_EXTEND)
        {
            return Intrinsics.UB_ZEXT32;
        }
        if (h == N_TO_INT)
        {
            return Intrinsics.UB_SXTW;
        }
        if (h == N_ZERO)
        {
            return Intrinsics.UB_ZERO;
        }
        if (h == N_ONE)
        {
            return Intrinsics.UB_ONE;
        }
        if (h == N_MAX)
        {
            return kind == OFFSET ? Intrinsics.UB_SMAX : Intrinsics.UB_MAX;
        }
        if (h == N_IS_ZERO)
        {
            return Intrinsics.UB_IS_ZERO;
        }
        if (h == N_IS_MAX)
        {
            return kind == OFFSET ? Intrinsics.UB_IS_SMAX : Intrinsics.UB_IS_MAX;
        }
        if (h == N_PLUS)
        {
            return Intrinsics.UB_ADD;
        }
        if (h == N_MINUS || h == N_DIFF)
        {
            return Intrinsics.UB_SUB;
        }
        if (h == N_AND)
        {
            return Intrinsics.UB_AND;
        }
        if (h == N_OR)
        {
            return Intrinsics.UB_OR;
        }
        if (h == N_XOR)
        {
            return Intrinsics.UB_XOR;
        }
        if (h == N_NOT)
        {
            return Intrinsics.UB_NOT;
        }
        if (h == N_LSH)
        {
            return Intrinsics.UB_LSL;
        }
        if (h == N_RSHL)
        {
            return Intrinsics.UB_LSR;
        }
        if (h == N_RSHA)
        {
            return Intrinsics.UB_ASR;
        }
        int cmp = compare(h);
        if (cmp >= 0)
        {
            return cmp;
        }
        int load = load(h, params);
        if (load >= 0)
        {
            return load;
        }
        if (h == N_STORE)
        {
            return store(firstParam(desc), params);
        }
        if (h == N_ATTEMPT)
        {
            return params == 2 ? Intrinsics.CAS64 : -1;
        }
        return -1;
    }

    /**
     * {@code magic/ObjectReference}'s methods, or -1. Its own table, not the shared rules: a reference has no
     * arithmetic, no ordering and no loads -- field access goes through {@code toAddress()} -- so an inherited
     * {@code equals}/{@code hashCode} or anything else not listed here fails the compile instead of lowering.
     */
    private static int referenceOp(long h)
    {
        if (h == N_FROM_LONG || h == N_TO_LONG || h == N_TO_ADDRESS)
        {
            return Intrinsics.UB_MOVE;
        }
        if (h == N_FROM_OBJECT)
        {
            return Intrinsics.ADDR_OF;
        }
        if (h == N_TO_OBJECT)
        {
            return Intrinsics.FROM_ADDR;
        }
        if (h == N_NULL_REFERENCE)
        {
            return Intrinsics.UB_ZERO;
        }
        if (h == N_IS_NULL)
        {
            return Intrinsics.UB_IS_ZERO;
        }
        if (h == N_EQ)
        {
            return Intrinsics.UB_EQ;
        }
        if (h == N_NE)
        {
            return Intrinsics.UB_NE;
        }
        return -1;
    }

    /** {@code magic/AddressArray}'s and {@code magic/ObjectReferenceArray}'s four methods, or -1. */
    private static int arrayOp(long h, int params)
    {
        if (h == N_CREATE && params == 1)
        {
            return Intrinsics.AA_NEW;
        }
        if (h == N_GET && params == 1)
        {
            return Intrinsics.AA_GET;
        }
        if (h == N_SET && params == 2)
        {
            return Intrinsics.AA_SET;
        }
        if (h == N_LENGTH && params == 0)
        {
            return Intrinsics.AA_LENGTH;
        }
        return -1;
    }

    private static int compare(long h)
    {
        if (h == N_EQ)
        {
            return Intrinsics.UB_EQ;
        }
        if (h == N_NE)
        {
            return Intrinsics.UB_NE;
        }
        if (h == N_LT)
        {
            return Intrinsics.UB_LO;
        }
        if (h == N_LE)
        {
            return Intrinsics.UB_LS;
        }
        if (h == N_GT)
        {
            return Intrinsics.UB_HI;
        }
        if (h == N_GE)
        {
            return Intrinsics.UB_HS;
        }
        if (h == N_S_LT)
        {
            return Intrinsics.UB_SLT;
        }
        if (h == N_S_LE)
        {
            return Intrinsics.UB_SLE;
        }
        if (h == N_S_GT)
        {
            return Intrinsics.UB_SGT;
        }
        if (h == N_S_GE)
        {
            return Intrinsics.UB_SGE;
        }
        return -1;
    }

    /** A load: the {@code Magic} id when it has no Offset argument, else the {@code _OFF} form. */
    private static int load(long h, int params)
    {
        boolean off = params == 1;
        if (h == N_LOAD_BYTE)
        {
            return off ? Intrinsics.UB_LD8S_OFF : Intrinsics.UB_LD8S;
        }
        if (h == N_LOAD_UNSIGNED_BYTE)
        {
            return off ? Intrinsics.UB_LD8U_OFF : Intrinsics.LOAD8;
        }
        if (h == N_LOAD_CHAR)
        {
            return off ? Intrinsics.UB_LD16U_OFF : Intrinsics.UB_LD16U;
        }
        if (h == N_LOAD_SHORT)
        {
            return off ? Intrinsics.UB_LD16S_OFF : Intrinsics.UB_LD16S;
        }
        if (h == N_LOAD_INT)
        {
            return off ? Intrinsics.UB_LD32_OFF : Intrinsics.LOAD32;
        }
        if (h == N_LOAD_LONG || h == N_LOAD_ADDRESS || h == N_LOAD_WORD || h == N_LOAD_OBJECT_REFERENCE)
        {
            return off ? Intrinsics.UB_LD64_OFF : Intrinsics.LOAD64;
        }
        return -1;
    }

    /** A store of a value whose descriptor starts with {@code first}; params == 2 is the Offset form. */
    private static int store(int first, int params)
    {
        boolean off = params == 2;
        if (first == 'Z' || first == 'B')
        {
            return off ? Intrinsics.UB_ST8_OFF : Intrinsics.STORE8;
        }
        if (first == 'C' || first == 'S')
        {
            return off ? Intrinsics.UB_ST16_OFF : Intrinsics.UB_ST16;
        }
        if (first == 'I')
        {
            return off ? Intrinsics.UB_ST32_OFF : Intrinsics.STORE32;
        }
        if (first == 'J' || first == 'L')
        {
            return off ? Intrinsics.UB_ST64_OFF : Intrinsics.STORE64;
        }
        return -1;
    }

    /** The number of parameters in a method descriptor (each counted once, long and double included). */
    static int paramCount(Utf8 desc)
    {
        int n = 0;
        int i = 1;                                      // after '('
        int len = desc.length();
        while (i < len && desc.byteAt(i) != ')')
        {
            int c = desc.byteAt(i);
            while (c == '[')
            {
                i = i + 1;
                c = desc.byteAt(i);
            }
            if (c == 'L')
            {
                while (desc.byteAt(i) != ';')
                {
                    i = i + 1;
                }
            }
            i = i + 1;
            n = n + 1;
        }
        return n;
    }

    /** The first character of the first parameter's descriptor, or 0 when there is none. */
    static int firstParam(Utf8 desc)
    {
        return desc.length() > 1 && desc.byteAt(1) != ')' ? desc.byteAt(1) : 0;
    }
}
