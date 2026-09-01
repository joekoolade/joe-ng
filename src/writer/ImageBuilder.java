package writer;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import classfile.ClassReader;
import compiler.BaselineCompiler;
import compiler.BaselineCompiler.CompiledMethod;
import objectmodel.ObjectModel;
import util.ByteKeyIntTable;
import util.ByteKeySet;
import util.IntVec;
import util.StrIntTable;
import util.StrSet;
import util.Vec;

/**
 * Lays out a graph of methods across classes into one image and relocates the
 * references between them — the core M2 writer job (PLAN.md §4). From an entry
 * method it acts as a tiny class loader: load the owning {@code .class}, compile
 * the method, discover its callees and the classes it instantiates, and repeat.
 * Then it assigns addresses (entry at {@code 0x80000}), lays out a TIB per
 * instantiated class after the code, and patches every {@code BL} to its
 * callee's entry and every {@code new}'s TIB-pointer load to the class's TIB.
 *
 * <p>Method sizes are layout-independent, so it compiles once to discover + size,
 * then recompiles each at its final base (fixing intra-method address loads) and
 * fixes up the inter-method references.
 */
public final class ImageBuilder implements BaselineCompiler.ClassResolver
{

    private static final int WORDS_PER_SLOT = ObjectModel.WORD / 4;   // 8-byte slot = 2 image ints
    private static final int TYPE_WORDS = ObjectModel.TYPE_SIZE / 4;  // Type = { instanceSize, superType }

    /** Descriptor per newarray atype (4..11); mirrors compiler/WriterSymbols.PRIM_ARRAY_DESC. */
    private static final String[] PRIM_ARRAY_DESC = {"[Z", "[C", "[F", "[D", "[B", "[S", "[I", "[J"};

    /** Element size in bytes for a newarray atype (4..11). */
    private static long primArrayElemSize(int atype)
    {
        if (atype == 4 || atype == 8)  { return 1; }   // boolean, byte
        if (atype == 5 || atype == 9)  { return 2; }   // char, short
        if (atype == 6 || atype == 10) { return 4; }   // float, int
        return 8;                                      // double, long
    }

    /** Element class of a single-dim reference-array descriptor "[L<cls>;", or null. */
    private static String refArrayElementOf(String desc)
    {
        if (desc.length() > 3 && desc.startsWith("[L") && desc.endsWith(";"))
        {
            return desc.substring(2, desc.length() - 1);
        }
        return null;
    }

    /** typeWord key of an array descriptor's element: the class of "[L<cls>;", or the element
     *  array desc of a nested "[[...". */
    private static String arrayElementKeyOf(String desc)
    {
        String el = refArrayElementOf(desc);
        return el != null ? el : desc.substring(1);
    }

    /** Collect an array descriptor chain for baking: add {@code desc} and every nested element desc
     *  to {@code refArrDescs} (stopping at the canonical prim arrays), and the base element class
     *  (if any) to {@code elemClasses} so its Type chain lays out. Interface elements join
     *  {@code usedInterfaces} (the covariance walk answers them from itable dirs) when given. */
    private void collectArrayDesc(String desc, StrSet refArrDescs, StrSet elemClasses, StrSet usedInterfaces)
    {
        String d = desc;
        while (d.startsWith("["))
        {
            if (d.length() == 2 && "ZCFDBSIJ".indexOf(d.charAt(1)) >= 0)
            {
                return;                               // canonical prim array: always laid out
            }
            String el = refArrayElementOf(d);
            refArrDescs.add(d);
            if (el != null)
            {
                elemClasses.add(el);
                if (usedInterfaces != null && registry.resolve(el).isInterface())
                {
                    usedInterfaces.add(el);
                }
                return;
            }
            d = d.substring(1);                       // nested: continue with the element desc
        }
    }

    /** The newarray atype (4..11) for a primitive array component type. */
    private static int atypeOfComponent(Class<?> c)
    {
        if (c == boolean.class) { return 4; }
        if (c == char.class)    { return 5; }
        if (c == float.class)   { return 6; }
        if (c == double.class)  { return 7; }
        if (c == byte.class)    { return 8; }
        if (c == short.class)   { return 9; }
        if (c == int.class)     { return 10; }
        return 11;                                     // long
    }
    /** A class-table directory entry: {nameAddr, nameLen, bytesAddr, bytesLen} = 4 longs. */
    private static final int CLASS_ENTRY_WORDS = 4 * WORDS_PER_SLOT;
    /** Entry-called stub whose body the writer fills with <clinit> calls (eager init). */
    private static final String INIT_CLASSES = "vm/VM.initClasses()V";
    private static final String BAKE_RESOLVE = "vm/VM.bakeResolve(I)J";

    // M8 bake stubs -> LAZY cross-world resolution (object-links increment): stock java.base
    // methods the host compiler couldn't compile bake as RESOLVE stubs -- an arg-preserving
    // trampoline that calls VM.bakeResolve(stubIndex), which demand-loads the class through the
    // on-metal loader (against the now-SHARED statics/Types/vtables/itables) and tail-branches to
    // the resolved buffer (memoized in the stub table). Filled during the sizing pass; consulted
    // by the emit pass (deterministic), by generateInitClasses (a stubbed <clinit> is dropped --
    // deferred), and by the statics snapshot (deferred statics come from the seed JVM instead).
    private final StrSet stubbedKeys = new StrSet();
    private final StrIntTable stubIndex = new StrIntTable();   // key -> stub-table index (add order)

    // M8 full bootstrap (path 1), static state: stock java.base methods force-rooted into the compile
    // closure, each compiled address stashed in a VM static so VM.bootstrapProbe can Magic.callN it.
    // The indirection exists because javac cannot name a package-private java.base member (StringUTF16
    // is package-private in java.lang) from the vm/ tree -- the writer links by key instead.
    private static final String[][] BAKE_ROOTS = {
        { "java/lang/StringUTF16.getBytes([BII[BI)V",     "vm/VM.utf16GetBytesAddr" },
        { "java/lang/Integer.formatUnsignedInt(II[BI)V",  "vm/VM.formatUnsignedIntAddr" },
        { "java/lang/Integer.intValue()I",                "vm/VM.integerIntValueAddr" },
        { "java/lang/Integer.valueOf(I)Ljava/lang/Integer;", "vm/VM.integerValueOfAddr" },
        // M8 real vtables: virtual-dispatch TARGETS are not reached by BL relocations, so a method
        // meant to be dispatched through a baked TIB must be rooted here (else its slot is a trap
        // stub). equals() itself contains the probe's invokevirtual through the argument's TIB.
        { "java/lang/Integer.equals(Ljava/lang/Object;)Z", "vm/VM.integerEqualsAddr" },
        { "java/lang/Long.equals(Ljava/lang/Object;)Z",   "vm/VM.longEqualsAddr" },
        { "java/lang/Long.longValue()J",                  "vm/VM.longLongValueAddr" },
        // M8 Strings: valueOf(Z) returns an interned literal -- the ldc-String-object path.
        // length()/charAt() read a baked String back out; coder()/isLatin1() are their
        // invokevirtual targets (must be rooted or their slots stay trap stubs).
        { "java/lang/String.valueOf(Z)Ljava/lang/String;", "vm/VM.stringValueOfBoolAddr" },
        { "java/lang/String.length()I",                   "vm/VM.stringLengthAddr" },
        { "java/lang/String.charAt(I)C",                  "vm/VM.stringCharAtAddr" },
        { "java/lang/String.coder()B",                    "vm/VM.stringCoderAddr" },
        { "java/lang/String.isLatin1()Z",                 "vm/VM.stringIsLatin1Addr" },
        // M8 widen: toString builds a REAL String on the metal heap (guest DecimalDigits overlay
        // computes digits, stock newStringWithLatin1Bytes + the private String(byte[],byte) ctor
        // wrap them); String.equals compares content across distinct heap Strings.
        { "java/lang/Integer.toString(I)Ljava/lang/String;",    "vm/VM.integerToStringAddr" },
        { "java/lang/Long.toString(J)Ljava/lang/String;",       "vm/VM.longToStringAddr" },
        { "java/lang/Integer.toHexString(I)Ljava/lang/String;", "vm/VM.integerToHexStringAddr" },
        { "java/lang/String.equals(Ljava/lang/Object;)Z",       "vm/VM.stringEqualsAddr" },
        // M8 invokeinterface (writer world): the compareTo pair backs Integer's Comparable itable
        // slot with real code -- the bridge is the itable entry, its checkcast+invokevirtual body
        // needs the typed compareTo compiled too. Dispatched by probe 9 through the itable
        // directory (no VM-static stash needed; the probe calls it as ordinary Java).
        { "java/lang/Integer.compareTo(Ljava/lang/Object;)I",  "vm/VM.integerCompareToBridgeAddr" },
        { "java/lang/Integer.compareTo(Ljava/lang/Integer;)I", "vm/VM.integerCompareToAddr" },
    };

    // M8 static state: statics force-added to the referenced set so they get a slot and a deep
    // snapshot even though no compiled method names them yet, with the SLOT's address stashed in a
    // VM static so the probe can reach the baked value. (Stock Integer.valueOf would reference
    // IntegerCache.cache naturally, but its never-taken `new Integer` branch would pull every
    // Integer virtual -- toString and its String/Unsafe closure -- into the host compile; rooting
    // the static directly keeps the closure tiny until the writer can stub natives.)
    private static final String[][] BAKE_STATICS = {
        { "java/lang/Integer$IntegerCache.cache", "vm/VM.integerCacheSlotAddr" },
        // Long's cache: nothing in the compiled closure ever `new`s a Long, so these baked Longs
        // get their TIB purely through the baked-scalar-class fixpoint -- the real-vtables proof.
        { "java/lang/Long$LongCache.cache",       "vm/VM.longCacheSlotAddr" },
    };

    private final ClassRegistry registry;
    private final Vec<Blob> blobs = new Vec<>();
    private final Vec<RFile> files = new Vec<>();     // M3: embedded RAMFS files
    /** Class-model queries behind a seam; the metal writer swaps in a registry-backed impl (§M5.5c). */
    private final ClassModel model = new SeedClassModel(this);

    /** Raw bytes embedded verbatim; the writer fills {@code addrKey}/{@code lenKey} statics.
     *  {@code className} is the class those bytes are (also folded into the class table so the
     *  metal writer can build from it), or {@code null} for a non-class blob. */
    private record Blob(String addrKey, String lenKey, String className, byte[] bytes) {}

    /** M3: an embedded read-only RAMFS file — laid out like the class table, as a directory of
     *  {nameAddr, nameLen, bytesAddr, bytesLen} entries ({@code vm/VM.fileDir}/{@code fileCount})
     *  followed by the path + content bytes. {@code VM.fileOpen} resolves a path to its entry. */
    private record RFile(String path, byte[] bytes) {}

    public ImageBuilder(ClassRegistry registry)
    {
        this.registry = registry;
    }

    /** Embed {@code bytes} verbatim; the runtime finds them via the given statics (e.g. a raw
     *  .class). {@code className} folds the blob into the class table (metal-writer input); pass
     *  {@code null} for bytes that are not a class. */
    public void addBlob(String addrKey, String lenKey, String className, byte[] bytes)
    {
        blobs.add(new Blob(addrKey, lenKey, className, bytes));
    }

    /** M3: embed a read-only RAMFS file at {@code path} (e.g. {@code "/etc/motd"}). */
    public void addFile(String path, byte[] bytes)
    {
        files.add(new RFile(path, bytes));
    }

    @Override public ClassFile resolve(String owner)
    {
        return registry.resolve(owner);
    }

    private record Resolved(ClassFile cf, ClassFile.Method method) {}
    private record GlobalCall(int siteWord, String calleeKey) {}
    private record GlobalTib(int siteWord, int reg, String className) {}
    private record GlobalStr(int siteWord, int reg, byte[] text) {}
    private record GlobalStatic(int siteWord, int reg, String fieldKey) {}
    private record GlobalType(int siteWord, int reg, String className) {}

