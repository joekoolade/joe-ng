/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import board.bcm2711.Uart;
import magic.Address;
import magic.Magic;
import magic.ObjectReference;

import static vm.ClassBytes.u2;

/**
 * The java.base state a stock JVM sets up before {@code main} and this VM SEEDS instead of running the JDK's own
 * initialization phases: {@code System.props}, the saved properties, {@code System.out/err/in}, the line separator,
 * {@code VM.initLevel}'s lock and the {@code SharedSecrets} accessors -- filled directly at their registered static
 * and field offsets, so no {@code <init>} has to run. Split out of {@link Loader}.
 */
final class BootSeeds
{
    private BootSeeds()
    {
    }

    /** The {@link #loaderGen} each standard stream was installed in (seeded, or set by the program); -1 none. */
    private static int outGen = -1;
    private static int errGen = -1;
    private static int inGen = -1;
    private static final int STD_OUT = 0;
    private static final int STD_ERR = 1;
    private static final int STD_IN = 2;
    /**
     * Seed a {@code MetalJavaLangAccess} instance into {@code SharedSecrets.javaLangAccess} so
     * {@code EnumMap.getKeyUniverse} (-&gt; {@code getJavaLangAccess().getEnumConstantsShared()}) works. On stock
     * the JLA is registered by {@code System.<clinit>}, which the metal skips, leaving the field null. Runs inside
     * {@code loadAll} BEFORE {@code runClinits}, since {@code StreamOpFlag.<clinit>} builds an EnumMap. No-op when
     * neither class is in the batch (non-EnumMap programs). Field-free overlay -> just the TIB (itable dispatch).
     */
    /**
     * Seed {@code SharedSecrets.javaIOAccess} with a {@link jdk.internal.access.MetalJavaIOAccess}.
     *
     * <p>Stock registers one from {@code java.io.Console}'s initializer, which needs a tty. Without it
     * {@code System.console()} dereferences null -- it calls {@code .console()} on the shim unconditionally --
     * so this is not an optional nicety: it is what makes {@code System.console()} answer NULL instead of
     * throwing, which is what every stock caller is written for.
     */
    /**
     * Seed {@code jdk/internal/misc/VM.lock} with a plain Object.
     *
     * <p>That class is {@code clinitBlocked} -- its initializer is a native {@code initialize()} that sets
     * saved properties and direct-memory limits -- so its {@code private static final Object lock} stays null.
     * {@code VM.initLevel(int)} opens with {@code synchronized (lock)}, so the null is a {@code monitorenter}
     * on null: an NPE from inside java.base on the SHUTDOWN path, where {@code Shutdown.runHooks} calls
     * {@code VM.shutdown()}. System.exit could not complete.
     *
     * <p>A bare Object is the whole requirement -- the field is only ever a monitor, never read for content --
     * so this seeds exactly what the blocked initializer would have provided and nothing more.
     */
    /**
     * ONE-SHOT FLAGS FOR THE SEEDS, and they are the whole fix for the largest item in the clinit phase.
     *
     * <p>Every seed below is IDEMPOTENT and its result IMMUTABLE -- a field-free access object, a bare
     * monitor, a primitive mirror. Re-running them per batch therefore cannot change an answer, and it cost
     * fifteen linear walks of the static registry per batch plus 512 allocations, over ~209 batches of a
     * launcher boot.
     *
     * <p>THE TWO BOX-CACHE SEEDS ARE GONE (2026-09-24), and with them the question the next paragraph
     * leaves open. {@code seedIntegerCache}/{@code seedLongCache} built an {@code Integer[256]}/
     * {@code Long[256]} on the HEAP and stored it over the array the WRITER had already baked -- a joe-ng
     * special case standing where stock's own archived-subgraph path belongs. The image carries the cache,
     * so nothing needs to build one: with both seeds deleted every box in [-128,127] comes back at an IMAGE
     * address, measured rather than argued.
     *
     * <p>I ALSO CLAIMED RE-RUNNING BROKE SMALL-INTEGER BOX IDENTITY, AND A NEGATIVE CONTROL REFUTED IT. Four
     * of these allocate, so re-running looked like it must REPLACE the object the static already held, making
     * `Integer.valueOf(5) == Integer.valueOf(5)` false across a batch boundary. A probe that forces a
     * demand-load BETWEEN the two calls reports `int=1 long=1 TYPE=1` with these guards REMOVED, so the
     * re-seed does not in fact replace the cache the running program reads. WHY it does not is not
     * established, and the probe was deleted rather than kept: an arm that passes in both states is not a
     * control, and leaving it in the suite would read as evidence to the next person.
     *
     * <p>A flag is set only on SUCCESS, because a seed legitimately cannot run until its class is registered
     * (a seed returns early while the class it fills is absent), and it must be retried until then. THEY RESET IN `resetLoader` BESIDE `sgCount = 0` -- a flag that outlived the static registry it
     * guards would skip the seed for a whole launch and leave the statics null, which is the same
     * watermark-outliving-its-table trap already recorded twice here.
     */
    static boolean sdVmLock, sdJio, sdJla, sdPrim;
    static int sdPrimMask;                      // one bit per wrapper TYPE already seeded (see seedPrimType)

