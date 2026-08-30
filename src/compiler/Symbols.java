package compiler;

import asm.CodeBuffer;

/**
 * The seam between the baseline compiler's code generation and symbol resolution
 * (PLAN.md §M5.4.2).
 *
 * <p>Every symbolic reference the compiler emits — a call, or an address load for a
 * TIB, a Type, a static field or an interned string — flows through here. The
 * compiler names the target by <em>constant-pool index</em> (or, for its own
 * synthesised runtime calls, by a helper id), never by a resolved string or
 * address, because the two contexts resolve it differently and at different times:
 *
 * <ul>
 *   <li>the <b>writer</b> can't know an address until layout, so its implementation
 *       emits a placeholder and records the site for {@code ImageBuilder} to
 *       relocate — keeping the {@code String} keys and record lists on its side;</li>
 *   <li>the <b>metal</b> JIT has already loaded its dependencies, so its
 *       implementation emits the resolved address immediately, straight from the
 *       loader's registries.</li>
 * </ul>
 *
 * With resolution behind this interface, the code generation above it is identical
 * in both worlds — which is what lets one compiler serve both (M5.4.4). Each method
 * emits into {@code cb}; the compiler has already set up the calling convention
 * around the call (argument moves, operand spill) and continues after it.
 */
public interface Symbols
{
    // Synthesised runtime helpers the compiler calls that are not in any classfile.
    int HEAP_ALLOC = 0;         // vm/Heap.alloc(I)J
    int HEAP_ALLOC_ARRAY = 1;   // vm/Heap.allocArray(II)J
    int GC_COLLECT = 2;         // vm/VM.gcCollect(J)V
    int INSTANCE_OF = 3;        // vm/VM.instanceOf(JJ)I
    int CHECK_CAST = 4;         // vm/VM.checkCast(JJ)J
    int UNWIND = 5;             // vm/VM.unwind(JJJ)V
    // Scheduler helpers reachable from JIT-loaded guest code (the mini java.base runtime).
    int SPAWN = 6;              // vm/VM.startThread(J)V  — start a task running a Runnable
    int SEM_WAIT = 7;           // vm/VM.semWait(I)V
    int SEM_POST = 8;           // vm/VM.semPost(I)V
    int SLEEP_MS = 9;           // vm/VM.sleep(J)V
    int NEW_SEM = 10;           // vm/VM.newSem(I)I  — allocate a semaphore, return its index
    int REPORT = 11;            // vm/VM.philReport(II)V — formatted status line (no String concat on metal)
    // invokedynamic string-concat (M-B slice 1): a growable byte[] builder + a status print.
    int SC_START = 12;          // vm/VM.scStart()J -> a builder
    int SC_CHAR = 13;           // vm/VM.scChar(JI)V  — append one byte
    int SC_INT = 14;            // vm/VM.scInt(JI)V   — append an int in decimal
    int SC_END = 15;            // vm/VM.scEnd(J)J    — finish -> a trimmed byte[]
    int PRINT_STR = 16;         // vm/VM.printStr(J)V — print a mini java/lang/String's value bytes
    int SC_STR = 17;            // vm/VM.scStr(JJ)V   — append a String/byte[] (slice 1b)
    int SC_LONG = 18;           // vm/VM.scLong(JJ)V  — append a long in decimal (slice 1b)
    // Implicit (JVM-synthesised) exceptions: allocate the exception object the JIT throws on a failed check.
    int NEW_NPE = 19;           // vm/VM.newNpe()J    — a java/lang/NullPointerException (null deref)
    int NEW_AIOOBE = 20;        // vm/VM.newAioobe()J — a java/lang/ArrayIndexOutOfBoundsException (bad index)
    int GET_CLASS = 21;         // vm/VM.getClassOf(J)J — Object.getClass() -> the receiver's Class mirror
    int ARRAY_CLONE = 22;       // vm/VM.arrayClone(J)J — [T.clone() -> a shallow array copy (no vtable on array TIBs)
    int CAPTURE_TRACE = 23;     // vm/VM.captureTrace(JJJ)V — fill exc's backtrace at the throw site (all throws)
    int NEW_ARITH = 24;         // vm/VM.newArith()J  — a java/lang/ArithmeticException (integer / or % by zero)
    // Object monitors + Thread.join: the mini java.base runtime's wait/notify/join lower to VM scheduler helpers.
    int MON_WAIT = 25;          // vm/VM.objWait(JJ)V      — park the current task on an object until notified
    int VIRTUAL_RESOLVE = 48;   // the late virtual-dispatch trampoline: resolve x17's site against x0's
                                //   receiver, restore the args, and tail-branch to the real method
    int MON_NOTIFY = 26;        // vm/VM.objNotify(J)V     — wake one waiter on an object
    int MON_NOTALL = 27;        // vm/VM.objNotifyAll(J)V  — wake every waiter on an object
    int THREAD_JOIN = 28;       // vm/VM.threadJoin(J)V    — block until a Thread's task has exited
    int STACK_TRACE = 29;       // vm/VM.threadStackTrace(JJJ)J — a StackTraceElement[] for a Thread's stack
    int ALL_THREADS = 30;       // vm/VM.allThreads()J          — a Thread[] of every live task's Thread object
    int MON_ENTER = 31;         // vm/VM.monEnter(J)V           — monitorenter (real, blocking, recursive)
    int MON_EXIT = 32;          // vm/VM.monExit(J)V            — monitorexit
    int HOLDS_LOCK = 33;        // vm/VM.holdsLock(J)I          — Thread.holdsLock
    int INTERRUPT = 34;         // vm/VM.interrupt(J)V          — Thread.interrupt
    int IS_INTERRUPTED = 35;    // vm/VM.isInterrupted(J)I      — Thread.isInterrupted
    int CHECK_INTR = 36;        // vm/VM.checkClearInterrupt()I — Thread.sleep interruption check (clears)
    int IS_ALIVE = 37;          // vm/VM.isAlive(J)I            — Thread.isAlive
    int JOIN_TIMED = 38;        // vm/VM.joinTimed(JJ)I         — Thread.join(Duration)
    int PARK = 39;              // vm/VM.park()V                — LockSupport.park
    int UNPARK = 40;            // vm/VM.unpark(J)V             — LockSupport.unpark
    int NEW_ASE = 41;           // vm/VM.newAse()J    — a java/lang/ArrayStoreException (aastore type mismatch)
    int ARRAY_STORE_OK = 42;    // vm/VM.arrayStoreOk(JJ)I — 1 if a value may be aastore'd into an array, else 0
    int NEW_UNRESOLVED = 43;    // vm/VM.newUnresolved(J)J — `new` the loader cannot resolve at compile time
    int CAST_OK = 44;           // vm/VM.castOk(JJ)I  — 1 if a checkcast holds, else 0 (the JIT then throws)
    int NEW_CCE = 45;           // vm/VM.newCce()J    — a java/lang/ClassCastException (failed checkcast)
    int SET_PRIO = 46;          // vm/VMScheduler.setPriority(JI)V — Thread.setPriority (0..1024 scale)
    int GET_PRIO = 47;          // vm/VMScheduler.getPriority(J)I  — Thread.getPriority (0..1024 scale)

