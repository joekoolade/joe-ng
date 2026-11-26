/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import board.Uart;
import magic.Address;
import magic.DataReference;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * The objects compiled code needs the runtime to MAKE: the implicit exceptions a JIT check throws
 * ({@code NullPointerException}, {@code ArrayIndexOutOfBoundsException}, {@code ClassCastException},
 * {@code ArithmeticException}, {@code NegativeArraySizeException}, {@code ArrayStoreException}), any exception
 * named by class, and {@code multianewarray}'s nested arrays (JVMS 6.5). Named after JikesRVM's
 * {@code RuntimeEntrypoints}. Split out of {@link Loader}; it still reaches the class registry through it.
 */
final class RuntimeEntrypoints
{
    private RuntimeEntrypoints()
    {
    }

    /** Throwable's instance-field offsets, found by name once: 0 until known, -1 when absent. */
    static long thrCauseOff;
    private static long thrStackTraceOff;
    private static long thrSuppressedOff;
    private static long thrMessageOff;
    /** Slot addresses of Throwable's two sentinel statics, once found (0 until then). */
    private static long thrUnassignedSlot;
    private static long thrSentinelSlot;
    private static final byte[][] excMissed = new byte[16][];
    private static int excMissedN;

    /** True if {@code exc}'s TIB is java/lang/NullPointerException's -- the JIT's implicit null check. */
    static boolean isNpe(ObjectReference exc)
    {
        int i = ClassRegistry.classIndexByName(Magic.bytes("java/lang/NullPointerException"));
        if (i < 0 || exc.isNull())
        {
            return false;
        }
        return exc.toAddress().plus(0L).loadAddress().EQ(ClassRegistry.clTab[i].tib.toAddress());
    }

    /** Allocate a mini {@code java/lang/NullPointerException} (TIB set, field-free) — the JIT's null-check helper. */
    static long newNpe()
    {
        return newExc(Magic.bytes("java/lang/NullPointerException"));
    }

    /** Allocate a mini {@code java/lang/ArrayIndexOutOfBoundsException} — the JIT's bounds-check helper. */
    static long newAioobe()
    {
        return newExc(Magic.bytes("java/lang/ArrayIndexOutOfBoundsException"));
    }

    /**
     * The JIT bounds check's exception: an AIOOBE whose {@code detailMessage} is stock's
     * {@code "Index i out of bounds for length n"} ({@code Preconditions.outOfBoundsCheckIndex}). The implicit
     * throw used to carry NO message, so {@code getMessage()} answered null where every stock JVM names the index
     * and the length -- OobMessageProbe printed that arm's class only. Set directly at {@code Throwable}'s field,
     * as {@link #newExc} allocates without running a constructor.
     */
    static long newAioobeIndex(long index, long length)
    {
        long exc = newAioobe();
        if (exc == 0L)
        {
            return exc;
        }
        byte[] msg = new byte[64];
        int n = putAsciiAt(msg, 0, Magic.bytes("Index "));
        n = putDecAt(msg, n, (int) index);
        n = putAsciiAt(msg, n, Magic.bytes(" out of bounds for length "));
        n = putDecAt(msg, n, (int) length);
        byte[] text = new byte[n];
        int k = 0;
        while (k < n)
        {
            text[k] = msg[k];
            k += 1;
        }
        setDetailMessage(ObjectReference.fromLong(exc), text);
        return exc;
    }

    /**
     * Store a fresh guest {@code String} holding {@code ascii} as {@code exc}'s {@code Throwable.detailMessage}.
     * VM code cannot pass a message to a constructor: a string literal in {@code vm/} code compiles to a raw
     * {@code byte[]} (the {@code Magic.bytes} contract), so {@code new X("msg")} there hands the constructor a
     * byte array where a String belongs, and {@code getMessage()} answered null (MonitorStateProbe). Left unset
     * if Throwable or its field cannot be named.
     */
    static void setDetailMessage(ObjectReference exc, byte[] ascii)
    {
        if (exc.isNull())
        {
            return;
        }
        setThrowableMessage(exc, Loader.guestString(ascii));
    }