    /**
     * Install a real {@code java.util.Properties} in {@code System.props}, the same way
     * {@link #seedSystemStreams()} installs the streams: stock {@code System.initPhase1} is native-heavy and
     * never runs on metal, so the field stays null and the FIRST {@code System.setProperty} NPEs inside
     * java.base -- which is where JUnit's {@code ConsoleLauncher} dies before printing anything.
     *
     * <p>Unlike the PrintStream overlay this one CANNOT be a bare allocation: {@code Properties} extends
     * {@code Hashtable}, whose table array is built by its constructor, so an object with only a TIB would NPE
     * on the first {@code put} instead. The {@code <init>} is resolved and run on the fresh instance.
     *
     * <p>No-op if the class is absent or the slot is already filled -- a boot whose closure lacks Properties
     * has no {@code setProperty} caller either.
     */
    static void seedSystemProps()
    {
        Address slot = Address.fromLong(Loader.staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("props")));
        if (slot.isZero())
        {
            reportPropsGap(Magic.bytes("java/lang/System.props has no static cell"));
            return;
        }
        if (slot.loadLong() != 0L)
        {
            return;                                     // already seeded: not a failure, say nothing
        }
        int pi = Loader.classIndexByName(Magic.bytes("java/util/Properties"));
        if (pi < 0)
        {
            reportPropsGap(Magic.bytes("java/util/Properties is not registered"));
            return;
        }
        long obj = Reflection.allocInstance(Loader.clTab[pi].type);
        if (obj == 0L)
        {
            reportPropsGap(Magic.bytes("java/util/Properties could not be allocated"));
            return;
        }
        int ctor = Reflection.constructorResolve(Loader.clTab[pi].type, 0);
        if (ctor < 0 || Loader.rgTab[ctor].buf == 0L)
        {
            reportPropsGap(Magic.bytes("java/util/Properties.<init>() has no compiled body"));
            return;                                     // no runnable <init>: leaving props null is honest
        }
        Magic.call2(Loader.rgTab[ctor].buf, obj, 0L);          // receiver in x0; a no-arg ctor reads nothing else
        slot.store(obj);
        seedStandardProps(obj, pi);
        seedLineSeparator();
    }

    /**
     * Fill {@code jdk.internal.misc.VM.savedProps}, the snapshot stock {@code System.initPhase1} hands to
     * {@code VM.saveProperties}. joe-ng never runs initPhase1, so the field stayed null and
     * {@code VM.getSavedProperty} THREW "Not yet initialized" -- inside {@code ThreadLocalRandom.<clinit>}, which
     * reads {@code java.util.secureRandomSeed}. That left every TLR static null, and stock
     * {@code ConcurrentHashMap} NPE'd in {@code addCount} the first time two threads contended.
     *
     * <p>A SEPARATE {@code Properties} with the same standard entries, NOT the {@code System.props} object: stock
     * keeps the two apart, so a {@code System.setProperty} in {@code main} is invisible to
     * {@code getSavedProperty}. Sharing one object would let a program's runtime property change what java.base
     * read as its launch configuration -- a silent difference, which is the kind this project removes.
     *
     * <p>Runs at LAUNCH beside {@link #seedSystemProps} because it compiles the same {@code Properties} methods,
     * which the per-batch seed site must not do. A missing {@code savedProps} cell is REPORTED: it means VM was
     * not registered at launch and a later {@code getSavedProperty} would throw.
     */
    /**
     * Raise {@code jdk.internal.misc.VM.initLevel} to {@code SYSTEM_BOOTED} (4) before {@code main}, where stock
     * {@code System.initPhase3} leaves it. This VM runs no initPhase, so the level stayed 0 and
     * {@code VM.isBooted()} answered false for ever: {@code System.getLogger} handed out a BootstrapLogger that
     * queues every record until a boot that never came, and {@code jdk.internal.util.Exceptions.setup} refused to
     * run, so exception texts omitted the host.
     *
     * <p>initPhase3 also installs the system class loader and the main thread's context loader before setting
     * the level. Here those two happen on FIRST USE instead (stock ClassLoader.getSystemClassLoader, the Thread
     * overlay's getContextClassLoader): doing them at launch -- tried, measured -- put the stock loader stack's
     * whole reachable closure (+567 classes, launch ~2.6x on QEMU) in front of every program. This used to claim
     * the two were "already in place here"; neither was (LoaderProbe: "context loader is the app loader =
     * false"). Set by storing the cell, as the other launch seeds do: nothing can be waiting on the level's lock
     * yet. Last of the seeds, because every one before it is part of what "booted" means.
     */
    static void seedBooted()
    {
        long slot = Loader.staticSlotOf(Magic.bytes("jdk/internal/misc/VM"), Magic.bytes("initLevel"));
        if (slot == 0L)
        {
            reportPropsGap(Magic.bytes("jdk/internal/misc/VM.initLevel has no static cell (isBooted stays false)"));
            return;
        }
        Address.fromLong(slot).store(4L);
    }

    /**
     * Run {@code java.lang.ref.Reference}'s initializer before the program does, as HotSpot does during startup.
     *
     * <p>That initializer is the ONLY writer of {@code SharedSecrets.javaLangRefAccess}, and the reader takes it
     * on trust: {@code Runtime.runFinalization} is {@code SharedSecrets.getJavaLangRefAccess().runFinalization()}
     * with no null check, because on HotSpot the class is initialized before any program runs ({@code
     * System.initPhase1} then calls {@code getJavaLangRefAccess().startThreads()} on it). Here nothing touched
     * Reference unless the program made a Reference, so {@code System.runFinalization()} was an NPE inside
     * java.base (RuntimeProbe; the zip test FinalizeInflater calls it). Running the stock initializer, rather
     * than seeding the slot, is rule 2: the class sets up its own statics.
     *
     * <p>{@code startThreads} is still NOT called: the Reference Handler would wait on a pending list this
     * collector never fills, and the Finalizer thread is pointless with finalization disabled.
     *
     * <p>Last of the seeds for the reason {@code seedSystemProps} is placed late: an initializer COMPILES, which
     * re-points the loader's compile cursor, and no seed after it may rely on that cursor.
     */
    static void seedRefAccess()
    {
        int reg = Loader.classRegByNameBytes(Magic.bytes("java/lang/ref/Reference"));
        if (reg < 0)
        {
            reportPropsGap(Magic.bytes("java/lang/ref/Reference is not registered (Runtime.runFinalization NPEs)"));
            return;
        }
        Loader.ensureClinit(reg);
    }

    static void seedSavedProps()
    {
        Address slot = Address.fromLong(Loader.staticSlotOf(Magic.bytes("jdk/internal/misc/VM"), Magic.bytes("savedProps")));
        if (slot.isZero())
        {
            reportPropsGap(Magic.bytes("jdk/internal/misc/VM.savedProps has no static cell (getSavedProperty throws)"));
            return;
        }
        if (slot.loadLong() != 0L)
        {
            return;
        }
        int pi = Loader.classIndexByName(Magic.bytes("java/util/Properties"));
        if (pi < 0)
        {
            reportPropsGap(Magic.bytes("VM.savedProps: java/util/Properties is not registered"));
            return;
        }
        long obj = Reflection.allocInstance(Loader.clTab[pi].type);
        int ctor = Reflection.constructorResolve(Loader.clTab[pi].type, 0);
        if (obj == 0L || ctor < 0 || Loader.rgTab[ctor].buf == 0L)
        {
            reportPropsGap(Magic.bytes("VM.savedProps: java/util/Properties could not be constructed"));
            return;
        }
        Magic.call2(Loader.rgTab[ctor].buf, obj, 0L);
        seedStandardProps(obj, pi);
        slot.store(obj);
    }

    /**
     * Say WHY the system properties could not be seeded.
     *
     * <p>Every exit on this path used to be silent, and that silence is the whole problem: an empty property
     * map does not fail where it is created, it fails much later inside a library that reads a property
     * unconditionally and does not expect null. picocli's {@code Ansi.isWindows()} does
     * {@code System.getProperty("os.name").toLowerCase()}; its {@code trimLineSeparator} does
     * {@code result.endsWith(System.getProperty("line.separator"))}. Both NPE deep inside the library, naming
     * nothing the VM did, and the two look like unrelated bugs while sharing one cause.
     */
    private static void reportPropsGap(byte[] why)
    {
        Uart.write(Magic.bytes("\n  SYSTEM PROPERTIES NOT SEEDED (every getProperty answers null): "));
        Uart.write(why);
        Uart.putc(0x0A);
    }

    /**
     * Fill {@code System.lineSeparator}, which is a STATIC FIELD and not a property lookup.
     *
     * <p>Seeding {@code System.props} is not enough and the distinction is not academic: stock
     * {@code System.lineSeparator()} returns a private static that {@code initPhase1} copies out of the
     * properties -- and joe-ng never runs initPhase1. So the map said "\n" while the accessor still answered
     * NULL, and {@code PrintWriter.newLine()} wrote a null separator straight into {@code Writer.write},
     * NPE-ing inside java.base with the real cause two frames up. That is how JUnit's console output failed.
     */
    private static void seedLineSeparator()
    {
        Address slot = Address.fromLong(Loader.staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("lineSeparator")));
        if (!slot.isZero() && slot.loadLong() == 0L)
        {
            slot.store(Loader.guestString(Magic.bytes("\n")));   // Uart.putc makes this CRLF for the console
        }
    }

    private static void seedStandardProps(long props, int propsClass)
    {
        // NOT through the method registry: `rgTab` holds one entry per COMPILED method, and bodies compile on
        // FIRST CALL -- this runs during loader init, so nothing has called setProperty and it is not in there
        // at all. Asking the registry could therefore only ever succeed by accident, in a closure that had
        // already compiled it for some other reason. That is exactly the shape of the bug this replaced: the
        // launcher image worked and a smaller one left EVERY property null.
        long buf = Loader.bufBySigU(Loader.utf8Blob(Magic.bytes("java/util/Properties")),
                Loader.utf8Blob(Magic.bytes("setProperty")),
                Loader.utf8Blob(Magic.bytes("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;")));
        if (buf == 0L)
        {
            // A BODY COMPILES ON FIRST CALL, and nothing has called setProperty yet: this runs during loader
            // init, long before any program. So a zero buffer here does NOT mean "unavailable", it means "not
            // yet" -- and treating the two alike is what left EVERY property null in any closure that had not
            // already compiled it for some other reason. That is the "works in one closure, broken in
            // another" signature: the launcher image happened to compile it and worked, while a smaller one
            // NPE'd inside picocli reading os.name.
            //
            // The registry entry already holds the three Utf8-shaped pointers compileSigOnDemand wants
            // (`base + *Off` is a u2 length followed by bytes), so this is the method's own identity, not a
            // reconstruction of it.
            buf = Loader.compileSigOnDemand(Loader.utf8Blob(Magic.bytes("java/util/Properties")),
                    Loader.utf8Blob(Magic.bytes("setProperty")),
                    Loader.utf8Blob(Magic.bytes("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;")));
        }
        if (buf == 0L)
        {
            reportPropsGap(Magic.bytes("java/util/Properties.setProperty would not compile"));
            return;
        }
        putProp(buf, props, Magic.bytes("os.name"), Magic.bytes("joe-ng"));
        putProp(buf, props, Magic.bytes("os.arch"), Magic.bytes("aarch64"));
        putProp(buf, props, Magic.bytes("os.version"), Magic.bytes("1.0"));
        putProp(buf, props, Magic.bytes("java.vm.name"), Magic.bytes("joe-ng"));
        putProp(buf, props, Magic.bytes("java.vm.vendor"), Magic.bytes("joe-ng"));
        putProp(buf, props, Magic.bytes("java.version"), Magic.bytes("26"));
        putProp(buf, props, Magic.bytes("java.specification.version"), Magic.bytes("26"));
        putProp(buf, props, Magic.bytes("line.separator"), Magic.bytes("\n"));
        putProp(buf, props, Magic.bytes("file.separator"), Magic.bytes("/"));
        putProp(buf, props, Magic.bytes("path.separator"), Magic.bytes(":"));
        putProp(buf, props, Magic.bytes("file.encoding"), Magic.bytes("UTF-8"));
        putProp(buf, props, Magic.bytes("native.encoding"), Magic.bytes("UTF-8"));
        putProp(buf, props, Magic.bytes("user.dir"), Magic.bytes("/"));
        putProp(buf, props, Magic.bytes("user.name"), Magic.bytes("root"));
        putProp(buf, props, Magic.bytes("java.io.tmpdir"), Magic.bytes("/tmp"));
        // THE SET BELOW IS NOT AD HOC: jdk/internal/util/StaticProperty.<clinit> reads fourteen keys through a
        // private getProperty(props, key) that THROWS InternalError("null property: " + key) on a null, so any
        // one of them missing halts the VM as soon as that initializer runs. Under the run-all-clinits rule it
        // always runs. The list was taken from the JDK 26 source in one pass rather than one key per ten-minute
        // boot -- `java.home` was simply the first of six to be reached.
        putProp(buf, props, Magic.bytes("java.home"), Magic.bytes("/"));
        putProp(buf, props, Magic.bytes("user.home"), Magic.bytes("/"));
        // The three console encodings and sun.jnu.encoding: everything this VM reads or writes is UTF-8 (the
        // String fast paths compare against UTF_8.INSTANCE), so naming anything else here would be a claim the
        // rest of the VM does not honour.
        putProp(buf, props, Magic.bytes("stdout.encoding"), Magic.bytes("UTF-8"));
        putProp(buf, props, Magic.bytes("stderr.encoding"), Magic.bytes("UTF-8"));
        putProp(buf, props, Magic.bytes("stdin.encoding"), Magic.bytes("UTF-8"));
        putProp(buf, props, Magic.bytes("sun.jnu.encoding"), Magic.bytes("UTF-8"));
        // The class path: stock ClassLoaders.<clinit> builds the application loader's URLClassPath from it, which
        // is how ClassLoader.getResource answers a URL into the jar. The init file's classpath= jar, or "" (stock's
        // default with no class path given).
        putProp(buf, props, Magic.bytes("java.class.path"), JarFs.path());
    }

    /** One {@code props.setProperty(key, value)} through the ordinary call convention. */
    private static void putProp(long buf, long props, byte[] key, byte[] value)
    {
        long[] args = new long[3];
        args[0] = props;
        args[1] = Loader.guestString(key);
        args[2] = Loader.guestString(value);
        Magic.callN(buf, Magic.addrOf(args) + 24L);     // args[] elements start at +24
    }

    /**
     * Install {@code System.out} / {@code System.err} as STOCK {@link java.io.PrintStream}s over the UART,
     * built by {@code jdk.internal.misc.MetalStdStreams.newStream()} (pulled by {@link #pullSupportClasses}).
     *
     * <p>Stock {@code System.initPhase1} that builds them is native-heavy and unrunnable, so the VM calls the
     * helper once per empty slot and stores what it returns. It used to drop a BARE instance of a
     * hand-written PrintStream overlay into each slot -- no constructor, no state -- which is why that overlay
     * had to be field-free and why it silently dropped every stock member it did not declare.
     *
     * <p>An ENSURE, not a set: a slot already holding a LIVE stream keeps its identity (a launched program, or
     * JUnit's {@code System.setOut}, may have replaced it), and nothing happens if the helper is not
     * registered yet. A stream built before a {@link #resetLoader} is NOT live -- see {@link #stdSlotNeedsSeed}.
     *
     * <p>It COMPILES a method, so it runs after {@link #seedSystemProps} for the reason recorded there: a
     * compile rebuilds the loader's cursor, and a seed after it would stand on the wrong class's state.
     */
    static void seedSystemStreams()
    {
        long outSlot = Loader.staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("out"));
        long errSlot = Loader.staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("err"));
        boolean needOut = stdSlotNeedsSeed(outSlot, STD_OUT);
        boolean needErr = stdSlotNeedsSeed(errSlot, STD_ERR);
        if (!needOut && !needErr)
        {
            return;
        }
        if (Loader.classIndexByName(Magic.bytes("jdk/internal/misc/MetalStdStreams")) < 0)
        {
            return;                                     // not registered yet -- a later batch seeds it
        }
        Address cls = Loader.utf8Blob(Magic.bytes("jdk/internal/misc/MetalStdStreams"));
        Address name = Loader.utf8Blob(Magic.bytes("newStream"));
        Address desc = Loader.utf8Blob(Magic.bytes("()Ljava/io/PrintStream;"));
        long buf = Loader.bufBySigU(cls, name, desc);
        if (buf == 0L)
        {
            buf = Loader.compileSigOnDemand(cls, name, desc);  // bodies compile on first call; this IS the first
        }
        if (buf == 0L)
        {
            // Say so: a null System.out does not fail here, it fails as an NPE at the program's first println.
            Uart.write(Magic.bytes("\n  SYSTEM STREAMS NOT SEEDED: MetalStdStreams.newStream would not compile\n"));
            return;
        }
        if (needOut)
        {
            Address.fromLong(outSlot).store(Magic.call0(buf));
            setStdGen(STD_OUT);
        }
        if (needErr)
        {
            Address.fromLong(errSlot).store(Magic.call0(buf));
            setStdGen(STD_ERR);
        }
    }

    /**
     * Point {@code System.out}/{@code err}/{@code in} at {@code ref} -- the body of stock's {@code setOut0}
     * family, which are natives precisely because those fields are {@code final} and the JDK reassigns them
     * from the VM side.
     *
     * <p>Here the field is an ordinary static cell that {@link #seedSystemStreams} already writes at boot, so
     * redirection is one store. JUnit's {@code StandardStreamsHandler} calls {@code System.setOut} to CAPTURE
     * a test's output; without the native the call resolved nowhere and surfaced as a DENYLIST TRAP naming a
     * list System is not on.
     *
     * <p>No {@code checkIO}: there is no SecurityManager on metal, and stock's own check is a no-op now.
     */
    static void setStdStream(byte[] name, ObjectReference ref)
    {
        long slot = Loader.staticSlotOf(Magic.bytes("java/lang/System"), name);
        if (slot != 0L)
        {
            Address.fromLong(slot).store(ref);
            // A stream the PROGRAM installed is current: the seed must not replace it this generation.
            if (name[0] == 'o')
            {
                setStdGen(STD_OUT);
            }
            else if (name[0] == 'e')
            {
                setStdGen(STD_ERR);
            }
            else
            {
                setStdGen(STD_IN);
            }
        }
    }

    private static int stdGen(int which)
    {
        if (which == STD_OUT)
        {
            return outGen;
        }
        return which == STD_ERR ? errGen : inGen;
    }

    private static void setStdGen(int which)
    {
        if (which == STD_OUT)
        {
            outGen = Loader.loaderGen;
        }
        else if (which == STD_ERR)
        {
            errGen = Loader.loaderGen;
        }
        else
        {
            inGen = Loader.loaderGen;
        }
    }

    /**
     * True if a {@code System.out}/{@code err}/{@code in} slot must be (re)seeded: it is empty, or what it
     * holds was installed under an EARLIER loader generation.
     *
     * <p>The second case is a stream built before a {@link #resetLoader}. The static cell survives the reset
     * (it is the baked {@code System} block), so the object does too -- built from classes of a registry
     * that no longer exists. Ordinary vtable calls through it still work, which is why it went unnoticed for
     * programs at a time; the first call site needing LATE resolution asks the new registry about an old Type
     * and stops at {@code DISPATCH ON UNREGISTERED TYPE}. Measured: the demo suite died there at
     * {@code DefaultIfaceDemo}'s first {@code println}, eleven programs after the reset that orphaned it.
     *
     * <p>It is a GENERATION and not a "is its class registered?" test, and that was learned the hard way: the
     * first cut asked about the stream object's own Type, and {@code PrintStream} is a BAKED class whose Type
     * every new registry adopts again -- so the stale stream passed, while the {@code StreamEncoder} inside it,
     * a demand-loaded class, was the orphan. Asking about one object of a graph cannot answer for the graph.
     */
    private static boolean stdSlotNeedsSeed(long slot, int which)
    {
        if (slot == 0L)
        {
            return false;                               // no cell: nothing can be seeded
        }
        return Address.fromLong(slot).loadLong() == 0L || stdGen(which) != Loader.loaderGen;
    }

    /**
     * Install {@code System.in} as an EMPTY stream — a {@code java.io.ByteArrayInputStream} over a zero-length
     * array, so it reads as immediate end-of-file. There is no console input on metal, but the field being
     * NULL is worse than it being empty: stock code passes {@code System.in} straight into a constructor that
     * null-checks it, so a test doing {@code new ZipInputStream(System.in)} died with a bare
     * NullPointerException before reaching anything it meant to test (java/util/zip/ZipInputStream/Skip).
     *
     * <p>Seeded rather than constructed, like {@link #seedSystemStreams}: the fields are filled directly at
     * their registered offsets, so no {@code <init>} has to run. Reading answers -1 without ever touching the
     * buffer, since {@code count} is 0.
     */
    static void seedSystemIn()
    {
        long inSlot = Loader.staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("in"));
        if (!stdSlotNeedsSeed(inSlot, STD_IN))
        {
            return;                                     // unknown slot, or a live stream already there
        }
        if (Console.stdinOn != 0)
        {
            int ci = Loader.classIndexByName(Magic.bytes("jdk/internal/misc/MetalConsoleIn"));
            if (ci >= 0)                                // field-free: the TIB is the whole object
            {
                Address cobj = Heap.alloc(16 + Loader.clTab[ci].fieldCount * 8);
                cobj.store(Loader.clTab[ci].tib);
                Address.fromLong(inSlot).store(cobj);
                setStdGen(STD_IN);
                return;
            }
            Uart.write(Magic.bytes("\n  stdin=uart: MetalConsoleIn not loaded, System.in stays empty\n"));
        }
        int bi = Loader.classIndexByName(Magic.bytes("java/io/ByteArrayInputStream"));
        if (bi < 0)
        {
            return;                                     // not in this batch: leave the slot as it was
        }
        long bufOff = Loader.instanceFieldOffset(bi, Magic.bytes("buf"));
        long posOff = Loader.instanceFieldOffset(bi, Magic.bytes("pos"));
        long cntOff = Loader.instanceFieldOffset(bi, Magic.bytes("count"));
        if (bufOff < 0L || posOff < 0L || cntOff < 0L)
        {
            return;
        }
        Address obj = Heap.alloc(16 + Loader.clTab[bi].fieldCount * 8);
        obj.store(Loader.clTab[bi].tib);
        obj.plus(bufOff).store(Heap.allocArray(0, 1));   // empty byte[]; never read (count == 0)
        obj.plus(posOff).store(0L);
        obj.plus(cntOff).store(0L);
        Address.fromLong(inSlot).store(obj);
        setStdGen(STD_IN);
    }

    static void seedVmInitLock()
    {
        if (sdVmLock)
        {
            return;
        }
        int oi = Loader.classIndexByName(Magic.bytes("java/lang/Object"));
        if (oi < 0)
        {
            return;
        }
        Address slot = Address.fromLong(Loader.staticSlotOf(Magic.bytes("jdk/internal/misc/VM"), Magic.bytes("lock")));
        if (!slot.isZero() && slot.loadLong() == 0L)
        {
            Address inst = Heap.alloc(16);                          // header only: a monitor needs no fields
            inst.store(Loader.clTab[oi].tib);
            slot.store(inst);
            sdVmLock = true;
        }
    }

    static void seedJavaIOAccess()
    {
        if (sdJio)
        {
            return;
        }
        int mi = Loader.classIndexByName(Magic.bytes("jdk/internal/access/MetalJavaIOAccess"));
        if (mi < 0)
        {
            return;
        }
        long slot = Loader.staticSlotOf(Magic.bytes("jdk/internal/access/SharedSecrets"), Magic.bytes("javaIOAccess"));
        if (slot != 0L)
        {
            Address inst = Heap.alloc(16 + Loader.clTab[mi].fieldCount * 8);   // field-free -> header only
            inst.store(Loader.clTab[mi].tib);                 // TIB (itable dir for the console() dispatch)
            Address.fromLong(slot).store(inst);
            sdJio = true;
        }
    }

    static void seedJavaLangAccess()
    {
        if (sdJla)
        {
            return;
        }
        int mi = Loader.classIndexByName(Magic.bytes("jdk/internal/access/MetalJavaLangAccess"));
        if (mi < 0)
        {
            return;
        }
        long slot = Loader.staticSlotOf(Magic.bytes("jdk/internal/access/SharedSecrets"), Magic.bytes("javaLangAccess"));
        if (slot != 0L)
        {
            Address inst = Heap.alloc(16 + Loader.clTab[mi].fieldCount * 8);   // field-free -> header only
            inst.store(Loader.clTab[mi].tib);                 // TIB (itable dir for getEnumConstantsShared dispatch)
            Address.fromLong(slot).store(inst);
            sdJla = true;
        }
    }
}
