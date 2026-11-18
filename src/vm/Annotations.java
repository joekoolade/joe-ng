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

import board.bcm2711.Uart;
import magic.Address;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * Runtime annotations read out of the classfile: which annotations a class, method, field or parameter carries,
 * and the {@code RuntimeVisibleAnnotations} element_value walking that finds them (JikesRVM: {@code
 * AnnotatedElement}). The objects themselves are built by {@link AnnotationInstances}.
 *
 * <p>Two levels. {@code methodAnnoPresent} is marker-level -- "does this method carry @Foo" -- and is enough for
 * discovery (@Test/@BeforeEach/@Disabled). {@code methodAnnotation}/{@code classAnnotation} build a real INSTANCE
 * whose element methods return the values; see {@code AnnotationInstances.annoTibFor}. No Proxy runtime was needed:
 * an annotation object has the same shape as a synthesised lambda.
 *
 * <p>Only RuntimeVISIBLEAnnotations count. javac emits RuntimeINVISIBLEAnnotations unless the annotation type itself
 * is declared @Retention(RUNTIME) -- so an annotation without that is not merely unreadable here, it is absent from
 * the classfile entirely. Split out of {@link Loader}.
 */
final class Annotations
{
    private Annotations()
    {
    }

    /** Cap on annotations enumerated per class -- a classfile may declare any number; nothing real declares
     *  more than a handful, and a fixed cap keeps the two passes below on plain locals. */
    private static final int MAXDECLANNO = 32;
    private static long[] annoMissSeen;
    private static int annoMissSeenN;

    /** True if the method registered at {@code rgIndex} carries the annotation whose descriptor is the
     *  {@code descLen} bytes at the {@code byte[]} payload {@code descArr} (e.g. "Lorg/junit/jupiter/api/Test;"). */
    static boolean methodAnnoPresent(int rgIndex, ObjectReference descArr, int descLen)
    {
        Address p = ClassAttributes.methodInfoPos(rgIndex);
        if (p.isZero())
        {
            return false;
        }
        return annoInAttrs(Reflection.reflEntry(rgIndex).base, p.plus(8), u2(p.plus(6)), descArr, descLen);
    }

    /**
     * {@code Method.getAnnotation0}: the annotation instance for descriptor {@code descArr} carried by the
     * method registered at {@code rgIndex}, or 0 if it is absent (or its interface is not loaded, since
     * without the interface there is no itable to give the object).
     */
    static long methodAnnotation(int rgIndex, ObjectReference descArr, int descLen)
    {
        Address p = ClassAttributes.methodInfoPos(rgIndex);
        if (p.isZero())
        {
            return 0L;
        }
        return annoFromAttrs(Reflection.reflEntry(rgIndex).base, p.plus(8), u2(p.plus(6)), descArr, descLen);
    }

