package compiler;

import asm.CodeBuffer;
import classfile.ClassFile;

import java.util.ArrayList;
import java.util.List;

/**
 * The writer-side driver over the shared {@link Baseline} core: it parses methods
 * with the JDK {@link ClassFile}, drives {@link WriterSymbols} (which records the
 * relocation sites), and packages each result as a {@link CompiledMethod} for
 * {@link writer.ImageBuilder}. All ClassFile-bound resolution lives here or in
 * WriterSymbols; the code generation lives in the core.
 */
public final class BaselineCompiler
{
    /** Resolves an owner class name (e.g. "vm/Cell") to its parsed classfile. */
    public interface ClassResolver
    {
        ClassFile resolve(String owner);
    }

    /** A single compiled method: its words, relocation fixups, and unwind metadata. */
    public record CompiledMethod(int[] words, List<CallSite> callSites, List<TibRef> tibRefs,
                                 List<StrRef> strRefs, List<StaticRef> staticRefs, List<TypeRef> typeRefs,
                                 List<TypeRef> interfaceRefs, int frameSize, List<HandlerRange> handlers) {}
    /** A try/catch region as word indices, for the writer's machine-PC handler table. */
    public record HandlerRange(int startWord, int endWord, int handlerWord, String catchClass) {}
    /** A {@code BL} site: word index within the method, and the callee key. */
    public record CallSite(int wordIndex, String calleeKey) {}
    /** A reserved TIB-pointer address load ({@code new}) awaiting the class's TIB address. */
    public record TibRef(int wordIndex, int reg, String className) {}
    /** A reserved address load for an interned string literal ({@code ldc}). */
    public record StrRef(int wordIndex, int reg, String text) {}
    /** A reserved address load for a static field ({@code getstatic}/{@code putstatic}). */
    public record StaticRef(int wordIndex, int reg, String fieldKey) {}
    /** A reserved address load for a class's Type ({@code instanceof}/{@code checkcast}). */
    public record TypeRef(int wordIndex, int reg, String className) {}

    private final ClassFile cf;
    private final WriterSymbols syms;   // the writer's Symbols impl; also holds the relocation records
    private final Baseline core;        // the shared, ClassFile-free code generator

    public BaselineCompiler(ClassFile cf)
    {
        this(cf, null);
    }
    public BaselineCompiler(ClassFile cf, ClassResolver resolver)
    {
        this.cf = cf;
        this.syms = new WriterSymbols(cf, resolver);
        this.core = new Baseline(cf.bytes(), cf.cpOff(), cf.cpTag(), syms);
    }

    /** Method key used for call resolution: {@code owner.name+descriptor}. */
    public static String key(String owner, String name, String desc)
    {
        return owner + "." + name + desc;
    }

    /** Compile one method at absolute {@code base}; {@code isEntry} => no frame. */
    public CompiledMethod compileMethod(ClassFile.Method method, long base, boolean isEntry)
    {
        if (method.code == null)
        {
            throw new IllegalStateException("method has no Code: " + method.name);
        }
        ClassFile.ExceptionEntry[] ex = method.exceptions;
        int n = ex.length;
        int[] es = new int[n], ee = new int[n], eh = new int[n], ec = new int[n];
        for (int i = 0; i < n; i++)
        {
            es[i] = ex[i].startPc();
            ee[i] = ex[i].endPc();
            eh[i] = ex[i].handlerPc();
            ec[i] = ex[i].catchType();
        }
        core.setExceptionTable(es, ee, eh, ec, n);
        int[] words = core.compileBody(method.code, method.descOff, method.isStatic, method.maxLocals, base, isEntry);

        // Zip the core's machine-PC handler ranges with their catch classes.
        List<HandlerRange> handlers = new ArrayList<>();
        for (int i = 0; i < core.handlerCount(); i++)
        {
            String catchClass = ec[i] == 0 ? null : cf.classAt(ec[i]);
            handlers.add(new HandlerRange(core.handlerStartWord(i), core.handlerEndWord(i),
                                          core.handlerWord(i), catchClass));
        }
        return new CompiledMethod(words, syms.callSites(), syms.tibRefs(), syms.strRefs(),
                                  syms.staticRefs(), syms.typeRefs(), syms.interfaceRefs(),
                                  core.frameSize(), List.copyOf(handlers));
    }

    /** Back-compat single-method compile with no real calls (spin/fixtures). */
    public void compile(ClassFile.Method method, CodeBuffer cb)
    {
        CompiledMethod cm = compileMethod(method, cb.base(), false);
        if (!cm.callSites.isEmpty() || !cm.tibRefs.isEmpty() || !cm.strRefs.isEmpty()
                || !cm.staticRefs.isEmpty() || !cm.typeRefs.isEmpty() || !cm.interfaceRefs.isEmpty())
        {
            throw new IllegalStateException("compile(Method,CodeBuffer) is for self-contained methods; use ImageBuilder");
        }
        for (int w : cm.words)
        {
            cb.emit(w);
        }
    }
}
