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
    /** {@code supertype#<overlay>#<stock supertype>} for each supertype an overlay drops. */
    private final Set<String> droppedTypes = new TreeSet<>();
    /** Internal name -> parsed STOCK class. Separate from {@link #parsed}, which prefers the OVERLAY. */
    private final Map<String, ClassFile> stockParsed = new HashMap<>();

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
        for (int i = 0; i < args.length; i++)
        {
            if (args[i].equals("--deep"))
            {
                c.deep = true;
            }
        }
        c.run(outDir, Path.of("ramfs", "lib"), baseline, write);
    }

    /**
     * Whether to scan the stock {@code java.base} classes as callers as well.
     *
     * <p>OFF by default, and the reason is scope rather than cost. Every java.base class is demand-loadable and
     * can call an overlaid member, so the deep scan is the TRUE set of "would trap if reached" -- about 1059
     * further members. But most of java.base is never reached on metal (denylisted subtrees, cold paths), so
     * folding that into the default baseline would bury the ~57 gaps in code we actually ship and the check
     * would stop being read -- which is how the one that mattered got missed in the first place.
     *
     * <p>Use {@code make overlaycheck-deep} when chasing a trap whose caller is stock library code:
     * {@code Character.getType} was dropped and its only caller is
     * {@code java.time.format.DateTimeFormatterBuilder}, so the shallow scan could not see it.
     */
    private boolean deep;

    private void run(Path outDir, Path jarDir, Path baseline, Path write) throws IOException
    {
        jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        findOverlays(outDir);
        System.out.println("overlays that shadow a stock java.base class: " + overlays.size());

        checkSupertypes();
        scanTree(outDir);
        scanJars(jarDir);
        if (deep)
        {
            scanJavaBase();
        }

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
            out.add("#");
            out.add("# Supertypes an overlay drops. A dropped SUPERCLASS or INTERFACE is as silent as a");
            out.add("# dropped member and strictly worse: nothing that WRAPS the class can bind to it.");
            out.addAll(droppedTypes);
            Files.write(write, out);
            System.out.println("wrote " + missing.size() + " known gap(s) and "
                    + droppedTypes.size() + " dropped supertype(s) to " + write);
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
        List<String> freshTypes = new ArrayList<>();
        for (String key : droppedTypes)
        {
            if (!allowed.contains(key))
            {
                freshTypes.add(key);
            }
        }

        if (!freshTypes.isEmpty())
        {
            System.out.println();
            System.out.println("OVERLAY DROPS A SUPERTYPE -- nothing that WRAPS the class can bind to it:");
            for (String key : freshTypes)
            {
                String[] part = key.split("#");
                System.out.println("  " + part[1] + "  is missing  " + part[2]);
            }
            System.out.println();
            System.out.println("Declare it on the overlay (`extends`/`implements`), or -- if the overlay");
            System.out.println("deliberately binds to a different ancestor -- add the line to the baseline.");
        }

        if (fresh.isEmpty() && freshTypes.isEmpty())
        {
            System.out.println("overlay-check: " + missing.size() + " known gap(s), "
                    + droppedTypes.size() + " known dropped supertype(s), 0 new -- OK");
            return;
        }
        if (fresh.isEmpty())
        {
            System.out.println("overlay-check: " + freshTypes.size() + " NEW dropped supertype(s)");
            System.exit(1);
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
        System.out.println("overlay-check: " + fresh.size() + " NEW gap(s), "
                + freshTypes.size() + " NEW dropped supertype(s)");
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

    /**
     * Scan the stock {@code java.base} classes too -- the LARGEST caller population, and the one this check
     * originally missed. Every class in java.base is demand-loadable on metal and routinely calls into an
     * overlaid class, so a member an overlay drops traps just as readily from there as from our own code.
     *
     * <p>That blind spot was not theoretical: {@code Character.getType} was dropped, and the only caller is
     * stock {@code java.time.format.DateTimeFormatterBuilder} -- so the gap was INVISIBLE to this check and
     * surfaced instead as a denylist trap on a boot.
     *
     * <p>An overlaid class's OWN stock version is skipped: its bytes are the ones the overlay REPLACES, so its
     * internal references describe the class we are not running.
     */
    private void scanJavaBase() throws IOException
    {
        Path base = jrt.getPath("/modules/java.base");
        try (var paths = Files.walk(base))
        {
            for (Path p : (Iterable<Path>) paths::iterator)
            {
                String sp = p.toString();
                if (!sp.endsWith(".class"))
                {
                    continue;
                }
                String name = base.relativize(p).toString();
                name = name.substring(0, name.length() - ".class".length());
                if (name.equals("module-info") || overlays.containsKey(name))
                {
                    continue;
                }
                checkRefs(name, Files.readAllBytes(p));
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

    /**
     * Every supertype a stock class has that its overlay's own supertype closure does not.
     *
     * <p><b>A dropped SUPERCLASS or INTERFACE is as silent as a dropped member, and strictly worse.</b> A
     * dropped member breaks calls TO it; a dropped supertype breaks everything that WRAPS the class. joe-ng
     * has paid for this twice: {@code StringBuilder} not implementing {@code Appendable} broke
     * {@code String.replaceAll} (stock {@code Matcher} declares its sink as {@code Appendable}), and
     * {@code PrintStream} not extending {@code OutputStream} meant {@code System.out} was not an
     * {@code OutputStream} at all -- so {@code new PrintWriter(System.out)} could not bind and the console
     * launcher produced <b>no output whatsoever</b>, not a crash. Neither was visible to the member check
     * this class was built around, which is why that arc's postmortem named this the highest-value gap.
     *
     * <p>Compared as a CLOSURE (superclasses and interfaces, transitively) rather than as the direct
     * supertype, because an overlay may legitimately bind to a different ancestor: joe-ng's
     * {@code PrintStream} extends {@code java.io.OutputStream} directly, skipping stock's
     * {@code FilterOutputStream} whose wrapped-stream field is unused. The closure comparison still reports
     * {@code FilterOutputStream} as dropped -- correctly, since it IS absent -- and that line belongs in the
     * baseline as a recorded decision. Same rule as the member backlog: a line means known, not safe.
     *
     * <p>The overlay side is walked with {@link #parse}, which prefers an overlaid ancestor at each step --
     * what the metal loader does -- so an overlay inheriting a supertype through another overlay counts.
     */
    private void checkSupertypes()
    {
        for (String name : new TreeSet<>(overlays.keySet()))
        {
            ClassFile stockCf = parseStock(name);
            if (stockCf == null)
            {
                continue;                                // not a shadowed class after all
            }
            Set<String> want = new TreeSet<>();
            collectSupers(stockCf, want, true);
            Set<String> have = new TreeSet<>();
            ClassFile overlayCf = parse(name);
            if (overlayCf == null)
            {
                continue;
            }
            collectSupers(overlayCf, have, false);
            for (String t : want)
            {
                if (!have.contains(t))
                {
                    droppedTypes.add("supertype#" + name + "#" + t);
                }
            }
        }
    }

    /**
     * The transitive superclass + interface closure of {@code cf}, excluding {@code java/lang/Object} (which
     * every class has, so reporting it would be noise). {@code stockSide} selects which world each ancestor
     * is read from: the stock one for what SHOULD be there, the overlay-preferring one for what IS.
     */
    private void collectSupers(ClassFile cf, Set<String> into, boolean stockSide)
    {
        String sup = cf.superClassName();
        if (sup != null && !sup.equals("java/lang/Object") && into.add(sup))
        {
            ClassFile s = stockSide ? parseStock(sup) : parse(sup);
            if (s != null)
            {
                collectSupers(s, into, stockSide);
            }
        }
        String[] ifs = cf.interfaceNames();
        if (ifs == null)
        {
            return;
        }
        for (String in : ifs)
        {
            if (!into.add(in))
            {
                continue;
            }
            ClassFile i = stockSide ? parseStock(in) : parse(in);
            if (i != null)
            {
                collectSupers(i, into, stockSide);
            }
        }
    }

    /** The STOCK java.base class, parsed once -- {@link #parse} prefers the overlay, which this must not. */
    private ClassFile parseStock(String name)
    {
        if (stockParsed.containsKey(name))
        {
            return stockParsed.get(name);
        }
        ClassFile cf = null;
        byte[] b = stockBytes(name);
        if (b != null)
        {
            try
            {
                cf = new ClassFile(b);
            }
            catch (Exception ex)
            {
                cf = null;                               // unparseable stock class: not this check's business
            }
        }
        stockParsed.put(name, cf);
        return cf;
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