    /**
     * The largest value a code address's top byte (bits 31..24) can take, for the dispatch-target guard.
     * Image code sits below {@code 0x0200_0000} and the JIT arena spans {@code [0x0200_0000, 0x0300_0000)}
     * ({@code vm/Heap.CODE_BASE}/{@code CODE_LIMIT}), so a real target's top byte is at most 2 — while the
     * data heap begins at {@code vm/Heap.BASE == 0x0400_0000}, giving it a top byte of 4 or more.
     *
     * <p>The guard previously tested {@code addr >> 28 == 0}, i.e. a ceiling of {@code 0x1000_0000}, which is
     * {@code Heap.LARGE_LIMIT} — the top of the LARGE-OBJECT region rather than the top of CODE. Every
     * ordinary heap pointer passed it, so a vtable slot holding a data pointer still reached the {@code blr}
     * and wild-branched. That is how {@code java/util/jar/Attributes/TestAttrsNL} faults at
     * {@code elr=0x0416_1D80}: a plausible-looking, 4-aligned, non-null word that is simply not code.
     */
    int CODE_TOP_BYTE_MAX = 2;

    /** Emit a {@code BL} to the method at Methodref/InterfaceMethodref index {@code methodCp}. */
    void call(CodeBuffer cb, int methodCp);