    /**
     * {@code Method.annoDefaults0}: an instance of annotation type {@code mirror} with NO element written, so
     * that every element carries its {@code AnnotationDefault} -- built by {@link AnnotationInstances#buildAnnoObject} like any
     * annotation, from an empty element-value-pair list. An element with no default reads null/0, which is why
     * {@code Method.getDefaultValue} asks {@link ClassAttributes#methodParamMeta} for the attribute first. 0 when the type is
     * not a loaded class.
     */
    static long annoDefaultInstance(ObjectReference mirror)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int reg = ClassRegistry.regOfType(mirror.toAddress().plus(16L).loadAddress());
        if (reg < 0)
        {
            return 0L;
        }
        Address zero = Heap.allocArray(2, 1);                    // u2 num_element_value_pairs = 0, zeroed by the allocator
        return AnnotationInstances.buildAnnoObject(ClassRegistry.clTab[reg].base, zero.plus(ObjectModel.ARRAY_BASE_OFFSET), reg);
    }

    /**
     * {@code Executable.paramAnnoRow0}: row {@code k} of the method's {@code RuntimeVisibleParameterAnnotations}
     * as a fresh {@code Annotation[]}, or 0 when the attribute is absent or {@code k} is out of range. Rows
     * are numbered as the ATTRIBUTE numbers them, which is not always the descriptor's numbering (see
     * {@link ClassAttributes#methodParamMeta}); {@code Executable.getParameterAnnotations} does stock's realignment.
     */
    static long methodParamAnnoRow(int rgIndex, long k)
    {
        Address p = ClassAttributes.methodInfoPos(rgIndex);
        if (p.isZero())
        {
            return 0L;
        }
        Address base = Reflection.reflEntry(rgIndex).base;
        Address q = ClassAttributes.classAttrNamed(base, p.plus(8L), u2(p.plus(6L)), Magic.bytes("RuntimeVisibleParameterAnnotations"));
        if (q.isZero() || k < 0L || k >= u1(q))
        {
            return 0L;
        }
        Address lp = q.plus(1L);                                // u1 num_parameters, then one annotation list per row
        long r = 0L;
        while (r < k)
        {
            int n = u2(lp);
            lp = lp.plus(2L);
            int i = 0;
            while (i < n)
            {
                lp = skipElementPairs(lp.plus(2L));          // u2 type_index, then the element-value pairs
                i += 1;
            }
            r += 1L;
        }
        return annotationsAt(base, lp);
    }

    /**
     * {@code Field.annoGet0}: the annotation instance for {@code descArr} on the field named {@code nameArr}
     * of the class whose mirror is given, or 0.
     *
     * <p>Field annotations are how a library declares CONFIGURATION: picocli's `@Option`/`@Parameters` live on
     * fields, and without this its command spec has no options at all -- the launcher printed
     * "Unknown options" for every argument and a usage block listing none.
     */
    /**
     * Every annotation DECLARED on one instance field -> {@code Annotation[]}, or 0 for a bad argument.
     *
     * <p>The field-level twin of {@link #methodAnnotationsAll}, and it keeps that method's TWO PASSES OVER
     * ABSOLUTE ADDRESSES for the reason recorded there: pass 2 RESOLVES, a resolve may demand-load, and a
     * load re-parses a blob and moves the {@code gView.cp} cursor pass 1 reads. Blob addresses survive that; parse
     * state does not.
     *
     * <p>The field WALK is {@link #fieldAnnotation}'s (past access_flags/this/super/interfaces, then the
     * field_info list), so the two agree on where a field's attributes live by construction rather than by
     * two copies of the same arithmetic.
     *
     * <p>ALLOCATION-FREE for a bad argument, because {@code VM.forceCompile} probes this native with zeros on
     * every image during loader init -- every sibling probe there returns before allocating.
     *
     * <p>JUnit's {@code AnnotationUtils.findAnnotation} walks this to reach META-ANNOTATIONS, and walks it
     * UNGUARDED, so an un-annotated field must answer an EMPTY array rather than null.
     */
    static long fieldAnnotationsAll(ObjectReference mirror, ObjectReference nameArr)
    {
        if (mirror.toLong() <= 0x1000L || nameArr.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int ci = ClassRegistry.classRegByType(mirror.toAddress().plus(16L).loadAddress());
        if (ci < 0)
        {
            return 0L;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseConstPool(base, Loader.blobLenOf(base));
        Address p = Loader.gp.plus(6L);                               // past access_flags, this_class, super_class
        p = p.plus(2L + u2(p) * 2L);                           // past the interfaces list
        int fcount = u2(p);
        p = p.plus(2);
        int nlen = (int) nameArr.toAddress().plus(16L).loadLong();
        Address nbase = nameArr.toAddress().plus(24L);
        Address lp = Address.zero();
        int f = 0;
        while (f < fcount)
        {
            int attrs = u2(p.plus(6));
            if (Utf8.rawEqUtf8(nbase, nlen, base, Loader.gView.cp[u2(p.plus(2))]))
            {
                lp = annoListPos(base, p.plus(8L), attrs);
                f = fcount;                             // found it; stop walking
            }
            else
            {
                p = Loader.skipAttributes(p.plus(8), attrs);
                f += 1;
            }
        }
        if (lp.isZero())
        {
            return emptyAnnoArray();                    // no such field, or none declared on it
        }
        return annotationsAt(base, lp);
    }

    static long fieldAnnotation(ObjectReference mirror, ObjectReference nameArr, ObjectReference descArr, int descLen)
    {
        if (mirror.toLong() <= 0x1000L || nameArr.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int ci = ClassRegistry.classRegByType(mirror.toAddress().plus(16L).loadAddress());
        if (ci < 0)
        {
            return 0L;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseConstPool(base, Loader.blobLenOf(base));
        Address p = Loader.gp.plus(6L);                               // past access_flags, this_class, super_class
        p = p.plus(2L + u2(p) * 2L);                           // past the interfaces list
        int fcount = u2(p);
        p = p.plus(2);
        int nlen = (int) nameArr.toAddress().plus(16L).loadLong();   // byte[] length @16
        Address nbase = nameArr.toAddress().plus(24L);
        int f = 0;
        while (f < fcount)
        {
            int attrs = u2(p.plus(6));
            if (Utf8.rawEqUtf8(nbase, nlen, base, Loader.gView.cp[u2(p.plus(2))]))
            {
                return annoFromAttrs(base, p.plus(8), attrs, descArr, descLen);
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            f += 1;
        }
        return 0L;
    }

    /** {@code Class.getAnnotation0}: the same, for the annotations on a CLASS (its own attribute list). */
    static long classAnnotation(ObjectReference mirror, ObjectReference descArr, int descLen)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int reg = ClassRegistry.regOfType(mirror.toAddress().plus(16L).loadAddress());
        if (reg < 0)
        {
            return 0L;
        }
        Address base = ClassRegistry.clTab[reg].base;
        Address p = ClassAttributes.classAttrsPos(base);
        if (p.isZero())
        {
            return 0L;
        }
        return annoFromAttrs(base, p.plus(2L), u2(p), descArr, descLen);
    }

    /** Find RuntimeVisibleAnnotations in an attribute list and build the matching annotation's instance. */
    private static long annoFromAttrs(Address base, Address p, int attrs, ObjectReference descArr, int descLen)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p = p.plus(2);
            int alen = u4(p);
            p = p.plus(4);
            if (Utf8.utf8IsAtBase(base, Loader.gView.cp[anIdx], Magic.bytes("RuntimeVisibleAnnotations")))
            {
                Address pairs = annoListFind(base, p, descArr, descLen);
                if (pairs.isZero())
                {
                    return 0L;
                }
                int ifaceReg = regOfAnnoDesc(descArr, descLen);
                if (ifaceReg < 0)
                {
                    reportAnnoIfaceMissing(descArr, descLen);
                    return 0L;
                }
                return AnnotationInstances.buildAnnoObject(base, pairs, ifaceReg);
            }
            p = p.plus(alen);
            a += 1;
        }
        return 0L;
    }

    /**
     * Every annotation DECLARED on this class, as instances, in a fresh {@code Annotation[]}.
     *
     * <p>Fresh each call because stock specifies the caller may modify the returned array, and because
     * nothing here caches annotation instances (see the note on identity in {@code Class.getAnnotation}).
     *
     * <p>TWO PASSES, over ABSOLUTE addresses. Pass 1 records each entry's type descriptor and element-pair
     * position as addresses into the blob; pass 2 builds. That split is not tidiness: {@code buildAnnoObject}
     * reads {@code gView.cp[nameIdx]}, the parse CURSOR, and anything that resolves during a build re-parses a
     * blob and moves it. Blob addresses do not move; the parse state does -- the same reason the written-pair
     * and default phases are kept apart, and the reason a 'c'/'e' element defers.
     *
     * <p>An annotation whose INTERFACE is not loaded is skipped, and {@code buildAnnoObject}'s existing report
     * names it: there is no itable to give such an instance, so the alternative is a null element, which
     * reads to the caller as an annotation that is present but broken.
     */
    static long classAnnotationsAll(ObjectReference mirror)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int reg = ClassRegistry.regOfType(mirror.toAddress().plus(16L).loadAddress());
        if (reg < 0)
        {
            return emptyAnnoArray();
        }
        Address base = ClassRegistry.clTab[reg].base;
        Address ap = ClassAttributes.classAttrsPos(base);
        if (ap.isZero())
        {
            return emptyAnnoArray();
        }
        Address lp = annoListPos(base, ap.plus(2L), u2(ap));
        if (lp.isZero())
        {
            return emptyAnnoArray();                    // no RuntimeVisibleAnnotations: none declared
        }
        return annotationsAt(base, lp);
    }

    /**
     * {@code Executable.annoAll0}: every annotation DECLARED on the method registered at {@code rgIndex}, as an
     * {@code Annotation[]}. Never 0; a method with none answers a zero-length array.
     *
     * <p>The method-level twin of {@link #classAnnotationsAll}, and it keeps that method's TWO PASSES OVER
     * ABSOLUTE ADDRESSES for the same non-negotiable reason: {@code buildAnnoObject} reads {@code gView.cp[..]},
     * the parse CURSOR, and anything that resolves during a build re-parses a blob and moves it. Blob
     * addresses survive that; the parse state does not. Pass 1 therefore records addresses only -- and
     * {@code methodInfoPos} must run BEFORE pass 1 for the same reason, since it parses too.
     *
     * <p>The only difference from the class version is WHERE the attributes live: a method's own
     * {@code attributes} rather than the class's. There is no inheritance to consider -- JLS 9.6.4.3 gives
     * {@code @Inherited} effect on class declarations ALONE, and an overriding method does not inherit the
     * annotations of the method it overrides -- so for a method "declared" and "present" coincide exactly,
     * which is why {@code getAnnotations} may share this and diverge from stock nowhere.
     */
    static long methodAnnotationsAll(int rgIndex)
    {
        if (Reflection.reflEntry(rgIndex) == null)
        {
            return 0L;                                  // no such registration; the guest answers an empty
        }                                               // array. ALLOCATION-FREE, because VM.forceCompile
        Address mp = ClassAttributes.methodInfoPos(rgIndex);               // probes this native with rgIndex -1 during boot.
        if (mp.isZero())
        {
            return emptyAnnoArray();                    // registered but not declared here: none to read
        }
        Address base = Reflection.reflEntry(rgIndex).base;
        Address lp = annoListPos(base, mp.plus(8L), u2(mp.plus(6)));
        if (lp.isZero())
        {
            return emptyAnnoArray();                    // no RuntimeVisibleAnnotations: none declared
        }
        return annotationsAt(base, lp);
    }

    /**
     * The two passes shared by the class, field, method and parameter readers: every annotation in the list at {@code lp}
     * ({@code u2 num_annotations} first, then the entries, as both {@code RuntimeVisibleAnnotations} and each
     * row of {@code RuntimeVisibleParameterAnnotations} lay it out), built into a fresh {@code Annotation[]}.
     * See {@link #methodAnnotationsAll} for why pass 1 records blob addresses only.
     */
    static long annotationsAt(Address base, Address lp)
    {
        AddressArray descAbs = AddressArray.create(MAXDECLANNO);
        AddressArray pairsAbs = AddressArray.create(MAXDECLANNO);
        int n = u2(lp);
        Address p = lp.plus(2L);
        int found = 0;
        int i = 0;
        while (i < n && found < MAXDECLANNO)             // PASS 1: addresses only, nothing resolved or built
        {
            int typeIdx = u2(p);
            p = p.plus(2);
            descAbs.set(found, base.plus(Loader.gView.cp[typeIdx]));
            pairsAbs.set(found, p);
            found += 1;
            p = skipElementPairs(p);
            i += 1;
        }
        long[] objs = new long[found];                   // PASS 2: resolve + build
        int built = 0;
        int k = 0;
        while (k < found)
        {
            int ifaceReg = loadAnnoTypeByDescAbs(descAbs.get(k));
            if (ifaceReg >= 0)
            {
                // RE-POINT THE CURSOR at the annotated class first. buildAnnoObject matches element names through
                // gView.cp, and building the PREVIOUS annotation re-parsed its annotation type's blob to read the
                // defaults (as resolving one may), so without this every annotation after the first in a list
                // matched no written element and answered its defaults: `@Mark @Num(3)` read `@Num(7)`.
                Loader.parseForMethods(base, Loader.blobLenOf(base));
                long obj = AnnotationInstances.buildAnnoObject(base, pairsAbs.get(k), ifaceReg);
                if (obj != 0L)
                {
                    objs[built] = obj;
                    built += 1;
                }
            }
            k += 1;
        }
        Address arr = Heap.allocArray(built, 8);
        arr.plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.refArrayTib(annotationTypeAddr()));
        int j = 0;
        while (j < built)
        {
            arr.plus(ObjectModel.ARRAY_BASE_OFFSET).plus(j * 8L).store(objs[j]);
            j += 1;
        }
        return arr.toLong();
    }

    /** A zero-length {@code Annotation[]} -- what a class with no declared annotations answers. */
    static long emptyAnnoArray()
    {
        Address arr = Heap.allocArray(0, 8);
        arr.plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.refArrayTib(annotationTypeAddr()));
        return arr.toLong();
    }

    /** {@code java/lang/annotation/Annotation}'s Type, or Object's if it is not loaded. */
    private static Address annotationTypeAddr()
    {
        int i = ClassRegistry.classIndexByName(Magic.bytes("java/lang/annotation/Annotation"));
        return i >= 0 ? ClassRegistry.clTab[i].type : TibBuilder.objectTypeAddr();
    }

    /** Position of the {@code RuntimeVisibleAnnotations} list ({@code u2 count} first), or 0 if absent. */
    static Address annoListPos(Address base, Address p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p = p.plus(2);
            int alen = u4(p);
            p = p.plus(4);
            if (Utf8.utf8IsAtBase(base, Loader.gView.cp[anIdx], Magic.bytes("RuntimeVisibleAnnotations")))
            {
                return p;
            }
            p = p.plus(alen);
            a += 1;
        }
        return Address.zero();
    }

    /**
     * As {@link #regOfAnnoDescAbs}, LOADING the annotation type when nothing has yet -- an annotation whose
     * type no class literal, cast or call names was left out of every reader, silently: a class carrying
     * {@code @OnClass} answered {@code getDeclaredAnnotations() == []} (AnnoLoadProbe). The same demand-load
     * {@code annoClassValue} does for a {@code Class} element. Callers re-point the parse cursor after it.
     */
    static int loadAnnoTypeByDescAbs(Address utf8Addr)
    {
        int reg = regOfAnnoDescAbs(utf8Addr);
        if (reg >= 0)
        {
            return reg;
        }
        int len = u2(utf8Addr);
        if (len < 3 || u1(utf8Addr.plus(2)) != 0x4C)        // not "L...;"
        {
            return -1;
        }
        byte[] name = new byte[len - 2];
        int i = 0;
        while (i < len - 2)
        {
            name[i] = (byte) u1(utf8Addr.plus(2 + 1 + i));
            i += 1;
        }
        Loader.loadClassIncremental(name);
        return ClassRegistry.classIndexByName(name);
    }

    /** As {@link #regOfAnnoDesc}, for a descriptor at an ABSOLUTE length-prefixed Utf8 address. */
    private static int regOfAnnoDescAbs(Address utf8Addr)
    {
        int len = u2(utf8Addr);
        if (len < 3)
        {
            return -1;
        }
        byte[] name = new byte[len - 2];                // strip the leading 'L' and the trailing ';'
        int i = 0;
        while (i < len - 2)
        {
            name[i] = (byte) u1(utf8Addr.plus(2 + 1 + i));
            i += 1;
        }
        return ClassRegistry.classIndexByName(name);
    }

    /** {@code "Lcom/x/Foo;"} -> the class-registry index of {@code com/x/Foo}, or -1 if it is not loaded. */
    private static int regOfAnnoDesc(ObjectReference descArr, int descLen)
    {
        if (descLen < 3)
        {
            return -1;
        }
        byte[] name = new byte[descLen - 2];            // strip the leading 'L' and the trailing ';'
        int i = 0;
        while (i < descLen - 2)
        {
            name[i] = (byte) u1(descArr.toAddress().plus(24 + 1 + i));  // byte[] elements start at +24
            i += 1;
        }
        return ClassRegistry.classIndexByName(name);
    }

    /**
     * Name an annotation whose INTERFACE is not loaded. Without it there is no itable to give the instance, so
     * the answer is null -- and a silent null here reads as "the annotation is absent", which is a different
     * and wrong statement. Reported once per descriptor.
     */
    private static void reportAnnoIfaceMissing(ObjectReference descArr, int descLen)
    {
        if (annoMissSeen == null)
        {
            annoMissSeen = new long[32];
            annoMissSeenN = 0;
        }
        int k = 0;
        while (k < annoMissSeenN)
        {
            if (annoMissSeen[k] == descArr.toLong())
            {
                return;
            }
            k += 1;
        }
        if (annoMissSeenN < 32)
        {
            annoMissSeen[annoMissSeenN] = descArr.toLong();
            annoMissSeenN += 1;
        }
        Uart.write(Magic.bytes("\n  ANNOTATION IFACE NOT LOADED (reads null): "));
        int i = 0;
        while (i < descLen)
        {
            Uart.putc((byte) u1(descArr.toAddress().plus(24 + i)));
            i += 1;
        }
        Uart.putc(0x0A);
    }

    /** Scan an attribute list for RuntimeVisibleAnnotations and look for the wanted descriptor in it. */
    private static boolean annoInAttrs(Address base, Address p, int attrs, ObjectReference descArr, int descLen)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p = p.plus(2);
            int alen = u4(p);
            p = p.plus(4);
            if (Utf8.utf8IsAtBase(base, Loader.gView.cp[anIdx], Magic.bytes("RuntimeVisibleAnnotations")))
            {
                return annoListHas(base, p, descArr, descLen);
            }
            p = p.plus(alen);
            a += 1;
        }
        return false;
    }

    /** {@code { u2 num_annotations; annotation[] }} -- true if any annotation's type descriptor matches. */
    private static boolean annoListHas(Address base, Address p, ObjectReference descArr, int descLen)
    {
        int n = u2(p);
        p = p.plus(2);
        int i = 0;
        while (i < n)
        {
            int typeIdx = u2(p);
            p = p.plus(2);
            if (Utf8.utf8EqArr(base, Loader.gView.cp[typeIdx], descArr, descLen))
            {
                return true;
            }
            p = skipElementPairs(p);                     // must skip precisely: a later annotation may be the one
            i += 1;
        }
        return false;
    }

    /** The position of the element_value_pairs of the annotation matching {@code descArr}, or 0. */
    private static Address annoListFind(Address base, Address p, ObjectReference descArr, int descLen)
    {
        int n = u2(p);
        p = p.plus(2);
        int i = 0;
        while (i < n)
        {
            int typeIdx = u2(p);
            p = p.plus(2);
            if (Utf8.utf8EqArr(base, Loader.gView.cp[typeIdx], descArr, descLen))
            {
                return p;
            }
            p = skipElementPairs(p);
            i += 1;
        }
        return Address.zero();
    }

    /** {@code { u2 num_pairs; { u2 name_index; element_value }[] }} -> the position after it. */
    private static Address skipElementPairs(Address p)
    {
        int n = u2(p);
        p = p.plus(2);
        int i = 0;
        while (i < n)
        {
            p = skipElementValue(p.plus(2));                 // past element_name_index
            i += 1;
        }
        return p;
    }

    /** One {@code element_value} (JVMS 4.7.16.1) -> the position after it. Recursive for '@' and '['. */
    static Address skipElementValue(Address p)
    {
        int tag = u1(p);
        p = p.plus(1);
        if (tag == 0x65)                                 // 'e' enum: type_name_index + const_name_index
        {
            return p.plus(4);
        }
        if (tag == 0x40)                                 // '@' nested annotation: type_index, then its pairs
        {
            return skipElementPairs(p.plus(2));
        }
        if (tag == 0x5B)                                 // '[' array of element_value
        {
            int n = u2(p);
            p = p.plus(2);
            int i = 0;
            while (i < n)
            {
                p = skipElementValue(p);
                i += 1;
            }
            return p;
        }
        return p.plus(2);                                    // B C D F I J S Z s c: one u2 constant-pool index
    }
}
