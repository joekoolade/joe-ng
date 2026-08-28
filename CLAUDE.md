# joe-ng — project memory for Claude Code

joe-ng is a **metacircular Java VM** whose foundation is a **boot-image writer**
that turns Java classes into a raw `kernel8.img` running **bare-metal on a
Raspberry Pi 4 (BCM2711, quad Cortex-A72, AArch64)** with **no OS underneath**.

Read `PLAN.md` for the full plan — it is the source of truth. This file is just
the standing rules and current state so we don't re-litigate them each session.

## Coding style (follow for all new/edited code)

- **Braces on their own line (Allman).** The opening `{` goes on its own line,
  not at the end of the preceding line, for classes, methods, `if`/`else`,
  `while`/`for`, `switch`, etc. The closing `}` is already on its own line.
- **One statement per line.** No two statements separated by `;` on one line
  (e.g. not `p += 1; i += 1;`). One variable declaration per line (no
  `int a = 0, b = 0;`). Every control-flow body is braced, even one-liners.

## Hard constraints (do not violate)

- **Everything is Java.** Assembler, compiler, boot-image writer, runtime, and
  the boot / exception-level setup are all Java. No C, no external assembler, no
  linker, no GRUB. The assembler emits raw A64 instruction **words**, not
  assembly text. The writer does its own layout, relocation, and raw
  `kernel8.img` emission — no `ld`/`objcopy`.
- **Metacircular is the foundation, not a later goal.** The classfile parser and
  baseline compiler are ordinary runtime classes: the writer runs them on the
  seed JVM to build the first image, and the image contains compiled copies so
  the VM can parse+compile new classes on the metal (and eventually run the
  writer itself).
- **No underlying OS.** Bare metal.
- **Single privilege level: EL1 (supervisor), no EL0.** Firmware enters at EL2;
  our Java boot code drops to EL1 and everything runs there. Protection is
  language type-safety + verification + GC, not hardware rings. No syscalls, no
  user/supervisor crossing.
- **Compile-only, no interpreter.** With no OS beneath, the first code on metal
  must already be machine code. One baseline compiler serves both the writer and
  the runtime JIT.
- **The only external seeds** (not things we build): a stock JVM to run the
  writer initially (gone after self-hosting, M5), and the Pi's GPU firmware that
  loads `kernel8.img`. Nothing else touches joe-ng.
- **First-principles / learning project:** reference the ARM ARM, BCM2711 docs,
  and Jikes RVM / JOE *concepts* — write every line ourselves. Log sources in
  `SOURCES.md`.

## Target facts (load-bearing)

- AArch64; image is raw `kernel8.img` **loaded at `0x80000`**; `config.txt` needs
  `arm_64bit=1`.
- Firmware enters at **EL2** → drop to **EL1** (`HCR_EL2.RW=1`, `SPSR_EL2=0x3C5`
  EL1h/DAIF-masked, `ELR_EL2`, `ERET`).
- All 4 cores start; park cores 1–3 (`WFE` on `MPIDR_EL1`) until SMP.
- Peripheral MMIO base **`0xFE000000`**; GPIO `0xFE200000`; PL011 UART0
  `0xFE201000`; mini-UART (AUX/UART1) is the simplest first console.
- MMU: off for first boot, then flat 1:1 — Normal cacheable for RAM,
  Device-nGnRnE for the `0xFE000000` window; map high memory (4 GB board).
- `CPACR_EL1.FPEN` must be set or Java floats trap; `CNTHCTL_EL2`/`CNTVOFF_EL2`
  must be set before `ERET` or EL1 can't read the timer.

## Architecture (all Java)

`magic/` (Address/Word/Offset + pragmas + AArch64 privileged intrinsics) ·
`asm/` (A64 encoder → raw words) · `compiler/` (bytecode → A64, compile-only) ·
`classfile/` (parser used by writer AND runtime) · `objectmodel/` (header, TIB,
statics, layout) · `writer/` (layout + relocate + emit `kernel8.img`) ·
`vm/` (VM.boot, class loader, memory mgr) · `board/bcm2711/` (UART, GPIO,
mailbox, later GIC-400, timers).

The boot-path magic intrinsic list (system-register moves, `ERET`, barriers,
`WFE`/`WFI`/`SEV`, `TLBI`, `IC`/`DC`, load/store) is in `PLAN.md` §5.1 — that set
defines the minimum the assembler must encode.

## Current status