    /** Emit a {@code BL} to a synthesised runtime helper (one of the ids above). */
    void callHelper(CodeBuffer cb, int helper);

    /** True if {@code athrow} should record the throw-site backtrace into the exception (so
     *  {@code printStackTrace()} has frames even for a same-method inline catch). Metal JIT only;
     *  the image writer's exceptions are internal and its methods carry no on-metal line info. */
    default boolean captureTraces() { return false; }

    /** Load into {@code reg} the TIB address of the class at {@code classCp} (for {@code new}). */
    void tib(CodeBuffer cb, int reg, int classCp);

    /** Load into {@code reg} the Type address of the class at {@code classCp}. */
    void type(CodeBuffer cb, int reg, int classCp);

    /** Load into {@code reg} the Class-mirror address for the CONSTANT_Class at {@code classCp} (a class literal). */
    void classLiteral(CodeBuffer cb, int reg, int classCp);

    /** True if the method ref at {@code methodCp} is {@code getClass()Ljava/lang/Class;} — intrinsified to GET_CLASS. */
    boolean isGetClass(int methodCp);

    /** True if the invokevirtual is {@code Class.desiredAssertionStatus()Z} (intrinsify to false). */
    default boolean isDesiredAssertionStatus(int methodCp) { return false; }

    /** True if the method ref at {@code methodCp} is an ARRAY {@code clone()} (owner starts '[') — intrinsified to ARRAY_CLONE. */
    default boolean isArrayClone(int methodCp) { return false; }

    /**
     * Object-monitor op for an invokevirtual to {@code java/lang/Object}: 0 = none, 1 = {@code wait()V},
     * 2 = {@code wait(J)V}, 3 = {@code notify()V}, 4 = {@code notifyAll()V}. Lowered DIRECTLY to a VM helper
     * (like getClass) rather than dispatched through the vtable — wait/notify are final (never overridden),
     * and the mini Object's bodies aren't compiled, so a vtable dispatch would hit a no-op slot.
     */
    default int monitorOp(int methodCp) { return 0; }

    /**
     * Tag a freshly-allocated array (in {@code arrReg}) with its array Type, so checkcast/instanceof against an
     * array class resolve. {@code operand} is the {@code newarray} atype when {@code isRef} is false, else the
     * {@code anewarray} element-class Class-entry. A no-op where array Types don't exist (the host writer): the
     * array stays raw (element size in its header), which is all its untyped uses need.
     */
    void tagArray(CodeBuffer cb, int arrReg, int operand, boolean isRef);

    /** Load into {@code reg} the Type address of the interface owning InterfaceMethodref {@code ifaceMethodCp}. */
    void interfaceType(CodeBuffer cb, int reg, int ifaceMethodCp);

    /** Load into {@code reg} the address of the static field at Fieldref index {@code fieldCp}. */
    void staticField(CodeBuffer cb, int reg, int fieldCp);

    /** Load into {@code reg} the address of the interned string at String index {@code stringCp}. */
    void string(CodeBuffer cb, int reg, int stringCp);

    /** Load into {@code reg} the address of the synthetic in-flight-exception static slot. */
    void exceptionSlot(CodeBuffer cb, int reg);

    /**
     * Load into {@code reg} the absolute PC of the instruction at word index
     * {@code targetWord} within this same method — a self reference (the athrow site
     * a stack unwind reports). The image and the on-metal JIT both compile at the
     * final base, so the default resolves it immediately; the metal writer compiles
     * at base 0 and relocates, so it overrides this to record the site.
     */
    default void codePc(CodeBuffer cb, int reg, int targetWord)
    {
        cb.patchAddr(cb.reserveAddr(reg), reg, cb.pcAt(targetWord));
    }

    /**
     * Whether the core should emit implicit runtime checks (null-deref -> NullPointerException, bad array
     * index -> ArrayIndexOutOfBoundsException). Only the on-metal JIT (which has the mini exception
     * hierarchy loaded and can allocate the exception object) enables these; the image writer keeps its
     * output check-free — trusted VM/board code doesn't rely on them, and adding checks would perturb the
     * byte-for-byte self-hosting fixpoint. Default off; {@code MetalSymbols} overrides it on.
     */
    default boolean implicitChecks()
    {
        return false;
    }

