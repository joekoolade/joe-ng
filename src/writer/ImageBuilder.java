package writer;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import compiler.BaselineCompiler;
import compiler.BaselineCompiler.CompiledMethod;
import objectmodel.ObjectModel;
import util.StrIntTable;
import util.StrSet;
import util.Vec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
    /** Entry-called stub whose body the writer fills with <clinit> calls (eager init). */
    private static final String INIT_CLASSES = "vm/VM.initClasses()V";

    private final Path classesDir;
    private final Map<String, ClassFile> classes = new HashMap<>();
    private final Vec<Blob> blobs = new Vec<>();

    /** Raw bytes embedded verbatim; the writer fills {@code addrKey}/{@code lenKey} statics. */
    private record Blob(String addrKey, String lenKey, byte[] bytes) {}

    public ImageBuilder(Path classesDir)
    {
        this.classesDir = classesDir;
    }

    /** Embed {@code bytes} verbatim; the runtime finds them via the given statics (e.g. a raw .class). */
    public void addBlob(String addrKey, String lenKey, byte[] bytes)
    {
        blobs.add(new Blob(addrKey, lenKey, bytes));
    }

    @Override public ClassFile resolve(String owner)
    {
        return classes.computeIfAbsent(owner, o ->
        {
            try
            {
                return ClassFile.parse(classesDir.resolve(o + ".class"));
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        });
    }

    private record Resolved(ClassFile cf, ClassFile.Method method) {}
    private record GlobalCall(int siteWord, String calleeKey) {}
    private record GlobalTib(int siteWord, int reg, String className) {}
    private record GlobalStr(int siteWord, int reg, String text) {}
    private record GlobalStatic(int siteWord, int reg, String fieldKey) {}
    private record GlobalType(int siteWord, int reg, String className) {}

    /** Build the whole image with {@code entryKey} (e.g. "vm/VM.boot()V") at 0x80000. */
    public CodeBuffer build(String entryKey) throws IOException
    {
        // --- discovery + sizing: BFS over calls; collect instantiated classes.
        //     Instantiating a class pulls in all its virtual methods so their code
        //     is laid out and the vtable can point at it (even if not directly called).
        StrIntTable sizeWords = new StrIntTable();                  // layout order, entry first
        StrSet tibClasses = new StrSet();
        StrSet strings = new StrSet();
        StrSet statics = new StrSet();
        StrSet typeRefClasses = new StrSet();          // instanceof/checkcast/interface targets
        StrSet usedInterfaces = new StrSet();          // invokeinterface targets (itable build)
        StrSet usedClasses = new StrSet();
        Vec<String> clinitOrder = new Vec<>();               // <clinit>s to run, first-use order
        int frameCount = 0;                                          // unwind-table entry counts
        int handlerCount = 0;
        Vec<String> worklist = new Vec<>();
        worklist.add(entryKey);
        while (!worklist.isEmpty())
        {
            String k = worklist.removeFirst();
            if (k.equals(INIT_CLASSES) || sizeWords.containsKey(k))
            {
                continue;    // init body is generated
            }
            CompiledMethod cm = compile(k, CodeBuffer.LOAD_ADDRESS, k.equals(entryKey));
            sizeWords.put(k, cm.words().length);
            var _r1 = cm.callSites();
            for (int _ri1 = 0; _ri1 < _r1.size(); _ri1++)
            {
                var cs = _r1.get(_ri1);
                worklist.add(cs.calleeKey());
            }
            var _r2 = cm.strRefs();
            for (int _ri2 = 0; _ri2 < _r2.size(); _ri2++)
            {
                var s = _r2.get(_ri2);
                strings.add(s.text());
            }
            var _r3 = cm.typeRefs();
            for (int _ri3 = 0; _ri3 < _r3.size(); _ri3++)
            {
                var t = _r3.get(_ri3);
                typeRefClasses.add(t.className());
            }
            var _r5 = cm.interfaceRefs();
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
            var _r4 = cm.tibRefs();
            for (int _ri4 = 0; _ri4 < _r4.size(); _ri4++)
            {
                var t = _r4.get(_ri4);
                use(t.className(), usedClasses, clinitOrder, worklist);
            }
            var _r7 = cm.staticRefs();
            for (int _ri7 = 0; _ri7 < _r7.size(); _ri7++)
            {
                var s = _r7.get(_ri7);
                statics.add(s.fieldKey());
                use(ownerOf(s.fieldKey()), usedClasses, clinitOrder, worklist);
            }
            use(ownerOf(k), usedClasses, clinitOrder, worklist);
            var _r8 = cm.tibRefs();
            for (int _ri8 = 0; _ri8 < _r8.size(); _ri8++)
            {
                var t = _r8.get(_ri8);
                if (tibClasses.add(t.className()))
                {
                    Vec<ClassFile.VSlot> vt = ClassFile.vtable(t.className(), this::resolve);
                    for (int _vi = 0; _vi < vt.size(); _vi++)
                    {
                        ClassFile.VSlot s = vt.get(_vi);
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
        StrIntTable strWord = new StrIntTable();
        for (int _s5 = 0; _s5 < strings.size(); _s5++)
        {
            String s = strings.at(_s5);
            strWord.put(s, cur);
            cur += stringWords(s);
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
                cur += resolve(i).interfaceMethods().size() * WORDS_PER_SLOT;
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
            var _r5 = cm.callSites();
            for (int _ri5 = 0; _ri5 < _r5.size(); _ri5++)
            {
                var cs = _r5.get(_ri5);
                calls.add(new GlobalCall(base + cs.wordIndex(), cs.calleeKey()));
            }
            var _r6 = cm.tibRefs();
            for (int _ri6 = 0; _ri6 < _r6.size(); _ri6++)
            {
                var t = _r6.get(_ri6);
                tibs.add(new GlobalTib(base + t.wordIndex(), t.reg(), t.className()));
            }
            var _r7 = cm.strRefs();
            for (int _ri7 = 0; _ri7 < _r7.size(); _ri7++)
            {
                var s = _r7.get(_ri7);
                strs.add(new GlobalStr(base + s.wordIndex(), s.reg(), s.text()));
            }
            var _r8 = cm.staticRefs();
            for (int _ri8 = 0; _ri8 < _r8.size(); _ri8++)
            {
                var s = _r8.get(_ri8);
                stats.add(new GlobalStatic(base + s.wordIndex(), s.reg(), s.fieldKey()));
            }
            var _r9 = cm.typeRefs();
            for (int _ri9 = 0; _ri9 < _r9.size(); _ri9++)
            {
                var t = _r9.get(_ri9);
                types.add(new GlobalType(base + t.wordIndex(), t.reg(), t.className()));
            }
            var _r10 = cm.interfaceRefs();
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
                      ObjectModel.scalarSize(resolve(cls).instanceFieldCount()));
            String sup = resolve(cls).superClassName();
            long superAddr = ClassFile.isRoot(sup) ? 0 : addr(typeWord.get(sup));
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
                var ims = resolve(i).interfaceMethods();
                for (int s = 0; s < ims.size(); s++)
                {
                    ClassFile.Method m = ims.get(s);
                    String impl = ClassFile.findImpl(c, m.name, m.descriptor, this::resolve);
                    int mbase = wordOffset.get(BaselineCompiler.key(impl, m.name, m.descriptor));
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
            var slots = ClassFile.vtable(cls, this::resolve);
            for (int slot = 0; slot < slots.size(); slot++)
            {
                ClassFile.VSlot s = slots.get(slot);
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
        stashHelper(image, staticWord, wordOffset, "vm/VM.gcCollect(J)V",     "vm/VM.gcCollect");
        stashHelper(image, staticWord, wordOffset, "vm/VM.instanceOf(JJ)I",   "vm/VM.instanceOfAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.checkCast(JJ)J",    "vm/VM.checkCastAddr");
        stashHelper(image, staticWord, wordOffset, "vm/VM.unwind(JJJ)V",      "vm/VM.unwindAddr");
        for (int b = 0; b < blobs.size(); b++)
        {
            Blob blob = blobs.get(b);
            writeBytes(image, blobWord[b], blob.bytes());
            fillStatic(image, staticWord, blob.addrKey(), addr(blobWord[b]));
            fillStatic(image, staticWord, blob.lenKey(),  blob.bytes().length);
        }

        // --- interned string literals as byte[] objects ([null TIB][status][length][bytes]) ---
        for (int _s11 = 0; _s11 < strings.size(); _s11++)
        {
            String s = strings.at(_s11);
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
        while (!ClassFile.isRoot(cls) && set.add(cls))
        {
            cls = resolve(cls).superClassName();
        }
    }

    private int vtableLength(String cls)
    {
        return ClassFile.vtable(cls, this::resolve).size();
    }

    /** The invokeinterface-target interfaces that {@code cls} implements, in use order. */
    private Vec<String> implementedUsedInterfaces(String cls, StrSet usedInterfaces)
    {
        StrSet all = ClassFile.allInterfaces(cls, this::resolve);
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

    /** Mark {@code cls} used; on first use, schedule its {@code <clinit>} (eager init). */
    private void use(String cls, StrSet used, Vec<String> clinitOrder, Vec<String> worklist)
    {
        if (used.add(cls) && resolve(cls).hasClinit())
        {
            String ck = cls + ".<clinit>()V";
            clinitOrder.add(ck);
            worklist.add(ck);
        }
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
        Vec<Integer> w = new Vec<>();
        Vec<BaselineCompiler.CallSite> calls = new Vec<>();
        w.add(A64.subImm(31, 31, frame));
        w.add(A64.strx(30, 31, 0));
        for (int ci = 0; ci < clinits.size(); ci++)
        {
            calls.add(new BaselineCompiler.CallSite(w.size(), clinits.get(ci)));
            w.add(A64.bl(0));
        }
        w.add(A64.ldrx(30, 31, 0));
        w.add(A64.addImm(31, 31, frame));
        w.add(A64.ret());
        int[] words = new int[w.size()];
        for (int i = 0; i < words.length; i++)
        {
            words[i] = w.get(i);
        }
        return new CompiledMethod(words, calls, new Vec<>(), new Vec<>(), new Vec<>(), new Vec<>(),
                                  new Vec<>(), frame, new Vec<>());
    }

    /** Image words a byte[] object for {@code s} occupies: header(16)+length(8)+bytes, 8-aligned. */
    private static int stringWords(String s)
    {
        int n = s.getBytes(StandardCharsets.US_ASCII).length;
        return (ObjectModel.ARRAY_BASE_OFFSET + ((n + 7) & ~7)) / 4;
    }

    /** Write a byte[] object holding {@code s}'s ASCII bytes at image word {@code w}. */
    private static void writeStringObject(int[] image, int w, String s)
    {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
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

    private CompiledMethod compile(String key, long base, boolean isEntry) throws IOException
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

    private Resolved lookup(String key) throws IOException
    {
        int dot = key.lastIndexOf('.', key.indexOf('('));
        String owner = key.substring(0, dot);
        String name = key.substring(dot + 1, key.indexOf('('));
        String desc = key.substring(key.indexOf('('));
        ClassFile cf = resolve(owner);
        return new Resolved(cf, cf.method(name, desc));
    }
}