    /** Copy {@code s} into {@code out} at {@code p}; returns the new position. */
    static int putAsciiAt(byte[] out, int p, byte[] s)
    {
        int i = 0;
        while (i < s.length)
        {
            out[p] = s[i];
            p += 1;
            i += 1;
        }
        return p;
    }

    /** Write {@code v} in decimal into {@code out} at {@code p}; returns the new position. Long math: -MIN fits. */
    private static int putDecAt(byte[] out, int p, int v)
    {
        long m = v;
        if (m < 0L)
        {
            out[p] = 0x2D;
            p += 1;
            m = -m;
        }
        byte[] tmp = new byte[12];
        int n = 0;
        do
        {
            tmp[n] = (byte) (0x30 + (int) (m % 10L));
            n += 1;
            m = m / 10L;
        }
        while (m > 0L);
        while (n > 0)
        {
            n -= 1;
            out[p] = tmp[n];
            p += 1;
        }
        return p;
    }

    /**
     * Allocate a {@code java/lang/ArithmeticException} — the JIT's divide-by-zero helper — with stock's message,
     * {@code "/ by zero"}, which HotSpot gives every integer divide and remainder alike.
     */
    static long newArith()
    {
        return newExcWithMessage(Magic.bytes("java/lang/ArithmeticException"), Magic.bytes("/ by zero"));
    }

    /**
     * Allocate a {@code java/lang/ArrayStoreException} — the JIT's aastore type-mismatch helper — carrying the
     * message stock gives it: the stored value's class name, as {@code getClass().getName()} spells it
     * ({@code java.lang.String}, {@code [I}). It used to carry none, so the one fact a reader needs -- what was
     * being stored -- was missing; a value whose Type cannot be read leaves the message out rather than guess.
     *
     * <p>The JIT passes the VALUE. It used to be recorded in a static by the failing check and read back here,
     * which another core's failing store could overwrite or clear in between (ExcMessageRaceProbe).
     */
    static long newArrayStoreException(long value)
    {
        long e = newExc(Magic.bytes("java/lang/ArrayStoreException"));
        Address valueType = value == 0L ? Address.zero() : Loader.typeOfObject(ObjectReference.fromLong(value));
        if (e != 0L && !valueType.isZero() && Loader.classNameLen(valueType) > 0)   // a lambda has no name: omit
        {
            setThrowableMessage(ObjectReference.fromLong(e), Loader.classNameString(DataReference.fromAddress(valueType)));
        }
        return e;
    }

    /**
     * A {@code java/lang/<name>} exception whose detail message is {@code msg} (left null when {@code msg} is
     * null) -- for a VM native that must throw with stock's text, {@link ArrayCopy} first.
     */
    static long newExcWithMessage(byte[] name, byte[] msg)
    {
        long e = newExc(name);
        if (e != 0L && msg != null)
        {
            setDetailMessage(ObjectReference.fromLong(e), msg);
        }
        return e;
    }

    /**
     * Allocate a mini {@code java/lang/NegativeArraySizeException} — the JIT's newarray/anewarray/multianewarray
     * length helper — whose message is the length in decimal, as stock's is ({@code "-1"}). It used to carry
     * none. The class is already force-loaded by {@link Loader#pullSupportClasses} and flagged instantiated, so this
     * needs no seeding of its own.
     */
    static long newNase(long length)
    {
        long exc = newExc(Magic.bytes("java/lang/NegativeArraySizeException"));
        if (exc == 0L)
        {
            return exc;
        }
        byte[] msg = new byte[12];
        int n = putDecAt(msg, 0, (int) length);
        byte[] text = new byte[n];
        int k = 0;
        while (k < n)
        {
            text[k] = msg[k];
            k += 1;
        }
        setDetailMessage(ObjectReference.fromLong(exc), text);
        return exc;
    }