- **Late link resolution — the call sites RTA cannot see (2026-08-27, PI-VALIDATED).** RTA marks a method
  reachable then walks its body to mark what IT calls; reflection breaks that chain. A method reached only
  through `Method.invoke` compiles on demand, but nothing statically reachable names it, so its OWN callees
  are never marked and their classes never pulled — and `patchRelocs` pointed every one of its call sites at
  `VM.denylistTrap`. Right for a genuinely pruned class, wrong for one merely absent from a closure computed
  without ever reading this body. An unresolved site whose callee is NOT denylisted now gets a **link stub**:
  on FIRST call (so only if the site is actually reached) it demand-loads the class and resolves through the
  same three-tier lookup `resolveBakeStub` uses for the baked world, memoized. A cold site costs one 32-byte
  stub and never runs.
  - The trampoline is the twin of the lazy-compile one and preserves x0..x15 for the same reason (a whole
    demand-load runs between entry and the tail-branch). **Restoring LR before branching is what keeps the
    fallback honest:** an unresolvable callee still lands in `denylistTrap` with its x30-keyed trapwire index
    and stack walk reading exactly as they do for a direct call.
  - **The gap is an unpulled CLASS, not an unmarked method.** `demo/ReflectRtaDemo`'s third arm is the
    control: also reflective, but calling a class the direct arm pulled — it resolves at patch time with no
    late link at all, because a registered class already stubs every one of its methods. That corrected a
    wrong assumption mid-implementation.
  - **Pi (`core 166MHz`, full suite):** the batch's `load` list contains `demo/RtaSeen` and never mentions
    `demo/RtaUnseen`; then `linkresolve demo/RtaUnseen.tag` + `phaseA: 1 cells ... for demo/RtaUnseen` mid-run
    and `reflective = unseen`. Negative control (link-stub path disabled): the direct arm still passes and the
    reflective arm ends in a DENYLIST TRAP at `ReflectRtaDemo.viaReflectionOnly` naming `RtaUnseen.tag`.
  - **Static methods on interfaces needed a fourth tier, and the zip harness found it.** `bufBySigU`'s three
    tiers all answer through a DISPATCH table (registered buffer, static cell, vtable slot), and
    `registerInterface` walks only `isVirtual` methods to hand out itable indices — so a STATIC interface
    method is registered nowhere at all. JUnit's `Arguments.of` is exactly that, and it is what a
    reflectively-reached `@MethodSource` factory calls: the stub fired and had nothing to resolve to.
    `compileSigOnDemand` compiles that one method from the class's own blob, matching name AND DESCRIPTOR
    (`of(T)` vs varargs `of(T...)`), and patches only its own reloc range so its callees can take stubs too.
    This is the interface half of `compileMethodOnDemand`'s recorded limitation (it refuses interfaces on the
    grounds it has no TIB to reuse — but `compileReuseTib` means it never touches one).
  - **`new` is covered now (`newresolve`) — PI-VALIDATED SIX LEVELS DEEP.** A `new` site defers instead of
    halting: reached, it demand-loads the class and allocates at the right size with the class's own TIB
    (`VM.newUnresolved` returns the reference the emit already pushed). With the harness seed removed, the Pi
    walks the whole chain — `Arguments.of` → `Stream.of` → `Spliterators.spliterator` → `newresolve
    Spliterators$ArraySpliterator` → `StreamSupport.stream` → `newresolve ReferencePipeline$Head` →
    `StreamOpFlag.fromCharacteristics` — demand-loading ~20 classes (`ReferencePipeline`, `AbstractPipeline`,
    `PipelineHelper`, `EnumMap` + 9 nested) as it goes. Resolution is not the blocker any more.
  - **What the seedless run died of next, and one earlier reading that was wrong.** A first Pi run was
    cut short and read as "too slow to finish"; it is slow (each demand-load is a full structure pass plus a
    `patchRelocs` over every reloc so far, and load time is super-linear) but it DOES complete, and then died:
    `ArrayIndexOutOfBoundsException at java/util/EnumMap.getKeyUniverse(EnumMap.java:751)`, whose whole body is
    `SharedSecrets.getJavaLangAccess().getEnumConstantsShared(keyType)` — one `invokeinterface`. A bare AIOOBE
    at a dispatch is this VM's null-vtable/itable guard: the RTA-pruned itable entry below, reached for real
    rather than only in a demo. Fixed by `mintPrunedStub`.
  - **The denylist guard on the `new` path is load-bearing:** the call path is guarded at patch time, but a
    `new` site is recorded during the compile and `pullClass(byte[])` goes straight to the classDir without
    consulting the denylist. Without an explicit check, resolution would pull a denylisted class and turn
    `demo/UnresolvedNewDemo` — whose whole point is that this halts — into a silent pass.
  - **An itable entry left empty by RTA pruning — FIXED.** `buildItableFor` fills each interface-method entry
    with `slotBuf(vs)`, which is 0 when the impl has a Code attribute but was never pulled into the batch
    (nothing statically reachable called it) and so never got a deferral stub. An interface call reaching it
    later hits `dispatchTargetGuard` as a **bare AIOOBE** — which is precisely what
    `SharedSecrets.getJavaLangAccess().getEnumConstantsShared(...)` inside `EnumMap.getKeyUniverse` does once
    its caller arrives through demand-loaded code, and what stopped the seedless zip run.
    `mintPrunedStub` mints the deferral stub there (it cannot reuse `emitDeferredStub`, which works off the
    per-method compile arrays that exist only for batched methods; everything needed is in the vtable entry,
    with `maxLocals`/`codeLen` read back from the Code attribute's header fields).
    - **Read the two guards to tell the cases apart:** the itable directory-miss sentinel throws **NPE**;
      `dispatchTargetGuard` (implausible target word) throws **AIOOBE**. AIOOBE therefore means the interface
      WAS found on the receiver and the slot's entry was empty — not a missing interface.
    - **Scope is deliberate and measured.** Minting for every vtable slot costs **3-4x the code arena per
      batch** (8.1K→30.9K, 20.3K→65.9K, 22.9K→91.2K) and buys nothing for plain virtual dispatch: RTA marks
      all virtuals of an INSTANTIATED class, and a class never instantiated can never be a receiver. Confining
      it to itable entries costs **~1.4x** (8.1K→11.6K, 20.3K→27.2K, 22.9K→33.1K).
    - `demo/ReflectRtaDemo`'s `ifaceprune` arm reproduces the whole thing in ten lines instead of 400 classes;
      `ifacecall` is its control (same interface, statically reachable, entry filled). `demo/EnumMapDemo`
      pins that EnumMap itself is fine in a small closure — the bug was never EnumMap, it was the context.
  - **Found, NOT fixed — a DIFFERENT dispatch gap: `invokevirtual` on a class unregistered at compile time.**
    `globalVtableSlot` returns 0 when it finds no match, so the call dispatches through vtable slot 0 of
    whatever the receiver is. Writing the demo arm as `new RtaMade().tag()` returned null through exactly
    that path; the arm returns the object instead, and the caller checks `getClass().getName()`.
  - **A deferred `new` SKIPPED its constructor — the last gap.** `isRealSpecial` treated an `invokespecial`
    as a real call only if the target class was already registered; a class that had just been resolved late
    is not, so `<init>` was lowered as a pop and the object came back raw. That is what NPE'd in
    `AbstractPipeline.isParallel` — `ReferencePipeline$Head`'s three-deep constructor chain never ran, so
    `sourceStage` was null. `classLoadable` widens the test to "in the classDir and not denied".
  - **DONE, PI-VALIDATED 2026-08-28 (PR #192): `zip junit: ran 29, failures 0` / `ALL PASSED` with
    `seedFactoryClosure` DELETED.** The log walks the whole reflective closure — `Arguments.of` →
    `Stream.of` → `Spliterators.spliterator` → `newresolve ArraySpliterator` → its `<init>` →
    `StreamSupport.stream` → `newresolve ReferencePipeline$Head` → `<init>` → `ReferencePipeline.<init>` →
    `AbstractPipeline.<init>` → `StreamOpFlag.fromCharacteristics` (pulling EnumMap + 10 nested) →
    `newresolve Spliterators$1Adapter` → `lambda$of$0`. **Each of the five gaps was invisible until the one
    before it was fixed** — one Pi boot per layer — so a fix that "exposes the next layer" is progress here.
    Seeding's price, now unpaid: the closure was **446 classes with the seed and 354 without**.
    - **Cost of minting: ~700 stubs over that 354-class closure**, nearly all `MetalJavaLangAccess`'s ~100
      interface methods and the java.time/collections families' unused overrides — arena pressure at load
      time for methods that never run. Lever if it matters: mint on first dispatch, not at itable-build time.
    - **Two slots legitimately have no bytecode and must stay 0**, and only the instrumented boot could say
      which: an ABSTRACT method the class declares itself (`AbstractCollection.iterator`,
      `AbstractMap.entrySet`, `AbstractList.size` all reach it) and a native with no VM helper. The guard had
      shipped with "native" as its stated cause on a reading-only diagnosis, and that was never confirmed —
      **when the Pi is the only harness that reaches a failure, spend the boot on an instrument, not a guess.**
- **Demand-load speed — `loadAll` 1712s -> 10.09s on the zip suite, 170x (2026-08-28, PI-VALIDATED).**
  `markReachable` was ~99% of every demand-load and **flat**: adding ONE class cost the same 219 s as adding
  thirteen, because the whole closure was re-derived from scratch each batch. First batch 357s -> 5.0s;
  each incremental 219s -> 8-93ms. `ALL PASSED` and the same linkresolve/newresolve chain throughout.
  - **The `load <cls> NNus` line is a DECOY** — it times only `addBlob` (putting a blob on the pending list),
    so it reads 5-180us while its batch takes minutes. `LOAD_PROFILE` in `vm/Loader` (off by default, like
    `LAZY_TRACE`) times each phase. **It killed two confident guesses:** `patchRelocs`'s linear registry scan
    (92 ms, not the problem) and my estimate that loop inversion would buy ~3x on a first batch (28.9x).
  - **Most of the work was being DISCARDED.** Phase B is guarded by `pdDoneB[i] == 0`, so a blob compiled by
    an earlier batch is never recompiled and its TIB never rebuilt — marking its methods cannot compile
    anything. ~97% of every pass on an incremental load. `markSettled` skips them everywhere except
    `probeAll`, which fills the `pdNameOff` that `findPdByName` needs. **Safe only because late resolution
    exists** — an unmarked method in a settled class now costs a deferral/link stub, not a trap-wired site.
  - **Several inner loops ran the wrong way.** "For each pend, does this class define it?" is
    `pends x methods` compares per level; "for each method, is it pended?" is one hash probe per method
    (`resolveVirtuals`/`markDefaults`/`resolveBlob`). The check that the marked set did not change is
    `reach=233 pend=699` identical before and after every step.
  - **QEMU structurally cannot see the last two** — they scale with `pendN`, **699 in the QEMU closure and
    24,826 on hardware**. `pull` (a `nameRegistered` linear scan per pend) was 6,664 ms of the remaining
    12,364 ms; a name hash index took it to 1,539 ms. A hardware profile is not a nicety here — it is the only
    place these appear.
  - **A wrong diagnosis the boot caught:** I said `virt`'s residue was the per-class `virtResolved` RESET.
    Replacing it with a stamp bought 15% (3,471 → 2,946 ms), so it was not the bulk. What remains is the
    chain walk — `superPdOf` does a `parseConstPool` + linear `findPdByName` per level and `parseForMethods`
    re-parses a constant pool per level. **The ROUND COUNT increment is built and PI-VALIDATED, but only 1.18x
    (11.75s -> 10.09s).** Each pass carries a per-blob/per-class WATERMARK into the pend list, so a round
    costs only what is new. `rounds=33 reach=3092 pend=24826` identical. The gain split on one line: passes
    with no epoch collapsed (`seed` 281 -> 13.5ms, `collect` 713 -> 140ms); the three throttled by one barely
    moved (`virt` 2946 -> 2511, `pull` 1539 -> 1309, `static` 520 -> 448).
  - **A per-pend watermark is UNSOUND for a pass that walks the SUPERCLASS CHAIN** — the chain grows as
    ancestors are pulled, so a pend already considered for C can later resolve to an inherited method that was
    not visible then. `resolveVirtuals`/`resolveBlob` carry a blob-count EPOCH as well. Getting it wrong cost
    HALF the closure (reach 1040 -> 449) and killed `demo/StrOpsDemo` with a bare AIOOBE in `String.split` —
    **and only the demo SUITE caught it**: standalone built the correct closure even with the bug.
  - **Two levers left.** Precise ancestor-invalidation instead of the conservative epoch (that is what unlocks
    `virt`, still 2.5s); and **`pull`'s 1.3s is mostly UART** — 448 `load` lines at 115200, printed
    unconditionally, against ~20ms of actual `addBlob` time.
- **`demo/PipDemo` — priority inversion as a GUEST program (2026-08-27, PI-VALIDATED).** The last scheduler
  set piece with no guest equivalent; stock `Thread`/`setPriority`/`synchronized` only. `VM.pipDemo`,
  `VMScheduler.pipSpin`/`pipTask`, the `pip*` statics and the writer stash are removed. **MED is four threads
  on purpose:** inversion needs contention for a cpu, and the VM version was single-core only because it ran
  before the secondaries joined the run queue — a launched program must out-number the cores instead. Pi
  (`core 166MHz`, full suite): `finish HML ... HIGH blocked 61ms`; QEMU 63 ms, and the negative control with
  the one `inherit()` in `monEnter` commented out gives `MHL` / 270 ms.
  - **Three bugs, all of them only visible where the SUITE runs it** — single core, timer stopped by
    `prioDemo`, purely cooperative scheduling. Two of them produced a healthy-LOOKING `LHM / 0ms`, the third
    hung outright, and standalone (`main=demo/PipDemo`, four cores, timer live) printed a clean `HML` through
    all three. **A launched demo is not validated until it has run inside the suite.**
  - **A wall-clock critical section starting at acquisition has already expired** by the time the other five
    threads are created (>60 ms on an emulator), so HIGH found the lock free. LOW now takes the lock and
    BLOCKS on a `gate` monitor, burning nothing, until everyone is in position.
  - **The go signal comes from HIGH**, immediately before it blocks on the lock. HIGH outranks LOW, so nothing
    runs between the two: the section provably starts with HIGH already contending. Every handshake is a real
    `Object.wait` and not a yield loop, because a spin at one priority level locks the other levels out when
    there is no timer.
  - **`Thread.join()` is a yield-POLL — the joiner stays RUNNABLE and merely offers the cpu.** The boot
    flow's task sits at the scheduler's `PRIO_NORM` (512), which is ABOVE Java priority 5 (455), so main
    joining the MED threads at its default priority starved them forever: yield, still the most urgent
    runnable task, cpu straight back. The MEDs never entered `run()`. main drops to `MIN_PRIORITY` once setup
    is done. **A coordinator must not outrank the threads it waits for** — and the Java 1..10 scale does not
    reach `PRIO_NORM`, so "the default" is not neutral.
- **The demo suite runs demos as PROGRAMS (2026-08-27, PI-VALIDATED).** Every boot-suite demo is started by
  `Loader.launch(name, args)` — pulled from the classDir by name, `main(String[])`, argv, seeded
  `System.out/err/in`, run trampoline, `[main returned normally]` — instead of one of 24 bespoke
  `Loader.loadXxx()` methods calling a no-arg `main()` on a privately embedded blob. Those loaders and their
  24 duplicate demo blobs are gone (each demo had been embedded twice; image −62 KB). NetDemo, the
  VM-internal scheduler set pieces (`prioDemo`/`pipDemo`/`smpThreadsDemo`) and the two `Integer` probes are
  unchanged. **Cost, and the point: each launch reloads the base closure, so QEMU manages ~7 demos in 250 s
  and hardware is the only harness that sees the whole suite.**

- **Phase: M8 metacircular bootstrap — the writer-baked stock java.base and the
  on-metal demand loader are ONE VM** (shared vtable numbering, Types, statics,
  and a lazy cross-world link bridge). Earlier: M4 done, M5 started (shared
  JDK-free `ClassReader`/`A64Enc`), OS-runtime M3 (stock `Socket` HTTP GET).
- **STAGE 5 COMPLETE — every method body compiles on first call (PRs #102–#112,
  each Pi-validated).** The loader no longer compiles anything eagerly except
  `<init>`/`<clinit>`: a class is registered as METADATA plus dispatch stubs —
  phase-A offset cells for its statics, deferral stubs in its TIB slots for its
  virtuals — and each body is compiled the first time it is actually called.
  This holds for **all** classes now, `java.base` and guest code alike; the one
  exception is `java/lang/Object`, kept eager because its 9 virtuals are the
  prefix of every vtable in both worlds. NetDemo's boot log is the shape of it:
  ~1,900 cells armed at load, then `baked`/`jitc` lines as bodies materialize
  during a live TCP session.
  - **What "retire the eager loader" turned out to mean.** Eager whole-closure
    *compilation* is gone. The demand-load *batch* machinery (`resetLoader`,
    `markReachable`, `patchRelocs`, the `MAX*` caps) is independent of
    eagerness and stays: it allocates per-batch tables, prunes which classes get
    pulled, and resolves cross-class calls for initializers and every lazy
    compile (`patchRelocsFrom`). PLAN.md §"Stage 5" records this distinction.
  - **Five bugs eager compilation had been hiding**, each found by widening one
    prefix at a time and Pi-validating: a lazy compile's own relocs were never
    patched (a stale `bl 0` branches to address 0, which the firmware shim turns
    into a re-entry of the image entry); an inherited static never found its
    cell (javac names it through the subclass); a baked body calling a PROVIDED
    NATIVE had no resolution tier (`nativeBufAt` now serves both worlds); a
    reloc into a celled static had none either — which broke
    `NioSocketImpl.lambda$closerFor$0`, the Cleaner action `close()` runs; and
    lazily compiled bodies were invisible to stack traces (fixed by
    `rememberLazyBody`, else the socket stack reports as `InternalError.<init>`).
  - **Debugging note that cost a round trip:** QEMU has no CYW43, so its healthy
    ending is a `DENYLIST TRAP` at `NioSocketImpl.connect` — TRAPWIRE index =
    `jdk/internal/util/Exceptions.filterNonSocketInfo`, the exception *message
    formatter*. A Pi run whose `connect()` fails produces a **byte-identical**
    log. Always confirm whether a pasted log is Pi or QEMU, and re-run the
    unmodified image before concluding a regression (one such "regression" was a
    dropped SYN). `LAZY_TRACE = true` in `vm/Loader` prints a per-method `jitc`
    line and is the tool that resolved both of this arc's hard bugs.
- **Priority scheduling — 0..1024, strict, higher is more urgent. PI-VALIDATED, BOTH DEMOS
  (2026-08-26, `core 166MHz`): `demo/PrioDemo` prints `finish order = 10 8 6 5 3 1` (exact reverse of start
  order, no FAIL lines), and the suite's VM-level demo prints `finish HML (want HML)` under REAL preemption
  with philosophers + 41 GC collections + lisp fixpoint + WiFi HTTP 200 OK all unmoved.**
  `pickNext` takes the highest-priority runnable task and only rotates round-robin among EQUALS (the scan
  starts at `cur+1`, visits `cur` last, and must beat the incumbent strictly). `PRIO_NORM` = 512 is the
  default; spawned tasks inherit their creator's. **Starvation is by design** — no ageing or decay.
  - **Preemption latency is the other half.** Every task-context wake (`semPost`, `monExit`, `notify`,
    `notifyAll`, `unpark`, `interrupt`) ends in `preemptFor(woken)`: an O(1) compare that yields the core
    at once if the wakee outranks the waker, instead of losing up to a 10 ms quantum to inversion.
    `semPostRaw` deliberately does NOT preempt (ISR path) — it only RETURNS the woken task.
  - **Contended resources go up the priority order too:** `semPostRaw`/`wakeMonWaiter`/`objNotify` wake the
    MOST URGENT waiter, not the lowest-numbered. Picking the best task but handing a released monitor to
    whoever is first in the table is only half a priority scheduler.
  - **APIs:** `VMScheduler.setTaskPriority(task,prio)` raw 0..1024 (yields when a task lowers its own);
    stock `Thread.setPriority`/`getPriority` on Java's 1..10 mapped linearly `(p-1)*1024/9`; and
    `Magic.setprio`/`getprio` for all 1025 levels. A priority set BEFORE `start()` is remembered in the
    `Thread` and applied at start — there is no task to retarget yet, and dropping it silently is the easy
    bug.
  - **QEMU:** VM demo `finish HML (want HML)` with tasks spawned LOW first; `demo/PrioDemo` (stock
    `Thread` + `synchronized` only, six threads started ASCENDING, funnelled through one monitor so the
    result is core-count independent) prints `finish order = 10 8 6 5 3 1` — the exact reverse of start
    order — with `getPriority` round-tripping before start and while running.
  - **PRIORITY INHERITANCE — PI-VALIDATED with a negative control (2026-08-26, `core 166MHz`, full suite:
    `priority inversion ... finish HML ... HIGH blocked 30ms`, same order and latency as QEMU, with
    everything around it unmoved).** Two priorities per
    task: `taskBasePrio` (asked for, what getPriority reports) and `taskPrio` (what the scheduler uses =
    base raised to the most urgent waiter on a monitor this task holds). `inherit()` in `monEnter` lends
    down the ownership CHAIN (nested monitors stall one link further down otherwise; a hop cap breaks
    cycles); `recomputePrio()` on release re-derives rather than resetting, because one task can hold
    several contended monitors — guarded on `taskPrio != taskBasePrio` so an uncontended `monExit` pays
    nothing. `setTaskPriority` sets the base and re-derives (a live boost survives a lowered base); spawn
    inherits the creator's BASE, never a borrowed boost.
    - **Negative control, the part that makes it evidence:** with the one `inherit` call commented out the
      demo prints `MHL / HIGH blocked 80ms` (MED's whole run); restored, `HML / 30ms` (just LOW's critical
      section).
    - **`pipSpin` yields once per ms on purpose:** a non-yielding task can only be preempted by a timer
      tick and **QEMU delivers none**, so LOW ran to completion and released before HIGH woke — printing
      `LHM / 0ms`, a healthy-looking result that tested nothing.
    - **A boot can predate the flash.** The first Pi log of this change printed NOTHING from `pipDemo` —
      not even its unconditional header — while the calls on either side of it ran. Impossible for code
      that is present, and it was simply a boot from the card's pre-flash contents. Two checks settle that
      class of question before theorising: `cmp` the mounted card against `sdcard/kernel8.img`, and prove
      the method is compiled AND called — `JOENG_SYMMAP=1 make image` prints every method's `[start,end)`,
      and scanning the image for `BL` words targeting that range names the call sites (`pipDemo <- 0x823c4`,
      wedged between its two neighbours' `BL`s, so no execution path can skip it).
  - **Gaps:** no ageing (starvation permanent); inheritance covers monitors only, not semaphores (no single
    owner to boost); it is basic inheritance, not priority CEILING, so it bounds blocking without
    preventing deadlock; the per-switch scan is O(taskCount).

- **SMP scheduling — one run queue, four cores. PI-VALIDATED BOTH PATHS (2026-08-26, `core 166MHz`):
  suite `smp sched: 4 of 4 cores on the run queue`, `steps/core: c0=61 c1=60 c2=60 c3=59` under REAL
  preemption (`ticks/core c1=50 c2=50 c3=50`), with philosophers + 41 GC collections + lisp fixpoint +
  WiFi HTTP 200 OK all unmoved and no STW TIMEOUT; launch path `demo/SmpDemo` 800/800 across four cores.** The real scheduler (the one behind
  `Thread.start`, monitors, `Object.wait`, `LockSupport`) now runs on ALL FOUR A72s, not just core 0. The
  four cores were already awake; they ran two fixed set pieces (`smpWork`, `pcCoreMain`) and parked while
  Java threads time-sliced core 0. Now: one shared task table, `curTask` is per-core (`coreTask[core]`),
  `TASK_RUNNING` marks a task claimed, and every table transition goes through `schedLock()`/`schedUnlock()`
  (mask IRQs + the `LDAXR/STLXR` `SCHED_LOCK`) instead of IRQ masking alone — which only ever stopped the
  local core. Each secondary joins via `smpSchedulerMain`: its own banked PPI 30, core 0's vectors, a task
  slot for the flow it is running (its per-core IDLE task, pinned), then pause-and-yield forever, so every
  yield is a `pickNext` that pulls whatever is READY. `taskCore[]` pins only what cannot move: task 0 (image
  stack) and each idle task. Everything else migrates freely.
  - **The sharp edge, found by reading not running:** a task publishes `TASK_BLOCKED` (or a waker publishes
    `TASK_READY`) BEFORE the switch stub saves its context, so in that window `taskSp` is stale and another
    core seeing it READY would resume a stale frame — one task, two cores. `VMScheduler.claimable` fixes it
    with no new state: a task is claimable only when no other scheduling core still has it as its
    `coreTask`. Plain preemption never had the window (the save is inside `pickNext`, under the lock).
  - **Stop-the-world**, because the collector is not concurrent: `gcStop` → every other core parks in
    `pickNext` (timer tick or idle yield) with its context already saved (which is also how the trace still
    sees what it held) → mark/sweep → release. A core that never parks within ~1 s counts `stwTimeouts` and
    says so out loud, rather than marking against a live mutator.
  - **A loader MUTEX**, because the on-metal JIT keeps its compile context in statics and the code arena is
    one unguarded bump pointer: `VM.loaderLock()/loaderUnlock()` guard `Loader.lazyCompile`, `VM.bakeResolve`
    and `Heap.allocCode`. Not a spinlock (a waiter would hold the core the holder needs) — it is built on
    `SCHED_LOCK` + `taskYield()`, owned by TASK (a compiling task can migrate) and recursive (a `<clinit>`
    inside a compile re-enters).
  - **On by default for launched programs**: `bringUpSecondaries` + `startSmpScheduling` now run BEFORE
    `launchInit`, gated by `/etc/init`'s `smp=` (absent = on, `smp=0` = off). The demo suite keeps its two
    set pieces and adds `smpThreadsDemo`.
  - **Pi-validated (2026-08-26, `core 166MHz`):** `demo/SmpDemo` — four ordinary `java.lang.Thread`s,
    `synchronized` on a shared counter, `join` — prints `core 0 steps 262 | core 1 42 | core 2 318 |
    core 3 178`, `total 800 of 800`, `[main returned normally]`. Every core non-zero and the total EXACT
    (the cross-core monitor lost nothing), with the whole 54-class demand-load prologue running while four
    cores scheduled. QEMU suite: `smp sched: 4 of 4 cores on the run queue`, `steps/core: c0=144 c1=35
    c2=27 c3=34`, no FAULT/STW TIMEOUT, philosophers + lisp fixpoint unchanged. `Magic.mpidr()` was added
    as the guest-callable spelling of `readMPIDR` (the metal JIT's magic table packs a name into a long,
    so nine characters cannot match).
  - **First hardware bug — the lock word nobody zeroed.** The first Pi boot stopped dead one line after
    `SMP: 4 of 4 cores up`: `SCHED_LOCK` is raw scratch RAM (`0x0302_0040`), not a Java field, and
    `Magic.spinLock` spins WHILE THE WORD IS NON-ZERO. QEMU hands out zeroed DRAM so it read as free;
    a real Pi's DRAM is firmware leftovers, so core 0's next timer tick entered `pickNext` → `schedLock`
    with IRQs already masked and never returned. `bringUpSecondaries` had always zeroed `LOCK_ADDR` for
    the job demo; the new lock just never got the same line. **Lesson: a raw-memory lock/flag needs an
    explicit initialiser — and `Heap.allocArray` does NOT zero elements either**, so `new int[4]` reading
    as zeroes is a QEMU accident too (`taskIdle`/`coreSched`/`gcParked` are now filled explicitly; garbage
    there would have made `stopTheWorld` believe a running core was parked).
  - **Second hardware bug — JIT'd code published to ONE I-cache out of four.** The next boot reached
    cross-core scheduling and faulted `ESR EC=0` (undefined instruction) at offset **+0** of
    `java/lang/Thread.sleep`, a method that reads back perfectly in memory. `Heap.publishCode` ended with
    `IC IALLU` — LOCAL to the calling PE. Fine while only core 0 ran JIT'd code; fatal once another core
    runs a method core 0 compiled, because the code arena REUSES swept buffers, so that core's I-cache
    holds stale/zeroed lines for that exact address. **Maintenance BY VA is broadcast to the Inner
    Shareable domain; "all" flavours are not** — publish is now `DC CVAU`/`DSB`/`IC IVAU`/`DSB`/`ISB` per
    line (new `IC IVAU` intrinsic, `SYS #3,c7,c5,#1` = `0xD50B7520|Rt`). The other cores' `ISB` is free:
    they only reach new code through an `ERET`. A console lock (`Uart.lock/unlock`, owner-by-core,
    recursive, armed with SMP) was needed to read the report at all — two cores' traces had interleaved
    byte by byte.
  - **Third hardware bug — the set-piece demo STRANDED the cores.** Full suite on hardware said
    `smp sched: 1 of 4 cores on the run queue`, `steps/core: c0=240 c1=0 c2=0 c3=0`, where QEMU said 4 of 4.
    `pcSchedule`'s stop path disabled the timer and resumed WHICHEVER task it interrupted; if that was
    `pcTask1` (whose exit is an unconditional `WFE` park) the core stranded, because the tick meant to
    "switch to task 0 later" was the one just disabled — so it never returned from `pcCoreMain` and never
    joined the run queue. A coin flip per core on hardware, impossible on QEMU (no timer PPI reaches a
    secondary there, so its cores never leave task 0). Stop now always resumes task 0.
  - **Fourth finding: NOT a bug — the demo outran itself.** With the strand fixed, hardware said
    `smp sched: 4 of 4` and still `steps/core: c0=240 c1=0 c2=0 c3=0`. `smpTask` did no work per step, so
    the whole run is 240 context switches — core 0 finishes them in well under a millisecond, while each
    secondary sits in a 1 ms back-off before offering itself once. QEMU hid it the other way (counter near
    real time, execution ~100x slower, so 1 ms leaves plenty to share). Idle now YIELDS FIRST then backs
    off, and each step spends ~1 ms. QEMU: `c0=61 c1=59 c2=60 c3=60`. **Lesson: `c0=everything` + zeroes
    reads exactly like "the secondaries never joined" and can equally mean "nothing was left to take."**
  - **Known gaps:** reflection-driven loading (`forName`/`defineClass`) is not under the loader lock;
    secondary arenas are still never collected; the queue is plain round robin with no balancing or
    priorities; ordinary log output is still unlocked (only fault reports take the console).
- **Write side too: `zip/Deflate` (STORED blocks) + `Deflater`/`Adler32` overlays.** A stored
  block is a first-class DEFLATE type, so the output is valid, conforming, and simply not
  smaller; that buys `Deflater`/`DeflaterOutputStream`/`ZipOutputStream` for a fraction of an
  LZ77+Huffman implementation. Proof is the JDK's OWN `Inflater` decoding our output (raw and
  zlib-wrapped, any buffer size) in `test/zip/ZipTest`. `ZipOutputStream.<clinit>` is
  `clinitBlocked` (it only reads a system property). Stock OpenJDK jtreg tests from
  `java/util/{jar,zip}`: 15 of the 19 runnable ones pass. **The JUnit half too** (2026-08-26): of the 32
  `@run junit` zip tests, 21 need `java.nio.file` (they write a temp archive) and 7 run on metal via a
  hand-written runner — joe-ng cannot host the JUnit engine, so a main() calls each `@Test` on a fresh
  instance. Needed a bigger `org.junit.jupiter` shim (assertEquals/assertNotNull/assertArrayEquals/
  assertSame, `@BeforeEach`, `params`) and five REAL java.base gaps: `Throwable.addSuppressed`/
  `getSuppressed` (**javac lowers EVERY try-with-resources into `addSuppressed` — a language feature was
  unimplemented and nothing had noticed**), `Inflater`/`Deflater` not `AutoCloseable` (JDK 24 added
  `close()`), `ByteBuffer` absolute accessors + `wrap` + settable `ByteOrder` (zip headers are little-endian,
  the ByteBuffer default is big-endian — silent when wrong), a missing `java/nio/ByteOrder`, and `ZipFile`'s
  header constants. Plus `Collections.enumeration`, found by a DENYLIST TRAP whose backtrace named it
  outright: `GZIPInputStream.readTrailer` builds a `SequenceInputStream`, whose 2-arg ctor is
  `this(Collections.enumeration(Arrays.asList(...)))`, and the overlay had only sort/unmodifiableSet/
  emptySet. **PI-VALIDATED 29/29** (2026-08-26) — every stock `java/util/zip` JUnit test joe-ng can host passes on
  hardware (`zip junit: ran 29, failures 0`), the `@ParameterizedTest` one included: its private
  `@MethodSource` factory is reached reflectively (`getDeclaredMethod` + `setAccessible` + `invoke`) and its
  stream consumed with `iterator()` — the open Stream bug is in `map`/`collect`, which `Stream.of(...)
  .iterator()` never touches. Two more bugs fell out of it. **(1) A LAMBDA WAS NOT AN INSTANCE OF `Object`:**
  `finishLambdaClass` set the lambda Type's `superType = 0` ("Object(0)"), but `typeAssignable` walks self →
  itable dir → superType, so every `aastore` of a lambda into an `Object[]` threw `ArrayStoreException` —
  i.e. EVERY varargs call taking a lambda. One line (`superType = objectTypeAddr()`). **(2) RTA cannot see
  through reflection:** a method reached only via `Method.invoke` compiles, but nothing statically reachable
  mentions ITS callees, so they are never pulled and its call sites trap (`logTrapWire = 1` names them).
  Worked around at the time at the HARNESS level (`seedFactoryClosure` called the same methods from reachable
  code); seeding had to match the DESCRIPTOR, not the name — `Stream.of(T)` does not satisfy a `Stream.of(T...)` site.
  The principled fix — late resolution at the trap site, reusing `resolveBakeStub`'s demand-load+memoize — is
  BUILT and Pi-validated now (PR #192, 2026-08-28), and the seed is deleted; see the late-resolution bullets
  above. The first boot of the 22-case suite was 15/22: DeflaterClose 3/3, InflaterClose 3/3,
  GZIPInputStreamAvailable, both DataDescriptor tests, CloseWrappedStream 6/6 (its log shows
  `baked java/lang/Throwable.addSuppressed`/`getSuppressed` — the tests that need suppressed exceptions are
  the ones exercising the new support). The 7 `Zip64DataDescriptor` failures were ONE bug:
  **`HexFormat.of()` returned null because its `<clinit>` was SILENTLY skipped.** `clinitCompilable` allows
  the `desiredAssertionStatus` idiom only when it is the WHOLE initializer; HexFormat does the idiom PLUS
  real work, and the rule's premise (such classes are clinitBlocked/seeded anyway) did not hold for it — so
  it fell between the two policies. Allowlisted like Pattern/Socket/ImmutableCollections. **The lesson is the
  SILENCE: a rejected initializer is indistinguishable from one that ran — the class loads, gets static
  cells, reports lazy-init pending, and answers null forever.** **A `ZipOutputStream` closure is ~500 classes and load time is super-linear (302 classes in 35 s, +70
  in the next 115 s), so QEMU cannot finish those four — ONE combined image (`ZipJUnitAll`, wired into
  `JDKTESTS`) plus a Pi boot is the only practical harness. Adding methods to Throwable widened its vtable
  12 → 14 and `vtparity java/lang/Throwable OK 14` still holds, because both worlds derive from the overlay.**
- **Jar/zip DONE — a program can ship as a jar and the VM runs it. Pi-validated.** `/etc/init` gained
  `classpath=<path>`: the named RAMFS archive goes on the class path, and any class the
  writer-baked directory lacks is inflated out of it on demand (`vm/JarFs` behind
  `VM.dirBytes`/`dirLen`, positively AND negatively cached). `main=app/Main
  classpath=/lib/app.jar` runs a program whose classes exist ONLY inside the archive.
  - **Engine (`zip/`, JDK-free, written from RFC 1951 + APPNOTE.TXT):** `Inflate` is a
    STREAMING, RESUMABLE raw-DEFLATE decoder (32 KiB mirror window so back-references
    survive the caller taking the bytes; mark/rewind of the bit position so a half-read
    Huffman code re-reads once more input arrives), `Huff` the canonical code table,
    `ZipDir` the central-directory reader, `Crc32` the checksum. The SAME source is baked
    into the image (for the loader) AND demand-loaded into the guest world (for the
    overlays) — one decoder, no bridging native. `zip/` is on the demand-loadable prefix
    list in `ImageBuilder.demandLoadable` for exactly that reason.
  - **Stock API on top:** UNMODIFIED `ZipInputStream`/`InflaterInputStream`/`ZipEntry`/
    `JarInputStream`/`JarEntry`/`Manifest`/`Attributes` run on metal. Overlaid only where
    the stock class is a shell over native zlib or `java.nio.file`/`RandomAccessFile`:
    `java/util/zip/{Inflater,CRC32,ZipUtils,ZipCoder,ZipFile}`, `java/util/jar/JarFile`,
    `jdk/internal/misc/CDS`. `java/io/FileInputStream` now extends stock `InputStream`.
    `demo/ZipDemo` walks a jar entry-by-entry with CRCs matching the host byte-for-byte;
    `demo/JarDemo` does `JarInputStream`+`JarFile`+`Manifest` and loads a class out of the
    jar with `defineClass`. `sun/security/` + `JarVerifier` are denylisted (an unsigned jar
    never runs them; verifying would pull the whole provider closure) — construct
    `new JarInputStream(in, false)`.
  - **Int shift COUNTS now mask to 5 bits** (`Baseline.maskShiftCount`), Pi-validated, as the JVM specifies —
    the 64-bit shift instructions use 6, so `x << 32` answered 0 instead of `x`, and
    `Integer.rotateLeft(x, 32)` (the hashing rotate idiom at distance 0) returned 0. Long forms
    are already correct and emit nothing. `demo/ShiftDemo` pins it.
  - **Three real VM bugs it uncovered** (all pre-existing, all fixed; PLAN.md "Jar / zip on
    metal" has the detail): int arithmetic did not stay sign-extended on OVERFLOW, so
    `idiv`/`irem` (64-bit SDIVs) saw huge positives — `Math.floorMod(String.hashCode(), n)`
    went negative and `Map.copyOf` walked off `MapN`'s table (`Baseline.canonInt`);
    constructor-time active uses were never initialized, because `<init>` compiles at load
    time when the lazy-init collector is off (`new ZipInputStream` read a null
    `UTF_8.INSTANCE`); and a NATIVE instance method left a 0 vtable slot, so `String.intern()`
    hit the null-vtable guard as a nameless AIOOBE.
  - **Pi run (2026-08-25, `core 166MHz`):** `classpath /lib/app.jar entries=5` → `launch app/Main`
    → `load app/Greeting` → `hello from a jar` / `hello, world (7 consonants)` / `sum 0..10 = 55`
    → `[main returned normally]`, boot battery clean. `demo/JarDemo` Pi-validated in the same session:
    `manifest mainClass=app.Main`, `crc=86caf830` (matches `unzip -v`), `Greeting.text() = hello, jar`,
    with `Attributes$Name`/`ImmutableCollections` clinits firing and the guest-world `zip/*` demand-loaded
    beside the baked copies. `demo/ZipDemo` too: every entry's CRC, computed on the Pi over bytes our
    inflater produced (drained through a 37-byte buffer, so the decode resumes mid-block/mid-LZ-copy
    constantly), matches `unzip -v` byte-for-byte. WHOLE ARC PI-VALIDATED.
  - **defineClass vtable hole FIXED (`Loader.rootBlob`), Pi-validated.** `defineFromBytes` seeded reachability
    from `<clinit>` alone, so a class without one had EVERY method pruned by RTA and `fillTib`
    filled a vtable of zeros — the first virtual call inside it hit the null-vtable guard as a
    bare AIOOBE (reflection still worked, since that goes through the method registry). A blob
    handed to `defineClass` is now a root in its entirety: every method seeded, and the class
    flagged instantiated so its INHERITED virtuals get marked too. This also closed the
    cross-batch gap for free — a class defined in a 2nd batch now `new`s and virtually calls one
    from the 1st. `Class.forName`'s incremental path is fixed too, but DIFFERENTLY
    (`Loader.stubBlob`): eager seeding there blew the closure and corrupted the heap, so its own
    virtuals get deferral STUBS without being marked reachable — a full vtable and a pulled
    closure turn out to be independent, and only RTA marking conflated them. A stub pulls
    nothing; the body compiles on first call. Pi-validated: `demo/ForNameVirtualDemo` pins both,
    and `forName("java.util.regex.Pattern")` registers 54 static cells + stubs its virtuals while
    pulling just TWO extra classes (its `<clinit>`'s own deps) — seeding would have pulled the
    regex engine.
  - **Dispatch-target guard (`Baseline.dispatchTargetGuard`).** Both dispatch paths already
    guarded the resolved target, but the ceiling was `>> 28` (`0x1000_0000` = `Heap.LARGE_LIMIT`),
    so every heap pointer passed and a bad slot still wild-branched. Now a top-byte compare against
    `Symbols.CODE_TOP_BYTE_MAX` (code lives below `Heap.CODE_LIMIT` `0x0300_0000`; the heap starts
    at `0x0400_0000`), shared by the virtual and interface sites. It immediately turned a nameless
    hardware trap into `AIOOBE at TestAttrsNL.test:115` — an interface DEFAULT method (`Map.forEach`)
    on the second implementor to reach it. FIXED below.
  - **Interface DEFAULT methods now dispatch from a class-typed receiver.** `attrs.forEach(...)`
    is an `invokevirtual` (class-typed receiver), but a class inherits a default from an INTERFACE,
    and neither flattener puts defaults in a class vtable — so resolution fell through to a
    name+descriptor match on an unrelated class, giving an index past the end of the receiver's TIB.
    Adding defaults to both flatteners would renumber every vtable and `vtparity` asserts those equal
    across the two worlds, so the call is ROUTED through the itable instead
    (`Symbols.defaultDispatch`; `Baseline.itableDispatch` is now shared with `invokeinterface`).
    `demo/DefaultIfaceDemo` pins it, including the two shapes that always worked (a `Map`-typed
    receiver, and `LinkedHashMap`, which overrides `forEach`). **Pi-validated on the full demo suite**
    (2026-08-25): all parity assertions OK, 41 GC collections over `churnMB=625`, `lisp evals=600
    result=610 stable=1`, WiFi WPA2 → DHCP → DNS → TCP → HTTP 200 OK.
  - **`StringBuilder` implements `Appendable` now — `String.replaceAll` works.** The overlay
    `guestsrc/java/lang/StringBuilder` was `public final class StringBuilder` implementing NOTHING, while
    stock implements `Appendable, CharSequence`. Stock `Matcher.appendExpandedReplacement` declares its
    sink as `Appendable` and does `app.append(nextChar)` — an `invokeinterface` onto an object whose class
    had no such interface, so there was no itable to find and it surfaced as an NPE inside the Matcher
    frame. javac states the gap directly when compiling against the guest overlay: *"StringBuilder cannot
    be converted to Appendable"*. Fixed by implementing the interface (javac generates the three covariant
    bridges) plus the `append(CharSequence,int,int)` the interface requires and the `$n` group path uses.
    `vtparity java/lang/StringBuilder` 19 → 23, both worlds agreeing. `demo/RegexReplaceDemo` pins it, and
    the stock jtreg `jar/Attributes/TestAttrsNL` now PASSES (it was the test that reported the NPE).
    **Pi-validated on the full demo suite** (2026-08-25, `core 166MHz`): `vtparity java/lang/StringBuilder
    OK 23` in every batch, `load java/lang/Appendable` alongside it, 41 GC collections over `churnMB=625`,
    `lisp evals=600 result=610 stable=1`, WiFi WPA2 → DHCP → DNS → TCP → HTTP 200 OK (828 bytes).
    **Lesson worth keeping:** a name-winning overlay silently drops the stock class's INTERFACES, and
    nothing complains until some stock code dispatches through one.
  - **A failed `checkcast` throws `ClassCastException` now — it used to SPIN.** `VM.checkCast` ended a
    failing cast in `while (true) Magic.wfe()`, a leftover from before exceptions existed. That is the whole
    of the `PutAndPutAll` "hang": the test's first action is a deliberately-failing cast. A VM helper cannot
    throw for its caller (it has its own frame, so the handler search starts one frame too deep), so the
    helper became a PREDICATE — `VM.castOk(ref,type)` → 1/0, exactly `checkCast`'s logic with the halt
    replaced by a return — and `Baseline.checkCast` branches on it and throws INLINE via the existing
    `throwImplicit`, putting the casting method's pc/sp in front of the unwinder like the null/bounds/aastore
    checks already do. Metal JIT only; the writer stays check-free (`implicitChecks()`), so the self-hosting
    fixpoint is untouched. `java/lang/ClassCastException` was ALREADY pulled and flagged instantiated — the
    infrastructure had been prepared, only the throw was never wired. `demo/CastDemo` pins six shapes incl.
    cross-frame unwind and catching as `RuntimeException` (proving the thrown object has a real TIB).
    **Pi-validated on the full demo suite** (2026-08-26, `core 166MHz`): the instanceof/checkcast demo still
    prints `YNW` (the `W` is a SUCCEEDING checkcast, so the new predicate path doesn't throw on a good cast),
    both exception demos still print `E`/`U`, 41 GC collections over `churnMB=625`, `lisp evals=600
    result=610 stable=1`, WiFi WPA2 → DHCP → DNS → TCP → HTTP 200 OK (828 bytes).
  - **The `clinit-lazy java/lang/StrictMath` line was a red herring**, and worth remembering as one: it was
    merely the last thing PRINTED before the wedge, not the fault site. `demo/StrictMathDemo` runs that
    initializer to completion on its own. The last log line names where output stopped, not where control did.
  - **Known gaps:** none in `java/util/jar` — `PutAndPutAll` and `TestAttrsNL` both pass now.
  - **`emitNew` fallback FIXED, Pi-validated.** A `new` whose class isn't registered used to take the CURRENT
    class's TIB — a wrong-typed object, silently. Measuring first found 18 such sites over 5
    classes in a jar batch, ALL denylisted classes on never-taken branches, so a compile-time
    halt would have broken working boots. Instead `objectSize` returns `-(site+1)` and
    `Baseline.lowerNew` emits the `NEW_UNRESOLVED` helper in place of the allocation: reached, it
    halts naming the class AND source line; unreached, it costs nothing. The same-class case (a
    class `new`ing itself pre-registration) keeps the old fallback. `demo/UnresolvedNewDemo` is
    the regression — manifest-only, since it is EXPECTED to halt. Pi-validated as a NEGATIVE test:
    JarDemo on hardware with all 18 traps armed is byte-identical to the pre-fix run, no trap fired.
  - **Debug aid:** `JOENG_SYMMAP=1 make image` prints every image method's `[start,end)`, so
    a bare PC from a QEMU `info registers` can be named — a constant PC is a `checkCast`/
    `capHalt` spin.
- **World-unification arc DONE (PRs #85–#94, all Pi-validated).** The two
  parallel java.base worlds — writer-baked (image TIBs/Types/statics) vs
  loader-demand-loaded (metal-built) — are collapsed into ONE class identity per
  class, so baked code and loaded code exchange objects freely:
  - **One vtable numbering:** the writer flattens full registered super chains
    like the loader (Object's 9 virtuals prefix every vtable; the `isRoot` stop
    that discarded inherited slots is gone — a whole family of `isRoot` bugs in
    `vtable`/`allInterfaces`/`findImpl`/`addTypeClass`/field layout was fixed).
    Field layout is chain-aware to match (`ClassFile.chainFieldBase`, inherited
    fields FIRST). Boot asserts it: `vtparity <cls> OK n` per baked class.
  - **One Type node per class:** the writer emits a vtable-signature table
    (`vtSig`, stride-48 entries `{classUtf8, slotsAddr, count, typeAddr,
    staticsAddr, staticCount}`); the loader ADOPTS the writer's Type in phase A
    (`typeadopt` lines) instead of building its own → cross-world
    `instanceof`/`checkcast` compare the same node. Interfaces adopt too
    (slotsAddr=0 marker).
  - **One itable shape:** both worlds index itables by FLATTENED per-interface
    method lists (super-interface runs first, dedup keeps inherited position);
    loader global-ifm-index scheme replaced (per-interface `buildItableFor`,
    lambdas get per-entry itables by SAM sig). Boot asserts `itparity`.
  - **One static home:** baked classes get DENSE per-class static blocks in the
    writer statics region (declaration order = loader slot numbering); the
    loader adopts them (`adoptStatics` before structure registration) so loader
    clinits initialize the SHARED slots. Boot asserts `staticadopt`.
  - **Object-returning links (the finale):** every link-filter gate is lifted —
    the loader links lazy compiles straight to baked bodies (56 methods incl.
    `valueOf`/`toString`/`String.valueOf`). Unlinkable fringe methods become
    arg-preserving **resolution trampolines** (save x0..x7+LR, `movz stubIdx`,
    `BL VM.bakeResolve`, tail-branch): first call resolves via
    `Loader.resolveBakeStub` (demand-load the class into the RUNNING program +
    3-tier buffer lookup), memoized. Boot shows both directions live: `baked
    Integer.valueOf` (direct link) and `bakeresolve Integer.hashCode`/
    `String.getBytes` (lazy resolve).
  - **Cross-world gotcha fixed:** writer/boot arrays are RAW (element size in
    the header word, no Type node) while loader arrays carry real array Types.
    `checkCast` of a raw array to an array class now trusts the verifier
    (mirror of `instanceOf`'s conservative 0) instead of halting — a baked
    `Integer.toString`'s String value hit this inside `getBytes` and froze the
    Pi. `demo/PrintIntDemo` is the regression demo. Writer-array Types remain
    un-unified (array `instanceof` on writer arrays answers false) — future
    increment if it bites. Debug trick that found it: QEMU `-monitor unix:` +
    `info registers`; a constant PC = a `checkCast`/`capHalt` spin.
- **Bootstrap static-snapshot arc DONE (PRs #77–#84, all Pi-validated).** Baked
  stock classes get REAL static state without running their (native-heavy)
  `<clinit>`s on metal: the writer runs/defers them on the SEED JVM and
  snapshots the results into the image.
  - `BAKE_ROOTS` force-compiles stock methods into the image (addresses stashed
    in VM statics); `bakeNoClinit`/`clinitDeferred` defer initializers to a
    seed-JVM snapshot (`StaticSnapshot` reads the host class's statics via
    reflection and fills the image slots) — primitives AND object graphs:
    `bakeDiscover`/`writeBakedObject` deep-bake referenced objects (e.g. the
    whole `IntegerCache`) as real heap-layout image objects.
  - Baked classes carry real TIBs/vtables and baked String objects; stock
    methods that won't compile become stubs (`compileOrStub`; bake domain =
    `java/`, `jdk/`, `sun/` prefixes).
  - **Endgame:** the on-metal Loader consults a writer-emitted baked-link table
    in `lazyCompile` — a demand-loaded class's method whose signature matches a
    baked body links to it instead of JIT-compiling (`baked <cls>.<name>` boot
    lines), so the baked closure absorbs lazy-compile work in the live socket
    path (`Preconditions.checkFromIndexSize`, `Math.min`, `String.length`...).
  - Boot runs an 11-probe bootstrap battery (`Math`/`Integer`/`IntegerCache`/
    `valueOf`/`equals`/`toString`/`instanceof`/`compareTo`) gating every image.
- **OS-runtime M3 DONE — a stock `java.net.Socket` HTTP GET on bare metal.** The
  image now runs like a traditional JVM-on-an-OS: `VM.boot` brings up HW + WiFi,
  then `Loader.launch` runs the `main(String[])` named by the RAMFS `/etc/init`
  manifest (`main=`/`args=`/`net=1`; `BuildRuntimeImage --main/--args` writes it).
  `demo/NetDemo` does `new Socket("example.com",80)` → GET → **HTTP 200 OK + the
  full HTML body → clean `close()`** over **UNMODIFIED** `java/net/Socket` →
  `sun/nio/ch/{NioSocketImpl,Net,SocketDispatcher,IOUtil,NativeThread}` →
  `java/io/FileDescriptor`, backed by the all-Java `net/{Ip,Tcp}` stack + WiFi
  (verified on a real Pi 4). **Real-HW-only** (needs CYW43). The stock socket
  *logic* runs as-is; only the unavoidable floor is shimmed with name-winning
  `guestsrc/` overlays + a few loader/writer hooks:
  - **VarHandle shim (keeps `Socket` 100% stock):** `Socket` updates its `state`/
    `in`/`out` fields through a `VarHandle` (`STATE.getAndBitwiseOr`, `IN/OUT
    .compareAndSet`), which needs the denied `java.lang.invoke` runtime. Overlaid
    `java/lang/invoke/{VarHandle,MethodHandles}` + `jdk/internal/invoke/MhUtil`:
    the handle carries the field NAME and resolves its offset from the target
    object at call time (`VM.vhFieldOffset` via the class+field registries). Its
    signature-polymorphic call sites (`getAndBitwiseOr:(LSocket;I)I` etc.) are
    resolved by NAME only in `Loader.vtableSlotOf` and the ops are seeded (else a
    0 vtable slot); narrow-allowed past the `java/lang/invoke/` deny.
  - **Overlays:** no-op `ReentrantLock` (single-threaded → no AQS/MethodHandles),
    transparent `SocksSocketImpl` delegator (Socket ALWAYS wraps the platform impl
    in it — not a never-taken proxy), `Inet4Address` (+ `InetAddress.getByAddress`/
    `anyLocalAddress`/`isXxxAddress`/`getHostName`), `ByteBuffer`/`DirectBuffer`/
    `Util` (temp direct buffer = a heap `byte[]`, `address()`=`addrOf+24`),
    `Cleaner`/`CleanerFactory` (synchronous), `SocketOptionRegistry`,
    `sun/net/ext/ExtendedSocketOptions` (no-op), `Thread.isVirtual`→false.
  - **`<clinit>` handling:** `FileDescriptor.<clinit>` runs FIRST (registers the
    `JavaIOFileDescriptorAccess` that `NativeDispatcher`/`NioSocketImpl` read via
    `SharedSecrets`; else `getJavaIOFileDescriptorAccess`→`MethodHandles.lookup`
    trap). `Socket`/`NioSocketImpl`/`StandardSocketOptions.<clinit>` are allowed
    past the tag-7 `ldc Class` gate (assertions idiom / option constants) — they
    bind `STATE`/`nd`/`SO_LINGER`. `Inet4/6Address.<clinit>` (native `init()`) and
    `Net.<clinit>` (native-heavy, reads `System.getProperty` whose props are null →
    cascades to `Properties`/CHM) stay blocked; `Net.EXTENDED_OPTIONS` is instead
    SEEDED directly (`seedNetExtendedOptions`, like `System.out`) so
    `close()`→`Net.getSocketOption(SO_LINGER)` doesn't NPE.
  - **Natives (`Loader.nativeBuf` → `VM.*`, all static):** `Net.{socket0,connect0,
    available}`→`net.Tcp`, `SocketDispatcher.{read0,write0}`, `UnixDispatcher
    .close0`, `IOUtil.{fdVal,setfdVal}`, `FileDescriptor.{initIDs,getHandle,
    getAppend}`, `NativeThread.{current0,supportPendingSignals0,signal0}`,
    `InetAddress.resolve0`→WiFi DNS, `VarHandle.fieldOffset0`. The `fd` int (offset
    16) IS the `net.Tcp` handle. Narrow denials keep the closure tight (Poller/
    Exceptions/IPAddressUtil/ExtendedSocketOption trap on never-taken branches).
  - Full arc = the `os-runtime-m3` branch (M1 launcher → M2 `net.*` → M3 stock
    java.net). Credentials in the gitignored `ramfs/etc/wifi.conf` (never committed).
- **WiFi (CYW43455) DONE through M6 — an all-Java internet device.** The Pi 4's
  on-board WiFi is driven entirely in Java over SDIO (`board/cyw43/Cyw43` +
  `board/bcm2711/{Sdio,Gpio,Gic,Mailbox}`, no C): chip bring-up (firmware/NVRAM/
  CLM upload from RAMFS, SDPCM/BCDC framing), scan + open join (`joe-ng-open`),
  and a from-scratch TCP/IP stack (ARP/IPv4/ICMP/UDP/DHCP/DNS/TCP) → **HTTP GET
  returns 200 OK** on real hardware — the "internet device" acceptance test.
  **Real-HW-only** (QEMU `raspi4b` has no CYW43; the WiFi path is HW-gated on
  `Uart.coreHz` and skipped there) and runs as the boot finale after the full
  demo suite. **WPA2-PSK WORKS on real hardware (host supplicant, DONE).** The
  all-Java 4-way handshake runs a JDK-free crypto stack (SHA-1/HMAC-SHA1/PBKDF2/
  PRF/AES-128/RFC-3394 key-unwrap, `crypto/*`, 17 vectors in `CryptoTest`) and
  joins a WPA2 network → HTTP 200 OK. The old "banked — firmware won't relay
  EAPOL" conclusion was WRONG; five stacked bugs hid it, found by pairing UART
  traces with monitor captures: (1) EAPOL sent at BDC priority 7 (AC_VO) was
  dropped on the unauthorized port — use priority 0 (AC_BE) like brcmfmac; (2)
  `ourMac` was read at DHCP time, after `fourWay`, so the PTK/MIC used a zero MAC;
  (3) the authenticator address must be msg1's Ethernet source (the real BSSID),
  not a mis-parsed `WLC_GET_BSSID` (which returned the router MAC); (4) PBKDF2 +
  diagnostic ioctls in the msg1→msg2 path caused ~14 s latency, but the AP
  restarts the 4-way with a fresh ANonce ~1/s and drops stale replies — precompute
  the PMK pre-association and keep the path bare (~6 ms); (5) msg2's key-data RSN
  IE capabilities must be `0x000c` (match the firmware's association RSN IE, not
  `0x0000`) or the AP silently drops msg2 on the downgrade check. This firmware
  has NO in-chip supplicant (`sup_wpa` → -23), so the `WPA2_OFFLOAD` path is kept
  but disabled. **M6 IRQ-driven RX (latest,
  on main):** F2 receive is interrupt-driven — the SDIO card interrupt (GIC SPI
  158) is a *level* line gated at the **GIC** (`GICD_ICENABLER`/`ISENABLER`), not
  the SDHCI (masking there never de-asserts it and stormed core 0); the ISR
  (`Cyw43.onIrq` from `VM.schedule`) disables the SPI + posts `WIFI_SEM`, and every
  RX loop (first frame, ioctl, scan, join, DHCP/ARP/ICMP/DNS/TCP, EAPOL) blocks in
  **`VM.semWaitTimeout`** (block on a semaphore OR a CNTPCT deadline, so a lost
  frame times out instead of hanging) via `waitFrameIrq` instead of busy-polling —
  on wake it reads the frame, clears the SDIOD/SDHCI status, and re-arms the SPI.
  The chip only asserts once the CYW43 **SDIOD-core Intmask** (backplane
  `0x18004024` = FrameInt|MailboxInt|Fcchange) is set. Verified end-to-end on a
  real Pi 4 with a clean UART trace (no storm, no demo-task noise). Full detail in
  the `wifi-driver-arc` memory + PLAN.md "WiFi" section. Credentials live in the
  gitignored `ramfs/etc/wifi.conf` (never committed).
- **Loading a real JDK class on bare metal.** `BuildRuntimeImage` extracts
  `java/lang/Math.class` from the seed JDK's `java.base` (via
  `getResourceAsStream`, since it lives in `lib/modules`) and embeds the raw
  bytes. On the metal, `vm/Loader` parses it, finds `max(int,int)` by name+
  descriptor, JIT-compiles it, and runs it: QEMU prints `M` from
  `Math.max(0x4D,0x21)`. Works because `Math.max` is a pure leaf (iload/if_icmp/
  goto/ireturn) — no calls, fields, `<clinit>`, or native methods. Args are
  passed via `Magic.call2` (loader convention: slot0=x1, slot1=x2).
- **M5 (self-hosting) — first steps.** `vm/Loader`'s mini-JIT is now a real
  two-pass bytecode→A64 compiler (branch-target word map; JVM locals x1..x8,
  operand stack x9..x15; **operand-stack depth tracked at branch merges** like the
  writer-side compiler). Covers iconst/bipush/sipush, iload/istore, iadd/isub/imul,
  iinc, if/if_icmp, goto, ireturn, **getstatic/putstatic**, and now
  **`invokestatic`** — the loader parses the class's fields, assigns static slots,
  allocates a zeroed statics block, and resolves field refs (via all-cp-entry
  offsets: Fieldref→NameAndType→name). QEMU's `*` now round-trips through a loaded
  static field.
  - **`invokestatic` DONE (same-class):** the loader now compiles a whole *program*
    — the entry method plus every static method it transitively calls — in three
    flat passes: **discover** (BFS the call graph, resolving each Methodref→
    NameAndType to a same-class method's Code, deduped by bytecode address so cycles
    don't loop), **place** (pass1-size each method and hand it its own heap buffer),
    **emit** (now every `BL` target address is known). Each call lowers to a
    fixed-shape sequence: spill x30 + x1..x15 to a 128-byte SP frame, move the top
    `argc` operand-stack entries into x1.., `BL` the callee buffer, restore, and
    land `x0` on the stack. The **full spill** (all 15 value regs) keeps the emitted
    size independent of operand depth, so pass1 can size it, and makes a call whose
    result is combined with a still-live stack value correct. Args/return follow the
    loader convention (slot k = x(1+k), result x0). Three flat passes (not on-the-fly
    recursive compilation) sidestep the shared static compile-state and the
    writer-side ≤10-local ceiling. QEMU's `*` now flows through `Guest.answer()`
    → `outer()` → `inner()`×2 (`21+21=42`), a two-deep chain with a below-args call.
    **Limits:** same-class static calls only (no cross-class/JDK targets, no
    `invokevirtual`/`special`); no int-slot args beyond the ≤8-local convention;
    a callee reached from N classes-of-scope is fine but there's no recursion/cycle
    support beyond dedup, and each distinct method compiles once.
  - **`new` + instance fields DONE (same-class):** the loader now assigns each
    instance field a slot (offset `16 + slot*8` per `ObjectModel`) alongside the
    static slots, and captures the class's own name so a same-class check
    distinguishes `Guest.<init>` from `Object.<init>`. `new` allocates by calling
    the image's real `Heap.alloc` — its address is stashed in a writer-filled static
    `VM.heapAlloc`, and the on-metal `new` spills x1..x15 (same 128-byte frame as a
    call, since `Heap.alloc` clobbers the value regs), `movz` the size into x0, `BL`s
    it, nulls the TIB header, and pushes the ref. `getfield`/`putfield` lower to
    `ldr`/`str Xt,[obj,#off]`; `invokespecial` calls a same-class `<init>` with the
    receiver as the leading arg (reusing the call sequence with `thisArg=1`) and
    treats `Object.<init>` (any cross-class target) as a pop. Added `dup`,
    `aload/astore` (+_0..3), `areturn`, and void `return`. QEMU's `*` now flows
    through `new Guest()` → default `<init>` → `putfield`/`getfield` (values fed by
    the static call chain, with a loaded field live across a call).
    **Limits:** same-class `new`/fields only (no cross-class or JDK types); no
    virtual dispatch on loaded objects (null TIB); fields are zero only on a fresh
    bump (`Heap.alloc` doesn't clear reused blocks); constructors take no args beyond
    `this` (no real `super(...)`/field-init args).
  - **`<clinit>` DONE (on-metal):** after `parseFields` (statics block exists) and
    before the entry method, the loader seeks `<clinit>()V`; if present it compiles
    and runs it (`Magic.call0`) so the initializer's `putstatic`s land before first
    use. It's just another method the loader compiles — no special casing. QEMU's
    `*` now depends on `Guest.bias` (a non-final static set only by `<clinit>`); an
    un-run initializer would leave `bias=0` and yield `20` instead of `42`. Only the
    loaded class's own `<clinit>` runs (Math keeps its no-`<clinit>` path — its
    initializer uses doubles/native, out of scope). No eager multi-class init order
    or per-class guards yet (single loaded class).
  - **`invokevirtual` DONE (on-metal, single class):** the loader now builds a
    **TIB on the metal**. `parseVtable` assigns each virtual method (instance,
    non-private, non-`<init>`/`<clinit>`) a vtable slot in declaration order and
    records its name/descriptor/Code. During a compile, all virtual methods are
    seeded into the program (so the vtable is complete even if some aren't called),
    and after placement `buildTib` allocates `{Type=null, code0, code1, ...}` in the
    heap filled with each slot's compiled-buffer address. `new` now stores that TIB
    into the object header (was null), and `invokevirtual` dispatches
    `ldr tib,[this]; ldr code,[tib + 8 + slot*8]; blr` (x16 scratch) after the same
    128-byte receiver+args spill as a call. QEMU's `*` now flows through
    `g.compute()` (a real vtable call) reading an instance field and a `<clinit>`
    static. **Limits:** single loaded class — vtable = the class's own virtual
    methods, no inherited/overridden slots (needs the superclass's classfile), no
    interfaces, `Type` is null so still no `instanceof`/`checkcast` on loaded objects.
  - **`invokeinterface` DONE (on-metal, single class):** with one concrete loaded
    class, an interface method resolves directly to that class's own vtable slot by
    name+descriptor (the InterfaceMethodref's class — e.g. `vm/Speaker` — is
    ignored), so `invokeinterface` shares `invokevirtual`'s TIB-dispatch path
    (`vtableSlotOf` matches on name+descriptor, not class). Only the opcode length
    differs (5 bytes: index + count + zero). QEMU's `*` now flows through
    `((Speaker) g).speak()`. A real per-interface **itable** (Type→itable directory,
    like the writer side) only becomes necessary once several loaded classes
    implement the same interface at different vtable positions — that waits on
    cross-class loading.
  - **Cross-class loading DONE (static calls):** the loader now loads more than one
    class and links calls between them. A **global method registry** (`register`/
    `registerAll`/`globalBuf`) records each compiled method's class/name/descriptor
    Utf8 (captured by blob base+offset, compared with a two-base `utf8EqAt`) plus its
    buffer. New per-class helpers: `setClass` (parse cp+fields+vtable for a blob) and
    `compileClass` (compile *every* method of a class in its own context, so it can
    be registered whole). The driver loads dependencies first: it compiles+registers
    `Helper`, then `Guest`, whose `invokestatic Helper.scale` resolves via
    `resolveCallBuf` (same-class → local buffer, else the registry) to Helper's
    compiled buffer and `BL`s it. QEMU's `*` now flows `Guest.answer` →
    `Helper.scale(11)=22` (cross-class) → field → `speak()`=42. `BuildRuntimeImage`
    embeds `Helper.class` as a second raw blob. **Limits:** cross-class **static
    calls only** — cross-class `new`/fields/`invokevirtual` would need each class's
    TIB/field-layout/statics cached in the registry (the current single-class
    context statics only hold the class being compiled); dependency order is manual
    (`Helper` before `Guest`), no cross-class cycles; resolution is class+name+
    descriptor (sound), not verified against the interface/super chain.
    - **Gotcha fixed:** the registry arrays must be `new`'d — this VM emits no null
      checks, so a store to a null array silently scribbles low RAM instead of
      faulting (it corrupted the compile until the arrays were allocated).
  - **Cross-class `new` + fields + constructors DONE:** two more registries make a
    class's *shape* visible to others. A **class registry** (`registerClass`/
    `classRegOf`) records each loaded class's name, TIB, and instance-field count;
    a **field registry** (`globalFieldOffset`) records each instance field's
    class+name+slot. Now when Guest compiles `new Helper()` it allocates at Helper's
    size and stores Helper's TIB (`emitNew` resolves the target via `classRegOf`,
    falling back to the current class when the target isn't registered yet — i.e. a
    same-class `new` mid-compile); `getfield`/`putfield vm/Helper.a` resolve the
    offset through the field registry (`fieldOffsetOf` routes cross-class refs to
    `globalFieldOffset`); and `invokespecial vm/Helper.<init>` is now a *real*
    cross-class constructor call (`emitInvokeSpecial`/`wordsFor` use `isRealSpecial`
    = same-class or a loaded class, so only `Object.<init>` stays a pop). QEMU's `*`
    now runs across the boundary: `new Helper()`, `h.a = Helper.scale(11)`,
    `h.b = bias`, `h.a + h.b = 42`.
  - **Cross-class virtual dispatch DONE:** the dispatch code was already correct
    cross-class (it loads the TIB from the *receiver* object, which carries the
    right class's TIB from `new`); only the vtable **slot** was resolved against the
    wrong class. A **vtable-slot registry** (`registerClass` records each class's
    virtual methods as class+name+descriptor→slot; `globalVtableSlot` looks them up)
    fixes it: `vtableSlotOf` keeps the same-class fast path and routes cross-class /
    interface refs to the registry (class-qualified for `invokevirtual`; name+
    descriptor fallback for `invokeinterface`, whose ref class is the unloaded
    interface). QEMU's `*` now ends in `h.sum()` — a cross-class `invokevirtual`
    that loads Helper's TIB from the object and calls Helper's slot, `sum()` reading
    Helper's own fields (`22 + 20 = 42`). **Limits:** manual dependency order; no
    class hierarchies (inherited/overridden slots need the superclass's file); `Type`
    still null so no `instanceof` on loaded objects.
  - **Class hierarchies DONE (loaded superclass + subclass):** the loader loads a
    superclass then a subclass and links them. `parseFields` reads `super_class` and
    lays a subclass's own fields *after* the inherited ones (super's field count from
    the class registry); `parseVtable` builds a **flattened vtable** — `inheritVtable`
    copies the super's registered slots (signature + already-compiled impl buffer,
    read from its registered vtable), then each own method either **overrides** an
    inherited slot in place (`findVtSlot` matches name+descriptor, keeping the super's
    index) or **appends**. `buildTib` fills each slot from its inherited buffer or
    this class's own (`slotBuf`). The class/field/vtable registries gained inheritance
    support: `clVtCount`, a dual-base vtable registry (a slot's class vs its signature
    blob can differ), a `classRegByName`, and name-only fallbacks in
    `globalFieldOffset`/`globalVtableSlot` so an inherited member named through the
    subclass (javac emits `Pup.base`/`Pup.legs`) still resolves. The driver is now a
    per-class `loadOne` pipeline (parse → `<clinit>` → flatten → compile → register),
    run superclass-first. QEMU's `*`: `new Pup()` (subclass of `Critter`, allocated at
    the inherited size, `super()` run) → write inherited `Critter.base` → `c.sound()`
    on a `Critter`-typed ref dispatches to Pup's **override**, which reads the
    inherited field and calls the inherited `legs()` (`20 + 4 + 18 = 42`). The
    inherited-method call *requires* flattening — a naive own-methods-only vtable
    wouldn't have Pup's slot 1. **Limits:** single inheritance, no interfaces in the
    hierarchy, name-only fallbacks assume member names are unique across unrelated
    loaded classes, `Type` still null (no `instanceof` on loaded objects), manual
    superclass-first load order.
  - **`instanceof`/`checkcast` DONE (on-metal Type chain):** each loaded class now
    builds a **Type** node — a one-word heap object holding its superclass's Type —
    linked into a chain (`buildTib` allocates it, stores the super's Type from the
    class registry, and puts it in TIB slot 0; `clType`/`registerClass` track it).
    `instanceof` lowers to an inline walk: load the object's Type via
    `[[obj]][0]`, then follow `Type.superType` (offset 0) comparing against the
    target class's Type until a match (push 1) or 0 (push 0). `checkcast` does the
    same walk but leaves the ref and spins on failure (no `ClassCastException` object
    yet). QEMU's `*` is now gated by two checks — `p instanceof Pup` (true) and
    `c2 instanceof Pup` for a plain `Critter` (false) — so `42` proves the walk
    discriminates, not just always-true. **Limits:** only loaded classes have metal
    Types (writer-built objects use the writer's Types); the target of
    instanceof/checkcast must be a loaded class; no interface `instanceof`; failed
    checkcast halts rather than throwing.
  - **Loaded interfaces + itables DONE:** `invokeinterface` used to resolve by a
    name-only fallback, which silently breaks once two loaded classes implement the
    same interface method at *different* vtable slots. Now interfaces are loaded as
    classes (`ACC_INTERFACE` → `registerInterface`, no compile since every method is
    abstract) and each of their methods gets a **global interface-method index**.
    Every implementing class then builds an **imap** (`buildImap`) indexed by that
    global index, holding *its own* implementation (matched into its flattened
    vtable by name+descriptor); the imap hangs off the Type, which grew to two words
    `{superType, imap}`. `invokeinterface` dispatches
    `[[[this]][1]][g]` — TIB → Type → imap → code — while `invokevirtual` keeps the
    cheaper fixed vtable slot. Interfaces must be loaded before their implementors
    (indices must be fixed first); imaps are a fixed `MAXIFM` wide so a later
    interface can't leave an earlier imap short.
    - **The demo is a real regression test:** `Alpha` puts `greet()` at vtable slot
      0, `Beta` declares `filler()` first so `greet()` is at slot 1, and Guest calls
      both through *the same* `invokeinterface` constant-pool entry. Verified by
      temporarily reverting to vtable dispatch: the answer byte became `0x1B` (27 =
      `20 + filler(7)` — Beta's call hit `filler`), vs `*` (42) with the itable.
  - **Dependency auto-ordering DONE:** load order is now derived, not hand-kept.
    `parseConstPool` records each entry's tag, so `probeAll` can read every blob's
    own name plus every class it *names* (its `CONSTANT_Class` entries). That is the
    right dependency set — not just superclass/interfaces (needed for field layout,
    vtable flattening, itable indices) but anything it instantiates, calls or
    type-tests (needed by the class/method/field registries). `loadAll` then loads
    any blob whose dependencies are all satisfied — already loaded, or not among the
    blobs at all, so `java/lang/Object` never blocks — repeating until done, and
    stopping on a pass with no progress (cycle or missing class). The driver hands
    blobs over deliberately worst-first (Guest, Beta, Alpha, Greeter) to prove the
    order is computed; loading them in that given order instead crashes the loader
    (Guest `new`s classes that aren't registered yet).
  - **On-metal loader is feature-complete for single inheritance.** Remaining work is
    M5 proper: one JDK-free ClassFile+BaselineCompiler shared by writer and runtime.
  - **M5 proper STARTED — one parser, both worlds.** `classfile/ClassReader` is the
    first genuinely *shared* component: strictly JDK-free (no String, collections,
    streams or exceptions — only primitive arrays and int math; results written into
    caller-supplied arrays; every method under the 10-local ceiling), so it both runs
    on the seed JVM and compiles into the image with our own compiler. It reads a
    `byte[]`, the one representation both sides can supply — the writer passes
    `Files.readAllBytes`, the loader copies an embedded blob onto the heap
    (`toBytes`; the offsets stay classfile-relative so they still line up with the
    loader's `gbase + off` raw access). Covers the constant-pool walk (offsets +
    tags), this/super class names, ACC_INTERFACE, section navigation
    (interfaces/fields/methods) and cross-classfile `utf8Eq`.
    - `vm/Loader.parseConstPool` now delegates to it, so the *same code* parses on
      the metal — QEMU still ends in `*M`, and the image grew ~57K→67K as it was
      compiled in.
    - `test/classfile/ClassReaderTest` runs it on the seed JVM and cross-validates
      against the JDK-based `ClassFile` (class/super names, interface-ness, member
      counts) over four real classfiles — 39 checks.
    - **Gotcha it encodes:** `u1` masks `& 0xFF` because the JVM sign-extends
      `baload` while joe-ng's compiler zero-extends it; the mask makes both agree.
    - **`ClassFile` migrated onto it:** the writer's parser no longer walks the
      format itself (DataInputStream is gone) — it uses `ClassReader` for the cp
      walk, section navigation and attribute skipping, keeping only the host-side
      model (Strings, records) on top. Utf8 decoding is explicit (`utf8At`) because
      classfiles use *modified* UTF-8, and `decodeEntry` ignores tag 0 since
      `constantPool` already consumes a Long/Double's dead second slot. Verified by
      the emitted image being byte-for-byte unchanged.
  - **`asm/A64Enc` — the JDK-free half of the assembler (shared).** A64's javadoc
    long claimed it was dual-context but it couldn't be: 14 operand checks throw
    with concatenated messages, and String concat lowers to `invokedynamic`, which
    has no runtime on metal. Split along that seam — `A64Enc` holds the encodings as
    pure int arithmetic (no imports, exceptions or JDK types); `A64` keeps the
    validation and delegates. The math was **moved, not retyped**, so A64's 80
    bit-for-bit ARM ARM checks now verify `A64Enc` transitively.
    - `vm/Loader` now emits through it: all **42 hand-written hex encodings are
      gone**, so the on-metal JIT emits machine code from checked encoders instead
      of typed literals — the difference between a verified encoding and a typo that
      corrupts memory invisibly. Cross-checked all 21 distinct encodings against the
      exact pre-migration literals (bit-identical), plus QEMU still runs to `*M`.
    - Conventions differ where natural: `A64` takes branch displacements in bytes
      and validates them, `A64Enc` in words (how the JIT computes them).
  - **Still to migrate:** `BaselineCompiler` (collections, String keys, lambdas,
    and `switch` expressions that lower to table/lookupswitch — unsupported
    opcodes), and the rest of `Loader`'s bespoke parsing.
- **M4 (runtime class loading) — headline goal, minimal cut.** The writer embeds
  `vm/Guest.class` as raw bytes only (never compiles it); at runtime the on-metal
  `vm/Loader` (compiled into the image by our own baseline compiler) parses the
  classfile it has never seen — constant pool, methods, Code — finds `answer()`,
  compiles its bytecode to A64 in a heap buffer, publishes it (`DSB`+`ISB`; caches
  are off so no dc/ic maintenance), and executes it via `Magic.call0`. QEMU prints
  `Z` (0x5A, from `Guest.answer()` JIT-compiled on the metal). Loader is JDK-free
  (primitive arrays + `Magic` byte access; state in statics because methods are
  capped at 10 local slots) and its mini-compiler handles only `return <const>`.
  Full parser/compiler self-hosting (M5) is far larger — our writer-side
  `classfile`/`compiler` depend on the JDK (collections/strings) and can't be
  compiled into the image yet.
- **Earlier phase note (M2/M6): multi-class runtime with real cross-class calls.**
- **M0 (done):** all-Java pipeline end to end. `asm/A64` encoder + `asm/CodeBuffer`
  + `writer/BootImageWriter` emit a raw, header-less `kernel8.img`;
  `writer/BuildSpinImage` = the 8-byte `wfe; b .-4` park loop at `0x80000`.
- **M1b (done):** `vm/EmitBoot` emits the first-light routine — reads CurrentEL,
  drops EL2→EL1 via ERET, enables FP (CPACR_EL1.FPEN), sets SP, brings up the AUX
  mini-UART (GPIO14/15 ALT5 + config), prints "hello from joe-ng\r\n", parks in wfe.
  `writer/BuildBootImage` emits it (344-byte image). **Prints correctly under
  QEMU `raspi4b`** (mini-UART = serial1). `asm/A64` now also encodes MRS/MSR
  (+boot sysregs), ERET, DSB/DMB/ISB, LDR/STR/LDRB/STRB, ADD/SUB imm, MOV,
  B.cond/CBZ/CBNZ/TBZ/TBNZ — 61 bit-for-bit checks in `test/asm/A64Test`.
  Build/test/emit: `scripts/build.sh`; QEMU smoke test: `scripts/qemu.sh`.
  **CONFIRMED ON REAL SILICON** — see "Real-hardware flashing" below; the baud is
  now self-calibrating, so this no longer needs hand-tuning.
- **M1c DONE (the metacircular half):** `writer/BuildCompiledBootImage` compiles
  `vm.VM.boot()` from javac bytecode — EL2→EL1 drop, FP enable, stack, mini-UART
  bring-up, and the print loop — into a `kernel8.img` that **prints "hello from
  joe-ng" under QEMU raspi4b** (functional check: `scripts/qemu-check.sh`). This is
  now the default image `build.sh` emits. The equivalent hand-assembled path
  (`vm.EmitBoot` / `writer.BuildBootImage`) is kept for reference.
  - `magic/Magic`: intrinsic markers (privileged ops, raw MMIO, `dropToEL1`, and
    a temporary `message()`/`messageLen()` data-pool bridge until real strings).
  - `classfile/ClassFile`: JVMS classfile parser (constant pool, methods, Code).
  - `compiler/BaselineCompiler`: bytecode → A64 with a register-backed operand
    stack (x9..x15) and locals (x19..x28). Coverage: nop/return/goto, const
    pushes, local load/store + iinc, add/sub/and, i2l/l2i/i2b/i2c no-ops,
    if/if_icmp/goto branches, and the Magic intrinsics. Unsupported opcodes throw.
  - `test/compiler/CompilerTest`: spin/pokeWord/writeReg pinned exactly (66 A64
    encoding checks + compiler checks run in `build.sh`).
- **Object model DECIDED (gates M2).** Source of truth: `objectmodel/ObjectModel`.
  Direct 8-byte pointer refs (8-aligned, null=0); two-word header (`+0` TIB,
  `+8` status word reserved to ~M6); fields at `+16`; arrays `+16` length / `+24`
  elements; TIB = `[0]`Type + `[1..]`vtable. All offsets centralized here so
  header growth is a one-file change. Pinned by `test/objectmodel/ObjectModelTest`.
  Full rationale in PLAN.md "Decided".
- **M2 so far (multi-class + real calls DONE):** the boot is now split across
  classes (`vm.VM.boot` → `board.bcm2711.Uart.init`/`puts` → `Uart.putc`) and
  compiled as a multi-method program that **still prints "hello from joe-ng"** under
  QEMU. New machinery:
  - `compiler/BaselineCompiler` calling convention: args x0..x7, return x0, locals
    in callee-saved x19.., per-method prologue/epilogue (save x30 if non-leaf,
    save+restore used locals, move params in). Entry method (`boot`) is frameless
    and sets its own SP. `ireturn/lreturn/areturn` return in x0. Real static calls
    lower to `BL` placeholders + recorded call sites; `Magic.*` still inlines.
  - `writer/ImageBuilder`: mini class loader + layout + relocation. From an entry
    key it BFS-discovers reachable methods, sizes them (sizes are
    layout-independent), assigns bases (entry at 0x80000), recompiles at final
    bases, concatenates, and patches every `BL` to its callee's entry.
  - `writer/BuildRuntimeImage` is now the default image `build.sh` emits.
  - Tests: `addOne(int)` pins the frame/return sequence; `qemu-check.sh` is the
    functional gate. (Old single-method `BuildCompiledBootImage` removed.)
- **Instances DONE (M2/M3 overlap): `new` + heap + TIB + fields + constructors.**
  The runtime now allocates on a heap and uses object fields; QEMU prints the
  banner then `k` computed from `new Cell(0x6A); c.value = c.value + 1`.
  - `vm/Heap`: Java bump allocator (metacircular) over a fixed region
    (`PTR_CELL`=0xF0000, `BASE`=0x100000); `Heap.init()` seeds it in boot, no GC.
  - Compiler: `new` (→ `Heap.alloc(size)` + store TIB pointer in the header),
    `dup`, `getfield`/`putfield` (8-byte slots via `ObjectModel`), `invokespecial`
    (constructor calls; `Object.<init>` is a no-op), `aload/astore`, `load64/
    store64`. Instance-method prologue maps `this`→slot0. Operand values now
    **spill to the frame across calls** so mid-expression calls (e.g. `new X()`'s
    constructor) don't clobber live refs. `ClassResolver` gives field offsets /
    instance sizes across classes.
  - Writer: `ImageBuilder` lays out, per instantiated class, a `Type`
    (`{instanceSize}` for now) and a real **TIB = [Type ptr, vtable...]** after the
    code; it relocates each `new`'s TIB-pointer load and fills vtable slots with
    the virtual methods' code addresses (pulling all of an instantiated class's
    virtual methods into the layout).
  - `classfile/ClassFile` parses fields + method access flags + `virtualMethods`/
    `vtableSlot`. Tests: `FieldFixture` pins getfield/putfield and invokevirtual
    dispatch; `qemu-check.sh` gates the banner and the heap-field print.
- **`invokevirtual` DONE:** dispatch through the receiver's TIB vtable
  (`ldr tib,[recv]; ldr code,[tib+slot]; blr`), using x16 scratch. Vtable slot =
  method's position among the class's virtual methods (no inheritance beyond
  Object yet — revisit slot assignment when class hierarchies arrive). QEMU's `k`
  now flows through `c.inc()`/`c.get()` virtual calls.
- **Arrays DONE:** `new byte[]`/`int[]`, `arraylength`, and element load/store
  (`baload`/`bastore`, `iaload`/`iastore`, `laload`/`aaload` etc.). Layout per
  `ObjectModel`: `[header][length @16][elements @24]`, element addr = base +
  `index<<scale`. `vm/Heap.allocArray(length, elemSize)` allocates + writes the
  header (null TIB for now; array TIBs come with GC/instanceof). Alloc rounds the
  bump to keep objects 8-aligned (MMU off → unaligned faults). QEMU prints `AB`
  from a filled+iterated heap `byte[]`. Added `MUL` and `ADD (shifted reg)`.
- **String literals DONE — `message()` bridge retired.** `ldc "..."` interns the
  literal as a real heap-layout **byte[] object** in the image (writer lays out
  `[null TIB][status][length][ASCII bytes]`, 8-aligned; the `ldc` address load is
  relocated like TIB refs). `Magic.bytes(String):byte[]` is a compile-time type
  adapter lowered to a no-op (joe-ng has no `java.lang.String` yet, so this lets
  Java source name the bytes). `Uart.write(byte[])` iterates it. The old
  appended-blob `message()`/`messageLen()` and the compiler's `imageData`
  plumbing are gone. `CompilerTest` asserts the interned bytes land in the image.
- **Static fields DONE:** `getstatic`/`putstatic` against an image **statics area**
  — one zero-initialized 8-byte slot per unique static field (`owner.name`), laid
  out by `ImageBuilder` after the strings; the address load is relocated like TIB/
  string refs. Statics live in RAM (image is loaded writable, MMU off), so
  `putstatic` works. No `<clinit>` yet (fields default to 0; javac inlines
  compile-time-constant statics as `ldc`). QEMU prints `3` from a bumped static
  counter. `CompilerTest` pins the `getstatic` lowering.
- **`<clinit>` DONE (eager, closed-world):** the writer discovers each used
  class's `<clinit>()V` (on first use — method owner, `new`, or static access),
  lays them out, and **generates the body of `VM.initClasses()`** as a sequence of
  `BL`s to each. `VM.boot` calls `initClasses()` after Heap/stack setup, before
  `run()`, so all statics are initialized once before the program. QEMU prints `7`
  from `Config.mark` set in a static block. (Naive first-use ordering — no
  dependency-topological order or per-class init guards yet.)
- **Class hierarchies DONE:** superclass parsed (`ClassFile.superClassName`) and a
  **flattened vtable** (`ClassFile.vtable`) — superclass slots first, overrides
  replace in place, new methods append. `invokevirtual` on a static supertype hits
  the runtime override at the shared slot; `super(...)` constructor calls work
  (`invokespecial` to a non-Object `<init>` is a real call). The writer fills each
  class's TIB vtable with the most-derived impl per slot and lays out all slot
  implementations. QEMU prints `W?` — `Dog` override vs `Animal` base via an
  `Animal`-typed reference. (No interfaces / abstract dispatch yet.)
- **`instanceof`/`checkcast` DONE:** `Type` now carries a superclass pointer
  (`{instanceSize, superType}`); the writer interns one `Type` per class and links
  the chain, and lays out Types for all type-check targets + their superclasses.
  The compiler lowers both to a call to a Java helper (`VM.instanceOf`/`checkCast`)
  that walks the object's Type→super chain; `checkCast` halts on failure (no
  exceptions yet). QEMU prints `YNW`. Added `lcmp` (long compare via CSET/CSINV)
  and — importantly — the compiler now tracks **operand-stack depth at branch
  merges** (so ternaries / values-live-across-branches work), pinned by `tern`.
- **Interfaces DONE (`invokeinterface`):** each `Type` gains an itable-directory
  pointer (`{instanceSize, superType, itableDir}`); the writer builds, per
  instantiated class, an itable per implemented interface (method→impl code addr)
  and a directory of `{interfaceType, itable}` entries. The compiler lowers
  `invokeinterface` to an **inline itable search** (walk the receiver's directory
  for the interface's Type, index the itable by slot, `blr`) using x16/x17/x9
  scratch. QEMU prints `RP` (Robot vs Phone via a `Speaker` reference).
  `ClassFile` now parses `interfaces` + `interfaceMethods`/`allInterfaces`/
  `findImpl`.
- **Exceptions — same-method AND cross-method DONE.** `athrow` tests each covering
  exception-table entry's catch type inline; on a local match it branches to the
  handler (exception on the operand stack). On no local match it calls
  `VM.unwind(exc, pc, sp)`, which walks the stack using two writer-built tables —
  a **handler table** (machine-PC ranges → handler + catch Type, from every
  method's exception table) and a **frame table** (codeStart/end → frameSize).
  At each frame: if a handler covers the PC and the type matches, `Magic.resume`
  (set SP, exception in x9, branch) transfers to it; else pop the frame (read
  saved LR at [sp], `sp += frameSize`) and retry at the caller's call site
  (`LR - 4`). QEMU prints `E` (same-method) and `U` (thrown in `thrower()`, caught
  in `catcher()`). `java/*` supers/`<init>` are roots/no-ops so throwables extend
  JDK classes cleanly (`ClassFile.isRoot`). Table locations live in writer-filled
  statics (`VM.frameTable`/`frameCount`/`handlerTable`/`handlerCount`).
  **Limitation:** callee-saved locals are NOT restored during the walk, so a
  handler must not read a *pre-try* local (it may be stale). No `finally`-specific
  handling beyond catch-all entries.
- **GC — conservative mark-sweep DONE (first cut of M6).** Each object records its
  allocation size in the status word (low bit = mark), so the heap is walkable and
  objects are sizable without per-type maps. `Magic.gc()` spills x19..x28 (so live
  refs there are scannable), then `VM.gcCollect` marks from roots — the stack
  ([spilled SP, 0x80000)) and the statics region (writer-filled
  `VM.staticsStart/End`) — traces marked objects' bodies to a fixpoint, and sweeps
  dead objects onto `Heap`'s free list, which `alloc` reuses (first-fit) before
  bumping. QEMU prints `R` (a post-GC allocation served from the free list).
  Objects are **not moved** (no precise stack maps needed); it may **over-retain**
  via false roots (conservative). No generations/incrementality.
- **M2 complete; M6 GC has a working collector.** Remaining niceties:
  super-interfaces / default methods, char/short arrays (`ldrh`/`strh`), a real
  `String`/`Throwable` class, restoring locals on unwind, a moving/precise GC.
  `baload` zero-extends (fine for ASCII).
- Milestones (see PLAN.md §4): M0 writer emits booting image → M1 first light
  (compiled `VM.boot` prints over UART) → M2 object model + multi-class → M3
  heap + `new` → M4 runtime class loading → M5 self-hosting (drop seed JVM) →
  M6+ GC, interrupts, SMP, exceptions, class library.

## Real-hardware flashing

- `scripts/sdcard.sh` builds the image and assembles `sdcard/` (kernel8.img +
  config.txt + fetched GPU firmware start4.elf/fixup4.dat). Copy to a FAT32 SD
  card. `scripts/flash.md` is the full guide (serial wiring GPIO14/15, 115200 8N1,
  troubleshooting). The user runs the flash + serial monitor themselves.
- **VERIFIED ON REAL HARDWARE.** A Pi 4 boots the image and prints the whole
  feature run over the mini-UART, ending in `*M` — the on-metal class loader
  (hierarchies, cross-class linking, `instanceof`) and `java.lang.Math.max` from
  `java.base`, all JIT-compiled on bare metal. QEMU is no longer the only witness.
- **mini-UART baud is self-calibrating — do not hardcode a divisor.** The baud is
  `core_clock / (8*(divisor+1))`, and the VPU core clock is not predictable: it
  differed across firmware builds and even across SD cards (a card carrying
  recovery files boots different firmware). Three hardcoded guesses each worked on
  one setup and garbled on the next (270/250 MHz, 541/500 MHz, 216/200 MHz).
  `board/bcm2711/Mailbox` now asks the firmware over the VideoCore mailbox and
  `Uart.baudDivisor()` computes the divisor at boot; `Bcm2711.BAUD_115200` (179) is
  only the fallback. Two hard-won details:
  - Ask for **`GET_CLOCK_RATE_MEASURED` (0x00030047)**, not `GET_CLOCK_RATE` — the
    latter echoes back the *requested* rate (it returned exactly our
    `core_freq=200`) while the silicon actually ran at **166 MHz**.
  - Boot prints `core NNNMHz` so the board reports what it calibrated to. When the
    baud is wrong *every* message is unreadable — including any message about the
    clock — so the way out was a **baud sweep**: print the same self-identifying
    line once per candidate clock, each at that candidate's baud, and read whichever
    line renders. Reach for that again if serial ever goes silent-but-garbled.
- Serial output must be **CRLF** (`Uart.putc` translates `\n`); a raw console
  staircases on bare `\n`, which QEMU's stdio hides.

## Working agreements for the agent

- Validate on a **real Pi 4** (USB-TTL serial) from M0 onward; QEMU `raspi4b` is
  a test aid with partial peripheral emulation, not ground truth, and it is not
  part of building the VM.
- Unit-test every A64 encoding bit-for-bit against the ARM ARM before relying on
  it — a mis-lowered `Address.store` corrupts memory invisibly.
- Keep the first object model tiny; dump and diff image layouts to catch
  relocation bugs.
- UART-first observability: make output work before anything hard (MMU, EL drop)
  so failures are visible.
- Prefer growing the compiler's bytecode coverage milestone-by-milestone over
  building it broad up front.