    // ----- fatal compiler diagnostics -----
    // A compiler bug or an unsupported bytecode is unrecoverable; the core reports it
    // through fail() rather than building a String message or a JDK exception, which
    // would keep the core off metal (String concat lowers to invokedynamic; java/lang
    // exceptions aren't loaded there). fail() never returns: the writer throws a rich
    // diagnostic (see WriterSymbols), the metal halts. Args carry the offending value.
    int FAIL_OPCODE = 0;            // a = opcode, b = bytecode pos
    int FAIL_LOCAL_SLOT = 1;        // a = slot
    int FAIL_STACK_NOT_EMPTY = 2;   // a = depth, b = site (0 new, 1 dropToEL1)
    int FAIL_STACK_DEPTH = 3;       // a = bytecode index
    int FAIL_NEWARRAY_ATYPE = 4;    // a = atype
    int FAIL_INTRINSIC_ID = 5;      // a = intrinsic id
    int FAIL_LDC_CONST = 6;         // a = cp index
    int FAIL_BRANCH_TARGET = 7;     // a = target bytecode index
    int FAIL_STACK_OVERFLOW = 8;    // operand stack too deep
    int FAIL_STACK_UNDERFLOW = 9;   // operand stack underflow
    int FAIL_BL_RANGE = 10;         // a = call-site addr >>12, b = target addr >>12 (bl exceeds +-128 MiB)
    int FAIL_ARG_COUNT = 11;        // a = argument count (more args than there are argument registers)

    // Sites for FAIL_STACK_NOT_EMPTY's b argument.
    int SITE_NEW = 0;
    int SITE_DROP_TO_EL1 = 1;

    /** Report an unrecoverable compile failure. Never returns. */
    void fail(int reason, int a, int b);

    // ----- symbol queries: values the lowering needs but resolves per world -----
    // These return a number rather than emitting; the writer resolves it from the
    // classfile at compile time, the metal from its loaded-class registries. Either
    // way the lowering names the target only by cp index (M5.4.4).

    /** Byte offset of the instance field at Fieldref index {@code fieldCp} within its object. */
    int fieldOffset(int fieldCp);

    /** Scalar allocation size (bytes) of an instance of the class at {@code classCp} (for {@code new}). */
    /**
     * Scalar instance size of the class at {@code classCp}, or a NEGATIVE value when the class cannot be
     * resolved: {@code -(site + 1)}, where {@code site} identifies the unresolved `new` for
     * {@link #NEW_UNRESOLVED} to name at runtime. Returning a size for an unresolvable class is what let a
     * `new` quietly produce an object carrying the WRONG class's TIB (see Baseline.lowerNew).
     */
    /**
     * True when an {@code invokevirtual} must dispatch through the ITABLE rather than the vtable: the receiver
     * class has no vtable slot of its own for the method but inherits it as an interface DEFAULT.
     *
     * <p>Neither flattener puts interface defaults in a class vtable (this one or the writer's), which is
     * consistent between them — and fine until a class-typed receiver names such a method. javac emits
     * {@code invokevirtual} then, resolution finds no slot, and the name+descriptor fallback returns a slot
     * belonging to an UNRELATED class: an index past the end of this receiver's TIB, read as a code pointer.
     * That is the wild branch behind {@code java/util/jar/Attributes/TestAttrsNL} — {@code attrs.forEach(...)},
     * where {@code Attributes} implements {@code Map} without overriding {@code forEach}.
     *
     * <p>Routing the call through the itable fixes it without renumbering a single vtable, which is what
     * adding defaults to both flatteners would have required — and vtable numbering is asserted equal between
     * the two worlds on every boot ({@code vtparity}).
     */
    boolean defaultDispatch(int methodCp);

    /** Load the Type of the interface declaring the inherited default named by {@code methodCp} into {@code reg}. */
    void defaultIfaceType(CodeBuffer cb, int reg, int methodCp);

    /** The method's slot within that interface's flattened method list. */
    int defaultIfaceSlot(int methodCp);

    int objectSize(int classCp);