    /** Element size in bytes for the component named by the descriptor char after a '[': a reference
     *  ({@code '['} or {@code 'L'}) is a pointer, everything else is a primitive of its own width. */
    private static int descElemSize(int c)
    {
        if (c == 0x5B || c == 0x4C)
        {
            return ObjectModel.WORD;                     // '[' or 'L': the elements are references
        }
        int atype = Primitives.atypeForDescChar(c);
        return atype < 0 ? ObjectModel.WORD : arrayElemSizeOfAtype(atype);
    }

    /** Bytes per element for a {@code newarray} atype (JVMS Table 6.5.newarray-A). */
    static int arrayElemSizeOfAtype(int atype)
    {
        if (atype == 4 || atype == 8) { return 1; }      // boolean, byte
        if (atype == 5 || atype == 9) { return 2; }      // char, short
        if (atype == 6 || atype == 10) { return 4; }     // float, int
        return 8;                                        // double, long
    }

    /**
     * {@code multianewarray}: build the first {@code dims} levels of the array type named by the descriptor
     * at {@code desc} ({@code {u2 len}{bytes}}), with {@code c0..c3} as the per-level counts.
     *
     * <p>THE DESCRIPTOR DRIVES EVERYTHING, which is what makes the awkward form fall out instead of needing a
     * case: {@code dims} may be FEWER than the type's rank ({@code new short[a][b][]} is {@code [[[S} with
     * dims 2), and then the innermost level allocated is an array OF REFERENCES whose elements stay null.
     * Stripping one '[' per level gives both the level's own TIB and its element size, and the size question
     * answers itself -- a level whose next char is '[' or 'L' holds pointers, anything else a primitive.
     *
     * <p>The counts are NOT range-checked here: {@code Baseline} emits a {@code NegativeArraySizeException}
     * check on every one of them BEFORE the call, which is both the JVMS order (all dimensions are checked
     * before anything is allocated) and the only way to throw from the caller's frame rather than this one.
     */
    static long multiNewArray(long desc, int dims, int c0, int c1, int c2, int c3)
    {
        Address d = Address.fromLong(desc);              // VM.multiNewArray's raw word: the descriptor's address
        int n = u2(d);                                   // descriptor length, then its bytes at desc+2
        return multiLevel(d, 2, n, dims, 0, c0, c1, c2, c3);
    }

    private static long multiLevel(Address desc, int off, int n, int dims, int level,
                                   int c0, int c1, int c2, int c3)
    {
        int cnt = level == 0 ? c0 : level == 1 ? c1 : level == 2 ? c2 : c3;
        ObjectReference arr = Heap.allocArray(cnt, descElemSize(u1(desc.plus(off + 1)))).toObjectReference();
        Address tib = ArrayTypes.arrayTibOfDescN(desc, off, n);
        if (!tib.isZero())
        {
            arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(tib);   // else leave the raw element size, as tagArray does
        }
        if (level + 1 < dims)
        {
            int k = 0;
            while (k < cnt)
            {
                long sub = multiLevel(desc, off + 1, n - 1, dims, level + 1, c0, c1, c2, c3);
                WriteBarrier.putElem(arr.toAddress(), k, sub);  // arr may be old by now: promoted, or born large (S7-4d''')
                k += 1;
            }
        }
        return arr.toLong();
    }

