package writer;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import compiler.BaselineCompiler;
import compiler.BaselineCompiler.CompiledMethod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out a graph of methods across classes into one image and relocates the
 * calls between them — the core M2 writer job (PLAN.md §4). Starting from an
 * entry method it acts as a tiny class loader: load the owning {@code .class},
 * compile the method, discover its callees, and repeat. Then it assigns each
 * method an address (entry at {@code 0x80000}), concatenates the code, and
 * patches every {@code BL} to its callee's absolute entry.
 *
 * <p>Because method sizes are layout-independent (internal address loads are
 * fixed-width {@code MOVZ/MOVK}, branches are relative), it compiles once to
 * discover + size, then recompiles each at its final base so intra-method
 * address patches are correct — leaving only inter-method {@code BL}s to fix up.
 */
public final class ImageBuilder {

    private final Path classesDir;
    private final byte[] imageData;
    private final Map<String, ClassFile> classes = new HashMap<>();

    public ImageBuilder(Path classesDir, byte[] imageData) {
        this.classesDir = classesDir;
        this.imageData = imageData;
    }

    private record Resolved(ClassFile cf, ClassFile.Method method) {}

    /** Build the whole image with {@code entryKey} (e.g. "vm/VM.boot()V") at 0x80000. */
    public CodeBuffer build(String entryKey) throws IOException {
        // --- discovery + sizing: BFS over the call graph from the entry ---
        Map<String, Integer> sizeWords = new LinkedHashMap<>();  // preserves layout order, entry first
        List<String> worklist = new ArrayList<>(List.of(entryKey));
        while (!worklist.isEmpty()) {
            String key = worklist.remove(0);
            if (sizeWords.containsKey(key)) continue;
            Resolved r = resolve(key);
            CompiledMethod cm = new BaselineCompiler(r.cf, imageData)
                    .compileMethod(r.method, CodeBuffer.LOAD_ADDRESS, key.equals(entryKey));
            sizeWords.put(key, cm.words().length);
            for (BaselineCompiler.CallSite cs : cm.callSites()) worklist.add(cs.calleeKey());
        }

        // --- assign each method a base address in layout order ---
        Map<String, Integer> wordOffset = new HashMap<>();
        int off = 0;
        for (Map.Entry<String, Integer> e : sizeWords.entrySet()) {
            wordOffset.put(e.getKey(), off);
            off += e.getValue();
        }
        int totalWords = off;

        // --- final compile at real bases; concatenate; record call sites ---
        int[] image = new int[totalWords];
        List<GlobalCall> calls = new ArrayList<>();
        for (String key : sizeWords.keySet()) {
            int base = wordOffset.get(key);
            Resolved r = resolve(key);
            CompiledMethod cm = new BaselineCompiler(r.cf, imageData)
                    .compileMethod(r.method, CodeBuffer.LOAD_ADDRESS + (long) base * 4, key.equals(entryKey));
            int[] w = cm.words();
            if (w.length != sizeWords.get(key)) throw new IllegalStateException("size drift for " + key);
            System.arraycopy(w, 0, image, base, w.length);
            for (BaselineCompiler.CallSite cs : cm.callSites())
                calls.add(new GlobalCall(base + cs.wordIndex(), cs.calleeKey()));
        }

        // --- relocate BLs to callee entry addresses ---
        for (GlobalCall c : calls) {
            Integer calleeBase = wordOffset.get(c.calleeKey());
            if (calleeBase == null) throw new IllegalStateException("unresolved call to " + c.calleeKey());
            image[c.siteWord()] = A64.bl((calleeBase - c.siteWord()) * 4);
        }

        CodeBuffer cb = new CodeBuffer();     // base = LOAD_ADDRESS
        for (int w : image) cb.emit(w);
        return cb;
    }

    private record GlobalCall(int siteWord, String calleeKey) {}

    private Resolved resolve(String key) throws IOException {
        int dot = key.lastIndexOf('.', key.indexOf('('));
        String owner = key.substring(0, dot);
        String name = key.substring(dot + 1, key.indexOf('('));
        String desc = key.substring(key.indexOf('('));
        ClassFile cf = classes.get(owner);
        if (cf == null) {
            cf = ClassFile.parse(classesDir.resolve(owner + ".class"));
            classes.put(owner, cf);
        }
        return new Resolved(cf, cf.method(name, desc));
    }
}
