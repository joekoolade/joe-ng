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
import magic.ObjectReference;
import magic.DataReference;
import magic.AddressArray;
import magic.DataReferenceArray;
import magic.Magic;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Array Types (JikesRVM: {@code RVMArray}). Split out of {@link Loader}.
 *
 * <p>An array carries a real Type (via a 1-word array TIB in its header @0) so checkcast/instanceof against an
 * array class resolve precisely and {@code arr instanceof Object} walks the super chain. Primitive-array Types are
 * cached by atype; reference-array Types by element Type. All live in the per-batch demand heap (recreated after each
 * resetLoader). A raw array (VM-internal byte buffers, boot-time) keeps a small element size in @0 instead --
 * untyped, never checkcast, distinguished by magnitude (&lt;= MAX_RAW_ARRAY_TIB).
 */
final class ArrayTypes
{
    private ArrayTypes()
    {
    }

    // Array Type cache (per demand-load batch, since resetLoader reclaims the heap under them). primArrTib is
    // indexed by newarray atype (4..11); refArr* is a small element-Type-keyed registry for reference arrays.
    static DataReferenceArray primArrTib;     // arrayTib per primitive atype (0 = not yet created)
    static boolean[] primArrAdopted;   // true = writer-baked image TIB (never refill: its Object
                                               // impls are baked code; loader bufs die with the batch heap)
    static DataReferenceArray refArrElem;     // element Type key (reference arrays)
    static DataReferenceArray refArrTib;      // arrayTib for that element
    static boolean[] refArrAdopted;    // true = writer-baked image TIB (never refill)
    static int refArrCount;
    /** java/lang/Object's Type (array super), or 0 if Object isn't loaded yet. */
    /** Cached Types of the two interfaces every array implements; 0 until each is found (Types never move). */
    static DataReference cloneableTypeCache;
    static DataReference serializableTypeCache;
    /** {@code clTab[0, arrayIfaceScanned)} has been searched for those two names. */
    static int arrayIfaceScanned;

