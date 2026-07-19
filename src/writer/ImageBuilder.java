package writer;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import compiler.BaselineCompiler;
import compiler.BaselineCompiler.CompiledMethod;
import objectmodel.ObjectModel;

import java.io.IOException;
import java.io.UncheckedIOException;
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
public final class ImageBuilder implements BaselineCompiler.ClassResolver {

    private static final int WORDS_PER_SLOT = ObjectModel.WORD / 4;   // 8-byte slot = 2 image ints
    private static final int TYPE_WORDS = WORDS_PER_SLOT;             // Type = { instanceSize } for now

    private final Path classesDir;
    private final byte[] imageData;
    private final Map<String, ClassFile> classes = new HashMap<>();

    public ImageBuilder(Path classesDir, byte[] imageData) {
        this.classesDir = classesDir;
        this.imageData = imageData;
    }

    @Override public ClassFile resolve(String owner) {
        return classes.computeIfAbsent(owner, o -> {
            try { return ClassFile.parse(classesDir.resolve(o + ".class")); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    private record Resolved(ClassFile cf, ClassFile.Method method) {}
    private record GlobalCall(int siteWord, String calleeKey) {}
    private record GlobalTib(int siteWord, int reg, String className) {}

    /** Build the whole image with {@code entryKey} (e.g. "vm/VM.boot()V") at 0x80000. */
    public CodeBuffer build(String entryKey) throws IOException {
        // --- discovery + sizing: BFS over calls; collect instantiated classes.
        //     Instantiating a class pulls in all its virtual methods so their code
        //     is laid out and the vtable can point at it (even if not directly called).
        Map<String, Integer> sizeWords = new LinkedHashMap<>();     // layout order, entry first
        Set<String> tibClasses = new LinkedHashSet<>();
        List<String> worklist = new ArrayList<>(List.of(entryKey));
        while (!worklist.isEmpty()) {
            String k = worklist.remove(0);
            if (sizeWords.containsKey(k)) continue;
            CompiledMethod cm = compile(k, CodeBuffer.LOAD_ADDRESS, k.equals(entryKey));
            sizeWords.put(k, cm.words().length);
            cm.callSites().forEach(cs -> worklist.add(cs.calleeKey()));
            for (var t : cm.tibRefs()) {
                if (tibClasses.add(t.className()))
                    for (ClassFile.Method vm : resolve(t.className()).virtualMethods())
                        worklist.add(BaselineCompiler.key(t.className(), vm.name, vm.descriptor));
            }
        }

        // --- lay out: [method code] [Types] [TIBs], each 8-byte aligned ---
        Map<String, Integer> wordOffset = new HashMap<>();
        int cur = 0;
        for (var e : sizeWords.entrySet()) { wordOffset.put(e.getKey(), cur); cur += e.getValue(); }
        cur += cur % 2;                                             // pad to 8 bytes before data
        Map<String, Integer> typeWord = new HashMap<>();
        for (String cls : tibClasses) { typeWord.put(cls, cur); cur += TYPE_WORDS; }
        Map<String, Integer> tibWord = new HashMap<>();
        for (String cls : tibClasses) { tibWord.put(cls, cur); cur += ObjectModel.tibSize(vtableLength(cls)) / 4; }
        int totalWords = cur;

        // --- final compile at real bases; concatenate; gather fixups ---
        int[] image = new int[totalWords];
        List<GlobalCall> calls = new ArrayList<>();
        List<GlobalTib> tibs = new ArrayList<>();
        for (String k : sizeWords.keySet()) {
            int base = wordOffset.get(k);
            CompiledMethod cm = compile(k, CodeBuffer.LOAD_ADDRESS + (long) base * 4, k.equals(entryKey));
            if (cm.words().length != sizeWords.get(k)) throw new IllegalStateException("size drift for " + k);
            System.arraycopy(cm.words(), 0, image, base, cm.words().length);
            cm.callSites().forEach(cs -> calls.add(new GlobalCall(base + cs.wordIndex(), cs.calleeKey())));
            cm.tibRefs().forEach(t -> tibs.add(new GlobalTib(base + t.wordIndex(), t.reg(), t.className())));
        }

        // --- Types (instanceSize) and TIBs (Type ptr + vtable code addresses) ---
        for (String cls : tibClasses) {
            ClassFile cf = resolve(cls);
            writeLong(image, typeWord.get(cls), ObjectModel.scalarSize(cf.instanceFieldCount()));
            int tw = tibWord.get(cls);
            writeLong(image, tw + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT) / 4, addr(typeWord.get(cls)));
            var vmethods = cf.virtualMethods();
            for (int slot = 0; slot < vmethods.size(); slot++) {
                ClassFile.Method vm = vmethods.get(slot);
                int mbase = wordOffset.get(BaselineCompiler.key(cls, vm.name, vm.descriptor));
                writeLong(image, tw + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)) / 4, addr(mbase));
            }
        }

        CodeBuffer cb = new CodeBuffer();                           // base = LOAD_ADDRESS
        for (int w : image) cb.emit(w);
        for (GlobalCall c : calls) {
            Integer calleeBase = wordOffset.get(c.calleeKey());
            if (calleeBase == null) throw new IllegalStateException("unresolved call to " + c.calleeKey());
            cb.set(c.siteWord(), A64.bl((calleeBase - c.siteWord()) * 4));
        }
        for (GlobalTib t : tibs) {
            Integer w = tibWord.get(t.className());
            if (w == null) throw new IllegalStateException("no TIB for " + t.className());
            cb.patchAddr(t.siteWord(), t.reg(), addr(w));          // store absolute TIB address
        }
        return cb;
    }

    private int vtableLength(String cls) { return resolve(cls).virtualMethods().size(); }

    /** Absolute address of image word index {@code w}. */
    private static long addr(int w) { return CodeBuffer.LOAD_ADDRESS + (long) w * 4; }

    /** Write a 64-bit value as two little-endian image ints at word index {@code w}. */
    private static void writeLong(int[] image, int w, long v) {
        image[w]     = (int) (v & 0xFFFFFFFFL);
        image[w + 1] = (int) (v >>> 32);
    }

    private CompiledMethod compile(String key, long base, boolean isEntry) throws IOException {
        Resolved r = lookup(key);
        return new BaselineCompiler(r.cf, imageData, this).compileMethod(r.method, base, isEntry);
    }

    private Resolved lookup(String key) throws IOException {
        int dot = key.lastIndexOf('.', key.indexOf('('));
        String owner = key.substring(0, dot);
        String name = key.substring(dot + 1, key.indexOf('('));
        String desc = key.substring(key.indexOf('('));
        ClassFile cf = resolve(owner);
        return new Resolved(cf, cf.method(name, desc));
    }
}
