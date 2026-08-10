package writer;

import classfile.ClassFile;
import compiler.Baseline;
import util.StrIntTable;
import util.StrSet;
import util.Vec;

import java.io.IOException;
import java.nio.file.Path;

/**
 * HOST-ONLY faithful reachability scanner (#43). Unlike {@link ClosureProbe} (which drives the compiler and
 * truncates on any opcode the host compiler lacks), this walks bytecode DIRECTLY -- no compilation -- so it
 * computes the full method-level class closure the metal demand-loader would pull, mirroring
 * {@code Loader.collectRefs}: every invoke/field/new/anewarray/checkcast/instanceof/multianewarray class ref,
 * plus each class's super + interfaces, plus &lt;clinit&gt; seeding. Prints the closure size and a per-subtree
 * histogram (regex / icu / foreign / invoke / stream / locale / serviceloader / ...) so the regex-Pattern
 * closure can be attacked -- i.e. which cold subtrees to cut to fit MAXBLOB=1024.
 *
 * <p>Usage: {@code java -cp out writer.ReachScan "<entryKey>" [classesDir]}
 * default entry = {@code java/lang/String.split(Ljava/lang/String;)[Ljava/lang/String;}
 */
public final class ReachScan
{
    private ReachScan() {}

    /** Metal-absent subtrees to prune: pulled only by never-executed cold paths on metal. Keep in sync with
     *  the loader's denylist (Loader.isDenylisted). */
    static final String[] DENY = {
        "java/lang/invoke/", "java/lang/foreign/", "jdk/internal/foreign/",
        "sun/nio/fs/", "java/nio/file/", "jdk/internal/loader/", "java/lang/ClassLoader",
        "java/security/", "java/util/ServiceLoader", "java/util/spi/", "sun/util/",
        // java/net is loadable (M3 sockets). SocksSocketImpl IS on the taken path (Socket.createImpl always
        // wraps the platform impl in it) -- overlaid as a pure delegator, so NOT denied. The HTTP-CONNECT
        // proxy impl + www/ext + the GC-auto-close SocketCleanable stay trapped (never taken).
        "java/net/HttpConnectSocketImpl", "java/net/SocketCleanable",
        "sun/net/www/", "sun/net/ext/",
        // heavy socket subtrees never taken on the blocking client path (would pull streams/ForkJoin/regex).
        "sun/nio/ch/Poller", "sun/nio/ch/ExtendedSocketOption", "java/net/NetworkInterface",
        // Exceptions = NioSocketImpl's error-message formatter (String.format->Formatter->regex + a
        // security-property read->Properties/stream), reached only at throw sites; IPAddressUtil =
        // link-local scoped-address cache (ConcurrentHashMap), reached only under isLinkLocalAddress()==false.
        "jdk/internal/util/Exceptions", "sun/net/util/IPAddressUtil",
        "jdk/internal/logger/", "java/lang/reflect/", "jdk/internal/reflect/",
        "jdk/internal/module/", "java/lang/module/", "java/text/spi/",
        // cold ICU/normalizer/break-iterator, pulled by Pattern but never run for a literal match. (NOT
        // java/util/concurrent -- the philosophers demand-load java/util/concurrent/Semaphore.)
        "jdk/internal/icu/", "java/text/", "sun/text/",
        // grapheme-boundary tables (\b{g}): a 15x15 [[Z built via multianewarray; cold for a literal match.
        "jdk/internal/util/regex/Grapheme",
        // case-folding tables ([[I via multianewarray): only CASE_INSENSITIVE regex needs them.
        "jdk/internal/lang/CaseFolding",
        // charset encoder/decoder fallback (never taken: the overlay singletons pin the UTF-8 fast path).
        "java/nio/charset/CharsetDecoder", "java/nio/charset/CharsetEncoder",
        "java/nio/charset/Coder", "java/nio/charset/Coding", "java/nio/charset/CharacterCoding",
        "java/nio/charset/Malformed", "java/nio/charset/Unmappable",
        "java/nio/charset/IllegalCharsetName", "java/nio/charset/UnsupportedCharset",
        "java/nio/CharBuffer", "sun/nio/cs/Array",   // ByteBuffer now loadable (overlay -> socket buffers)
    };

    static boolean deny = false;                       // set by the "DENY" arg
    static final StrSet denied = new StrSet();         // denylisted classes referenced by kept code (-> trap sites)

