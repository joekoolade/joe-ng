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
import magic.AddressArray;
import magic.Magic;

import static vm.ClassBytes.insnLen;
import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Class initialization (JikesRVM: {@code RVMClass.initialize()}; JVMS 5.5 and JLS 12.4.2): finding and compiling a
 * class's {@code <clinit>}, running it once with the per-class claim/await protocol across tasks, the superclass
 * and dependency ordering, the init GUARDS compiled code consults (and their memo), the pending-initialization queue
 * a lazy compile drains, image-baked ("boot inited") classes, and the ERRONEOUS state with its
 * {@code ExceptionInInitializerError}/{@code NoClassDefFoundError}. Split out of {@link Loader}.
 */
final class ClassInitializer
{
    private ClassInitializer()
    {
    }

    /**
     * Cheap pre-check that a {@code <clinit>} won't trip a FATAL compiler gap (symbols.fail HALTS, so we can't
     * just try to compile and recover). Currently rejects {@code ldc}/{@code ldc_w} of a constant the Baseline
     * compiler doesn't handle (anything but Integer/Float/String) — the common gap in stock initializers
     * (Math, Arrays, ... ldc a Class/long/double/MethodType). Such a class is treated as unrunnable and left to
     * seeding. Extend as other fatal gaps surface. (Native-calling <clinit>s compile fine but wild-branch at
     * run time, so those stay in the name blocklist.)
     */
    /** Rule 2: every class initializer runs. Set false only to recover the old gate for a bisect. */
    static final boolean RUN_ALL_CLINITS = true;
    /**
     * True if the current class's {@code <clinit>} must NOT be run — it calls natives / reads system properties
     * / needs JVM services absent on metal. We run every other class's initializer by default and seed only
     * these (provideKnownStatics). Grows as new unrunnable stock initializers surface.
     */
    /** Entry addresses of this batch's compiled {@code <clinit>}s, in load order (run after patchRelocs). */
    static long[] clinitEntry;   // compiled entry of initializer i, or 0 until clinitEntryOf compiles it
    static long[] clinitCode;    // its BYTECODE (captured at load); non-zero == this slot is enqueued
    static int[] clinitCodeLen;
    static int[] clinitDescOff;
    static int[] clinitStatic;
    static int[] clinitLocals;
    static int[] clinitPd;       // pd-blob index of each enqueued <clinit> (for dependency-ordered running)
    // The record's OWN blob identity. clinitPd indexes the pending-blob list, which resetLoader rebuilds every
    // batch and which never re-lists a SETTLED blob -- so pd is meaningless to anything that outlives the batch
    // that enqueued the record. Blob base + class-name offset are properties of the classfile itself, so they
    // stay valid, and every use below that only wanted "which blob / which class" reads these instead.
    static AddressArray clinitBase;    // blob base of initializer i
    static int[] clinitNameOff;  // its class's name Utf8 offset within that blob
    static int clinitN;
    static int clinitFdFirst;    // index of java/io/FileDescriptor's enqueued <clinit> (run first), or -1
    static int clinitRunFrom;    // watermark: clinits [0,clinitRunFrom) already ran (incremental forName load)
    static int[] clinitRan;      // 1 once initializer i has actually executed (batch sweep OR lazy barrier)
    // CLAIMED, BUT NOT YET RUNNING. joe-ng's <clinit> path does three things JVMS 5.5 does not put between
    // marking a class in-flight (step 6) and executing its body (step 9): a dependency pre-pass, a COMPILE,
    // and a second dependency pre-pass. Marking `clinitRan` at the top made every one of those a window in
    // which the class reads as in-flight while its body has executed nothing -- so a nested active use took
    // step 3's recursive-request return and got null. That is what made a mutually-referencing pair fail in
    // ONE direction only: Early2 assigns EARLY at bytecode 3 and touches Late2 at 6, but the pre-pass ran
    // Late2 first and its constructor's read of Early2.EARLY hit the empty window.
    //
    // So `clinitRan` now means RUNNING-OR-RUN and is set immediately before the call, and `clinitBusy` marks
    // the dep/compile phase separately -- purely to stop that phase re-entering itself.
    static int[] clinitBusy;
    // JVMS 5.5 STEP 2: "If C is being initialized by SOME OTHER THREAD, block until it completes." The record
    // carries WHO claimed it (task id + 1, so 0 is none) and whether its body has FINISHED -- normally or by a
    // throw. Without them a second task found `clinitRan` already set, took it as done, and read the statics
    // the first task had not assigned yet: stock ConcurrentHashMap's contended counter NPE'd in
    // ThreadLocalRandom.getProbe on exactly that, four threads entering TLR.<clinit> at once.
    static int[] clinitOwner;
    static int[] clinitDone;
    /**
     * The IMAGE statics blocks whose {@code <clinit>} has completed, for the life of the VM -- deliberately NOT
     * reset by {@link Loader#resetLoader}. A baked class adopts the writer's statics block, so every loader world shares
     * ONE home for its statics, which makes it the analogue of an OpenJDK boot-loader class: initialized at most
     * once per VM (JVMS 5.5), never once per world. Re-running it in a later world re-ran Locale's initializer
     * over statics still holding objects whose code the reset had discarded ({@code stub outlived its table}).
     */
    private static long[] bootInited;
    private static int bootInitedN;
    // PRECISE per-<clinit> init dependencies: the classes the initializer BODY actively touches
    // (getstatic/putstatic/invokestatic owner, new/anewarray class, ldc Class literal), name Utf8 offsets in the
    // owning blob's gView.base. Used by clinitDepBlocked INSTEAD of the whole-constant-pool dp table, whose field-type /
    // signature / enclosing-class refs create spurious cycles (e.g. an inner enum's cp names its outer class, so
    // the outer's <clinit> and the inner's appear mutually dependent). The <clinit> bytecode names exactly the
    // classes whose initialization it needs.
    static int[] clDepOff;       // flat: dependency class name Utf8 offsets
    static int[] clDepStart;     // per enqueued <clinit>: start index into clDepOff
    static int[] clDepN;         // per enqueued <clinit>: dependency count
    static int clDepTop;         // running top of clDepOff
    /** Run each enqueued {@code <clinit>} now that patchRelocs has fixed every cross-class call. */
    /**
     * SHOUT IF AN INITIALIZER IS ABOUT TO RUN WITH THE LOADER LOCK HELD -- the deadlock this batch path is
     * one edit away from.
     *
     * <p>{@code loadAll} takes the loader lock for the whole batch, and this loop is the only thing inside it
     * that can run GUEST CODE. {@code clinitEntryOf}, {@code compileMethodOnDemand}, {@code
     * compileSigOnDemand} and the deferred-{@code <init>} path all state the same invariant in terms: "the
     * lock covers the COMPILE only, never the running of an initializer -- a {@code <clinit>} can block on a
     * monitor or spawn threads, and holding the loader lock across that would deadlock any task that needs
     * to compile before it can release."
     *
     * <p><b>TODAY THAT CANNOT HAPPEN, AND THAT IS THE ONLY REASON THIS IS A REPORT RATHER THAN A FIX.</b>
     * {@code clinitEagerKept} returns false unconditionally, so {@code lazyClinitGated} is true for every
     * class, so the deferral arm above always fires and sets {@code progress}; the three {@code Magic.call0}
     * sites in this method are UNREACHABLE. Measured rather than read off the source: {@code runcl} is
     * 4.813ms across a 139-batch launcher boot and 245us across the demo suite -- this loop's own
     * bookkeeping over 531 initializers, not initializer execution.
     *
     * <p><b>THE HAZARD IS LIVE, AND THIS FILE SAID OTHERWISE FOR A DAY.</b> The earlier text here read "the
     * hazard is LATENT, NOT LIVE ... only because {@code clinitEagerKept} returns false unconditionally".
     * That gate covers {@code runClinits} and NOTHING ELSE. {@code ensureClinit} is a separate route, and it
     * runs an initializer under the lock on EVERY boot:
     *
     * <pre>
     *   lazyCompile -&gt; VM.loaderLock(LOCK_LAZY) -&gt; lazyCompileLocked
     *     -&gt; drainPendingInit -&gt; ensureClinit -&gt; runPendingClinit -&gt; Magic.call0   &lt;- GUEST CODE
     * </pre>
     *
     * The Pi printed that stack itself, unprompted, in the routine batch-21 {@code ProcessImpl} trap. The
     * claim was reached by checking the one gate that was pointed out and never asking whether it covered
     * every path to {@code call0} -- it covers one of four.
     *
     * <p><b>AND THE GUARD WAS WIRED EXCLUSIVELY TO THE DEAD SITES, which is worse than not having it.</b> Its
     * three call sites all sat inside the {@code clinitEagerKept} region, so a silent boot re-confirmed only
     * that the gate is false -- something already known statically. That is exactly the "an instrument that
     * cannot fire looks identical to a condition that never happens" trap this file names. It is installed on
     * the LIVE site now, which is why it needs a budget: it fires on essentially every initializer.
     *
     * <p>AND SUSPENDING THE LOCK HERE WOULD BE UNSOUND, which is why the obvious fix is not the fix. The
     * lock is RECURSIVE: {@code loadAll} routinely runs nested inside {@code lazyCompile}'s hold, so
     * dropping to depth 0 around a {@code call0} would release a lock the OUTER frame is relying on to keep
     * the static compile context to itself -- trading a latent deadlock for a live clobber. Whatever closes
     * this has to move the clinit phase out of the batch, not punch a hole in the middle of it.
     */
    /** How many of these to print. It fires on nearly every initializer, so an unbudgeted report would BE the
     *  failure it reports -- this file already records a diagnostic flooding the UART and starving the run it
     *  was meant to diagnose. The COUNT keeps accruing past the cap so the total is still honest. */
    private static final int CLINIT_LOCK_SAID_MAX = 8;
    private static int clinitLockSaid;
    static int clinitLockN;                              // initializers run under the lock, cumulative
    /**
     * Initialize the class named by the absolute Utf8 run {@code clsU}. The runtime half of the active-use
     * trigger.
     *
     * <p>An UNREGISTERED name is not an error and must not report: the class has simply not been pulled yet,
     * and every path that pulls one (deferred new, link stub, late virtual resolve) initializes it itself.
     * Reporting would cry wolf on every healthy boot -- this file's own standing rule for an instrument.
     */
    /** TEMPORARY (init-guard diagnosis): report every guard EMITTED and every guard that FIRES. */
    static final boolean INIT_GUARD_LOG = false;
    /**
     * JVMS 5.5, AND NOTHING ELSE, ONCE THE RUNTIME IS UP: a class is initialized at an EXECUTED active use -- the
     * compiled init guard -- and never because a compile NAMED it. The dependency pre-walk
     * ({@link #initClinitDeps}) and the compile drain ({@link #drainPendingInit}) both approximated 5.5 from
     * compile-time references, and an approximation picks the WRONG member of a mutually-referencing pair:
     * <ul>
     *   <li>{@code ConstantDescs} was claimed, and its dependency phase ran {@code PrimitiveClassDescImpl}'s
     *       initializer before ConstantDescs' own body, which reads {@code BSM_PRIMITIVE_CLASS} back -- still
     *       null -- so {@code int[].class.describeConstable()} died in a {@code requireNonNull}
     *       (ConstantDescOrderProbe);</li>
     *   <li>with the pre-walk alone removed, compiling {@code Early2}'s initializer drained {@code Late2}, whose
     *       constructor then read {@code Early2.EARLY2} from a class already claimed: {@code ctor saw = null}
     *       (ClinitOrderProbe order 2).</li>
     * </ul>
     * Both are gone with the guard as the only trigger, and nothing regressed: the suite ran the same 348
     * initializers it ran before. BEFORE the runtime is up the eager paths stay: the boot self-test's legacy
     * {@code loadParseInt} path runs before {@link Loader#bringUpRuntime}, where {@code ensureInitFor} skips guards by
     * design, and it relies on them -- disabled there, {@code Character.digit} read a null
     * {@code CharacterDataLatin1.instance}.
     */
    static final boolean GUARDS_ONLY = true;
    /**
     * MEMO OF INIT GUARDS THAT HAVE ALREADY BEEN ANSWERED. A guard is emitted when a method compiles before the
     * class it uses is initialized, and it stays in the code for ever: every later execution called
     * regBySigU -- a byte-wise hash of the name and a byte-compare walk of its whole bucket chain -- only to
     * find the class initialized. MEASURED (qprof, SslEngineProbe, QEMU 2026-11-06): in a WARM TLS session,
     * with nothing left to load or compile, ensureInitByName -> regBySigU -> classRegAt was the largest
     * single cost, because guards sit inside the handshake's hot loops.
     *
     * <p>A hit means "the class whose name Utf8 is at clsU was fully initialized", and that answer is sound
     * because INITIALIZATION IS MONOTONE -- only a class that is initialized, whose superclass chain is done
     * and that is not erroneous is ever entered. WHAT IS NOT MONOTONE IS THE ADDRESS: clsU points into the
     * referring class's blob, blobs are collected by reachability, and a new blob can reuse the address for a
     * different, UNINITIALIZED class. A stale hit would then skip an initializer -- the silent null static.
     * So each entry carries the collection EPOCH it was made in ({@link VMGc#collections}, which advances
     * under stop-the-world before the sweep frees anything) and is only a hit in that same epoch; memory
     * freed by a collection is reused only after it. Key and epoch are ONE 64-bit word (epoch << 32 | clsU)
     * so another core can never read a torn pair; an address above 4 GB is simply never memoised.
     * {@link Loader#resetLoader} clears the table: a new loader world re-registers classes.
     */
    private static final int IDMEMO = 4096;
    private static final int IDMEMO_PROBE = 8;
    private static long[] idMemo = new long[IDMEMO];
    // Per-COMPILE memo for the init-guard decision, keyed by class-name address. Small and linear: it is
    // consulted a handful of times per method and cleared per compile, so an index would cost more than it
    // saves.
    private static final int IGMEMO = 64;
    private static long[] igName = new long[IGMEMO];
    private static boolean[] igAns = new boolean[IGMEMO];
    private static int igN;
    /** Watch a named class's STATIC CELLS around its initializer, and again whenever any watched class is
     *  initialised later. The launcher stores a GroupValidationResult whose `type` is null even though the
     *  enum's initializer runs FIRST and the cells resolve consistently -- so the question is whether the
     *  values are ever written, and whether they are still there afterwards. */
    private static final boolean CELL_WATCH = false;
    static final int MAXPENDINIT = 64;
    static int[] lzInitReg;
    static int lzInitN;
    static int lzInitDropped;                    // active uses discarded for want of room (0 = none)
    static int lzInitWant;                       // DISTINCT classes this compile wants initialized
    static int lzInitWantMax;                    // ... and the most any one compile has wanted
    static boolean lzInitFullSaid;
    // Said ONCE if an active use is ever noted outside a bracketed compile. See noteInitNeeded: this
    // replaced the ctor-init mechanism, which existed only because `<init>` used to compile at load time
    // with lzCompiling false. That mechanism is retired -- and its ordering was WEAKER than the lazy path's
    // (it waited for the OWNING class to initialize, where the lazy path initializes active uses strictly
    // before the body can run, which is the JVMS 5.5 order), so it must not come back as a shortcut.
    //
    // MEASURED before the retirement rather than assumed: with its own CTOR_TRACE armed, a demo-suite boot
    // recorded ZERO edges across 3,481 drain calls -- reached constantly, never with anything to record.
    private static boolean unbracketedInitSaid;