    /**
     * Vtable slot of the virtual method at Methodref index {@code methodCp}, or {@code -1} when it cannot be
     * resolved at compile time -- the referenced class is not registered, which happens whenever a method is
     * compiled on demand after RTA has stopped looking (anything reached only reflectively).
     *
     * <p>{@code -1} rather than 0 because 0 IS a valid slot: it used to be returned for a miss, and the call
     * then dispatched through java/lang/Object's first virtual and silently returned the wrong thing. The
     * caller lowers a {@code -1} through {@link #virtualSite} + {@link #VIRTUAL_RESOLVE} instead.
     */
    int vtableSlot(int methodCp);

    /**
     * Record the unresolved virtual call site at {@code methodCp} and emit its index into the scratch register
     * the {@link #VIRTUAL_RESOLVE} trampoline reads (x17 -- outside the 16 argument registers, and already the
     * convention deferral stubs use). Only ever called when {@link #vtableSlot} answered -1.
     */
    void virtualSite(CodeBuffer cb, int methodCp);

    /**
     * Load helper {@code helper}'s entry address into {@code reg}. Unlike {@link #callHelper} this does not
     * branch: the dispatch guard uses it to SUBSTITUTE a resolve trampoline for a null slot, so the call
     * site's own {@code blr} does the calling.
     */
    default void helperInto(CodeBuffer cb, int reg, int helper)
    {
        fail(FAIL_ARG_COUNT, helper, reg);              // only the metal JIT resolves dispatch late
    }

    /** Itable slot of the interface method at InterfaceMethodref index {@code ifaceMethodCp}. */
    int interfaceSlot(int ifaceMethodCp);

    // ----- call classification: which lowering path an invoke takes -----
    // Name identity, resolved per world (writer: String compare; metal: Utf8-offset
    // compare), so the lowering branches on a boolean rather than a String.

    /** Whether the {@code invokestatic} at {@code methodCp} is a {@code magic/Magic} intrinsic. */
    boolean isIntrinsicCall(int methodCp);

    /** Whether that intrinsic itself emits a {@code BL}/{@code BLR} (so the caller is non-leaf). */
    boolean intrinsicEmitsCall(int methodCp);

    /** The {@link Intrinsics} id of the {@code magic/Magic} intrinsic at {@code methodCp}. */
    int intrinsicId(int methodCp);

    /** Whether the {@code invokespecial} at {@code methodCp} is a root-class {@code <init>} to skip. */
    boolean isSkippableInit(int methodCp);

    // ----- invokedynamic (string concat, M-B slice 1) -----

    /** Whether the {@code invokedynamic} at {@code idx} bootstraps via StringConcatFactory (concat). */
    boolean isConcatIndy(int idx);

    /** Utf8 body offset of the concat recipe (bootstrap arg 0) for the indy at {@code idx}. */
    int concatRecipeOff(int idx);

    /**
     * Wrap the {@code byte[]} in x0 as a {@code java/lang/String} object, leaving it in x0 (the metal JIT
     * knows String's TIB/layout; the writer never reaches this — image code has no invokedynamic).
     */
    void newStringFromBytes(CodeBuffer cb);

    // ----- invokedynamic (lambda, M-B slice 1c) -----

    /** Whether the {@code invokedynamic} at {@code idx} bootstraps via {@code LambdaMetafactory.metafactory}. */
    boolean isLambdaIndy(int idx);

    /** Instance size (bytes) of the lambda object at {@code idx} (header + captured fields). */
    int lambdaSize(int idx);

    /** Parameter count of the lambda's functional-interface method (SAM) — slice 1c handles only 0. */
    int lambdaSamArgc(int idx);

    /** Load into {@code reg} the synthetic lambda class's TIB address (building the class on the metal). */
    void lambdaTib(CodeBuffer cb, int reg, int idx);

    // ----- invokedynamic (record ObjectMethods) -----

    /** Whether the {@code invokedynamic} at {@code idx} bootstraps via {@code ObjectMethods.bootstrap} (record). */
    boolean isRecordIndy(int idx);

    /** Emit a runtime trap for an unsupported record {@code equals}/{@code hashCode}/{@code toString}; never returns. */
    void recordTrap(CodeBuffer cb);
}
