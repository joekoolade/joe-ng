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

    private static final int TIB_WORDS = ObjectModel.tibSize(0) / 4;  // Type slot only, for now

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
        // --- discovery + sizing: BFS over calls; collect instantiated classes ---
        Map<String, Integer> sizeWords = new LinkedHashMap<>();     // layout order, entry first
        Set<String> tibClasses = new LinkedHashSet<>();
        List<String> worklist = new ArrayList<>(List.of(entryKey));
        while (!worklist.isEmpty()) {
            String k = worklist.remove(0);
            if (sizeWords.containsKey(k)) continue;
            CompiledMethod cm = compile(k, CodeBuffer.LOAD_ADDRESS, k.equals(entryKey));
            sizeWords.put(k, cm.words().length);
            cm.callSites().forEach(cs -> worklist.add(cs.calleeKey()));
            cm.tibRefs().forEach(t -> tibClasses.add(t.className()));
        }

        // --- assign method bases, then TIB region (8-byte aligned) ---
        Map<String, Integer> wordOffset = new HashMap<>();
        int off = 0;
        for (var e : sizeWords.entrySet()) { wordOffset.put(e.getKey(), off); off += e.getValue(); }
        int tibRegion = off + (off % 2);                            // pad to an 8-byte boundary
        Map<String, Integer> tibWord = new HashMap<>();
        for (String cls : tibClasses) { tibWord.put(cls, tibRegion); tibRegion += TIB_WORDS; }
        int totalWords = tibRegion;

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

        CodeBuffer cb = new CodeBuffer();                           // base = LOAD_ADDRESS
        for (int w : image) cb.emit(w);                            // TIB slots are zero (Type placeholder)

        for (GlobalCall c : calls) {
            Integer calleeBase = wordOffset.get(c.calleeKey());
            if (calleeBase == null) throw new IllegalStateException("unresolved call to " + c.calleeKey());
            cb.set(c.siteWord(), A64.bl((calleeBase - c.siteWord()) * 4));
        }
        for (GlobalTib t : tibs) {
            Integer w = tibWord.get(t.className());
            if (w == null) throw new IllegalStateException("no TIB for " + t.className());
            cb.patchAddr(t.siteWord(), t.reg(), cb.pcAt(w));        // store absolute TIB address
        }
        return cb;
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