    /**
     * A registry entry whose blob pointer is 0 — the shape a batch-reclaimed entry takes, since the reclaim
     * zeroes the memory it rewinds. Name the caller and the class being compiled, then halt. Called BEFORE
     * the read, so {@code lr} still holds the caller's return address; after the fault it is gone, and the
     * raw abort names only the two-line helper that happened to do the load.
     */
    /**
     * Walk the arming table and report the first entry whose blob has gone to zero since it was inserted.
     * Paired with the insertion guard: if the entry was born valid and is zero here, something wrote over
     * it, and the isolation runs have already ruled out the collector.
     */
    /** Halt NAMED if {@code entry} is a code buffer the collector swept — the initializer is about to be
     *  called and would execute zeros, reported minutes later from an unnamed address. */
    private static void checkClinitEntry(Address entry, int idx)
    {
        if (Heap.codeBlockFreeAt(entry) == 1)
        {
            Uart.write(Magic.bytes("\nCLINIT ENTRY WAS SWEPT idx="));
            VM.printDec(idx);
            Uart.write(Magic.bytes(" entry="));
            VM.printHex(entry.toLong());
            Uart.putc(0x0A);
            VMGc.reportSweptPc(entry.toLong());
            Loader.printCurrentClass();
            Uart.putc(0x0A);
            while (true) { Magic.wfe(); }
        }
    }

