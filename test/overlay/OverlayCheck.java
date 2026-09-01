package overlay;

import classfile.ClassFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Catch the trap that has cost joe-ng nine separate debugging sessions: a {@code guestsrc/} overlay WINS the
 * name, so every stock member it does not declare CEASES TO EXIST -- with no build error and no
 * NoSuchMethodError. The call resolves nowhere, becomes a link stub, fails, and prints
 * {@code DENYLIST TRAP: call into a pruned (metal-absent) class}, naming a denylist the class is not even on.
 * Found so far this way: StringBuilder/Appendable, Class.getPrimitiveClass, the wrappers' TYPE,
 * Throwable.initCause, Character.toString, Boolean.getBoolean, the Collections unmodifiable* family,
 * ConcurrentHashMap.&lt;init&gt;(IFI)V, AtomicReferenceArray.getOpaque, DecimalDigits.appendPair.
 *
 * <p><b>A plain overlay-vs-stock diff is the wrong check.</b> Overlays are deliberately minimal -- dropping
 * most of a stock class is the POINT -- so that diff is thousands of lines of intended absence and would be
 * ignored within a week. This asks the question that actually matters instead: <b>does anything we ship
 * REFERENCE a member the overlay dropped?</b> Only those can trap.
 *
 * <p>References are read from every class the build produces ({@code out/}) and every entry of the RAMFS jars
 * ({@code ramfs/lib/*.jar}) -- the latter is not optional, because {@code ConcurrentHashMap.<init>(IFI)V} is
 * called from the JUnit jar and from nowhere else in the tree.
 *
 * <p>Resolution walks the OVERLAY's own super chain (an overlay may extend something different from stock),
 * preferring an overlaid ancestor over the stock one at each step -- which is exactly what the metal loader
 * does. A member found anywhere on that chain is fine; only a member found NOWHERE is reported.
 *
 * <p>Run: {@code java -cp out overlay.OverlayCheck [--baseline <file>]}. Exit 1 if anything new is missing.
 */
public final class OverlayCheck
{
    /** Internal name -> the overlay's own bytes (out/), for every class that SHADOWS a stock java.base class. */
    private final Map<String, byte[]> overlays = new HashMap<>();
    /** Internal name -> stock java.base bytes, cached. */
    private final Map<String, byte[]> stock = new HashMap<>();
    /** Internal name -> parsed, cached (parsing is the expensive part). */
    private final Map<String, ClassFile> parsed = new HashMap<>();
    /** owner#name#desc -> the classes that reference it. */
    private final Map<String, Set<String>> missing = new TreeMap<>();

    private FileSystem jrt;

    public static void main(String[] args) throws IOException
    {
        Path outDir = Path.of("out");
        Path baseline = null;
        Path write = null;
        for (int i = 0; i < args.length; i++)
        {
            if (args[i].equals("--baseline") && i + 1 < args.length)
            {
                baseline = Path.of(args[i + 1]);
            }
            if (args[i].equals("--write") && i + 1 < args.length)
            {
                write = Path.of(args[i + 1]);
            }
        }
        OverlayCheck c = new OverlayCheck();
        c.run(outDir, Path.of("ramfs", "lib"), baseline, write);
    }

    private void run(Path outDir, Path jarDir, Path baseline, Path write) throws IOException
    {
        jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        findOverlays(outDir);
        System.out.println("overlays that shadow a stock java.base class: " + overlays.size());

        scanTree(outDir);
        scanJars(jarDir);

        if (write != null)
        {
            List<String> out = new ArrayList<>();
            out.add("# Stock members that an overlay DROPS and something still REFERENCES -- each traps if");
            out.add("# reached. Regenerate: java -cp out overlay.OverlayCheck --write " + write);
            out.add("#");
            out.add("# This file is a BACKLOG, not an approval list. A line here means the gap is known and");
            out.add("# unfixed, not that it is safe -- most are cold paths in the JUnit jar, but the ones that");
            out.add("# bit us (Boolean.getBoolean, ConcurrentHashMap.<init>(IFI)V) looked exactly this cold");
            out.add("# until the day they ran. The check exists to stop NEW ones appearing silently.");
            out.addAll(missing.keySet());
            Files.write(write, out);
            System.out.println("wrote " + missing.size() + " known gap(s) to " + write);
            return;
        }

        Set<String> allowed = baseline != null && Files.exists(baseline)
                ? new HashSet<>(Files.readAllLines(baseline)) : new HashSet<>();
        allowed.removeIf(l -> l.isBlank() || l.startsWith("#"));

        List<String> fresh = new ArrayList<>();
        for (String key : missing.keySet())
        {
            if (!allowed.contains(key))
            {
                fresh.add(key);
            }
        }

        if (fresh.isEmpty())
        {
            System.out.println("overlay-check: " + missing.size() + " known gap(s), 0 new -- OK");
            return;
        }
        System.out.println();
        System.out.println("OVERLAY DROPS A REFERENCED MEMBER -- each of these traps if the call is reached:");
        for (String key : fresh)
        {
            System.out.println("  " + key.replace('#', ' '));
            int shown = 0;
            for (String from : missing.get(key))
            {
                if (shown++ == 3)
                {
                    System.out.println("        ... and " + (missing.get(key).size() - 3) + " more");
                    break;
                }
                System.out.println("        referenced by " + from);
            }
        }
        System.out.println();
        System.out.println("Declare the member on the overlay, or -- if it is genuinely unreachable on metal --");
        System.out.println("add the line to the baseline file to record that as a deliberate decision.");
        System.out.println("overlay-check: " + fresh.size() + " NEW gap(s)");
        System.exit(1);
    }

    /** Every {@code out/} class whose internal name also exists in java.base: that is what "overlay" means. */
    private void findOverlays(Path outDir) throws IOException
    {
        try (var paths = Files.walk(outDir))
        {
            for (Path p : (Iterable<Path>) paths::iterator)
            {
                if (!p.toString().endsWith(".class"))
                {
                    continue;
                }
                String name = internalName(outDir, p);
                byte[] s = stockBytes(name);
                if (s != null)
                {
                    overlays.put(name, Files.readAllBytes(p));
                }
            }
        }
    }

    private void scanTree(Path dir) throws IOException
    {
        try (var paths = Files.walk(dir))
        {
            for (Path p : (Iterable<Path>) paths::iterator)
            {
                if (p.toString().endsWith(".class"))
                {
                    checkRefs(internalName(dir, p), Files.readAllBytes(p));
                }
            }
        }
    }

    private void scanJars(Path jarDir) throws IOException
    {
        if (!Files.isDirectory(jarDir))
        {
            return;
        }
        try (var paths = Files.list(jarDir))
        {
            for (Path jar : (Iterable<Path>) paths::iterator)
            {
                if (!jar.toString().endsWith(".jar"))
                {
                    continue;
                }
                try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar)))
                {
                    ZipEntry e;
                    while ((e = zin.getNextEntry()) != null)
                    {
                        if (e.getName().endsWith(".class"))
                        {
                            checkRefs(e.getName().substring(0, e.getName().length() - 6), zin.readAllBytes());
                        }
                    }
                }
            }
        }
    }

    /** Report every member reference from {@code fromName} that targets an overlay and resolves nowhere. */
    private void checkRefs(String fromName, byte[] bytes)
    {
        ClassFile cf;
        try
        {
            cf = new ClassFile(bytes);
        }
        catch (Exception ex)
        {
            return;                                     // not parseable by our reader; nothing to say about it
        }
        int[] tag = cf.cpTag();
        for (int i = 1; i < tag.length; i++)
        {
            // 9 = Fieldref, 10 = Methodref, 11 = InterfaceMethodref
            if (tag[i] != 9 && tag[i] != 10 && tag[i] != 11)
            {
                continue;
            }
            ClassFile.MemberRef r;
            try
            {
                r = cf.memberRef(i);
            }
            catch (Exception ex)
            {
                continue;
            }
            if (r.owner() == null || !overlays.containsKey(r.owner()) || r.owner().equals(fromName))
            {
                continue;                               // not an overlay, or a class referring to itself
            }
            if (r.name().startsWith("<") && !r.name().equals("<init>"))
            {
                continue;                               // <clinit> is never referenced by a call site
            }
            if (!declaredOnChain(r.owner(), r.name(), r.descriptor(), tag[i] == 9))
            {
                missing.computeIfAbsent(r.owner() + "#" + r.name() + r.descriptor(),
                        k -> new TreeSet<>()).add(fromName);
            }
        }
    }

    /**
     * Does {@code cls} or any ancestor declare this member? The chain is the OVERLAY's, and at each level an
     * overlaid ancestor is preferred over the stock one -- which is what the metal loader resolves against.
     * A constructor is deliberately NOT inherited (JVMS): {@code <init>} must be declared by the class itself.
     */
    private boolean declaredOnChain(String cls, String name, String desc, boolean field)
    {
        String cur = cls;
        int hops = 0;
        while (cur != null && hops++ < 24)
        {
            ClassFile cf = parse(cur);
            if (cf == null)
            {
                return true;                            // cannot see it -> do not accuse
            }
            if (field)
            {
                for (ClassFile.FieldInfo f : cf.fields())
                {
                    if (f.name().equals(name) && f.descriptor().equals(desc))
                    {
                        return true;
                    }
                }
            }
            else
            {
                for (ClassFile.Method m : cf.methods())
                {
                    if (m.name.equals(name) && m.descriptor.equals(desc))
                    {
                        return true;
                    }
                }
            }
            if (name.equals("<init>"))
            {
                return false;                           // constructors are never inherited
            }
            if (interfaceDeclares(cf, name, desc, field))
            {
                return true;                            // a default/abstract method, or an interface constant
            }
            cur = cf.superClassName();
            if ("java/lang/Object".equals(cur) && !cls.equals("java/lang/Object"))
            {
                ClassFile obj = parse("java/lang/Object");
                if (obj != null)
                {
                    for (ClassFile.Method m : obj.methods())
                    {
                        if (m.name.equals(name) && m.descriptor.equals(desc))
                        {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        return true;                                    // chain too deep / cyclic: stay quiet rather than lie
    }

    private boolean interfaceDeclares(ClassFile cf, String name, String desc, boolean field)
    {
        String[] ifs = cf.interfaceNames();
        if (ifs == null)
        {
            return false;
        }
        for (String in : ifs)
        {
            ClassFile icf = parse(in);
            if (icf == null)
            {
                continue;
            }
            if (field)
            {
                for (ClassFile.FieldInfo f : icf.fields())
                {
                    if (f.name().equals(name) && f.descriptor().equals(desc))
                    {
                        return true;
                    }
                }
            }
            else
            {
                for (ClassFile.Method m : icf.methods())
                {
                    if (m.name.equals(name) && m.descriptor.equals(desc))
                    {
                        return true;
                    }
                }
            }
            if (interfaceDeclares(icf, name, desc, field))
            {
                return true;
            }
        }
        return false;
    }

    /** Overlay bytes if this class is overlaid, else the stock java.base bytes; parsed once. */
    private ClassFile parse(String name)
    {
        if (parsed.containsKey(name))
        {
            return parsed.get(name);
        }
        byte[] b = overlays.get(name);
        if (b == null)
        {
            b = stockBytes(name);
        }
        ClassFile cf = null;
        if (b != null)
        {
            try
            {
                cf = new ClassFile(b);
            }
            catch (Exception ex)
            {
                cf = null;
            }
        }
        parsed.put(name, cf);
        return cf;
    }

    private byte[] stockBytes(String internal)
    {
        if (stock.containsKey(internal))
        {
            return stock.get(internal);
        }
        byte[] b = null;
        try
        {
            Path p = jrt.getPath("/modules/java.base/" + internal + ".class");
            if (Files.exists(p))
            {
                b = Files.readAllBytes(p);
            }
        }
        catch (Exception ex)
        {
            b = null;
        }
        stock.put(internal, b);
        return b;
    }

    private static String internalName(Path root, Path p)
    {
        String rel = root.relativize(p).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ".class".length());
    }
}
