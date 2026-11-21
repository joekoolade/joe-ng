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
import classfile.ClassReader;
import magic.Address;
import magic.DataReference;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * The classfile attributes reflection reports, read straight out of a loaded class's blob: Signature, Exceptions,
 * MethodParameters, EnclosingMethod, NestHost/NestMembers, InnerClasses, Record, PermittedSubclasses, and the
 * declared interfaces and member classes (JikesRVM keeps these on {@code RVMClass}/{@code RVMMember}). Split out of
 * {@link Loader}.
 */
final class ClassAttributes
{
    private ClassAttributes()
    {
    }

    private static DataReference gAttrBase;                      // the blob classAttrOf last answered for
    /** Cap on member classes enumerated per class -- fixed so the two passes below stay on plain locals. */
    private static final int MAXDECLCLASS = 64;

    /**
     * The {@code method_info} position (at {@code access_flags}) for the method registered at {@code rgIndex},
     * or 0 when there is no such registration or the class does not declare it. Its {@code attributes_count}
     * is {@code u2(p + 6)} and its attributes begin at {@code p + 8}.
     *
     * <p>Matched on name AND descriptor, which is what separates two overloads -- a name-only match returns
     * whichever came first, the bug that made a {@code @ParameterizedTest} report the test's annotation for
     * its own factory.
     */
    static Address methodInfoPos(int rgIndex)
    {
        if (Reflection.reflEntry(rgIndex) == null)
        {
            return Address.zero();
        }
        DataReference base = Reflection.reflEntry(rgIndex).base;
        int nameOff = Reflection.reflEntry(rgIndex).nameOff;
        int descOff = Reflection.reflEntry(rgIndex).descOff;
        Loader.parseForMethods(base.toAddress(), Loader.blobLenOf(base.toAddress()));
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (Loader.utf8EqAt(base.toAddress(), Loader.gView.cp[u2(p.plus(2))], base.toAddress(), nameOff)
                    && Loader.utf8EqAt(base.toAddress(), Loader.gView.cp[u2(p.plus(4))], base.toAddress(), descOff))
            {
                return p;
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return Address.zero();
    }

    /**
     * {@code Class.genericSignature0}: the class's {@code Signature} attribute (JVMS 4.7.9) as a String, or 0
     * when it has none -- which is what stock's {@code getGenericSignature0} answers, and what makes
     * {@code getGenericSuperclass} fall back to the erasure for a NON-generic class, exactly as stock does.
     *
     * <p>The bytes are copied as Latin-1 by {@link Loader#utf8ToString}; a signature is a modified-UTF-8 string,
     * so a non-ASCII identifier in one would read wrongly. ALLOCATION-FREE for a bad argument, because
     * {@code VM.forceCompile} probes this native with zero.
     */
    static long classSignature(ObjectReference mirror)
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
        DataReference base = ClassRegistry.clTab[reg].base;
        Address ap = classAttrsPos(base.toAddress());
        if (ap.isZero())
        {
            return 0L;
        }
        Address p = classAttrNamed(base.toAddress(), ap.plus(2L), u2(ap), Magic.bytes("Signature"));
        if (p.isZero())
        {
            return 0L;
        }
        return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(p)]);
    }

    /**
     * {@code Executable.signature0}: the {@code Signature} attribute of the method or constructor registered at
     * {@code rgIndex}, as a String, or 0 when it has none (a non-generic member, whose generic types ARE its
     * erasures -- stock's answer too). The {@code method_info} is found by name AND descriptor
     * ({@link #methodInfoPos}), so overloads keep their own signatures.
     */
    static long methodSignature(int rgIndex)
    {
        Address p = methodInfoPos(rgIndex);
        if (p.isZero())
        {
            return 0L;
        }
        DataReference base = Reflection.reflEntry(rgIndex).base;
        Address q = classAttrNamed(base.toAddress(), p.plus(8L), u2(p.plus(6L)), Magic.bytes("Signature"));
        if (q.isZero())
        {
            return 0L;
        }
        return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(q)]);
    }

    /**
     * {@code Executable.exceptionName0}: the internal name of the {@code i}th class in the {@code Exceptions}
     * attribute (JVMS 4.7.5) of the method or constructor registered at {@code rgIndex}, or 0 past the end --
     * and 0 for every {@code i} when there is no such attribute, i.e. the member declares no {@code throws}.
     * One name per call keeps this allocation-free for a bad argument ({@code VM.forceCompile} probes with -1).
     */
    static long methodExceptionName(int rgIndex, long i)
    {
        Address p = methodInfoPos(rgIndex);
        if (p.isZero())
        {
            return 0L;
        }
        DataReference base = Reflection.reflEntry(rgIndex).base;
        Address q = classAttrNamed(base.toAddress(), p.plus(8L), u2(p.plus(6L)), Magic.bytes("Exceptions"));
        if (q.isZero())
        {
            return 0L;
        }
        if (i < 0L || i >= u2(q))                       // u2 number_of_exceptions, then u2 class indices
        {
            return 0L;
        }
        int ci = u2(q.plus(2L + 2L * i));
        return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(base.plus(Loader.gView.cp[ci]))]);  // CONSTANT_Class -> its name Utf8
    }

    /**
     * {@code Executable.paramMeta0}: the integer half of a method's parameter attributes. {@code k == -1}
     * answers the {@code parameters_count} of its {@code MethodParameters} attribute (JVMS 4.7.24), {@code -2}
     * the {@code num_parameters} of its {@code RuntimeVisibleParameterAnnotations} (JVMS 4.7.18) -- each -1 when
     * the attribute is absent -- and {@code k >= 0} the {@code access_flags} of {@code MethodParameters} entry
     * {@code k}. The two counts can differ from the descriptor's and from each other: javac writes no
     * annotation row for a constructor's implicit outer instance or an enum's name and ordinal, and a
     * {@code MethodParameters} entry for each. -1 for anything out of range; allocation-free, since
     * {@code VM.forceCompile} probes with rgIndex -1.
     */
    static long methodParamMeta(int rgIndex, long k)
    {
        Address p = methodInfoPos(rgIndex);
        if (p.isZero())
        {
            return -1L;
        }
        DataReference base = Reflection.reflEntry(rgIndex).base;
        if (k == -3L)
        {
            // Not a parameter fact, but the same kind of question about the same method_info: does this
            // annotation element have an AnnotationDefault (JVMS 4.7.22)? 1 or 0. Method.getDefaultValue asks.
            return classAttrNamed(base.toAddress(), p.plus(8L), u2(p.plus(6L)), Magic.bytes("AnnotationDefault")).isZero() ? 0L : 1L;
        }
        if (k == -2L)
        {
            Address q = classAttrNamed(base.toAddress(), p.plus(8L), u2(p.plus(6L)), Magic.bytes("RuntimeVisibleParameterAnnotations"));
            return q.isZero() ? -1L : u1(q);
        }
        Address q = classAttrNamed(base.toAddress(), p.plus(8L), u2(p.plus(6L)), Magic.bytes("MethodParameters"));
        if (q.isZero())
        {
            return -1L;
        }
        int n = u1(q);                                   // u1 parameters_count, then {u2 name, u2 flags} each
        if (k == -1L)
        {
            return n;
        }
        if (k < 0L || k >= n)
        {
            return -1L;
        }
        return u2(q.plus(1L + 4L * k + 2L));
    }

    /**
     * {@code Executable.paramName0}: the name in {@code MethodParameters} entry {@code k} as a String, or 0 when
     * the entry has none ({@code name_index} 0 -- javac writes that for an implicit or synthetic parameter of a
     * class not compiled with {@code -parameters}), the attribute is absent, or {@code k} is out of range.
     */
    static long methodParamName(int rgIndex, long k)
    {
        Address p = methodInfoPos(rgIndex);
        if (p.isZero())
        {
            return 0L;
        }
        DataReference base = Reflection.reflEntry(rgIndex).base;
        Address q = classAttrNamed(base.toAddress(), p.plus(8L), u2(p.plus(6L)), Magic.bytes("MethodParameters"));
        if (q.isZero() || k < 0L || k >= u1(q))
        {
            return 0L;
        }
        int nameIdx = u2(q.plus(1L + 4L * k));
        return nameIdx == 0 ? 0L : Loader.utf8ToString(base.toAddress(), Loader.gView.cp[nameIdx]);
    }

    /**
     * {@code Field.signature0}: the {@code Signature} attribute of field {@code nameArr} (a raw {@code byte[]})
     * of the class whose mirror is given, or 0. The field walk is {@link Annotations#fieldAnnotation}'s.
     */
    static long fieldSignature(ObjectReference mirror, ObjectReference nameArr)
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
        DataReference base = ClassRegistry.clTab[ci].base;
        Loader.parseConstPool(base, Loader.blobLenOf(base.toAddress()));
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
            if (Utf8.rawEqUtf8(nbase, nlen, base.toAddress(), Loader.gView.cp[u2(p.plus(2))]))
            {
                Address q = classAttrNamed(base.toAddress(), p.plus(8L), attrs, Magic.bytes("Signature"));
                if (q.isZero())
                {
                    return 0L;
                }
                return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(q)]);
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            f += 1;
        }
        return 0L;
    }

    /**
     * {@code Class.enclosingPart0}: one part of the class's {@code EnclosingMethod} attribute (JVMS 4.7.7) as a
     * String, or 0. Part 0 is the enclosing CLASS's internal name; 0 means the attribute is absent, which JVMS
     * makes the definition of "neither local nor anonymous". Part 1 is the enclosing method's name and part 2
     * its descriptor; both are 0 when {@code method_index} is 0, i.e. the class sits in an initializer. These
     * are the three elements of stock's {@code getEnclosingMethod0()} array, with the class left as a name for
     * the guest to resolve. ALLOCATION-FREE for a bad argument, because {@code VM.forceCompile} probes with 0.
     */
    static long classEnclosingPart(ObjectReference mirror, long part)
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
        DataReference base = ClassRegistry.clTab[reg].base;
        Address ap = classAttrsPos(base.toAddress());
        if (ap.isZero())
        {
            return 0L;
        }
        Address p = classAttrNamed(base.toAddress(), ap.plus(2L), u2(ap), Magic.bytes("EnclosingMethod"));
        if (p.isZero())
        {
            return 0L;
        }
        if (part == 0L)
        {
            return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(base.plus(Loader.gView.cp[u2(p)]))]);  // CONSTANT_Class -> its name Utf8
        }
        int nat = u2(p.plus(2L));                           // u2 class_index, u2 method_index
        if (nat == 0)
        {
            return 0L;
        }
        Address natPos = base.plus(Loader.gView.cp[nat]);                  // CONSTANT_NameAndType: u2 name, u2 descriptor
        return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(natPos.plus((part == 1L ? 0L : 2L)))]);
    }

    /**
     * {@code Class.classFileWord0} / {@code MetalJavaLangAccess.classFileWord0}: one word of the class's own
     * classfile header. {@code k == 0} answers the version as stock's {@code getClassFileVersion0} packs it,
     * {@code (minor << 16) | major}; {@code k == 1} answers the raw {@code access_flags}, {@code ACC_SUPER}
     * included -- the cached modifiers strip it, and for a nested class the flags are not {@code InnerClasses}'.
     * 0 for a mirror with no classfile (a primitive or array type: the guest answers those itself).
     */
    static long classFileWord(ObjectReference mirror, long k)
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
        DataReference base = ClassRegistry.clTab[reg].base;
        if (k == 0L)
        {
            return ((long) u2(base.plus(4L)) << 16) | u2(base.plus(6L));   // u4 magic, u2 minor, u2 major
        }
        Loader.parseConstPool(base, Loader.blobLenOf(base.toAddress()));          // leaves gp at access_flags, as loadStructure reads it
        return u2(Loader.gp);
    }

    /**
     * {@code Class.nestPart0}: the class's nest, as written by its {@code NestHost} and {@code NestMembers}
     * attributes (JVMS 4.7.28, 4.7.29). {@code k == -1} answers the HOST's internal name, or 0 when this class
     * has no {@code NestHost} -- it is then its own host; {@code k >= 0} answers the {@code k}th
     * {@code NestMembers} entry's internal name, or 0 past the end or when there is no such attribute. Names
     * only: the guest resolves them, so nothing here demand-loads under the cursor.
     */
    static long classNestPart(ObjectReference mirror, long k)
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
        DataReference base = ClassRegistry.clTab[reg].base;
        Address ap = classAttrsPos(base.toAddress());
        if (ap.isZero())
        {
            return 0L;
        }
        if (k < 0L)
        {
            Address p = classAttrNamed(base.toAddress(), ap.plus(2L), u2(ap), Magic.bytes("NestHost"));
            return p.isZero() ? 0L : Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(base.plus(Loader.gView.cp[u2(p)]))]);  // CONSTANT_Class -> name
        }
        Address p = classAttrNamed(base.toAddress(), ap.plus(2L), u2(ap), Magic.bytes("NestMembers"));
        if (p.isZero() || k >= u2(p))                      // u2 number_of_classes, then u2 class indices
        {
            return 0L;
        }
        int ci = u2(p.plus(2L + 2L * k));
        return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(base.plus(Loader.gView.cp[ci]))]);
    }

    /**
     * {@code Class.innerPart0}: one part of the class's own {@code InnerClasses} entry as a String, or 0. Part 0
     * is the OUTER class's internal name -- 0 for a top-level, local or anonymous class, since only a member
     * class names an outer one; this is HotSpot's {@code getDeclaringClass0}. Part 1 is the SIMPLE name -- 0
     * for a top-level or anonymous class; HotSpot's {@code getSimpleBinaryName0}. Read from the classfile, never
     * from the binary name, so a top-level class whose name contains '$' stays top-level.
     */
    static long classInnerPart(ObjectReference mirror, long part)
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
        DataReference base = ClassRegistry.clTab[reg].base;
        Address e = Reflection.ownInnerClassEntry(base.toAddress());
        if (e.isZero())
        {
            return 0L;
        }
        int idx = u2(e.plus((part == 0L ? 2L : 4L)));     // outer_class_info_index or inner_name_index
        if (idx == 0)
        {
            return 0L;
        }
        if (part == 0L)
        {
            return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(base.plus(Loader.gView.cp[idx]))]);    // CONSTANT_Class -> its name Utf8
        }
        return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[idx]);
    }

    /**
     * The class's attribute named {@code name} -- position of its body, or 0 when absent or the mirror has no
     * classfile. The base of the blob is left in {@link #gAttrBase} for the caller's reads.
     */
    private static Address classAttrOf(ObjectReference mirror, byte[] name)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return Address.zero();
        }
        int reg = ClassRegistry.regOfType(mirror.toAddress().plus(16L).loadAddress());
        if (reg < 0)
        {
            return Address.zero();
        }
        DataReference base = ClassRegistry.clTab[reg].base;
        Address ap = classAttrsPos(base.toAddress());
        if (ap.isZero())
        {
            return Address.zero();
        }
        gAttrBase = base;
        return classAttrNamed(base.toAddress(), ap.plus(2L), u2(ap), name);
    }

    /** Position of record component {@code idx} in the Record attribute at {@code rec}, or 0 past the end. */
    private static Address recordComponentPos(Address rec, long idx)
    {
        int n = u2(rec);                                // u2 components_count, then the components
        if (idx < 0L || idx >= n)
        {
            return Address.zero();
        }
        Address p = rec.plus(2L);
        long i = 0L;
        while (i < idx)
        {
            p = Loader.skipAttributes(p.plus(6L), u2(p.plus(4L)));     // u2 name, u2 descriptor, u2 attributes_count, attrs
            i += 1L;
        }
        return p;
    }

    /**
     * {@code RecordComponent.recordInfo0}: the number of components in the class's {@code Record} attribute
     * (JVMS 4.7.30), or -1 when it has none -- which is what makes it a record, beyond extending Record.
     */
    static long classRecordInfo(ObjectReference mirror, long k)
    {
        Address rec = classAttrOf(mirror, Magic.bytes("Record"));
        return rec.isZero() ? -1L : u2(rec);
    }

    /**
     * {@code RecordComponent.recordPart0}: for component {@code code >> 2}, part {@code code & 3} -- 0 its name, 1
     * its field descriptor, 2 its {@code Signature} attribute -- as a String, or 0 (no such component, or no
     * Signature).
     */
    static long classRecordPart(ObjectReference mirror, long code)
    {
        Address rec = classAttrOf(mirror, Magic.bytes("Record"));
        if (rec.isZero())
        {
            return 0L;
        }
        DataReference base = gAttrBase;
        Address p = recordComponentPos(rec, code >> 2);
        if (p.isZero())
        {
            return 0L;
        }
        int part = (int) (code & 3L);
        if (part == 0)
        {
            return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(p)]);
        }
        if (part == 1)
        {
            return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(p.plus(2L))]);
        }
        Address q = classAttrNamed(base.toAddress(), p.plus(6L), u2(p.plus(4L)), Magic.bytes("Signature"));
        return q.isZero() ? 0L : Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(q)]);
    }

    /** {@code RecordComponent.recordAnnos0}: the annotations written on component {@code idx}, as an Annotation[]. */
    static long classRecordAnnos(ObjectReference mirror, long idx)
    {
        Address rec = classAttrOf(mirror, Magic.bytes("Record"));
        if (rec.isZero())
        {
            return 0L;
        }
        DataReference base = gAttrBase;
        Address p = recordComponentPos(rec, idx);
        if (p.isZero())
        {
            return 0L;
        }
        Address lp = Annotations.annoListPos(base.toAddress(), p.plus(6L), u2(p.plus(4L)));
        return lp.isZero() ? Annotations.emptyAnnoArray() : Annotations.annotationsAt(base.toAddress(), lp);
    }

    /** {@code Class.permitted0}: the {@code k}th name in the class's {@code PermittedSubclasses}, or 0. */
    static long classPermitted(ObjectReference mirror, long k)
    {
        Address ps = classAttrOf(mirror, Magic.bytes("PermittedSubclasses"));
        if (ps.isZero() || k < 0L || k >= u2(ps))         // u2 number_of_classes, then u2 class indices
        {
            return 0L;
        }
        DataReference base = gAttrBase;
        int ci = u2(ps.plus(2L + 2L * k));
        return Loader.utf8ToString(base.toAddress(), Loader.gView.cp[u2(base.plus(Loader.gView.cp[ci]))]);   // CONSTANT_Class -> its name Utf8
    }

    /**
     * {@code Class.sealed0}: 1 when the class carries a {@code PermittedSubclasses} attribute (JVMS 4.7.31),
     * else 0. That is exactly when stock's {@code getPermittedSubclasses()} is non-null, which is stock's whole
     * definition of {@code isSealed()} for a non-array, non-primitive class.
     */
    static long classSealed(ObjectReference mirror)
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
        DataReference base = ClassRegistry.clTab[reg].base;
        Address ap = classAttrsPos(base.toAddress());
        if (ap.isZero())
        {
            return 0L;
        }
        return !classAttrNamed(base.toAddress(), ap.plus(2L), u2(ap), Magic.bytes("PermittedSubclasses")).isZero() ? 1L : 0L;
    }

    /** Position of the body of the attribute named {@code name} in an attribute list, or 0 if absent. */
    static Address classAttrNamed(Address base, Address p, int attrs, byte[] name)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p = p.plus(2);
            int alen = u4(p);
            p = p.plus(4);
            if (Utf8.utf8IsAtBase(base, Loader.gView.cp[anIdx], name))
            {
                return p;
            }
            p = p.plus(alen);
            a += 1;
        }
        return Address.zero();
    }

    /** The class-level {@code attributes_count} position: past every method, which is where they begin. */
    static Address classAttrsPos(Address base)
    {
        Loader.parseForMethods(base, Loader.blobLenOf(base));
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            p = Loader.skipAttributes(p.plus(8), u2(p.plus(6)));
            m += 1;
        }
        return p;
    }

    /**
     * This class's DIRECTLY DECLARED interfaces, as Class mirrors, in declaration order.
     *
     * <p>Read from {@code clIfaceReg}, which the loader fills from the classfile's {@code interfaces} array --
     * so it is the declared set, not the transitive closure. That is what stock returns, and what JUnit's
     * {@code AnnotationUtils.findAnnotation} needs: it recurses into each interface itself, so handing it a
     * flattened closure would make it walk the same interfaces repeatedly.
     */
    static long classInterfaces(ObjectReference mirror)
    {
        DataReference elemType = ClassMirrors.classMirrorTypeAddr();
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int reg = ClassRegistry.regOfType(mirror.toAddress().plus(16L).loadAddress());
        int n = reg >= 0 ? InterfaceInvocation.clIfaceRegN[reg] : 0;
        ObjectReference arr = Heap.allocArray(n, 8).toObjectReference();
        arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.refArrayTib(elemType.toAddress()));
        int i = 0;
        while (i < n)
        {
            arr.toAddress().plus(ObjectModel.ARRAY_BASE_OFFSET).plus(i * 8L).store(
                    ClassMirrors.classMirror(ClassRegistry.clTab[InterfaceInvocation.clIfaceReg[reg * InterfaceInvocation.MAX_DIRECT_IF + i]].type.toAddress()));
            i += 1;
        }
        return arr.toLong();
    }

    /**
     * {@code Class.declaredClasses0}: the MEMBER classes this class declares, as a fresh {@code Class[]}.
     * Never 0 for a registered class; one that declares none answers a zero-length array.
     *
     * <p>Read from the {@code InnerClasses} attribute (JVMS 4.7.6), which is where the relationship is
     * actually written down -- NOT from the binary name. The nesting PREDICATES on {@code Class} do use the
     * name, and can: they ask "is THIS class a member", which {@code Outer$Inner} answers. This asks the
     * reverse question, "which classes are members of this one", and a name cannot answer it without scanning
     * the whole classDir and assuming javac's naming, which the spec does not mandate.
     *
     * <p>A class's own {@code InnerClasses} table lists every nested class it MENTIONS -- including itself
     * when it is nested, and others' nested classes it merely references -- so filtering on
     * {@code outer_class_info_index} naming THIS class is what makes the answer "declared by me". That
     * filter also excludes local and anonymous classes for free, which stock excludes too: JVMS 4.7.6
     * requires {@code outer_class_info_index} to be ZERO for both, and {@code inner_name_index} to be zero
     * for an anonymous class. Both are checked.
     *
     * <p>TWO PASSES OVER ABSOLUTE ADDRESSES, for the reason {@link Annotations#classAnnotationsAll} records: pass 2
     * RESOLVES, a resolve may demand-load, and a load re-parses a blob and moves the {@code gView.cp} cursor that
     * pass 1 reads. Blob addresses survive that; parse state does not.
     *
     * <p>A member class that cannot be loaded is OMITTED and named on the console, rather than left as a null
     * element -- a null reads to the caller as a class that exists but is broken.
     */
    static long classDeclaredClasses(ObjectReference mirror)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;                                  // boot force-compile guard passes 0; allocates nothing
        }
        int reg = ClassRegistry.regOfType(mirror.toAddress().plus(16L).loadAddress());
        if (reg < 0)
        {
            return Reflection.emptyClassArray();
        }
        DataReference base = ClassRegistry.clTab[reg].base;
        Address ap = classAttrsPos(base.toAddress());
        if (ap.isZero())
        {
            return Reflection.emptyClassArray();
        }
        Address lp = innerClassesPos(base.toAddress(), ap.plus(2L), u2(ap));
        if (lp.isZero())
        {
            return Reflection.emptyClassArray();                   // no InnerClasses attribute: declares no member class
        }
        int thisIdx = u2(base.plus(Loader.gView.afterCp + 2));          // this_class, per the ClassReader.afterCp layout
        if (thisIdx == 0)
        {
            return Reflection.emptyClassArray();
        }
        int thisNameOff = Loader.gView.cp[u2(base.plus(Loader.gView.cp[thisIdx]))]; // CONSTANT_Class -> name_index -> Utf8
        AddressArray nameAbs = AddressArray.create(MAXDECLCLASS);
        int n = u2(lp);
        Address p = lp.plus(2L);
        int found = 0;
        int i = 0;
        while (i < n && found < MAXDECLCLASS)            // PASS 1: addresses only, nothing resolved
        {
            int innerIdx = u2(p);
            int outerIdx = u2(p.plus(2));
            int simpleIdx = u2(p.plus(4));
            if (innerIdx != 0 && outerIdx != 0 && simpleIdx != 0)
            {
                int outerNameOff = Loader.gView.cp[u2(base.plus(Loader.gView.cp[outerIdx]))];
                if (Loader.utf8EqAt(base.toAddress(), outerNameOff, base.toAddress(), thisNameOff))
                {
                    nameAbs.set(found, base.plus(Loader.gView.cp[u2(base.plus(Loader.gView.cp[innerIdx]))]));
                    found += 1;
                }
            }
            p = p.plus(8);                                      // inner, outer, simple name, access flags
            i += 1;
        }
        long[] mirrors = new long[found];                // PASS 2: resolve, which may demand-load
        int built = 0;
        int k = 0;
        while (k < found)
        {
            long m = ClassMirrors.mirrorForNameAt(nameAbs.get(k));
            if (m != 0L)
            {
                mirrors[built] = m;
                built += 1;
            }
            k += 1;
        }
        ObjectReference arr = Heap.allocArray(built, 8).toObjectReference();
        arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.refArrayTib(ClassMirrors.classMirrorTypeAddr().toAddress()));
        int j = 0;
        while (j < built)
        {
            arr.toAddress().plus(ObjectModel.ARRAY_BASE_OFFSET).plus(j * 8L).store(mirrors[j]);
            j += 1;
        }
        return arr.toLong();
    }

    /** Names a member class that could not be loaded, so an omission from getDeclaredClasses is never silent. */
    static void reportNestedNotLoaded(byte[] name)
    {
        Uart.write(Magic.bytes("\n  MEMBER CLASS NOT LOADED (omitted from getDeclaredClasses): "));
        Uart.write(name);
        Uart.write(Magic.bytes("\n"));
    }

    /**
     * Position of the {@code InnerClasses} list ({@code u2 number_of_classes} first), or 0 if absent.
     *
     * <p>{@link Reflection#innerAccessOf} scans the same attribute, but asks the opposite question -- "what are THIS
     * class's own inner-class access flags", keyed on {@code inner_class_info_index} -- and works off the
     * current parse state rather than a {@code base}. The two are different queries over one table, not
     * redundant copies.
     */
    static Address innerClassesPos(Address base, Address p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p = p.plus(2);
            int alen = u4(p);
            p = p.plus(4);
            if (Utf8.utf8IsAtBase(base, Loader.gView.cp[anIdx], Magic.bytes("InnerClasses")))
            {
                return p;
            }
            p = p.plus(alen);
            a += 1;
        }
        return Address.zero();
    }
}
