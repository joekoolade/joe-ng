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
    /** A class-table directory entry: {nameAddr, nameLen, bytesAddr, bytesLen} = 4 longs. */
    private static final int CLASS_ENTRY_WORDS = 4 * WORDS_PER_SLOT;
    /** Entry-called stub whose body the writer fills with <clinit> calls (eager init). */
    private static final String INIT_CLASSES = "vm/VM.initClasses()V";

    // M8 full bootstrap (path 1), static state: stock java.base methods force-rooted into the compile
    // closure, each compiled address stashed in a VM static so VM.bootstrapProbe can Magic.callN it.
    // The indirection exists because javac cannot name a package-private java.base member (StringUTF16
    // is package-private in java.lang) from the vm/ tree -- the writer links by key instead.
    private static final String[][] BAKE_ROOTS = {
        { "java/lang/StringUTF16.getBytes([BII[BI)V",     "vm/VM.utf16GetBytesAddr" },
        { "java/lang/Integer.formatUnsignedInt(II[BI)V",  "vm/VM.formatUnsignedIntAddr" },
        { "java/lang/Integer.intValue()I",                "vm/VM.integerIntValueAddr" },
    };

    // M8 static state: statics force-added to the referenced set so they get a slot and a deep
    // snapshot even though no compiled method names them yet, with the SLOT's address stashed in a
    // VM static so the probe can reach the baked value. (Stock Integer.valueOf would reference
    // IntegerCache.cache naturally, but its never-taken `new Integer` branch would pull every
    // Integer virtual -- toString and its String/Unsafe closure -- into the host compile; rooting
    // the static directly keeps the closure tiny until the writer can stub natives.)
    private static final String[][] BAKE_STATICS = {
        { "java/lang/Integer$IntegerCache.cache", "vm/VM.integerCacheSlotAddr" },
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
        StrSet usedClasses = new StrSet();
        Vec<String> clinitOrder = new Vec<>();               // <clinit>s to run, first-use order
        int frameCount = 0;                                          // unwind-table entry counts
        int handlerCount = 0;
        Vec<String> worklist = new Vec<>();
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
        while (!worklist.isEmpty())
        {
            String k = worklist.removeFirst();
            if (k.equals(INIT_CLASSES) || sizeWords.containsKey(k))
            {
                continue;    // init body is generated
            }
            CompiledMethod cm = compile(k, CodeBuffer.LOAD_ADDRESS, k.equals(entryKey));
            sizeWords.put(k, cm.words().length);
            lineTabIndex.put(k, lineTabList.size());
            lineTabList.add(buildLineTable(cm.bcToWord(), k));
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
            var _r3 = cm.relocs().typeRefs();
            for (int _ri3 = 0; _ri3 < _r3.size(); _ri3++)
            {
                var t = _r3.get(_ri3);
                typeRefClasses.add(t.className());
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
                if (tibClasses.add(t.className()))
                {
                    Vec<ClassModel.VSlot> vt = model.vtable(t.className());
                    for (int _vi = 0; _vi < vt.size(); _vi++)
                    {
                        ClassModel.VSlot s = vt.get(_vi);
                        worklist.add(BaselineCompiler.key(s.owner(), s.name(), s.descriptor()));
                    }
                }
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
        ByteKeyIntTable strWord = new ByteKeyIntTable();
        for (int _s5 = 0; _s5 < strings.size(); _s5++)
        {
            byte[] s = strings.at(_s5);
            strWord.put(s, cur);
            cur += stringWords(s);
        }
        // M8 static state, object statics: a bakeNoClinit class's reference-typed static is DEEP
        // snapshotted -- the seed JVM's whole reachable object graph (primitive arrays, reference
        // arrays, scalar objects with their fields) is baked into the image and the static slot
        // points at the root. A scalar gets its class's TIB when the image lays one out
        // (instantiated classes); otherwise a null TIB like interned strings -- fine until
        // something virtually dispatches on it. Scalar field layout comes from the SAME registered
        // classfile the compiler resolves getfield against, so offsets agree by construction.
        Vec<String> bakedKeys = new Vec<>();
        Vec<Object> bakedRoots = new Vec<>();
        Vec<Object> bakedObjs = new Vec<>();
        for (int _s14 = 0; _s14 < statics.size(); _s14++)
        {
            String key = statics.at(_s14);
            if (bakeNoClinit(ownerOf(key)))
            {
                Object v = StaticSnapshot.reference(key);
                if (v != null)
                {
                    bakedKeys.add(key);
                    bakedRoots.add(v);
                    bakeDiscover(v, bakedObjs);
                }
            }
        }
        int[] bakedObjWord = new int[bakedObjs.size()];
        for (int _s15 = 0; _s15 < bakedObjs.size(); _s15++)
        {
            bakedObjWord[_s15] = cur;
            cur += bakedWords(bakedObjs.get(_s15));
        }
        StrIntTable staticWord = new StrIntTable();          // one 8-byte slot per static field, zero-init
        int staticsRegionStart = cur;
        for (int _s6 = 0; _s6 < statics.size(); _s6++)
        {
            String s = statics.at(_s6);
            staticWord.put(s, cur);
            cur += WORDS_PER_SLOT;
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
        int totalWords = cur;

        // --- final compile at real bases; concatenate; gather fixups ---
        int[] image = new int[totalWords];
        Vec<GlobalCall> calls = new Vec<>();
        Vec<GlobalTib> tibs = new Vec<>();
        Vec<GlobalStr> strs = new Vec<>();
        Vec<GlobalStatic> stats = new Vec<>();
        Vec<GlobalType> types = new Vec<>();
        Vec<long[]> frameEntries = new Vec<>();       // {codeStart, codeEnd, frameSize}
        Vec<long[]> handlerEntries = new Vec<>();     // {machStart, machEnd, handler, catchType}
        for (int si = 0; si < sizeWords.size(); si++)
        {
            String k = sizeWords.keyAt(si);
            int base = wordOffset.get(k);
            CompiledMethod cm = k.equals(INIT_CLASSES) ? initBody
                                : compile(k, CodeBuffer.LOAD_ADDRESS + (long) base * 4, k.equals(entryKey));
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
                      ObjectModel.scalarSize(model.instanceFieldCount(cls)));
            String sup = model.superClassName(cls);
            long superAddr = model.isRoot(sup) ? 0 : addr(typeWord.get(sup));
            writeLong(image, tw + ObjectModel.TYPE_SUPER_OFFSET / 4, superAddr);
            long dir = itableDirWord.containsKey(cls) ? addr(itableDirWord.get(cls)) : 0;
            writeLong(image, tw + ObjectModel.TYPE_ITABLE_DIR_OFFSET / 4, dir);
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
        fillStatic(image, staticWord, "vm/VM.imageSymTable", addr(symTableWord));
        fillStatic(image, staticWord, "vm/VM.imageSymCount", symCount);
        fillStatic(image, staticWord, "vm/VM.frameTable",   addr(frameTableWord));
        fillStatic(image, staticWord, "vm/VM.frameCount",   frameEntries.size());
        fillStatic(image, staticWord, "vm/VM.handlerTable", addr(handlerTableWord));
        fillStatic(image, staticWord, "vm/VM.handlerCount", handlerEntries.size());
        fillStatic(image, staticWord, "vm/VM.staticsStart", addr(staticsRegionStart));
        fillStatic(image, staticWord, "vm/VM.staticsEnd",   addr(staticsRegionEnd));
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
        stashHelper(image, staticWord, wordOffset, "vm/VM.irqHandler()V",     "vm/VM.irqHandlerAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.schedule(J)J",      "vm/VM.scheduleAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.yieldPick(J)J",     "vm/VM.yieldPickAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskA()V",          "vm/VM.taskAAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskB()V",          "vm/VM.taskBAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskC()V",          "vm/VM.taskCAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VMScheduler.taskR()V",          "vm/VM.taskRAddr");
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
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.arraycopy(JIJII)V", "vm/VM.arraycopyAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newNpe()J",         "vm/VM.newNpeAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newAioobe()J",      "vm/VM.newAioobeAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newArith()J",       "vm/VM.newArithAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.newAse()J",          "vm/VM.newAseAddr");     // ArrayStoreException
        stashHelper(image, staticWord, wordOffset, "vm/VM.arrayStoreOk(JJ)I",  "vm/VM.arrayStoreOkAddr"); // aastore check
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
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.methodInfo(JJJ)I",   "vm/VM.methodInfoAddr");     // reflection M2: Method.methodInfo0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.constructorResolve(JJ)I", "vm/VM.constructorResolveAddr"); // M2: Constructor.ctorResolve0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.allocInstance(J)J",  "vm/VM.allocInstanceAddr");  // reflection M2: Constructor.allocInstance0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.superclassOf(J)J",   "vm/VM.superclassAddr"); // M4: Class.superclass0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.currentThreadObj()J","vm/VM.currentThreadAddr"); // M4: Thread.currentThread0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.arrayClone(J)J",    "vm/VM.arrayCloneAddr");  // [T.clone() intrinsic
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.newReflectArray(JJ)J", "vm/VM.newReflectArrayAddr"); // reflect/Array.newInstance0
        stashHelper(image, staticWord, wordOffset, "vm/VMNatives.componentTypeOf(J)J", "vm/VM.componentTypeAddr");   // Class.getComponentType0
        stashHelper(image, staticWord, wordOffset, "vm/VM.getClassOf(J)J",    "vm/VM.getClassAddr");
        for (int br = 0; br < BAKE_ROOTS.length; br++)
        {
            stashHelper(image, staticWord, wordOffset, BAKE_ROOTS[br][0], BAKE_ROOTS[br][1]);
        }
        for (int bs = 0; bs < BAKE_STATICS.length; bs++)
        {
            fillStatic(image, staticWord, BAKE_STATICS[bs][1], addr(staticWord.get(BAKE_STATICS[bs][0])));
        }
        // M8 static state: a bakeNoClinit class's <clinit> never runs (not at build time, not on the
        // metal), so any of its statics referenced by baked code would read 0. Snapshot the seed JVM's
        // initialized PRIMITIVE values into their slots instead.
        for (int si = 0; si < statics.size(); si++)
        {
            String key = statics.at(si);
            if (bakeNoClinit(ownerOf(key)))
            {
                Long bits = StaticSnapshot.primitiveBits(key);
                if (bits != null)
                {
                    fillStatic(image, staticWord, key, bits);
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
            writeStringObject(image, strWord.get(s), s);
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

    /** Add {@code cls} and all its superclasses (up to Object) to {@code set}. */
    private void addTypeClass(String cls, StrSet set)
    {
        while (!model.isRoot(cls) && set.add(cls))
        {
            cls = model.superClassName(cls);
        }
    }

    private int vtableLength(String cls)
    {
        return model.vtable(cls).size();
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
                || cls.equals("java/lang/Integer$IntegerCache");
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
    private static void writeStringObject(int[] image, int w, byte[] b)
    {
        writeLong(image, w + ObjectModel.TIB_OFFSET / 4, 0);                // null TIB (as arrays)
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
            ClassFile cf = registry.resolve(bakedClassName(o));
            for (ClassFile.FieldInfo f : cf.fields())
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
        return ObjectModel.scalarSize(registry.resolve(bakedClassName(o)).instanceFieldCount()) / 4;
    }

    /** Write baked object {@code o} at image word {@code w}; references resolve through the graph. */
    private void writeBakedObject(int[] image, int w, Object o, Vec<Object> objs, int[] objWord,
                                  StrIntTable tibWord)
    {
        if (o.getClass().isArray())
        {
            if (o.getClass().getComponentType().isPrimitive())
            {
                writeArrayObject(image, w, o);
                return;
            }
            Object[] a = (Object[]) o;
            writeLong(image, w + ObjectModel.TIB_OFFSET / 4, 0);          // null TIB (as arrays)
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
        ClassFile cf = registry.resolve(cls);
        int slot = 0;
        for (ClassFile.FieldInfo f : cf.fields())
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
    private static void writeArrayObject(int[] image, int w, Object v)
    {
        int scale = arrayScale(v);
        int n = java.lang.reflect.Array.getLength(v);
        writeLong(image, w + ObjectModel.TIB_OFFSET / 4, 0);
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
                useDiag(tr.get(i).className(), used, clinitOrder, worklist, parent, parentKey, k);
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