    static boolean isDenied(String c)
    {
        // Narrow ALLOW for the VarHandle-as-atomic-field-accessor shim (overlays): java.net.Socket uses a
        // VarHandle for its state/in/out fields. These specific java/lang/invoke classes are allowed; the rest
        // of java/lang/invoke stays denied.
        if (c.startsWith("java/lang/invoke/VarHandle")
                || c.startsWith("java/lang/invoke/MethodHandles")
                || c.startsWith("jdk/internal/invoke/MhUtil")
                || c.startsWith("sun/net/ext/ExtendedSocketOptions"))   // overlaid no-op; rest of sun/net/ext denied
        {
            return false;
        }
        for (String p : DENY) { if (c.startsWith(p)) { return true; } }
        return false;
    }

    public static void main(String[] args) throws IOException
    {
        String entry = args.length > 0 ? args[0] : "java/lang/String.split(Ljava/lang/String;)[Ljava/lang/String;";
        deny = args.length > 1 && args[1].equals("DENY");
        Path classesDir = Path.of(args.length > 2 ? args[2] : "out");
        ClassRegistry reg = BuildRuntimeImage.populateRegistry(classesDir);

        StrSet classes = new StrSet();                 // reached classes
        StrIntTable seenM = new StrIntTable();         // reached method keys
        Vec<String> work = new Vec<>();
        work.add(entry);
        int unresolved = 0;

        while (work.size() > 0)
        {
            String k = work.removeFirst();
            if (seenM.containsKey(k)) { continue; }
            seenM.put(k, 1);
            int lp = k.indexOf('(');
            int dot = k.lastIndexOf('.', lp);
            String owner = k.substring(0, dot);
            String name = k.substring(dot + 1, lp);
            String desc = k.substring(lp);
            if (deny && isDenied(owner)) { denied.add(owner); continue; }   // trap site: don't pull its body
            useClass(owner, classes, work, reg);
            ClassFile cf = tryResolve(reg, owner);
            if (cf == null) { unresolved++; continue; }
            ClassFile.Method m = null;
            for (ClassFile.Method mm : cf.methods())
            {
                if (mm.name.equals(name) && mm.descriptor.equals(desc)) { m = mm; break; }
            }
            if (m == null || m.code == null) { continue; }
            scan(cf, m.code, classes, work, reg);
        }

        System.out.println("entry: " + entry + (deny ? "   [DENYLIST ON]" : ""));
        System.out.println("reachable classes: " + classes.size() + "   (MAXBLOB budget = 1024)");
        System.out.println("reachable methods: " + seenM.size() + "   (unresolved owners skipped: " + unresolved + ")");
        if (deny)
        {
            System.out.println("denied classes referenced (trap sites): " + denied.size());
            for (int i = 0; i < denied.size(); i++) { System.out.println("    TRAP " + denied.at(i)); }
        }
        System.out.println();
        System.out.println("=== per-subtree histogram (biggest first) ===");
        StrIntTable hist = new StrIntTable();
        for (int i = 0; i < classes.size(); i++)
        {
            String b = bucket(classes.at(i));
            hist.put(b, (hist.containsKey(b) ? hist.get(b) : 0) + 1);
        }
        // print sorted by count desc
        boolean[] done = new boolean[hist.size()];
        for (int r = 0; r < hist.size(); r++)
        {
            int best = -1;
            for (int i = 0; i < hist.size(); i++)
            {
                if (!done[i] && (best < 0 || hist.valAt(i) > hist.valAt(best))) { best = i; }
            }
            done[best] = true;
            System.out.println("  " + pad(String.valueOf(hist.valAt(best)), 6) + hist.keyAt(best));
        }
    }

    private static void scan(ClassFile cf, byte[] code, StrSet classes, Vec<String> work, ClassRegistry reg)
    {
        int cpN = cf.cpTag().length;
        int pc = 0;
        while (pc < code.length)
        {
            int op = code[pc] & 0xFF;
            int idx = pc + 2 < code.length ? u2(code, pc + 1) : -1;
            boolean okIdx = idx > 0 && idx < cpN;
            if ((op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xB9) && okIdx)   // invoke v/special/static/interface
            {
                ClassFile.MemberRef r = cf.memberRef(idx);
                if (r != null && r.owner() != null)
                {
                    useClass(r.owner(), classes, work, reg);
                    work.add(r.owner() + "." + r.name() + r.descriptor());
                }
            }
            else if ((op == 0xB2 || op == 0xB3 || op == 0xB4 || op == 0xB5) && okIdx)   // get/put static|field
            {
                ClassFile.MemberRef r = cf.memberRef(idx);
                if (r != null && r.owner() != null) { useClass(r.owner(), classes, work, reg); }
            }
            else if ((op == 0xBB || op == 0xBD || op == 0xC0 || op == 0xC1 || op == 0xC5) && okIdx)  // new/checkcast/...
            {
                String c = cf.classAt(idx);
                if (c != null) { useClass(stripArray(c), classes, work, reg); }
            }
            int step = (op == 0xC8 || op == 0xC9) ? 5 : Baseline.opLen(op, code, pc);   // goto_w/jsr_w = 5
            pc += step < 1 ? 1 : step;
        }
    }