    /**
     * A {@code ClassCastException} that SAYS WHAT COULD NOT BE CAST TO WHAT, as stock does.
     *
     * <p>A bare ClassCastException names neither side, so the only way to find out is to disassemble the
     * method and work out which {@code checkcast} it was -- which is exactly what the console launcher's
     * failure in {@code EngineExecutionOrchestrator} cost. The JIT passes the object and the target Type; with
     * no target the message is simply omitted, so this can never turn a cast failure into a different failure.
     *
     * <p>They used to be recorded in statics by {@code castOk}'s failing path and read back here, which another
     * core's failing cast could overwrite or clear in between (ExcMessageRaceProbe).
     */
    static long newCce(ObjectReference ref, DataReference targetType)
    {
        long e = newExc(Magic.bytes("java/lang/ClassCastException"));
        if (e != 0L && !ref.isNull() && !targetType.isZero())
        {
            long tib = ref.toAddress().loadLong();
            setThrowableMessage(ObjectReference.fromLong(e), Loader.guestString(castFailureMessage(tib, Loader.typeOfObject(ref), targetType.toAddress())));
        }
        return e;
    }

    /** "class a.B cannot be cast to class c.D", or null when either Type cannot be named. */
    private static byte[] castFailureMessage(long fromTib, Address fromType, Address toType)
    {
        byte[] out = new byte[512];
        int p = Loader.putUtf8Bytes(out, 0, Magic.bytes("class "));
        p = Loader.putTypeName(out, p, fromType, fromTib);
        p = Loader.putUtf8Bytes(out, p, Magic.bytes(" cannot be cast to class "));
        p = Loader.putTypeName(out, p, toType, 0L);
        byte[] exact = new byte[p];
        int q = 0;
        while (q < p)
        {
            exact[q] = out[q];
            q += 1;
        }
        return exact;
    }

    /** Append {@code v} as 0x-prefixed hex -- a measured address, for when a name is not available. */
    static int putHex(byte[] out, int p, long v)
    {
        p = Loader.putUtf8Bytes(out, p, Magic.bytes("0x"));
        int sh = 60;
        boolean lead = true;
        while (sh >= 0)
        {
            int d = (int) ((v >>> sh) & 0xFL);
            if (d != 0 || !lead || sh == 0)
            {
                lead = false;
                if (p < out.length)
                {
                    out[p] = (byte) (d < 10 ? '0' + d : 'a' + d - 10);
                    p += 1;
                }
            }
            sh -= 4;
        }
        return p;
    }

    /** Allocate a mini {@code java/lang/InternalError} — the fault handler's catch-all for an unexpected trap. */
    static long newInternalError()
    {
        return newExc(Magic.bytes("java/lang/InternalError"));
    }

    /**
     * Allocate a loaded exception class by name: header + fields, with its TIB stored so {@code catch}
     * dispatch can walk its Type chain. No constructor runs — the mini exceptions are field-free, and the
     * JIT synthesises these (there is no {@code new} bytecode to invoke {@code <init>}).
     */
    static long newExc(byte[] name)
    {
        int i = ClassRegistry.classIndexByName(name);
        if (i < 0)
        {
            // NAME IT. A missing exception class yields a bare header with TIB 0: no Type, so `instanceOf`
            // cannot walk it (uncatchable), `getClass()` answers null, and it has no detailMessage slot --
            // an exception carrying NOTHING, reported by library code as an identity hash. Silent until now,
            // and indistinguishable at the catch site from an exception that simply says little.
            excMissing(name);
        }
        DataReference tib = i >= 0 ? ClassRegistry.clTab[i].tib : DataReference.zero();
        ObjectReference objRef = Heap.alloc(i >= 0 ? (16 + ClassRegistry.clTab[i].fieldCount * 8) : 16).toObjectReference();
        long obj = objRef.toLong();                  // a raw copy; objRef keeps the block alive
        Address.fromLong(obj).plus(0L).store(tib);
        if (i >= 0)
        {
            initVmThrowable(ObjectReference.fromLong(obj));
        }
        return obj;
    }

    static long throwableFieldOff(long cached, byte[] name)
    {
        if (cached != 0L || ClassRegistry.clTab == null)
        {
            return cached;
        }
        int ti = ClassRegistry.classIndexByName(Magic.bytes("java/lang/Throwable"));
        return ti >= 0 ? FieldRegistry.instanceFieldOffset(ti, name) : 0L;    // -1 if absent: cached by the caller
    }

