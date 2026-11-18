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

import magic.Address;
import magic.Magic;
import magic.ObjectReference;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * A record's {@code hashCode}/{@code equals}/{@code toString}, SYNTHESISED (JDK: {@code java.lang.runtime.ObjectMethods}).
 *
 * <p>javac compiles a record's hashCode/equals/toString to an {@code invokedynamic} bootstrapped by
 * java/lang/runtime/ObjectMethods, which hands back a MethodHandle. They are synthesised here instead, which is the
 * same choice already made for a lambda: recognise the bootstrap and BUILD THE BEHAVIOUR, rather than run the
 * bootstrap.
 *
 * <p>THE COMPONENTS ARE THE CLASS'S OWN INSTANCE FIELDS, and that is a GUARANTEE, not a convenience: JLS 8.10.3
 * forbids a record declaring any instance field other than the private final ones corresponding to its components.
 * So no filtering is needed, and declaration order IS component order -- which is why nothing per-site is baked
 * here. Everything is read from the object at call time.
 *
 * <p>WHAT IS SPECIFIED AND WHAT IS NOT, because the three differ and it decides how exact each must be:
 * {@code Record.equals} is fully specified (same record type, every component equal) and is exact here.
 * {@code Record.hashCode} explicitly leaves its algorithm unspecified, so this need only be a consistent function of
 * the component hashes -- equal records hash equally because it is derived from them alone. {@code Record.toString}'s
 * exact format is likewise unspecified; this matches the standard rendering. Split out of {@link Loader}.
 */
final class ObjectMethods
{
    private ObjectMethods()
    {
    }

    /** Cap on the rendered length of one record's toString, so the buffer below stays a plain local. */
    private static final int MAXRECSTR = 1024;