    /** True if {@code type} is an array Type (its instanceSize slot carries {@link ObjectModel#ARRAY_TYPE_TAG}). */
    static boolean isArrayType(Address type)
    {
        if (type.isZero())
        {
            return false;
        }
        long instSize = type.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong();
        return (instSize & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG;
    }

    /**
     * The JVMS descriptor char of a PRIMITIVE array Type's element ('I' for {@code int[]}), or 0.
     *
     * <p>Recovered by identity against the per-atype cache rather than from the Type itself, because the
     * element size cannot tell {@code byte[]} from {@code boolean[]} (both 1) or {@code int[]} from
     * {@code float[]} (both 4) -- and because that works for a writer-BAKED array Type the loader merely
     * adopted, which no metal-side field would have been filled in for.
     */
    static int primElemCharOf(Address type)
    {
        int atype = 4;
        while (atype < 12)
        {
            Address tib = primArrTib.get(atype).toAddress();
            if (!tib.isZero() && tib.loadAddress().EQ(type))
            {
                return Primitives.primDescChar(atype);
            }
            atype += 1;
        }
        return 0;
    }

    /**
     * The {@code Class} mirror of an ARRAY Type's COMPONENT, or 0 when {@code type} is not an array (or is a
     * reference array whose element Type never resolved).
     *
     * <p>THE PRIMITIVE CASE NEEDS MORE THAN THE ELEMENT SLOT, which is why this is not one load. An array
     * Type's {@code ARRAY_TYPE_ELEMENT_OFFSET} exists for reference-array COVARIANCE and is 0 for a primitive
     * element by construction -- so reading it alone answered NULL for every {@code byte[]}/{@code int[]}, and
     * null is not an error a caller sees: it is the answer for "not an array", so every caller branching on it
     * took the not-an-array path for a real array. The element SIZE cannot stand in either
     * ({@code byte[]}/{@code boolean[]} are both 1, {@code int[]}/{@code float[]} both 4), so the descriptor
     * char is recovered by IDENTITY against the per-atype TIB cache -- the same trick {@link Loader#classNameLen}
     * already uses to render {@code "[I"}, and one that also works for a writer-BAKED array Type the loader
     * merely adopted, which no metal-side field would have been filled in for.
     */
    static long arrayComponentMirror(Address type)
    {
        if (type.isZero() || !isArrayType(type))
        {
            return 0L;
        }
        Address el = type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadAddress();
        if (!el.isZero())
        {
            return ClassMirrors.classMirror(el);                      // a REFERENCE array: the element Type is right there
        }
        int c = primElemCharOf(type);
        return c == 0 ? 0L : Primitives.primitiveMirror(c);         // 0 = a ref array whose element never resolved
    }

    /**
     * A reflectively created array of {@code length} elements whose COMPONENT Type is {@code compType}:
     * {@code java.lang.reflect.Array.newInstance}'s allocation, and {@code Arrays.copyOf}/{@code toArray}
     * behind it.
     *
     * <p>THE PRIMITIVE ARM IS WHY THIS EXISTS. The caller used to allocate 8-byte REFERENCE elements
     * unconditionally and hang {@code refArrayTib} off them, which for a primitive component gives an array of
     * the wrong element WIDTH under a reference TIB -- the right length, reflectively readable, and wrong the
     * moment ordinary bytecode indexes past element 0. It was unreachable only because
     * {@code getComponentType()} answered null for a primitive array; fixing that opens the route, so the two
     * belong together.
     */
    static long newArrayOfComponent(Address compType, int length)
    {
        if (!compType.isZero() && Primitives.isPrimitiveType(compType))
        {
            int atype = Primitives.atypeForDescChar(Primitives.primTypeChar(compType));
            if (atype >= 0)
            {
                ObjectReference arr = Heap.allocArray(length, RuntimeEntrypoints.arrayElemSizeOfAtype(atype)).toObjectReference();
                arr.toAddress().store(primArrayTib(atype));  // the SAME interned TIB `new int[]` uses
                return arr.toLong();
            }
        }
        ObjectReference arr = Heap.allocArray(length, ObjectModel.WORD).toObjectReference();
        if (!compType.isZero())
        {
            arr.toAddress().store(refArrayTib(compType));    // typed [L<component>;, interned per element
        }
        return arr.toLong();                                       // else a raw untyped array: still fills and returns
    }

    /**
     * {@code Class.forName} of an ARRAY name -- {@code "[I"}, {@code "[[Ljava.lang.String;"} -- already turned to
     * the internal form {@code desc}. It used to look the descriptor up as a CLASS name and answer not found, so
     * every reflective route to an array class by name threw ClassNotFoundException.
     *
     * <p>The descriptor must be exact: one to 255 '[', then one primitive char that is not 'V', or
     * {@code L<name>;} with a non-empty name; anything else is not found, as on stock. A reference element is
     * LOADED but not INITIALIZED -- stock's {@code forName} initializes the class it names, and an array class has
     * no initializer, so its element's {@code <clinit>} waits for the element's own first active use.
     */
    static long arrayForName(byte[] desc)
    {
        int len = desc.length;
        int dims = 0;
        while (dims < len && desc[dims] == 0x5B)
        {
            dims += 1;
        }
        if (dims > 255 || dims == len)
        {
            return 0L;
        }
        int c = desc[dims];
        if (c == 0x4C)                                  // 'L': a class element, "L<name>;"
        {
            if (len < dims + 3 || desc[len - 1] != 0x3B)
            {
                return 0L;
            }
            byte[] el = new byte[len - dims - 2];
            int k = 0;
            while (k < el.length)
            {
                int b = desc[dims + 1 + k];
                if (b == 0x3B || b == 0x5B)             // ';' or '[' inside the name: malformed
                {
                    return 0L;
                }
                el[k] = (byte) b;
                k += 1;
            }
            if (ClassRegistry.classIndexByName(el) < 0)
            {
                if (Loader.isDenylisted(Loader.utf8Blob(el).toAddress(), 0) || Loader.loadClassIncremental(el).isZero())
                {
                    return 0L;
                }
            }
        }
        else if (Primitives.atypeForDescChar(c) < 0 || len != dims + 1)
        {
            return 0L;                                  // 'V', an unknown char, or trailing bytes
        }
        Address tib = arrayTibOfDesc(Loader.utf8Blob(desc).toAddress(), 0);
        return tib.toLong() <= ObjectModel.MAX_RAW_ARRAY_TIB ? 0L : ClassMirrors.classMirror(tib.loadAddress());
    }

    /**
     * The mirror of {@code dims}-dimensional array of the class whose mirror is {@code elemMirror}, or
     * {@code elemMirror} itself for 0 dimensions. Each level is the element Type's reference-array TIB, whose
     * word 0 is the array Type -- the same node {@code getClass()} on such an array answers, so
     * {@code getParameterTypes()[i] == String[].class} holds.
     *
     * <p>ARRAY PARAMETER AND RETURN TYPES USED TO RESOLVE TO NULL ("not modelled"). A null element in a
     * {@code Class[]} is a wrong answer a caller cannot tell from a missing class: re-looking a method up by its
     * own {@code getParameterTypes()} failed for any array parameter (MemberGenericsProbe, through stock
     * {@code TypeVariableImpl.getGenericDeclaration}).
     */
    static long arrayOf(long elemMirror, int dims)
    {
        if (elemMirror == 0L)
        {
            return 0L;
        }
        DataReference type = Address.fromLong(elemMirror).plus(16L).loadDataReference();
        int k = 0;
        while (k < dims)
        {
            Address tib = refArrayTib(type.toAddress());
            if (tib.isZero())
            {
                return 0L;
            }
            type = DataReference.fromAddress(tib.loadAddress());
            k += 1;
        }
        return ClassMirrors.classMirror(type.toAddress());
    }

    /** As {@link #arrayOf}, for an array of the primitive with descriptor char {@code c}: {@code [I}, {@code [[J}. */
    static long primArrayMirror(int c, int dims)
    {
        Address tib = primArrayTib(Primitives.atypeForDescChar(c));
        if (tib.isZero())
        {
            return 0L;
        }
        DataReference type = tib.loadDataReference();
        int k = 1;
        while (k < dims)
        {
            tib = refArrayTib(type.toAddress());
            if (tib.isZero())
            {
                return 0L;
            }
            type = DataReference.fromAddress(tib.loadAddress());
            k += 1;
        }
        return ClassMirrors.classMirror(type.toAddress());
    }

    /** Absolute address of the ARRAY DESCRIPTOR Utf8 ({@code {u2 len}{bytes}}) named by {@code classCp} --
     *  what {@link RuntimeEntrypoints#multiNewArray} walks. Blobs do not move, so this address outlives the compile. */
    static long arrayDescAddr(int classCp)
    {
        return Loader.gView.base.plus((long) Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[classCp]))]).toLong();
    }

    /**
     * Is {@code type} {@code java/lang/Cloneable} or {@code java/io/Serializable} -- the two interfaces every array
     * type implements (JLS 4.10.3)? Allocation- and lock-free, because {@code VM.typeAssignable} asks it from
     * checkcast and instanceof.
     *
     * <p>EACH REGISTRY ENTRY IS SEARCHED ONCE. This used to scan ALL of {@code clTab} on every call while either
     * Type was still unfound -- and a program that never loads one of them (no class it runs implements
     * Cloneable) never finds it, so every array-against-interface type check paid O(classes loaded) for ever.
     * MEASURED by qprof on SslEngineProbe: 76% of 273 samples in this method, under instanceOf/castOk, with the
     * TLS handshake starved. A Type can only be one of these two if its class is REGISTERED, and {@code clTab}
     * is append-only ({@code type} is written once, before {@code clCount} moves -- see the ctProbe note), so
     * the entries added since the last call are the only ones that can change the answer.
     */
    static boolean isArrayInterface(Address type)
    {
        if (type.isZero())
        {
            return false;
        }
        if (cloneableTypeCache.isZero() || serializableTypeCache.isZero())
        {
            int n = ClassRegistry.clCount;
            int i = arrayIfaceScanned;
            while (i < n)
            {
                if (ClassRegistry.clTab[i] != null)
                {
                    if (cloneableTypeCache.isZero()
                            && Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff, Magic.bytes("java/lang/Cloneable")))
                    {
                        cloneableTypeCache = ClassRegistry.clTab[i].type;
                    }
                    else if (serializableTypeCache.isZero()
                            && Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff, Magic.bytes("java/io/Serializable")))
                    {
                        serializableTypeCache = ClassRegistry.clTab[i].type;
                    }
                }
                i += 1;
            }
            arrayIfaceScanned = n;
        }
        return type.EQ(cloneableTypeCache.toAddress()) || type.EQ(serializableTypeCache.toAddress());
    }

    /** Allocate an array Type {tag|elemSize, super=Object, itableDir=0, elementType} + a 1-word TIB; return the TIB. */
    private static DataReference makeArrayTib(int elemSize, Address elementType)
    {
        DataReference type = Heap.allocType();
        DataReference objType = TibBuilder.objectTypeAddr();
        type.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).store(ObjectModel.ARRAY_TYPE_TAG | (long) elemSize);
        type.plus(ObjectModel.TYPE_SUPER_OFFSET).store(objType);            // arr instanceof Object
        type.plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).store(0L);            // Cloneable/Serializable: later
        type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).store(elementType);
        type.plus(ObjectModel.TYPE_DEPTH_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_DISPLAY_OFFSET).store(0L);
        type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).store(1L);   // empty-but-computed bitmap
        type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).plus(8L).store(0L);
        DynamicTypeCheck.buildDisplay(type.toAddress(), objType.toAddress());                     // depth 1, display [Object, self]
        // The array TIB carries java/lang/Object's vtable so an invokevirtual on an array works. An array's static
        // type is Object at a dispatch site (e.g. Arrays.deepEquals0's `e1.equals(e2)`, or `element.toString()` in
        // String.valueOf/join), so the receiver's TIB must resolve equals/hashCode/toString to Object's impls.
        // Without the vtable the dispatch read past a 1-word TIB and BLR'd garbage (a wild branch to the image entry).
        int nv = TibBuilder.objectVtableCount();
        DataReference tib = Heap.allocTib(8 + nv * 8);
        tib.plus(ObjectModel.TIB_TYPE_SLOT * 8).store(type);                // TIB[0] = Type
        TibBuilder.fillObjectVtable(tib.toAddress());                                                   // TIB[1..] = Object's vtable slots
        return tib;
    }

    /** Re-copy Object's (now-filled) vtable into every cached array TIB. Called at the end of {@link Loader#loadAll},
     *  after Object's body is compiled, to repair any array TIB that was created with a still-empty Object vtable
     *  (else an invokevirtual on that array reads a 0 slot and the null-vtable guard throws AIOOBE). */
    static void refillArrayTibVtables()
    {
        if (primArrTib != null)
        {
            int a = 0;
            while (a < primArrTib.length())
            {
                if (!primArrTib.get(a).isZero() && !primArrAdopted[a])
                {
                    TibBuilder.fillObjectVtable(primArrTib.get(a).toAddress());                 // adopted (baked) TIBs keep their writer impls
                }
                a += 1;
            }
        }
        int r = 0;
        while (r < refArrCount)
        {
            if (!refArrTib.get(r).isZero() && !refArrAdopted[r])
            {
                TibBuilder.fillObjectVtable(refArrTib.get(r).toAddress());                      // adopted (baked) TIBs keep their writer impls
            }
            r += 1;
        }
    }

    /** The array TIB for a primitive array of the given newarray {@code atype}; adopted or created on demand. */
    static Address primArrayTib(int atype)
    {
        if (atype < 0 || atype >= 12)
        {
            return Address.zero();
        }
        if (primArrTib.get(atype).isZero())
        {
            // M8 array-Type unification: ADOPT the writer-baked canonical array TIB (VM.primArrayTibs,
            // 8 longs indexed atype-4) so byte[] is ONE class across both worlds -- writer-tagged
            // arrays and loader arrays carry the same Type node, and instanceof/checkcast targets
            // resolve to it on both sides. Metal-built only if the image predates the table.
            Address tab = VM.primArrayTibs;
            Address baked = tab.isZero() || atype < 4 ? Address.zero() : tab.plus((long) (atype - 4) * 8L).loadAddress();
            if (!baked.isZero())
            {
                primArrTib.set(atype, DataReference.fromAddress(baked));
                primArrAdopted[atype] = true;
                if (Loader.LOAD_LOG)
                {
                    Uart.write(Magic.bytes("  arrayadopt "));
                    Uart.putc(0x5B);                        // '['
                    Uart.putc(Primitives.primDescChar(atype));
                    Uart.putc(0x0A);
                }
            }
            else
            {
                primArrTib.set(atype, makeArrayTib(Primitives.primElemSize(atype), Address.zero()));
            }
        }
        return primArrTib.get(atype).toAddress();
    }

    /** Array TIB for a byte[] (newarray atype 8) — used to type String value arrays / interned literals. */
    static Address byteArrayTib()
    {
        return primArrayTib(8);
    }

    /** Array TIB for the array descriptor at {@code base+nameOff} (a Utf8 like "[B" / "[Ljava/lang/String;" /
     *  "[[I"), or 0. */
    static Address arrayTibOfDesc(Address base, int nameOff)
    {
        return arrayTibOfDescN(base, nameOff + 2, u2(base.plus(nameOff)));   // skip the u2 length prefix
    }

    /** Array TIB for the {@code n}-byte descriptor at {@code base+off}. Nested descriptors recurse: the
     *  element of "[[I" is "[I", whose Type keys the ref-array cache -- so int[][] shares the SAME canonical
     *  "[I" element node both worlds bake/adopt, and the covariance walk discriminates nested arrays. */
    static Address arrayTibOfDescN(Address base, int off, int n)
    {
        int c = u1(base.plus(off + 1));                     // char after the leading '['
        int atype = Primitives.atypeForDescChar(c);
        if (atype >= 0)
        {
            return primArrayTib(atype);                 // "[<primitive>"
        }
        if (c == 0x5B)                                  // '[': nested -- element desc is this minus one '['
        {
            Address elTib = arrayTibOfDescN(base, off + 1, n - 1);
            return refArrayTib(elTib.isZero() ? Address.zero() : elTib.loadAddress());
        }
        return refArrayTib(elementTypeOfArrayDescN(base, off, n).toAddress());   // "[L<class>;"
    }

    /** Element Type of the {@code n}-byte reference-array descriptor "[L<class>;" at {@code base+off}
     *  (the class's Type, or 0 when unresolved). */
    static DataReference elementTypeOfArrayDescN(Address base, int off, int n)
    {
        if (u1(base.plus(off + 1)) != 0x4C)                 // not 'L'
        {
            return DataReference.zero();
        }
        int i = 0;                                      // strip the leading "[L" and trailing ';' -> class name
        while (i < ClassRegistry.clCount)
        {
            if (Utf8.utf8SliceEq(base, off + 2, n - 3, ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff))
            {
                return ClassRegistry.clTab[i].type;
            }
            i += 1;
        }
        return DataReference.zero();
    }

    /** Array TIB for a reference array with the given element Type; created + cached (keyed by element Type). */
    static Address refArrayTib(Address elementType)
    {
        int i = 0;
        while (i < refArrCount)
        {
            if (refArrElem.get(i).toAddress().EQ(elementType))
            {
                return refArrTib.get(i).toAddress();
            }
            i += 1;
        }
        // M8 ref-array unification: ADOPT the writer-baked ref-array TIB whose element Type matches.
        // The table (VM.refArrayTibs: {elementType, tib} pairs) is keyed by the SAME adopted element
        // Type nodes both worlds share, so a writer-tagged Integer[] and a loader Integer[] carry one
        // TIB -- and the covariance walk compares one set of nodes. Metal-built when not baked.
        // A DATA REFERENCE, not an Address: a freshly built TIB is reachable from nothing else until it is stored in
        // the cache below, and growRefArrCache() allocates first. Held raw, it is no root to the precise stack walk
        // (frames/GC arc S5), so a collection there would free the new TIB and the Type behind it. Found by reading
        // while chasing S6b's Method.call result bug, not by a failure of its own.
        DataReference tib = DataReference.zero();
        boolean adopted = false;
        if (!elementType.isZero())
        {
            Address tab = VM.refArrayTibs;
            long n = VM.refArrayTibCount;
            long k = 0;
            while (k < n)
            {
                if (tab.plus(k * 16L).loadAddress().EQ(elementType))
                {
                    tib = tab.plus(k * 16L).plus(8L).loadDataReference();
                    adopted = true;
                    int r = ClassRegistry.regOfType(elementType);
                    if (r >= 0)
                    {
                        Uart.write(Magic.bytes("  arrayadopt [L"));
                        Loader.writeName(ClassRegistry.clTab[r].base.plus(ClassRegistry.clTab[r].nameOff + 2), u2(ClassRegistry.clTab[r].base.plus(ClassRegistry.clTab[r].nameOff)));
                        Uart.putc(0x3B);                // ';'
                    }
                    else
                    {
                        Uart.write(Magic.bytes("  arrayadopt [[ (nested)"));   // element is itself an array Type
                    }
                    Uart.putc(0x0A);
                    break;
                }
                k += 1;
            }
        }
        if (tib.isZero())
        {
            tib = makeArrayTib(ObjectModel.WORD, elementType);   // reference elements are 8-byte pointers
        }
        if (refArrCount >= refArrTib.length())
        {
            growRefArrCache();
        }
        refArrElem.set(refArrCount, DataReference.fromAddress(elementType));
        refArrTib.set(refArrCount, tib);
        refArrAdopted[refArrCount] = adopted;
        refArrCount += 1;
        return tib.toAddress();
    }

    /**
     * Double the reference-array cache. It used to stop at 64 and SILENTLY stop caching, so every array Type
     * asked for after that was minted afresh: two `Object[][]` nodes, two mirrors, and `Object[][].class`
     * answering false against the erased parameter type of a method -- MemberGenericsProbe after sixteen
     * probes in one batch had filled it. An array Type must be unique like a class Type; identity, covariance
     * and the mirror cache all key on it. Copied by hand: java.util.Arrays is a bake stub here.
     */
    private static void growRefArrCache()
    {
        int n = refArrTib.length() * 2;
        DataReferenceArray elem = DataReferenceArray.create(n);
        DataReferenceArray tibs = DataReferenceArray.create(n);
        boolean[] adopted = new boolean[n];
        int i = 0;
        while (i < refArrCount)
        {
            elem.set(i, refArrElem.get(i));
            tibs.set(i, refArrTib.get(i));
            adopted[i] = refArrAdopted[i];
            i += 1;
        }
        refArrElem = elem;
        refArrTib = tibs;
        refArrAdopted = adopted;
    }

    /**
     * Array TIB for an {@code anewarray} whose element class is Class-entry {@code classCp}.
     *
     * <p>THE ELEMENT CLASS MAY NOT BE REGISTERED YET, and answering that with a typed array Type whose
     * element is 0 is the silent-wrong-answer shape. `tagArray` bakes this TIB as an IMMEDIATE, so -- exactly
     * as for a class literal -- there is no reloc for a later batch to patch: the array carries an element of
     * 0 for the rest of the boot. Nothing then proves `Parameter[] <: Object[]`, and the covariance check
     * fails far from here. The launcher hit it as
     * `ClassCastException: class [ cannot be cast to class [Ljava.lang.Object;` inside
     * `Arrays.stream(getParameters())` -- an array whose element could not even be NAMED.
     *
     * <p>Two responses, in order, and neither invents an element type:
     * <ol>
     *   <li>PULL AND RECOMPILE, the route a class literal and an unresolved static already take. A late
     *       compile can note the class and be retried once with it loaded, which gives the array its real
     *       element Type rather than a substitute.</li>
     *   <li>Failing that (a batch compile, or the retry is spent), type it as {@code Object[]}, which is TRUE
     *       of every reference array (JLS 10.1) even when the exact element class is unknown. That restores
     *       the covariance this bug broke and keeps the elements TRACED.</li>
     * </ol>
     *
     * <p>LEAVING IT RAW WAS TRIED AND IS WRONG, and the reason is recorded elsewhere in this file: a raw
     * array carries no Type, and <b>a raw 8-byte-element array is not traced as references</b> -- the
     * elements would be reachable from nothing the collector follows and swept out from under the caller.
     * {@code castOk} does trust the verifier for a raw array, so the cast would have passed; trading a failed
     * cast for a swept object is a far worse bargain, and the failure it produces is a wild reference rather
     * than a named exception.
     *
     * <p>The residual divergence is stated rather than hidden: an {@code Object[]}-typed array answers FALSE
     * to {@code instanceof Parameter[]}, where stock answers true. That is visible at the caller and only
     * arises on the fallback, which the report below names every time it is taken.
     * <ol>
     * </ol>
     */
    static Address refArrayTibForClass(int classCp)
    {
        DataReference type = DynamicTypeCheck.typeOfClass(classCp);
        if (type.isZero())
        {
            int nameOff = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[classCp]))];
            if (u1(Loader.gView.base.plus(nameOff + 2)) != 0x5B                     // an array-of-array builds its own Type
                    && Loader.lzCompiling && !Loader.lzRetried && Loader.notePullNeeded(Loader.gView.base.plus(nameOff)))
            {
                return Address.zero();                              // discarded compile: retried with it loaded
            }
            reportUntypedArray(Loader.gView.base.plus(nameOff));
            DataReference obj = TibBuilder.objectTypeAddr();
            return obj.isZero() ? Address.zero() : refArrayTib(obj.toAddress());   // Object[]: covariance holds, elements traced
        }
        return refArrayTib(type.toAddress());
    }

    /**
     * Name an {@code anewarray} whose element class could not be resolved, ONCE per element class.
     *
     * <p>Reported rather than left silent for the reason this file keeps relearning: the consequence shows up
     * as a failed cast in unrelated library code, and an array that cannot name its own element is not
     * something any caller can diagnose from there. Deduped through the same table the unresolved-static and
     * null-class-literal reports use, because a hot path would otherwise repeat it per compile.
     */
    private static void reportUntypedArray(Address clsU)
    {
        if (Loader.unresStaticSeen == null)
        {
            Loader.unresStaticSeen = new long[64];
            Loader.unresStaticSeenN = 0;
        }
        int k = 0;
        while (k < Loader.unresStaticSeenN)
        {
            if (Loader.unresStaticSeen[k] == clsU.toLong())
            {
                return;
            }
            k += 1;
        }
        if (Loader.unresStaticSeenN < Loader.unresStaticSeen.length)
        {
            Loader.unresStaticSeen[Loader.unresStaticSeenN] = clsU.toLong();
            Loader.unresStaticSeenN += 1;
        }
        Uart.write(Magic.bytes("\n  ARRAY ELEMENT UNRESOLVED (typed Object[] -- `instanceof X[]` answers false): "));
        Loader.printNameAt(clsU, 0);
        Uart.write(Magic.bytes(" -- "));
        Loader.printWhyUnpulled(clsU);
        Uart.putc(0x0A);
    }
}