    /** Build the whole image with {@code entryKey} (e.g. "vm/VM.boot()V") at 0x80000. */
    public CodeBuffer build(String entryKey)
    {
        // --- discovery + sizing: BFS over calls; collect instantiated classes.
        //     Instantiating a class pulls in all its virtual methods so their code
        //     is laid out and the vtable can point at it (even if not directly called).
        StrIntTable sizeWords = new StrIntTable();                  // layout order, entry first
        StrSet tibClasses = new StrSet();
        ByteKeySet strings = new ByteKeySet();
        StrSet statics = new StrSet();
        StrSet typeRefClasses = new StrSet();          // instanceof/checkcast/interface targets
        StrSet usedInterfaces = new StrSet();          // invokeinterface targets (itable build)
        StrSet refArrDescs = new StrSet();             // "[L<cls>;" ref-array descs needing baked Types
        StrSet usedClasses = new StrSet();
        Vec<String> clinitOrder = new Vec<>();               // <clinit>s to run, first-use order
        int frameCount = 0;                                          // unwind-table entry counts
        int handlerCount = 0;
        Vec<String> worklist = new Vec<>();
        Vec<String> pendingVtable = new Vec<>();   // bake-domain vtable slots: stub unless really called
        StrIntTable lineTabIndex = new StrIntTable();   // stack-trace debug: method key -> position in lineTabList
        Vec<int[]> lineTabList = new Vec<>();           // per method: {wordOffset, line}* transitions (machine-independent)
        worklist.add(entryKey);
        for (int br = 0; br < BAKE_ROOTS.length; br++)
        {
            worklist.add(BAKE_ROOTS[br][0]);
        }
        for (int bs = 0; bs < BAKE_STATICS.length; bs++)
        {
            statics.add(BAKE_STATICS[bs][0]);
            use(ownerOf(BAKE_STATICS[bs][0]), usedClasses, clinitOrder, worklist);
        }
        // M8 real vtables for baked classes: the deep-snapshot object graphs are discovered INSIDE
        // the compile fixpoint (not at layout), so every baked scalar's class can still join
        // tibClasses -- getting a real TIB + Type, with unreached vtable slots baked as stubs. The
        // outer loop runs discovery each time the compile worklists drain, until nothing new appears
        // (a discovered graph can add classes whose stub vtables add code, which can add statics).
        Vec<String> bakedKeys = new Vec<>();
        Vec<Object> bakedRoots = new Vec<>();
        Vec<Object> bakedObjs = new Vec<>();
        StrSet bakedStaticsDone = new StrSet();
        Vec<Object> strObjs = new Vec<>();                // interned host Strings for bake-domain ldc sites
        ByteKeyIntTable strObjIndex = new ByteKeyIntTable();   // literal bytes -> strObjs index (JLS interning)
        int strObjsDone = 0;                              // strObjs prefix already graph-discovered
        while (true)
        {
        while (!worklist.isEmpty() || !pendingVtable.isEmpty())
        {
            // Called work first; once it drains, size parked bake-domain vtable slots as stubs (their
            // stub's BL to VM.bakeTrap feeds the worklist and compiles the trap + its vm closure).
            if (worklist.isEmpty())
            {
                String vk = pendingVtable.removeFirst();
                if (sizeWords.containsKey(vk))
                {
                    continue;      // reached by real calls -> already compiled (or attempted)
                }
                if (stubbedKeys.add(vk))
                {
                    stubIndex.put(vk, stubIndex.size());
                    System.out.println("  bake-stub " + vk + " (vtable slot, not reached)");
                }
                worklist.add(vk);  // compileOrStub sees stubbedKeys first -> stub, no compile attempt
                continue;
            }
            String k = worklist.removeFirst();
            if (k.equals(INIT_CLASSES) || sizeWords.containsKey(k))
            {
                continue;    // init body is generated
            }
            CompiledMethod cm = compileOrStub(k, CodeBuffer.LOAD_ADDRESS, k.equals(entryKey));
            sizeWords.put(k, cm.words().length);
            lineTabIndex.put(k, lineTabList.size());
            lineTabList.add(buildLineTable(cm.bcToWord(), k));
            // M8 world unification COMPLETE for dispatch and type checks: invokevirtual links
            // (vtparity), instanceof/checkcast link for classes and interfaces (Type adoption),
            // and invokeinterface links too -- both worlds now index itables by the SAME flattened
            // per-interface slot (itparity-checked at boot). Field offsets and BL targets are
            // world-independent. The remaining link gate is the primitive-return filter below
            // (writer-TIB objects must not leak until statics/allocation unify).
            var _r1 = cm.relocs().callSites();
            for (int _ri1 = 0; _ri1 < _r1.size(); _ri1++)
            {
                var cs = _r1.get(_ri1);
                worklist.add(cs.calleeKey());
            }
            var _r2 = cm.relocs().strRefs();
            for (int _ri2 = 0; _ri2 < _r2.size(); _ri2++)
            {
                var s = _r2.get(_ri2);
                strings.add(s.text());
            }
            var _r2b = cm.relocs().stringObjs();
            for (int _ri2b = 0; _ri2b < _r2b.size(); _ri2b++)
            {
                var s = _r2b.get(_ri2b);
                if (strObjIndex.get(s.text()) < 0)
                {
                    strObjIndex.put(s.text(), strObjs.size());
                    strObjs.add(new String(s.text(), java.nio.charset.StandardCharsets.US_ASCII));
                }
            }
            var _r3 = cm.relocs().typeRefs();
            for (int _ri3 = 0; _ri3 < _r3.size(); _ri3++)
            {
                var t = _r3.get(_ri3);
                if (t.className().startsWith("["))
                {
                    // Array Type target: laid out canonically, not a classfile. The desc chain
                    // pulls its ELEMENT descs/class in (the covariance walk + the baked
                    // {elementType, tib} table both need the element's Type node).
                    collectArrayDesc(t.className(), refArrDescs, typeRefClasses, usedInterfaces);
                    continue;
                }
                typeRefClasses.add(t.className());
                // M8 itables: an INTERFACE instanceof/checkcast target must also join the itable
                // directories (VM.instanceOf answers interface targets from dir KEYS) -- before
                // this, only invokeinterface targets did, so a pure instanceof-interface read
                // false even against a genuine implementor.
                if (registry.resolve(t.className()).isInterface())
                {
                    usedInterfaces.add(t.className());
                }
            }
            var _r5 = cm.relocs().interfaceRefs();
            for (int _ri5 = 0; _ri5 < _r5.size(); _ri5++)
            {
                var t = _r5.get(_ri5);
                typeRefClasses.add(t.className());
                usedInterfaces.add(t.className());
            }
            var _r6 = cm.handlers();
            for (int _ri6 = 0; _ri6 < _r6.size(); _ri6++)
            {
                var h = _r6.get(_ri6);
                if (h.catchClass() != null)
                {
                    typeRefClasses.add(h.catchClass());
                }
            }
            if (cm.frameSize() > 0)
            {
                frameCount++;
            }
            handlerCount += cm.handlers().size();
            var _r4 = cm.relocs().tibRefs();
            for (int _ri4 = 0; _ri4 < _r4.size(); _ri4++)
            {
                var t = _r4.get(_ri4);
                if (t.className().startsWith("["))
                {
                    // tagArray's array TIB ref: not a class, nothing to use/clinit -- but the desc
                    // chain still pulls its element descs/class in (the TIB's Type carries them).
                    collectArrayDesc(t.className(), refArrDescs, typeRefClasses, null);
                    continue;
                }
                use(t.className(), usedClasses, clinitOrder, worklist);
            }
            var _r7 = cm.relocs().staticRefs();
            for (int _ri7 = 0; _ri7 < _r7.size(); _ri7++)
            {
                var s = _r7.get(_ri7);
                statics.add(s.fieldKey());
                use(ownerOf(s.fieldKey()), usedClasses, clinitOrder, worklist);
            }
            use(ownerOf(k), usedClasses, clinitOrder, worklist);
            var _r8 = cm.relocs().tibRefs();
            for (int _ri8 = 0; _ri8 < _r8.size(); _ri8++)
            {
                var t = _r8.get(_ri8);
                if (t.className().startsWith("["))
                {
                    continue;   // prim-array TIBs are laid out canonically, no vtable pull
                }
                if (tibClasses.add(t.className()))
                {
                    // A bake-domain (stock java.base) class's vtable methods are NOT cascade-compiled:
                    // they park in pendingVtable, and any slot the CALLED closure never reaches bakes
                    // as a trap stub without a compile attempt -- else one `new Integer` drags every
                    // Integer virtual (toString -> String -> Pattern...) plus THEIR closures into the
                    // image. vm/board/... classes keep the eager pull (their vtables must all compile).
                    boolean park = bakeDomain(t.className());
                    Vec<ClassModel.VSlot> vt = model.vtable(t.className());
                    for (int _vi = 0; _vi < vt.size(); _vi++)
                    {
                        ClassModel.VSlot s = vt.get(_vi);
                        String vk = BaselineCompiler.key(s.owner(), s.name(), s.descriptor());
                        if (park)
                        {
                            pendingVtable.add(vk);
                        }
                        else
                        {
                            worklist.add(vk);
                        }
                    }
                }
            }
        }
        // Fixpoint step: snapshot the object graph of every deferred-<clinit> reference static seen
        // so far, and give each newly-discovered baked scalar's class a TIB (parked vtable). Any
        // progress re-enters the compile drain above.
        boolean more = false;
        for (int _s16 = 0; _s16 < statics.size(); _s16++)
        {
            String key = statics.at(_s16);
            if (!clinitDeferred(ownerOf(key)) || !bakedStaticsDone.add(key))
            {
                continue;
            }
            Object v = StaticSnapshot.reference(key);
            if (v == null)
            {
                continue;
            }
            bakedKeys.add(key);
            bakedRoots.add(v);
            bakeDiscover(v, bakedObjs);
            more = true;
        }
        // Interned ldc Strings from bake-domain methods bake exactly like any other scalar graph
        // (String object -> its value byte[]); java/lang/String joins tibClasses via the scalar rule.
        while (strObjsDone < strObjs.size())
        {
            bakeDiscover(strObjs.get(strObjsDone), bakedObjs);
            strObjsDone++;
            more = true;
        }
        for (int _s17 = 0; _s17 < bakedObjs.size(); _s17++)
        {
            Object o = bakedObjs.get(_s17);
            if (o.getClass().isArray())
            {
                continue;
            }
            String cls = bakedClassName(o);
            if (tibClasses.add(cls))
            {
                Vec<ClassModel.VSlot> vt = model.vtable(cls);
                for (int _vi2 = 0; _vi2 < vt.size(); _vi2++)
                {
                    ClassModel.VSlot s = vt.get(_vi2);
                    String vk = BaselineCompiler.key(s.owner(), s.name(), s.descriptor());
                    if (bakeDomain(cls))
                    {
                        pendingVtable.add(vk);
                    }
                    else
                    {
                        worklist.add(vk);
                    }
                }
                more = true;
            }
        }
        if (!more)
        {
            break;
        }
        }
        // Generate VM.initClasses(): call each discovered <clinit> once, in first-use order.
        CompiledMethod initBody = generateInitClasses(clinitOrder);
        sizeWords.put(INIT_CLASSES, initBody.words().length);
        if (initBody.frameSize() > 0)
        {
            frameCount++;
        }

        // --- lay out: [method code] [Types] [TIBs] [interned strings], 8-byte aligned ---
        StrIntTable wordOffset = new StrIntTable();
        int cur = 0;
        for (int i = 0; i < sizeWords.size(); i++)
        {
            wordOffset.put(sizeWords.keyAt(i), cur);
            cur += sizeWords.valAt(i);
        }
        cur += cur % 2;                                             // pad to 8 bytes before data
        // Types are needed by every instantiated class (TIB[0]), every type-check
        // target, and every superclass in those chains (the instanceof walk).
        StrSet typeClasses = new StrSet();
        for (int _s1 = 0; _s1 < tibClasses.size(); _s1++)
        {
            String c = tibClasses.at(_s1);
            addTypeClass(c, typeClasses);
        }
        // M8 ref arrays: a deep-baked reference array (e.g. IntegerCache's Integer[]) is typed with
        // the canonical array TIB for its component -- pull the desc chain + element class in
        // (BEFORE typeRefClasses is consumed below, so elements get Type chains like any target).
        for (int _s2b = 0; _s2b < bakedObjs.size(); _s2b++)
        {
            Class<?> bc = bakedObjs.get(_s2b).getClass();
            if (bc.isArray() && !bc.getComponentType().isPrimitive())
            {
                Class<?> comp = bc.getComponentType();
                String d = comp.isArray() ? "[" + comp.getName().replace('.', '/')
                                          : "[L" + comp.getName().replace('.', '/') + ";";
                collectArrayDesc(d, refArrDescs, typeRefClasses, null);
            }
        }
        for (int _s2 = 0; _s2 < typeRefClasses.size(); _s2++)
        {
            String c = typeRefClasses.at(_s2);
            addTypeClass(c, typeClasses);
        }
        StrIntTable typeWord = new StrIntTable();
        for (int _s3 = 0; _s3 < typeClasses.size(); _s3++)
        {
            String cls = typeClasses.at(_s3);
            typeWord.put(cls, cur);
            cur += TYPE_WORDS;
        }
        StrIntTable tibWord = new StrIntTable();
        for (int _s4 = 0; _s4 < tibClasses.size(); _s4++)
        {
            String cls = tibClasses.at(_s4);
            tibWord.put(cls, cur);
            cur += ObjectModel.tibSize(vtableLength(cls)) / 4;
        }
        // M8 array-Type unification: one canonical Type + TIB per primitive array class (newarray
        // atype 4..11), registered in typeWord/tibWord under the descriptor ("[B"...) so the normal
        // typeRef/tibRef patching and baked-array headers all resolve them uniformly. The TIB carries
        // java/lang/Object's vtable (an array's static type is Object at a dispatch site). The loader
        // ADOPTS these nodes via the VM.primArrayTibs table -- ONE array class across both worlds.
        for (int _a1 = 4; _a1 <= 11; _a1++)
        {
            typeWord.put(PRIM_ARRAY_DESC[_a1 - 4], cur);
            cur += ObjectModel.ARRAY_TYPE_SIZE / 4;
        }
        int objectVtLen = vtableLength("java/lang/Object");
        for (int _a2 = 4; _a2 <= 11; _a2++)
        {
            tibWord.put(PRIM_ARRAY_DESC[_a2 - 4], cur);
            cur += ObjectModel.tibSize(objectVtLen) / 4;
        }
        int primArrTabWord = cur;                     // 8 longs: baked array TIB per atype (4..11)
        cur += 16;
        // Single-dim reference arrays: one Type + TIB per element class in use, plus the
        // {elementType, tib} pair table the loader adopts from (VM.refArrayTibs). Keyed by the
        // shared adopted element Type nodes, so both worlds resolve the same array class.
        for (int _a2b = 0; _a2b < refArrDescs.size(); _a2b++)
        {
            typeWord.put(refArrDescs.at(_a2b), cur);
            cur += ObjectModel.ARRAY_TYPE_SIZE / 4;
        }
        for (int _a2c = 0; _a2c < refArrDescs.size(); _a2c++)
        {
            tibWord.put(refArrDescs.at(_a2c), cur);
            cur += ObjectModel.tibSize(objectVtLen) / 4;
        }
        int refArrTabWord = cur;                      // {elementType, tib} pair per baked ref-array desc
        cur += refArrDescs.size() * 4;
        // O(1) type checks: one DISPLAY per class/array Type -- depth+1 Type addrs, display[d] =
        // the chain's ancestor at depth d (display[depth] = self). Interfaces get none.
        StrIntTable displayWord = new StrIntTable();
        for (int _d1 = 0; _d1 < typeClasses.size(); _d1++)
        {
            String dcls = typeClasses.at(_d1);
            if (registry.resolve(dcls).isInterface())
            {
                continue;
            }
            displayWord.put(dcls, cur);
            cur += (chainDepthOf(dcls) + 1) * 2;
        }
        for (int _d2 = 4; _d2 <= 11; _d2++)
        {
            displayWord.put(PRIM_ARRAY_DESC[_d2 - 4], cur);
            cur += 4;                                  // arrays: [Object, self]
        }
        for (int _d3 = 0; _d3 < refArrDescs.size(); _d3++)
        {
            displayWord.put(refArrDescs.at(_d3), cur);
            cur += 4;
        }
        // O(1) interface checks: number every baked interface Type (IDs 1..127; 0 stays "walk").
        // The loader continues from VM.ifaceIdNext, so IDs are globally unique across both worlds.
        StrIntTable ifaceId = new StrIntTable();
        int nextIfaceId = 1;
        for (int _d4 = 0; _d4 < typeClasses.size(); _d4++)
        {
            String icls = typeClasses.at(_d4);
            if (registry.resolve(icls).isInterface() && nextIfaceId < 128)
            {
                ifaceId.put(icls, nextIfaceId);
                nextIfaceId += 1;
            }
        }
        ByteKeyIntTable strWord = new ByteKeyIntTable();
        for (int _s5 = 0; _s5 < strings.size(); _s5++)
        {
            byte[] s = strings.at(_s5);
            strWord.put(s, cur);
            cur += stringWords(s);
        }
        // M8 static state, object statics: the deep-snapshotted graphs (discovered in the compile
        // fixpoint above -- primitive arrays, reference arrays, scalar objects) get their image
        // words here. Every scalar's class has a real TIB (it joined tibClasses during discovery);
        // scalar field layout comes from the SAME registered classfile the compiler resolves
        // getfield against, so offsets agree by construction.
        int[] bakedObjWord = new int[bakedObjs.size()];
        for (int _s15 = 0; _s15 < bakedObjs.size(); _s15++)
        {
            bakedObjWord[_s15] = cur;
            cur += bakedWords(bakedObjs.get(_s15));
        }
        StrIntTable staticWord = new StrIntTable();          // one 8-byte slot per static field, zero-init
        int staticsRegionStart = cur;
        // M8 statics unification: baked (vtSig) classes get DENSE per-class static blocks -- one
        // slot per DECLARED static field, in declaration order = the loader's slot numbering -- so
        // the loader ADOPTS the block as clTab[].statics: ONE home per field across both worlds
        // (the loader's clinit run then initializes the shared slots; the snapshot pre-fills the
        // deferred ones). Every declared field keys into staticWord, so getstatic patches, writer
        // fills, and the snapshot all resolve into the block. Blocks live inside the statics
        // region, so the GC's staticsStart/End root scan covers loader-written heap pointers.
        Vec<String> vtSigClasses = new Vec<>();
        for (int i = 0; i < typeClasses.size(); i++)
        {
            String c = typeClasses.at(i);
            if (bakeDomain(c))
            {
                vtSigClasses.add(c);
            }
        }
        int[] bakedStaticsWord = new int[vtSigClasses.size()];
        int[] bakedStaticCount = new int[vtSigClasses.size()];
        for (int i = 0; i < vtSigClasses.size(); i++)
        {
            bakedStaticsWord[i] = cur;
            int n = 0;
            for (ClassFile.FieldInfo fld : registry.resolve(vtSigClasses.get(i)).fields())
            {
                if (fld.isStatic())
                {
                    staticWord.put(vtSigClasses.get(i) + "." + fld.name(), cur);
                    cur += WORDS_PER_SLOT;
                    n++;
                }
            }
            bakedStaticCount[i] = n;
        }
        for (int _s6 = 0; _s6 < statics.size(); _s6++)
        {
            String s = statics.at(_s6);
            if (!staticWord.containsKey(s))              // baked classes' fields already have block slots
            {
                staticWord.put(s, cur);
                cur += WORDS_PER_SLOT;
            }
        }
        int staticsRegionEnd = cur;
        // itables: per instantiated class, a directory of {interfaceType, itable} plus the itables.
        StrIntTable itableDirWord = new StrIntTable();       // class -> directory
        StrIntTable itableWord = new StrIntTable();          // "class|iface" -> itable
        for (int _s7 = 0; _s7 < tibClasses.size(); _s7++)
        {
            String c = tibClasses.at(_s7);
            Vec<String> impls = implementedUsedInterfaces(c, usedInterfaces);
            if (impls.isEmpty())
            {
                continue;
            }
            itableDirWord.put(c, cur);
            // +1 entry: a zeroed {interfaceType=0, itable=0} sentinel terminates the
            // directory so a bounded scan (VM.instanceOf) knows where it ends.
            cur += (impls.size() + 1) * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
            for (int _v1 = 0; _v1 < impls.size(); _v1++)
            {
                String i = impls.get(_v1);
                itableWord.put(c + "|" + i, cur);
                cur += model.interfaceMethods(i).size() * WORDS_PER_SLOT;
            }
        }
        // unwind tables: frame entries {start,end,frameSize} (6 words), handler
        // entries {start,end,handler,catchType} (8 words).
        int frameTableWord = cur;
        cur += frameCount * 6;
        int handlerTableWord = cur;
        cur += handlerCount * 8;
        // embedded blobs (e.g. a raw .class for the runtime loader), 8-byte aligned.
        int[] blobWord = new int[blobs.size()];
        for (int b = 0; b < blobs.size(); b++)
        {
            blobWord[b] = cur;
            cur += ((blobs.get(b).bytes().length + 7) & ~7) / 4;
        }
        // class table: the compile-reachable classes, name-indexed so the metal writer
        // can resolve a class by name from the image alone (PLAN.md §M5.5c step 2). Laid
        // out as a directory of {nameAddr, nameLen, bytesAddr, bytesLen} entries followed
        // by each class's name bytes and raw .class bytes.
        // The class table is the compile-reachable set plus the runtime-load blobs' classes,
        // folded in so the metal writer can build closures spanning them (e.g. Guest.answer),
        // not just JIT them at runtime (PLAN.md §M5.5c step 3b.4).
        // Demand-loadable classes only (stock java.base + guest overrides + demos), SORTED by name so the
        // metal findClass binary-searches the directory. VM-internal classes (magic/*, vm/*, compiler/*, ...)
        // are EXCLUDED: magic/Magic's methods are intrinsics (not real bytecode) and must resolve via the
        // intrinsic path, never be demand-loaded. Runtime pulls stay bounded by the reachability-gated closure.
        Vec<String> classNames = sortByName(demandLoadable(registry.allNames()));
        int classCount = classNames.size();
        int classDirWord = cur;
        cur += classCount * CLASS_ENTRY_WORDS;
        int[] classNameWord = new int[classCount];
        int[] classBytesWord = new int[classCount];
        for (int i = 0; i < classCount; i++)
        {
            classNameWord[i] = cur;
            cur += align8Words(classNames.get(i).length());
            classBytesWord[i] = cur;
            cur += align8Words(registry.rawBytes(classNames.get(i)).length);
        }
        // M3: RAMFS file table — same {nameAddr, nameLen, bytesAddr, bytesLen} shape as the class table,
        // then each file's path bytes and content bytes. VM.fileOpen walks the directory by path.
        int fileCount = files.size();
        int fileDirWord = cur;
        cur += fileCount * CLASS_ENTRY_WORDS;
        int[] fileNameWord = new int[fileCount];
        int[] fileBytesWord = new int[fileCount];
        for (int i = 0; i < fileCount; i++)
        {
            fileNameWord[i] = cur;
            cur += align8Words(files.get(i).path().length());
            fileBytesWord[i] = cur;
            cur += align8Words(files.get(i).bytes().length);
        }
        // --- image symbol table (stack traces): per image method, in sizeWords order, an entry
        //     {codeStart, codeEnd, nameAddr, srcAddr, lineAddr} (5 longs) + its Utf8 name/source strings +
        //     line table {count, (wordOffset, line)*}. Loader.printFrameAt scans it to resolve a VM/driver PC. ---
        int symCount = sizeWords.size();
        byte[][] symName = new byte[symCount][];
        byte[][] symSrc = new byte[symCount][];
        int[][] symLine = new int[symCount][];
        for (int i = 0; i < symCount; i++)
        {
            String k = sizeWords.keyAt(i);
            if (k.equals(INIT_CLASSES))
            {
                symName[i] = utf8("vm/VM.initClasses");
                symSrc[i] = utf8("");
                symLine[i] = new int[0];
            }
            else if (stubbedKeys.contains(k))
            {
                // A bake stub has no bytecode -- and its method may not even exist in the registered
                // classfile (e.g. a stock ref into a guest overlay), so don't lookup() it.
                symName[i] = utf8(k.substring(0, k.indexOf('(')));
                symSrc[i] = utf8("");
                symLine[i] = lineTabList.get(lineTabIndex.get(k));
            }
            else
            {
                symName[i] = utf8(k.substring(0, k.indexOf('(')));   // "owner/Class.method"
                String src = lookup(k).cf.sourceFile();
                symSrc[i] = utf8(src == null ? "" : src);
                symLine[i] = lineTabList.get(lineTabIndex.get(k));
            }
        }
        int symTableWord = cur;
        cur += symCount * 10;                                       // 5 longs per entry
        int[] symNameWord = new int[symCount];
        int[] symSrcWord = new int[symCount];
        int[] symLineWord = new int[symCount];
        for (int i = 0; i < symCount; i++)
        {
            symNameWord[i] = cur;
            cur += align8Words(symName[i].length);
            symSrcWord[i] = cur;
            cur += align8Words(symSrc[i].length);
            symLineWord[i] = cur;
            cur += 1 + symLine[i].length;                          // {count}{(off,line) ints}
        }
        // --- M8 endgame: baked-method LINK table -- the writer-compiled stock methods the on-metal
        //     Loader may run instead of lazy-compiling its own copy. {classUtf8Addr, nameUtf8Addr,
        //     descUtf8Addr, codeAddr} per entry (4 longs); the name runs are {u2 len}{bytes}, the same
        //     shape as classfile Utf8s. Restricted to methods safe to run on LOADER-world receivers:
        //     primitive/void return (no writer-TIB objects leak out), and none of the world-crossing
        //     constructs (noLinkKeys). Statics they read are the writer's snapshotted slots --
        //     effectively-final config values (e.g. COMPACT_STRINGS), consistent across worlds. ---
        Vec<String> bakedLink = new Vec<>();
        for (int i = 0; i < sizeWords.size(); i++)
        {
            String k = sizeWords.keyAt(i);
            if (k.equals(INIT_CLASSES) || !bakeDomain(ownerOf(k)))
            {
                continue;
            }
            if (stubbedKeys.contains(k))
            {
                continue;
            }
            String name = k.substring(ownerOf(k).length() + 1, k.indexOf('('));
            if (name.equals("<init>") || name.equals("<clinit>"))
            {
                continue;      // never lazy-compiled by name; init semantics stay per-world
            }
            // M8 object links: OBJECT-returning methods link too. A writer-TIB object in loader
            // hands is safe now -- layout/vtables/Types/statics are unified, and any dispatch that
            // lands in a bake stub resolves lazily through VM.bakeResolve instead of trapping.
            bakedLink.add(k);
        }
        System.out.println("  baked-link " + bakedLink.size() + " methods");
        int bakedTableWord = cur;
        cur += bakedLink.size() * 8;                                // 4 longs per entry
        int[] bakedClsWord = new int[bakedLink.size()];
        int[] bakedNameWord = new int[bakedLink.size()];
        int[] bakedDescWord = new int[bakedLink.size()];
        for (int i = 0; i < bakedLink.size(); i++)
        {
            String k = bakedLink.get(i);
            String owner = ownerOf(k);
            bakedClsWord[i] = cur;
            cur += align8Words(2 + owner.length());
            bakedNameWord[i] = cur;
            cur += align8Words(2 + (k.indexOf('(') - owner.length() - 1));
            bakedDescWord[i] = cur;
            cur += align8Words(2 + (k.length() - k.indexOf('(')));
        }
        // --- M8 world unification: vtable-SIGNATURE + TYPE-ADOPTION table for bake-domain classes.
        //     Per class {classUtf8Addr, slotsAddr, count, typeAddr} (4 longs), slots as {nameUtf8Addr,
        //     descUtf8Addr} pairs. The loader compares its own flattening against this at structure
        //     time (vtparity) AND adopts the writer's Type node as the class's runtime Type -- ONE
        //     Type per baked class across both worlds, so instanceof/checkcast agree everywhere.
        //     Covers every bake-domain class with a writer Type (typeClasses: instantiated classes,
        //     type-check targets, and their full super chains); interfaces are skipped (the loader
        //     never runs the class phase-A path for them, and itables stay per-world for now). ---
        // M8 itables: INTERFACE entries carry their FLATTENED per-interface method signatures --
        // the itable slot numbering -- so the loader can itparity-check its own flattened capture
        // exactly like classes vtparity-check their vtable flattening. (The loader tells the two
        // kinds apart by its own ACC_INTERFACE; both adopt the writer Type from the 4th slot.)
        // (vtSigClasses hoisted above the statics layout -- the dense blocks need it.)
        int vtSigDirWord = cur;
        cur += vtSigClasses.size() * 12;                            // {classUtf8, slotsAddr, count, typeAddr, staticsAddr, staticCount}
        int[] vtSigClsWord = new int[vtSigClasses.size()];
        int[] vtSigSlotsWord = new int[vtSigClasses.size()];
        int[][] vtSigNameWord = new int[vtSigClasses.size()][];
        int[][] vtSigDescWord = new int[vtSigClasses.size()][];
        for (int i = 0; i < vtSigClasses.size(); i++)
        {
            Vec<String[]> vt = sigPairsFor(vtSigClasses.get(i));
            vtSigClsWord[i] = cur;
            cur += align8Words(2 + vtSigClasses.get(i).length());
            vtSigSlotsWord[i] = cur;
            cur += vt.size() * 4;                                   // {nameAddr, descAddr} per slot
            vtSigNameWord[i] = new int[vt.size()];
            vtSigDescWord[i] = new int[vt.size()];
            for (int s = 0; s < vt.size(); s++)
            {
                vtSigNameWord[i][s] = cur;
                cur += align8Words(2 + vt.get(s)[0].length());
                vtSigDescWord[i][s] = cur;
                cur += align8Words(2 + vt.get(s)[1].length());
            }
        }
        // --- M8 object links: the bake-STUB table -- {classUtf8, nameUtf8, descUtf8, memo} per
        //     stub, in stubIndex order (the movz immediate each stub carries). VM.bakeResolve reads
        //     entry[idx], demand-loads the class via the loader, and memoizes the callable buffer
        //     in the 4th slot (runtime-written; the image is RAM). ---
        int stubTabWord = cur;
        cur += stubIndex.size() * 8;                                // 4 longs per stub
        int[] stubClsWord = new int[stubIndex.size()];
        int[] stubNameWord = new int[stubIndex.size()];
        int[] stubDescWord = new int[stubIndex.size()];
        for (int i = 0; i < stubIndex.size(); i++)
        {
            String k = stubIndex.keyAt(i);
            stubClsWord[i] = cur;
            cur += align8Words(2 + ownerOf(k).length());
            stubNameWord[i] = cur;
            cur += align8Words(2 + (k.indexOf('(') - ownerOf(k).length() - 1));
            stubDescWord[i] = cur;
            cur += align8Words(2 + (k.length() - k.indexOf('(')));
        }
        int totalWords = cur;

        // --- final compile at real bases; concatenate; gather fixups ---
        int[] image = new int[totalWords];
        Vec<GlobalCall> calls = new Vec<>();
        Vec<GlobalTib> tibs = new Vec<>();
        Vec<GlobalStr> strs = new Vec<>();
        Vec<GlobalStr> strObjSites = new Vec<>();   // bake-domain ldc sites -> baked String objects
        Vec<GlobalStatic> stats = new Vec<>();
        Vec<GlobalType> types = new Vec<>();
        Vec<long[]> frameEntries = new Vec<>();       // {codeStart, codeEnd, frameSize}
        Vec<long[]> handlerEntries = new Vec<>();     // {machStart, machEnd, handler, catchType}
        for (int si = 0; si < sizeWords.size(); si++)
        {
            String k = sizeWords.keyAt(si);
            int base = wordOffset.get(k);
            CompiledMethod cm = k.equals(INIT_CLASSES) ? initBody
                                : compileOrStub(k, CodeBuffer.LOAD_ADDRESS + (long) base * 4, k.equals(entryKey));
            if (cm.words().length != sizeWords.get(k))
            {
                throw new IllegalStateException("size drift for " + k);
            }
            System.arraycopy(cm.words(), 0, image, base, cm.words().length);
            var _r5 = cm.relocs().callSites();
            for (int _ri5 = 0; _ri5 < _r5.size(); _ri5++)
            {
                var cs = _r5.get(_ri5);
                calls.add(new GlobalCall(base + cs.wordIndex(), cs.calleeKey()));
            }
            var _r6 = cm.relocs().tibRefs();
            for (int _ri6 = 0; _ri6 < _r6.size(); _ri6++)
            {
                var t = _r6.get(_ri6);
                tibs.add(new GlobalTib(base + t.wordIndex(), t.reg(), t.className()));
            }
            var _r7 = cm.relocs().strRefs();
            for (int _ri7 = 0; _ri7 < _r7.size(); _ri7++)
            {
                var s = _r7.get(_ri7);
                strs.add(new GlobalStr(base + s.wordIndex(), s.reg(), s.text()));
            }
            var _r7b = cm.relocs().stringObjs();
            for (int _ri7b = 0; _ri7b < _r7b.size(); _ri7b++)
            {
                var s = _r7b.get(_ri7b);
                strObjSites.add(new GlobalStr(base + s.wordIndex(), s.reg(), s.text()));
            }
            var _r8 = cm.relocs().staticRefs();
            for (int _ri8 = 0; _ri8 < _r8.size(); _ri8++)
            {
                var s = _r8.get(_ri8);
                stats.add(new GlobalStatic(base + s.wordIndex(), s.reg(), s.fieldKey()));
            }
            var _r9 = cm.relocs().typeRefs();
            for (int _ri9 = 0; _ri9 < _r9.size(); _ri9++)
            {
                var t = _r9.get(_ri9);
                types.add(new GlobalType(base + t.wordIndex(), t.reg(), t.className()));
            }
            var _r10 = cm.relocs().interfaceRefs();
            for (int _ri10 = 0; _ri10 < _r10.size(); _ri10++)
            {
                var t = _r10.get(_ri10);
                types.add(new GlobalType(base + t.wordIndex(), t.reg(), t.className()));
            }
            if (cm.frameSize() > 0)
            {
                frameEntries.add(new long[] {addr(base), addr(base + cm.words().length), cm.frameSize()});
            }
            var _rh = cm.handlers();
            for (int _rhi = 0; _rhi < _rh.size(); _rhi++)
            {
                var hr = _rh.get(_rhi);
                long ct = hr.catchClass() == null ? 0 : addr(typeWord.get(hr.catchClass()));
                handlerEntries.add(new long[] {addr(base + hr.startWord()), addr(base + hr.endWord()),
                                               addr(base + hr.handlerWord()), ct
                                              });
            }
        }

        // --- Types: { instanceSize, superType, itableDir } ---
        for (int _s8 = 0; _s8 < typeClasses.size(); _s8++)
        {
            String cls = typeClasses.at(_s8);
            int tw = typeWord.get(cls);
            writeLong(image, tw + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET / 4,
                      ObjectModel.scalarSize(ClassFile.chainFieldBase(cls, this)
                                             + model.instanceFieldCount(cls)));
            String sup = model.superClassName(cls);
            long superAddr = sup == null ? 0 : addr(typeWord.get(sup));   // full chain (0 only at Object)
            writeLong(image, tw + ObjectModel.TYPE_SUPER_OFFSET / 4, superAddr);
            long dir = itableDirWord.containsKey(cls) ? addr(itableDirWord.get(cls)) : 0;
            writeLong(image, tw + ObjectModel.TYPE_ITABLE_DIR_OFFSET / 4, dir);
            // O(1) type checks: depth + superclass display (interfaces: depth -1, no display --
            // they are answered from itable dirs, never the chain).
            if (registry.resolve(cls).isInterface())
            {
                writeLong(image, tw + ObjectModel.TYPE_DEPTH_OFFSET / 4, -1L);
                writeLong(image, tw + ObjectModel.TYPE_IMPLEMENTS_OFFSET / 4,
                          ifaceId.containsKey(cls) ? ifaceId.get(cls) : 0);   // interface: its global ID
            }
            else
            {
                int depth = chainDepthOf(cls);
                int dw = displayWord.get(cls);
                writeLong(image, tw + ObjectModel.TYPE_DEPTH_OFFSET / 4, depth);
                writeLong(image, tw + ObjectModel.TYPE_DISPLAY_OFFSET / 4, addr(dw));
                String c2 = cls;
                for (int dd = depth; dd >= 0; dd--)
                {
                    writeLong(image, dw + dd * 2, addr(typeWord.get(c2)));
                    c2 = model.superClassName(c2);
                }
                // doesImplement bitmap over the full interface closure (marker bit 0 + ID bits).
                long b0 = 1L;
                long b1 = 0L;
                StrSet all = model.allInterfaces(cls);
                for (int ii = 0; ii < all.size(); ii++)
                {
                    String ic = all.at(ii);
                    if (ifaceId.containsKey(ic))
                    {
                        int iid = ifaceId.get(ic);
                        if (iid < 64)
                        {
                            b0 |= 1L << iid;
                        }
                        else
                        {
                            b1 |= 1L << (iid - 64);
                        }
                    }
                }
                writeLong(image, tw + ObjectModel.TYPE_IMPLEMENTS_OFFSET / 4, b0);
                writeLong(image, tw + ObjectModel.TYPE_IMPLEMENTS_OFFSET / 4 + 2, b1);
                // GC reference map over the chain-aware slot numbering. The loader ADOPTS this node for
                // any class it also loads, so both worlds trace the same object through the same map.
                long[] rm = ClassFile.refMap(cls, this);
                writeLong(image, tw + ObjectModel.TYPE_REFMAP_OFFSET / 4, rm[0]);
                writeLong(image, tw + ObjectModel.TYPE_REFMAP_OFFSET / 4 + 2, rm[1]);
            }
        }

        // --- itable directories and itables (interface method dispatch) ---
        for (int _s9 = 0; _s9 < tibClasses.size(); _s9++)
        {
            String c = tibClasses.at(_s9);
            Vec<String> impls = implementedUsedInterfaces(c, usedInterfaces);
            if (impls.isEmpty())
            {
                continue;
            }
            int dw = itableDirWord.get(c);
            for (int e = 0; e < impls.size(); e++)
            {
                String i = impls.get(e);
                int entry = dw + e * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
                writeLong(image, entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET / 4, addr(typeWord.get(i)));
                writeLong(image, entry + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET / 4, addr(itableWord.get(c + "|" + i)));
            }
            for (int _v2 = 0; _v2 < impls.size(); _v2++)
            {
                String i = impls.get(_v2);
                int iw = itableWord.get(c + "|" + i);
                var ims = model.interfaceMethods(i);
                for (int s = 0; s < ims.size(); s++)
                {
                    ClassModel.Method m = ims.get(s);
                    String impl = model.findImpl(c, m.name(), m.descriptor());
                    int mbase = wordOffset.get(BaselineCompiler.key(impl, m.name(), m.descriptor()));
                    writeLong(image, iw + s * WORDS_PER_SLOT, addr(mbase));
                }
            }
        }

        // --- TIBs: [Type ptr][vtable code addresses] ---
        for (int _s10 = 0; _s10 < tibClasses.size(); _s10++)
        {
            String cls = tibClasses.at(_s10);
            int tw = tibWord.get(cls);
            writeLong(image, tw + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT) / 4, addr(typeWord.get(cls)));
            var slots = model.vtable(cls);
            for (int slot = 0; slot < slots.size(); slot++)
            {
                ClassModel.VSlot s = slots.get(slot);
                int mbase = wordOffset.get(BaselineCompiler.key(s.owner(), s.name(), s.descriptor()));
                writeLong(image, tw + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)) / 4, addr(mbase));
            }
        }

        // --- primitive-array Types + TIBs (canonical; the loader adopts them via VM.primArrayTibs) ---
        var objSlots = model.vtable("java/lang/Object");
        for (int _a3 = 4; _a3 <= 11; _a3++)
        {
            String d = PRIM_ARRAY_DESC[_a3 - 4];
            int tw = typeWord.get(d);
            writeLong(image, tw + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET / 4,
                      ObjectModel.ARRAY_TYPE_TAG | primArrayElemSize(_a3));
            writeLong(image, tw + ObjectModel.TYPE_SUPER_OFFSET / 4, addr(typeWord.get("java/lang/Object")));
            writeLong(image, tw + ObjectModel.TYPE_ITABLE_DIR_OFFSET / 4, 0);
            writeLong(image, tw + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET / 4, 0);
            writeArrayDisplay(image, tw, displayWord.get(d), typeWord.get("java/lang/Object"));
            int bw = tibWord.get(d);
            writeLong(image, bw + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT) / 4, addr(tw));
            for (int slot = 0; slot < objSlots.size(); slot++)
            {
                ClassModel.VSlot s = objSlots.get(slot);
                int mbase = wordOffset.get(BaselineCompiler.key(s.owner(), s.name(), s.descriptor()));
                writeLong(image, bw + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)) / 4, addr(mbase));
            }
            writeLong(image, primArrTabWord + (_a3 - 4) * 2, addr(bw));
        }
        for (int _a4 = 0; _a4 < refArrDescs.size(); _a4++)
        {
            String d = refArrDescs.at(_a4);
            String el = arrayElementKeyOf(d);       // element class, or the nested element array desc
            int tw = typeWord.get(d);
            writeLong(image, tw + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET / 4,
                      ObjectModel.ARRAY_TYPE_TAG | ObjectModel.WORD);
            writeLong(image, tw + ObjectModel.TYPE_SUPER_OFFSET / 4, addr(typeWord.get("java/lang/Object")));
            writeLong(image, tw + ObjectModel.TYPE_ITABLE_DIR_OFFSET / 4, 0);
            writeLong(image, tw + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET / 4, addr(typeWord.get(el)));
            writeArrayDisplay(image, tw, displayWord.get(d), typeWord.get("java/lang/Object"));
            int bw = tibWord.get(d);
            writeLong(image, bw + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT) / 4, addr(tw));
            for (int slot = 0; slot < objSlots.size(); slot++)
            {
                ClassModel.VSlot s = objSlots.get(slot);
                int mbase = wordOffset.get(BaselineCompiler.key(s.owner(), s.name(), s.descriptor()));
                writeLong(image, bw + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)) / 4, addr(mbase));
            }
            writeLong(image, refArrTabWord + _a4 * 4, addr(typeWord.get(el)));
            writeLong(image, refArrTabWord + _a4 * 4 + 2, addr(bw));
        }

        // --- unwind tables + their location statics ---
        for (int i = 0; i < frameEntries.size(); i++)
        {
            long[] e = frameEntries.get(i);
            int w = frameTableWord + i * 6;
            writeLong(image, w, e[0]);
            writeLong(image, w + 2, e[1]);
            writeLong(image, w + 4, e[2]);
        }
        for (int i = 0; i < handlerEntries.size(); i++)
        {
            long[] e = handlerEntries.get(i);
            int w = handlerTableWord + i * 8;
            writeLong(image, w, e[0]);
            writeLong(image, w + 2, e[1]);
            writeLong(image, w + 4, e[2]);
            writeLong(image, w + 6, e[3]);
        }
        // --- image symbol table fill (stack traces) ---
        for (int i = 0; i < symCount; i++)
        {
            String k = sizeWords.keyAt(i);
            int base = wordOffset.get(k);
            writeBytes(image, symNameWord[i], symName[i]);
            writeBytes(image, symSrcWord[i], symSrc[i]);
            image[symLineWord[i]] = symLine[i].length / 2;         // count = number of (offset, line) pairs
            for (int j = 0; j < symLine[i].length; j++)
            {
                image[symLineWord[i] + 1 + j] = symLine[i][j];
            }
            int w = symTableWord + i * 10;
            writeLong(image, w, addr(base));
            writeLong(image, w + 2, addr(base + sizeWords.get(k)));
            writeLong(image, w + 4, addr(symNameWord[i]));
            writeLong(image, w + 6, addr(symSrcWord[i]));
            writeLong(image, w + 8, addr(symLineWord[i]));
        }
        // Debug aid (working agreements: "dump and diff image layouts"): with JOENG_SYMMAP set, print every
        // image method's [start,end) so a bare PC from a QEMU `info registers` -- the only evidence a spin in
        // image code leaves -- can be named without guessing.
        if (System.getenv("JOENG_SYMMAP") != null)
        {
            for (int i = 0; i < symCount; i++)
            {
                String k = sizeWords.keyAt(i);
                int base = wordOffset.get(k);
                System.out.println(String.format("  symmap %08x %08x %s",
                        addr(base), addr(base + sizeWords.get(k)), k));
            }
        }
        fillStatic(image, staticWord, "vm/VM.imageSymTable", addr(symTableWord));
        fillStatic(image, staticWord, "vm/VM.imageSymCount", symCount);
        fillStatic(image, staticWord, "vm/VM.frameTable",   addr(frameTableWord));
        fillStatic(image, staticWord, "vm/VM.frameCount",   frameEntries.size());
        fillStatic(image, staticWord, "vm/VM.handlerTable", addr(handlerTableWord));
        fillStatic(image, staticWord, "vm/VM.handlerCount", handlerEntries.size());
        fillStatic(image, staticWord, "vm/VM.staticsStart", addr(staticsRegionStart));
        fillStatic(image, staticWord, "vm/VM.staticsEnd",   addr(staticsRegionEnd));
        for (int i = 0; i < bakedLink.size(); i++)
        {
            String k = bakedLink.get(i);
            String owner = ownerOf(k);
            String mname = k.substring(owner.length() + 1, k.indexOf('('));
            String mdesc = k.substring(k.indexOf('('));
            writeBytes(image, bakedClsWord[i], utf8(owner));
            writeBytes(image, bakedNameWord[i], utf8(mname));
            writeBytes(image, bakedDescWord[i], utf8(mdesc));
            int w = bakedTableWord + i * 8;
            writeLong(image, w,     addr(bakedClsWord[i]));
            writeLong(image, w + 2, addr(bakedNameWord[i]));
            writeLong(image, w + 4, addr(bakedDescWord[i]));
            writeLong(image, w + 6, addr(wordOffset.get(k)));
        }
        fillStatic(image, staticWord, "vm/VM.bakedTable", addr(bakedTableWord));
        fillStatic(image, staticWord, "vm/VM.bakedCount", bakedLink.size());
        for (int i = 0; i < vtSigClasses.size(); i++)
        {
            Vec<String[]> vt = sigPairsFor(vtSigClasses.get(i));
            writeBytes(image, vtSigClsWord[i], utf8(vtSigClasses.get(i)));
            for (int s = 0; s < vt.size(); s++)
            {
                writeBytes(image, vtSigNameWord[i][s], utf8(vt.get(s)[0]));
                writeBytes(image, vtSigDescWord[i][s], utf8(vt.get(s)[1]));
                writeLong(image, vtSigSlotsWord[i] + s * 4,     addr(vtSigNameWord[i][s]));
                writeLong(image, vtSigSlotsWord[i] + s * 4 + 2, addr(vtSigDescWord[i][s]));
            }
            int w = vtSigDirWord + i * 12;
            writeLong(image, w,      addr(vtSigClsWord[i]));
            writeLong(image, w + 2,  addr(vtSigSlotsWord[i]));
            writeLong(image, w + 4,  vt.size());
            writeLong(image, w + 6,  addr(typeWord.get(vtSigClasses.get(i))));  // the ONE Type node
            writeLong(image, w + 8,  addr(bakedStaticsWord[i]));                // the ONE statics block
            writeLong(image, w + 10, bakedStaticCount[i]);
        }
        fillStatic(image, staticWord, "vm/VM.vtSigTable", addr(vtSigDirWord));
        fillStatic(image, staticWord, "vm/VM.vtSigCount", vtSigClasses.size());
        for (int i = 0; i < stubIndex.size(); i++)
        {
            String k = stubIndex.keyAt(i);
            String owner = ownerOf(k);
            writeBytes(image, stubClsWord[i], utf8(owner));
            writeBytes(image, stubNameWord[i], utf8(k.substring(owner.length() + 1, k.indexOf('('))));
            writeBytes(image, stubDescWord[i], utf8(k.substring(k.indexOf('('))));
            int w = stubTabWord + i * 8;
            writeLong(image, w,     addr(stubClsWord[i]));
            writeLong(image, w + 2, addr(stubNameWord[i]));
            writeLong(image, w + 4, addr(stubDescWord[i]));
            writeLong(image, w + 6, 0);                             // memo: resolved buffer (runtime)
        }
        fillStatic(image, staticWord, "vm/VM.bakeStubTable", addr(stubTabWord));
        fillStatic(image, staticWord, "vm/VM.bakeStubCount", stubIndex.size());
        fillStatic(image, staticWord, "vm/VM.primArrayTibs", addr(primArrTabWord));
        fillStatic(image, staticWord, "vm/VM.refArrayTibs", addr(refArrTabWord));
        fillStatic(image, staticWord, "vm/VM.refArrayTibCount", refArrDescs.size());
        fillStatic(image, staticWord, "vm/VM.ifaceIdNext", nextIfaceId);
        // Stash each runtime-helper method address so the on-metal JIT (MetalSymbols)
        // can BL it. Keys mirror compiler/WriterSymbols.HELPER_KEY.
        stashHelper(image, staticWord, wordOffset, "vm/Heap.alloc(I)J",       "vm/VM.heapAlloc");
        stashHelper(image, staticWord, wordOffset, "vm/Heap.allocArray(II)J", "vm/VM.allocArray");
        stashHelper(image, staticWord, wordOffset, "vm/VMGc.gcCollect(J)V",   "vm/VM.gcCollect");
        stashHelper(image, staticWord, wordOffset, "vm/VM.instanceOf(JJ)I",   "vm/VM.instanceOfAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.checkCast(JJ)J",    "vm/VM.checkCastAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMUnwind.unwind(JJJ)V", "vm/VM.unwindAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMUnwind.captureTrace(JJJ)V", "vm/VM.captureTraceAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.reportFault()V",    "vm/VM.reportFaultAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.throwFromFault(J)V", "vm/VM.throwFromFaultAddr");
        stashHelper(image, staticWord, wordOffset, "vm/Loader.lazyCompile(I)J", "vm/VM.lazyCompileAddr");
        stashHelper(image, staticWord, wordOffset, "vm/Loader.resolveLinkStub(I)J", "vm/VM.resolveLinkStubAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.irqHandler()V",     "vm/VM.irqHandlerAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.schedule(J)J",      "vm/VM.scheduleAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.yieldPick(J)J",     "vm/VM.yieldPickAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskA()V",          "vm/VM.taskAAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskB()V",          "vm/VM.taskBAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskC()V",          "vm/VM.taskCAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskR()V",          "vm/VM.taskRAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.smpTask()V",        "vm/VM.smpTaskAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.prioTask(I)V",      "vm/VM.prioTaskAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.setPriority(JI)V",  "vm/VM.setPrioAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.getPriority(J)I",   "vm/VM.getPrioAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.secondaryMain(I)V", "vm/VM.secondaryMainAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.pcSchedule(J)J",    "vm/VM.pcScheduleAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.pcTask1(I)V",       "vm/VM.pcTask1Addr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.startThread(J)V",   "vm/VM.startThreadAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.objWait(JJ)V",      "vm/VM.objWaitAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.objNotify(J)V",     "vm/VM.objNotifyAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.objNotifyAll(J)V",  "vm/VM.objNotifyAllAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.monEnter(J)V",      "vm/VM.monEnterAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.monExit(J)V",       "vm/VM.monExitAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.holdsLock(J)I",     "vm/VM.holdsLockAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.interrupt(J)V",     "vm/VM.interruptAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.isInterrupted(J)I", "vm/VM.isInterruptedAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.checkClearInterrupt()I", "vm/VM.checkIntrAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.isAlive(J)I",       "vm/VM.isAliveAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.joinTimed(JJ)I",    "vm/VM.joinTimedAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.park()V",           "vm/VM.parkAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.unpark(J)V",        "vm/VM.unparkAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.threadJoin(J)V",    "vm/VM.threadJoinAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.threadStackTrace(JJJ)J", "vm/VM.threadStackTraceAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.allThreads()J",         "vm/VM.allThreadsAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.semWait(I)V",       "vm/VM.semWaitAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.semPost(I)V",       "vm/VM.semPostAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.sleep(J)V",         "vm/VM.sleepAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.newSem(I)I",        "vm/VM.newSemAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.philReport(II)V",   "vm/VM.philReportAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskExit()V",       "vm/VM.taskExitAddr");
        stashHelper(image, staticWord, wordOffset, "vm/Loader.resolveRun(J)J",          "vm/VM.runResolveAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMBox.box(JI)J",            "vm/VM.boxPrimAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMConcat.scStart()J",        "vm/VM.scStartAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMConcat.scChar(JI)V",       "vm/VM.scCharAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMConcat.scInt(JI)V",        "vm/VM.scIntAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMConcat.scEnd(J)J",         "vm/VM.scEndAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMConcat.scStr(JJ)V",        "vm/VM.scStrAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMConcat.scLong(JJ)V",       "vm/VM.scLongAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.printStr(J)V",      "vm/VM.printStrAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.denylistTrap()V",   "vm/VM.denylistTrapAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.nanoTime()J",       "vm/VM.nanoTimeAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.currentTimeMillis()J", "vm/VM.currentTimeMillisAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.identity(J)J",      "vm/VM.identityAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.unsafeFence(J)V", "vm/VM.unsafeFenceAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.arraycopy(JIJII)V", "vm/VM.arraycopyAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newNpe()J",         "vm/VM.newNpeAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newAioobe()J",      "vm/VM.newAioobeAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newArith()J",       "vm/VM.newArithAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newAse()J",          "vm/VM.newAseAddr");     // ArrayStoreException
        stashHelper(image, staticWord, wordOffset, "vm/VM.arrayStoreOk(JJ)I",  "vm/VM.arrayStoreOkAddr"); // aastore check
        stashHelper(image, staticWord, wordOffset, "vm/VM.newCce()J",          "vm/VM.newCceAddr");     // ClassCastException
        stashHelper(image, staticWord, wordOffset, "vm/VM.castOk(JJ)I",        "vm/VM.castOkAddr");     // checkcast predicate
        stashHelper(image, staticWord, wordOffset, "vm/VM.newUnresolved(J)J", "vm/VM.newUnresolvedAddr"); // late-resolved `new`
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.printStackTrace(J)V", "vm/VM.printStackTraceAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.fileOpen(J)J",       "vm/VM.fileOpenAddr");   // M3: FileInputStream.open0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.dnsResolve(J)I",     "vm/VM.dnsResolveAddr"); // M3: InetAddress.resolve0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.vhFieldOffset(JJ)J", "vm/VM.vhFieldOffsetAddr"); // M3: VarHandle.fieldOffset0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.fieldMods(JJ)I", "vm/VM.fieldModsAddr");         // reflection: Class.fieldMods0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.fieldTypeChar(JJ)I", "vm/VM.fieldTypeCharAddr"); // reflection: Class.fieldTypeChar0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.classAtPc(J)J", "vm/VM.classAtPcAddr");           // getCallerClass (updater access check)
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockSocket0(JJJJ)I", "vm/VM.sockSocket0Addr");   // M3 sockets
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockConnect0(JJJJ)I","vm/VM.sockConnect0Addr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockRead0(JJJ)I",    "vm/VM.sockRead0Addr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockWrite0(JJJ)I",   "vm/VM.sockWrite0Addr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockClose0(J)V",     "vm/VM.sockClose0Addr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockAvailable(J)I",  "vm/VM.sockAvailableAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.fdVal(J)I",          "vm/VM.fdValAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.setFdVal(JJ)V",      "vm/VM.setFdValAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockNoop()V",        "vm/VM.sockNoopAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.sockZero()J",        "vm/VM.sockZeroAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.classNameOf(J)J",    "vm/VM.classNameAddr");  // M4: Class.getName0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.forName(J)J",        "vm/VM.forNameAddr");    // reflection M1: Class.forName0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.defineClass(JJJJ)J", "vm/VM.defineClassAddr"); // reflection M3: ClassLoader.defineClass0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.classModifiers(J)J", "vm/VM.classModifiersAddr"); // reflection M1: Class.getModifiers
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.methodResolve(JJ)I", "vm/VM.methodResolveAddr");  // reflection M2: Method.methodResolve0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.methodResolveDesc(JJJ)I", "vm/VM.methodResolveDescAddr"); // overload-exact resolve
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.methodInfo(JJJ)I",   "vm/VM.methodInfoAddr");     // reflection M2: Method.methodInfo0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.constructorResolve(JJ)I", "vm/VM.constructorResolveAddr"); // M2: Constructor.ctorResolve0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.allocInstance(J)J",  "vm/VM.allocInstanceAddr");  // reflection M2: Constructor.allocInstance0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.superclassOf(J)J",   "vm/VM.superclassAddr"); // M4: Class.superclass0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.currentThreadObj()J","vm/VM.currentThreadAddr"); // M4: Thread.currentThread0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.arrayClone(J)J",    "vm/VM.arrayCloneAddr");  // [T.clone() intrinsic
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.newReflectArray(JJ)J", "vm/VM.newReflectArrayAddr"); // reflect/Array.newInstance0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.componentTypeOf(J)J", "vm/VM.componentTypeAddr");   // Class.getComponentType0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.isArrayClass(J)J", "vm/VM.isArrayClassAddr");        // Class.isArray0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.isPrimClass(J)J", "vm/VM.isPrimClassAddr");          // Class.isPrimitive0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.primClassOf(J)J", "vm/VM.primClassAddr");            // Class.primitiveClass0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.annoPresent(JJ)J", "vm/VM.annoPresentAddr");         // Method.annoPresent0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.declaredMethodAt(JJ)J", "vm/VM.declMethodAddr");     // Class.declaredMethodAt0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.declaredMethodDescAt(JJ)J", "vm/VM.declMethodDescAddr"); // Class.declaredMethodDescAt0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.declaredMethodCount(J)J", "vm/VM.declMethodCountAddr"); // Class.declaredMethodCount0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.throwableTrace(J)J", "vm/VM.stackTraceAddr");        // Throwable.stackTrace0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.virtualResolve(JJ)J", "vm/VM.virtualResolveAddr"); // late virtual dispatch
        stashHelper(image, staticWord, wordOffset, "vm/VM.getClassOf(J)J",    "vm/VM.getClassAddr");
        for (int br = 0; br < BAKE_ROOTS.length; br++)
        {
            stashHelper(image, staticWord, wordOffset, BAKE_ROOTS[br][0], BAKE_ROOTS[br][1]);
        }
        for (int bs = 0; bs < BAKE_STATICS.length; bs++)
        {
            fillStatic(image, staticWord, BAKE_STATICS[bs][1], addr(staticWord.get(BAKE_STATICS[bs][0])));
        }
        // M8 static state: a deferred-<clinit> class's statics would read 0, so snapshot the seed
        // JVM's initialized PRIMITIVE values. With statics unification the loader ADOPTS a baked
        // class's block, so snapshot EVERY declared primitive of a deferred baked class (loader-
        // compiled readers see the block too, not just the writer-referenced fields); the sparse
        // referenced set still covers non-block classes. Unreferenced OBJECT statics stay null
        // until a runtime <clinit> fills the shared slot.
        for (int si = 0; si < statics.size(); si++)
        {
            String key = statics.at(si);
            if (clinitDeferred(ownerOf(key)))
            {
                Long bits = StaticSnapshot.primitiveBits(key);
                if (bits != null)
                {
                    fillStatic(image, staticWord, key, bits);
                }
            }
        }
        for (int vi = 0; vi < vtSigClasses.size(); vi++)
        {
            String cls = vtSigClasses.get(vi);
            if (!clinitDeferred(cls))
            {
                continue;
            }
            for (ClassFile.FieldInfo fld : registry.resolve(cls).fields())
            {
                if (fld.isStatic())
                {
                    Long bits = StaticSnapshot.primitiveBits(cls + "." + fld.name());
                    if (bits != null)
                    {
                        fillStatic(image, staticWord, cls + "." + fld.name(), bits);
                    }
                }
            }
        }
        // M8 static state, object statics: write every deep-snapshotted object, then point each
        // static slot at its root.
        for (int bi = 0; bi < bakedObjs.size(); bi++)
        {
            writeBakedObject(image, bakedObjWord[bi], bakedObjs.get(bi), bakedObjs, bakedObjWord, tibWord);
        }
        for (int bk = 0; bk < bakedKeys.size(); bk++)
        {
            int root = bakedIndexOf(bakedObjs, bakedRoots.get(bk));
            fillStatic(image, staticWord, bakedKeys.get(bk), addr(bakedObjWord[root]));
        }
        for (int b = 0; b < blobs.size(); b++)
        {
            Blob blob = blobs.get(b);
            writeBytes(image, blobWord[b], blob.bytes());
            fillStatic(image, staticWord, blob.addrKey(), addr(blobWord[b]));
            fillStatic(image, staticWord, blob.lenKey(),  blob.bytes().length);
        }
        // class table: name bytes + class bytes, then the directory pointing at them.
        for (int i = 0; i < classCount; i++)
        {
            byte[] name = asciiBytes(classNames.get(i));
            byte[] bytes = registry.rawBytes(classNames.get(i));
            writeBytes(image, classNameWord[i], name);
            writeBytes(image, classBytesWord[i], bytes);
            int e = classDirWord + i * CLASS_ENTRY_WORDS;
            writeLong(image, e,     addr(classNameWord[i]));
            writeLong(image, e + 2, name.length);
            writeLong(image, e + 4, addr(classBytesWord[i]));
            writeLong(image, e + 6, bytes.length);
        }
        fillStatic(image, staticWord, "vm/VM.classDir",   addr(classDirWord));
        fillStatic(image, staticWord, "vm/VM.classCount", classCount);
        // M3: RAMFS file table — path bytes + content bytes, then the directory pointing at them.
        for (int i = 0; i < fileCount; i++)
        {
            byte[] name = asciiBytes(files.get(i).path());
            byte[] bytes = files.get(i).bytes();
            writeBytes(image, fileNameWord[i], name);
            writeBytes(image, fileBytesWord[i], bytes);
            int e = fileDirWord + i * CLASS_ENTRY_WORDS;
            writeLong(image, e,     addr(fileNameWord[i]));
            writeLong(image, e + 2, name.length);
            writeLong(image, e + 4, addr(fileBytesWord[i]));
            writeLong(image, e + 6, bytes.length);
        }
        fillStatic(image, staticWord, "vm/VM.fileDir",   addr(fileDirWord));
        fillStatic(image, staticWord, "vm/VM.fileCount", fileCount);

        // --- interned string literals as byte[] objects ([null TIB][status][length][bytes]) ---
        for (int _s11 = 0; _s11 < strings.size(); _s11++)
        {
            byte[] s = strings.at(_s11);
            writeStringObject(image, strWord.get(s), s, addr(tibWord.get("[B")));
        }

        CodeBuffer cb = new CodeBuffer();                           // base = LOAD_ADDRESS
        for (int w : image)
        {
            cb.emit(w);
        }
        for (int _v3 = 0; _v3 < calls.size(); _v3++)
        {
            GlobalCall c = calls.get(_v3);
            int calleeBase = wordOffset.get(c.calleeKey());
            if (calleeBase < 0)
            {
                throw new IllegalStateException("unresolved call to " + c.calleeKey());
            }
            cb.set(c.siteWord(), A64.bl((calleeBase - c.siteWord()) * 4));
        }
        for (int _v4 = 0; _v4 < tibs.size(); _v4++)
        {
            GlobalTib t = tibs.get(_v4);
            int w = tibWord.get(t.className());
            if (w < 0)
            {
                throw new IllegalStateException("no TIB for " + t.className());
            }
            cb.patchAddr(t.siteWord(), t.reg(), addr(w));          // store absolute TIB address
        }
        for (int _v5 = 0; _v5 < strs.size(); _v5++)
        {
            GlobalStr s = strs.get(_v5);
            cb.patchAddr(s.siteWord(), s.reg(), addr(strWord.get(s.text())));   // store byte[] address
        }
        for (int _v5b = 0; _v5b < strObjSites.size(); _v5b++)
        {
            GlobalStr s = strObjSites.get(_v5b);
            Object str = strObjs.get(strObjIndex.get(s.text()));
            cb.patchAddr(s.siteWord(), s.reg(), addr(bakedObjWord[bakedIndexOf(bakedObjs, str)]));
        }
        for (int _v6 = 0; _v6 < stats.size(); _v6++)
        {
            GlobalStatic s = stats.get(_v6);
            cb.patchAddr(s.siteWord(), s.reg(), addr(staticWord.get(s.fieldKey()))); // static field address
        }
        for (int _v7 = 0; _v7 < types.size(); _v7++)
        {
            GlobalType t = types.get(_v7);
            cb.patchAddr(t.siteWord(), t.reg(), addr(typeWord.get(t.className())));  // class Type address
        }
        return cb;
    }

    /** Add {@code cls} and its WHOLE registered super chain (through java/* up to Object) to
     *  {@code set}. M8 world unification: every class in a Type walk gets a real, distinct Type
     *  node -- the old isRoot stop left java/* classes with no Type at all, so their instanceof
     *  sites all patched the same out-of-range address (accidentally-consistent, no discrimination). */
    private void addTypeClass(String cls, StrSet set)
    {
        while (cls != null && set.add(cls))
        {
            cls = model.superClassName(cls);
        }
    }

    /** Array-Type display: depth 1, display = [Object's Type, self]; empty-but-computed bitmap
     *  (arrays implement no modeled interfaces). */
    private void writeArrayDisplay(int[] image, int typeW, int dispW, int objectTypeW)
    {
        writeLong(image, typeW + ObjectModel.TYPE_DEPTH_OFFSET / 4, 1);
        writeLong(image, typeW + ObjectModel.TYPE_DISPLAY_OFFSET / 4, addr(dispW));
        writeLong(image, typeW + ObjectModel.TYPE_IMPLEMENTS_OFFSET / 4, 1);   // marker only
        writeLong(image, dispW, addr(objectTypeW));
        writeLong(image, dispW + 2, addr(typeW));
    }

    /** Superclass-chain depth of {@code cls} (java/lang/Object = 0). */
    private int chainDepthOf(String cls)
    {
        int d = 0;
        String sup = model.superClassName(cls);
        while (sup != null)
        {
            d += 1;
            sup = model.superClassName(sup);
        }
        return d;
    }

    private int vtableLength(String cls)
    {
        return model.vtable(cls).size();
    }

    /** The vtSig {name, descriptor} slot pairs for {@code cls}: a class's flattened vtable, or an
     *  interface's flattened per-interface method list (its itable slot numbering). */
    private Vec<String[]> sigPairsFor(String cls)
    {
        Vec<String[]> out = new Vec<>();
        if (registry.resolve(cls).isInterface())
        {
            Vec<ClassFile.Method> ms = ClassFile.interfaceMethods(cls, this);
            for (int i = 0; i < ms.size(); i++)
            {
                out.add(new String[] {ms.get(i).name, ms.get(i).descriptor});
            }
            return out;
        }
        Vec<ClassModel.VSlot> vt = model.vtable(cls);
        for (int i = 0; i < vt.size(); i++)
        {
            out.add(new String[] {vt.get(i).name(), vt.get(i).descriptor()});
        }
        return out;
    }

    /** The invokeinterface-target interfaces that {@code cls} implements, in use order. */
    private Vec<String> implementedUsedInterfaces(String cls, StrSet usedInterfaces)
    {
        StrSet all = model.allInterfaces(cls);
        Vec<String> out = new Vec<>();
        for (int _s12 = 0; _s12 < usedInterfaces.size(); _s12++)
        {
            String i = usedInterfaces.at(_s12);
            if (all.contains(i))
            {
                out.add(i);
            }
        }
        return out;
    }

    /** Mark {@code cls} used; on first use, schedule its {@code <clinit>} (eager init) unless it's a stock
     *  java.base class we bake methods-only (its {@code <clinit>} is deferred -- M8 full bootstrap, path 1). */
    private void use(String cls, StrSet used, Vec<String> clinitOrder, Vec<String> worklist)
    {
        if (used.add(cls) && model.hasClinit(cls) && !bakeNoClinit(cls))
        {
            String ck = cls + ".<clinit>()V";
            clinitOrder.add(ck);
            worklist.add(ck);
        }
    }

    /** M8 full bootstrap (path 1): stock java.base classes the writer bakes into the image METHODS-ONLY --
     *  their {@code <clinit>} is NOT compiled/run at build time (the host writer can't compile some java.base
     *  {@code <clinit>}s -- e.g. Math's uses {@code ldc} class-literal, StringUTF16's calls a native). A baked
     *  method that reads such a class's statics gets the SEED JVM's initialized values instead: the writer
     *  snapshots them into the image statics ({@link StaticSnapshot} -- primitives only so far). Only
     *  consulted when such a class is actually reached by the compile closure (e.g. under VM.BOOTSTRAP_PROBE). */
    private static boolean bakeNoClinit(String cls)
    {
        return cls.equals("java/lang/Math")
                || cls.equals("java/lang/Integer")
                || cls.equals("java/lang/StringUTF16")
                || cls.equals("java/lang/Integer$IntegerCache")
                || cls.equals("java/lang/Long")
                || cls.equals("java/lang/Long$LongCache")
                || cls.equals("java/lang/String");
    }

    /** Owner class of a method key ("o/C.m(desc)") or field key ("o/C.f"). */
    private static String ownerOf(String key)
    {
        int paren = key.indexOf('(');
        return key.substring(0, key.lastIndexOf('.', paren >= 0 ? paren : key.length()));
    }

    /** Generate VM.initClasses()'s body: save LR, BL each &lt;clinit&gt;, restore, ret. */
    private CompiledMethod generateInitClasses(Vec<String> clinits)
    {
        int frame = A64.align16(8);                                 // LR only
        IntVec w = new IntVec();
        BaselineCompiler.Relocations relocs = new BaselineCompiler.Relocations();
        w.add(A64.subImm(31, 31, frame));
        w.add(A64.strx(30, 31, 0));
        for (int ci = 0; ci < clinits.size(); ci++)
        {
            if (stubbedKeys.contains(clinits.get(ci)))
            {
                continue;      // uncompilable <clinit> -> deferred; its statics come from the snapshot
            }
            relocs.callSites().add(new BaselineCompiler.CallSite(w.size(), clinits.get(ci)));
            w.add(A64.bl(0));
        }
        w.add(A64.ldrx(30, 31, 0));
        w.add(A64.addImm(31, 31, frame));
        w.add(A64.ret());
        int[] words = w.toArray();
        Vec<BaselineCompiler.HandlerRange> handlers = new Vec<>();
        return new CompiledMethod(words, relocs, frame, handlers, null);   // synthetic: no bytecode -> no line info
    }

    /** Image words a byte[] object for {@code b} occupies: header(16)+length(8)+bytes, 8-aligned. */
    private static int stringWords(byte[] b)
    {
        int n = b.length;
        return (ObjectModel.ARRAY_BASE_OFFSET + ((n + 7) & ~7)) / 4;
    }

    /** Write a byte[] object holding literal bytes {@code b} at image word {@code w}. */
    private static void writeStringObject(int[] image, int w, byte[] b, long byteArrTib)
    {
        writeLong(image, w + ObjectModel.TIB_OFFSET / 4, byteArrTib);       // the canonical [B TIB
        writeLong(image, w + ObjectModel.STATUS_OFFSET / 4, 0);
        writeLong(image, w + ObjectModel.ARRAY_LENGTH_OFFSET / 4, b.length);
        int base = w + ObjectModel.ARRAY_BASE_OFFSET / 4;
        for (int i = 0; i < b.length; i++)
        {
            int word = base + i / 4;
            int shift = (i % 4) * 8;
            image[word] |= (b[i] & 0xFF) << shift;
        }
    }

    /** BFS-discover the object graph reachable from {@code root} into {@code objs}, identity-deduped
     *  (so shared/aliased objects bake once and cycles terminate). */
    private void bakeDiscover(Object root, Vec<Object> objs)
    {
        Vec<Object> work = new Vec<>();
        work.add(root);
        while (!work.isEmpty())
        {
            Object o = work.removeFirst();
            if (bakedIndexOf(objs, o) >= 0)
            {
                continue;
            }
            objs.add(o);
            if (o.getClass().isArray())
            {
                if (!o.getClass().getComponentType().isPrimitive())
                {
                    Object[] a = (Object[]) o;
                    for (Object e : a)
                    {
                        if (e != null)
                        {
                            work.add(e);
                        }
                    }
                }
                continue;
            }
            Vec<ClassFile> chain = superChain(bakedClassName(o));
            for (int ci = 0; ci < chain.size(); ci++)
            {
                for (ClassFile.FieldInfo f : chain.get(ci).fields())
                {
                    if (!f.isStatic() && isRefDescriptor(f.descriptor()))
                    {
                        Object child = StaticSnapshot.instanceRef(o, f.name());
                        if (child != null)
                        {
                            work.add(child);
                        }
                    }
                }
            }
        }
    }

    /** {@code cls}'s classfile chain, ROOT-most first (Object..cls) — the field-layout order:
     *  inherited fields lay out before a subclass's own, matching the on-metal loader. */
    private Vec<ClassFile> superChain(String cls)
    {
        Vec<ClassFile> chain = new Vec<>();
        String c = cls;
        while (c != null)
        {
            chain.add(registry.resolve(c));
            c = registry.resolve(c).superClassName();
        }
        Vec<ClassFile> rootFirst = new Vec<>();
        for (int i = chain.size() - 1; i >= 0; i--)
        {
            rootFirst.add(chain.get(i));
        }
        return rootFirst;
    }

    /** Image words the baked object {@code o} occupies. */
    private int bakedWords(Object o)
    {
        if (o.getClass().isArray())
        {
            if (o.getClass().getComponentType().isPrimitive())
            {
                return arrayWords(o);
            }
            int n = java.lang.reflect.Array.getLength(o);
            return (ObjectModel.ARRAY_BASE_OFFSET + n * ObjectModel.WORD) / 4;
        }
        String cls = bakedClassName(o);
        return ObjectModel.scalarSize(ClassFile.chainFieldBase(cls, this)
                                      + registry.resolve(cls).instanceFieldCount()) / 4;
    }

    /** Write baked object {@code o} at image word {@code w}; references resolve through the graph. */
    private void writeBakedObject(int[] image, int w, Object o, Vec<Object> objs, int[] objWord,
                                  StrIntTable tibWord)
    {
        if (o.getClass().isArray())
        {
            if (o.getClass().getComponentType().isPrimitive())
            {
                // Typed with the canonical prim-array TIB (registered in tibWord under "[B"...):
                // a baked String's value byte[] passes array type-checks like any loader array.
                int at = atypeOfComponent(o.getClass().getComponentType());
                writeArrayObject(image, w, o, addr(tibWord.get(PRIM_ARRAY_DESC[at - 4])));
                return;
            }
            Object[] a = (Object[]) o;
            Class<?> comp = o.getClass().getComponentType();
            String cdesc = comp.isArray() ? "[" + comp.getName().replace('.', '/')
                                          : "[L" + comp.getName().replace('.', '/') + ";";
            int atw = tibWord.get(cdesc);
            writeLong(image, w + ObjectModel.TIB_OFFSET / 4, atw >= 0 ? addr(atw) : 0);
            writeLong(image, w + ObjectModel.STATUS_OFFSET / 4, 0);
            writeLong(image, w + ObjectModel.ARRAY_LENGTH_OFFSET / 4, a.length);
            int base = w + ObjectModel.ARRAY_BASE_OFFSET / 4;
            for (int i = 0; i < a.length; i++)
            {
                long ref = a[i] == null ? 0 : addr(objWord[bakedIndexOf(objs, a[i])]);
                writeLong(image, base + i * WORDS_PER_SLOT, ref);
            }
            return;
        }
        String cls = bakedClassName(o);
        int tw = tibWord.get(cls);
        writeLong(image, w + ObjectModel.TIB_OFFSET / 4, tw >= 0 ? addr(tw) : 0);
        writeLong(image, w + ObjectModel.STATUS_OFFSET / 4, 0);
        Vec<ClassFile> chain = superChain(cls);              // inherited fields first (loader layout)
        int slot = 0;
        for (int ci = 0; ci < chain.size(); ci++)
        {
            for (ClassFile.FieldInfo f : chain.get(ci).fields())
            {
                if (f.isStatic())
                {
                    continue;
                }
                long bits;
                if (isRefDescriptor(f.descriptor()))
                {
                    Object child = StaticSnapshot.instanceRef(o, f.name());
                    bits = child == null ? 0 : addr(objWord[bakedIndexOf(objs, child)]);
                }
                else
                {
                    bits = StaticSnapshot.instanceBits(o, f.name());
                }
                writeLong(image, w + ObjectModel.fieldOffset(slot) / 4, bits);
                slot++;
            }
        }
    }

    /** Index of {@code o} in {@code objs} by identity, or -1. */
    private static int bakedIndexOf(Vec<Object> objs, Object o)
    {
        for (int i = 0; i < objs.size(); i++)
        {
            if (objs.get(i) == o)
            {
                return i;
            }
        }
        return -1;
    }

    /** The internal-form class name of the baked scalar {@code o} (must be registered). */
    private static String bakedClassName(Object o)
    {
        return o.getClass().getName().replace('.', '/');
    }

    /** Whether field descriptor {@code d} is reference-typed (class or array). */
    private static boolean isRefDescriptor(String d)
    {
        return d.startsWith("L") || d.startsWith("[");
    }

    /** Image words the baked array object for {@code v} occupies: header(16)+length(8)+elements, 8-aligned. */
    private static int arrayWords(Object v)
    {
        int n = java.lang.reflect.Array.getLength(v);
        return (ObjectModel.ARRAY_BASE_OFFSET + ((n * arrayScale(v) + 7) & ~7)) / 4;
    }

    /** Element size in bytes of the primitive array {@code v}; any other object is not yet bakeable. */
    private static int arrayScale(Object v)
    {
        Class<?> ct = v.getClass().getComponentType();
        if (ct == null || !ct.isPrimitive())
        {
            throw new IllegalStateException("unsupported baked object static (only primitive arrays): "
                    + v.getClass().getName());
        }
        if (ct == byte.class || ct == boolean.class)
        {
            return 1;
        }
        if (ct == char.class || ct == short.class)
        {
            return 2;
        }
        if (ct == int.class || ct == float.class)
        {
            return 4;
        }
        return 8;                                                   // long, double
    }

    /** Element {@code i} of the primitive array {@code v} as raw little-endian bits. */
    private static long elementBits(Object v, int i)
    {
        if (v instanceof boolean[] a)
        {
            return a[i] ? 1L : 0L;
        }
        if (v instanceof float[] a)
        {
            return Float.floatToRawIntBits(a[i]) & 0xFFFFFFFFL;
        }
        if (v instanceof double[] a)
        {
            return Double.doubleToRawLongBits(a[i]);
        }
        return java.lang.reflect.Array.getLong(v, i);               // byte/char/short/int/long widen
    }

    /** Write a baked primitive-array object holding {@code v}'s elements at image word {@code w} —
     *  the same shape as {@link #writeStringObject} (null TIB), generalized over element size. */
    private static void writeArrayObject(int[] image, int w, Object v, long tibAddr)
    {
        int scale = arrayScale(v);
        int n = java.lang.reflect.Array.getLength(v);
        writeLong(image, w + ObjectModel.TIB_OFFSET / 4, tibAddr);
        writeLong(image, w + ObjectModel.STATUS_OFFSET / 4, 0);
        writeLong(image, w + ObjectModel.ARRAY_LENGTH_OFFSET / 4, n);
        int base = w * 4 + ObjectModel.ARRAY_BASE_OFFSET;           // byte offset within the image
        for (int i = 0; i < n; i++)
        {
            long bits = elementBits(v, i);
            for (int b = 0; b < scale; b++)
            {
                int off = base + i * scale + b;
                image[off / 4] |= (int) ((bits >>> (8 * b)) & 0xFF) << ((off % 4) * 8);
            }
        }
    }

    /** Image words an 8-byte-aligned run of {@code len} bytes occupies. */
    private static int align8Words(int len)
    {
        return ((len + 7) & ~7) / 4;
    }

    /** A {@code {u2 length}{bytes}} Utf8 run (as the loader's registry stores names), so the metal
     *  {@code writeName(addr+2, u2(addr))} formatter prints it uniformly. */
    private static byte[] utf8(String s)
    {
        byte[] b = s.getBytes();
        byte[] u = new byte[2 + b.length];
        u[0] = (byte) (b.length >> 8);
        u[1] = (byte) b.length;
        System.arraycopy(b, 0, u, 2, b.length);
        return u;
    }

    private static final byte[] LINE_NUMBER_TABLE = "LineNumberTable".getBytes();

    /**
     * Stack-trace line table for an image method: {@code {wordOffset, line}} pairs, one per source-line
     * transition, zipping the core's bci-&gt;machine-offset map ({@code bcToWord}) with the classfile
     * LineNumberTable. Machine offsets are placement-independent, so this is valid for the real-base layout.
     * Empty (no debug info) if the method has no code, no Code offset, or no LineNumberTable.
     */
    private int[] buildLineTable(int[] bcToWord, String key)
    {
        if (bcToWord == null)
        {
            return new int[0];
        }
        Resolved r = lookup(key);
        if (r.method.code == null || r.method.codeBodyOff < 0)
        {
            return new int[0];
        }
        int lntOff = ClassReader.lineNumberTableOff(r.cf.bytes(), r.cf.cpOff(), r.method.codeBodyOff, LINE_NUMBER_TABLE);
        if (lntOff < 0)
        {
            return new int[0];
        }
        IntVec out = new IntVec();
        int prev = -1;
        for (int bci = 0; bci < bcToWord.length; bci++)
        {
            if (bcToWord[bci] >= 0)
            {
                int line = ClassReader.lineForBci(r.cf.bytes(), lntOff, bci);
                if (line != prev)
                {
                    out.add(bcToWord[bci]);
                    out.add(line);
                    prev = line;
                }
            }
        }
        return out.toArray();
    }

    /** Whether {@code names} already holds {@code name} (small lists — linear is fine). */
    private static boolean contains(Vec<String> names, String name)
    {
        for (int i = 0; i < names.size(); i++)
        {
            if (names.get(i).equals(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The names sorted ascending (ASCII/lexicographic, matching the metal {@code findClass} byte compare) so
     * the embedded class directory can be binary-searched. Host-only (the writer runs on the seed JVM), so a
     * JDK sort is fine.
     */
    /** Keep only the demand-loadable classes ({@code java/}, {@code jdk/}, {@code sun/}, {@code demo/}) —
     *  the stock library + guest overrides + demos. VM internals resolve via AOT code / intrinsics, not the
     *  class table. */
    private static Vec<String> demandLoadable(Vec<String> names)
    {
        Vec<String> out = new Vec<>();
        for (int i = 0; i < names.size(); i++)
        {
            String n = names.get(i);
            // Unnamed-package (top-level, no '/') classes are demand-loadable too, so an UNMODIFIED JDK test
            // (e.g. GenerifyStackTraces) runs as the manifest main. All VM-internal classes carry a package.
            if (n.startsWith("java/") || n.startsWith("jdk/") || n.startsWith("sun/") || n.startsWith("demo/")
                    || n.startsWith("org/")               // JUnit-lite shims (org/junit/...) for the JDK tests
                    // zip/* is dual-world by design: the image-baked copy backs the class loader's jar
                    // reading, and the SAME source is demand-loaded into the guest world so the
                    // java.util.zip overlays can delegate to it. Ordinary bytecode, no intrinsics.
                    || n.startsWith("zip/")
                    || !n.contains("/"))
            {
                out.add(n);
            }
        }
        return out;
    }

    private static Vec<String> sortByName(Vec<String> names)
    {
        String[] arr = new String[names.size()];
        for (int i = 0; i < arr.length; i++)
        {
            arr[i] = names.get(i);
        }
        java.util.Arrays.sort(arr);
        Vec<String> out = new Vec<>();
        for (int i = 0; i < arr.length; i++)
        {
            out.add(arr[i]);
        }
        return out;
    }

    /** A class name's bytes (internal names are ASCII — no charset needed on metal). */
    private static byte[] asciiBytes(String s)
    {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++)
        {
            b[i] = (byte) s.charAt(i);
        }
        return b;
    }

    /** Absolute address of image word index {@code w}. */
    private static long addr(int w)
    {
        return CodeBuffer.LOAD_ADDRESS + (long) w * 4;
    }

    /** Write a 64-bit value as two little-endian image ints at word index {@code w}. */
    private static void writeLong(int[] image, int w, long v)
    {
        image[w]     = (int) (v & 0xFFFFFFFFL);
        image[w + 1] = (int) (v >>> 32);
    }

    /** Stash a compiled method's address into a VM static field, if both exist. */
    private static void stashHelper(int[] image, StrIntTable staticWord,
                                    StrIntTable wordOffset, String methodKey, String field)
    {
        int w = wordOffset.get(methodKey);
        if (w >= 0)
        {
            fillStatic(image, staticWord, field, addr(w));
        }
    }

    /** Fill a (writer-initialized) static field slot with {@code value}, if the field is used. */
    private static void fillStatic(int[] image, StrIntTable staticWord, String key, long value)
    {
        int w = staticWord.get(key);
        if (w >= 0)
        {
            writeLong(image, w, value);
        }
    }

    /** Pack raw {@code bytes} into image words (little-endian) starting at word {@code w}. */
    private static void writeBytes(int[] image, int w, byte[] bytes)
    {
        for (int i = 0; i < bytes.length; i++)
        {
            image[w + i / 4] |= (bytes[i] & 0xFF) << ((i % 4) * 8);
        }
    }

    private CompiledMethod compile(String key, long base, boolean isEntry)
    {
        Resolved r = lookup(key);
        try
        {
            return new BaselineCompiler(r.cf, this).compileMethod(r.method, base, isEntry);
        }
        catch (RuntimeException e)
        {
            throw new RuntimeException("compiling " + key + ": " + e.getMessage(), e);
        }
    }

    /** M8 bake stubs: compile {@code key}, or -- when a STOCK java.base method can't be host-compiled
     *  (native/abstract = no Code, unsupported opcode, missing helper or class) -- bake a RESOLVE
     *  stub in its place, so the closure of a baked method survives its uncompilable fringe. A stub
     *  that actually gets CALLED resolves lazily through {@code VM.bakeResolve} (loader demand-load
     *  + tail-branch). Failures outside java.base (vm/board/net/...) stay fatal. */
    private CompiledMethod compileOrStub(String key, long base, boolean isEntry)
    {
        if (stubbedKeys.contains(key))
        {
            return stubMethod(key);
        }
        try
        {
            return compile(key, base, isEntry);
        }
        catch (RuntimeException e)
        {
            if (!stubbable(key))
            {
                throw e;
            }
            if (stubbedKeys.add(key))
            {
                stubIndex.put(key, stubIndex.size());
                System.out.println("  bake-stub " + key + " (" + e.getMessage() + ")");
            }
            return stubMethod(key);
        }
    }

    /** Whether a compile failure of {@code key} may be stubbed: only stock java.base territory.
     *  (Guest overlays share these prefixes but are never writer-compiled outside bake roots.) */
    private static boolean stubbable(String key)
    {
        return bakeDomain(ownerOf(key));
    }

    /** Whether {@code cls} is stock-java.base territory for the M8 bake (stub-tolerant). */
    private static boolean bakeDomain(String cls)
    {
        return cls.startsWith("java/") || cls.startsWith("jdk/") || cls.startsWith("sun/");
    }

    /** The RESOLVE stub baked for an uncompilable method: preserve the call's args (x0..x7) + LR,
     *  call {@code VM.bakeResolve(stubIndex)} -- which demand-loads the class through the on-metal
     *  loader and returns a callable buffer, memoized in the stub table -- then restore and
     *  tail-branch to it. Constant-size regardless of index (one movz), so both passes agree. */
    private CompiledMethod stubMethod(String key)
    {
        int idx = stubIndex.get(key);
        IntVec w = new IntVec();
        BaselineCompiler.Relocations relocs = new BaselineCompiler.Relocations();
        // Preserve x0..x15, not x0..x7: VM.bakeResolve demand-loads a whole class between the save and the
        // tail-branch, so every argument register the resolved method will read must survive it. Saving only
        // x0..x7 silently dropped the 9th argument onward -- the same defect the loader's lazy trampoline had
        // (see Loader.buildLazyTramp), and the one behind demo deep10's wrong answer.
        w.add(A64.subImm(31, 31, 144));
        w.add(A64.strx(30, 31, 0));
        for (int r = 0; r <= 15; r++)
        {
            w.add(A64.strx(r, 31, 8 + r * 8));
        }
        w.add(A64.movz(0, idx, 0));
        relocs.callSites().add(new BaselineCompiler.CallSite(w.size(), BAKE_RESOLVE));
        w.add(A64.bl(0));
        w.add(A64.movReg(16, 0));
        for (int r = 0; r <= 15; r++)
        {
            w.add(A64.ldrx(r, 31, 8 + r * 8));
        }
        w.add(A64.ldrx(30, 31, 0));
        w.add(A64.addImm(31, 31, 144));
        w.add(A64.br(16));
        Vec<BaselineCompiler.HandlerRange> handlers = new Vec<>();
        return new CompiledMethod(w.toArray(), relocs, 144, handlers, null);
    }

    /** Whether {@code cls}'s {@code <clinit>} never runs (neither at build time nor boot): explicitly
     *  deferred ({@link #bakeNoClinit}) or stubbed as uncompilable. Such a class's referenced statics
     *  are snapshotted from the seed JVM instead. */
    private boolean clinitDeferred(String cls)
    {
        return bakeNoClinit(cls) || stubbedKeys.contains(cls + ".<clinit>()V");
    }

    private Resolved lookup(String key)
    {
        int dot = key.lastIndexOf('.', key.indexOf('('));
        String owner = key.substring(0, dot);
        String name = key.substring(dot + 1, key.indexOf('('));
        String desc = key.substring(key.indexOf('('));
        ClassFile cf = resolve(owner);
        return new Resolved(cf, cf.method(name, desc));
    }

    /** Result of {@link #analyzeClosure}: the reachable class set + method-key pull graph + skipped methods. */
    public record ClosureReport(StrSet classes, StrIntTable parent, Vec<String> parentKey, Vec<String> failures) {}

    /**
     * HOST-ONLY DIAGNOSTIC (task #41): run just the discovery BFS from {@code entryKey} — no sizing/emit —
     * and report the reachable CLASS closure the metal demand-loader would pull. Mirrors {@link #build}'s
     * worklist (call sites + tibRef/static-owner {@code use()} which schedules {@code <clinit>} on first use,
     * + instantiated-class vtable methods) but tolerates a compile failure on a deep java.base method by
     * recording and skipping it (the metal loader's jitFail-and-continue), so one unsupported opcode doesn't
     * abort the measurement. Lets us size/attack the String-ops closure without a hardware round-trip.
     */
    public ClosureReport analyzeClosure(String entryKey)
    {
        StrSet used = new StrSet();
        Vec<String> clinitOrder = new Vec<>();
        Vec<String> worklist = new Vec<>();
        Vec<String> failures = new Vec<>();
        StrIntTable seen = new StrIntTable();
        StrIntTable parent = new StrIntTable();          // callee key -> index into parentKey (its puller)
        Vec<String> parentKey = new Vec<>();
        worklist.add(entryKey);
        parent.put(entryKey, 0);
        parentKey.add("<root>");
        while (worklist.size() > 0)
        {
            String k = worklist.removeFirst();
            if (k.equals(INIT_CLASSES) || seen.containsKey(k))
            {
                continue;
            }
            seen.put(k, 1);
            CompiledMethod cm;
            try
            {
                cm = compile(k, CodeBuffer.LOAD_ADDRESS, k.equals(entryKey));
            }
            catch (RuntimeException e)
            {
                failures.add(k + "  ::  " + e.getMessage());
                continue;
            }
            var cs = cm.relocs().callSites();
            for (int i = 0; i < cs.size(); i++)
            {
                pushWl(worklist, parent, parentKey, k, cs.get(i).calleeKey());
            }
            var tr = cm.relocs().tibRefs();
            for (int i = 0; i < tr.size(); i++)
            {
                if (!tr.get(i).className().startsWith("["))
                {
                    useDiag(tr.get(i).className(), used, clinitOrder, worklist, parent, parentKey, k);
                }
            }
            var sr = cm.relocs().staticRefs();
            for (int i = 0; i < sr.size(); i++)
            {
                useDiag(ownerOf(sr.get(i).fieldKey()), used, clinitOrder, worklist, parent, parentKey, k);
            }
            useDiag(ownerOf(k), used, clinitOrder, worklist, parent, parentKey, k);
            // NB: unlike build(), we deliberately do NOT expand every vtable method of a tibRef'd class. The
            // writer must lay out complete vtables (so an uncalled virtual still gets code); metal RTA marks
            // only ACTUALLY-CALLED virtuals. Expanding here would falsely pull String.matches->Pattern and
            // String.toUpperCase->Locale for any method that merely touches a String. Call sites (above)
            // already carry the resolved callees, which is the reachability we want to measure.
        }
        return new ClosureReport(used, parent, parentKey, failures);
    }

    private void pushWl(Vec<String> worklist, StrIntTable parent, Vec<String> parentKey, String from, String to)
    {
        if (!parent.containsKey(to))
        {
            parent.put(to, parentKey.size());
            parentKey.add(from);
        }
        worklist.add(to);
    }

    private void useDiag(String cls, StrSet used, Vec<String> clinitOrder, Vec<String> worklist,
                         StrIntTable parent, Vec<String> parentKey, String from)
    {
        if (used.add(cls) && model.hasClinit(cls))
        {
            pushWl(worklist, parent, parentKey, from, cls + ".<clinit>()V");
            clinitOrder.add(cls + ".<clinit>()V");
        }
    }
}
