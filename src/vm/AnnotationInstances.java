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

import asm.A64Enc;
import magic.Address;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * Annotation INSTANCES: the objects {@code getAnnotation} returns, built without a Proxy runtime. Split out of
 * {@link Loader}; {@link Annotations} finds the annotation, this builds it.
 *
 * <p>An annotation object has the SAME SHAPE as a synthesised lambda ({@code Loader.finishLambdaClass}): an object
 * whose Type carries an itable directory for one interface. The difference is that a lambda has ONE thunk (its SAM)
 * and an annotation has one per ELEMENT -- and each of those is trivial, because the value lives in the instance:
 *
 * <pre>
 *     ldr x0, [x0, #16 + slot*8]
 *     ret
 * </pre>
 *
 * Two instructions, no boxing and no capture list, because the interface's own itable slot numbering IS the value
 * index: the element method at slot s reads value slot s. That identity is what makes this small, and it is not a
 * coincidence -- both sides are {@code ifmSlotIn}'s numbering for the same interface.
 *
 * <p>Type/TIB/itable depend only on the annotation INTERFACE, never on the values, so they are built once per
 * interface and shared by every instance.
 */
final class AnnotationInstances
{
    private AnnotationInstances()
    {
    }

    private static final int MAXANNOTIB = 64;
    private static AddressArray annoTibKey;                   // interface Type -> its shared TIB
    private static AddressArray annoTibVal;
    static int annoTibN;
    /** Compiled bodies of {@code MetalAnnotations.annoEquals/annoHashCode/annoToString}; 0 until compiled. */
    static long annoEqBuf;
    static long annoHashBuf;
    static long annoStrBuf;
    // Pending Class/enum element resolutions for the annotation currently being built. Kept as a flat list
    // rather than resolved in place: see buildAnnoObject phase 3. `annoPendAt` is the value slot, recorded by
    // the caller because only it knows which element is being decoded.
    private static final int MAXANNOPEND = 256;   // per annotation instance; ARRAY elements count too now
                                                     //   (@Command.subcommands() is itself a Class[])
    private static int[] annoPendKind;
    private static AddressArray annoPendA;
    private static AddressArray annoPendB;
    private static int[] annoPendAt;
    private static int[] annoPendIdx;                   // element index within the slot's array, or -1
    private static int annoPendN;
    private static int annoPendSlot = -1;               // the slot being decoded, or -1 outside an element
    private static int annoPendElem = -1;               // array element index being decoded, or -1
    private static Address annoArrayTib;                // the array TIB for that slot's declared element type
    private static int annoArrayPrim;                   // that slot's PRIMITIVE array component char, or 0

    /**
     * Compile the annotation-contract helpers once, loading their class on first use -- lazily, so a program
     * that reads no annotation pays nothing. Each stays 0 if it will not compile, and its slot then keeps
     * {@code Object}'s identity behaviour, which is the old answer rather than a wild branch.
     */
    private static void annoContractBufs()
    {
        if (annoEqBuf != 0L && annoHashBuf != 0L && annoStrBuf != 0L)
        {
            return;
        }
        if (Loader.classIndexByName(Magic.bytes("jdk/internal/misc/MetalAnnotations")) < 0)
        {
            Loader.loadClassIncremental(Magic.bytes("jdk/internal/misc/MetalAnnotations"));
            if (Loader.classIndexByName(Magic.bytes("jdk/internal/misc/MetalAnnotations")) < 0)
            {
                return;
            }
        }
        Address cls = Loader.utf8Blob(Magic.bytes("jdk/internal/misc/MetalAnnotations"));
        if (annoEqBuf == 0L)
        {
            annoEqBuf = Loader.helperBuf(cls, Magic.bytes("annoEquals"), Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        }
        if (annoHashBuf == 0L)
        {
            annoHashBuf = Loader.helperBuf(cls, Magic.bytes("annoHashCode"), Magic.bytes("(Ljava/lang/Object;)I"));
        }
        if (annoStrBuf == 0L)
        {
            annoStrBuf = Loader.helperBuf(cls, Magic.bytes("annoToString"), Magic.bytes("(Ljava/lang/Object;)Ljava/lang/String;"));
        }
    }

    /** Whether {@code tib} is one of the shared annotation TIBs. */
    static boolean isAnnoTib(Address tib)
    {
        int i = 0;
        while (i < annoTibN)
        {
            if (annoTibVal.get(i).EQ(tib))
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /**
     * Point an annotation TIB's {@code equals}/{@code hashCode}/{@code toString} -- the {@code Object} vtable
     * slots, and when {@code dir} is given the {@code java/lang/annotation/Annotation} itable entry -- at
     * {@code MetalAnnotations}' static helpers, whose (receiver, arg) register layout is the instance method's.
     * Before this an annotation was compared, hashed and printed as an identity object: two reads of the same
     * annotation were not equal, a {@code HashSet} kept every copy, and {@code toString} printed a {@code $$Lambda}
     * name (AnnoContractProbe). Slots beyond {@code cap} are not written.
     */
    static void patchAnnoContract(Address tib, int cap, Address dir)
    {
        annoContractBufs();
        int oi = Loader.objectClassIndex();
        if (oi < 0)
        {
            return;
        }
        int st = Loader.clTab[oi].vtStart;
        int nv = Loader.clTab[oi].vtCount;
        int k = 0;
        while (k < nv && k < cap)
        {
            long buf = annoContractBufFor(Loader.vtNameBase.get(st + k), Loader.vtNameOff[st + k], Loader.vtDescOff[st + k]);
            if (buf != 0L)
            {
                tib.plus(8L).plus((long) k * 8L).store(buf);
            }
            k += 1;
        }
        if (dir.isZero())
        {
            return;
        }
        Address e = dir;
        while (e.loadLong() != 0L)
        {
            int r = Loader.classRegByType(e.loadAddress());
            Address it = e.plus(8L).loadAddress();
            if (r >= 0 && !it.isZero() && Utf8.utf8IsAtBase(Loader.clTab[r].base, Loader.clTab[r].nameOff, Magic.bytes("java/lang/annotation/Annotation")))
            {
                int z = 0;
                while (z < Loader.clTab[r].ifmCount)
                {
                    int m = Loader.clTab[r].ifmStart + z;
                    long buf = annoContractBufFor(Loader.ifBase.get(m), Loader.ifNameOff[m], Loader.ifDescOff[m]);
                    if (buf != 0L)
                    {
                        it.plus(z * 8L).store(buf);
                    }
                    z += 1;
                }
            }
            e = e.plus(16L);
        }
    }

    /** The contract helper for a method named and typed as {@code equals}/{@code hashCode}/{@code toString}, or 0. */
    private static long annoContractBufFor(Address base, int nameOff, int descOff)
    {
        if (Utf8.utf8IsAtBase(base, nameOff, Magic.bytes("equals")) && Utf8.utf8IsAtBase(base, descOff, Magic.bytes("(Ljava/lang/Object;)Z")))
        {
            return annoEqBuf;
        }
        if (Utf8.utf8IsAtBase(base, nameOff, Magic.bytes("hashCode")) && Utf8.utf8IsAtBase(base, descOff, Magic.bytes("()I")))
        {
            return annoHashBuf;
        }
        if (Utf8.utf8IsAtBase(base, nameOff, Magic.bytes("toString")) && Utf8.utf8IsAtBase(base, descOff, Magic.bytes("()Ljava/lang/String;")))
        {
            return annoStrBuf;
        }
        return 0L;
    }

    /** The shared TIB for annotation interface {@code ifaceReg}, built once and GC-rooted like a lambda's. */
    private static Address annoTibFor(int ifaceReg)
    {
        Address ifaceType = Loader.clTab[ifaceReg].type;
        if (ifaceType.isZero())
        {
            return Address.zero();
        }
        if (annoTibKey == null)
        {
            annoTibKey = AddressArray.create(MAXANNOTIB);
            annoTibVal = AddressArray.create(MAXANNOTIB);
            annoTibN = 0;
        }
        int i = 0;
        while (i < annoTibN)
        {
            if (annoTibKey.get(i).EQ(ifaceType))
            {
                return annoTibVal.get(i);
            }
            i += 1;
        }
        if (annoTibN >= MAXANNOTIB)
        {
            return Address.zero();
        }
        int n = Loader.clTab[ifaceReg].ifmCount;
        Address it = Heap.allocData(n > 0 ? n * 8 : 8);
        Address ownBase = Loader.clTab[ifaceReg].base;
        int s = 0;
        while (s < n)
        {
            // ONLY THE INTERFACE'S OWN ELEMENTS READ A VALUE SLOT. Its method list also carries what it inherits
            // from java/lang/annotation/Annotation, and every slot used to get a value-reading thunk -- so
            // annotationType() in THIS entry read an empty value slot. A direct call names Annotation and lands in
            // that entry's thunk below, which is why it worked; a method reference resolves BY NAME and finds
            // this entry first, so `Annotation::annotationType` on an annotation read null (AnnoRefProbe) and
            // AnnotatedElement's default getDeclaredAnnotationsByType keyed its map by null (RepeatableProbe).
            // annotationType() gets the mirror thunk here too; an inherited equals/hashCode/toString stays 0,
            // which the dispatch guard turns into a resolution, not a branch.
            Address thunk = Address.zero();
            boolean own = Loader.ifBase.get(Loader.clTab[ifaceReg].ifmStart + s).EQ(ownBase);
            boolean isAnnoType = Utf8.utf8IsAtBase(Loader.ifBase.get(Loader.clTab[ifaceReg].ifmStart + s),
                    Loader.ifNameOff[Loader.clTab[ifaceReg].ifmStart + s], Magic.bytes("annotationType"));
            if (own || isAnnoType)
            {
                thunk = Heap.allocCode(8);
                Heap.pinCodeAt(thunk);                  // only the itable names it; no `bl` displacement does
                thunk.plus(0L).store(A64Enc.ldrx(0, 0, 16 + (own ? s : n) * 8));
                thunk.plus(4L).store(A64Enc.ret());
                Heap.publishCode(thunk, thunk.plus(8L));    // another core may run it: maintenance is BY ADDRESS
            }
            it.plus(s * 8L).store(thunk);
            s += 1;
        }
        // THE DIRECTORY MUST CARRY THE INTERFACE'S CLOSURE, NOT JUST THE INTERFACE. Every `@interface Foo`
        // implicitly extends java.lang.annotation.Annotation, and a bounded `<T extends Annotation>` caller
        // emits a `checkcast Annotation` on the result -- which walks this directory. With only Foo in it the
        // cast throws ClassCastException, which is exactly how this first failed. Same closure treatment
        // finishLambdaClass gives a functional interface, and for the same reason.
        //
        // The extra entries get CORRECTLY SIZED but ZERO-FILLED itables: nothing here implements
        // Annotation.annotationType()/equals/hashCode, and a zero slot meets the dispatch guard (a named trap)
        // rather than branching into whatever a short table would have run off into.
        int closN = Loader.ifaceClosureOf(ifaceReg);
        Address dir = Heap.allocData((closN + 2) * 16);
        dir.plus(0L).store(ifaceType);
        dir.plus(8L).store(it);
        int e = 1;
        int ci = 0;
        while (ci < closN)
        {
            int cr = Loader.ifClosureBuf[ci];
            int cn = Loader.clTab[cr].ifmCount;
            Address cit = Heap.allocData(cn > 0 ? cn * 8 : 8);
            int z = 0;
            while (z < cn)
            {
                // annotationType() IS implemented: it reads the mirror buildAnnoObject stored in the trailing
                // word. Leaving it zero cost a launcher boot -- JUnit's findRepeatableAnnotations calls it on
                // every annotation it walks, and a null flowed through isInJavaLangAnnotationPackage's own
                // null guard to NPE one line later, naming a JUnit method rather than this gap.
                boolean isAnnoType = Utf8.utf8IsAtBase(Loader.ifBase.get(Loader.clTab[cr].ifmStart + z),
                        Loader.ifNameOff[Loader.clTab[cr].ifmStart + z], Magic.bytes("annotationType"));
                if (isAnnoType)
                {
                    Address at = Heap.allocCode(8);
                    Heap.pinCodeAt(at);
                    at.plus(0L).store(A64Enc.ldrx(0, 0, 16 + n * 8));
                    at.plus(4L).store(A64Enc.ret());
                    Heap.publishCode(at, at.plus(8L));
                    cit.plus(z * 8L).store(at);
                }
                else
                {
                    cit.plus(z * 8L).store(0L);
                }
                z += 1;
            }
            dir.plus(e * 16L).plus(0L).store(Loader.clTab[cr].type);
            dir.plus(e * 16L).plus(8L).store(cit);
            e += 1;
            ci += 1;
        }
        dir.plus(e * 16L).plus(0L).store(0L);          // sentinel: interfaceType 0 ends the directory
        dir.plus(e * 16L).plus(8L).store(0L);
        Address type = Heap.allocData(ObjectModel.TYPE_SIZE);
        type.plus(0L).store((long) (16 + n * 8));
        type.plus(ObjectModel.TYPE_SUPER_OFFSET).store(Loader.objectTypeAddr());   // NOT 0 -- see finishLambdaClass
        type.plus(16L).store(dir);
        // TIB { Type, Object's vtable } -- see finishLambdaClass. An annotation instance is synthesised the
        // same way and has the same gap: unregistered, itable-only, so equals/hashCode/toString on one had
        // nowhere to resolve. It also has to be the same SHAPE as a lambda TIB, because both live in
        // lambdaTibRoots and refillSynthTibVtables walks that one array.
        int onv2 = Loader.objectVtableCount();
        Address tib = Heap.allocData(8 + onv2 * 8);
        tib.plus(0L).store(type);
        Loader.fillObjectVtable(tib);
        patchAnnoContract(tib, onv2, dir);
        annoTibKey.set(annoTibN, ifaceType);
        annoTibVal.set(annoTibN, tib);
        annoTibN += 1;
        if (Loader.lambdaTibRoots != null && Loader.lambdaTibRootN < Loader.MAXLAMBDATIB)
        {
            Loader.lambdaTibRoots.set(Loader.lambdaTibRootN, tib);    // same GC root as a lambda TIB, for the same reason:
            Loader.lambdaTibVtCap[Loader.lambdaTibRootN] = onv2;      //   nothing scanned would otherwise reference it
            Loader.lambdaHostBase.set(Loader.lambdaTibRootN, Address.zero());        // an annotation instance has no host class
            Loader.lambdaHostNameOff[Loader.lambdaTibRootN] = 0;
            Loader.lambdaTibFilled[Loader.lambdaTibRootN] = false;    // EXPLICIT: allocArray does not zero its elements on
                                                        //   this VM, so a garbage `true` here would skip a TIB
                                                        //   that still needs filling -- a 0 vtable slot, i.e.
                                                        //   a wild branch from baked code. Same trap the SMP
                                                        //   arc hit with taskIdle/coreSched/gcParked.
            Loader.lambdaTibRootN += 1;
        }
        return tib;
    }

    /**
     * Build the annotation object for the annotation whose element_value_pairs start at {@code pairsPos}, as
     * an instance of interface {@code ifaceReg}.
     *
     * <p>ELEMENT TYPES: primitives, {@code String} and arrays of those. {@code Class} ('c'), enum ('e') and
     * nested annotation ('@') elements are left NULL and reported by name -- deliberately, because resolving
     * one demand-loads a class, and a load re-parses a blob and clobbers {@code gcp}/{@code gbase} underneath
     * this walk. That is the same hazard recorded for {@code buildLambdaTib}; the fix is the note-and-retry
     * route the static/class-literal paths already use, and it belongs in its own increment rather than
     * bolted into the first one.
     */
    static long buildAnnoObject(Address base, Address pairsPos, int ifaceReg)
    {
        Address tib = annoTibFor(ifaceReg);
        if (tib.isZero())
        {
            return 0L;
        }
        Loader.parseForMethods(base, Loader.blobLenOf(base));         // a first TIB compiles the contract helpers, which
                                                        //   re-parses; phase 1 reads names through gcp
        int n = Loader.clTab[ifaceReg].ifmCount;
        // ONE EXTRA TRAILING WORD holds this annotation's own Class mirror, which is what
        // Annotation.annotationType() answers. Keeping it IN THE INSTANCE is what lets that method reuse the
        // same two-instruction thunk shape as every element accessor -- see annoTibFor.
        Address obj = Heap.alloc(16 + (n + 1) * 8);
        obj.plus(ObjectModel.TIB_OFFSET).store(tib);
        obj.plus(16L).plus(n * 8L).store(ClassMirrors.classMirror(Loader.clTab[ifaceReg].type));
        int start = Loader.clTab[ifaceReg].ifmStart;
        int pendMark = annoPendN;                       // a NESTED build drains only what it adds past here
        int pairs = u2(pairsPos);
        // PHASE 1 -- every element the annotation USE writes, decoded against the annotated class's blob.
        boolean[] filled = new boolean[n];
        int s = 0;
        while (s < n)
        {
            Address p = pairsPos.plus(2L);
            int k = 0;
            while (k < pairs)
            {
                int nameIdx = u2(p);
                if (Loader.utf8EqAt(base, Loader.gcp[nameIdx], Loader.ifBase.get(start + s), Loader.ifNameOff[start + s]))
                {
                    annoPendSlot = s;                   // a 'c'/'e' value notes ITS slot, not a global one
                    annoArrayTib = annoArrayTibFor(Loader.ifBase.get(start + s), Loader.ifDescOff[start + s]);
                    annoArrayPrim = annoArrayPrimFor(Loader.ifBase.get(start + s), Loader.ifDescOff[start + s]);
                    obj.plus(16L).plus(s * 8L).store(annoElementValue(base, p.plus(2L)));
                    annoPendSlot = -1;
                    annoArrayTib = Address.zero();
                    annoArrayPrim = 0;
                    filled[s] = true;
                    break;
                }
                p = Annotations.skipElementValue(p.plus(2L));
                k += 1;
            }
            s += 1;
        }
        // PHASE 2 -- DEFAULTS, for every element the use omitted. Strictly after phase 1 and never interleaved
        // with it: the defaults live in the ANNOTATION TYPE's classfile, and reading them re-parses that blob,
        // which clobbers the gcp/gbase cursor phase 1 is walking. Two passes cost one extra parse and make the
        // ordering impossible to get wrong.
        //
        // Not optional. An annotation use writes only the elements it overrides -- picocli's
        // `@Command(name = "junit")` leaves a dozen defaulted -- so without this those read null and the
        // library NPEs on its own annotation. That is exactly how this surfaced.
        applyAnnoDefaults(obj.toObjectReference(), ifaceReg, filled, n);
        // PHASE 3 -- the elements that need a CLASS RESOLVED ('c' and 'e'). Deferred to here for the reason
        // phases 1 and 2 are separated at all: resolving demand-loads, a load re-parses a blob, and that
        // clobbers the gcp/gbase cursor a walk is standing on. Nothing is walking now.
        //
        // The pending entries hold ABSOLUTE Utf8 addresses, which survive a re-parse because blobs do not move
        // -- only the parse STATE does. That is the same property applyAnnoDefaults relies on.
        drainAnnoPending(obj.toObjectReference(), pendMark);
        return obj.toLong();
    }

    /**
     * Fill the elements a use omitted from the annotation TYPE's {@code AnnotationDefault} attributes.
     *
     * <p>An element's default is stored as a single {@code element_value} on the interface's own method, so
     * this is the same decode as a written pair -- only the blob differs. Elements with neither a written value
     * nor a default (a mandatory element) keep 0/null, which is what the classfile says.
     */
    private static void applyAnnoDefaults(ObjectReference obj, int ifaceReg, boolean[] filled, int n)
    {
        int start = Loader.clTab[ifaceReg].ifmStart;
        Address ibase = Loader.clTab[ifaceReg].base;
        // Snapshot the element NAMES as absolute Utf8 addresses BEFORE re-parsing: ifNameOff[] is an offset
        // into ifBase[], and the parse below is what makes those offsets resolvable against a different blob.
        AddressArray want = AddressArray.create(n);
        AddressArray defArrayTib = AddressArray.create(n);
        int[] defArrayPrim = new int[n];
        int s = 0;
        while (s < n)
        {
            want.set(s, filled[s] ? Address.zero() : Loader.ifBase.get(start + s).plus(Loader.ifNameOff[start + s]));
            // Computed HERE, before the re-parse: annoArrayTibFor reads the element method's descriptor
            // through gcp/clTab, and the parse below moves that cursor to a different blob.
            defArrayTib.set(s, filled[s] ? Address.zero() : annoArrayTibFor(Loader.ifBase.get(start + s), Loader.ifDescOff[start + s]));
            defArrayPrim[s] = filled[s] ? 0 : annoArrayPrimFor(Loader.ifBase.get(start + s), Loader.ifDescOff[start + s]);
            s += 1;
        }
        Loader.parseForMethods(ibase, Loader.blobLenOf(ibase));
        Address p = Loader.gMethodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            int nameOff = Loader.gcp[u2(p.plus(2))];
            int slot = -1;
            int j = 0;
            while (j < n)
            {
                if (!want.get(j).isZero() && Loader.utf8EqAt(ibase, nameOff, want.get(j), 0))
                {
                    slot = j;
                    break;
                }
                j += 1;
            }
            if (slot >= 0)
            {
                Address q = p.plus(8);
                int a = 0;
                while (a < attrs)
                {
                    int anIdx = u2(q);
                    q = q.plus(2);
                    int alen = u4(q);
                    q = q.plus(4);
                    if (Utf8.utf8IsAtBase(ibase, Loader.gcp[anIdx], Magic.bytes("AnnotationDefault")))
                    {
                        annoPendSlot = slot;
                        annoArrayTib = defArrayTib.get(slot);   // computed BEFORE this parse: see applyAnnoDefaults
                        annoArrayPrim = defArrayPrim[slot];
                        obj.toAddress().plus(16L).plus(slot * 8L).store(annoElementValue(ibase, q));
                        annoPendSlot = -1;
                        annoArrayTib = Address.zero();
                        annoArrayPrim = 0;
                        break;
                    }
                    q = q.plus(alen);
                    a += 1;
                }
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
    }

    /** Decode one {@code element_value} to the word an element method should return. */
    private static long annoElementValue(Address base, Address p)
    {
        int tag = u1(p);
        int idx = u2(p.plus(1));
        if (tag == 0x73)                                // 's' -- a String
        {
            return internUtf8Obj(base, Loader.gcp[idx]);
        }
        if (tag == 0x42 || tag == 0x43 || tag == 0x49 || tag == 0x53 || tag == 0x5A)
        {
            return (long) u4(base.plus(Loader.gcp[idx]));          // B C I S Z -- all CONSTANT_Integer
        }
        if (tag == 0x4A || tag == 0x44)                 // 'J' / 'D' -- CONSTANT_Long/Double, two u4 halves; a
        {                                               //   double's raw bits, as the slot keeps them
            return ((long) u4(base.plus(Loader.gcp[idx])) << 32) | ((long) u4(base.plus(Loader.gcp[idx] + 4)) & 0xFFFFFFFFL);
        }
        if (tag == 0x46)                                // 'F' -- CONSTANT_Float: its raw bits, zero-extended,
        {                                               //   as a compiled putfield leaves a float. Unhandled, a
            return (long) u4(base.plus(Loader.gcp[idx])) & 0xFFFFFFFFL;   // float or double element read 0.0
        }
        if (tag == 0x40)                                // '@' -- a nested annotation
        {
            return annoNestedValue(base, p.plus(1L));
        }
        if (tag == 0x5B && annoArrayPrim != 0)          // '[' of a PRIMITIVE element type
        {
            return annoPrimArray(base, p);
        }
        if (tag == 0x5B)                                // '[' -- array of element_values
        {
            int count = u2(p.plus(1));
            Address arr = Heap.allocArray(count, 8);
            // TYPE it as Object[]: a RAW 8-byte-element array is not traced as references, so the Strings this
            // array holds would be reachable from nothing the collector follows and swept out from under it.
            // TYPE IT BY THE ELEMENT METHOD'S OWN DESCRIPTOR, not as Object[]. Object[] traces correctly but is
            // the WRONG TYPE: `@Command`'s String[] elements reach picocli as `cmd.customSynopsis().clone()`
            // followed by `checkcast [Ljava/lang/String;`, and an Object[] fails that cast. Falling back to
            // Object[] only when the element type is unresolvable keeps the GC tracing either way -- what must
            // never happen is a RAW array, whose elements are not traced as references at all.
            Address at = !annoArrayTib.isZero() ? annoArrayTib : ArrayTypes.refArrayTib(Loader.objectTypeAddr());
            if (!at.isZero())
            {
                arr.plus(ObjectModel.TIB_OFFSET).store(at);
            }
            Address q = p.plus(3L);
            int i = 0;
            int outerElem = annoPendElem;
            while (i < count)
            {
                // An element INSIDE an array keeps the enclosing value SLOT and adds its own INDEX, so a
                // 'c'/'e' value here can be noted and resolved in phase 3 like any other. Suppressing the
                // note (what this did before) left a Class[] element reading null for ever -- picocli's
                // @Option(converter = ...) is exactly that shape.
                annoPendElem = i;
                arr.plus(24L).plus(i * 8L).store(annoElementValue(base, q));
                q = Annotations.skipElementValue(q);
                i += 1;
            }
            annoPendElem = outerElem;
            return arr.toLong();
        }
        if (tag == 0x63)                                // 'c' -- a Class literal; descriptor Utf8
        {
            noteAnnoPending(0, base.plus(Loader.gcp[idx]), Address.zero());
            return 0L;                                  // filled by drainAnnoPending
        }
        if (tag == 0x65)                                // 'e' -- an enum constant: type descriptor + name
        {
            noteAnnoPending(1, base.plus(Loader.gcp[idx]), base.plus(Loader.gcp[u2(p.plus(3))]));
            return 0L;
        }
        return 0L;
    }

    /**
     * A {@code '['} element of a PRIMITIVE component -- {@code int[] ints() default {1, 2, 3}} -- as a real
     * primitive array: that component's element width and the interned primitive-array TIB {@code new int[]}
     * uses. It used to be built as 8-byte reference slots, so an {@code int[]} was malformed (the reader
     * crashed in {@code Arrays.toString}) and an empty one came back typed {@code Object[]}.
     */
    private static long annoPrimArray(Address base, Address p)
    {
        int count = u2(p.plus(1));
        int atype = Primitives.atypeForDescChar(annoArrayPrim);
        int esz = RuntimeEntrypoints.arrayElemSizeOfAtype(atype);
        Address arr = Heap.allocArray(count, esz);
        arr.plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.primArrayTib(atype));
        Address q = p.plus(3L);
        int i = 0;
        while (i < count)
        {
            long v = annoElementValue(base, q);         // B C I S Z F: the value or raw bits; J D: 64 bits
            Address at = arr.plus(ObjectModel.ARRAY_BASE_OFFSET + (long) i * esz);
            if (esz == 1)
            {
                at.store((byte) v);
            }
            else if (esz == 2)
            {
                at.store((byte) v);
                at.plus(1L).store((byte) (v >>> 8));
            }
            else if (esz == 4)
            {
                at.store((int) v);
            }
            else
            {
                at.store(v);
            }
            q = Annotations.skipElementValue(q);
            i += 1;
        }
        return arr.toLong();
    }

    /**
     * A {@code '@'} element: the nested annotation, built by {@link #buildAnnoObject} like any other -- it was
     * left null, so every {@code @Repeatable} container read {@code value() == null}. {@code at} is the nested
     * {@code annotation} structure ({@code u2 type_index}, then its element-value pairs).
     *
     * <p>A build is not re-entrant on its own, and this one runs INSIDE an outer build's walk:
     * <ul>
     *   <li>the decoder's per-element globals (slot, array index, array TIB and component) are saved and
     *       restored around it;</li>
     *   <li>its pending Class/enum values drain against IT -- {@link #drainAnnoPending} drains from the mark
     *       {@code buildAnnoObject} takes, not the outer build's entries;</li>
     *   <li>and the parse cursor is re-pointed at {@code base} after it, because applying its defaults
     *       re-parsed its own type's blob and the outer walk reads element names through that cursor.</li>
     * </ul>
     */
    private static long annoNestedValue(Address base, Address at)
    {
        int nreg = Annotations.loadAnnoTypeByDescAbs(base.plus(Loader.gcp[u2(at)]));
        if (nreg < 0)
        {
            Loader.parseForMethods(base, Loader.blobLenOf(base));
            return 0L;
        }
        Loader.parseForMethods(base, Loader.blobLenOf(base));         // a load re-parses: the build reads names through gcp
        int sSlot = annoPendSlot;
        int sElem = annoPendElem;
        Address sTib = annoArrayTib;
        int sPrim = annoArrayPrim;
        annoPendSlot = -1;
        annoPendElem = -1;
        annoArrayTib = Address.zero();
        annoArrayPrim = 0;
        long obj = buildAnnoObject(base, at.plus(2L), nreg);
        annoPendSlot = sSlot;
        annoPendElem = sElem;
        annoArrayTib = sTib;
        annoArrayPrim = sPrim;
        Loader.parseForMethods(base, Loader.blobLenOf(base));
        return obj;
    }

    /**
     * The array TIB an element of declared type {@code ()[Lfoo;} needs, or 0 if it is not an array of a
     * resolvable reference type. Read from the element METHOD's descriptor, which is the only place the
     * element's real type is written down -- an {@code element_value} says what a value IS, never what the
     * declaration expects.
     */
    /** The primitive component char of an element method's {@code ()[X} return type, or 0 for anything else. */
    private static int annoArrayPrimFor(Address base, int descOff)
    {
        int len = u2(base.plus(descOff));
        int i = 0;
        while (i < len && u1(base.plus(descOff + 2 + i)) != 0x29)   // ')'
        {
            i += 1;
        }
        if (i + 2 >= len || u1(base.plus(descOff + 2 + i + 1)) != 0x5B)  // '['
        {
            return 0;
        }
        int c = u1(base.plus(descOff + 2 + i + 2));
        return c == 0x4C || c == 0x5B ? 0 : c;          // 'L' / '[': a reference component
    }

    private static Address annoArrayTibFor(Address base, int descOff)
    {
        int len = u2(base.plus(descOff));
        int i = 0;
        while (i < len && u1(base.plus(descOff + 2 + i)) != 0x29)   // ')'
        {
            i += 1;
        }
        int ret = descOff + 2 + i + 1;                  // the return descriptor, just past ')'
        if (ret - descOff - 2 >= len || u1(base.plus(ret)) != 0x5B)  // '['
        {
            return Address.zero();
        }
        Address et = ArrayTypes.elementTypeOfArrayDescN(base, ret, len - (ret - descOff - 2));
        return et.isZero() ? Address.zero() : ArrayTypes.refArrayTib(et);
    }

    private static void noteAnnoPending(int kind, Address a, Address b)
    {
        if (annoPendSlot < 0)
        {
            return;                                     // outside any element -- nothing to fill in
        }
        if (annoPendKind == null)
        {
            annoPendKind = new int[MAXANNOPEND];
            annoPendA = AddressArray.create(MAXANNOPEND);
            annoPendB = AddressArray.create(MAXANNOPEND);
            annoPendAt = new int[MAXANNOPEND];
            annoPendIdx = new int[MAXANNOPEND];
        }
        if (annoPendN >= MAXANNOPEND)
        {
            return;
        }
        annoPendKind[annoPendN] = kind;
        annoPendA.set(annoPendN, a);
        annoPendB.set(annoPendN, b);
        annoPendAt[annoPendN] = annoPendSlot;
        annoPendIdx[annoPendN] = annoPendElem;          // -1 = the value slot itself, >=0 = that array element
        annoPendN += 1;
    }

    /**
     * Resolve every noted Class/enum element into {@code obj}. Runs with no classfile walk in progress, so a
     * demand-load here is safe.
     *
     * <p>A Class element whose class cannot be loaded, and an enum constant whose class has no such static,
     * stay NULL rather than becoming something plausible -- the same choice the rest of this runtime makes.
     */
    private static void drainAnnoPending(ObjectReference obj, int mark)
    {
        int n = annoPendN - mark;
        if (n <= 0)
        {
            return;
        }
        // COPY this build's entries out, then release them: a <clinit> or another build below can note new
        // entries, which land at `mark` and would overwrite the ones being resolved.
        int[] kind = new int[n];
        AddressArray ea = AddressArray.create(n);
        AddressArray eb = AddressArray.create(n);
        int[] eat = new int[n];
        int[] eidx = new int[n];
        int c = 0;
        while (c < n)
        {
            kind[c] = annoPendKind[mark + c];
            ea.set(c, annoPendA.get(mark + c));
            eb.set(c, annoPendB.get(mark + c));
            eat[c] = annoPendAt[mark + c];
            eidx[c] = annoPendIdx[mark + c];
            c += 1;
        }
        annoPendN = mark;
        int i = 0;
        while (i < n)
        {
            long v = kind[i] == 0 ? annoClassValue(ea.get(i)) : annoEnumValue(ea.get(i), eb.get(i));
            if (v != 0L)
            {
                if (eidx[i] < 0)
                {
                    obj.toAddress().plus(16L).plus(eat[i] * 8L).store(v);
                }
                else
                {
                    // Reached THROUGH the live object rather than through a cached array address: this runs
                    // after the walks, and resolving a Class here can demand-load and collect. The array is
                    // reachable from the object's value slot, so going via the slot keeps it alive; a stashed
                    // address would not.
                    Address arr = obj.toAddress().plus(16L).plus(eat[i] * 8L).loadAddress();
                    // BOUNDS-CHECK BEFORE STORING. A raw store at a COMPUTED offset: if the slot does not hold
                    // the array this entry was recorded against, or the index is stale, it writes into the
                    // middle of whatever object is there. Not the cause of the corruption this change was once
                    // blamed for -- that was the code arena overlapping the image -- but unchecked computed
                    // stores were three separate defects in one day, so this one does not ship unchecked.
                    // A miss means the pending record is wrong, so it is skipped, not clamped.
                    if (!arr.isZero() && eidx[i] < (int) arr.plus(16L).loadLong())
                    {
                        arr.plus(24L).plus(eidx[i] * 8L).store(v);
                    }
                }
            }
            i += 1;
        }
    }

    /** A {@code 'c'} element: a field DESCRIPTOR Utf8 ("Lcom/x/Foo;", "I", "[I") -> its Class mirror, or 0. */
    static long annoClassValue(Address descU)
    {
        int len = u2(descU);
        if (len == 1)
        {
            return Primitives.primitiveMirror(u1(descU.plus(2)));      // I J Z C B S F D V
        }
        int dims = 0;                                   // an ARRAY class literal -- int[].class, String[][].class
        while (dims < len && u1(descU.plus(2 + dims)) == 0x5B)  // -- read null before; '[' counts the dimensions
        {
            dims += 1;
        }
        if (dims > 0 && dims == len - 1)
        {
            return ArrayTypes.primArrayMirror(u1(descU.plus(2 + dims)), dims);
        }
        if (len - dims < 3 || u1(descU.plus(2 + dims)) != 0x4C)
        {
            return 0L;
        }
        byte[] name = new byte[len - dims - 2];
        int i = 0;
        while (i < name.length)
        {
            name[i] = (byte) u1(descU.plus(2 + dims + 1 + i));
            i += 1;
        }
        int ci = Loader.classIndexByName(name);
        if (ci < 0)
        {
            Loader.loadClassIncremental(name);                 // safe here: no walk is in progress
            ci = Loader.classIndexByName(name);
        }
        if (ci < 0)
        {
            return 0L;
        }
        long m = ClassMirrors.classMirror(Loader.clTab[ci].type);
        return dims == 0 ? m : ArrayTypes.arrayOf(m, dims);
    }

    /** An {@code 'e'} element: enum type descriptor + constant name -> the constant object, or 0. */
    private static long annoEnumValue(Address typeU, Address nameU)
    {
        int tlen = u2(typeU);
        if (tlen < 3 || u1(typeU.plus(2)) != 0x4C)
        {
            return 0L;
        }
        byte[] cls = new byte[tlen - 2];
        int i = 0;
        while (i < tlen - 2)
        {
            cls[i] = (byte) u1(typeU.plus(2 + 1 + i));
            i += 1;
        }
        int ci = Loader.classIndexByName(cls);
        if (ci < 0)
        {
            Loader.loadClassIncremental(cls);
            ci = Loader.classIndexByName(cls);
        }
        if (ci < 0)
        {
            return 0L;
        }
        Loader.ensureClinit(ci);                               // the constants ARE statics, and only <clinit> sets them
        int nlen = u2(nameU);
        byte[] fname = new byte[nlen];
        int k = 0;
        while (k < nlen)
        {
            fname[k] = (byte) u1(nameU.plus(2 + k));
            k += 1;
        }
        long slot = Loader.staticSlotOf(cls, fname);
        return slot == 0L ? 0L : Address.fromLong(slot).loadLong();
    }

    /** A {@code java/lang/String} object for the Utf8 at {@code off} in {@code base} (no cp-index cache). */
    private static long internUtf8Obj(Address base, int off)
    {
        int len = u2(base.plus(off));
        Address bytes = Heap.allocArray(len, 1);
        Address bt = ArrayTypes.byteArrayTib();
        if (!bt.isZero())
        {
            bytes.plus(ObjectModel.TIB_OFFSET).store(bt);
        }
        int i = 0;
        while (i < len)
        {
            bytes.plus(24).plus(i).store((byte) u1(base.plus(off + 2 + i)));
            i += 1;
        }
        Address tib = Loader.stringTib();
        if (tib.isZero())
        {
            return bytes.toLong();
        }
        Address obj = Heap.alloc(Loader.stringSize());
        obj.plus(0L).store(tib);
        obj.plus(16L).store(bytes);
        return obj.toLong();
    }
}