    /**
     * Run the class's static initializer if it has one. Called after
     * {@link Loader#parseFields} (so the statics block exists) and before the entry
     * method, so {@code <clinit>}'s {@code putstatic}s land before first use. It is
     * just another method the loader compiles and runs; it dirties the seek key, so
     * the caller sets the entry seek afterwards.
     */
    static void runClinit(Address bytes)
    {
        if (clinitBlocked())
        {
            return;    // unrunnable <clinit> (calls natives / reads properties): its statics are seeded instead
        }
        Loader.seek(0x3C636C696E69743EL, 8, 0x282956L, 3);    // "<clinit>" "()V"
        long code = Loader.findMethod(bytes);
        if (code != 0L && !clinitCompilable(code, Loader.gcodeLen))
        {
            // SAY SO. A REJECTED initializer is indistinguishable from one that ran: the class loads, gets
            // its static cells, and the batch-end sweep marks it INITIALIZED because no record is pending --
            // so every static reads null FOR EVER and nothing ever revisits it. That silence cost a full
            // debugging round for HexFormat, and it is what makes an enum whose constants are all null look
            // like a resolution bug rather than an initializer that was never run.
            reportClinitRejected();
        }
        if (code != 0L && clinitCompilable(code, Loader.gcodeLen))
        {
            if (clinitN >= Loader.MAXBLOB) { Loader.capHalt(Magic.bytes("Loader.MAXBLOB-clinit"), clinitN); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
            // Record the initializer's PRECISE dependencies (from its bytecode) BEFORE compile — compile uses the
            // same gView, but scanning first keeps the cp-parse state pristine for the walk.
                clDepStart[clinitN] = clDepTop;
            scanClinitDeps(code, Loader.gcodeLen);
                clDepN[clinitN] = clDepTop - clDepStart[clinitN];
            // CAPTURE the body; do not compile it. Initialization already happens on demand (JVMS 5.5, the
            // lazy-init arc), but the COMPILE stayed here at load time, so an initializer the program never
            // touches still cost its full A64 body -- twice over, since each batch that loads the class
            // recompiles it. java/lang/Character$UnicodeScript is the extreme case: 32K of bytecode -> ~1.2 MB
            // of code, emitted in two batches and executed in neither, which was 3.5 MB of a 4.0 MB arena.
            // clinitEntryOf compiles on the first ACTUAL run, and its own relocs are patched there (the
            // batch-wide patchRelocs is long past by then) exactly as lazyCompile does for deferred bodies.
            clinitCode[clinitN] = code;
            clinitCodeLen[clinitN] = Loader.gcodeLen;
            clinitDescOff[clinitN] = Loader.gFoundDescOff;
            clinitStatic[clinitN] = Loader.gFoundStatic;
            clinitLocals[clinitN] = Loader.gMaxLocals;
            clinitEntry[clinitN] = 0L;              // uncompiled: clinitCode[] is now the "enqueued" marker
                clinitPd[clinitN] = Loader.findPdByName(Loader.gView.base, Loader.gThisNameOff);   // which blob (for dependency-ordered running)
            clinitBase.set(clinitN, Loader.gView.base);                // batch-independent identity (see the declarations)
            clinitNameOff[clinitN] = Loader.gThisNameOff;
            clinitOwner[clinitN] = 0;                   // explicit: allocArray does not zero on this VM
            clinitDone[clinitN] = 0;
            if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/io/FileDescriptor")))
            {
                clinitFdFirst = clinitN;   // run FIRST in runClinits: it registers the JavaIOFileDescriptorAccess
            }                              // that NativeDispatcher/NioSocketImpl <clinit>s read via SharedSecrets
            clinitN += 1;
        }
    }

    /**
     * THE COMPILE CONTEXT IS STATIC, so exactly one task may be inside the compiler at a time. {@code
     * lazyCompile} has always taken {@link VM#loaderLock} for that reason; this path reached {@code compile}
     * without it, so a second task compiling here while another compiled elsewhere clobbered {@code gView.bytes}/
     * {@code gView.cp} mid-compile -- which surfaces as a NULL bytecode array inside the compiler
     * ({@code NullPointerException} in {@code Baseline.s4}, lowering a switch) rather than as anything
     * resembling a locking bug. The lock is RECURSIVE and owned by TASK, so nesting inside an
     * already-locked path costs nothing.
     *
     * <p>The lock covers the COMPILE only, never the running of an initializer: a {@code <clinit>} can block
     * on a monitor or spawn threads, and holding the loader lock across that would deadlock any task that
     * needs to compile before it can release.
     */
    private static long clinitEntryOf(int i)
    {
        VM.loaderLock(VM.LOCK_CLINIT);
        long r;
        try
        {
            r = clinitEntryOfLocked(i);
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
        return r;
    }

    /**
     * The compiled entry of initializer {@code i}, compiling its captured body on first request.
     *
     * <p>This is the whole point of deferring: an initializer that never runs never gets an A64 body. The
     * shape mirrors {@link LazyCompiler#lazyCompile} because the situation is identical — a body captured during one
     * batch, compiled at an arbitrary later moment when the batch's compile context is long gone. So it
     * restores that context from the class registry, keeps the class's already-filled TIB, and patches its
     * OWN reloc sites, since the batch-wide {@code patchRelocs} has already run and will not run again for
     * these. Without that last step a cross-class call in the initializer stays a {@code bl 0}.
     *
     * <p>Memoized into {@code clinitEntry[i]}, which is also what keeps the buffer alive: the array is a
     * heap {@code long[]}, so the conservative root scan sees the entry address and pins the block. An
     * uncompiled slot holds 0 and pins nothing, which is correct — there is nothing to keep.
     */
    private static long clinitEntryOfLocked(int i)
    {
        if (clinitEntry[i] != 0L)
        {
            return clinitEntry[i];                      // already compiled (or run once before)
        }
        if (clinitCode[i] == 0L)
        {
            return 0L;                                  // empty slot: nothing was enqueued here
        }
        int reg = clinitRegOf(i);
        if (reg < 0)
        {
            Loader.capHalt(Magic.bytes("clinit-compile-ctx"), i);   // no class to compile against -- name it, don't guess
        }
        LazyCompiler.restoreCtxForCompile(clinitBase.get(i), Loader.blobLenOf(clinitBase.get(i)), reg);
        Loader.gMaxLocals = clinitLocals[i];                   // AFTER the restore: parseFields/parseVtable clobber it
        boolean reuseWas = Loader.compileReuseTib;              // a nested load resets it: restore, never clear
        Loader.compileReuseTib = true;                         // the class's TIB exists and is filled -- do not rebuild it
        int rcMark = Relocator.rcCount;                           // this compile's own reloc sites start here
        int rsMark = Relocator.rsCount;
        // BRACKET THE COMPILE WITH lzCompiling, like the other two on-demand paths. A getstatic whose class is
        // not registered yet resolves to the PERMANENTLY-ZERO cell, and the note-pull-recompile that fixes
        // that is gated on lzCompiling -- so without this an initializer compiled here binds its statics to
        // zero FOR EVER, and reads null however many times the owning class is initialized afterwards.
        //
        // This is the third path to need it: lazyCompileLocked had it, compileMethodOnDemand and
        // compileSigOnDemand were fixed in PR #213, and clinitEntryOf was missed. It is what made picocli's
        // GroupValidationResult.<clinit> build its SUCCESS_* instances with a null `type` even once the Type
        // enum HAD been initialized first -- the initializer was reading a zero cell, not a stale one.
        boolean outerCompiling = Loader.lzCompiling;
        boolean outerRetried = Loader.lzRetried;
        int outerPullN = Loader.lzPullN;
        Loader.lzCompiling = true;
        Loader.lzPullN = 0;
        Loader.lzRetried = false;
        // isSync = 0 STATED, not defaulted: a <clinit> is never synchronized (JVMS 2.9.2 -- the class
        // initializer's ACC_SYNCHRONIZED is ignored), and the convenience overload that used to supply
        // that 0 silently is deleted, because it is what let THREE compile paths drop the monitor without
        // a word: compileMethodOnDemand (reflection), compileSigOnDemand (phase-A cells) and two probes.
        long buf = Loader.compile(clinitCode[i], clinitCodeLen[i], clinitDescOff[i], clinitStatic[i], 0);
        Loader.lzCompiling = outerCompiling;
        Loader.compileReuseTib = reuseWas;
        boolean pulled = Loader.lzPullN > 0;
        if (pulled)
        {
            Loader.drainPendingPulls();
            Relocator.truncateRelocs(rcMark);                     // drop the doomed body's reloc sites: patching them
            Relocator.rsCount = rsMark;                           //   later would write into reused memory
        }
        Loader.lzRetried = outerRetried;
        Loader.lzPullN = outerPullN;
        if (pulled && !Loader.odRetried)
        {
            // Recompile with the pulled classes present. Everything is re-derived from `i`, so the retry is
            // this method again; the first buffer is abandoned before anything can have called it, since
            // clinitEntry[i] is only assigned below. odRetried bounds it to one attempt.
            Loader.odRetried = true;
            long again = clinitEntryOf(i);
            Loader.odRetried = false;
            return again;
        }
        LazyCompiler.registerCoCompiled();                           // a private static helper the initializer calls
        Relocator.patchRelocsFrom(rcMark, rsMark);                // resolve OUR sites: batch patchRelocs is long past
        if (buf == 0L)
        {
            Loader.capHalt(Magic.bytes("clinit-compile-null"), i);  // about to call address 0 -- halt with an index instead
        }
        clinitEntry[i] = buf;
        // INITIALIZE WHAT THIS INITIALIZER READS, before ensureClinit calls it. The compile above just
        // recorded every cross-class getstatic/new site it contains (JVMS 5.5 active uses), and because this
        // method brackets the compile with lzCompiling those land in lzInitReg -- which only lazyCompile
        // drained. So they were noted and never acted on: the body then ran against classes whose statics
        // were still unset, which is a null that looks exactly like a value.
        //
        // picocli's GroupValidationResult.<clinit> is the case: it builds SUCCESS_PRESENT/SUCCESS_ABSENT from
        // `Type.SUCCESS_*`, so with the Type enum uninitialized every instance's `type` field was null --
        // and blockingFailure() compares `type` against those same constants, so `null == null` reported a
        // BLOCKING FAILURE on a SUCCESS and handed the launcher a null exception to throw.
        //
        // Memoized into clinitEntry[i] FIRST: a drained initializer can lead straight back here for this same
        // class, and the assignment above is what makes that re-entry return the buffer instead of recursing.
        //
        // THE DRAIN IS DELIBERATELY NOT DONE HERE -- see runPendingClinit, which does it AFTER the body runs.
        // Draining at this point initializes classes the COMPILE noted while the compiled initializer has not
        // executed a single instruction, and that is a rule a real JVM does not have: linking (JVMS 5.4) may
        // never run an initializer, only an EXECUTED active use (5.5) does. Compilation is not execution.
        // What this method's compile owes the body is covered by the PRECISE bytecode dep set, which
        // runPendingClinit walks both before and after this call.
        return buf;
    }

    /** The class-registry index of initializer {@code i}'s owner, matched by blob base (as ensureClinit does). */
    private static int clinitRegOf(int i)
    {
        if (ClassRegistry.clTab == null)
        {
            return -1;
        }
        Address base = clinitBase.get(i);
        int r = 0;
        while (r < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[r] != null && ClassRegistry.clTab[r].base.EQ(base))
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /**
     * Walk the {@code <clinit>} bytecode and record the classes whose initialization it needs — the JVM's own
     * init triggers ({@code getstatic}/{@code putstatic}/{@code invokestatic} owner, {@code new}/{@code anewarray}
     * class) plus {@code ldc} of a Class literal (a class literal in a {@code <clinit>} typically feeds reflective
     * access — {@code getEnumConstants} reads the enum's {@code $VALUES}, which only its own {@code <clinit>} sets).
     * Self-references are skipped. These are the exact edges clinit ordering needs, without the spurious ones the
     * whole constant pool carries (field types, signatures, an inner class naming its outer, ...).
     */
    private static void scanClinitDeps(long code, int len)
    {
        int pc = 0;
        while (pc < len)
        {
            int op = u1(code + pc);
            int cnOff = -1;
            if (op == 0xB2 || op == 0xB3 || op == 0xB8)        // getstatic / putstatic / invokestatic: owner init
            {
                cnOff = Loader.refClassNameOff(u2(code + pc + 1));
            }
            else if (op == 0xBB || op == 0xBD)                 // new / anewarray: the class is initialized
            {
                cnOff = Closure.classCpNameOff(u2(code + pc + 1));
            }
            else if (op == 0x12)                               // ldc: a Class literal feeds reflective init
            {
                int ci = u1(code + pc + 1);
                if (Loader.gView.cpTag[ci] == 7)
                {
                    cnOff = Closure.classCpNameOff(ci);
                }
            }
            else if (op == 0x13)                               // ldc_w
            {
                int ci = u2(code + pc + 1);
                if (Loader.gView.cpTag[ci] == 7)
                {
                    cnOff = Closure.classCpNameOff(ci);
                }
            }
            if (cnOff >= 0 && cnOff != Loader.gThisNameOff)            // skip self-references
            {
                addClDep(cnOff);
            }
            pc += insnLen(code, pc);
        }
    }

    /** Name a class whose {@code <clinit>} the compiler gate refused, once per class. */
    private static void reportClinitRejected()
    {
        Uart.write(Magic.bytes("\n  CLINIT REJECTED (statics stay null): "));
        Loader.printNameAt(Loader.gView.base, Loader.gThisNameOff);
        Uart.putc(0x0A);
    }

    /** Append a precise clinit dependency (name Utf8 offset in the current blob), deduped within this <clinit>. */
    private static void addClDep(int nameOff)
    {
        int k = clDepStart[clinitN];
        while (k < clDepTop)
        {
            if (clDepOff[k] == nameOff)
            {
                return;
            }
            k += 1;
        }
        if (clDepTop >= clDepOff.length) { Loader.capHalt(Magic.bytes("MAXDEP-clinit"), clDepTop); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        clDepOff[clDepTop] = nameOff;
        clDepTop += 1;
    }

    static boolean clinitCompilable(long code, int len)
    {
        // RULE 2 (2026-09-12): ALL <clinit>s RUN. A skipped initializer is a SILENT WRONG ANSWER -- the class
        // loads, gets static cells, and answers null for ever -- and chasing those one null static at a time
        // is the most expensive thing this project has done. The gate below is kept, unreached, because its
        // per-class comments are the record of WHY each of those initializers matters; it is not deleted until
        // the arc that replaces it is finished.
        //
        // The two earlier rejections of this rule were measured against a VM that DENIES natives (Form 1 died
        // at java/io/UnixFileSystem.<clinit> -> the denied native initIDs; Form 2 in ObjectStreamClass$Caches).
        // Rule 3 removes that cause by overlaying from JDK 26 source with natives STUBBED, so those
        // measurements no longer describe this VM.
        if (RUN_ALL_CLINITS)
        {
            return true;
        }
        // Allowlist: java/util/regex/Pattern.<clinit> ldc's Pattern.class for the `desiredAssertionStatus()`
        // assertions idiom (a tag-7 Class literal), which the generic ldc-tag gate below rejects. Its remaining
        // work (accept/lastAccept = new Node()/new LastNode(); putstatic) is runnable, and it MUST run -- compile
        // reads `lastAccept` (a static) as the node-chain terminal; a null lastAccept -> unlinked nodes -> NPE in
        // SliceNode.study. Class literals are supported (Milestone 0d) + desiredAssertionStatus() returns false.
        // Kept as a targeted allow (not a blanket tag-7 allow) because many other <clinit>s use the same idiom but
        // are intentionally skipped-and-seeded (String/ArraysSupport/Unsafe...) and hang if actually run.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/regex/Pattern")))
        {
            return true;
        }
        // java/net/Socket.<clinit> ldc's InputStream.class/OutputStream.class (tag-7 Class literals, supported)
        // to bind its STATE/IN/OUT VarHandles via the overlaid MethodHandles/MhUtil/VarHandle shim. It is
        // runnable and MUST run -- an unbound STATE -> NullPointerException in getAndBitwiseOrState during connect.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/net/Socket")))
        {
            return true;
        }
        // sun/nio/ch/NioSocketImpl.<clinit> ldc's NioSocketImpl.class for the desiredAssertionStatus() idiom
        // (it uses `assert`), which the tag-7 gate rejects. It MUST run: it binds `nd = new SocketDispatcher()`
        // (an unbound nd -> read/write dispatch on null) and disables assertions (desiredAssertionStatus()=false).
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("sun/nio/ch/NioSocketImpl")))
        {
            return true;
        }
        // java/util/concurrent/ConcurrentLinkedQueue.<clinit> is java/net/Socket's case exactly: it ldc's
        // ConcurrentLinkedQueue.class and Node.class to bind its HEAD/TAIL/ITEM/NEXT VarHandles through the
        // overlaid MethodHandles/MhUtil/VarHandle shim, and the tag-7 gate rejects it for the Node literal.
        // It MUST run -- CLQ drives its ENTIRE structure through those handles, so a skipped initializer is a
        // null ITEM and an NPE in Node.<init> on the first offer(), which is where the console launcher
        // stopped (ConcurrentLinkedQueue.java:193).
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/concurrent/ConcurrentLinkedQueue")))
        {
            return true;
        }
        // java/net/StandardSocketOptions.<clinit> creates its option constants with Integer.class/Boolean.class
        // (tag-7 Class literals, supported). It MUST run: close() reads SO_LINGER, and an unbound (null) option
        // makes Net.getSocketOption(fd, null) NPE on name.type().
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/net/StandardSocketOptions")))
        {
            return true;
        }
        // java/util/jar/Attributes$Name.<clinit> ldc's Attributes$Name.class to hand to
        // CDS.initializeFromArchive (overlaid to a no-op here -- metal has no class-data archive), which trips
        // the tag-7 gate. It MUST run: it builds KNOWN_NAMES, the map every manifest attribute name is looked
        // up in, and Name.of dereferences it unguarded -- a skipped initializer is an NPE on the first
        // manifest header. The rest of the body is plain `new Name(...)` + HashMap + Map.copyOf.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/jar/Attributes$Name")))
        {
            return true;
        }
        // java/util/ImmutableCollections.<clinit> ldc's its own class for CDS.initializeFromArchive (a no-op
        // overlay here) -- the tag-7 gate again. It MUST run: it seeds SALT32L (the hash mixer every MapN/SetN
        // probe uses) from System.nanoTime and builds the EMPTY_* singletons. Skipped, SALT32L stays 0 and the
        // EMPTY singletons stay null, and Map.copyOf(...) -- which java.util.jar.Attributes$Name's initializer
        // calls -- spins in MapN's probe loop instead of failing. Everything it needs is wired: nanoTime is a
        // provided native, CDS is overlaid.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/ImmutableCollections")))
        {
            return true;
        }
        // java/util/HexFormat.<clinit> is the assertion idiom PLUS real work, which the rule below rejects --
        // and the rejection is SILENT, so HEX_FORMAT simply stayed null and HexFormat.of() returned null. That
        // rule's comment assumes such classes are clinitBlocked or seeded instead; HexFormat is neither, so it
        // fell in the gap between the two. Its body is runnable here: SharedSecrets.getJavaLangAccess() is
        // seeded with a MetalJavaLangAccess (seedJavaLangAccess), the digit tables are plain newarray/bastore,
        // and the singletons are `new HexFormat(...)` over String literals. `jla` is only dereferenced by the
        // FORMATTING methods (uncheckedNewStringWithLatin1Bytes), not by parseHex.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/HexFormat")))
        {
            return true;
        }
        // org/junit/jupiter/engine/config/DefaultJupiterConfiguration.<clinit> ldc's OTHER classes --
        // ExecutionMode.class and TestInstance$Lifecycle.class, handed to EnumConfigurationParameterConverter
        // -- so the self-literal rule below does not reach it. It MUST run: validateConfigurationParameters
        // reads UNSUPPORTED_CONFIGURATION_PARAMETERS unguarded, and a skipped initializer is an NPE the
        // moment the Jupiter engine configures itself.
        //
        // TARGETED RATHER THAN GENERAL, and this time the general rule was MEASURED rather than argued.
        // Allowing every tag-7 outside java//jdk//sun/ does clear all ten of these at once, and the launcher
        // does get further -- but it also lets application initializers pull SERIALIZATION in, and the run
        // then dies inside java/io/ObjectStreamClass$Caches.<clinit> -> ClassCache.<init>, a subsystem this
        // VM deliberately does not carry. That is a correctness cost, not the speed cost I wrongly recorded
        // the first time: the earlier "stall" was this rule being slow, and the LOADER LOCK watchdog that
        // looked like a hang said `state 1` (TASK_READY) and recovered on its own.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff,
                Magic.bytes("org/junit/jupiter/engine/config/DefaultJupiterConfiguration")))
        {
            return true;
        }
        // Three more Jupiter initializers of the SAME shape as DefaultJupiterConfiguration above: each ldc's
        // OTHER classes, so the self-class-literal rule cannot reach them, and being application classes the
        // bake-domain rule does not either. None is clinitBlocked or seeded, so a rejection leaves their
        // statics null FOR EVER -- and all three are read UNGUARDED:
        //
        //   DisplayNameUtils            ldc's the four DisplayNameGenerator$* classes and stores a generator
        //                               static for each; every rendered test name reads one.
        //   IndicativeSentencesGeneration  ldc's DisplayNameGenerator$Standard into DEFAULT_GENERATOR.
        //   IsTestFactoryMethod         ldc's DynamicNode, formats EXPECTED_RETURN_TYPE_MESSAGE from it.
        //
        // All three are runnable here now: static interface methods resolve, Class.getName works, and
        // String.formatted has a real Formatter behind it.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff,
                Magic.bytes("org/junit/jupiter/engine/descriptor/DisplayNameUtils")))
        {
            return true;
        }
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff,
                Magic.bytes("org/junit/jupiter/api/IndicativeSentencesGeneration")))
        {
            return true;
        }
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff,
                Magic.bytes("org/junit/jupiter/engine/discovery/predicates/IsTestFactoryMethod")))
        {
            return true;
        }
        // OpenTest4JAndJUnit4AwareThrowableCollector is a VARIANT worth naming: its <clinit> BEGINS with the
        // logger idiom (ldc Own.class -> getLogger -> putstatic logger), which the self-class-literal rule
        // below DOES allow -- and then ldc's TestAbortedException.class as well, which trips the gate. So a
        // self literal plus ONE other literal is still a rejection.
        //
        // Skipped, `abortedExecutionPredicate` stays null and Jupiter's ThrowableCollector.<init> rejects it
        // through its own Preconditions.notNull -- which is how the launcher reported it, naming the
        // parameter: "abortedExecutionPredicate must not be null".
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff,
                Magic.bytes("org/junit/jupiter/engine/support/OpenTest4JAndJUnit4AwareThrowableCollector")))
        {
            return true;
        }
        // org/junit/jupiter/engine/extension/TimeoutExtension.<clinit> is three instructions --
        // ldc Timeout.class; Namespace.create(new Object[]{that}); putstatic NAMESPACE -- so it names ANOTHER
        // class and the self-class-literal rule below cannot reach it. It MUST run: every store access goes
        // through LauncherStoreFacade.getStoreAdapter, whose FIRST act is Preconditions.notNull on the
        // namespace, so a skipped initializer is "Namespace must not be null" the moment Jupiter sets up
        // EXECUTION -- which is exactly where the console launcher stopped
        // (LauncherStoreFacade.java:48). Nothing new is pulled: Namespace is an inner class of
        // ExtensionContext, already in the closure, and the literal is an annotation type.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff,
                Magic.bytes("org/junit/jupiter/engine/extension/TimeoutExtension")))
        {
            return true;
        }
        // org/junit/platform/console/output/ColorPalette.<clinit> ldc's Style.class (another class, so the
        // self-literal rule misses it) to build its SINGLE_COLOR/DEFAULT/NONE palettes. Added in the SAME
        // increment as TimeoutExtension rather than waiting for it to trap, because it is the same shape on
        // the very next phase: the launcher runs with --disable-ansi-colors, which selects ColorPalette.NONE,
        // and a skipped initializer makes that null at the first line of test output. Its body is an EnumMap
        // plus `new ColorPalette(...)` over Style constants -- no denied subsystem, unlike the four converters
        // below.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff,
                Magic.bytes("org/junit/platform/console/output/ColorPalette")))
        {
            return true;
        }
        // DELIBERATELY NOT ALLOWLISTED, and this is the whole reason the BROAD rule keeps failing. A scan of
        // the jar (1484 classes, 293 with a <clinit>) finds exactly FOURTEEN whose initializer ldc's a class
        // other than itself -- so the per-class list is finishable, contrary to what was recorded here
        // earlier. But four of the remaining ones pull a subsystem this VM does not carry, and running them
        // is precisely what the broad rule did:
        //   org/junit/platform/commons/support/conversion/StringToNumberConverter  -> BigInteger/BigDecimal
        //   org/junit/platform/commons/support/conversion/StringToJavaTimeConverter -> java/time/*
        //   org/junit/jupiter/params/converter/JavaTimeArgumentConverter            -> java/time/*
        //   org/junit/platform/commons/support/conversion/StringToCommonJavaTypesConverter
        //                                                    -> java/io/File, java/nio/file/Path, java/net/URL
        // java.math is a known landmine here (BigInteger's initializer reaches ForkJoinPool through
        // BigInteger$RecursiveOp) and java/nio/file + java/net/URL are denylisted outright. Their statics are
        // read only by the string-conversion path of @ParameterizedTest, which nothing reached calls, so
        // leaving them null is correct rather than merely convenient.
        //
        // org/junit/platform/engine/UniqueId is the fifth deliberate rejection: its <clinit> calls
        // ObjectStreamClass.lookup to fill serialPersistentFields, which only SERIALIZATION reads. Denying
        // ObjectStreamClass instead was tried and is worse -- it turns a harmless null into a halting trap
        // inside UniqueId.<clinit> itself.
        // org/junit/platform/commons/util/ReflectionUtils.<clinit> ldc's its own class (for getLogger) and
        // the array class literals "[Z".."[Ljava/lang/String;" for classNameToTypeMap -- tag-7 literals, which
        // the gate below rejects. It MUST run: `tryToLoadClass` reads classNameToTypeMap UNGUARDED, so a
        // skipped initializer is an NPE on the first class-name lookup, and that is what aborted the console
        // launcher after it had otherwise run to completion. Everything the body needs now exists: getLogger
        // (java.util.logging is provided), Pattern.compile, ClasspathScannerLoader via ServiceLoader,
        // ConcurrentHashMap.newKeySet, and array/primitive class literals.
        //
        // A BROADER FIX WAS TRIED AND REJECTED ON EVIDENCE, not argument. The gate's own comment says a
        // rejected initializer is "clinitBlocked/seeded anyway" -- true in the bake domain, false for an
        // application class -- so allowing tag-7 for everything OUTSIDE java//jdk//sun/ looked principled.
        // Measured, it made the launcher run ~4x longer without reaching the point it had reached before
        // (stalled at the same log position for 10 minutes at full CPU): running every application
        // initializer in a ~900-class closure is a different cost class. The narrowing may still be right,
        // but it needs its own increment with that cost understood.
        if (Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("org/junit/platform/commons/util/ReflectionUtils")))
        {
            return true;
        }
        // A pervasive idiom is a <clinit> that ONLY disables assertions: `ldc X.class; invokevirtual
        // desiredAssertionStatus; ...; putstatic $assertionsDisabled` (many java.util.stream classes). Its lone
        // tag-7 ldc trips the gate below, so those <clinit>s were skipped -> $assertionsDisabled stayed false ->
        // assertions ENABLED -> an `assert` evaluates an uncompiled method and the null-vtable guard throws
        // AIOOBE. Allow it, but ONLY when the <clinit> is PURELY the assertion idiom: its sole tag-7 use is the
        // idiom AND it has no side-effecting op (new/newarray/invoke* other than desiredAssertionStatus). A
        // <clinit> that does the idiom PLUS real init (String/ArraysSupport/Unsafe...) is NOT let through here
        // (those are clinitBlocked/seeded anyway) -- running its extra init out of order regresses other classes.
        boolean sawAssertIdiom = false;
        boolean risky = false;
        int pc = 0;
        while (pc < len)
        {
            int op = u1(code + pc);
            int cpi = -1;
            if (op == 0x12)                              // ldc
            {
                cpi = u1(code + pc + 1);
            }
            else if (op == 0x13)                         // ldc_w
            {
                cpi = u2(code + pc + 1);
            }
            if (cpi >= 0)
            {
                int tag = Loader.gView.cpTag[cpi];
                if (tag == 7)                            // Class literal: OK as the assertion idiom...
                {
                    if (isAssertionIdiom(code, pc))
                    {
                        sawAssertIdiom = true;
                    }
                    else if (!selfClassLiteral(cpi))
                    {
                        return false;
                    }
                    // ... or when the literal is THIS CLASS'S OWN and the class is outside the bake domain.
                    //
                    // That is the LOGGER IDIOM, and it is what every one of these looks like:
                    //     ldc Own.class; invokestatic LoggerFactory.getLogger; putstatic logger
                    // Ten application classes in the launcher's closure were rejected for it, leaving `logger`
                    // null -- and `logger.debug(...)` is called UNGUARDED, so discovery NPE'd in
                    // EngineDiscoveryOrchestrator. One allowlist entry per class does not scale.
                    //
                    // Deliberately NOT `sawAssertIdiom`: that flag exists to reject "idiom PLUS real work",
                    // which is right inside java.base (those classes are clinitBlocked or seeded instead) and
                    // wrong for an application class, where the real work is the whole point and there is no
                    // substitute for running it.
                    //
                    // A SELF literal is also the narrow half of a broader rule I tried and backed out:
                    // allowing ALL tag-7 outside java//jdk//sun/ made the launcher run ~4x longer without
                    // reaching where it already was. A clinit naming OTHER classes is where the closures come
                    // from; naming its own class pulls nothing new, because the class is already being loaded.
                }
                else if (tag != 3 && tag != 4 && tag != 8)   // not Integer / Float / String
                {
                    return false;
                }
            }
            else if (op == 0xbb || op == 0xbc || op == 0xbd || op == 0xc5     // new / newarray / anewarray / multianew
                    || op == 0xb8 || op == 0xb7 || op == 0xb9 || op == 0xba)  // invoke static/special/interface/dynamic
            {
                risky = true;
            }
            else if (op == 0xb6 && !isDesiredAssertionStatusCall(code, pc))   // invokevirtual != desiredAssertionStatus
            {
                risky = true;
            }
            pc += insnLen(code, pc);
        }
        // The idiom-allow is only safe if that's ALL the <clinit> does; if it also has side effects, running it
        // here (out of the normal init order) is what broke other classes -- reject so it stays skipped/seeded.
        if (sawAssertIdiom && risky)
        {
            return false;
        }
        return true;
    }

    /**
     * True if the {@code CONSTANT_Class} at {@code cpi} names the class currently being parsed, AND that class
     * is outside the bake domain ({@code java/}, {@code jdk/}, {@code sun/}) where a rejected initializer is
     * clinitBlocked or seeded instead.
     */
    private static boolean selfClassLiteral(int cpi)
    {
        if (Loader.utf8HasPrefix(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/"))
                || Loader.utf8HasPrefix(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("jdk/"))
                || Loader.utf8HasPrefix(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("sun/")))
        {
            return false;
        }
        int nameOff = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[cpi]))];         // CONSTANT_Class -> its name Utf8 offset
        return Loader.utf8EqAt(Loader.gView.base, nameOff, Loader.gView.base, Loader.gThisNameOff);
    }

    /** True if the {@code ldc} at {@code pc} is immediately followed by {@code invokevirtual Class
     *  .desiredAssertionStatus} -- the javac assertions idiom. */
    private static boolean isAssertionIdiom(long code, int pc)
    {
        int nx = pc + insnLen(code, pc);
        return isDesiredAssertionStatusCall(code, nx);
    }

    /** True if the op at {@code pc} is {@code invokevirtual Class.desiredAssertionStatus}. */
    private static boolean isDesiredAssertionStatusCall(long code, int pc)
    {
        if (u1(code + pc) != 0xb6)                       // invokevirtual
        {
            return false;
        }
        int idx = u2(code + pc + 1);
        return Utf8.utf8IsAtBase(Loader.gView.base, Loader.mrefNameOff(idx), Magic.bytes("desiredAssertionStatus"));
    }

    private static void warnClinitUnderLock(int i)
    {
        if (!VM.loaderLockLive() || VM.loaderOwner != VM.curTask())
        {
            return;                                      // not holding it (or the lock is inert): nothing to say
        }
        clinitLockN += 1;
        if (clinitLockSaid >= CLINIT_LOCK_SAID_MAX)
        {
            return;                                      // counted, not printed -- see CLINIT_LOCK_SAID_MAX
        }
        clinitLockSaid += 1;
        Uart.write(Magic.bytes("\n  *** INITIALIZER RUNNING UNDER THE LOADER LOCK: "));
        Loader.printUtf8Capped(!clinitBase.get(i).isZero() ? clinitBase.get(i).plus(clinitNameOff[i]) : Address.zero());
        Uart.write(Magic.bytes(" ***\n  ... guest code is executing while this task holds the loader lock. If\n"));
        Uart.write(Magic.bytes("  ... this <clinit> blocks on a monitor, joins a thread or waits, every task\n"));
        Uart.write(Magic.bytes("  ... that must COMPILE in order to wake it is queued behind this lock.\n"));
        Uart.write(Magic.bytes("  ... EXPECTED TODAY on the lazyCompile -> ensureClinit route, which is why\n"));
        Uart.write(Magic.bytes("  ... this is capped, and the TOTAL is on the batch line as clinitLk=. It is NOT\n"));
        Uart.write(Magic.bytes("  ... a deadlock report: it names a standing hazard, not an event.\n"));
    }

    static void runClinits()
    {
        // Run each <clinit> AFTER the <clinit>s of the classes it references (its CONSTANT_Class deps), so an
        // initializer that reads another class's static (e.g. ArraysSupport.<clinit> -> Unsafe.getUnsafe(),
        // whose theUnsafe is set by Unsafe.<clinit>) sees it initialized. Two-phase compiles bodies
        // superclass-first, NOT usage-dependency-first, so enqueue order is NOT a safe run order -- this
        // restores the single-phase dependency-first init order. Cycles (rare among initializers) are broken by
        // force-running the first pending, matching the loader's own cycle handling.
        // SNAPSHOT, because clinitN is not ours alone. This loop is the one place in a batch that can run
        // GUEST CODE (Magic.call0 below), and guest code can reach Class.forName -> loadClassIncremental ->
        // loadAll, which APPENDS initializers. Re-reading clinitN each iteration while done[] was sized at
        // entry walks the array past its end -- an AIOOBE inside the loader, from a length that grew under
        // it. Bounding everything by one snapshot makes the pass describe the queue it started with;
        // anything appended while it runs belongs to the batch that appended it.
        int snap = clinitN;
        boolean[] done = new boolean[snap];
        int remaining = snap;
        // Incremental load (Class.forName after launch): clinits [0,clinitRunFrom) ran in an earlier batch. Mark
        // them done so this pass runs ONLY the newly-enqueued initializers (they may still depend on the earlier
        // ones, which count as satisfied). Without this watermark a second loadAll would re-run every prior
        // <clinit>, double-initialising the whole running program.
        int w = 0;
        while (w < clinitRunFrom && w < snap)
        {
            done[w] = true;
            remaining -= 1;
            w += 1;
        }
        // FileDescriptor.<clinit> registers the JavaIOFileDescriptorAccess that the socket stack's own
        // initializers read back via SharedSecrets. That edge is registry-mediated, so it appears in no
        // class's bytecode; runClinits used to encode it as "run FileDescriptor first, unconditionally".
        // With initialization lazy, the same rule lives in initPrereq instead -- FileDescriptor initializes
        // when the first sun/nio/ch or java/net class does, which is both later and exactly as ordered. The
        // eager pre-run is kept only for a FileDescriptor that is NOT lazy (nothing today).
        if (clinitFdFirst >= 0 && clinitFdFirst < snap && !done[clinitFdFirst]
                && !lazyClinitGated(clinitBase.get(clinitFdFirst), clinitNameOff[clinitFdFirst]))
        {
            if (Loader.logClinit != 0)
            {
                Uart.write(Magic.bytes("  clinit(fd-first) java/io/FileDescriptor\n"));
            }
            long fdEntry = clinitEntryOf(clinitFdFirst);
            checkClinitEntry(Address.fromLong(fdEntry), clinitFdFirst);
            warnClinitUnderLock(clinitFdFirst);
            long unusedFd = Magic.call0(fdEntry);
            clinitRan[clinitFdFirst] = 1;
            done[clinitFdFirst] = true;
            remaining -= 1;
        }
        while (remaining > 0)
        {
            int progress = 0;
            int i = 0;
            while (i < snap)
            {
                if (!done[i] && lazyClinitGated(clinitBase.get(i), clinitNameOff[i]))
                {
                    // JVMS 5.5 initialization-on-first-active-use: leave this one PENDING. The class stays at
                    // ST_INSTANTIATED and its initializer runs from the barrier in lazyCompile, the first time
                    // one of its methods is called -- or never, if the program never touches it.
                    done[i] = true;                      // bookkeeping only: clinitRan[i] stays 0
                    remaining -= 1;
                    progress = 1;
                }
                else if (!done[i] && bootInitedEntry(i))
                {
                    clinitRan[i] = 1;                    // ran once per VM, in an earlier loader world
                    done[i] = true;
                    remaining -= 1;
                    progress = 1;
                }
                else if (!done[i] && !clinitDepBlocked(i, done))
                {
                    if (Loader.logClinit != 0)                  // #43: name each <clinit> as it runs (spot a hanging one)
                    {
                        Uart.write(Magic.bytes("  clinit "));
                        Loader.writeName(clinitBase.get(i).plus(clinitNameOff[i] + 2), u2(clinitBase.get(i).plus(clinitNameOff[i])));
                        Uart.putc(0x0A);
                    }
                    long entry = clinitEntryOf(i);   // compiled HERE, on the run that actually needs it
                    checkClinitEntry(Address.fromLong(entry), i);
                    warnClinitUnderLock(i);
                    long unused = Magic.call0(entry);
                    clinitRan[i] = 1;
                    noteBootInited(ClassRegistry.classRegAt(clinitBase.get(i), clinitNameOff[i]));
                    done[i] = true;
                    remaining -= 1;
                    progress = 1;
                }
                i += 1;
            }
            if (progress == 0)                          // initializer cycle: force-run the first pending
            {
                int j = 0;
                while (j < snap && done[j])
                {
                    j += 1;
                }
                if (j < snap)
                {
                    long cyEntry = clinitEntryOf(j);
                    checkClinitEntry(Address.fromLong(cyEntry), j);
                    warnClinitUnderLock(j);
                    long unused = Magic.call0(cyEntry);
                    clinitRan[j] = 1;
                    noteBootInited(ClassRegistry.classRegAt(clinitBase.get(j), clinitNameOff[j]));
                    done[j] = true;
                    remaining -= 1;
                }
                else
                {
                    remaining = 0;
                }
            }
        }
        // NEVER BACKWARDS. With the lock dropped around a Magic.call0 (which is what the guard below exists
        // to force), a nested batch can advance this past our snapshot; assigning our own would re-run
        // everything it had already marked done -- double-initialising a running program, which is the exact
        // failure the watermark was introduced to prevent.
        if (snap > clinitRunFrom)
        {
            clinitRunFrom = snap;                        // these have run; a later incremental batch starts past here
        }
    }

    /**
     * The classes whose {@code <clinit>} is run on FIRST ACTIVE USE (JVMS 5.5) instead of at batch end.
     * Deliberately small to start: correctness rests on the trigger set being complete for the class, and
     * today the only barrier is {@link LazyCompiler#lazyCompile} — a call to one of the class's own methods. That is
     * sound exactly when nothing reads the class's statics from OUTSIDE it, since a cross-class
     * {@code getstatic} compiles to a direct address load with no barrier to fire.
     *
     * <p>Both classes here satisfy that. {@code CleanerFactory.commonCleaner} is private and read only by
     * {@code cleaner()}; {@code ConditionalSpecialCasing}'s tables are private and read only by its own
     * {@code toUpperCaseEx}/{@code toLowerCaseEx}. They also demonstrate the two outcomes: NetDemo calls
     * {@code CleanerFactory.cleaner()} while setting up the socket (so its initializer runs late, on
     * demand), and never calls {@code ConditionalSpecialCasing} at all (so its initializer — which builds a
     * HashMap of Entry objects — never runs).
     */
    /** {@link #adoptBootInit} for batch initializer entry {@code i}, by its class. */
    private static boolean bootInitedEntry(int i)
    {
        int reg = ClassRegistry.classRegAt(clinitBase.get(i), clinitNameOff[i]);
        return reg >= 0 && adoptBootInit(reg);
    }

    static boolean lazyClinitGated(Address base, int off)
    {
        return !clinitEagerKept(base, off);
    }

    /**
     * The classes whose {@code <clinit>} still runs at batch end, under {@link #runClinits}'
     * dependency ordering, instead of on first active use.
     *
     * <p>What is left here is the socket bring-up order, which is hand-tuned and NOT derivable from
     * bytecode. The load-bearing example is {@code FileDescriptor.<clinit>}: it registers the
     * {@code JavaIOFileDescriptorAccess} that {@code NioSocketImpl}/{@code NativeDispatcher} read back
     * through {@code SharedSecrets}, an edge no dependency scan can see because it runs through a
     * registry rather than a direct reference. `runClinits` special-cases it to run first; a barrier
     * firing on first use would not reproduce that. The dispatchers and `Socket` sit in the same
     * hand-ordered bring-up, and `Unsafe`/`ArraysSupport` supply array offsets that half of java.base
     * reads through statics.
     */
    private static boolean clinitEagerKept(Address base, int off)
    {
        return false;                                   // nothing: every initializer now runs on first use
    }

    /**
     * The one initialization edge that is REAL but invisible to every automatic mechanism here.
     * {@code FileDescriptor.<clinit>} registers a {@code JavaIOFileDescriptorAccess} into
     * {@code SharedSecrets}; {@code NioSocketImpl} and the dispatchers read it back out at their own
     * {@code <clinit>} time. The dependency runs through a registry, so it appears in neither class's
     * bytecode — no dependency scan, and no compile-time barrier, can find it. {@code runClinits}
     * encodes it as "run FileDescriptor first"; this is the same rule for the lazy regime, and stating
     * it explicitly is what lets the whole socket stack initialize on demand rather than staying eager.
     */
    private static void initPrereq(int reg)
    {
        if (!Loader.utf8HasPrefix(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff, Magic.bytes("sun/nio/ch/"))
                && !Loader.utf8HasPrefix(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff, Magic.bytes("java/net/")))
        {
            return;
        }
        int fd = ClassRegistry.classRegByNameBytes(Magic.bytes("java/io/FileDescriptor"));
        if (fd >= 0 && fd != reg)
        {
            ensureClinit(fd);
        }
    }

    /** Print an absolute {u2 len}{bytes} class name with a tag. Temporary, paired with INIT_GUARD_LOG. */
    static void logInitGuard(byte[] tag, Address clsU)
    {
        if (!INIT_GUARD_LOG || clsU.isZero())
        {
            return;
        }
        Uart.write(tag);
        Loader.printNameAt(clsU, 0);
        Uart.write(Magic.bytes(" [in "));
        if (Loader.gView != null)
        {
            Loader.printNameAt(Loader.gView.base, Loader.gThisNameOff);
        }
        Uart.write(Magic.bytes("]"));
        Uart.putc(0x0A);
    }

    static void ensureInitFor(Address clsU)
    {
        if (clsU.isZero())
        {
            return;                                     // VM.forceCompile's touch, and an unresolvable name
        }
        if (!Loader.runtimeUp)
        {
            return;                                     // a BAKED guard (WriterSymbols) reached before the loader
        }                                               //   tables exist: no class is registered yet to initialize
        if (initDoneMemoHit(clsU.toLong()))
        {
            return;
        }
        int reg = DynamicLinker.regBySigU(clsU);
        if (INIT_GUARD_LOG)
        {
            logInitGuard(Magic.bytes("  IG-fire "), clsU);
            Uart.write(Magic.bytes("      reg="));
            VM.printDec(reg);
            if (reg >= 0)
            {
                Uart.write(Magic.bytes(" state="));
                VM.printDec(ClassRegistry.clTab[reg].state);
            }
            Uart.putc(0x0A);
        }
        if (reg >= 0)
        {
            ensureClinit(reg);
            RVMClass c = ClassRegistry.clTab[reg];
            if (c.state >= RVMClass.ST_INITIALIZED && c.superInited && !c.initFailed)
            {
                initDoneMemoPut(clsU.toLong());
            }
        }
    }

    private static int idMemoSlot(long clsU)
    {
        long h = (clsU >>> 1) * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 52) & (IDMEMO - 1);
    }

    private static boolean initDoneMemoHit(long clsU)
    {
        if ((clsU >>> 32) != 0L)
        {
            return false;
        }
        long want = ((VMGc.collections & 0xFFFFFFFFL) << 32) | clsU;
        int s = idMemoSlot(clsU);
        int k = 0;
        while (k < IDMEMO_PROBE)
        {
            long w = idMemo[(s + k) & (IDMEMO - 1)];
            if (w == want)
            {
                return true;
            }
            if (w == 0L)
            {
                return false;
            }
            k += 1;
        }
        return false;
    }

    private static void initDoneMemoPut(long clsU)
    {
        if ((clsU >>> 32) != 0L)
        {
            return;
        }
        long epoch = VMGc.collections & 0xFFFFFFFFL;
        long word = (epoch << 32) | clsU;
        int s = idMemoSlot(clsU);
        int k = 0;
        while (k < IDMEMO_PROBE)
        {
            int i = (s + k) & (IDMEMO - 1);
            long w = idMemo[i];
            // An empty slot, this key from an older epoch, or ANY entry from an older epoch (dead: it can never
            // hit again) is reused; a full run of current entries overwrites the first slot -- a lost entry only
            // costs one more lookup.
            if (w == 0L || (w & 0xFFFFFFFFL) == clsU || (w >>> 32) != epoch)
            {
                idMemo[i] = word;
                return;
            }
            k += 1;
        }
        idMemo[s] = word;
    }

    static void initDoneMemoClear()
    {
        int i = 0;
        while (i < IDMEMO)
        {
            idMemo[i] = 0L;
            i += 1;
        }
    }

    /** Cleared at the top of every {@link Loader#compile}: the decision must not outlive the compile it is for. */
    static void resetInitGuardMemo()
    {
        igN = 0;
    }

    /**
     * Whether an active use of {@code clsU} needs a JVMS 5.5 trigger emitted.
     *
     * <p>Answered from the class's CURRENT state, which is sound because INITIALIZATION IS MONOTONE -- a
     * class initialized now can never become uninitialized, so a guard skipped here can never be needed
     * later. An unregistered class DOES get one: it may be pulled and need initializing before this code
     * runs.
     *
     * <p>MEMOISED FOR THE COMPILE, and that is required rather than an optimization. Both compiler passes
     * run inside one compile() and the size pass DRY-RUN COMPILES, so a demand-load between the passes can
     * flip a live state check -- and two passes emitting different WORD COUNTS overruns the buffer the size
     * pass reserved. A full memo answers TRUE (emit the guard), never false: over-emitting costs a call,
     * under-emitting is a silent null static.
     */
    static boolean initGuardNeeded(Address clsU)
    {
        long key = clsU.toLong();                       // the memo KEY: the address of the name, compared as a long
        if (key == 0L)
        {
            return false;
        }
        // A CLASS NEVER NEEDS A TRIGGER FOR ITSELF, and emitting one is not merely wasteful -- it is the
        // re-entrant case. You cannot be executing C's code unless C is already in-flight or initialized:
        // invoking a static method of C, reading a static of C or instantiating C are each themselves active
        // uses that got you here, and C's own <clinit> is reached only from the initialization procedure.
        //
        // MEASURED, not reasoned: jdk/internal/util/Preconditions.<clinit> is three `aconst_null; putstatic`
        // of its OWN fields -- thirteen bytecodes -- and ConcatDemo hung inside its compiled body with the
        // initializer-start trace showing no cycle and the guard-fire counter under its threshold. Every one
        // of those three sites was a self-reference.
        if (Loader.gView != null && Loader.utf8EqAt(clsU, 0, Loader.gView.base, Loader.gThisNameOff))
        {
            return false;
        }
        int k = 0;
        while (k < igN)
        {
            if (igName[k] == key)
            {
                return igAns[k];
            }
            k += 1;
        }
        int reg = DynamicLinker.regBySigU(clsU);
        boolean need = reg < 0 || ClassRegistry.clTab[reg] == null || ClassRegistry.clTab[reg].state < RVMClass.ST_INITIALIZED;
        if (igN < IGMEMO)
        {
            igName[igN] = key;
            igAns[igN] = need;
            igN += 1;
        }
        else
        {
            return true;                                // memo full: emit, never silently skip
        }
        return need;
    }

    private static void initClinitDeps(int i, int self)
    {
        if (GUARDS_ONLY && Loader.runtimeUp)
        {
            return;                                     // executed guards alone (JVMS 5.5) -- see GUARDS_ONLY
        }
        int e = clDepStart[i] + clDepN[i];
        int d = clDepStart[i];
        while (d < e)
        {
            int dep = ClassRegistry.classRegByNameAt(clinitBase.get(i), clDepOff[d]);
            if (dep >= 0 && dep != self)
            {
                ensureClinit(dep);
            }
            d += 1;
        }
    }

    private static void clinitCellWatch(int reg, byte[] when)
    {
        if (!CELL_WATCH || ClassRegistry.clTab[reg] == null || ClassRegistry.clTab[reg].statics.isZero())
        {
            return;
        }
        if (!Loader.utf8HasPrefix(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff,
                Magic.bytes("org/junit/platform/console/shadow/picocli/CommandLine$ParseResult$GroupValidationResult")))
        {
            return;
        }
        Uart.write(Magic.bytes("  CELLS "));
        Uart.write(when);
        Uart.putc(0x20);
        Loader.printNameAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff);
        Uart.write(Magic.bytes(" block="));
        VM.printHex(ClassRegistry.clTab[reg].statics);
        int k = 0;
        while (k < 4)
        {
            Uart.write(Magic.bytes(" ["));
            VM.printDec(k);
            Uart.write(Magic.bytes("]="));
            VM.printHex(ClassRegistry.clTab[reg].statics.plus(k * 8L).loadLong());
            k += 1;
        }
        Uart.putc(0x0A);
    }

    /** True when {@code statics} is a writer-emitted block in the image (an adopted class's ONE home). */
    private static boolean isImageStatics(long statics)
    {
        return statics != 0L && statics >= VM.staticsStart && statics < VM.staticsEnd;
    }

    /** Whether the class at {@code reg} shares an image statics block whose initializer already ran. */
    private static boolean bootInitedReg(int reg)
    {
        long st = ClassRegistry.clTab[reg].statics.toLong();
        if (!isImageStatics(st))
        {
            return false;
        }
        int i = 0;
        while (i < bootInitedN)
        {
            if (bootInited[i] == st)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /**
     * Record that the class at {@code reg}'s initializer completed -- if its statics are the image's, and only once
     * the RUNTIME world is up. The suite's hand-built worlds (loadParseInt and friends) run first and reset the
     * loader to a few raw blobs; MEASURED, they initialize {@code String} and {@code StringUTF16}, and carrying that
     * into the runtime world would leave String's statics holding objects built in a world the next reset
     * discards. Those worlds are scaffolding, not part of the VM whose lifetime "once" refers to.
     */
    private static void noteBootInited(int reg)
    {
        if (!Loader.runtimeUp || reg < 0 || !isImageStatics(ClassRegistry.clTab[reg].statics.toLong()) || bootInitedReg(reg))
        {
            return;                                     // only the RUNTIME world's initializations count: see below
        }
        if (bootInited == null)
        {
            bootInited = new long[64];
        }
        if (bootInitedN == bootInited.length)
        {
            long[] grown = new long[bootInitedN * 2];
            int k = 0;
            while (k < bootInitedN)
            {
                grown[k] = bootInited[k];
                k += 1;
            }
            bootInited = grown;
        }
        bootInited[bootInitedN] = ClassRegistry.clTab[reg].statics.toLong();
        bootInitedN += 1;
    }

    /**
     * A class registered in THIS world whose image statics were initialized in an earlier one: mark it
     * initialized without running anything, and retire its pending initializer entries so neither the batch sweep
     * nor a step-2 waiter treats them as outstanding. Returns whether it applied.
     */
    private static boolean adoptBootInit(int reg)
    {
        if (!bootInitedReg(reg))
        {
            return false;
        }
        ClassRegistry.clTab[reg].state = RVMClass.ST_INITIALIZED;
        int i = 0;
        while (i < clinitN)
        {
            if (clinitBase.get(i).EQ(ClassRegistry.clTab[reg].base))
            {
                clinitRan[i] = 1;
                clinitDone[i] = 1;
            }
            i += 1;
        }
        return true;
    }

    static void ensureClinit(int reg)
    {
        if (reg < 0 || ClassRegistry.clTab == null || ClassRegistry.clTab[reg] == null)
        {
            return;
        }
        // JVMS 5.5: BEFORE A CLASS IS INITIALIZED, ITS DIRECT SUPERCLASS MUST BE -- and this has to happen
        // BEFORE the state check below, not after it.
        //
        // A class with NO <clinit> of its own reaches ST_INITIALIZED at LOAD (see noteInitNeeded), so for such
        // a class the state check returns immediately. "I have no initializer" says nothing whatever about my
        // SUPERCLASS, and skipping it is how java/io/ClassCache$1 -- an anonymous java/lang/ClassValue
        // subclass with no initializer -- got constructed while ClassValue's own statics were still null:
        // ClassValue.<init> reads `nextHashCode` and NPE'd at ClassValue.java:267.
        //
        // That is the failure the FIRST run-all-clinits attempt died of, recorded at the time only as "dies
        // inside ObjectStreamClass$Caches.<clinit> -> ClassCache.<init>" and read as serialization being too
        // big a subsystem to carry. It was never about serialization; it was this ordering rule missing.
        //
        // Guarded by its own flag rather than by `state`, and done ONCE: the walk must run even for a class
        // the loader considers initialized, but re-walking on every call would cost O(depth) per dispatch.
        //
        // THE FLAG IS SET WHEN THE SUPERCLASS IS DONE, NOT WHEN THE WALK STARTS. It used to be set before the
        // recursion, as a cycle guard -- and then a SECOND task arriving while the first was still inside the
        // superclass's initializer read `superInited` as true, skipped the superclass, and ran this class's
        // initializer against the superclass's unset statics. MEASURED: ChmRaceProbe, once in five, three
        // threads NPE'd in ThreadLocalRandom.<clinit> -> new ThreadLocalRandom -> Random.<init> ->
        // Random.seedUniquifier (Random's static, null) and then in ThreadLocalRandom.getProbe; reduced to
        // ClinitRaceProbe's Base/Derived shape it is deterministic, `0 of 3` against the host's `3 of 3`.
        // ensureClinit(sup) is what makes the second task WAIT (JVMS 5.5 step 2) or, for the task already
        // initializing it, return (step 3); a superclass chain is acyclic, so the re-entry this allows is
        // bounded by its depth. The flag is set once the superclass is initialized, or has no initializer in
        // flight at all (a class this barrier never runs) -- never while one is mid-run.
        if (!ClassRegistry.clTab[reg].superInited)
        {
            int sup = ClassRegistry.superRegOf(reg);
            if (sup >= 0 && sup != reg)
            {
                try
                {
                    ensureClinit(sup);
                }
                catch (Throwable t)
                {
                    // JVMS 12.4.2 step 7: the superclass's initialization completed abruptly, so this class's
                    // does too -- it is ERRONEOUS, and the same exception propagates.
                    markInitFailed(reg);
                    throw t;
                }
            }
            if (sup < 0 || sup == reg || ClassRegistry.clTab[sup].state >= RVMClass.ST_INITIALIZED || !initInFlight(sup))
            {
                ClassRegistry.clTab[reg].superInited = true;
            }
        }
        linkable(reg);
        if (ClassRegistry.clTab[reg].initFailed)
        {
            throw noClassDefFound(reg);                 // JVMS 12.4.2 step 5: an erroneous class never initializes
        }
        if (ClassRegistry.clTab[reg].state >= RVMClass.ST_INITIALIZED)
        {
            return;
        }
        if (adoptBootInit(reg))
        {
            return;                                     // initialized once per VM, in an earlier loader world
        }
        if (!lazyClinitGated(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff))
        {
            // Only a gated class initializes here. Every other initializer stays under runClinits'
            // dependency-ordered control: a lazy compile can happen DURING runClinits (an initializer calling
            // a deferred method), and without this check the barrier would run whatever initializer happened
            // to be pending for that class, ahead of the ones it depends on.
            return;
        }
        // JVMS 5.5: BEFORE A CLASS IS INITIALIZED, ITS DIRECT SUPERCLASS MUST BE. joe-ng did not do this --
        // initPrereq below is a narrow special case (FileDescriptor for sun/nio/ch and java/net), not the rule
        // -- so a subclass could be constructed while its SUPERCLASS's statics were still null.
        //
        // That is not theoretical: java/io/ClassCache$1 extends java/lang/ClassValue, and ClassValue's
        // constructor reads its own static `nextHashCode`. With the superclass uninitialized that read is
        // null and the constructor NPEs at ClassValue.java:267 -- which is precisely where the first
        // run-all-clinits attempt died inside ObjectStreamClass$Caches.<clinit>, recorded at the time only as
        // "dies in ClassCache.<init>".
        //
        // Walked one level here and recursively by the callee, so the whole chain is initialized bottom-up in
        // the order the specification requires. State is checked first, so an already-initialized ancestor
        // costs nothing and a cycle cannot recurse for ever.
        initPrereq(reg);                                // the one edge no bytecode scan can see (see below)
        if (awaitOtherInitializer(reg))
        {
            if (ClassRegistry.clTab[reg].initFailed)
            {
                throw noClassDefFound(reg);             // the task we waited for FAILED it (step 2, then step 5)
            }
            return;                                     // another task ran it to completion while we waited
        }
        runPendingClinit(reg);
        if (ClassRegistry.clTab[reg].initFailed)
        {
            throw noClassDefFound(reg);                 // runPendingClinit waited on another task, which failed it
        }
    }

    /**
     * {@link #initClinitDeps} for initializer record {@code i} of {@code reg}, run before its body. Those classes
     * stand for active uses the BODY makes (JVMS 5.5), so a dependency whose initialization throws is the body
     * throwing at that use: {@code reg} becomes ERRONEOUS too (step 10), and the record is retired as run and
     * done. Without this the exception escaped with the record still CLAIMED and never done -- waiters on other
     * tasks yielded for ever, and this task's next attempt took the step-3 "recursive request" return and used
     * the class as if it had initialized.
     */
    private static void initDepsOrFail(int i, int reg)
    {
        try
        {
            initClinitDeps(i, reg);
        }
        catch (Throwable t)
        {
            markInitFailed(reg);
            clinitRan[i] = 1;
            clinitDone[i] = 1;
            throw initializerFailure(t);
        }
    }

    /**
     * Throw {@code NoClassDefFoundError} naming the missing supertype if {@code reg}'s superclass or a direct
     * superinterface can never load (RVMClass.absentSuperOff) -- the JVM refuses such a class at load time, so every
     * use of it, initializing or not, fails the same way. A subclass of it fails too: its superclass walk reaches
     * this through ensureClinit.
     */
    static void linkable(int reg)
    {
        if (reg >= 0 && ClassRegistry.clTab != null && ClassRegistry.clTab[reg] != null && ClassRegistry.clTab[reg].absentSuperOff != 0)
        {
            Address superU = ClassRegistry.clTab[reg].base.plus(ClassRegistry.clTab[reg].absentSuperOff);
            byte[] msg = new byte[u2(superU)];
            Loader.putUtf8(msg, 0, superU);
            throw (Error) Magic.fromAddr(RuntimeEntrypoints.newExcWithMessage(Magic.bytes("java/lang/NoClassDefFoundError"), msg));
        }
    }

    /**
     * Mark {@code reg} ERRONEOUS (JVMS 12.4.2). Its state stays below ST_INITIALIZED, so every init guard keeps
     * reaching {@link #ensureClinit}, which now throws {@code NoClassDefFoundError} instead of returning as if it
     * had initialized. Before this, a class whose initializer threw was used for ever against whatever statics
     * the body had set before the throw: a silent wrong answer (ClinitFailProbe). The barrier makes the flag
     * visible to a waiter on another core before {@code clinitDone} releases it.
     */
    private static void markInitFailed(int reg)
    {
        ClassRegistry.clTab[reg].initFailed = true;
        Magic.dsb();
    }

    /** {@code NoClassDefFoundError("Could not initialize class a.b.C")}, as HotSpot words it, for erroneous {@code reg}. */
    private static Error noClassDefFound(int reg)
    {
        byte[] pre = Magic.bytes("Could not initialize class ");
        Address nameU = ClassRegistry.clTab[reg].base.plus(ClassRegistry.clTab[reg].nameOff);
        byte[] msg = new byte[pre.length + u2(nameU)];
        int p = RuntimeEntrypoints.putAsciiAt(msg, 0, pre);
        Loader.putDotted(msg, p, nameU);
        return (Error) Magic.fromAddr(RuntimeEntrypoints.newExcWithMessage(Magic.bytes("java/lang/NoClassDefFoundError"), msg));
    }

    /**
     * What a failed initializer throws the first time (JVMS 12.4.2 step 11): an {@code Error} as it is, anything
     * else wrapped in {@code ExceptionInInitializerError} with it as the cause.
     */
    private static Error initializerFailure(Throwable t)
    {
        if (t instanceof Error)
        {
            return (Error) t;
        }
        long e = RuntimeEntrypoints.newExc(Magic.bytes("java/lang/ExceptionInInitializerError"));
        RuntimeEntrypoints.thrCauseOff = RuntimeEntrypoints.throwableFieldOff(RuntimeEntrypoints.thrCauseOff, Magic.bytes("cause"));
        if (RuntimeEntrypoints.thrCauseOff > 0L)
        {
            Address.fromLong(e).plus(RuntimeEntrypoints.thrCauseOff).store(Magic.addrOf(t));
        }
        return (Error) Magic.fromAddr(e);
    }

    /** True if {@code reg}'s initializer is claimed -- by any task, this one included -- and has not finished. */
    private static boolean initInFlight(int reg)
    {
        int i = 0;
        while (i < clinitN)
        {
            if (clinitBase.get(i).EQ(ClassRegistry.clTab[reg].base) && clinitBusy[i] != 0 && clinitDone[i] == 0)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** True if {@code reg}'s initializer is claimed by ANOTHER task and has not finished (see drainPendingInit). */
    private static boolean initClaimedByOther(int reg)
    {
        if (reg < 0 || ClassRegistry.clTab == null || ClassRegistry.clTab[reg] == null)
        {
            return false;
        }
        int me = VM.curTask() + 1;
        int i = 0;
        while (i < clinitN)
        {
            if (clinitBase.get(i).EQ(ClassRegistry.clTab[reg].base) && clinitOwner[i] != 0 && clinitOwner[i] != me
                    && clinitDone[i] == 0)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /**
     * JVMS 5.5 step 2: if {@code reg}'s initializer is CLAIMED by another task and has not finished, yield until
     * it has. Returns true when it waited (the caller then proceeds against the finished statics, or -- if the
     * body threw -- against what it left, which is what the claiming task saw too).
     *
     * <p>A task HOLDING THE LOADER LOCK does not wait: the initializing task may need that lock to compile a
     * method its body calls, and waiting would deadlock the two. That case keeps the old behaviour (proceed);
     * it is the hazard {@code warnClinitUnderLock} already names.
     */
    private static boolean awaitOtherInitializer(int reg)
    {
        int me = VM.curTask() + 1;
        int i = 0;
        while (i < clinitN)
        {
            if (clinitBase.get(i).EQ(ClassRegistry.clTab[reg].base) && clinitOwner[i] != 0 && clinitOwner[i] != me
                    && clinitDone[i] == 0)
            {
                return awaitInitializer(i, reg);
            }
            i += 1;
        }
        return false;
    }

    /**
     * For {@link VMScheduler#hangDump}: every initializer record that is CLAIMED and not finished -- the class,
     * the claiming task, and whether its body has started ({@code ran=1}) or it is still in the dependency phase
     * ({@code ran=0}). This is the table a cross-task initialization wait cycle is read from. Read without any
     * lock, as the rest of the dump is.
     */
    static void hangDumpInits()
    {
        int i = 0;
        while (i < clinitN)
        {
            if (clinitBusy[i] != 0 && clinitDone[i] == 0)
            {
                Uart.write(Magic.bytes("  init claimed: "));
                Loader.printUtf8Capped(!clinitBase.get(i).isZero() ? clinitBase.get(i).plus(clinitNameOff[i]) : Address.zero());
                Uart.write(Magic.bytes(" owner task="));
                VM.printDec(clinitOwner[i] - 1);
                Uart.write(Magic.bytes(" ran="));
                VM.printDec(clinitRan[i]);
                Uart.putc(0x0A);
            }
            i += 1;
        }
    }

    /** Yield until initializer record {@code i} (claimed by another task) has finished; false -- without
     *  waiting -- if this task holds the loader lock (see {@link #awaitOtherInitializer}). */
    private static boolean awaitInitializer(int i, int reg)
    {
        if (VM.loaderOwner == VM.curTask())
        {
            return false;
        }
        while (clinitDone[i] == 0)
        {
            VMScheduler.taskYield();
        }
        return true;
    }

    /**
     * Run the pending {@code <clinit>} record belonging to {@code reg}, if one is still enqueued.
     * Returns 1 if an initializer ran.
     */
    private static int runPendingClinit(int reg)
    {
        int ran = 0;
        int i = 0;
        while (i < clinitN)
        {
            if (clinitRan[i] == 0 && clinitCode[i] != 0L && clinitBase.get(i).EQ(ClassRegistry.clTab[reg].base))
            {
                int me = VM.curTask() + 1;
                long daif = VMScheduler.schedLock();     // test-and-claim is ONE step: two tasks must not both
                int busy = clinitBusy[i];                //   read busy==0 and both run the body
                int owner = clinitOwner[i];
                if (busy == 0)
                {
                    clinitBusy[i] = 1;
                    clinitOwner[i] = me;
                }
                VMScheduler.schedUnlock(daif);
                if (busy != 0 && owner != me)
                {
                    // JVMS 5.5 STEP 2, not step 3: ANOTHER task is initializing this class. Wait for it, and
                    // then proceed exactly as if it had already been done when we arrived.
                    awaitInitializer(i, reg);
                    return 0;
                }
                if (busy != 0)
                {
                    // RE-ENTERED WHILE THIS CLASS IS ALREADY BEING INITIALIZED BY US -- JVMS 5.5 step 3:
                    // "this must be a recursive request for initialization ... complete normally". So we
                    // RETURN, and the caller proceeds against whatever this initializer has assigned so far.
                    // That is the specified answer, and a real JVM gives exactly it.
                    //
                    // IT USED TO RUN THE BODY HERE, on the reasoning that "a real JVM would already have run
                    // this body (it never starts a dependency first), so the least-wrong thing is to run it
                    // NOW rather than hand the caller null". The first half is true and the conclusion does
                    // not follow: running it from INSIDE the dep phase runs it before its own dependencies
                    // are initialized, and then marks it RAN so the outer frame -- which is about to finish
                    // those deps and call it properly -- skips it for ever.
                    //
                    // MEASURED, on the stock jtreg java/math suite. Bracketing both call0 sites printed
                    //     >RE java/math/BigInteger      (entered here, threw, never returned)
                    //     >RE java/math/BigDecimal      (same)
                    // and the trace read BigDecimal.<clinit> -> BigInteger.valueOf -> `posConst` NULL:
                    // BigInteger's body had been started from this arm and had not reached its own array
                    // assignments. Six of six tests died that way, each as an NPE inside java.math with no
                    // hint that an initializer had been run twice over from the middle.
                    //
                    // Returning instead keeps the ORDER the outer frame was establishing: its
                    // initClinitDeps finishes, so the dependency is fully initialized, and THEN the body
                    // runs once at the live site below.
                    return 0;
                }
                // CLAIMED above (busy=1, owner=us): the dep/compile phase, NOT yet "being initialized", but already
                // OURS -- another task arriving now waits (step 2) instead of taking step 3's recursive return.
                // COMPILE FIRST. Measured: the dependency pre-pass was NOT what pulled the
                // mutually-referencing partner in -- the COMPILE was, which is JVMS 5.4's "linking may never
                // run an initializer" being violated by this VM's own codegen. That is why the phase is
                // marked BUSY around the compile at all: so an active use arriving out of it is recognised
                // as the recursive request it is and returns, instead of starting this body early.
                //
                // THE ENTRY USED TO BE STASHED HERE (`clinitEntryAddr`) so the re-entrant arm could run the
                // body from it. That arm is gone -- see the JVMS 5.5 note above -- so the stash had become a
                // write-only field whose comment claimed something no longer true, and it is deleted with
                // the arm rather than left to read as live machinery.
                long entry = clinitEntryOf(i);           // compile it now -- this is the first (and only) run
                if (Heap.codeBlockFreeAt(Address.fromLong(entry)) == 1)   // GUARD: about to call a SWEPT initializer.
                {                                                //   Name it here, where the class is in hand.
                    Uart.write(Magic.bytes("  CLINIT ENTRY WAS SWEPT: "));
                    Loader.printNameAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff);
                    Uart.write(Magic.bytes(" entry="));
                    VM.printHex(entry);
                    Uart.write(Magic.bytes(" idx="));
                    VM.printDec(i);
                    Uart.putc(0x0A);
                    VMGc.reportSweptPc(entry);
                    while (true) { Magic.wfe(); }
                }
                // AND BEFORE ITS DEPENDENCIES: the getstatic/invokestatic/new sites inside this initializer
                // are active uses of the classes they name (JVMS 5.5), so those must be initialized first or
                // this body reads their statics UNSET. runClinits enforces that for eagerly-run initializers
                // (clinitDepBlocked); a GATED class runs HERE instead, where nothing did.
                //
                // The miss reads as a null, not an error. picocli's GroupValidationResult.<clinit> built its
                // SUCCESS_* instances from an uninitialised Type enum, so every constant and every instance's
                // `type` was null -- and blockingFailure() compares the two, so `null == null` reported a
                // BLOCKING FAILURE on a SUCCESS and handed picocli a null exception to throw.
                //
                // clinitRan[i] is already 1, so an initializer cycle re-entering this class stops here rather
                // than recursing -- the same guard the comment above relies on.
                initDepsOrFail(i, reg);
                if (Loader.LOAD_LOG)
                {
                    Uart.write(Magic.bytes("  clinit-lazy "));
                    Loader.printNameAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff);
                    Uart.putc(0x0A);
                }
                // BEFORE running it: initialize what the initializer ITSELF reads -- and ONLY that.
                //
                // The pre-compile initClinitDeps above resolves the deps that were already registered; the
                // ones this compile had to demand-load could not resolve then, so the same PRECISE set is
                // walked once more now that they are. StandardCharsets is the case that requires this pass at
                // all: its initializer copies `sun.nio.cs.UTF_8.INSTANCE` into UTF_8, and running the body
                // with sun/nio/cs/UTF_8 uninitialized left the field null and `s.getBytes(UTF_8)` throwing a
                // bare NPE.
                //
                // IT USED TO BE A `drainCtorInit(reg)` -- SINCE RETIRED ENTIRELY -- AND THAT WAS AN ORDERING
                // BUG. The drain fired every class the COMPILE touched, a far wider set than its active uses:
                // compiling a body pulls everything its constant pool names, nest host and inner-class
                // references included. Initializing those here runs their <clinit>s BEFORE this body has
                // executed a single instruction -- and a real JVM cannot do that, because linking (JVMS 5.4)
                // may never run an initializer; only an EXECUTED active use (5.5) does. Compilation is not
                // execution.
                //
                // picocli is where it showed: `GroupValidationResult$Type` merely NAMES its enclosing
                // GroupValidationResult, so compiling the enum's initializer pulled GVR and the drain ran
                // GVR.<clinit> first -- which reads Type.SUCCESS_*, still null, because Type's own body had
                // not run yet. Every GVR constant came out with a null `type`, blockingFailure() compared
                // null to null, and a SUCCESS reported a BLOCKING FAILURE.
                initDepsOrFail(i, reg);
                if (clinitRan[i] != 0)
                {
                    ran = 1;                             // a nested active use ran it during the walk above
                    break;
                }
                clinitCellWatch(reg, Magic.bytes("before"));
                warnClinitUnderLock(i);                 // THE LIVE SITE: lazyCompile holds the lock across this
                clinitRan[i] = 1;                        // CLAIM here: step 6 and step 9 with nothing between
                try
                {
                    long unused = Magic.call0(entry);
                    ClassRegistry.clTab[reg].state = RVMClass.ST_INITIALIZED;
                    noteBootInited(reg);
                }
                catch (Throwable t)
                {
                    // JVMS 12.4.2 steps 10-12: the body threw. The class is ERRONEOUS -- marked BEFORE the
                    // finally releases the waiters, so a task woken by clinitDone sees it -- and the first throw
                    // is the exception itself if it is an Error, else ExceptionInInitializerError around it.
                    markInitFailed(reg);
                    throw initializerFailure(t);
                }
                finally
                {
                    clinitDone[i] = 1;                   // release the step-2 waiters, normally or by a throw
                }
                // NOW -- and not before -- initialize the classes this initializer's COMPILE noted. They are
                // an over-approximation of its active uses (the compile pulls whatever the constant pool
                // names, nest-host and inner-class references included) and running them ahead of this body
                // inverts a mutually-referencing pair: picocli's GroupValidationResult$Type merely NAMES its
                // enclosing GroupValidationResult, so draining inside the compile ran GVR.<clinit> first,
                // which read Type.SUCCESS_* while Type had assigned nothing. Every GVR constant then carried
                // a null `type`, blockingFailure() compared null to null, and a SUCCESS reported a BLOCKING
                // FAILURE. Draining here matches the JVMS order: this class's statics are set, then whatever
                // it touched initializes and can read them.
                drainPendingInit();
                clinitCellWatch(reg, Magic.bytes("after "));
                ran = 1;
                break;
            }
            i += 1;
        }
        if (INIT_GUARD_LOG && ran == 0)
        {
            // TEMPORARY: say WHY nothing ran. A silent miss here is indistinguishable from "already done".
            int hits = 0;
            int j = 0;
            while (j < clinitN)
            {
                if (clinitBase.get(j) == ClassRegistry.clTab[reg].base)
                {
                    hits += 1;
                    Uart.write(Magic.bytes("      CL-miss "));
                    Loader.printNameAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff);
                    Uart.write(Magic.bytes(" idx="));
                    VM.printDec(j);
                    Uart.write(Magic.bytes(" ran="));
                    VM.printDec(clinitRan[j]);
                    Uart.write(Magic.bytes(" code="));
                    VM.printHex(clinitCode[j]);
                    Uart.putc(0x0A);
                }
                j += 1;
            }
            if (hits == 0)
            {
                Uart.write(Magic.bytes("      CL-miss "));
                Loader.printNameAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff);
                Uart.write(Magic.bytes(" NO RECORD clinitN="));
                VM.printDec(clinitN);
                Uart.putc(0x0A);
            }
        }
        return ran;
    }

    private static void reportUnbracketedInit(int reg)
    {
        if (unbracketedInitSaid)
        {
            return;
        }
        unbracketedInitSaid = true;
        Uart.write(Magic.bytes("\n  ACTIVE USE NOTED OUTSIDE A BRACKETED COMPILE (its <clinit> may not run): "));
        if (ClassRegistry.clTab != null && reg >= 0 && ClassRegistry.clTab[reg] != null)
        {
            Loader.printNameAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff);
        }
        Uart.write(Magic.bytes(" -- a compile path is missing its lzCompiling bracket"));
        Uart.putc(0x0A);
    }

    /** Note that the method being lazily compiled touches class {@code reg}, so it must be initialized
     *  before that method runs (JVMS 5.5: a static access or a {@code new} is an active use). */
    static void noteInitNeeded(int reg)
    {
        // NOT gated on the class's own state: a class with no <clinit> reaches ST_INITIALIZED at load, yet
        // its constructors' active uses (recorded below) still come due the first time it is instantiated.
        if (reg < 0 || ClassRegistry.clTab == null || ClassRegistry.clTab[reg] == null)
        {
            return;
        }
        if (!Loader.lzCompiling)
        {
            // OUTSIDE A BRACKETED COMPILE, WHICH SHOULD NOW BE UNREACHABLE. Every surviving compile() is
            // bracketed by lzCompiling -- lazyCompileLocked, clinitEntryOf, compileMethodOnDemand,
            // compileSigOnDemand and the drain re-entry -- and since `eagerKept` was retired nothing compiles
            // at load time at all: compileClass defers every method of every class.
            //
            // IT REPORTS RATHER THAN RETURNING SILENTLY, and that is the whole point of the replacement.
            // Dropping an active use here is the silent `if (room) { record it }` shape this file records as
            // its most expensive failure mode: the class simply never initializes, and the first symptom is a
            // static reading null somewhere else entirely, arbitrarily far away.
            reportUnbracketedInit(reg);
            return;
        }
        int i = 0;
        while (i < lzInitN)
        {
            if (lzInitReg[i] == reg)
            {
                return;                                 // already noted for this compile
            }
            i += 1;
        }
        // THE DEDUP SCAN RUNS FIRST SO THAT A REFUSAL BELOW IS A GENUINE LOSS. Ordered the other way -- as
        // it was -- a full list also refused classes ALREADY ON IT, which are not losses at all, and a count
        // taken there would over-report exactly the way MAXREACH's first cut did: it counted already-marked
        // methods as dropped and claimed 2,384,018 against a true shortfall of ~6,000.
        lzInitWant += 1;
        if (lzInitWant > lzInitWantMax)
        {
            lzInitWantMax = lzInitWant;                 // the size this cap would have had to be
        }
        if (lzInitN >= MAXPENDINIT)
        {
            // A DROPPED ACTIVE USE IS A CLASS THAT NEVER INITIALIZES (JVMS 5.5). It was a bare `return`:
            // the silent `if (room) { record it }` shape this file calls its most expensive failure mode, at
            // a FOURTH site after MAXREACH, MAXPEND and the unbracketed arm just above. The symptom is a
            // static reading null arbitrarily far away, with nothing connecting it back to here.
            // UNGATED, because a class that never initializes is a failure and failures are not gated.
            lzInitDropped += 1;
            if (!lzInitFullSaid)
            {
                lzInitFullSaid = true;
                Uart.write(Magic.bytes("\n  PENDING-INIT LIST FULL: <clinit> will NOT run for "));
                Loader.printNameAt(ClassRegistry.clTab[reg].base, ClassRegistry.clTab[reg].nameOff);
                Uart.write(Magic.bytes(" -- its statics read null. MAXPENDINIT="));
                VM.printDec(MAXPENDINIT);
                Uart.putc(0x0A);
            }
            return;
        }
        lzInitReg[lzInitN] = reg;
        lzInitN += 1;
    }

    /** Initialize everything the just-compiled method touches. Drains rather than iterates: an initializer
     *  can compile further methods, which can note more classes. */
    static void drainPendingInit()
    {
        if (GUARDS_ONLY && Loader.runtimeUp)
        {
            lzInitN = 0;                                // noted, never acted on: the guard is the only trigger,
            lzInitWant = 0;                             //   as an executed active use is in a JVM (GUARDS_ONLY)
            return;
        }
        // SNAPSHOT AND CLEAR FIRST, as drainPendingPulls does. Each ensureClinit runs GUEST code, which
        // compiles further methods -- and a nested compile's own drain used to pop THIS drain's remaining
        // entries off the shared list, initializing them from inside an unrelated initializer. Measured:
        // stock Locale's initDefault noted StaticProperty and BaseLocale; StaticProperty.<clinit> lazily
        // compiled a callee, whose drain initialized BaseLocale -- whose <clinit> reads
        // StaticProperty.javaLocaleUseOldISOCodes() while StaticProperty was still half-built: an NPE in
        // BaseLocale.<clinit>. The order within this drain is unchanged (newest first).
        int n = lzInitN;
        int[] regs = new int[n];
        int i = 0;
        while (i < n)
        {
            regs[i] = lzInitReg[i];
            i += 1;
        }
        lzInitN = 0;
        while (n > 0)
        {
            n -= 1;
            // A CLASS ANOTHER TASK IS INITIALIZING IS LEFT TO THAT TASK, not waited for. This list is an
            // OVER-APPROXIMATION -- every class the compile's constant pool touched, initialized before the
            // body has executed one instruction -- and waiting on it adds a wait-for edge a real JVM never has,
            // because a JVM initializes a class only when a use EXECUTES. Held while this task has a claim of its
            // own in flight, that edge closes a cross-task cycle. MEASURED (ChmRaceProbe, ~1 boot in 5, silent):
            // task A claimed java/util/Random and, inside its dependency phase, drained ThreadLocalRandom; task B
            // had claimed ThreadLocalRandom and was initializing its superclass Random first (JVMS 5.5 step 7).
            // Random's initializer never touches ThreadLocalRandom -- the edge was this drain's.
            //
            // SKIPPING IS SOUND because every such site is GUARDED: a class still being initialized was not
            // initialized when the body compiled (initialization is monotone), so getstatic/putstatic/new/
            // invokestatic on it got an init guard (MetalSymbols.needsInitGuard*), and the guard waits -- at the
            // use, on the owner -- exactly when a JVM would.
            if (initClaimedByOther(regs[n]))
            {
                continue;
            }
            ensureClinit(regs[n]);
        }
        lzInitWant = 0;                                 // the list is empty: this compile's want is settled.
                                                        //   Reset HERE rather than at note time because an
                                                        //   initializer compiles further methods, which note
                                                        //   more classes -- the drain is the real boundary.
    }

    /**
     * Say how many active uses were dropped for want of room, at the END of a launch -- where the answer
     * exists. SILENT when none, which is this file's standing rule for an instrument: a report that cries
     * wolf on a passing boot is worse than none.
     *
     * <p>Unlike the MAXREACH report, this one can NAME THE SIZE TO USE. That report deliberately suggests
     * none, because a run that truncated its closure cannot know the true total; here {@code lzInitWantMax}
     * is measured directly -- the most distinct classes any single compile actually asked for.
     *
     * <p>THE LIMIT OF THAT NUMBER, stated rather than rounded up: it is exact for the compiles that RAN, and
     * the compiles that ran on a dropping boot are not the ones that would run on a healthy one -- a class
     * whose {@code <clinit>} was skipped changes what is compiled after it. So treat it as a FLOOR ("at
     * least this") rather than the final size, which is how the wording puts it.
     */
    static void reportPendInitDrops()
    {
        if (lzInitDropped == 0)
        {
            return;
        }
        Uart.write(Magic.bytes("\n  PENDING-INIT DROPS: "));
        VM.printDec(lzInitDropped);
        Uart.write(Magic.bytes(" active use(s) never initialized -- MAXPENDINIT="));
        VM.printDec(MAXPENDINIT);
        Uart.write(Magic.bytes(", but one compile wanted "));
        VM.printDec(lzInitWantMax);
        Uart.write(Magic.bytes("; raise it to at least that"));
        Uart.putc(0x0A);
    }

    /** True if class {@code reg} has an enqueued {@code <clinit>} that has not executed yet. */
    static boolean clinitPendingFor(int reg)
    {
        int i = 0;
        while (i < clinitN)
        {
            if (clinitRan[i] == 0 && clinitCode[i] != 0L && clinitBase.get(i).EQ(ClassRegistry.clTab[reg].base))
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** True if clinit {@code i} references a class whose own (still-unrun) {@code <clinit>} must run first. */
    private static boolean clinitDepBlocked(int i, boolean[] done)
    {
        int pd = clinitPd[i];                           // this record's blob in THIS batch's pending list, or -1
        int e = clDepStart[i] + clDepN[i];
        int d = clDepStart[i];
        while (d < e)                                   // the initializer's PRECISE (bytecode) deps, not the whole cp
        {
            int jpd = Loader.findPdByName(clinitBase.get(i), clDepOff[d]);   // the referenced class's blob
            if (jpd < 0)
            {
                // NOT IN THIS BATCH'S PENDING LIST -- which does not mean "not loaded". findPdByName searches
                // pdNameOff[], the blobs this batch is working on; a dependency pulled by an EARLIER batch has
                // settled and is absent from it. Such a class can still be UNINITIALIZED (initialization is
                // lazy), and this getstatic is exactly its first active use, so it must be initialized here.
                //
                // Missing this read as a null rather than as an error: picocli's GroupValidationResult.<clinit>
                // built its SUCCESS_* instances from an uninitialised Type enum, so every constant AND every
                // instance's `type` field was null -- and `blockingFailure()` compares them, so `null == null`
                // reported a blocking failure on a SUCCESS and handed picocli a null exception to throw.
                int reg = ClassRegistry.classRegByNameAt(clinitBase.get(i), clDepOff[d]);
                if (reg >= 0)
                {
                    ensureClinit(reg);
                }
            }
            else if (jpd != pd)
            {
                // An eagerly-run initializer that touches a LAZY-INIT class is an active use of it, and
                // runClinits has already passed that class over. Initialize it here, which is exactly the
                // JVMS rule; otherwise this initializer would read its statics unset.
                ensureClinit(ClassRegistry.classRegByBlob(Loader.pdBase.get(jpd)));
                int k = 0;
                while (k < clinitN)                     // does that blob have a not-yet-run <clinit>?
                {
                    if (clinitPd[k] == jpd && !done[k])
                    {
                        return true;
                    }
                    k += 1;
                }
            }
            d += 1;
        }
        return false;
    }

    static boolean clinitBlocked()
    {
        // THE TEMPLATE-GENERATED VarHandle CLASSES ARE NO LONGER BLOCKED (java.lang.invoke arc, 2026-10-26). Their
        // <clinit> is `FORM = new VarForm(...)`, skipped while VarForm was DENIED on the argument that vform is
        // read only by the three mode-reflection methods. VarForm runs stock now, and a skipped FORM was MEASURED
        // to be exactly the silent wrong answer rule 2 forbids: every handle's vform stayed null, so
        // isAccessModeSupported reached a null receiver (MemberNameProbe). The byte-array-view family's outer
        // <clinit> (NIO_ACCESS, SCOPED_MEMORY_ACCESS, ALIGN) runs too.
        return Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/System"))
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Integer$IntegerCache"))
                // primitive wrappers: <clinit> sets TYPE = Class.getPrimitiveClass(...) (a native)
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Integer"))
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Long"))
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Float"))
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Double"))
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Character"))
                // NOTE: java/lang/Boolean is NOT blocked -- the metal OVERLAY replaces the stock class, and its
                // <clinit> only sets TRUE/FALSE (no native primitive TYPE), so it is safe (and needed) to run.
                // NOTE: Byte/Short ARE OVERLAID, and those overlays have NO <clinit> of their own (TYPE is
                // seeded by seedPrimType, MIN/MAX are inlined constants), so those two entries are INERT
                // today -- they describe the stock classes the overlays replace.
                //
                // java/lang/Character IS THE STOCK CLASS NOW (its overlay is deleted), so this entry is LIVE
                // rather than inert -- and it still costs nothing, MEASURED rather than assumed: stock's
                // OUTER Character declares exactly ONE non-constant static, TYPE = getPrimitiveClass("char"),
                // and seedPrimType fills that cell anyway. Everything else on the outer class is a
                // compile-time constant (JLS 4.12.4), and the 347 UnicodeBlock constants belong to the NESTED
                // Character$UnicodeBlock, which is a different name with its own <clinit>. The boot arm that
                // says so is the class-literal demo's `char isPrimitive=true name=char` -- javac compiles
                // char.class to a getstatic of this very field, so a null TYPE would fail there and nowhere
                // else.
                //
                // AND THE QUESTION THIS NOTE USED TO POSE IS ANSWERED BY NOT NEEDING TO BE ASKED. It said the
                // JLS 5.1.7 valueOf cache is filled LAZILY "precisely because an initializer added HERE would
                // be SKIPPED", and that un-blocking these three was what an eager, race-free fill needed. The
                // eager fill landed without touching this list, because stock does not put the cache on the
                // wrapper at all: it nests Character$CharacterCache / Byte$ByteCache / Short$ShortCache, and
                // JVMS 5.5 makes reading CharacterCache.cache an active use of the NESTED class. A nested
                // class is a different name, and these entries match by EXACT name -- so the cache's
                // initializer was never blocked by them in the first place. The premise was true of the shape
                // the lazy fill chose, not of the problem.
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Byte"))
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/lang/Short"))
                // M3 sockets: these <clinit>s call natives (initIDs/poll consts/SharedSecrets). Skipping is
                // sound for the blocking connect/read/write/close path -- none of the skipped statics are read
                // there (IPv4-only, no poll consts).
                // sun/nio/ch/IOUtil is NO LONGER SKIPPED: its statics ARE read on that path -- sun/nio/ch/Util
                // sizes the per-thread temporary direct-buffer cache from IOUtil.IOV_MAX, so skipped it was 0
                // and every socket write allocated a fresh direct buffer. Its loadLibrary calls, initIDs,
                // iovMax and writevMax are all provided in nativeBufAt.
                // NOTE: java/io/FileDescriptor is NOT skipped -- its <clinit> registers the
                // JavaIOFileDescriptorAccess that NioSocketImpl.<clinit> reads via SharedSecrets; skipping it
                // left the access null, so getJavaIOFileDescriptorAccess() took the ensureClassInitialized ->
                // MethodHandles.lookup branch (denied) and trapped. Its 3 natives (initIDs/getHandle/getAppend)
                // are stubbed in nativeBuf, and runClinits() runs it before NioSocketImpl's (dependency order).
                // sun/nio/ch/NativeThread is NO LONGER SKIPPED: its initializer is IOUtil.load() (an empty
                // method) and init(), whose C source only installs a do-nothing INTERRUPT_SIGNAL handler --
                // there are no signals here, and current0 answers 0 ("cannot signal"), so signal0 is unreached.
                // sun/nio/ch/Net is NO LONGER SKIPPED: its first static block builds the IPv6 any-local and
                // loopback addresses with InetAddress.getByAddress(new byte[16]), which the old hand-written
                // InetAddress overlay answered with an Inet4Address 0.0.0.0. InetAddress is stock now, and the
                // natives the initializer calls are provided with Net.c's Linux answers (nativeBufAt).
                // java/net/Inet4Address and Inet6Address are NO LONGER SKIPPED: InetAddress is stock now and
                // instantiates both, and their init() natives are provided empty (JNI ID caches).
                // java/lang/reflect/Proxy is NO LONGER SKIPPED: it is overlaid (guestsrc), and the overlay's
                // initializer builds the proxy-class cache newProxyInstance needs. Stock's built a ClassLoaderValue
                // cache and a ClassValue of MethodHandles, both denied.
                // ContinuationSupport.<clinit> sets SUPPORTED = isSupported0() (a native). Metal has no
                // continuations, so skipping it leaves SUPPORTED=false -- which is correct, and makes
                // pinIfSupported/unpinIfSupported no-ops that never touch the denied Continuation class.
                // (Reached via collection iterators / Thread.isVirtual checks in several java.util closures.)
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("jdk/internal/vm/ContinuationSupport"))
                // jdk/internal/misc/VM.<clinit> is NO LONGER SKIPPED: it assigns `lock` and the 64 MB default
                // `directMemory` and calls the native initialize(), which only RegisterNatives for JNI
                // (share/native/libjava/VM.c) and is wired empty. Skipped, directMemory stayed 0 and stock
                // DirectByteBuffer's Bits.reserveMemory refused every direct buffer. It does not touch savedProps,
                // which seedSavedProps fills at launch.
                // Arrays$LegacyMergeSort.<clinit> reads the java.util.Arrays.useLegacyMergeSort property via
                // AccessController/GetBooleanAction (denied). Skipping leaves userRequested=false -- correct,
                // so Arrays.sort takes the modern TimSort path (reached by any object Arrays.sort/Collections.sort).
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/Arrays$LegacyMergeSort"))
                // java/util/stream/AbstractTask.<clinit> sets LEAF_TARGET = ForkJoinPool.getCommonPoolParallelism()
                // << 2. The common pool is never set up on metal (no ForkJoin), so getCommonPoolParallelism NPEs.
                // LEAF_TARGET is read ONLY by parallel task splitting (AbstractTask.compute/getLeafTarget), which a
                // SEQUENTIAL stream never reaches -- so skipping the <clinit> (LEAF_TARGET=0, never read) is sound.
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/stream/AbstractTask"))
                // java/util/stream/Tripwire.<clinit> sets ENABLED = Boolean.getBoolean(<debug property>) (reads
                // System props -> denied on metal). ENABLED is a debug-only assert flag, read solely in
                // `if (Tripwire.ENABLED) trip(...)`; skipping leaves it false (correct), never taken.
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/stream/Tripwire"))
                // java/util/Tripwire is the same debug-flag class as the stream one (ENABLED = Boolean.getBoolean),
                // pulled by the java.util.stream spliterator machinery. Same safe skip (ENABLED false, never taken).
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/Tripwire"))
                // java/util/zip/ZipOutputStream.<clinit> is one field: inhibitZip64 = Boolean.getBoolean(
                // "jdk.util.zip.inhibitZip64"), a system-property read (denied on metal). Skipping leaves it
                // FALSE, which is the correct default -- Zip64 stays enabled. Same shape as Tripwire above.
                || Utf8.utf8IsAtBase(Loader.gView.base, Loader.gThisNameOff, Magic.bytes("java/util/zip/ZipOutputStream"));
    }

    /** Initialize the loaded class whose Type node is {@code type} (VMNatives.ensureMirrorInit); a Type the
     *  registry does not know -- an array, a primitive -- has no initializer and is left alone. */
    static void ensureInitOfType(Address type)
    {
        if (type.isZero() || !Loader.runtimeUp)
        {
            return;
        }
        int reg = ClassRegistry.regOfType(type);
        if (reg >= 0)
        {
            ensureClinit(reg);
        }
    }

    /**
     * {@code Unsafe.shouldBeInitialized}: whether the class whose Type is {@code type} still needs its JVMS 5.5
     * initialization -- HotSpot's {@code !is_initialized()}, which covers the superclass chain. Walked rather
     * than read off one flag: a class with no initializer of its own is ST_INITIALIZED from load, and its
     * {@code superInited} flag is set only when {@link #ensureClinit} visits it, so for a class nothing has
     * visited yet whose superclasses ARE initialized the flag alone would say "needs init" where HotSpot says
     * it is initialized. That case rests on READING, not on a failure: an A/B with the flag alone still passed
     * MemberNameProbe, whose arm calls the class first (an invokestatic visits it). The cost of the flag-alone
     * answer is only a needless initialization barrier, which can push a handle off a pregenerated form. A class
     * with no registry entry is one {@code ensureClinit} cannot initialize, so a barrier would only add a no-op.
     */
    static boolean typeNeedsInit(Address type)
    {
        if (type.isZero() || !Loader.runtimeUp)
        {
            return false;
        }
        int reg = ClassRegistry.regOfType(type);
        int steps = 0;
        while (reg >= 0 && ClassRegistry.clTab[reg] != null && steps < 64)
        {
            if (ClassRegistry.clTab[reg].state < RVMClass.ST_INITIALIZED)
            {
                return true;
            }
            if (ClassRegistry.clTab[reg].superInited)
            {
                return false;
            }
            int sup = ClassRegistry.superRegOf(reg);
            if (sup == reg)
            {
                return false;
            }
            reg = sup;
            steps += 1;                                 // a superclass chain is acyclic; this only bounds a bad one
        }
        return false;
    }
}