    /** Mark a class used; on first use pull its super + interfaces + &lt;clinit&gt; (like the loader's use()). */
    private static void useClass(String cls, StrSet classes, Vec<String> work, ClassRegistry reg)
    {
        cls = stripArray(cls);
        if (cls.startsWith("[") || cls.isEmpty()) { return; }
        if (deny && isDenied(cls)) { denied.add(cls); return; }   // pruned: a call to it becomes a metal trap site
        if (!classes.add(cls)) { return; }
        ClassFile cf = tryResolve(reg, cls);
        if (cf == null) { return; }
        String sup = cf.superClassName();
        if (sup != null)
        {
            if (deny && isDenied(sup)) { System.out.println("!! STRUCTURAL: kept " + cls + " extends denied " + sup); }
            useClass(sup, classes, work, reg);
        }
        for (String ifc : cf.interfaceNames())
        {
            if (deny && isDenied(ifc)) { System.out.println("!! STRUCTURAL: kept " + cls + " implements denied " + ifc); }
            useClass(ifc, classes, work, reg);
        }
        for (ClassFile.Method mm : cf.methods())
        {
            if (mm.name.equals("<clinit>")) { work.add(cls + ".<clinit>()V"); }
        }
    }

    private static ClassFile tryResolve(ClassRegistry reg, String name)
    {
        try { return reg.resolve(name); }
        catch (RuntimeException e) { return null; }   // unregistered root (java/lang/Object, magic/Magic, ...)
    }

    private static String stripArray(String c)
    {
        int i = 0;
        while (i < c.length() && c.charAt(i) == '[') { i++; }
        if (i == 0) { return c; }
        String e = c.substring(i);
        if (e.startsWith("L") && e.endsWith(";")) { return e.substring(1, e.length() - 1); }
        return "";                                     // primitive array element -> nothing to load
    }

    private static int u2(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    private static String bucket(String c)
    {
        if (c.startsWith("java/util/regex")) { return "java/util/regex (engine)"; }
        if (c.startsWith("jdk/internal/icu")) { return "jdk/internal/icu (Normalizer)"; }
        if (c.startsWith("java/lang/foreign") || c.startsWith("jdk/internal/foreign")) { return "foreign-memory"; }
        if (c.startsWith("java/lang/invoke")) { return "java/lang/invoke (VarHandle/MH)"; }
        if (c.startsWith("java/util/stream")) { return "java/util/stream"; }
        if (c.startsWith("java/util/concurrent")) { return "java/util/concurrent"; }
        if (c.startsWith("sun/nio/fs") || c.startsWith("java/nio/file")) { return "nio filesystem"; }
        if (c.startsWith("java/nio")) { return "java/nio (buffers)"; }
        if (c.startsWith("jdk/internal/loader") || c.startsWith("java/lang/ClassLoader") || c.startsWith("java/security")) { return "classloader/security"; }
        if (c.startsWith("java/util/ServiceLoader") || c.startsWith("java/util/spi") || c.startsWith("sun/util/locale") || c.startsWith("sun/util/cldr")) { return "serviceloader/locale-provider"; }
        if (c.startsWith("java/util/Locale") || c.startsWith("sun/util")) { return "locale"; }
        if (c.startsWith("jdk/internal/logger") || c.startsWith("sun/util/logging")) { return "logging"; }
        if (c.startsWith("java/lang/reflect") || c.startsWith("jdk/internal/reflect")) { return "reflect"; }
        if (c.startsWith("java/text")) { return "java/text"; }
        if (c.startsWith("java/net")) { return "java/net"; }
        if (c.startsWith("java/io")) { return "java/io"; }
        if (c.startsWith("java/util/function")) { return "java/util/function"; }
        if (c.startsWith("java/util")) { return "java/util (other)"; }
        if (c.startsWith("java/lang")) { return "java/lang (core)"; }
        if (c.startsWith("jdk/internal")) { return "jdk/internal (other)"; }
        return "misc";
    }

    private static String pad(String s, int n)
    {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) { b.append(' '); }
        return b.toString();
    }
}