    /**
     * Give a VM-allocated exception the state stock's field initializers give a constructed one. No constructor
     * runs ({@link #newExc}), so {@code cause = this}, {@code stackTrace = UNASSIGNED_STACK} and
     * {@code suppressedExceptions = SUPPRESSED_SENTINEL} were never set: stock's {@code initCause} then throws
     * IllegalStateException ("Can't overwrite cause") and {@code addSuppressed} drops every exception -- on an
     * implicit NPE, a try-with-resources whose close() also throws lost the close() failure. The sentinels are
     * Throwable's statics and are read once Throwable has initialized; until then those two fields stay null,
     * which stock reads as suppression disabled. {@code backtrace = this} marks the exception for
     * {@code VMUnwind.captureTrace}, which fills its trace at the throw.
     */
    private static void initVmThrowable(ObjectReference exc)
    {
        thrCauseOff = throwableFieldOff(thrCauseOff, Magic.bytes("cause"));
        thrStackTraceOff = throwableFieldOff(thrStackTraceOff, Magic.bytes("stackTrace"));
        thrSuppressedOff = throwableFieldOff(thrSuppressedOff, Magic.bytes("suppressedExceptions"));
        long btOff = StackTrace.throwableBacktraceOff();
        if (btOff > 0L)
        {
            exc.toAddress().plus(btOff).store(exc);
        }
        if (thrCauseOff > 0L)
        {
            exc.toAddress().plus(thrCauseOff).store(exc);
        }
        if (thrUnassignedSlot == 0L)
        {
            thrUnassignedSlot = Statics.staticSlotOf(Magic.bytes("java/lang/Throwable"), Magic.bytes("UNASSIGNED_STACK"));
        }
        if (thrSentinelSlot == 0L)
        {
            thrSentinelSlot = Statics.staticSlotOf(Magic.bytes("java/lang/Throwable"), Magic.bytes("SUPPRESSED_SENTINEL"));
        }
        if (thrStackTraceOff > 0L && thrUnassignedSlot != 0L)
        {
            exc.toAddress().plus(thrStackTraceOff).store(Address.fromLong(thrUnassignedSlot).loadLong());
        }
        if (thrSuppressedOff > 0L && thrSentinelSlot != 0L)
        {
            exc.toAddress().plus(thrSuppressedOff).store(Address.fromLong(thrSentinelSlot).loadLong());
        }
    }

    /** {@code exc}'s {@code Throwable.detailMessage} (a guest String reference), or 0. */
    static long throwableMessage(ObjectReference exc)
    {
        thrMessageOff = throwableFieldOff(thrMessageOff, Magic.bytes("detailMessage"));
        return thrMessageOff > 0L ? exc.toAddress().plus(thrMessageOff).loadLong() : 0L;
    }

    /** Store the guest String {@code msg} as {@code exc}'s {@code Throwable.detailMessage}. */
    private static void setThrowableMessage(ObjectReference exc, long msg)
    {
        thrMessageOff = throwableFieldOff(thrMessageOff, Magic.bytes("detailMessage"));
        if (thrMessageOff > 0L)
        {
            WriteBarrier.putRef(exc.toAddress(), thrMessageOff, msg);   // msg was allocated after exc
        }
    }

    /** Report an exception class the VM had to throw but could not find, once per name. */
    private static void excMissing(byte[] name)
    {
        int k = 0;
        while (k < excMissedN)
        {
            if (excMissed[k] == name)
            {
                return;
            }
            k += 1;
        }
        if (excMissedN < excMissed.length)
        {
            excMissed[excMissedN] = name;
            excMissedN += 1;
        }
        Uart.write(Magic.bytes("\n  EXCEPTION CLASS NOT LOADED (thrown object has no Type): "));
        Uart.write(name);
        Uart.putc(0x0A);
    }
}
