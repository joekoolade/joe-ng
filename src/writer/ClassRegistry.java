package writer;

import classfile.ClassFile;
import util.StrIntTable;
import util.Vec;

import java.io.IOException;

/**
 * A name-keyed store of raw {@code .class} bytes with a lazy parse cache — the writer's
 * class source, abstracted from <em>where</em> the bytes come from. On the seed host
 * {@link BuildRuntimeImage} fills it from files on disk; on metal the self-build harness
 * will fill it from the class blobs embedded in the image (PLAN.md §M5.5c step 2). Either
 * way {@link ImageBuilder} sees only {@link #resolve}, a pure lookup with no I/O — the
 * shape that compiles on metal, replacing the old {@code Path} + {@code HashMap} + file
 * read that could not.
 *
 * <p>Over-inclusion is harmless: parsing is lazy, so a registered class the build never
 * reaches is never parsed and never affects the image.
 */
final class ClassRegistry
{
    private final StrIntTable index = new StrIntTable();   // internal name -> slot
    private final Vec<byte[]> raw = new Vec<>();           // raw .class bytes per slot
    private final Vec<ClassFile> parsed = new Vec<>();     // parse cache (null until first resolve)
    private final Vec<String> reached = new Vec<>();       // names in first-resolve order (compile set)

    /** Register {@code bytes} as the class {@code name} (internal form, e.g. "vm/VM"). */
    void add(String name, byte[] bytes)
    {
        index.put(name, raw.size());
        raw.add(bytes);
        parsed.add(null);
    }

    /** Whether {@code name} has been registered. */
    boolean has(String name)
    {
        return index.containsKey(name);
    }

    /** The raw {@code .class} bytes registered for {@code name} (for verbatim embedding). */
    byte[] rawBytes(String name)
    {
        return raw.get(slotOf(name));
    }

    /** The parsed {@link ClassFile} for {@code name}, parsed on first use and cached. */
    ClassFile resolve(String name)
    {
        int slot = slotOf(name);
        ClassFile cf = parsed.get(slot);
        if (cf == null)
        {
            try
            {
                cf = new ClassFile(raw.get(slot));
            }
            catch (IOException e)
            {
                throw new RuntimeException("bad classfile for " + name, e);
            }
            parsed.set(slot, cf);
            reached.add(name);               // parsed = the writer reached it = compile set member
        }
        return cf;
    }

    /**
     * The classes the writer has parsed so far, in first-resolve order — the
     * compile-reachable set the metal self-build must embed and look up by name
     * (PLAN.md §M5.5c step 2). Live: query it once discovery is complete.
     */
    Vec<String> reached()
    {
        return reached;
    }

    private int slotOf(String name)
    {
        if (!index.containsKey(name))
        {
            throw new RuntimeException("class not registered: " + name);
        }
        return index.get(name);
    }
}
