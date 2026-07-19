package writer;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import compiler.BaselineCompiler;
import compiler.BaselineCompiler.CompiledMethod;
import objectmodel.ObjectModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final List<Blob> blobs = new ArrayList<>();

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
        Map<String, Integer> sizeWords = new LinkedHashMap<>();     // layout order, entry first
        Set<String> tibClasses = new LinkedHashSet<>();
        Set<String> strings = new LinkedHashSet<>();
        Set<String> statics = new LinkedHashSet<>();
        Set<String> typeRefClasses = new LinkedHashSet<>();          // instanceof/checkcast/interface targets
        Set<String> usedInterfaces = new LinkedHashSet<>();          // invokeinterface targets (itable build)
        Set<String> usedClasses = new LinkedHashSet<>();
        List<String> clinitOrder = new ArrayList<>();               // <clinit>s to run, first-use order
        int frameCount = 0, handlerCount = 0;                        // unwind-table entry counts
        List<String> worklist = new ArrayList<>(List.of(entryKey));
        while (!worklist.isEmpty())
        {
            String k = worklist.remove(0);
            if (k.equals(INIT_CLASSES) || sizeWords.containsKey(k))
            {
                continue;    // init body is generated
            }
            CompiledMethod cm = compile(k, CodeBuffer.LOAD_ADDRESS, k.equals(entryKey));
            sizeWords.put(k, cm.words().length);
            cm.callSites().forEach(cs -> worklist.add(cs.calleeKey()));
            cm.strRefs().forEach(s -> strings.add(s.text()));
            cm.typeRefs().forEach(t -> typeRefClasses.add(t.className()));
            cm.interfaceRefs().forEach(t ->
            {
                typeRefClasses.add(t.className());
                usedInterfaces.add(t.className());
            });
            cm.handlers().forEach(h ->
            {
                if (h.catchClass() != null)
                {
                    typeRefClasses.add(h.catchClass());
                }
            });
            if (cm.frameSize() > 0)
            {
                frameCount++;
            }
            handlerCount += cm.handlers().size();
            cm.tibRefs().forEach(t -> use(t.className(), usedClasses, clinitOrder, worklist));
            cm.staticRefs().forEach(s ->
            {
                statics.add(s.fieldKey());
                use(ownerOf(s.fieldKey()), usedClasses, clinitOrder, worklist);
            });
            use(ownerOf(k), usedClasses, clinitOrder, worklist);
            for (var t : cm.tibRefs())
            {
                if (tibClasses.add(t.className()))
                    for (ClassFile.VSlot s : ClassFile.vtable(t.className(), this::resolve))
                    {
                        worklist.add(BaselineCompiler.key(s.owner(), s.name(), s.descriptor()));
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
        Map<String, Integer> wordOffset = new HashMap<>();
        int cur = 0;
        for (var e : sizeWords.entrySet())
        {
            wordOffset.put(e.getKey(), cur);
            cur += e.getValue();
        }
        cur += cur % 2;                                             // pad to 8 bytes before data
        // Types are needed by every instantiated class (TIB[0]), every type-check
        // target, and every superclass in those chains (the instanceof walk).
        Set<String> typeClasses = new LinkedHashSet<>();
        for (String c : tibClasses)
        {
            addTypeClass(c, typeClasses);
        }
        for (String c : typeRefClasses)
        {
            addTypeClass(c, typeClasses);
        }
        Map<String, Integer> typeWord = new HashMap<>();
        for (String cls : typeClasses)
        {
            typeWord.put(cls, cur);
            cur += TYPE_WORDS;
        }
        Map<String, Integer> tibWord = new HashMap<>();
        for (String cls : tibClasses)
        {
            tibWord.put(cls, cur);
            cur += ObjectModel.tibSize(vtableLength(cls)) / 4;
        }
        Map<String, Integer> strWord = new HashMap<>();
        for (String s : strings)
        {
            strWord.put(s, cur);
            cur += stringWords(s);
        }
        Map<String, Integer> staticWord = new HashMap<>();          // one 8-byte slot per static field, zero-init
        int staticsRegionStart = cur;
        for (String s : statics)
        {
            staticWord.put(s, cur);
            cur += WORDS_PER_SLOT;
        }
        int staticsRegionEnd = cur;
        // itables: per instantiated class, a directory of {interfaceType, itable} plus the itables.
        Map<String, Integer> itableDirWord = new HashMap<>();       // class -> directory
        Map<String, Integer> itableWord = new HashMap<>();          // "class|iface" -> itable
        for (String c : tibClasses)
        {
            List<String> impls = implementedUsedInterfaces(c, usedInterfaces);
            if (impls.isEmpty())
            {
                continue;
            }
            itableDirWord.put(c, cur);
            cur += impls.size() * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
            for (String i : impls)
            {
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
        List<GlobalCall> calls = new ArrayList<>();
        List<GlobalTib> tibs = new ArrayList<>();
        List<GlobalStr> strs = new ArrayList<>();
        List<GlobalStatic> stats = new ArrayList<>();
        List<GlobalType> types = new ArrayList<>();
        List<long[]> frameEntries = new ArrayList<>();       // {codeStart, codeEnd, frameSize}
        List<long[]> handlerEntries = new ArrayList<>();     // {machStart, machEnd, handler, catchType}
        for (String k : sizeWords.keySet())
        {
            int base = wordOffset.get(k);
            CompiledMethod cm = k.equals(INIT_CLASSES) ? initBody
                                : compile(k, CodeBuffer.LOAD_ADDRESS + (long) base * 4, k.equals(entryKey));
            if (cm.words().length != sizeWords.get(k))
            {
                throw new IllegalStateException("size drift for " + k);
            }
            System.arraycopy(cm.words(), 0, image, base, cm.words().length);
            cm.callSites().forEach(cs -> calls.add(new GlobalCall(base + cs.wordIndex(), cs.calleeKey())));
            cm.tibRefs().forEach(t -> tibs.add(new GlobalTib(base + t.wordIndex(), t.reg(), t.className())));
            cm.strRefs().forEach(s -> strs.add(new GlobalStr(base + s.wordIndex(), s.reg(), s.text())));
            cm.staticRefs().forEach(s -> stats.add(new GlobalStatic(base + s.wordIndex(), s.reg(), s.fieldKey())));
            cm.typeRefs().forEach(t -> types.add(new GlobalType(base + t.wordIndex(), t.reg(), t.className())));
            cm.interfaceRefs().forEach(t -> types.add(new GlobalType(base + t.wordIndex(), t.reg(), t.className())));
            if (cm.frameSize() > 0)
                frameEntries.add(new long[] {addr(base), addr(base + cm.words().length), cm.frameSize()});
            for (var hr : cm.handlers())
            {
                long ct = hr.catchClass() == null ? 0 : addr(typeWord.get(hr.catchClass()));
                handlerEntries.add(new long[] {addr(base + hr.startWord()), addr(base + hr.endWord()),
                                               addr(base + hr.handlerWord()), ct
                                              });
            }
        }

        // --- Types: { instanceSize, superType, itableDir } ---
        for (String cls : typeClasses)
        {
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
        for (String c : tibClasses)
        {
            List<String> impls = implementedUsedInterfaces(c, usedInterfaces);
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
            for (String i : impls)
            {
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
        for (String cls : tibClasses)
        {
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
        for (int b = 0; b < blobs.size(); b++)
        {
            Blob blob = blobs.get(b);
            writeBytes(image, blobWord[b], blob.bytes());
            fillStatic(image, staticWord, blob.addrKey(), addr(blobWord[b]));
            fillStatic(image, staticWord, blob.lenKey(),  blob.bytes().length);
        }

        // --- interned string literals as byte[] objects ([null TIB][status][length][bytes]) ---
        for (String s : strings)
        {
            writeStringObject(image, strWord.get(s), s);
        }

        CodeBuffer cb = new CodeBuffer();                           // base = LOAD_ADDRESS
        for (int w : image)
        {
            cb.emit(w);
        }
        for (GlobalCall c : calls)
        {
            Integer calleeBase = wordOffset.get(c.calleeKey());
            if (calleeBase == null)
            {
                throw new IllegalStateException("unresolved call to " + c.calleeKey());
            }
            cb.set(c.siteWord(), A64.bl((calleeBase - c.siteWord()) * 4));
        }
        for (GlobalTib t : tibs)
        {
            Integer w = tibWord.get(t.className());
            if (w == null)
            {
                throw new IllegalStateException("no TIB for " + t.className());
            }
            cb.patchAddr(t.siteWord(), t.reg(), addr(w));          // store absolute TIB address
        }
        for (GlobalStr s : strs)
        {
            cb.patchAddr(s.siteWord(), s.reg(), addr(strWord.get(s.text())));   // store byte[] address
        }
        for (GlobalStatic s : stats)
        {
            cb.patchAddr(s.siteWord(), s.reg(), addr(staticWord.get(s.fieldKey()))); // static field address
        }
        for (GlobalType t : types)
        {
            cb.patchAddr(t.siteWord(), t.reg(), addr(typeWord.get(t.className())));  // class Type address
        }
        return cb;
    }

    /** Add {@code cls} and all its superclasses (up to Object) to {@code set}. */
    private void addTypeClass(String cls, Set<String> set)
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
    private List<String> implementedUsedInterfaces(String cls, Set<String> usedInterfaces)
    {
        Set<String> all = ClassFile.allInterfaces(cls, this::resolve);
        List<String> out = new ArrayList<>();
        for (String i : usedInterfaces) if (all.contains(i))
            {
                out.add(i);
            }
        return out;
    }

    /** Mark {@code cls} used; on first use, schedule its {@code <clinit>} (eager init). */
    private void use(String cls, Set<String> used, List<String> clinitOrder, List<String> worklist)
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
    private CompiledMethod generateInitClasses(List<String> clinits)
    {
        int frame = A64.align16(8);                                 // LR only
        List<Integer> w = new ArrayList<>();
        List<BaselineCompiler.CallSite> calls = new ArrayList<>();
        w.add(A64.subImm(31, 31, frame));
        w.add(A64.strx(30, 31, 0));
        for (String c : clinits)
        {
            calls.add(new BaselineCompiler.CallSite(w.size(), c));
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
        return new CompiledMethod(words, calls, List.of(), List.of(), List.of(), List.of(), List.of(),
                                  frame, List.of());
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
            int word = base + i / 4, shift = (i % 4) * 8;
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

    /** Fill a (writer-initialized) static field slot with {@code value}, if the field is used. */
    private static void fillStatic(int[] image, Map<String, Integer> staticWord, String key, long value)
    {
        Integer w = staticWord.get(key);
        if (w != null)
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
        return new BaselineCompiler(r.cf, this).compileMethod(r.method, base, isEntry);
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