    /** {@code fldTab} index of the {@code want}-th instance field DECLARED by class {@code reg}, or -1. */
    private static int recordComponent(int reg, int want)
    {
        int seen = 0;
        int j = 0;
        while (j < FieldRegistry.fldCount)
        {
            if (FieldRegistry.fldTab[j] != null
                    && Loader.utf8EqAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff, FieldRegistry.fldTab[j].base, FieldRegistry.fldTab[j].classOff))
            {
                if (seen == want)
                {
                    return j;
                }
                seen += 1;
            }
            j += 1;
        }
        return -1;
    }

    /** How many components (declared instance fields) class {@code reg} has. */
    private static int recordComponentCount(int reg)
    {
        int n = 0;
        int j = 0;
        while (j < FieldRegistry.fldCount)
        {
            if (FieldRegistry.fldTab[j] != null
                    && Loader.utf8EqAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff, FieldRegistry.fldTab[j].base, FieldRegistry.fldTab[j].classOff))
            {
                n += 1;
            }
            j += 1;
        }
        return n;
    }

    /**
     * Call {@code name desc} on the guest object {@code obj}, with {@code obj} as the receiver and
     * optionally one further argument. 0 when the method cannot be resolved -- the caller decides what an
     * unanswerable component means rather than this inventing a value.
     */
    private static long callOnObject(ObjectReference obj, byte[] name, byte[] desc, long arg1, int argc)
    {
        long code = resolveOn(obj, name, desc);
        if (code == 0L)
        {
            return 0L;
        }
        long[] slots = new long[2];                     // fresh per call: a component's own equals/hashCode
        Address argBase = ObjectReference.fromObject(slots).toAddress().plus(24L);  // may re-enter this for a nested record
        argBase.store(obj);
        if (argc > 1)
        {
            argBase.plus(8L).store(arg1);
        }
        return Magic.callN(code, argBase.toLong());
    }

    /** The compiled body of {@code name desc} for {@code obj}'s class, or 0 (null, a raw array, unresolvable). */
    private static long resolveOn(ObjectReference obj, byte[] name, byte[] desc)
    {
        int reg = ClassRegistry.regOfObject(obj);
        if (reg < 0)
        {
            return 0L;
        }
        return Loader.resolveLinkTarget(ClassRegistry.clTab[reg].base.plus(ClassRegistry.clTab[reg].nameOff), Loader.utf8Blob(name),
                Loader.utf8Blob(desc));
    }

    /**
     * {@code obj.toString()} on an arbitrary guest object, as a raw String/byte[] reference -- the call
     * {@code String.valueOf(Object)} makes, reached the way {@link #recordToString} already reaches a
     * component's own {@code toString}.
     *
     * <p>THE TWO FAILURE ANSWERS ARE KEPT APART, and that is the whole reason this is not just
     * {@link #callOnObject}. {@code 0} is a {@code toString} that RETURNED null, which JLS 15.18.1 renders
     * as the word "null"; {@code -1} is one that could not be RESOLVED, which is a gap in this VM. One
     * sentinel for both would print a plausible "null" exactly where the dispatch is the broken thing --
     * the shape this project pays for most.
     */
    static long objToString(ObjectReference obj)
    {
        long code = resolveOn(obj, Magic.bytes("toString"), Magic.bytes("()Ljava/lang/String;"));
        if (code == 0L)
        {
            return -1L;
        }
        long[] slots = new long[2];                     // fresh per call: the callee may re-enter this
        long argBase = Magic.addrOf(slots) + 24L;
        Address.fromLong(argBase).store(obj);
        return Magic.callN(code, argBase);
    }

    /** The hash of one component, by its descriptor's first character. */
    private static int componentHash(long v, int tc)
    {
        if (tc == 'Z')
        {
            return v != 0L ? 1231 : 1237;               // Boolean.hashCode's specified values
        }
        if (tc == 'J' || tc == 'D')
        {
            return (int) (v ^ (v >>> 32));              // Long.hashCode; D uses its raw bits
        }
        if (tc == 'L' || tc == '[')
        {
            return v == 0L ? 0 : (int) callOnObject(ObjectReference.fromLong(v), Magic.bytes("hashCode"), Magic.bytes("()I"), 0L, 1);
        }
        return (int) v;                                 // B S C I, and F by its raw bits
    }

    /** {@code Record.hashCode}: a fold over the components. See the note above on why 31 is free to choose. */
    static long recordHash(ObjectReference obj)
    {
        int reg = ClassRegistry.regOfObject(obj);
        if (reg < 0)
        {
            return 0L;
        }
        int n = recordComponentCount(reg);
        int h = 0;
        int i = 0;
        while (i < n)
        {
            int j = recordComponent(reg, i);
            long v = obj.toAddress().plus(16L).plus(FieldRegistry.fldTab[j].slot * 8L).loadLong();
            h = h * 31 + componentHash(v, u1(FieldRegistry.fldTab[j].base.plus(FieldRegistry.fldTab[j].descOff + 2L)));
            i += 1;
        }
        return h;
    }

    /** {@code Record.equals}: same record class, and every component equal. Fully specified, so exact. */
    static long recordEquals(ObjectReference a, ObjectReference b)
    {
        if (a.EQ(b))
        {
            return 1L;                                  // includes both null
        }
        if (a.isNull() || b.isNull())
        {
            return 0L;
        }
        int ra = ClassRegistry.regOfObject(a);
        int rb = ClassRegistry.regOfObject(b);
        if (ra < 0 || ra != rb)
        {
            return 0L;                                  // a different class is never equal to a record
        }
        int n = recordComponentCount(ra);
        int i = 0;
        while (i < n)
        {
            int j = recordComponent(ra, i);
            long off = 16L + FieldRegistry.fldTab[j].slot * 8L;
            long va = a.toAddress().plus(off).loadLong();
            long vb = b.toAddress().plus(off).loadLong();
            int tc = u1(FieldRegistry.fldTab[j].base.plus(FieldRegistry.fldTab[j].descOff + 2L));
            if (tc == 'L' || tc == '[')
            {
                if (va != vb)                           // reference components compare with equals()
                {
                    if (va == 0L || vb == 0L)
                    {
                        return 0L;
                    }
                    if (callOnObject(ObjectReference.fromLong(va), Magic.bytes("equals"), Magic.bytes("(Ljava/lang/Object;)Z"), vb, 2) == 0L)
                    {
                        return 0L;
                    }
                }
            }
            else if (va != vb)
            {
                return 0L;                              // primitives compare by value
            }
            i += 1;
        }
        return 1L;
    }

    /** Append one component's rendered VALUE; returns the new position. */
    private static int putComponent(char[] out, int p, long v, int tc)
    {
        if (tc == 'Z')
        {
            return Loader.putAscii(out, p, v != 0L ? Magic.bytes("true") : Magic.bytes("false"));
        }
        if (tc == 'C')
        {
            // ALL SIXTEEN BITS. This used to store one byte into a byte[] buffer, so the whole rendering
            // could only carry code points 0..255 and a char above that came out as a DIFFERENT character --
            // '\u20ac' rendered as '\u00ac'. A plausible wrong character is the shape rule 2 exists to remove.
            if (p < out.length)
            {
                out[p] = (char) v;
                p += 1;
            }
            return p;
        }
        if (tc == 'L' || tc == '[')
        {
            if (v == 0L)
            {
                return Loader.putAscii(out, p, Magic.bytes("null"));
            }
            long str = callOnObject(ObjectReference.fromLong(v), Magic.bytes("toString"), Magic.bytes("()Ljava/lang/String;"), 0L, 1);
            return Loader.putGuestString(out, p, ObjectReference.fromLong(str));
        }
        // F AND D GO THROUGH THE REAL FORMATTER, because this VM keeps a float or a double in an ORDINARY
        // 64-BIT SLOT -- so {@code v} IS {@code Double.doubleToRawLongBits(d)}, and letting it fall through
        // to putDec below printed a nineteen-digit integer where stock prints the number. It is also how the
        // 2026-09-28 bare-minus defect reached a double: {@code doubleToRawLongBits(-0.0)} is
        // {@code 0x8000000000000000}, which IS {@code Long.MIN_VALUE}.
        //
        // RESOLVED BY NAME AT RUNTIME rather than formatted here, which is the whole design decision.
        // {@code Double.toString} is the SHORTEST decimal that round-trips (Schubfach), and re-deriving that
        // by hand is a silent wrong answer waiting to happen -- it would get ordinary values right and NaN,
        // the infinities and the signed zeros wrong. {@code VMConcat.scDouble} already reaches the stock
        // formatter through these same two accessors; this is the same route from the record path.
        //
        // AND A FLOAT IS NOT A WIDENED DOUBLE, which is why there are two accessors and not one:
        // {@code Float.toString(0.1f)} is "0.1" where {@code Double.toString((double) 0.1f)} is
        // "0.10000000149011612". The slot holds a float's raw int SIGN-EXTENDED, so the low 32 bits are
        // masked out before the call -- exactly what {@code VMConcat.scFloat} does.
        if (tc == 'D' || tc == 'F')
        {
            long buf = tc == 'D' ? Loader.doubleToStringBuf() : Loader.floatToStringBuf();
            if (buf == 0L)
            {
                return Loader.putAscii(out, p, Magic.bytes("?"));
            }
            long bits = tc == 'D' ? v : (v & 0xFFFFFFFFL);
            return Loader.putGuestString(out, p, ObjectReference.fromLong(Magic.call2(buf, bits, 0L)));
        }
        return Loader.putDec(out, p, v, true);                  // B S I J
    }

    /**
     * {@code Record.toString}: {@code Simple[a=1, b=x]}. The exact format is unspecified by
     * {@code Record.toString()}; this is the standard rendering, and the SIMPLE name is used (the part after
     * the last '/' or '$'), as stock does.
     */
    static long recordToString(ObjectReference obj)
    {
        int reg = ClassRegistry.regOfObject(obj);
        if (reg < 0)
        {
            return Loader.guestString(Magic.bytes("null"));
        }
        // A UTF-16 CODE-UNIT buffer, not bytes. It was a byte[] wrapped as a LATIN1 String at the end, so
        // the whole rendering could only carry code points 0..255 however each component was appended.
        char[] out = new char[MAXRECSTR];
        int p = 0;
        Address nm = ClassRegistry.clTab[reg].base.plus(ClassRegistry.clTab[reg].nameOff);  // the binary name, as a Utf8
        int nlen = u2(nm);
        int start = 0;
        int i = 0;
        while (i < nlen)
        {
            int c = u1(nm.plus(2L + i));
            if (c == '/' || c == '$')
            {
                start = i + 1;                           // keep only the simple name
            }
            i += 1;
        }
        // The separator scan above is over RAW BYTES and stays correct under UTF-8: '/' and '$' are below
        // 0x80, and a continuation byte is 0xxx in 0x80..0xBF, so neither can occur inside a multi-byte
        // sequence. Only the copy below has to decode.
        i = start;
        while (i < nlen && p < out.length)
        {
            Loader.decodeUtf8At(nm.plus(2L), i, nlen);
            out[p] = (char) Loader.decCh;
            p += 1;
            i = Loader.decNext;
        }
        p = Loader.putAscii(out, p, Magic.bytes("["));
        int n = recordComponentCount(reg);
        int k = 0;
        while (k < n)
        {
            if (k > 0)
            {
                p = Loader.putAscii(out, p, Magic.bytes(", "));
            }
            int j = recordComponent(reg, k);
            p = Loader.putUtf8(out, p, FieldRegistry.fldTab[j].base.plus(FieldRegistry.fldTab[j].nameOff));
            p = Loader.putAscii(out, p, Magic.bytes("="));
            p = putComponent(out, p, obj.toAddress().plus(16L).plus(FieldRegistry.fldTab[j].slot * 8L).loadLong(),
                    u1(FieldRegistry.fldTab[j].base.plus(FieldRegistry.fldTab[j].descOff + 2L)));
            k += 1;
        }
        p = Loader.putAscii(out, p, Magic.bytes("]"));
        return Loader.guestStringChars(out, p);
    }
}
