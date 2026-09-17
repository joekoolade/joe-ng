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

## Standing rules (2026-09-12) -- THESE SUPERSEDE EARLIER CONCLUSIONS

1. **THE GOAL IS A FULLY FUNCTIONAL VM.** Not a minimal one that runs the demos. Where a subsystem is
   currently absent by denial (java/nio/file, java/lang/invoke, sun/security, parts of java.math), the
   direction of travel is to IMPLEMENT or STUB it, not to prune further.

2. **ALL `<clinit>`s RUN.** The `clinitCompilable` gate, its per-class allowlist and the
   `CLINIT REJECTED (statics stay null)` outcome are being retired. A skipped initializer is a SILENT WRONG
   ANSWER -- the class loads, gets static cells, and answers null for ever -- which is the single most
   expensive failure mode in this project's history.
   - **This reverses two recorded rejections, and the reason they no longer apply is rule 3.** Both earlier
     attempts were measured against a VM that DENIES natives: Form 1 died in 3 minutes at
     `java/io/UnixFileSystem.<clinit>` -> the denylisted native `UnixFileSystem.initIDs`, and Form 2 died in
     `ObjectStreamClass$Caches.<clinit>` for want of serialization. With natives STUBBED instead of denied,
     that objection is gone. The old measurements were correct about the VM as it was, not about this one.

3. **A `guestsrc/` OVERLAY STARTS FROM THE JDK 26 SOURCE CLASS**, not from a hand-written minimum.
   - **Source: `/Users/joe/git/jdk/src` -- a FULL OpenJDK tree at feature version 26** (3301 `java.base`
     java files). Preferred over the JDK's `lib/src.zip` because it also carries the **native C sources**,
     which is what makes the stub decisions below answerable by READING instead of guessing.
   - **The seed JDK on PATH IS 26.0.1** (`jenv`; note `/usr/libexec/java_home` does NOT list Homebrew JDKs,
     so it looks like only 17/11/8/7 are installed). Seed and source therefore agree, and an overlay taken
     from this tree matches the java.base the writer bakes -- field layouts, signatures and `jdk.internal.*`
     all agree by construction.
   - **Take the RIGHT PLATFORM VARIANT.** The tree is `share/` + `unix/` + `macosx/` + `linux/` + `aix/` +
     `windows/`; a class can exist in more than one (`java/io/UnixFileSystem.java` is under `unix/classes`,
     not `share/classes`). Copying the wrong one is a silent mismatch with the baked class.
   - **Natives that are not implemented are STUBBED, and a stub THROWS rather than answering.** A stub that
     returns a plausible value is indistinguishable from a working one; a throw names the class and method
     the moment it is reached, which turns "implement everything" into a worklist the program itself writes.
   - **A void native may be EMPTY only where doing nothing is the CORRECT semantics here, and the C source is
     how that is DECIDED rather than assumed.** Worked example: `UnixFileSystem.initIDs`
     (`unix/native/libjava/UnixFileSystem_md.c`) caches a JNI `fieldID` for `File.path` and has no effect
     outside JNI -- so on a VM with no JNI, empty is PROVABLY right. Empty-by-default, without reading the
     native, is the same silence rule 2 exists to remove.
   - **This supersedes "guestsrc is only for classes that need natives."** That rule existed to stop overlays
     accumulating as minimal hand-written shells that silently drop stock members -- the trap that has cost
     ELEVEN debugging sessions. Starting from the real source removes the cause rather than the symptom.

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
- **The only external seeds** (not things we build): a stock JVM to run the writer,
  and the Pi's GPU firmware that loads `kernel8.img`. Nothing else touches joe-ng.
  **The seed JVM is PERMANENT (decided 2026-08-28)** — running the boot-image writer
  on metal was dropped as a milestone, so the writer stays a build-time tool like
  javac. Metacircular here means the classfile parser, the baseline compiler and the
  runtime are ordinary Java classes running in BOTH worlds; the image *build* is not
  part of that loop and is not intended to be.
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

- **ADDING EIGHTEEN OVERLAY MEMBERS ABORTS THE DEMO SUITE -- OPEN, BISECTED, AND THREE HYPOTHESES ALREADY
  DEAD (2026-09-17).** `Unsafe.getAndBitwiseOrInt` shipped alone because the FAMILY does not fit: with all
  eighteen getAndBitwise{Or,And,Xor}{Int,Long} forms present the suite dies at `demo/DefaultIfaceDemo` with
  an uncaught NPE. **This gates overlay-gap work generally** -- `overlaycheck-deep` lists ~250
  referenced-but-dropped members on `Unsafe` alone, and they cannot be added in batches while this stands.

  | arm | suite |
  |---|---|
  | plain HEAD | **pass** -- 33 programs, 0 exceptions |
  | `+1` (`getAndBitwiseOrInt`) | **pass** |
  | `+18` appended at class end | **FAIL** |
  | `+18` inserted in place | **FAIL**, byte-identical trace |

  - **APPENDED AND IN-PLACE FAIL IDENTICALLY, so vtable RENUMBERING is not the mechanism** -- inserting mid-
    class shifts every later method's slot and appending shifts none, and both produce the same trace. What
    is left is the SIZE of the addition, i.e. the layout/closure sensitivity this file already records as
    having cost boots twice.
  - **THE FAILURE, exactly:** `Map.forEach` (an interface DEFAULT, itable-routed) -> `LinkedEntrySet.iterator()`
    -> `new LinkedEntryIterator(this$0, false)` -> `Objects.requireNonNull` NPE. Confirmed from the real
    JDK's bytecode rather than guessed: that constructor opens `aload_1; dup; invokestatic requireNonNull`,
    so the null is the OUTER INSTANCE the caller passed, i.e. `LinkedEntrySet.this$0`.
  - **NOT ONE EXISTING GUARD FIRES.** No `UNREGISTERED SUPER`, no `aliases slot 0`, no `UNRESOLVED FIELD`,
    no `CAP EXCEEDED`, no `PEND`/`REACH LIST FULL`, no parity `DIFF`, no `FAULT`. Silent, which is the
    property that makes it worth its own arc rather than a footnote.
  - **THREE HYPOTHESES KILLED, EACH BY AN INSTRUMENT RATHER THAN BY ARGUMENT** -- and each instrument is in
    the tree:
    - **A SKIPPED CONSTRUCTOR** (the recorded shape: a raw object whose fields stay 0). New `CTOR SKIPPED`
      report names every non-Object `<init>` lowered to a pop. On BOTH arms it names exactly one thing, the
      benign DENYLISTED `UnmappableCharacterException` -- the same single skip the earlier deferred-ctor arc
      found by hand. So `LinkedEntrySet.<init>` IS emitted as a real call.
    - **A WRONG FIELD OFFSET.** Live until it was measured, because `LinkedEntrySet` declares `reversed`
      FIRST and `this$0` second, so slot 0 holds a boolean false -- and `globalFieldOffset`'s fall-through
      returns exactly slot 0. A wrong-slot read would therefore produce precisely this clean null. It reads
      **`this$0 -> +24 tier0`, twice**: correct, and the same on the store and the load.
    - **THE COLLECTOR.** `gc` tracks between arms at the failure point (3 vs 4), so a swept-but-live object
      is not indicated.
  - **AND THE WATCH THAT ANSWERED IT COULD NOT ANSWER AT FIRST, which is its own lesson.** `fieldOffsetLog`
    was called from two of five tiers, so an armed watch on `this$0` printed NOTHING -- a silence that reads
    as "the resolver is not involved" and means "this path cannot report". Every tier logs now.
  - **A FOURTH HYPOTHESIS IS DEAD, AND IT WAS THE LEADING ONE: the receiver is NOT mis-typed.** New
    `RECVWATCH` (Loader.RECV_WATCH, off by default) describes a dispatch's receiver -- TIB, Type, class NAME
    from the registry, status word, first field slots. At the same site in both arms:

    | | class | status | slot0 (+16) `reversed` | slot1 (+24) `this$0` |
    |---|---|---|---|---|
    | passing | `LinkedHashMap$LinkedEntrySet` | `0x20` | `0x0` | **`0x04F32450`** |
    | failing | `LinkedHashMap$LinkedEntrySet` | `0x20` | `0x0` | **`0x0`** |

    Right class, right TIB, right SIZE (`0x20` = header + two fields). It is a genuine `LinkedEntrySet`, so
    "a wrong TIB dispatched `iterator()` against something else" is out.
  - **SO THE CONSTRUCTOR RUNS AND ITS `putfield` DOES NOT TAKE EFFECT ON THIS OBJECT.** That is what the
    three surviving facts force: `<init>` is emitted as a real call (`CTOR SKIPPED` clean), the offset is
    `+24` on BOTH the store and the load (`tier0`, twice), and the field is still 0. **NEXT:** a wrongly
    RESOLVED `<init>` body (the recorded `globalBufByRef` super-chain-on-`<init>` shape, or the link stub /
    `resolveUnresolvedNew` pair for a deferred `new`), or a store to a different object than the one
    returned. Instrument the `<init>` call itself -- which body it resolved to, and the receiver it got.
  - **THE RECEIVER WATCH SHIPPED BROKEN AND A PASSING-IMAGE CONTROL IS THE ONLY REASON THAT IS KNOWN.**
    `ImageBuilder` stashes `watchRet`/`watchArg`/`watchX21` by name; `watchRecv` was not added, so
    `watchRecvAddr` stayed 0 and `callHelper` emitted a call to ADDRESS 0 -- an endless reboot the firmware
    shim turns into a wild branch reported inside the itable dispatch. Armed on the FAILING image that reads
    exactly like the defect changing shape. Arming it on the PASSING image broke that too, which is what
    named it as mine. **A new helper needs a writer stash, and an instrument is not evidence until it has
    run on a boot that passes** -- this file's own rule, now paid for a fourth time.
  - **A SEPARATE SILENT WRONG ANSWER, found on the way and NOT fixed:** a boolean CONCATENATES AS 1/0 rather
    than `true`/`false`. `Baseline.appendArg` routes a `'Z'` concat argument to `SC_INT`, which renders a
    decimal integer, where JLS 15.18.1 requires the words. Measured (metal printed `1` where the host
    printed `true`) and confirmed by reading the lowering. Not a one-liner -- the writer lowers concat too,
    so a new helper perturbs the self-hosting fixpoint -- so it wants its own increment. `StringBuilder
    .append(boolean)` is a different path and is correct, which is why `count=42 ok=true` in the suite never
    caught it.

- **THE picocli `factory` NPE IS FIXED, AND IT WAS THREE NESTED BUGS -- the last of them a 960 KiB memory
  overlap the boot-time overlap check was STRUCTURALLY UNABLE TO SEE (2026-09-17, PI-VALIDATED).** The
  launcher runs to completion on HARDWARE: `Test run finished after 102038 ms`, `[3 containers successful]`,
  `[2 tests successful]`, `[0 tests failed]`, exit 0, at batch 139 (+2473 blobs) -- with both stock jtreg
  tests green (`testMillisNanos() 50808 ms`, `testMillis() 17376 ms`). Each layer was invisible until the
  one before it was fixed, which is this file's most-repeated shape and is why it took an arc.
  - **(1) THE WATCH COULD NOT FIRE, which was the question this arc opened on.** `FIELD_STORE_WATCH` armed on
    the field name `factory` never fired for `CommandLine.factory` though its `putfield` at offset 60
    demonstrably executes on a HEALTHY run. Cause: on the FAILING path `Assert.notNull` throws SIX BYTECODES
    EARLIER, so the store is never reached -- the instrument was aimed at code the failing run does not run.
    **An instrument that cannot fire looks exactly like a condition that never happens**, and reading its
    silence as evidence is what this file forbids and what happened here for two sessions.
  - **(2) A BAKED FRAME'S CALLEE-SAVED REGISTERS WERE DROPPED BY THE UNWINDER.** `jitRegLocalsAt` consulted
    the JIT table ALONE while `frameSizeAt` consulted BOTH, and the writer emitted no image-side local table
    at all -- so an unwind crossing a baked java.base frame answered 0 and copied NONE of that frame's saved
    x19..x28 into the reconstruction. The handler then resumed with its CALLER's registers still holding
    throw-time values: a local live before the call reading as null, **with no fault and no trace, arbitrarily
    far from the throw.** Guest-only unwinds are structurally blind to it (every guest frame IS in the JIT
    table); it needs a throw that crosses baked java.base, which is exactly what class loading does, and what
    picocli and JUnit do constantly because they throw as ordinary control flow.
  - **(3) FIXING (2) LOOKED LIKE A REGRESSION AND I BACKED IT OUT FOR A REASON THAT WAS WRONG.** Enabling the
    image lookup cleared the NPE and made the launcher stop EARLIER, on a `Map.put` against a receiver whose
    Type carried `ARRAY_TYPE_TAG`. I recorded that as "an image frame's save area is not what this
    reconstruction assumes" and shelved it -- **while the dump I had already run showed the image save area
    matches EXACTLY.** The real cause was underneath: **`VMGc.MARK_STACK` at `0x03E50000` overlapped
    `Heap.JIT_TABLES..+0x140000` (`0x03E00000-0x03F40000`) by 0xF0000**, so every deep collection wrote heap
    pointers over the JIT LOCAL and HANDLER tables. Enabling the image half merely changed which frames
    reached a JIT half that was reading corrupted memory. Measured: a local entry holding
    `{lo=0x060A3758 hi=0x060A37C0}` -- a heap range -- where its frame-table twin at the same index held a
    valid code range.
  - **IT ARRIVED WITH A CAP CHANGE AND NOTHING SAID SO.** `MARK_STACK` was placed at `JIT_TABLES + 0x50000`,
    exactly right while `JIT_FRAME_MAX`/`JIT_HANDLER_MAX` were 4096. Raising them to 16384 took the three
    tables `0x50000 -> 0x140000` and moved nothing else. VM.java's own comments still carried the 4096-cap
    sizes (`0x18000`/`0x30000`/`0x20000`), which is half the reason it read as correct on inspection.
  - **`ScratchMap` EXISTS FOR THIS AND COULD NOT SEE IT, because the reservation named its NEIGHBOUR instead
    of its own size:** `add(Heap.JIT_TABLES, VMGc.MARK_STACK)` -- which cannot overlap the mark stack by
    construction, it shrinks to fit it. It is `add(JIT_TABLES, JIT_TABLES + VM.JIT_TABLES_BYTES)` now, DERIVED
    from the caps, so a future cap change trips the check. **NEGATIVE CONTROL, run before it shipped:** with
    the old value restored, boot prints `SCRATCH MAP OVERLAP 0x03E00000-0x03F40000 vs
    0x03E50000-0x03FF0000`; with the fix it is silent. **A guard written against the wrong quantity is worse
    than no guard** -- this one had been quiet across four overlap bugs' worth of layout change, and was
    created precisely because of them.
  - **THE OTHER HALF OF THE REPAIR IS A `-1`.** `tableValueIn` returned 0 both for "no entry covers this pc"
    and for "the entry says 0", and a LEAF IMAGE METHOD THAT SAVED NOTHING IS GENUINELY 0. Asking the image
    table first is only sound once those are distinguishable, or the JIT table gets consulted for pcs the
    image table already answered. The same distinction is what lets a real gap be REPORTED rather than
    silently skipped.
  - **MEASURED, A/B, same image layout in both arms:** `X21 CLOBBERED` 2 -> 0, `factory` NPE 1 -> 0,
    `DISPATCH ON UNREGISTERED TYPE` 1 -> 0 (the "new blocker" the back-out was for), JIT local `nrl`
    `0xFFFFFFFFFFFFFFFF` -> `0xA`/`0x1`. `FAULT`, `BOOT RE-ENTERED`, `SCRATCH MAP`, `BADPATCH`, `heap OOM`,
    `JIT unsupported`, `JIT UNWIND TABLE FULL`, `VIRTUALRESOLVE FAILED` all zero, with only the standing
    `ProcessImpl.init` `DENYLIST TRAP` (and its `LINK FAILED` and the two `unclaimed pc` frames inside that
    trap's own trace).
  - **`demo/CtorArgProbe` STAYS AS A PINNED NEGATIVE CONTROL, and it is a seven-arm one now** (A-G: inline
    `new`-as-argument, via-call, fat, wide-locals, late-`this`, demand-load, unwind). Every arm passes host
    AND metal -- which is the point: **it reproduces the SHAPE and never reproduced the CONDITION.** The
    condition needs a throw crossing a BAKED frame, and no probe built from guest classes has one.
  - **HOST CONTROL FIRST, and it settled authorship in ten seconds** -- `java -jar ramfs/lib/junit.jar` on a
    stock JVM constructs `CommandLine` fine in 12ms, so the fault was ours. That is still the cheapest move
    available on any library-misbehaviour symptom.
  - **REGRESSION GATE: the demo suite, because `MARK_STACK` MOVED** and this project has twice had latent
    bugs surface from layout movement alone. 33 programs, identity exact -- `memo=1418 res=2510 unres=2253`,
    `rf:skip=1631 visit=1173 clos=1173 holeEnd=1074`, `n:imap=52 synth=18 clinits=25`, `churnMB=625 live=32
    intact=32`, `gc: collections=55`, `lisp evals=600 result=610 stable=1`, `finish HML` / `HIGH blocked
    60ms`, `smp sched: 4 of 4`, `YNW`/`RP`/`sum20=210` -- and eighteen failure markers zero. Host tests
    unchanged: A64 105, object-model 22, class-reader 171, refmap 14, compiler 39, crypto 17, zip 91,
    `overlay-check 0 new`. **The gate was re-run on the FINAL tree after a comment-only edit**, because this
    file already records that such an edit changes the image (same size, different bytes: LineNumberTable).
  - **Mark stack capacity falls ~213k -> ~90k entries**, still more than an order of magnitude above the few
    thousand blocks a real collection marks, and `markOverflow`'s fixpoint fallback finishes the trace
    correctly if it ever fills.
  - **PI-VALIDATED, AND THE BOOT ANSWERED THE QUESTION QEMU COULD NOT.** The emulator hands out ZEROED DRAM
    and this change MOVED the GC mark stack, so a collector now writing into memory it had never touched
    reads clean there by construction. On silicon: **`gc=15` collections, no `heap OOM`, no `STW TIMEOUT`,
    no `FAULT`/`ESR EC=0`/`BOOT RE-ENTERED`** -- plus `SCRATCH MAP OVERLAP`, `X21 CLOBBERED`,
    `JIT UNWIND TABLE FULL`, `BADPATCH`, `VIRTUALRESOLVE FAILED`, the `factory` NPE and
    `DISPATCH ON UNREGISTERED TYPE` all absent. The batch-139 counters match the QEMU arm exactly
    (+2473 blobs), so the closure is identical at both scales.
  - **THE `ProcessImpl` TRAP FIRED AT BATCH 21 AND THE BOOT RAN ON TO 139 -- proof by PRESENCE, for the
    fourth consecutive hardware boot.** picocli's terminal-width probe reaching a denied native inside
    `lazyCompileLocked` is a routine survivable event; its ABSENCE would have been as suspicious as a new
    failure. It brings the boot's only `LINK FAILED` and both `unclaimed pc` frames with it, all inside its
    own trace.
  - **ONE SPIKE, NAMED RATHER THAN CHASED: batch 133 reads `seed=640.158ms` against ~0.5ms for its
    neighbours, and `gc` steps 14 -> 15 on that exact batch.** That is the batch-188 shape this file already
    settled after four boots: a collection landing inside whichever timer happens to be holding the
    stopwatch. Recorded so it is not re-found and re-chased.
  - **EVERY PER-BATCH TIMING IN THE CARDS BELOW IS NOW STALE, and that is worth stating before someone
    compares against them.** They were measured at batch 209 / 1782 blobs against a closure that was 44%
    TRUNCATED; this boot is batch 139 / 2473 blobs on a complete one. The load-path arc's ratios still hold
    as ratios -- the absolute figures describe a different VM.
  - **NEXT, and it is a known gap rather than a failure:** `jdk/internal/misc/Unsafe.getAndBitwiseOrInt` is
    still referenced-but-dropped (the overlay declares only the `Long` form), reachable from stock
    `ForkJoinTask.setDone`. `make overlaycheck-deep` is what sees it; the shallow check cannot.
- **THE SILENT CLOSURE TRUNCATION IS FIXED, AND FIXING IT REMOVED THE FAILURE THAT COST THIS ARC FOUR BOOTS
  (2026-09-16, NOT YET PI-VALIDATED).** `MAXREACH` 8192 -> 65536 plus the report `addReach` never had.

  | | before | after |
  |---|---|---|
  | batch 1 `reach` | **8192 == the cap** | **14554** (complete) |
  | `rounds` / `pend` | 26 / 56,383 | 54 / 96,158 |
  | `VIRTUALRESOLVE FAILED` on the launcher | 1 (`Unsafe.getAndBitwiseOrInt`) | **0** |

  - **44% OF THE CLOSURE WAS BEING DISCARDED IN SILENCE ON EVERY LAUNCHER BOOT**, including every one that
    passed and exited 0. `addReach` was `if (code == 0L || reachN >= MAXREACH) { return false; }` -- no
    report, no counter. **`MAXPEND` learned this exact lesson and its report sits at the END OF THE SAME
    METHOD**, ten lines away; the method set never got it. The new report is modelled on it deliberately.
  - **A DROPPED METHOD IS WORSE THAN A DROPPED REF, and the wording says why.** Marking is a FIXPOINT, so an
    unmarked method never contributes its own refs and its entire transitive subtree is lost with it --
    which is why raising the cap moved `rounds` 26 -> 54 rather than adding a few leaves. And the method
    simply gets no dispatch stub, so the first symptom is a `VIRTUALRESOLVE FAILED` in a class that looks
    unrelated to anything anyone touched.
  - **IT ALSO MADE CLOSURE MEMBERSHIP ORDER-DEPENDENT, which is the property that cost the boots.** Which
    8,192 of 14,554 survive is insertion order, so ANY change to the image moves the cut. Two statics were
    enough. The report says this outright, because a reader who sees it needs to know that every other
    result from that boot is suspect.
  - **THE REPORT'S FIRST CUT LIED AND THE NEGATIVE CONTROL CAUGHT IT.** It read **"2,384,018 methods dropped
    ... raise MAXREACH above 2392210"** on a closure short by ~6,000. `addReach` is called for the same
    method in every round, and once full it was counting every call -- including ones for methods ALREADY
    marked, which are ordinary no-ops rather than losses. Probing the set before counting takes it to
    **33,107 refusals**, 72x smaller and honestly named: refusals of UNMARKED methods, still over-counting a
    new method once per round. **A diagnostic asserting a number it has not measured is the failure this
    file records three times, and it nearly shipped a fourth.**
  - **AND IT SUGGESTS NO SIZE, deliberately.** The true closure size is UNKNOWABLE from a run that truncated
    it; the only way to learn it is to raise the cap and re-read `reach=`. The MAXPEND report can print a
    target because its count is distinct; this one cannot, so it says what to do instead of inventing a
    number.
  - **VERIFIED BOTH DIRECTIONS, which is what makes it evidence:** at `MAXREACH=8192` it fires with the
    count above; at 65536 it is **SILENT** and `reach=14554`. A report that cries wolf on a passing boot is
    worse than none, and this file has had to say that four times.
  - **WHAT THE COMPLETE CLOSURE THEN EXPOSED, and it is a REAL pre-existing bug the truncation was masking:**
    QEMU now reaches batch 4 with **no `VIRTUALRESOLVE FAILED`, no denylist trap, no fault** and stops at
    `NullPointerException: factory` -- `CommandLine$Model$CommandUserObject.create` ->
    `CommandLine$Assert.notNull`, under `extractCommandSpec` -> `forAnnotatedObject`. Batch 2 pulls **+2164
    blobs against 1344**, 78% more classes, so this is a materially bigger VM than any boot before it.
  - **AND IT NAMES THE SECOND LATENT DEFECT THE COIN FLIP HAD BEEN CHOOSING BETWEEN.** `make
    overlaycheck-deep` reports `jdk/internal/misc/Unsafe getAndBitwiseOrInt(Ljava/lang/Object;JI)I` as
    referenced-but-dropped: the overlay declares `getAndBitwiseOrLong` and NOT the `Int` form, so the method
    CEASED TO EXIST -- the overlay-drops-stock-members trap for the eleventh time. The shallow check cannot
    see it because the only caller is STOCK java.base (`ForkJoinTask.setDone`), which is exactly the blind
    spot `overlaycheck-deep` exists for. **Correction to the entry below: I wrote that `getAndBitwiseOrInt`
    "lands on the wrong side of the cut". It does not -- it is absent outright. What the cut moved was
    whether `BigDecimal.<clinit>` became reachable and ran that path at all.**
  - **NOT FLASHED, and that is a judgement worth stating.** Committed but not on hardware: main now has a
    correct closure and a launcher that stops at batch 4 on QEMU, where before it had a 44%-truncated
    closure and a launcher that completed. The second state looks better and is worse -- it worked by luck,
    and any edit re-rolled the dice. **NEXT: the picocli `factory` NPE**, which is now the honest blocker.

- **THE LAUNCHER IS RESTORED, AND A SAME-BUILD-PATH A/B CLOSES THE ONE CONFOUND I HAD LEFT OPEN
  (2026-09-16, PI-VALIDATED).** With the instrument reverted, the Pi runs to batch 209, `[2 tests
  successful]`, exit 0, **`Test run finished after 96983 ms` -- identical to the millisecond** to the
  original `f7b3eec` validation. Identity exact: `memo=128548 res=82350 unres=27419`, `rf:skip=114380
  visit=44987 clos=44987 holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `sd:n=2802 steps=2590k`,
  `ps:n=580 miss=580 tab=3051`, `rb:n=50071 cl=1782`, `sy:n=2276 chg=0`, `hcls=0k`, `pc:n=1243`.

  | build path | code | result |
  |---|---|---|
  | direct `BuildRuntimeImage` | `f7b3eec` | PASS |
  | **`make image`** | `af291ca` (instrument) | **FAIL at batch 2** |
  | **`make image`** | `b6c0c31` (instrument + OP_MAX fix) | **FAIL at batch 2** |
  | **`make image`** | `9ca5680` (instrument REVERTED) | **PASS** |

  - **I HAD FLAGGED A REAL FLAW IN MY OWN EXPERIMENT AND IT TURNED OUT NOT TO MATTER -- BOTH HALVES WORTH
    RECORDING.** `make image` depends on `build jdktests plugins appjar junitjar`, so it regenerates the
    RAMFS; both failures were `make image` builds and the first passing control was a DIRECT build ~28KB
    smaller. That is two variables, not one, and saying so was right. Rebuilding the reverted source through
    `make image` -- the failures' own path -- removes it: **the build path is not the variable, the
    instrument is.** The original control was right for the right reason after all.
  - **(The 28KB was itself explained rather than waved at:** ten adjacent byte-pairs around `/lib/app.jar`'s
    `META-INF/`, i.e. DOS timestamps in zip local headers. `ramfs/lib/app.jar` is gitignored and regenerated
    by the build, and the launcher reads `junit.jar`, never `app.jar`. Two builds from identical sources are
    byte-identical, so the build IS deterministic.)
  - **SO THE CAUSE IS THE INSTRUMENT AND THE MECHANISM IS THE TRUNCATED CLOSURE, which is the only
    explanation left standing that fits every observation:** it is hardware-only (QEMU ran all three to 209
    with identical counters), it was NOT fixed by the `OP_MAX` correction, and it IS fixed by restoring the
    exact prior layout. Adding ~11 statics and a few hundred bytes of code to `Loader` moves which 8,192 of
    14,554 methods survive `MAXREACH`, and `getAndBitwiseOrInt` -- a signature-polymorphic VarHandle op this
    file already records as invisible to RTA -- lands on the wrong side of the cut.
  - **THE INSTRUMENT'S READING STANDS AND IT CANNOT SHIP YET.** `betw`/`gcT`/`lzT` measured the boot's
    unmeasured two thirds and named the collector as its largest item; none of that is retracted. But no
    instrument can be trusted while adding a static can change the closure, so the order of work is fixed:
    **fix the truncation first, then re-land the measurement on top of a closure that is not
    layout-sensitive.**

- **THE RTA CLOSURE HAS BEEN SILENTLY TRUNCATED AT 44% ON EVERY LAUNCHER BOOT -- AND MY DEEP-STACK
  DIAGNOSIS ONE COMMIT AGO WAS WRONG (2026-09-16).** The fix below -- `lazyCompile` back to `stack=4`,
  identical to the control -- **did not change the outcome**: the Pi wedged at batch 2 again, at the same
  `BigDecimal.<clinit>` -> `ForkJoinTask` -> `Unsafe.getAndBitwiseOrInt` trap, with near-identical numbers
  (`betw=3695.745ms lz:n=97 lzT=2003.987ms gcT=558.667ms` against 3690/97/2001/557). **`OP_MAX` was not the
  mechanism. I stated it as the root cause and one boot refuted it.**

  | `MAXREACH` | batch 1 `reach` | `rounds` | `pend` |
  |---|---|---|---|
  | **8192** (shipped for the life of this project) | **8192 -- THE CAP, EXACTLY** | 26 | 56,383 |
  | 65536 | **14,554** | 54 | 96,158 |

  - **THE TRUE CLOSURE IS 14,554 METHODS AND THE CAP IS 8,192, SO ~6,362 REACHABLE METHODS -- 44% -- HAVE
    BEEN DROPPED ON EVERY LAUNCHER BOOT**, including every one that passed and exited 0. `addReach` was
    `if (code == 0L || reachN >= MAXREACH) { return false; }`: **no report, no counter, no marker.** The
    same silent `if (room) { record it }` shape `PEND LIST FULL` was raised and made to report for, and the
    shape this file already records as "the single most expensive failure mode in this project's history".
  - **AND IT MAKES CLOSURE MEMBERSHIP LAYOUT-SENSITIVE, which is what actually explains the two lost boots.**
    Which 8,192 of 14,554 survive is insertion order, so ANY change to the image can move the cut. An
    instrument that adds two statics is enough. **That is not a property a closure may have**, and it means
    every "identity exact" claim in this file was made against a closure that was already 44% short --
    stable between boots, which is why it never showed, but stable for no good reason.
  - **A DROPPED METHOD IS NEVER COMPILED, so a by-name dispatch onto it fails somewhere else entirely** --
    `VIRTUALRESOLVE FAILED jdk/internal/misc/Unsafe.getAndBitwiseOrInt`, in a class that looks unrelated to
    anything I touched. This file already records that `getAndBitwiseOrInt` is one of the
    signature-polymorphic VarHandle ops that **RTA cannot see and which must be seeded, "else a 0 vtable
    slot"**. That is exactly the shape of a method falling off the end of a truncated mark.
  - **RAISING THE CAP IS NOT A DROP-IN FIX, and that was checked rather than assumed.** At 65536 the closure
    really does complete (`REACH LIST FULL` never fires) -- and QEMU then fails DIFFERENTLY, with
    `NullPointerException: factory` in picocli's `CommandUserObject.create`, on a batch-2 closure of
    **+2164 blobs against 1344**. 78% more classes is a different VM to debug. It needs its own arc.
  - **SO THE INSTRUMENT IS REVERTED OUT OF THE IMAGE.** `src/vm/Loader.java` and `src/vm/VMGc.java` go back
    to `f7b3eec` byte-for-byte (`git diff f7b3eec -- src/ guestsrc/ ramfs/` is empty), which is the build
    PROVEN to pass on this Pi. A measurement is not worth a launcher that does not boot, and I have no fix
    for the defect underneath it. **What is kept is the host-side guard** (`compiler: 37 -> 39 checks`),
    because it is build-time only and cannot perturb an image.
  - **THE ACCOUNTING, because it was expensive: THREE Pi boots.** One to find the regression, one to test a
    wrong hypothesis, one control. What would have saved two of them is the rule this file already states
    and I did not apply to myself: **the control comes FIRST.** I flashed an unvalidated instrument, and
    when it failed I reached for a mechanism instead of a bisect. The `OP_MAX` work was not wasted -- the
    guard is real and its negative control is verified -- but it was an answer to a question I had not
    established was the right one.
  - **WHAT THE THREE BOOTS DID BUY, and it is worth more than the instrument was:** a 44% truncated closure
    on every boot of the flagship workload, the reason it was invisible (silent by construction), the reason
    it bites unpredictably (layout-sensitive), a measured true size (14,554), and the knowledge that fixing
    it uncovers a second failure rather than a green boot. **NEXT ARC, and it is ahead of any load-path
    work:** report the overflow, raise the cap, and take the picocli `factory` NPE the full closure exposes.

- **MY INSTRUMENT PUSHED `lazyCompile` PAST `OP_MAX` AND BROKE THE PI -- CONTROL-PROVEN MINE, AND QEMU COULD
  NOT SEE IT (2026-09-16). THE `OP_MAX` DIAGNOSIS BELOW IS REFUTED BY THE ENTRY ABOVE: the fix landed and
  the Pi failed identically. The guard it produced is real and kept; the CAUSE it claims is not.** The `betw`/`lzT`/`gcT` instrument below wedged the launcher at BATCH 2 on
  hardware, in the recorded java.math landmine: `BigDecimal.<clinit>` -> `squareToomCook3` -> `RecursiveOp`
  -> `ForkJoinTask` -> `Unsafe.getAndBitwiseOrInt`. An instrument-only change cannot move a closure, so my
  first instinct was that this was pre-existing. **It was not, and one control boot said so.**

  | launcher boot | result |
  |---|---|
  | control `f7b3eec` on the **Pi** | batch 209, `[2 tests successful]`, exit 0, 96,996ms |
  | change `af291ca` on the **Pi** | **wedged at batch 2**, denylist trap |
  | both, on **QEMU** | batch 209, identical counters, one known `ProcessImpl` trap |

  - **ROOT CAUSE, AND IT IS VISIBLE IN THE BYTECODE RATHER THAN THE DIFF.** Two timestamp LOCALS added to
    `Loader.lazyCompile` took it from **`stack=4, locals=4` to `stack=8, locals=8`**. `Baseline.OP_MAX` is
    **7**: past it the operand stack leaves registers for FRAME MEMORY, so the method compiled in DEEP-STACK
    mode for the first time in its life. Its compiled body went 532 -> 976 bytes, +83%, for what should have
    been two clock reads.
  - **AND `lazyCompile` CARRIES A `try/finally`.** A deep-stack HANDLER is exactly where this VM has already
    been bitten and Pi-validated once: "a deep-stack handler read the caught exception from an unwritten
    spill slot", where the inline path worked and only a CROSS-METHOD unwind failed. `lazyCompile` is the
    hottest exception-bearing path in the VM, and on the launcher it unwinds for real -- JUnit throws as
    ordinary control flow.
  - **THE FIX MOVES THE STATE TO STATICS, which is what this file already prescribes for `Loader`** ("state
    in statics because methods are capped at 10 local slots"). Two helpers, `lzEnter`/`lzExit`, so
    `lazyCompile` gains NO locals and NO operands: it is back to **`stack=4, locals=4`, identical to the
    control**. `lzExit` was then split into steps because it landed at `stack=8` itself -- it is a leaf and
    would probably have been fine, and "probably fine" is precisely what cost the boot.
  - **A BUILD-TIME GUARD NOW CATCHES THIS CLASS OUTRIGHT, and its bound is MEASURED rather than guessed.**
    `vm/Loader` has 599 compiled methods, **52 of them legally deep (`stack > 7`), and NONE carries an
    exception handler** -- so the forbidden thing is the PAIRING, not depth. `compiler: 37 -> 39 checks`.
    **NEGATIVE CONTROL, run before it shipped:** against the failing commit the test FAILS, naming
    `lazyCompile(I)J stack=8`; against the fix it passes. That check costs milliseconds and would have
    replaced a Pi boot.
  - **WHAT I HAVE PROVEN AND WHAT I HAVE NOT.** Proven: the regression is mine (control passes on the same
    hardware), and the deep-stack switch is the ONLY codegen-mode change my instrument caused. **NOT proven:
    that deep-stack is the mechanism by which an extra class entered batch 2.** The Pi pulled `+1344blob
    rounds=3 reach=8` where QEMU pulled `+1343blob rounds=2 reach=1`, and I cannot yet explain that link.
    Only a passing hardware boot closes it, and this entry stays NOT PI-VALIDATED until one does.
  - **QEMU IS STRUCTURALLY BLIND HERE, which is worth stating precisely.** It ran BOTH binaries to batch 209
    with byte-identical counters. The launcher's hardware-only conditions -- picocli's terminal-width probe
    on its own thread, the `ProcessImpl` trap, real timing against a 166MHz core -- are what exercise this,
    and the emulator runs execution ~100x slow with a near-real-time counter. **A green QEMU A/B is not
    evidence that a codegen-mode change is safe.**
  - **MY OWN A/B WAS BROKEN ONCE ON THE WAY, AND `cmp` CAUGHT IT -- the third recorded instance.** I compared
    symmaps and got IDENTICAL addresses for both arms, because a `git checkout` of the other arm's sources
    was never followed by `make build`, so `out/` held stale classes and both runs emitted the control
    (33,288,136 bytes, byte-identical, neither containing the string `betw`). **An A/B whose arms are the
    same binary looks exactly like a change that does nothing.** Checking the images, not the exit code, is
    what separated the two.
  - **ALSO FOUND, PRE-EXISTING AND UNRELATED, AND NOT FIXED HERE: the RTA closure is SILENTLY TRUNCATED.**
    Batch 1 of every launcher boot reports **`reach=8192`, and `MAXREACH` is 8192** -- the cap hit exactly,
    on the Pi and on QEMU, on control and change alike. `addReach` is `if (code == 0L || reachN >= MAXREACH)
    { return false; }`: no report, no counter. That is the same silent `if (room) { record it }` shape that
    `PEND LIST FULL` was raised and made to report for, and a dropped ref means a class is never pulled and
    its `<clinit>` never enqueued. Deliberately left off this card -- two unvalidated changes on one card is
    what forced a bisect last time -- and it is the first thing to look at next.

- **THE LOAD PATH IS NO LONGER THE BOTTLENECK, AND THE THING THAT IS HAS NEVER BEEN MEASURED -- `betw`,
  `lzT`, `gcT` (2026-09-16, REVERTED OUT OF THE IMAGE -- see the two entries above; the READING stands,
  the instrument cannot ship until the closure stops being layout-sensitive).** Nine increments took a launcher boot 161,556 ->
  96,983ms and the per-batch load path to 32.9ms. But every cumulative figure in this file measures time
  INSIDE `loadAll`, and the same log says **65,644ms of that 96,983ms is TEST EXECUTION** -- so what this
  arc has been cutting is now a few percent of the boot and roughly two thirds of it is covered by no timer
  at all. Before hunting a tenth target, measure the part nobody has looked at.

  | demo suite, last batch (QEMU) | | |
  |---|---|---|
  | `betw` -- wall clock OUTSIDE `loadAll` | **14,309.707ms** | the boot, essentially |
  | `gcT` / `gcB` -- collector, total / inside a batch | **7,458.403ms** / 132.930ms | **51% of `betw`** |
  | `lzT` / `lzB` -- first-call compiles, total / in-batch | 2,516.363ms / **0us** | 488 calls, 5.2ms each |
  | `rpt` -- this report's own serial traffic | 195.941ms | see the prediction below |
  | `tot` (one batch) | 12.248ms | what nine increments have been cutting |

  - **THE COLLECTOR IS THE LARGEST SINGLE ITEM IN THE BOOT OUTSIDE THE BATCHES, and it appears on no line
    today.** 7.3s of `betw` after subtracting the 133ms that fired inside a batch. `gc=` has been on the
    batch line since the batch-188 chase and says only that a collection HAPPENED -- never what it cost, so
    the one figure this file carries (~565ms at 1750 blobs) came from a single batch where a collection
    happened to land inside an unrelated timer, after FOUR boots.
  - **`betw` COSTS NOTHING TO COLLECT, which is why it should have existed years ago:** `tAll` and `tEnd`
    are already read at both ends of every batch, so the gap between them is a subtraction. `gcT` is two
    clock reads per COLLECTION (45 on the suite, ~10 on the launcher) and `lzT` two per lazy compile, which
    runs the whole compiler -- the opposite of the per-item loops this project has had to strip twice.
  - **THE INSTRUMENT CAUGHT ITS OWN FLAW ON ITS FIRST RUN, before anything shipped.** I wrote `lz` and `gc`
    as pieces INSIDE `betw`. Batch 1 came back `betw=0us gcT=7.081ms` -- a collection with no gap yet to
    hold it, i.e. inside `loadAll`. A batch allocates, so it can collect; it runs `<clinit>`s through
    `Magic.call0`, which is guest code, so it can lazily compile. **Both terms CUT ACROSS the split**, which
    is exactly the "a sub-split that does not add up is not a split" defect this file records against the
    `mark` split -- committed this time against my own. Each now prints its total beside the part that fired
    inside a batch, so `gcT - gcB` and `lzT - lzB` are what decompose `betw` and nothing double-counts `tot`.
  - **`lzB=0us` IS A REAL ANSWER, not an empty counter:** not one of the 488 first-call compiles happened
    inside a batch. The `<clinit>`s a batch runs reach already-compiled code, so lazy compilation is
    entirely an execution-time cost -- which is where it should be looked for.
  - **WHAT `betw` STILL DOES NOT NAME: ~4.5s, 31% of it.** GC and lazy compile account for 9,841 of
    14,310ms; the rest is the program actually running, plus real `Thread.sleep`. That remainder is the
    floor, and it is worth having as a number rather than an assumption.
  - **`lzT` IS NET OF ANY BATCH IT TRIGGERS, because it genuinely triggers them:** `lazyCompile ->
    drainPendingPulls -> loadClassIncremental -> loadAll`. `cumAll` grows by exactly that batch's bracket,
    so subtracting its delta leaves compile time alone. It is timed INSIDE the loader lock (waiting for it
    is another core's compile, and charging it here would bill one compile to two cores), and only the
    OUTERMOST compile accumulates -- counting re-entries would hide the nesting this exists to measure,
    which is the `rel`-counter lesson from the loader-lock arc.
  - **IDENTITY EXACT AGAINST A CONTROL BUILT FROM HEAD -- AND THE RECORDED FIGURE WOULD HAVE READ AS A
    REGRESSION.** `memo=1418 res=2510 unres=2253` on this change, against `memo=1379 res=2446 unres=2193`
    written in this file for the suite two increments ago. That looked like a broken closure. Building HEAD
    and booting it gives **1418/2510/2253 as well** -- the recorded figure is simply from a different tree
    state. Also identical: `rounds=4 pend=180 reach=16`, `rf:skip=1631 clos=1173 holeEnd=1074`, `n:imap=52
    synth=18`, `sd:n=283`, `ps:n=140`, `rb:n=10743`, `sy:n=32`, `hcls=0k`, `pc:n=103`, `gc=45`, 33 programs,
    TWELVE failure markers zero in both arms. **A cited number is not a measured one** -- this file's own
    rule, and one boot of the control is what kept it from being broken here.
  - **The only output diffs are the recorded run-to-run ones:** `smp jobs`/`jobs/core`/`per-core tasks`/
    `steps/core` (which this file already records as differing on the SAME binary), and `gc: roots` +15
    words with **`heap=` byte-identical** -- the statics region grew by nine new statics plus slots this
    does not separately account for, and the TRACE side is untouched, so nothing about reachability moved.
    Host tests unchanged: A64 105, object-model 22, class-reader 171, refmap 14, `compiler: 37 checks`,
    crypto 17, zip 91, `overlay-check 0 new`.
  - **A PREDICTION, and it is about the instrument rather than a fix.** `rpt` reads 196ms on QEMU because
    **QEMU's serial is not baud-paced** -- the same structural blindness that hid the `clinit` UART artifact
    for the life of this project. On a Pi at 115200 baud one batch line is ~300 characters at ~87us each,
    so ~200 batches should put `rpt` in the **seconds**, and if it does not, the batch line is shorter than
    I think or `printDur` is cheaper than the character count implies. Either way it is now a term rather
    than a contaminant in somebody else's number.
  - **WHAT THE BOOT DECIDES:** whether the launcher's ten collections really are ~565ms each (~5.6s, the
    figure this file carries from one accidental measurement), and what share of its 65,644ms of test
    execution is GC, first-call compilation, and genuine sleeping. **No fix is proposed here on purpose** --
    the previous nine increments each targeted something a counter had already ranked, and nothing has
    ranked this yet.

- **`synth` RE-WROTE NINE WORDS PER SYNTHESISED TIB PER BATCH -- 24.3x, AND NOT ONE OF THEM EVER CHANGED
  ANYTHING (2026-09-16, PI-VALIDATED).** `synth` was 191.718ms at batch 209 -- the largest item left outside
  `callT` and `imap`, and the third top-level split in a row that had never been looked inside. It is
  `refillSynthTibVtables`, which walks every synthesised lambda/annotation TIB on EVERY batch and re-copies
  `java/lang/Object`'s vtable into each; `n:synth` grows all boot (511 at batch 59, **2276** at batch 209).

  | demo suite, batch 64 (cumulative) | before | after | |
  |---|---|---|---|
  | `sy:chg` -- writes that CHANGED the stored word | **0** | **0** | not one, ever |
  | `sy:slots` -- vtable words written | 3k | **0k** | |
  | `sy:obj` -- objectClassIndex scan steps | **0k** | 0k | the other candidate, killed |
  | `synth` | 594us | **295us** | 2.0x (and the suite understates by ~126x) |
  | `memo/res/unres`, `rf:*`, `n:*`, `ps:*`, `rb:*` | -- | **identical** | |

  - **READING NAMED TWO CANDIDATES AND RANKED THE WRONG ONE FIRST, for the fifth time in this arc.**
    `fillObjectVtableUpTo` calls `objectClassIndex()` -- a linear walk of all `clCount` classes -- ONCE PER
    TIB, so 2276 scans a batch, and hoisting that out of the loop is the remedy `printFrameAt` needed in the
    demand-load arc. **`obj=0k` says it is worth nothing**: `java/lang/Object` is registered early, so the
    scan returns in a handful of steps and never grows. The plausible O(n) was not the cost. Again.
  - **`chg=0` IS THE WHOLE FINDING, and it is a counter that says "not here".** Every write stored the value
    the slot already held, across an entire boot. The pass was re-deriving an answer it already had -- the
    `seeds` shape exactly, where a one-shot flag was worth 148x. **A counter designed to decide between two
    fixes instead invalidated one of them and sized the other.**
  - **LATCHED ONLY ON A COMPLETE FILL (`n >= cap`), which is the correction the seeds fix had to make.** A
    TIB built while Object had fewer virtuals than the TIB has room for is filled PARTIALLY; latching there
    would leave the rest zero for ever, and a 0 vtable slot reached from BAKED code -- which carries no
    dispatch guard -- is a wild branch, not a named trap. `fillObjectVtableUpTo` answers whether it filled to
    capacity and only that latches.
  - **SOUND BY CONSTRUCTION AS WELL AS BY MEASUREMENT.** `java/lang/Object` is THE ONE EAGERLY-COMPILED CLASS
    (its nine virtuals are the prefix of every vtable in both worlds), so its slots hold real bodies from
    registration and are never re-pointed by a lazy compile the way a deferred class's are. `chg=0` is the
    empirical half of that argument, and the counter STAYS on the batch line so a future boot can refute it.
  - **A BUG I ALMOST SHIPPED, caught by this file's own record rather than by a test.** The latch array is
    read at every appended index, and **`allocArray` does not zero its elements on this VM** -- so a garbage
    `true` would have skipped a TIB that still needed filling, i.e. a 0 vtable slot and a wild branch from
    baked code. It is now cleared explicitly at BOTH append sites. Same trap the SMP arc hit with
    `taskIdle`/`coreSched`/`gcParked`, and the reason that entry exists.
  - **`lambdaTibRoots` IS APPEND-ONLY -- checked, not assumed.** Three writes exist: the `resetLoader`
    allocation and two appends at `lambdaTibRootN`. No entry is ever cleared or re-pointed, so a latch cannot
    carry over to a different TIB.
  - **THE SUITE UNDERSTATES THIS BY CONSTRUCTION AND THE FACTOR IS KNOWN: 18 synthesised TIBs against the
    launcher's 2276, ~126x.** Same asymmetry as the seeds (which the suite understated 17x) and `statT`.
  - **WHAT THE INSTRUMENT SHOWS THE FIX DOING, which is the part a timer cannot:** `sy:n` -- TIBs filled,
    cumulative -- now holds FLAT across batches that create no new TIB (24, 24, 24) and steps only when they
    appear (30). Each TIB is filled exactly once, where before every one was refilled every batch.
  - **A PREDICTION, and deliberately a range rather than a point.** The redundant writes are gone, but the
    pass still WALKS the root array every batch to check the flag: 2276 entries x 209 batches is ~476k
    iterations that no longer do anything. If the copying was the cost, `synth` lands in the low tens of
    milliseconds; if that surviving walk dominates, it lands nearer 50ms and **the next fix is a watermark
    rather than a per-entry flag** -- sound here because the array is append-only. Either way, near 190
    would mean the walk was never the cost and the reading above is wrong.
  - **IDENTITY:** `memo=1379 res=2446 unres=2193`, `rf:skip=1596 visit=1156 clos=1156`, `n:imap=52 synth=18
    clinits=25`, plus `ps:*` and `rb:*` untouched. 32 programs, EIGHTEEN failure markers zero, program output
    byte-identical but for the SMP task interleaving and the philosophers' ordering. Host tests unchanged:
    A64 105, object-model 22, class-reader 171, refmap 14, `compiler: 37 checks`, crypto 17, zip 91,
    `overlay-check 0 new`.
  - **PI-VALIDATED: `synth` 191.718ms -> 7.903ms (24.3x, -184ms), AND MY PREDICTED RANGE WAS TOO
    CONSERVATIVE AT ITS LOW END.** I said "low tens of milliseconds if the copying was the cost; nearer 50ms
    if the surviving walk dominates". **It reads 7.903ms -- BELOW the range I gave.** The direction was
    right and the lower bound was wrong: the per-batch walk of the root array, which I estimated at ~0.1us an
    iteration, costs far less than that. I am not inventing a mechanism for the exact figure; what the number
    settles is that the walk is NOT worth a watermark, which is the decision the range existed to make.

    | launcher, batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `synth` (cumulative) | 191.718ms | **7.903ms** | **24.3x, -184ms** |
    | `sy:n` (TIBs filled, cumulative) | -- | **2276** | **= `n:synth` EXACTLY: each filled ONCE** |
    | `sy:slots` | -- | 20k | = 2276 x 9, one fill each |
    | `sy:chg` | -- | **0** | at 2276 TIBs and 1782 classes |
    | `sy:obj` | -- | 20k | ~9 steps a call: Object sits at clTab[~9] |

  - **`sy:n=2276` AGAINST `n:synth=2276` IS THE PROOF, and it is the `sd:n` shape.** Every synthesised TIB
    was filled exactly once across the whole boot, where before every one was refilled on every batch --
    ~476k TIB-visits and ~4.3M vtable words collapsed to 2276 and 20k.
  - **`chg=0` HELD AT FULL SCALE, which is the claim that mattered.** Not one write was ever necessary, at
    1782 classes and 2276 TIBs. That is the empirical half of "Object is the one eagerly-compiled class, so
    its vtable slots are never re-pointed", and the counter stays on the batch line so a future boot can
    still refute it.
  - **AND `obj=20k` CONFIRMS THE CANDIDATE THE COUNTERS KILLED.** `objectClassIndex` finds `java/lang/Object`
    in ~9 steps because it is registered early; hoisting it out of the loop -- the fix reading ranked first
    -- would have removed 4.3M steps of a scan that was never the cost, and left the 4.3M redundant WRITES
    in place. **Fifth time in this arc that reading named a plausible O(n) and measurement refused it.**
  - **THIRD CLEAN TIMING CONTROL IN A ROW:** every untouched cumulative timer within 0.7% -- `callT` 927.087
    -> 928.946, `lookT` 681.174 -> 679.382, `unresT` 138.399 -> 138.918, `imap` 604.744 -> 608.677, `statT`
    4.460 -> 4.417, and `ps:n=580 miss=580 tab=3051` / `rb:n=50071 steps=63k` byte-identical.
  - **IDENTITY EXACT:** `memo=128548 res=82350 unres=27419`, `rf:skip=114380 visit=44987 clos=44987
    holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `sd:n=2802 steps=2590k` frozen, `hcls=0k`,
    `pc:n=1243`, `pb:probed=1 of=1782`. **The failure shape here is a wild branch rather than a wrong
    answer** -- a TIB latched before it was full leaves a 0 vtable slot and baked code carries no dispatch
    guard -- so the assertion is the absence of `BOOT RE-ENTERED`, `FAULT`, `unclaimed pc` and `ESR EC=0`,
    all zero. `[3 containers successful]` / `[2 tests successful]` / exit 0, only the two known denylisted
    lines. Boot 96,750 -> 96,983ms with the tests within 43ms: flat, inside the noise.
  - **THE ARC: 161,556 -> 96,983ms -- 64.6 SECONDS, 40% OFF A LAUNCHER BOOT.** Per batch at 209, `tot` is
    **32.9ms** against the 676.1ms this arc started from.
  - **AND THE CHEAP WINS ARE DONE, which is worth saying plainly rather than hunting for a tenth.** The
    ranking at batch 209 is `callT` 928.946 (of which `lookT` 679.382, `unresT` 138.918, `tailT` 102.811),
    `imap` 608.677, then `alloc` 106.035 -- and `synth` 7.903 and `statT` 4.417 have both left the list.
    **Every one of the three ahead already has a recorded reason not to touch it:**
    - `lookT` -- memoising the super-chain and stub tiers would answer with an ancestor's body after a
      subclass registered its own override. A silent wrong-method dispatch, and 0.7% of a boot.
    - `imap` -- the `hnam` residue was MEASURED at ~400ms and the fix is re-keying `rgBucket` at four sites,
      two on the patch hot path. Declined once on those grounds; the grounds have not changed.
    - `alloc` -- already cut 266x in the demand-load arc (27,585ms -> 104ms) and sitting at 106ms since.
    What is left is a load path where the largest items are correct-by-design rather than defective. A tenth
    increment would be looking for one, which is how the batch-188 chase cost four boots for "not a defect".


- **THE CLASS REGISTRY HAD NO NAME INDEX -- THREE COPIES OF ONE SCAN, `statT` 42.5x, AND THE PREDICTED
  FIGURE LANDED (2026-09-16, PI-VALIDATED).** The previous increment indexed the STATIC registry and then could not
  account for its own residue: `statT` 189.764ms across 580 sites is **327us a site**, absurd for a hash
  probe. The arithmetic named the culprit exactly -- every one of those 580 misses calls
  `reportZeroCellBind`, whose FIRST act is `regBySigU`, a linear walk of all `clCount` classes run purely to
  decide whether the miss is worth PRINTING. 580 x 1782 = ~1,034k `utf8EqAt` calls, which at that boot's own
  measured ~0.18us a call is **~186ms against a measured 189.764ms**.

  | demo suite, batch 64 (cumulative) | before | after | |
  |---|---|---|---|
  | `rb:steps` | **117k** | **9k** | and the AFTER counts 8.7x MORE calls |
  | `rb:n` (calls counted) | 1224 | 10,696 | before: regBySigU only; after: all three |
  | steps PER CALL | **95.6** | **0.84** | **114x** -- most probes reach an empty bucket |
  | `statT` | 21.389ms | **933us** | **23x** |
  | `memo/res/unres`, `rf:*`, `n:*`, `ps:*` | -- | **identical** | |

  - **`rb` IS NOT LIKE-FOR-LIKE ACROSS THE ARMS, AND SAYING SO IS THE POINT.** Before, it counted
    `regBySigU` alone; after, it counts the shared body that `classRegByName` and `classRegByNameAt` also
    route through -- so the AFTER figure covers 8.7x more calls and is still 13x smaller in total. The
    honest comparison is steps PER CALL, and that is 95.6 -> 0.84.
  - **THREE SPELLINGS OF ONE QUERY, each carrying its own copy of the scan.** `regBySigU` (an absolute
    `{u2 len}{bytes}` run), `classRegByName` (an offset into `gbase`) and `classRegByNameAt` (base + offset)
    differ only in how the key bytes are addressed; the predicate and the answer are identical. All three are
    one body now -- the "one call site's fix, several timers" shape the phase-B publish and the static index
    both had. `classRegByNameBytes` is deliberately LEFT ALONE: a different key shape (a VM-side `byte[]`),
    and its callers are the seeds, which latch.
  - **THE KEY COSTS NOTHING TO BUILD, because a previous increment already paid for it.**
    `RVMClass.nameHash` is folded once at registration -- it exists because the imap refill was re-folding it
    per probe -- so the index is built from a field rather than by re-walking any name. Only the PROBE folds,
    once, where the scan folded nothing and compared everything.
  - **Sound by construction:** `clTab[clCount]` is written and `clCount` incremented immediately after, at
    BOTH registration sites (the interface path and `registerClassStructure` -- checked, not assumed), and no
    entry is ever re-pointed. **THE LOWEST MATCHING INDEX STILL WINS** -- head-insertion makes the chain
    descending, so it is searched for the MINIMUM rather than stopped at the first hit. Two entries share a
    name only when a class is registered twice (the `lifecycle DIFF` shape this file records), and silently
    preferring the later one would change which class EVERY caller of this resolves to. The watermark resets
    BESIDE the table in `resetLoader`, not only through the "went backwards" check.
  - **ONE DEFENSIVE NULL CHECK WAS DROPPED, AND THAT IS DELIBERATE RATHER THAN OVERSIGHT.**
    `classRegByNameAt` alone tested `clTab[i] != null`; the other two did not, and the registration sites
    show entries below `clCount` are never null. The index builder walks every entry, so if that invariant is
    ever broken it now NPEs loudly at boot instead of one caller in three quietly skipping a class.
  - **A CHECKABLE PREDICTION, stated because it is falsifiable.** If the account above is right --
    `statT`'s residue being almost entirely this gate -- the Pi should read `statT` **near 4ms**, not near
    190. Anything close to 190 means the 327us-a-site arithmetic was wrong and the residue is something I
    have not named. (The suite's 23x is consistent but cannot settle it: 189.764ms is a launcher figure at
    1782 classes against the suite's 189.)
  - **IDENTITY:** `memo=1379 res=2446 unres=2193`, `rf:skip=1596 visit=1156 clos=1156 holeEnd=1057`,
    `n:imap=52 synth=18 clinits=25`, and `ps:n=136 steps=0k miss=136 tab=280` -- the static index untouched.
    32 programs, TWENTY-THREE failure markers zero, `finish HML` 20/20/20, inversion 60ms, `smp sched: 4 of
    4`, `sum20=210`, `YNW`/`RP`, `ifacedflt`/`ifacedfltch` late-default, `churnMB=625 live=32 intact=32`.
    Program output byte-identical but for the SMP task interleaving and the philosophers' ordering, which
    this file already records as differing run-to-run on the SAME binary. Host tests unchanged: A64 105,
    object-model 22, class-reader 171, refmap 14, `compiler: 37 checks`, crypto 17, zip 91,
    `overlay-check 0 new`.
  - **PI-VALIDATED, AND THE PREDICTION LANDED: I said `statT` would read "near 4ms, not near 190". IT READS
    4.460ms.**

    | launcher, batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `statT` (cumulative) | 189.764ms | **4.460ms** | **42.5x, -185ms** |
    | `rb:steps` | 3,926k | **63k** | 62x, while counting 9.4x MORE calls |
    | `rb:n` (calls counted) | 5,316 | 50,071 | before: regBySigU only; after: all three |
    | steps PER CALL | **738** | **1.26** | **585x** |
    | whole boot | 98,464ms | **96,750ms** | **-1,714ms** |

  - **THE PREDICTION IS THE RESULT HERE, more than the ratio.** The previous increment could not account for
    its own residue and I turned the gap into a falsifiable number: 580 sites x 1782 classes x ~0.18us =
    ~186ms of a measured 189.764ms, so removing it had to leave ~4ms. **It left 4.460ms.** That is the
    arithmetic, the cost-per-`utf8EqAt` estimate AND the identification of `reportZeroCellBind`'s gate as the
    whole residue, all confirmed at once -- and it would have been visibly wrong had any of the three been.
  - **AND THE MACHINE WAS PINNED AGAIN, so the figure is honest:** every untouched cumulative timer moved
    under 0.5% -- `callT` 931.371 -> 927.087, `lookT` 683.009 -> 681.174, `unresT` 138.691 -> 138.399,
    `imap` 605.914 -> 604.744, `synth` 191.697 -> 191.718. Two Pi boots in a row with a clean control, after
    an arc where QEMU could not separate a 2x change from machine load.
  - **THE BOOT MOVED 1,714ms AND MOST OF IT IS WHERE NOTHING WAS MEASURING.** `statT` accounts for 185ms of
    it. The rest belongs to the other seven callers of `regBySigU` and to `classRegByName`/
    `classRegByNameAt`, which sit in the compile and clinit paths that no cumulative timer covers -- the
    boot total is the only instrument that can see them. **Stated as consistent-with rather than proven:**
    the seven launcher boots now read 99,433 / 99,299 / 99,151 / 97,817 / 97,914 / 98,464 / **96,750**, and
    that ~1.6s spread is the same order as the move. What makes this reading better than the usual one is
    the pinned control above, not the delta itself.
  - **IDENTITY EXACT ON EVERY GATE:** `memo=128548 res=82350 unres=27419`, `rf:skip=114380 visit=44987
    clos=44987 holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `sd:n=2802 steps=2590k` still frozen,
    `hcls=0k`, `pc:n=1243`, `pb:probed=1 of=1782`, and `ps:n=580 steps=3k miss=580 tab=3051` -- the static
    index untouched, still 100% miss. **The assertion for THIS change is a set of absences**, because an
    index that answered the wrong class would not crash, it would resolve eight callers to a different class
    for ever: no `CANNOT LOAD`, no `UNREGISTERED SUPER`, no `VIRTUALRESOLVE FAILED`, no `ClassCastException`,
    no `lifecycle DIFF`, no `STATIC BOUND TO THE ZERO CELL`. `[3 containers successful]` / `[2 tests
    successful]` / `[0 tests failed]` / exit 0, with only the two known denylisted lines.
  - **THE ARC: 161,556 -> 96,750ms -- 64.8 SECONDS, 40% OFF A LAUNCHER BOOT.**
  - **WHAT IS NEXT: `synth` at 191.718ms, and it has never been split.** The ranking at batch 209 is now
    `callT` 927.087 (of which `lookT` 681.174, `unresT` 138.399, `tailT` 99.573), `imap` 604.744,
    **`synth` 191.718**, `alloc` 105.120 -- and `statT` has dropped off the list entirely at 4.460ms.
    `synth` is `n:synth=2276` synthesised lambda/annotation TIBs, and it grows steadily (187.255ms three
    increments ago). Same starting position `seeds` and `statT` were in: nobody has looked inside it.


- **`statT` HAD NEVER BEEN SPLIT -- 2.45x, AND THE COUNTERS FOUND A 100% MISS RATE PLUS A SECOND SCAN I HAD
  NOT COUNTED (2026-09-16, PI-VALIDATED).** With `unres` fixed, the batch-209 ranking is `lookT`
  682.398ms, `imap` 605.936ms, then **`statT` 463.914ms** -- one of `patch`'s three top-level splits and the
  only one nothing had ever looked inside. Its loop is short enough to read: for every static reloc site,
  `globalStaticByRef` walks ALL `sgCount` registry entries with TWO `utf8EqAt` an entry, and `patchRelocs`
  calls it with `rsStart` 0 at every batch end. Fourteenth instance of this file's most common defect.

  | demo suite, batch 64 (cumulative) | before | after | |
  |---|---|---|---|
  | `ps:steps` -- sgTab entries compared | **37k** | **0k** | the probe reaches an EMPTY bucket |
  | `ps:n` / `miss` | 136 / **136** | 136 / 136 | identical -- same sites, same answers |
  | `rb:n` / `steps` (regBySigU) | 1224 / 117k | identical | untouched, and now the whole residue |
  | `memo/res/unres` | 1379/2446/2193 | **identical** | |

  - **COUNTED BEFORE BEING BELIEVED, AND THE COUNTERS SAID TWO THINGS READING HAD NOT.** The reading was
    unambiguous and would have shipped an index on its own; the unres arm one increment ago ranked the same
    way by reading and the counters put a different half 5.6x ahead. Here they said:
    - **EVERY SITE MISSES -- 136 of 136**, each walking all 280 entries to answer "not here". So the depth IS
      the cost. And **the unres arm's memo does NOT transfer**: a site that cannot resolve today MUST be
      re-asked, because a class loaded later has to resolve -- the code's own comment already said so. The
      remedy is to make the NEGATIVE cheap, not to cache it, which is the shape the imap refill took
      (`chain=0k`, the probe reaching an empty bucket).
    - **A SECOND GROWING SCAN SITS IN THE SAME LOOP AND MY FIRST INSTRUMENT WAS BLIND TO IT.** Every miss
      falls into `reportZeroCellBind`, whose first act is `regBySigU` -- a linear walk of all `clCount`
      classes, run to decide whether the miss is worth PRINTING. At 100% miss that is a second growing table
      walked per site per batch: `rb:n=1224 steps=117k cl=189`, of which this loop's 136 misses are ~26k.
      **That is exactly the mistake the unres arm made, caught this time before any fix shipped** -- by
      adding the second counter rather than trusting that the first one covered the loop.
  - **AND THE ACCOUNT CLOSES, which is the check that says nothing else is hiding in there.** 37k sgTab steps
    (two compares each) plus ~26k regBySigU steps (one each) is ~100k Utf8 compares against a measured
    46.9ms -- **~0.47us a compare, the same per-compare cost the unres arm's `deny=116k` showed.** `statT`
    is these two scans and essentially nothing else.
  - **ONE INDEX, TWO CALL SITES.** `globalStaticAddr` -- the COMPILE-TIME twin -- carried its own copy of the
    identical scan over the identical key, so both now share `sgCellOf`. Same shape as the phase-B publish's
    "one call site's fix, two timers".
  - **SOUND BY CONSTRUCTION, not by a claim about when work may be skipped:** `sgTab[sgCount]` is written and
    `sgCount` incremented immediately after, at the ONE site that appends (`registerStaticFields`) -- no
    entry is ever re-pointed, so an append-only index cannot go stale. **THE LOWEST MATCHING INDEX STILL
    WINS**: head-insertion makes the chain descending, so it is searched for the MINIMUM rather than stopped
    at the first hit (the care the Type index and `findPdByName`'s index both needed). Two entries share a
    class+name only if a class reached `registerStaticFields` twice, and silently preferring the later cell
    would bind a read to different memory than the `<clinit>` wrote through. **The watermark resets BESIDE
    the table** in `resetLoader` as well as being checked in the builder -- `sgCount` returning to the same
    value after a reset would slip past the "went backwards" check alone.
  - **THE BUCKET ARRAY IS 8192, NOT a power of two above MAXREG, and that is measured rather than tidy:** it
    is refilled with -1 on every rebuild and `resetLoader` triggers one per LAUNCH, so 65536 would be 30 x
    65536 stores across the suite's 30 programs to hold chains that are already empty. `psSteps` reports the
    truth if that sizing is ever wrong.
  - **NO QEMU MILLISECOND FIGURE IS QUOTED, AND FOUR RUNS SAY WHY.** `statT` read **46.945 / 29.173 / 21.389
    / 12.948ms** across four boots of only TWO distinct binaries -- the last two are the SAME code, 1.65x
    apart. Every untouched timer moved with them (`imap` 1.69x, `alloc` 1.52x, `unresT` 2.94x, `lookT`
    0.61x), so the 0.62x `statT` "improvement" sits inside a band the change cannot have caused. Machine load
    from my own concurrent builds -- the confound this file records three times, here LARGER than the effect
    being measured. **`ps:steps` 37k -> 0k is the load-independent reading and the only one claimed.**
  - **NOT PREDICTING A FIGURE**, for the reason already paid for twice: the suite understated the seeds by
    17x and got the unres ratio right by accident. What makes this one especially unguessable is that the
    suite's miss rate is **100%** -- the launcher's split between resolving and unresolved sites is unknown,
    and an index is the one fix whose value does not depend on it.
  - **IDENTITY:** `ps:n=136 miss=136` unchanged -- the index found exactly what the scan found, site for
    site and answer for answer -- plus `rb:n=1224 steps=117k`, `memo=1379 res=2446 unres=2193`,
    `rf:skip=1596 visit=1156 clos=1156 holeEnd=1057`, `n:imap=52 synth=18 clinits=25`. 32 programs, TWENTY-ONE
    failure markers zero, `finish HML` 20/20/20, inversion 79ms, `smp sched: 4 of 4`, `sum20=210`, `YNW`/`RP`,
    `churnMB=625 live=32 intact=32`. Host tests unchanged: A64 105, object-model 22, class-reader 171,
    refmap 14, `compiler: 37 checks`, crypto 17, zip 91, `overlay-check 0 new`. The only output diffs are the
    SMP task interleaving and the philosophers' ordering, which this file already records as differing
    run-to-run on the SAME binary.
  - **A COMMENT-ONLY EDIT CHANGED THE IMAGE, and it was re-run rather than waved through.** Same size, 7744
    differing bytes -- LineNumberTable entries shifted by the comment lines added above them (`6216 -> 6223`,
    +7, is visible in the diff). Benign, and the reason to check rather than assume is that some of those
    bytes sit inside the code region: the committed binary was booted again and reproduces every counter and
    marker above exactly.
  - **WHAT IS NEXT, and it is already counted: `regBySigU`.** It is now the entire residue of this loop, and
    117k steps over all nine of its callers -- a linear walk of `clCount` with no index anywhere, where
    `classRegByName`/`classRegByNameAt`/`classRegByNameBytes` are three more copies of the same scan. The
    counters are in the tree; the decision wants a boot, not a reading.
  - **PI-VALIDATED: `statT` 463.914ms -> 189.764ms (2.45x, -274ms), AND THE 100% MISS RATE HOLDS AT
    LAUNCHER SCALE.**

    | launcher, batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `statT` (cumulative) | 463.914ms | **189.764ms** | **2.45x, -274ms** |
    | `ps:steps` | ~1,740k (580 x tab 3051) | **3k** | ~580x |
    | `ps:n` / `miss` | -- | **580 / 580** | 100% miss, exactly as the suite predicted |
    | `tab` / `cl` | -- | 3051 / 1782 | the two growing tables |
    | `callT` / `lookT` / `unresT` / `tailT` | 929.908 / 682.398 / 138.367 / 101.076 | 931.371 / 683.009 / 138.691 / 101.856 | untouched, to 0.1% |
    | `imap` | 605.936ms | 605.914ms | untouched |

  - **THIS IS THE FIRST TIME THIS ARC HAS HAD A CLEAN TIMING CONTROL, and it is worth saying why.** Every
    untouched cumulative timer reads within **0.1%** of the previous boot -- `callT` +0.16%, `lookT` +0.09%,
    `unresT` +0.23%, `imap` -0.004% -- so the machine is pinned and the 2.45x on the one timer targeted is
    real. Contrast the QEMU arms for the same change, where `statT` read 46.9 / 29.2 / 21.4 / 12.9ms across
    only TWO binaries. **The Pi is the honest harness; four QEMU boots could not have told this from noise.**
  - **THE SUITE'S 100% MISS RATE WAS NOT AN ARTIFACT OF ITS SIZE: `ps:n=580 miss=580` at 1782 blobs.** Every
    static reloc site that reaches this loop resolves to nothing, at both scales. That is what made the index
    the right fix rather than a memo -- the negative answer must be re-derivable, so it had to be made cheap.
  - **AND THE RESIDUE IS FULLY ACCOUNTED FOR, WHICH NAMES THE NEXT INCREMENT EXACTLY.** 190ms across 580
    sites is 327us a site, which is absurd for a hash probe -- so the remaining `statT` is NOT the loop I
    fixed. It is the OTHER scan, the one the first cut of the instrument was blind to: every miss calls
    `reportZeroCellBind`, whose `regBySigU` walks all `clCount` classes, so 580 x 1782 = **~1,034k utf8EqAt
    calls**. At the ~0.18us a call this boot's own `deny=508k`/`unresT=138ms` implies, that is **~186ms
    against a measured 189.764ms.** `statT` is now essentially ALL diagnostic gate: a linear scan of the
    class registry, run per site, to decide whether a miss is worth PRINTING.
  - **IDENTITY EXACT ON EVERY GATE:** `memo=128548 res=82350 unres=27419`, `rf:skip=114380 visit=44987
    clos=44987 holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `sd:n=2802 steps=2590k` still frozen,
    `hcls=0k`, `pb:probed=1 of=1782`, `pc:n=1243`. `rb:n=5316` unchanged in shape. **And the marker that
    matters for a STATIC index is an absence: no `STATIC BOUND TO THE ZERO CELL` and no new `UNRESOLVED
    STATIC`** -- a wrong cell would not crash, it would read a different field's memory for ever, and
    `ps:n`/`miss` matching site-for-site is the numeric form of that check. `[3 containers successful]` /
    `[2 tests successful]` / `[0 tests failed]` / exit 0, with only the two known denylisted lines
    (`java/nio/file/OpenOption`, `Files.newBufferedWriter`), both correctly labelled DENYLISTED.
  - **THE BOOT WENT UP 550ms AND THAT IS STATED RATHER THAN BURIED:** 97,914 -> 98,464ms against a 274ms
    cumulative cut, with the tests within 20ms of the previous run. Six launcher boots now read 99,433 /
    99,299 / 99,151 / 97,817 / 97,914 / 98,464 -- the same ~1.6s spread this file has recorded three times.
    The cumulative counters are the trustworthy reading, and here they are unusually trustworthy: every
    untouched one is within 0.1%.
  - **THE ARC: 161,556 -> 98,464ms.**
  - **WHAT IS NEXT, AND IT IS ALREADY COUNTED AND NOW QUANTIFIED: `regBySigU`.** `rb:n=5316 steps=3926k` over
    all nine callers, of which ~1,034k is this one gate. It is a linear walk of `clCount` with a utf8EqAt an
    entry and **no index anywhere**, with three more copies of the same scan beside it
    (`classRegByName`/`classRegByNameAt`/`classRegByNameBytes`). The ranking at batch 209 is now `callT`
    931.371 (of which `lookT` 683.009), `imap` 605.914, **`synth` 191.697 -- which has quietly overtaken
    `statT` and has also never been split** -- then `statT` 189.764 and `alloc` 106.513.


- **THE UNRESOLVED ARM RE-DECIDED TWO IMMUTABLE FACTS PER SITE PER BATCH -- `unresT` 3.67x, AND THE COUNTERS
  SAY THE OLD NOTE BLAMED THE WRONG HALF (2026-09-16, PI-VALIDATED).** With `seeds` gone, `callT` is the largest item
  in a batch (1,300.917ms) and splits exactly: `lookT` 682.775 + **`unresT` 507.742** + `tailT` 101.653 =
  1,292ms of 1,301ms. `unresT` is the arm that runs when a call site's callee cannot be resolved.

  | demo suite, batch 64 (cumulative) | control | fix | |
  |---|---|---|---|
  | `un:deny` -- `utf8HasPrefix` calls | **220k** | 116k | 1.9x |
  | `un:stub` -- `linkStubFor` scan entries | **39k** | **2k** | 19x |
  | `un:memo` / `full` | 0k / 2k | 1k / 0k | the arm skipped outright |
  | `memo/res/unres` | 1379/2446/2193 | **identical** | |

  - **`patchRelocs` REVISITS EVERY RELOC SITE FROM 0 AT EACH BATCH END, deliberately** -- a callee that loads
    later must resolve for real. But a site that is STILL unresolved re-derives two things that cannot
    change: whether its callee's class is DENYLISTED (a pure function of the name bytes, decided by SIXTY
    non-inlined `utf8HasPrefix` calls -- this VM's baseline compiler does not inline), and which link stub
    serves that callee (`linkStubFor` already dedups by callee identity, so it can only ever return the same
    stub). `rcStub[i]` records the answer; the arm becomes one array read.
  - **THE OLD NOTE ASKED FOR THIS AND NAMED THE CONDITION, WHICH IS WHY IT GOT DONE.** `linkStubFor` carried:
    "indexed during the patch arc on the theory that this was hot; MEASURED, the index bought NOTHING ...
    Revisit only if a measurement puts time here." A measurement does now -- 39% of `callT`. **A comment that
    records what would justify revisiting is worth more than one that records a conclusion.**
  - **AND THE COUNTERS EXPLAIN WHY THAT OLD INDEX BOUGHT NOTHING: the scan was never the cost.** `deny=220k`
    against `stub=39k` -- the prefix chain is **5.6x** the scan it was indexing. The earlier arc reached the
    right decision (do not index it) from the wrong model, and nothing recorded at the time could tell the
    two apart. This is the fourth time in this arc that counters have reordered two candidates that reading
    had ranked.
  - **A COUNTER USED AS AN INVARIANT MUST KEEP MEANING THE SAME THING, and I broke that and caught it.**
    First cut incremented only the new `pcUnresMemo` on the memo path, so `unres` read **463 against 2193**
    for identical work -- which looks exactly like a closure that changed, and `memo/res/unres` is the triple
    every entry in this file gates identity on. `pcUnres` counts both paths now and the triple matches the
    control exactly. **A split added to an invariant must leave the invariant alone.**
  - **IT ALSO STOPS THE TRAPWIRE TABLE GROWING WITHOUT BOUND:** that arm re-recorded the same site on every
    batch, and the table filling is precisely what makes a fired trap report an EMPTY callee -- the
    misattribution this file records as having cost several sessions.
  - **THE QEMU MILLISECONDS ARE WORTHLESS HERE AND THE RUNS PROVE IT, so only the counters are quoted above.**
    Two boots of functionally identical code (the fix, before and after the counter correction, which cannot
    affect timing) read `unresT` **49.222ms and 21.889ms** -- a factor of 2.2 apart, from my own concurrent
    builds. The control read 74.657ms. This file already records that confound three times; here it is
    larger than the effect being measured.
  - **IDENTITY:** `memo=1379 res=2446 unres=2193`, `rf:skip=1596 visit=1156`, 32 programs, and nineteen
    failure markers zero including `LINK STUB TABLE FULL` -- the one a broken memo would trip by re-minting.
    The only output diffs are the philosophers' interleaving, the inversion latency (61ms vs 84ms; both
    report the correct ORDER, which is what that arm asserts), and the known SMP/arena variance. Host tests
    unchanged incl. `compiler: 37 checks` and `overlay-check 0 new`.
  - **NO FIGURE PREDICTED.** The suite resets `rcStub` per PROGRAM (`launchMain` runs 30 of them), so a site
    settles and is immediately thrown away; the launcher is ONE launch over 209 batches, which is where a
    per-site memo can actually pay. Same asymmetry as the seeds, and the seeds understated by 17x.
  - **PI-VALIDATED: `unresT` 507.742ms -> 138.367ms (3.67x), AND THE GROWTH TERM IS WHAT WENT.**

    | launcher, batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `unresT` (cumulative) | 507.742ms | **138.367ms** | **3.67x, -369ms** |
    | `callT` (cumulative) | 1,300.917ms | **929.908ms** | -371ms |
    | `unresT` GROWTH per batch (b43->209) | **2.4ms** | **0.32ms** | **7.5x** |
    | `un:memo` / `full` | -- | 23k / 4k | 85% answered from the memo |
    | `lookT` / `tailT` | 682.775 / 101.653 | 682.398 / 101.076 | untouched, as they must be |

    `callT` still splits exactly (682.4 + 138.4 + 101.1 = 921.8 of 929.9), and the -371ms of `callT` is the
    -369ms of `unresT` and nothing else. **The per-batch GROWTH is the reading that matters**: the arm was
    adding 2.4ms a batch and now adds 0.32ms, which is what removing a per-item re-derivation over a table
    that grows all boot looks like.
  - **IDENTITY EXACT, AND THE COUNTER I BROKE IS PART OF THE PROOF:** `memo=128548 res=82350 unres=27419` --
    byte-identical to the previous boot, with `unres` now summing the memo path and the full arm. Also
    `rf:skip=114380 visit=44987 clos=44987 holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `sd:n=2802
    steps=2590k` still frozen, `hcls=0k`, `seeds=9.300ms`, `imap=605.936ms` (604.547 before -- untouched).
    `[3 containers successful]` / `[2 tests successful]` / exit 0, and **no `LINK STUB TABLE FULL`** -- the
    marker a memo that re-minted instead of reusing would trip.
  - **THE WHOLE BOOT WENT UP 97ms AND THAT IS STATED RATHER THAN BURIED:** 97,817 -> 97,914ms against a
    369ms cumulative cut, with the tests 17ms slower. The five most recent launcher boots read 99,433 /
    99,299 / 99,151 / 97,817 / 97,914 -- a ~1.6s spread on binaries whose load paths differ by far less than
    that. **The cumulative counter is the trustworthy reading and a whole-boot delta of a few hundred ms is
    not resolvable in this harness**, which this file has now had to say three times.
  - **THE QEMU PREDICTION WAS THE WRONG SHAPE AND THE PI CORRECTED IT.** The suite measured `un:deny`
    220k -> 116k and `un:stub` 39k -> 2k and I declined to convert either into milliseconds, because two
    boots of identical code had read `unresT` 49.222 and 21.889ms. That caution was right: on the launcher
    the effect is 3.67x where the suite's own `unresT` ratio suggested ~3.4x by accident -- the suite throws
    `rcStub` away every program, so it was measuring first-settle cost, not the memo.
  - **THE ARC: 161,556 -> 97,914ms.**
  - **WHAT IS NEXT, and `unres` is off the list.** At batch 209: **`lookT` 682.398ms** is now the largest
    single item, then `imap` 605.936ms, then **`statT` 463.914ms -- which has never been split at all**.
    `lookT` is `globalBufByRef`, already indexed twice, and its per-batch re-resolve is DELIBERATE: memoising
    the super-chain and stub tiers would answer with an ancestor's body after a subclass registered its own
    override, which is a silent wrong-method dispatch. So the next honest target is `statT`, for the reason
    `seeds` turned out to be worth 148x: nobody has looked inside it.



- **THE SEED BLOCK RE-RAN ON EVERY BATCH -- `seeds` 148x ON HARDWARE, AND A NEGATIVE CONTROL KILLED HALF MY
  READING OF WHY (2026-09-16, PI-VALIDATED).** The `imap` boot's own log named the next target: at
  batch 209 **`seeds` was 1,369.531ms, the largest item in the clinit phase** -- more than double `imap`'s
  603ms -- and it had never been split. It is six functions run unconditionally at the end of EVERY batch.

  | demo suite, batch 64 (cumulative) | before | after | |
  |---|---|---|---|
  | `seeds` | **106.296ms** | **12.544ms** | **8.5x** |
  | `sd:n` (staticSlotOf calls) | 1,055 | 276 | 3.8x |
  | `sd:steps` (registry entries compared) | 123k | 43k | 2.9x |

  - **EVERY SEED IS IDEMPOTENT AND ITS RESULT IMMUTABLE** -- a boxed Integer for -128..127, a field-free
    access object, a bare monitor, a primitive mirror -- so re-running cannot change an answer. What it cost
    is THREE growing scans per batch, all of them this file's most common defect (thirteenth instance) and
    all inside a function that had never been looked at: **fifteen `staticSlotOf` calls** (a linear walk of
    all `sgCount` static-registry entries, two Utf8 compares an entry), **five `classIndexByName`** (a walk
    of all `clCount`, and clCount is 1782 at batch 209), and **nine `classMirror`** (a walk of `mirN`).
    Plus 512 allocations from the two box caches.
  - **THE COUNTERS ONLY EXPLAIN A THIRD OF IT, AND THAT IS SAID RATHER THAN GLOSSED.** `sd:steps` falls
    2.9x while `seeds` falls 8.5x, so most of the saving is the two scans the counters do NOT cover
    (`classIndexByName` and `classMirror`) and the allocations. The instrument was added for `staticSlotOf`
    and it is honest about covering only that.
  - **A FLAG IS SET ONLY ON SUCCESS, because a seed legitimately cannot run yet.** `seedIntegerCache`
    returns early while `java/lang/Integer` is unregistered, and must be retried. **And the wrapper TYPEs
    latch PER WRAPPER rather than all-or-nine:** an all-or-nothing flag was written first and MEASURED not
    to latch at all on a small closure (`sd:n` still +15 a batch), because one wrapper a closure never
    carries holds the flag down for the whole launch and preserves the entire cost.
  - **THE FLAGS RESET IN `resetLoader` BESIDE `sgCount = 0`** -- the table they guard. A flag outliving the
    static registry would skip the seed for a whole launch and leave `System.out` / the Integer cache /
    `int.class` null, silently. Same rule as every other watermark here, and the third time this file has
    had to state it.
  - **I ALSO CLAIMED THIS BROKE SMALL-INTEGER BOX IDENTITY, AND THE NEGATIVE CONTROL REFUTED IT.** Four
    seeds allocate, so re-running looked like it must REPLACE the object the static already holds, making
    `Integer.valueOf(5) == Integer.valueOf(5)` false across a batch boundary -- exactly the silent-wrong-
    answer shape this file exists to remove. I wrote `demo/BoxIdentityDemo` to pin it (a demand-load forced
    BETWEEN the two calls, because calling `valueOf` twice in a row passes either way) and **with the guards
    REMOVED it still reports `int=1 long=1 TYPE=1`.** So the re-seed does not in fact replace the cache the
    running program reads. **Why it does not is NOT established.** The demo was DELETED rather than kept:
    an arm that passes in both states is not a control, and left in the suite it would read as evidence.
  - **Running the control is the whole reason that claim did not ship**, and it cost one QEMU boot against a
    postmortem that already records ten silent wrong answers. The reading was plausible, the mechanism was
    plausible, and it was wrong.
  - **IDENTITY EXACT:** `rf:skip=1596 visit=1194`, `memo=1369 res=2346 unres=2099` -- byte-identical between
    the arms, so the change moved only what it targeted. 33 programs, `finish HML` 20/20/20, inversion 61ms,
    `smp sched: 4 of 4`, `churnMB=625 live=32 intact=32`, and NINETEEN failure markers zero including
    `SYSTEM PROPERTIES NOT SEEDED` -- the one a broken seed guard would trip. Host tests unchanged: A64 105,
    object-model 22, class-reader 171, refmap 14, `compiler: 37 checks`, crypto 17, zip 91,
    `overlay-check 0 new`.
  - **`imap` READ 41.8ms IN ONE ARM AND 45.7ms IN THE OTHER AND THAT IS MACHINE LOAD, not a regression** --
    an image build ran concurrently with one of the two QEMU boots, the confound this file has recorded
    three times. Its COUNTERS are identical between the arms, which is the load-independent reading.
  - **NO FIGURE IS PREDICTED FOR THE PI.** The suite peaks at ~190 blobs against the launcher's 1782, and
    two of the three scans removed are O(clCount) -- so the suite is the small end of this by construction.
    What the boot answers: whether `seeds` 1,369ms follows the 8.5x, and whether the twelve-batch burst
    below survives.
  - **PI-VALIDATED, AND IT IS 148x -- `seeds` 1,369.531ms -> 9.262ms, WITH THE BURST GONE.**

    | launcher, batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `seeds` (cumulative) | 1,369.531ms | **9.262ms** | **148x, -1,360ms** |
    | `sd:n` / `sd:steps` | -- | **2,802 / 2,590k, FROZEN from batch 42** | |
    | `clinit` per batch, b174-185 | ~77ms | **~6.2ms** | |
    | whole boot | 99,151ms | **97,817ms** | **-1,334ms** |

    **THE FLAGS LATCH COMPLETELY AND THE COUNTER PROVES IT: `sd:n=2802 steps=2590k` at batch 42 and the
    IDENTICAL pair at batch 209.** Not one static-registry walk in 167 batches. The suite's 8.5x understated
    this by 17x, exactly as predicted and for the stated reason -- two of the three removed scans are
    O(`clCount`), and the suite peaks at ~190 blobs against the launcher's 1782.
  - **THE 850ms BURST WAS THE SEED BLOCK, and that is what the boot was for.** Batches 174-185 cost ~70.8ms
    each before; they now add **27 MICROSECONDS between them** (`seeds` 9.179 -> 9.206ms over the twelve),
    with `clinit` per batch 77ms -> 6.2ms across the same window. A growing scan cannot produce a burst that
    stops, and this was not one: it was the seeds hitting a window where `clCount`, `sgCount` and `mirN`
    together made fifteen-plus walks briefly enormous.
  - **THE SECOND SIGNAL DID NOT SURVIVE, AND I RECORD THAT AGAINST MYSELF.** I read `exc`'s +1.1ms/batch over
    the SAME window as the same event -- "two independent timers switching on and off at the same batch
    boundaries is one event". It is not: `exc` still grows ~1ms a batch, its own step just moved later
    (frozen at 28.951ms from b186 before, still climbing to 30.187ms at b209 now). Two timers coinciding
    once is a coincidence until something makes one of them move; the seeds half was right and the joint
    reading was not. `exc` is 30ms, so it does not matter -- what matters is that I stated it as one event.
  - **THE BOOT MOVED ALMOST EXACTLY WHAT THE COUNTER SAYS, which is unusual here and worth noting:** -1,334ms
    of boot against a -1,360ms cumulative cut. The `imap` increment gave 763ms cumulative and 282ms of boot;
    this one lands on the nose. And the tests were 1,008ms SLOWER on this boot (66,354 vs 65,346), so net of
    them the load path gave back ~2.3s -- stated as arithmetic on a noisy quantity, not as the headline.
  - **IDENTITY EXACT AT 1782 BLOBS:** `rf:skip=114380 visit=44987 clos=44987 holeEnd=44370`, `memo=128548
    res=82350 unres=27419`, `n:imap=855 synth=2276 clinits=388`, `hcls=0k`, and `imap=604.547ms` against the
    previous boot's 603.215ms -- untouched, as it must be. `[3 containers successful]` / `[2 tests
    successful]` / `[0 tests failed]` / exit 0, with **no `SYSTEM PROPERTIES NOT SEEDED`** -- the marker a
    flag that latched in the wrong place would trip, and the one real risk in this change.
  - **THE ARC: 161,556 -> 97,817ms -- 63.7 SECONDS, 39% OFF A LAUNCHER BOOT.**
  - **WHAT IS NEXT, AND `seeds` HAS FALLEN OUT OF THE LIST ENTIRELY.** At batch 209: **`callT` 1,300.917ms**
    is now the largest item by a factor of two, and it SPLITS EXACTLY -- `lookT` 682.775 + `unresT` 507.742 +
    `tailT` 101.653 = 1,292ms of 1,301ms. Then `imap` 604.547ms, `statT` 459.351ms, `synth` 187.255ms.
    **`unresT` is the interesting one: 508ms, 39% of `callT`, and this file records it as `linkStubFor`,
    "indexed once, measured cold, and REVERTED" on the grounds its path was not hot.** It was 541ms of an
    11s `callT` then; it is 39% of a 1.3s one now. A function that measured cold is a statement about that
    closure, not about the code -- for the third time.
  - **STILL UNEXPLAINED, AND IT IS THE REASON THIS TARGET WAS PICKED: `seeds` SPENT 850ms IN TWELVE BATCHES.**
    On the launcher it grew ~1.6ms a batch, then batches 174-185 cost **~70.8ms EACH**, then it went back to
    ~1.6ms. `exc` inside `mark` did the same thing in the same window (+1.1ms a batch, frozen at 28.9ms from
    batch 186). Two independent timers switching on and off at the same batch boundaries is one event, not a
    scan that grew -- and a growing scan cannot produce a burst that STOPS. If the guards remove it, it was
    the seeds; if it survives, it is something else in that window and the guards did not touch it.

- **THE IMAP REFILL WAS RE-COMPUTING AN IMMUTABLE HASH, AND THE FOUR COUNTERS ALREADY THERE COULD NOT SEE IT
  (2026-09-16, PI-VALIDATED -- `imap` 2.26x, -763ms).** `imap` was 1,366ms cumulative and the grows-with-load shape this file
  has named more than any other. The four `rfs:` counters tally SLOT READS -- and 3.3M of those over a
  launcher boot cannot account for 1,366ms at ~68 cycles a read on a 166MHz core. **So the instrument was the
  first thing to fix, not the code.**

  | demo suite, batch 64 (cumulative) | before | after | |
  |---|---|---|---|
  | `hcls` -- CLASS-name bytes FNV-folded | **916k** | **0k** | gone |
  | `hnam` -- METHOD-name bytes folded | 415k | 415k | irreducible (see below) |
  | `hole` + `fill` -- itable slot reads | 23k + 38k | **42k** | -31% |
  | `dbs` / `probe` / `chain` | 12k / 46k / 0k | identical | |
  | **total counted refill steps** | **1,420k** | **457k** | **3.1x** |

  - **THE COUNTERS RANKED IT IN ONE QEMU RUN, AND AGAINST THE FOUR THAT WERE ALREADY THERE.** Adding
    `dbs`/`probe`/`hash`/`chain` said the refill's cost is not scanning at all: `defaultBySig` probes the
    method registry once per CLOSURE INTERFACE per still-0 slot, and each probe FNV-folded the interface's
    class name AND the method name, byte by byte, to compute a bucket index. **`chain` read 0k** -- the
    bucket those probes reach is EMPTY, which is exactly right for a slot nothing in the closure declares a
    body for. **Computing the key WAS the search.** Splitting `hash` into its two halves then said which half
    to attack: 916k class-name bytes against 415k method-name bytes.
  - **FNV IS A FOLD, SO THE CLASS HALF IS A CACHEABLE PREFIX -- and caching it changes NO key.**
    `utf8HashFrom(utf8Hash(b1,o1), b2, o2)` is bit-identical to `utf8Hash2(b1,o1,b2,o2)`, so the probe lands
    in the same bucket and the other THREE sites that probe `rgBucket` are untouched. `RVMClass.nameHash` is
    folded once at registration and is immutable by construction: `base`/`nameOff` are written there and
    never re-pointed. **Changing the index's key function instead would have been the larger fix and the
    larger risk** -- four sites, and one left behind silently finds nothing.
  - **AND THE THREE SCANS BECAME ONE.** The refill pre-scanned with `itableHasHole` to decide whether to
    compute the closure, scanned again to refill, then scanned a THIRD time to ask whether a hole survived.
    **The counters said the pre-scan bought nothing**: `clos=1060` against `visit=1156`, i.e. 92% of visited
    imaps had a hole somewhere and the closure was computed regardless. `refillItable` already tests every
    slot for 0, so it reports what it saw; `itableHasHole` is gone. `clos` now reads exactly `visit` by
    construction -- an EXPECTED counter change, not an identity break.
  - **IDENTITY IS EXACT ON EVERY COUNTER THAT SAYS WHAT WAS RESOLVED:** `rf:skip=1596 visit=1156
    holeEnd=1057`, `memo=1379 res=2446 unres=2193`, `rounds=2 pend=12 reach=1`, `n:imap=52 synth=18
    clinits=25`, and `dbs`/`probe`/`chain` unchanged to the step -- so the same slots were searched, through
    the same tiers, to the same answers. All 32 programs' output byte-identical; twenty failure markers zero.
    Host tests unchanged: A64 105, object-model 22, class-reader 171, refmap **14**, `compiler: 37 checks`,
    crypto 17, zip 91, `overlay-check 0 new`.
  - **THE REFMAP TEST FAILED, WHICH IS IT DOING ITS JOB.** `nameHash` sits beside `nameOff` and pushed
    `tib`/`type`/`statics` down one slot each; the collector traces an RVMClass THROUGH that map, so a map
    left describing the old layout would follow an int as a pointer and skip a real one -- silent heap
    corruption, found by nothing until it swept a live TIB. **The new expected value was DERIVED from the
    field list, not copied from what the tool printed**, and a fourteenth check pins the new int slot.
  - **THE SMP AND ARENA DIFFS WERE SETTLED BY A CONTROL RATHER THAN BY CITING THIS FILE.** Every one of the
    74 diff lines is `smp jobs`/`jobs/core`/`per-core tasks`/`steps/core`, `lastReclaimed`, or the code
    arena's `cur`/`peak`. The two BASELINE runs differ from EACH OTHER more than baseline differs from the
    change (`c0=6 c1=1 c2=15 c3=2` vs `c0=21 c1=1 c2=1 c3=1`), `smp sched: 4 of 4` throughout. The arena is
    352 bytes SMALLER and that is explained rather than tolerated: one method fewer (`itableHasHole`).
  - **PI-VALIDATED, AND THE STEP MODEL WAS RIGHT BUT OVERSTATED.**

    | launcher, batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `imap` (cumulative) | 1,365.986ms | **603.215ms** | **2.26x, -763ms** |
    | `hcls` (class-name bytes folded) | ~25,900k (by the suite's ratio) | **0k** | gone |
    | `hnam` (method-name bytes) | 11,704k | 11,704k | the irreducible half |
    | whole boot | 99,433ms | 99,151ms | |

    **Counted steps predicted ~2.8x and silicon gave 2.26x**, so there is per-call overhead the byte counts
    cannot see -- 273k `defaultBySig` calls and 1,188k probe iterations carry loop cost of their own. Worth
    knowing before trusting a step ratio again. **IDENTITY EXACT:** `rf:skip=114380 visit=44987
    holeEnd=44370`, `memo=128548 res=82350 unres=27419`, `n:imap=855 synth=2276 clinits=388`, `rounds=2
    pend=4 reach=8` -- every one matching, with `clos` 44390 -> 44987 (= `visit`) the one expected change.
    `[3 containers successful]` / `[2 tests successful]` / exit 0.
  - **THE WHOLE BOOT MOVED 282ms AGAINST A 763ms CUMULATIVE CUT, AND THAT IS STATED RATHER THAN ROUNDED
    AWAY** -- the same gap this file already recorded for the Type index (1.43s cumulative, 518ms on the
    boot). The cumulative counter is the trustworthy reading; a few hundred ms of whole-boot delta is not
    resolvable in this harness.
  - **NO FIGURE WAS PREDICTED, deliberately.** The step count is the load-independent reading; how many
    milliseconds a step is worth on a 166MHz core is what the boot had to say, and I under-predicted the
    phase-B publish by 4x in this same arc by extrapolating instead.
  - **THE DECISION IT GATED, ANSWERED: NO.** `hnam` is 78% of the 15,096k steps left, but at the measured
    ~40ns a step that is ~400ms -- 0.4% of a 99-second boot -- against re-keying `rgBucket` on
    `combine(hash(class), hash(name))` at all four probe sites, two of them on the `patch` hot path where a
    mistake is a silent wrong dispatch. **Not worth it, and the same log says why: `seeds` is 1,369ms.**

- **BATCH 188 IS A GARBAGE COLLECTION. `struct` WAS NEVER A DEFECT, AND FOUR BOOTS SAY SO (2026-09-15,
  PI-VALIDATED).** The `gc=` counter settled it on the first try:

  | batch | 187 | **188** | 189 |
  |---|---|---|---|
  | `gc` (collections so far) | 9 | **10** | 10 |
  | `fetch` | 0us | **565.168ms** | 0us |

  **Only TWO collections increment anywhere in the captured window** (at batch 88 and batch 188), and one of
  them lands on the single batch that is 10x slower than any of its ~170 neighbours. `JarFs.remember`'s
  `Heap.allocData` was simply the allocation that crossed the threshold, and the fetch timer was holding the
  stopwatch when it did.

  - **IT ACCOUNTS FOR EVERY OBSERVATION, which is what a real answer has to do.** Deterministic
    batch-for-batch -- 188 reproduced to 0.13% across FOUR boots, because the same allocation sequence
    reaches the same threshold at the same point. Immune to a 16.2x cheaper inflater, because a collection
    costs what it costs. Six lookups and 3 KB inflated, because the allocation that tripped it was tiny.
  - **SO THE CHASE IS OVER AND THE HONEST ACCOUNTING IS: four boots on 0.57% of a boot, and the answer is
    "not a defect".** That is still a result -- it stops this being re-found and re-chased, which is exactly
    what the previous entry's "unexplained one-off" invited -- but it is a poor return, and the reason it ran
    long is worth keeping: **each wrong reading was cheap to test, so none of them felt like a decision.**
    The `fetch` timer, the `jf:` counters and `gc=` were one boot each and each killed a hypothesis; what was
    missing was asking, before boot two, whether the TARGET was worth four.
  - **WHAT THE ARC ACTUALLY BOUGHT, and it is not nothing:** the inflater is 16.2x fewer `bits()` calls and
    15-18% off every inflate-heavy `pull`; `struct`/`pull` no longer absorb the jar fetch; `JarFs.entry`'s and
    `ZipDir.find`'s linear scans are MEASURED (949k and 963k steps over a whole boot -- not worth indexing,
    which is the fix I would otherwise have shipped on reading alone); and the `JAR NAME CACHE FULL` cliff is
    now reported rather than silent, though it does not fire today.
  - **THE ONE NUMBER WORTH CARRYING FORWARD: a collection costs ~565ms at 1750 blobs.** That is larger than
    anything else this arc cut, and it belongs to the GC arc rather than the load path. Ten collections over
    the boot, most of them during test execution where no timer sees them.
  - **IDENTITY EXACT AT 1782 BLOBS for a fourth consecutive boot:** `rf:skip=114380 visit=44987 clos=44390
    holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `memo=128548 res=82350 unres=27419`.
    `[3 containers successful]` / `[2 tests successful]` / exit 0. Whole boot 99,433ms; the three
    instrument-only boots sit at 98,691 / 99,299 / 99,433 with the tests accounting for most of the spread.
  - **NEXT, and it is measured rather than guessed:** `imap` is **1,366ms cumulative** and still the
    grows-with-load shape this file has named more than any other -- per batch 3.4 -> 9.6ms across batches
    27-209 while blobs grew 1.29x. After that, `callT` 1,302ms and `lookT` 683ms. The `struct` pass still has
    no round watermark; it is sound to add and worth ~150us a batch, which is to say nearly nothing.

- **THE FETCH COUNTERS PRODUCED A DECISIVE NEGATIVE AND KILLED BOTH OF MY FIX CANDIDATES (2026-09-15,
  PI-VALIDATED).** Batch 188's 564ms of `fetch` is **SIX LOOKUPS**:

  | across batch 188 | delta |
  |---|---|
  | `jf:n` 2657 -> 2663 | **+6 entry() calls** |
  | `scan` 890k -> 895k | +5k cache-name comparisons |
  | `finds` 762 -> 764 | **+2 central-directory searches** |
  | `fsteps` 941k -> 945k | +4k directory comparisons |
  | `infl` 3181k -> 3184k | **+3 KB inflated** |

  ~94ms per lookup for microseconds of work. **The cost is not in `JarFs` at all.**

  - **BOTH DEFECTS I HAD NAMED AS NEXT ARE NOW MEASURED AND NEITHER MATTERS.** Over the WHOLE boot the name
    cache walks 949k steps and the central directory 963k -- real linear scans over growing tables, and
    together a small fraction of one batch. `JarFs.entry`'s scan and `ZipDir.find`'s scan are both *correct
    diagnoses of shape and wrong about cost*. **That is the fourth time in this arc that reading named a
    plausible O(n) and measurement refused it**, and it is exactly why the counters went in instead of a fix.
  - **THE CACHE-FULL CLIFF IS NOT FIRING TODAY:** no `JAR NAME CACHE FULL` anywhere in the boot. The report
    stays -- it guards a silent wrong answer whose cost is a class that never loads -- but the cap does not
    need raising yet, which is what the counter was there to say.
  - **WHAT IS LEFT INSIDE THAT BRACKET AND COUNTED BY NOTHING: an allocation.** `JarFs.remember` calls
    `Heap.allocData` twice per cached lookup, and an allocation that happens to cross the threshold pays for
    a whole collection. That would be **deterministic batch-for-batch** (the same allocation sequence reaches
    the same threshold at the same point, which is why 188 reproduced to 0.13% across three boots) and it
    would be **completely untouched by making the inflater 16.2x cheaper** -- which is precisely what the
    last two boots showed. So the batch line carries `gc=` now: one number, `Heap.gcPressure`, and a batch
    where it increments while `fetch` is huge settles it.
  - **THE SHAPE IS ALREADY VISIBLE ON QEMU:** ZipDemo against the 5-entry `app.jar` reads
    `fetch=4.732ms jf:n=41 scan=0k finds=7 fsteps=0k infl=0k gc=1` -- 41 lookups, nothing inflated, and a
    collection inside batch 1.
  - **IDENTITY EXACT AT 1782 BLOBS** across a third consecutive boot: `rf:skip=114380 visit=44987 clos=44390
    holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `memo=128548 res=82350 unres=27419`.
    `[3 containers successful]` / `[2 tests successful]` / exit 0. Whole boot 99,299ms against 98,691 --
    run-to-run variance, with the tests themselves accounting for most of it (65,757 vs 65,656ms).
  - **THE METHOD NOTE WORTH KEEPING: a counter that says "not here" is worth as much as one that says "here",
    and costs the same boot.** Two fixes were queued on reading alone -- an index for the name cache and a
    watermark for the struct pass. The first is now measured as worthless. Shipping it would have been
    unmeasured complexity in exactly the way this file already forbids for instruments.

- **THE `fetch` TIMER SETTLED `struct` IN ONE BOOT, AND REFUTED MY OWN READING OF WHAT THE FETCH IS
  (2026-09-15, PI-VALIDATED).** The split worked exactly as intended and the answer is unambiguous:
  **batch 188 reads `struct=564.695ms` with `fetch=564.363ms` -- 99.94% of it** -- and `struct` net of the
  fetch is 332us against its neighbours' ~150us. `struct` was never structural work; `pullStructural` ends in
  `registerNameFromDir`, so a class arriving as somebody's SUPERCLASS has its whole classpath-jar fetch
  charged there, and that batch's `pull=0us` is what says nothing pended it.

  - **THE INFLATER INLINING IS REAL AND IT IS NOT THE ANSWER TO 188, WHICH IS THE FINDING.** Where a fetch
    really is decoding, it paid:

    | `pull` | before | after | |
    |---|---|---|---|
    | batch 86 | 88.040ms | **74.290ms** | -15.6% |
    | batch 158 | 23.511ms | **19.160ms** | -18.5% |
    | batch 68 | 20.260ms | **16.612ms** | -18.0% |
    | batch 83 | 10.977ms | **9.447ms** | -13.9% |

    **Batch 188 moved 0.14%** (565.170 -> 564.363ms). A 16.2x cut in `bits()` calls that changes a timer by
    nothing means that timer contains no Huffman decoding. **So inflate is ~15-18% of an ordinary fetch and
    ~0% of this one**, and my reading that 188 was one large class being decompressed is REFUTED by the
    change I made to test it. Whole boot 98,694 -> 98,691ms: inside the noise, because the fetches this helps
    are a couple of hundred ms of a 98-second run and the bulk of the jar is inflated in batch 1, above the
    captured window.
  - **THE SAME SHAPE SHOWS ON A TINY JAR, which is what says it is not about size at all.** QEMU, ZipDemo
    against the 5-entry `app.jar`: `fetch=4.846ms jf:n=41 scan=0k finds=7 fsteps=0k infl=0k` -- **41 lookups,
    nothing inflated, and still ~118us per lookup.** Whatever the fetch is spending, it is per-LOOKUP and not
    per-byte.
  - **SO THE NEXT BOOT IS INSTRUMENTED RATHER THAN GUESSED AT, and that is deliberate after two wrong
    readings in a row.** `jf:n=… scan=…k finds=… fsteps=…k infl=…k` on the batch line: entry() calls, cache
    name comparisons, central-directory searches, directory entries compared, bytes actually decoded. **Plain
    counters, not timers** -- every one sits inside a per-item loop where two clock reads would be a visible
    share of what they measure, the lesson this arc has now paid for three times. Both scans are the shape
    this file names most often: `JarFs.entry` walks the whole name cache (hits AND misses, to `MAXCACHE`) and
    `ZipDir.find` walks all 2135 central-directory entries, **and `registerNameFromDir` asks TWICE** --
    `VM.dirBytes` then `VM.dirLen`, one `entry()` each.
  - **A SILENT WRONG ANSWER FOUND BY READING THAT PATH, AND IT NOW SAYS SO.** `JarFs.remember` returns -1
    when the cache is full, and its comment claimed "full: keep answering, just without memory". **It does
    not keep answering:** -1 makes `classBytes` return 0, so a class the jar DOES hold is reported ABSENT and
    never loads -- in the one place this VM can least afford it. Whether it fires today is unknown, which is
    exactly why it reports ONCE by name (`JAR NAME CACHE FULL at 2048 ... first overflow is <name>`) rather
    than being left to surface as a missing class somewhere else. **The cap is not raised and the answer path
    is not restructured here** -- that is a semantic change, and it waits for the counters to say whether the
    cap is even reached.
  - **IDENTITY IS EXACT AT 1782 BLOBS, as it must be for a change that touches no closure decision:**
    `rounds=2 pend=4 reach=8`, `rf:skip=114380 visit=44987 clos=44390 holeEnd=44370`, `n:imap=855 synth=2276
    clinits=388`, `memo=128548 res=82350 unres=27419` -- every one matching. `[3 containers successful]` /
    `[2 tests successful]` / `[0 tests failed]` / exit 0, and none of the corruption shapes a wrong decoder
    would produce (no `CANNOT LOAD`, no `BADPATCH`, no wild branch) across a boot that inflates most of a
    3MB jar.
  - **WHAT IS STILL OPEN, unchanged:** the `struct` pass has no round watermark, while every other pass in
    that loop got one in the demand-load arc. And `imap` is 1,377ms cumulative, still the grows-with-load
    shape.

- **THE INFLATER PAID A NON-INLINED CALL PER BIT AND PER BYTE, AND `struct` HAD BEEN CHARGED WITH ITS OWN JAR
  FETCHES (2026-09-15, NOT YET PI-VALIDATED).** The open item from the entry below -- batch 188's
  `mark=581.760ms` with `struct=565.170ms`, reproduced to 0.13% -- is a **classpath-jar FETCH**, not
  structural work: `pullStructural` ends in `registerNameFromDir`, so a class arriving as somebody's
  SUPERCLASS has its whole inflate charged to `struct`. The same batch reads `pull=0us`, which is what says
  nothing pended it.

  - **`decodeSym` WALKED THE HUFFMAN CODE ONE `bits(1)` CALL PER BIT.** A canonical walk reads one bit per
    code length and DEFLATE's average code is ~9 bits, so that is **~9 calls per SYMBOL** -- and every
    literal byte of every demand-loaded class is one symbol. `emit` was a second call **per output byte**,
    from both the literal path and the match copy. **This VM's baseline compiler does not inline**, so each
    was a real frame: prologue, callee-saved spill, epilogue, around four lines of work.
  - **MEASURED LOAD-INDEPENDENTLY ON THE REAL JAR, because wall clock here would have proved nothing.**
    Inflating all of `ramfs/lib/junit.jar` (1999 entries, 6,921,734 bytes): **`bits()` calls 17,827,492 ->
    1,099,726, 16.2x**, plus ~6.9M `emit` calls gone. **The HOST run moved only 112 -> 94ms and that is the
    expected result, not a disappointing one:** HotSpot inlines both callees, so the seed JVM structurally
    cannot see this change. The call COUNT is the evidence; the Pi is where a call is a frame.
  - **THE STARVATION CONTRACT IS THE PART THAT HAD TO BE PRESERVED, and it was checked rather than assumed.**
    The refill is exactly what `bits(1)` does (one byte OR'd in at `bitCnt`, which is 0 there), and starving
    still consumes nothing, so a caller's `mark`/`rewind` puts every bit back. The pre-emptive `if (starved)`
    the old loop ran per bit is dropped because **`decodeSym` can never be entered with it already set**:
    every caller returns the moment it is, and the driving loop breaks out until `input()` clears it --
    read at all four call sites, not inferred from one.
  - **THE GATE IS EXACTLY TARGETED AND IT IS THE HOST'S, not a boot:** `zip: 91 checks` builds with the
    JDK's own `Deflater` and reads back with ours, byte-for-byte, over fixed AND dynamic blocks, stored
    blocks, `HUFFMAN_ONLY` (pure literal path, no matches), incompressible data, matches past the 32K
    window, and a 70KB mixed stream -- **and feeds the decoder in CHUNKS**, which is the `mark`/`rewind`
    starvation path this change touches. All 91 pass; A64 105, object-model 22, class-reader 171, refmap 13,
    compiler 37, crypto 17, overlay-check 0 new.
  - **AND THE METAL DECODER IS GATED BY THE ARCHIVE'S OWN CRC, which is a check nothing can fake.** QEMU,
    `demo/ZipDemo` against `/lib/app.jar`: an unmodified `ZipInputStream` walks it, our engine inflates
    every entry, and a stock `CRC32` over the INFLATED bytes prints beside each name --
    `MANIFEST.MF 78 294d779e`, `Greeting.class 1101 86caf830`, `Main.class 1233 da5812a8`, `entries=5`,
    manifest text correct, `[main returned normally]`. **Every size and CRC identical to `unzip -v`**, so the
    JIT-compiled decoder produced byte-identical output. Demo suite alongside it: 33 programs,
    `lisp evals=600 result=610 stable=1`, `gc: collections=54`, `SMP: 4 of 4`, `finish HML` 20/20/20,
    inversion 60ms, `steps/core 60/60/60/60`, `sum20=210 weighted20=2870 wide=7000000155`, `YNW`/`RP`, and
    FOURTEEN failure markers zero. **The suite carries no jar-backed program**, so that boot claims NO
    REGRESSION and ZipDemo is what proves the feature -- a different claim, kept straight.
  - **`fetch=` IS ON THE MARK LINE NOW, and it is a SUBSET of `pull` and `struct` rather than a sibling** --
    stated at both the field and the print, because a term that does not partition its total is exactly how
    the `mark` sub-split lied once before. Two clock reads per DIR LOOKUP, which happens only for a name not
    yet registered -- not per item, which is the rule this arc has now paid for three times.
  - **TWO MORE DEFECTS ON THIS PATH, FOUND BY READING AND DELIBERATELY NOT FIXED HERE** (one unvalidated
    change per card, and neither is ranked yet):
    - **MEASURED AND REFUTED BY THE ENTRY ABOVE -- 949k steps over the whole boot, not worth an index.**
      **`JarFs.entry` LINEAR-SCANS THE WHOLE NAME CACHE, and its own doc claims the opposite** -- "the next
      ask costs one name compare instead of a directory scan", where the code walks up to `cacheCount`
      entries. The cache holds hits AND misses (its comment notes almost every name asked about is a
      java.base class the jar does not hold), so it grows all boot to `MAXCACHE` 2048. **And it is walked
      TWICE per pull**: `registerNameFromDir` asks `VM.dirBytes` then `VM.dirLen`, one `entry()` each.
    - **THE `struct` PASS HAS NO ROUND WATERMARK.** Every other pass in the round loop got one in the
      demand-load arc (`addCollected`, `pendPullTo`, `pdPendTo`/`pdPendEpoch`, `pdDfltTo`, the probe memo) --
      this one still re-derives, every round, facts that are immutable by construction: a blob's superclass
      and direct-interface names come straight out of its classfile bytes.
  - **NOT PREDICTING A FIGURE.** I under-predicted the phase-B publish by 4x in this same arc and recorded
    that I would rather say "unknown" than put a number on it. What the next boot answers: whether batch
    188's 565ms is `fetch`, and what the inflater costs once the calls are gone.
    **BOTH ANSWERED, AND ONE OF THEM AGAINST ME -- see the entry above.** 188 is `fetch` (564.363 of
    564.695ms) and the inflater is 15-18% of an ORDINARY fetch and ~0% of that one. Declining to predict was
    right; the reading behind the fix was still half wrong, and only shipping it said so.

- **`allocCode` SCANNED EVERY BLOCK EVER ALLOCATED, AND ON THE LAUNCHER THE WALK IS NOW GONE ENTIRELY
  (2026-09-15, PI-VALIDATED).** `place` was 6.981ms at batch 209 against `emit`'s 3.875ms though both run
  the same `compileMethod`; the difference was `allocCode`, whose `takeFreeCode` walks **all `codeBlockN`
  blocks** looking for a free one big enough. TWELFTH instance of this file's most common defect.

  | launcher, batch 209 (1782 blobs) | before | after | |
  |---|---|---|---|
  | `place` | 6.981ms | **140us** | **50x** |
  | `emit` | 3.875ms | **2.746ms** | 1.4x |
  | `compile` | 14.868ms | **6.955ms** | 2.1x |
  | `B` | 16.189ms | **8.271ms** | 2.0x |
  | `tot` (per batch) | 62.962ms | **55.098ms** | |
  | whole boot | 101,901ms | **98,694ms** | **-3.2s** |

  - **THE FIX IS A BOUND, NOT AN INDEX, and the bound is EXACT rather than conservative.** `codeFitBound`
    holds the largest free block there can be; a request above it cannot be satisfied, so the walk is skipped
    outright. When a walk does run to the end it has just measured the true maximum, so the bound is set from
    what it SAW (`scanFreeMax`), not from a guess. `freeCodeBlock` and `mergeInto` reset it to UNKNOWN --
    those are the only two ways a block can get bigger.
  - **`emit` FELL TOO, AND THAT IS THE SAME FUNCTION**: `emitDeferredStub` allocates its own buffer, so
    every deferred method was paying the walk as well. One call site's fix, two timers.
  - **THE LAUNCHER GETS THE WHOLE WIN AND THE SUITE DOES NOT, for a reason worth keeping.** On the suite the
    bound took scan steps 29,413k -> 13,080k (2.25x) and stopped there; on the launcher `ac:n=29k scan=0k
    reuse=0k bump=29k` -- **not one successful reuse in the entire boot, and under a thousand scan steps**.
    A launcher boot never frees code, so the bound is learned once and every later walk is skipped; the
    suite's `launchMain` frees between its 30 programs, resetting the bound each time. **The ratio a
    small harness reports is a statement about that harness's free-list churn, not about the code.**
  - **THE FAILURE SHAPE HERE IS MEMORY, NOT A CRASH, which is why the arena was the thing to watch.** A bound
    ever too LOW skips a usable block and bumps instead, so the arena grows and the loud end of that is
    `code arena OOM`. None on this boot, and `reuse=0` says the skipped walks were finding nothing anyway --
    the bound removed scans that could not have succeeded, which is exactly its claim.
  - **QEMU'S WALL CLOCK COULD NOT JUDGE THIS ONE AND THE COMMIT SAID SO.** Every untouched timer moved
    1.56-1.99x between the arms from machine load, so the only load-independent evidence before the flash was
    the step count with `reuse`/`bump` unchanged. The Pi is the first honest timing this change got.
  - **IDENTITY IS EXACT AT 1782 BLOBS:** `rounds=2 pend=4 reach=8`, `rf:skip=114380 visit=44987 clos=44390
    holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, `memo=128548 res=82350 unres=27419` -- every one
    matching the previous boot. `[3 containers successful]` / `[2 tests successful]` / `[0 tests failed]` /
    exit 0.
  - **THE ARC: 161,556 -> 98,694ms -- 62.9 SECONDS, 39% OFF A LAUNCHER BOOT**, over the probe memo, both
    `lookT` keys, the patch-side publish, the `clinit` instrument, the Type index, the phase-B publish, and
    this.
  - **WHAT IS NEXT, AND IT IS NOT WHAT I SAID LAST TIME: BATCH 188 REPRODUCED TO 0.13%.** See the correction
    below -- a 565ms `struct` at one named batch is now the largest per-batch item in the boot outside batch
    1, and it is deterministic. Behind it, **`imap` is 1,364ms cumulative and still the grows-with-load
    shape**: per batch it went 3.4 -> 9.6ms (2.8x) across batches 27-209 while blobs grew only 1.29x. That
    is the function this file has now named twice, at a third tier.

- **PHASE B PUBLISHED THE WHOLE CODE ARENA PER CLASS -- 6.4 SECONDS OFF THE BOOT, AND I UNDER-PREDICTED IT
  BY 4x (2026-09-15, PI-VALIDATED).** `compileClass` and `compile` both ended with
  `publishCode(CODE_BASE, <arena pointer>)` -- a clean+invalidate per 64-byte line over EVERY line of the
  code arena, once per class compiled. ELEVENTH instance of this file's most common defect, and the SECOND
  call site of the same function: the patch-side twin was fixed one increment earlier.

  | launcher, batch 209 (1782 blobs) | before | after | |
  |---|---|---|---|
  | `pub` (inside `compile`) | 7.074ms | **1us** | gone |
  | `compile` | 21.486ms | **14.868ms** | |
  | `B` | 22.849ms | **16.189ms** | |
  | `tot` (per batch) | 68.026ms | **62.962ms** | |
  | whole boot | 108,268ms | **101,901ms** | **-6.4s** |

  - **I PREDICTED ~1.5s AND IT WAS 6.4s, AND THE REASON IS THE INTERESTING PART: `pub` IS PER CLASS, NOT PER
    BATCH.** I extrapolated 7ms x 209 batches from the CAPTURED WINDOW, which starts at batch 16 and by then
    is adding ONE class per batch. **Batch 1 compiles the whole initial closure -- ~1340 classes -- and each
    one walked an arena already grown by every class before it.** That is QUADRATIC in the closure, it is
    where nearly all of this lived, and it is above the window every one of these measurements is taken
    from. Net of the tests (which moved 67,944 -> 66,593ms, real sleeps), **5.0s came out of the load path**
    against the ~1.2s the captured batches can account for.
  - **THE CLINCHING READING BEFORE THE FIX WAS A BATCH THAT COMPILED NOTHING:** batch 201 had `compile=0us`
    and `pub=6.895ms`. It now reads `pub=0us`. Cost proportional to the arena, not to the work.
  - **THE EXTENT IS MEASURED, NOT RECOMPUTED:** `emitMethod` records where its store loop stopped. An
    arena-mark range would be UNSOUND -- `allocCode` serves swept blocks from a free list, so the buffers are
    not contiguous. A deferred method records 0 because `emitDeferredStub` publishes its own buffer.
  - **WHAT ELSE THE WIDE SWEEP WAS COVERING, checked rather than assumed**, because that is where a publish
    narrowing goes SILENTLY wrong: every other `allocCode` caller publishes its own buffer. The two that do
    not are the deferred stub (covered) and `buildLineTable`, which puts a bci->line table in the code arena
    but is only ever READ by the stack-trace walker, never executed -- so it needs no I-cache maintenance.
  - **Barriers are paid ONCE per class rather than per method**, keeping the wide version's ordering exactly:
    every clean reaches unified memory BEFORE any invalidate is issued. Per-method `publishCode` would have
    been hundreds of full barriers to avoid one arena walk -- the trade the patch-side fix already rejected.
  - **IDENTITY IS EXACT AT 1782 BLOBS:** `rounds=2 pend=4 reach=8 v:walks=2 levels=4 grew=1 cached=4`,
    `rf:skip=114380 visit=44987 clos=44390 holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`,
    `memo=128548 res=82350 unres=27419`, and `rfs:type=201k hole=1550k clos=169k fill=1383k` -- every one
    matching the previous boot. No `FAULT`, no `ESR EC=0`, no `BOOT RE-ENTERED`, no `BADPATCH`, and none of
    the silent-wrong-answer shapes a bad publish would produce. `[2 tests successful]`, exit 0.
  - **QEMU COULD NOT JUDGE THE HALF THAT MATTERED and said so before the flash:** it does not model an
    incoherent I-cache, so publishing too LITTLE is invisible there and fatal on the board. The A/B proved
    the change publishes the right NUMBER of bytes; only the Pi could say they were the right LINES.
  - **ONE UNEXPLAINED OUTLIER -- AND THE FIRST READING WAS RIGHT AFTER ALL, THREE BOOTS LATER.** It IS a
    collection (`gc` 9 -> 10 at exactly batch 188); what was wrong was the REASON for believing it, and
    "a collection is not that repeatable" below is the sentence that sent this three boots sideways. A
    collection triggered by a DETERMINISTIC allocation sequence is exactly that repeatable. See the entry
    above. The text as written at the time:** Batch 188 shows `mark=580.990ms` with `struct=564.437ms`, against
    ~16ms for its neighbours and ~18ms at the same batch on the previous boot. I read that as a collection
    landing inside the timer. **The allocCode boot reproduced it at the SAME batch to 0.13%** --
    `mark=581.760ms` / `struct=565.170ms`, same `+1750blob`, neighbours still ~16ms. A collection is not
    that repeatable. It is deterministic work, and noting it was what let one more boot settle it for free.
    Whether the ~18ms reading two boots back is the same batch composition is not established; the two
    sightings that agree are what the next look should start from.
  - **THE ARC SO FAR: 161,556 -> 101,901ms, 59.7 SECONDS AND 37% OFF A LAUNCHER BOOT** across the probe memo,
    both `lookT` keys, the patch-side publish, the `clinit` instrument, the Type index, and this.
  - **WHAT IS NEXT: `place` IS 6.981ms AGAINST `emit`'s 3.875ms at batch 209, though both run the same
    `compileMethod`.** The difference is `allocCode`, whose `takeFreeCode` is a free-list scan and
    `noteCodeBlock` an append. **Which of the two is a question for counters, not for reading** -- the rule
    this arc has now been right about twice. The batch is `mark` 16.8 + `B` 16.2 + `patch` 14.1 +
    `clinit` 14.8 + `A` 1.1, so nothing dominates any more.

- **THE `clinit` PHASE WAS MEASURING ITS OWN UART TRAFFIC, AND THE REGISTRY LOOKUP UNDER IT WAS A LINEAR SCAN
  (2026-09-15, PI-VALIDATED).** Two changes: the number that named this target was half artifact, and
  the real work under it was the tenth instance of this file's most common defect.

  - **(1) `clinit` AND `tot` READ THE CLOCK PARTWAY THROUGH PRINTING THEMSELVES.** They were
    `elapsedUs(tRest)` / `elapsedUs(tAll)`, evaluated mid-line -- and **`Uart.putRaw` SPINS on the TX holding
    register**, so at 115200 baud each character costs ~87us and the ~300 characters already emitted on that
    line were charged to whichever phase happened to be printing. Every span is measured against ONE clock
    read now, taken before anything is printed.
    - **MEASURED, NOT ARGUED, AND AT BOTH ENDS OF A BOOT:** real sub-timer work + prefix length x 87us
      predicts **33.4ms against 32.067ms at batch 15** and **51.2ms against 50.429ms at batch 209** -- within
      1.5% across a 3x spread in the real work.
    - **THE SUB-SPLIT ADDED UP ALL ALONG; THE PHASE TOTAL WAS THE NUMBER THAT LIED.** I had applied this
      file's own rule ("a sub-split that does not add up is not a split") to the wrong half. `imap + synth +
      arr + seeds + runcl` PARTITION `tRest..end` by construction -- there was no missing term to print. Real
      clinit is **7.5ms at batch 15 and 23.8ms at batch 209**, not 32 and 50.
    - **QEMU STRUCTURALLY CANNOT SHOW THIS, which is why it survived:** its serial is not baud-paced, so the
      emulator reported the honest number and the board did not. The same defect inflates `cumClinit`/`cumAll`
      (read after the WHOLE line) and the `LOAD_PROFILE` block (worse -- it prints after the batch line).
    - **IT ALSO MEANS THIS ARC'S HEADLINE WAS UNDERSTATED.** Both arms of the 102.8s -> 19.4s comparison carry
      ~24-26ms per batch of their own serial traffic; net of it the range is roughly **98s -> 14.3s (~7x)**
      rather than 5.3x. The exact figures want a re-measured Pi boot, and this is stated as an estimate.

  - **(2) `regOfType`/`classRegByType` -- ONE QUERY SPELLED TWICE, BOTH WALKING ALL `clCount`.** `refillImaps`
    asks it once per itable-directory ENTRY, per visited imap, per batch, and `clCount` grows with every class
    loaded all boot. **Steps 264k -> 4k, 66x.**
    - **RANKED BY COUNTERS BEFORE BEING BELIEVED, and that is the reusable part.** Three candidates live
      inside a refill visit and reading cannot order them, so four plain int counters went in FIRST. Over a
      65-batch suite: **`type=264k` against `fill=39k`, `hole=23k`, `clos=4k`** -- the registry walk is 81% of
      the refill and the only one of the four that GROWS with the registry; the other two are bounded by the
      class's own hierarchy. The previous arc got exactly this ordering wrong by reading.
    - **SOUND BY CONSTRUCTION, not by a claim about when work may be skipped** -- the distinction that
      separates this from the `virt` attempt that silently under-marked half a closure. `clTab[i].type` is
      written ONCE, at index `clCount`, immediately BEFORE `clCount` is incremented (checked at BOTH
      registration sites). No entry is ever re-pointed, so an append-only index cannot go stale.
    - **The LOWEST matching index still wins** -- head-insertion makes the chain descending, so it is searched
      for the minimum rather than stopped at the first hit (the care `findPdByName`'s index needed). The
      watermark resets BESIDE the table it indexes, because one that outlives its table under-marks silently.
      The bucket array is filled explicitly: **`allocArray` does not zero its elements on this VM.**
    - **IDENTITY:** `rf:skip=1631 visit=1173 clos=1077 holeEnd=1074`, `n:imap=52 synth=18 clinits=25`,
      `memo=1418 res=2510 unres=2253` -- all byte-identical, and `hole`/`clos`/`fill` unchanged to the step,
      so the index moved ONLY what it targeted. 19 failure markers zero in both arms; host tests unchanged
      incl. `compiler: 37 checks` and `overlay-check 0 new`.

  - **THE QEMU WALL CLOCK LOOKED LIKE A 1.6x REGRESSION AND WAS MACHINE LOAD -- caught by the check this file
    prescribes rather than by waving it away.** `imap` read 48.8 -> 76.6ms. But EVERY timer the change cannot
    touch moved with it -- `callT` 1.60x, `alloc` 1.66x, `lookT` 1.54x, `statT` 1.35x -- and imap's 1.57x sits
    inside that band. Confounded by my own concurrent builds, exactly as recorded before. **The step count is
    the load-independent reading; the Pi is the honest harness.**
  - **THREE OUTPUT DIFFS, ALL SETTLED BY CONTROL RATHER THAN ASSUMPTION.** The suite's SMP job distribution
    differs run-to-run on the **SAME binary** (`c0=24 c1=0 c2=0 c3=0` vs `c0=1 c1=9 c2=3 c3=11`, `smp sched:
    4 of 4` throughout) -- one re-run settled what would otherwise have read as a regression. GC `roots` +3 is
    exactly the three new statics. The one stack-trace line shift (2060 -> 2050) is exactly the lines this
    removes above it, **at the same pc offset `+0x1DC`**; the code-arena delta (5232 B) is inside the
    baseline variance this file already measured between two IDENTICAL arms (4536 B).
  - **WHAT THE BATCH ACTUALLY LOOKS LIKE NOW, net of the artifact:** real `tot` at batch 209 is ~80ms, of
    which `B` 22.7ms, `mark` 16.8ms (`probe` 7.2ms of it, and that is `buildNameIndex`, not `probeAll`),
    `patch` 14.2ms, and `clinit` ~24ms falling to ~4ms once the index lands. **`B` is the largest item left**
    and has been sub-split only once. Also standing: `unresT` 684.8ms cumulative against `lookT`'s 726.4ms --
    `linkStubFor`, indexed once, measured cold, and REVERTED.
  - **PI-VALIDATED, AND THE SUB-SPLIT NOW SUMS TO THE PHASE EXACTLY.** `[3 containers successful]` /
    `[2 tests successful]` / `[0 tests failed]` / exit 0; no FAULT, no `BOOT RE-ENTERED`, no `BADPATCH`, no
    `VIRTUALRESOLVE FAILED`, no `LAMBDA IFACE UNRESOLVED`, no `ClassCastException` -- the silent-wrong-answer
    shapes a bad registry index would produce. Only the known `ProcessImpl` trap at batch 25, survived again.

    | launcher, batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `clinit` (per batch) | 50.429ms | **13.286ms** | of which ~26ms was the artifact |
    | `imap` (per batch) | 20.063ms | **9.594ms** | 2.1x |
    | `imap` (cumulative) | 2,925.903ms | **1,494.709ms** | -1.43s |
    | `tot` (per batch) | 106.156ms | **68.026ms** | |
    | whole boot | 108,786ms | **108,268ms** | -518ms |

    - **`imap 9.594 + synth 2.052 + arr 0.060 + seeds 1.561 + runcl 0.019 = 13.286`, against a printed
      `clinit=13.286ms`.** The partition is exact, which is the structural proof that the split was always a
      split and the PHASE TOTAL was the number that lied.
    - **IDENTITY IS EXACT AT 1782 BLOBS:** `rounds=2 pend=4 reach=8 v:walks=2 levels=4 grew=1 cached=4`,
      `rf:skip=114380 visit=44987 clos=44390 holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, and
      `memo=128548 res=82350 unres=27419` -- every one matching the previous boot.
    - **THE INDEX TURNED THE DOMINANT COUNTER INTO THE SMALLEST.** Per batch at 209 the refill's four steps
      now read **`type=1k` against `hole=9k`, `fill=7k`, `clos=1k`**. On the suite `type` had been 6.8x the
      next largest; it is now tied for last.
    - **THE WHOLE BOOT MOVED LESS THAN THE COUNTER SAYS WAS REMOVED, AND THAT IS STATED RATHER THAN ROUNDED
      AWAY.** `imap` gives back 1.43s cumulative; the boot moved 518ms. The difference is inside this
      harness's batch-to-batch variance -- batch 204 alone is 28ms SLOWER on this boot (`reg` 8.6 -> 24.3ms,
      JIT compile variance) and batch 197 35ms faster. The cumulative counter is the trustworthy reading;
      a whole-boot delta of a few hundred ms is not resolvable here.
  - **WHAT IS NEXT: `B` IS THE LARGEST PHASE NOW.** At batch 209, `tot` 68.0ms = `B` 22.8 + `mark` 16.8 +
    `patch` 14.1 + `clinit` 13.3 + `A` 1.0. **`B` has been sub-split once and never since.** Inside `clinit`
    the residue is `imap`'s 9.6ms, and its counters say what that is: `hole=9k` and `fill=7k` steps per batch
    -- `itableHasHole` and `refillItable` over the ~239 imaps that can never be memoised (a slot stays 0 when
    nothing in the closure declares a body). Those are searched, fail, and are searched again next batch.

- **`publishCode` WALKED THE WHOLE CODE ARENA PER PATCH PASS -- `pubT` 259x, AND THE LOAD PATH IS NO LONGER
  THE BOTTLENECK (2026-09-15, PI-VALIDATED).** `patchRelocsFrom` ended with
  `publishCode(CODE_BASE, CODE_PTR)` -- a `DC CVAU` + `IC IVAU` per 64-byte line over **every byte of code
  ever emitted**, once per batch, to publish the handful of words that pass had just written. It now
  publishes exactly the reloc sites it patched: two passes over `rcAddr`/`rsAddr`, cleans then invalidates,
  with the barriers hoisted out (`publishClean`/`publishMid`/`publishInval`/`publishEnd` in `Heap`, so the
  discipline lives in ONE place and the split cannot drift from the combined form).

  | launcher, batch 209 (1782 blobs) | before | after | |
  |---|---|---|---|
  | `pubT` (cumulative) | 12,679.541ms | **48.874ms** | **259x** |
  | `patch` (per batch) | 20.100ms | **14.162ms** | 1.4x |
  | whole boot | 114,533ms | **108,786ms** | -5.7s |

  - **THE ARC'S REAL RESULT IS THE SUM, and it is measured rather than extrapolated: `tot` over batches
    15-209 is 19.4s, against 102.8s for the IDENTICAL range before this arc -- 5.3x, ~83 seconds.** Per
    batch at 209: `tot` 676.1 -> **106.2ms**. The per-batch load path was 64% of that boot; it is **18% of
    this one**. Three fixes (probe memo, the two `lookT` keys, this) and the whole-boot total went
    161,556 -> 122,470 -> 114,533 -> **108,786ms**.
  - **QEMU COULD NOT JUDGE THIS ONE AND SAID SO IN THE COMMIT MESSAGE.** It measured 225x there and that
    number was never the question: **QEMU does not model an incoherent I-cache**, so it cannot show a MISSED
    maintenance op. This file already records that exact bug -- JIT'd code published to one I-cache out of
    four -- as found ONLY on hardware, with SMP on, as an undefined instruction at the entry of a method
    that reads back perfectly in memory. A narrowing that publishes too little is invisible on the emulator
    and fatal on the board. **The gate was the Pi, and it is the whole reason this shipped alone.**
  - **CLEAN, and the specific absences are the assertion:** no `FAULT`, no `ESR EC=0`, no `BOOT RE-ENTERED`
    (a stale `bl 0` in some core's I-cache branching to address 0), no `BADPATCH`, no wild branch --
    across ~200 batches of patching followed by executing what was patched, on four cores.
    `[3 containers successful]` / `[2 tests successful]` / `[0 tests failed]` / exit 0.
  - **IDENTITY IS EXACT AT 1782 BLOBS: the batch-209 line is byte-identical to the previous boot on EVERY
    counter but the timers** -- `rounds=2 pend=4 reach=8 v:walks=2 levels=4 grew=1 cached=4`,
    `rf:skip=114380 visit=44987 clos=44390 holeEnd=44370`, `n:imap=855 synth=2276 clinits=388`, and
    critically **`memo=128548 res=82350 unres=27419`**: the same sites resolved through the same tiers to
    the same answers, with the same words written. Only WHICH LINES got maintenance changed.
  - **THE HALTING `ProcessImpl` TRAP FIRED AGAIN AT BATCH 25 AND THE BOOT RAN ON TO 209.** picocli's
    terminal-width probe reaching a denied native inside `lazyCompileLocked` is now a routine survivable
    event rather than a boot-ender -- hardware proof of the lock fixes by PRESENCE, for the second
    consecutive boot.
  - **THE `dl` RE-KEY RODE ALONG (`7b4b6af`)** -- `dlCellOf`'s bucket is keyed on class+name now instead of
    the name alone, the same defect one tier below `lookT`. Correct, and worth ~0.1%: the suite's `dl` steps
    went 8k -> 5k. **Measured before being believed in, and shipped because it was already right rather than
    because it paid.**
  - **CORRECTED BY THE ENTRY ABOVE -- `clinit` WAS NEVER 47% OF A BATCH, AND `tot` IS OVERSTATED TOO.** Both
    were read partway through printing the line that reports them, so ~26ms of the figures below is the
    report's own UART traffic. Real clinit at batch 209 is 23.8ms, and the sub-split DID add up. The reading
    that follows was wrong about the size and right about the target (`imap`), which is the only reason it
    pointed anywhere useful. **WHAT IS NEXT, AND THE SHAPE HAS CHANGED: `clinit` IS 50.4ms OF A 106.2ms BATCH
    (47%) AND IT COSTS THAT ON A BATCH THAT COMPILES NOTHING.** Batch 201 has `compile=0us` and `clinit=52.345ms`; batch 199 the
    same. `runcl` -- actually RUNNING initializers -- is **7.041ms cumulative over the entire boot**, so
    essentially none of this phase is the work it is named for.
    - **AND ITS SUB-SPLIT DOES NOT ADD UP, WHICH BY THIS FILE'S OWN RULE MEANS IT IS NOT A SPLIT.**
      `imap` 20.063 + `synth` 2.050 + `seeds` 1.555 + `arr` 0.061 + `runcl` 0.017 = **23.7ms of 50.4ms**.
      Over half the phase is unnamed. Print the missing terms BEFORE guessing -- the `mark` sub-split made
      exactly this mistake (nine sub-timers summing to 295ms of 712ms, three computed and never printed).
    - **`imap` IS THE NAMED HALF AND IT IS THE SAME FUNCTION ALREADY FIXED ONCE (267x, 2026-09-14).**
      2,925.9ms cumulative, 20.1ms/batch. The counters say the regrowth is per-VISIT, not more visits:
      visits per batch grew 183 -> 239 (1.3x) while the time grew 6.11 -> 20.06ms (3.3x), so the cost per
      imap visit went **33us -> 84us**. Something inside a visit scans a table that grows all boot -- the
      instance this file has now named nine times.
    - Also standing: `B` is 22.7ms (second largest), `mark` 16.8ms of which `probe` is 7.2ms -- and that
      7.2ms is `buildNameIndex`, NOT `probeAll` (`pb:probed=1 of 1782`, so the memo is doing its job and the
      residue is the index rebuild). And **`unresT` is 684.8ms cumulative against `lookT`'s 726.4ms** --
      that is `linkStubFor`, indexed once, measured cold, and REVERTED on the grounds that its path was not
      hot. It is now within 6% of the tier that was just cut 21x. **A function that measured cold is a
      statement about that closure, not about the code.**

- **THE REGISTRY INDEX WAS KEYED ON THE METHOD NAME ALONE -- `lookT` 20.9x, PI-VALIDATED (2026-09-15).**
  `patch` was 60% of a batch with `lookT` 94% of `callT`. Two growing terms in the same lookup, and
  **MEASUREMENT got their ORDER right where reading did not** -- I had the second one as the hypothesis and
  was about to ship it alone.

  | launcher, batch 209 (1782 blobs) | before | after | |
  |---|---|---|---|
  | `lookT` (cumulative) | 14,270.8ms | **682.3ms** | **20.9x** |
  | `callT` (cumulative) | 15,142.1ms | **1,516.1ms** | 10.0x |
  | `patch` (per batch) | 132.077ms | **20.100ms** | 6.6x |
  | `tot` (per batch) | 221.280ms | **112.076ms** | 2.0x |
  | whole boot | 122,470ms | **114,533ms** | -7.9s |

  - **(1) THE REGISTRY BUCKET IS KEYED ON CLASS+NAME NOW.** Keyed on the name alone, a hot name (`<init>`,
    `run`, `get`) chained ONE ENTRY PER CLASS DECLARING IT, so the chain grew with the registry all boot --
    ~60 entries per resolve on a 192-blob suite. **Every `<init>` site walks it in full**, because the
    `<init>` short-circuit sits AFTER tier 1. Tier 2 re-hashes per superclass level rather than sharing the
    bucket, which is the whole point -- sharing a name-only bucket is what made both tiers walk it.
    `defaultBySig` hashes per closure interface (`clTab` supplies the class half) and **closure ORDER is
    still exact**: one probe per interface, walked in order.
  - **(2) `findPdByName` IS A PROBE.** A linear walk of all `pdCount` blobs, called by tier 2 once for the
    ref class plus once per superclass LEVEL. It reuses `pnBucket`, the index `buildNameIndex` already
    maintains for `nameRegistered` -- same key, same table, nothing new to invalidate. **The LOWEST matching
    index still wins**, which the scan gave for free and a chain does not: head-insertion makes the chain
    DESCENDING, so it is searched for the minimum rather than stopped at the first hit. On hardware:
    187,994 calls over **231k steps -- 1.23 per call**, against ~66 before.
  - **THE COUNTERS PUT THEM IN THE OPPOSITE ORDER TO THE ONE I EXPECTED, and that is the finding.** Across
    suite batches 40-65 `lookT` grew 7.9x; `t1Steps` grew **9.2x** (17k -> 156k) and tracked it, while
    `fpSteps` grew **1.6x** (452k -> 704k) and did not. The blob scan had 4.5x the STEP COUNT and ~67ns a
    step against the registry chain's ~650ns, so the smaller count was the bigger half. **`t2Steps` reads 0k
    on the suite** -- the super-chain walk barely runs there -- so (2) alone would have bought almost
    nothing. Fixing (1) first then exposed (2) as the entire remainder (47.2ms of 47.2ms).
  - **Cheap counters, not timers.** Two `readCNTPCT_EL0` per lookup would have been a visible share of what
    was being measured -- the lesson already paid for in the imap refill. Plain int increments, and they are
    what separate "the index fires" from "it fires and the rest is irreducible".
  - **IDENTITY, at 1782 blobs and not just the suite's 192:** the batch-209 line is byte-identical to the
    previous boot on EVERY counter -- `rounds=2 pend=4 reach=8 v:walks=2 levels=4 grew=1 cached=4`,
    `rf:skip=114380 visit=44987 clos=44390 holeEnd=44370`, and critically **`memo=128548 res=82350
    unres=27419`**: the same sites resolved through the same tiers to the same answers. Batches 22, 58 and 86
    likewise. On QEMU: 547 lines of program output byte-identical, 13 markers zero, 33 programs, host tests
    unchanged incl. `compiler: 37 checks`.
  - **WHAT IS NEXT, and it is the SAME defect one tier down: `dl` is 259k steps at batch 209** -- the largest
    remaining, against `fp` 231k, `t1` 31k, `t2` 15k. `dlCellOf` keys `dlBucket` on `utf8Hash(nameBase,
    nameOff)` -- the name ALONE -- and `dlStubByRef` is the tier **every `<init>` short-circuits into**.
    Exactly the chain that was just removed from tier 1, still in place below it.
    **DONE -- see the entry above.**

- **THE BLOB PROBE RE-DERIVED IMMUTABLE FACTS EVERY ROUND -- MEMOED, PI-VALIDATED, AND THE CLOSURE IS
  PROVEN IDENTICAL (2026-09-15).** `probeAll` re-parsed EVERY blob's constant pool on every call --
  once per `markReachable` round plus once more per batch -- while `pdCount` grows all boot. It was the
  item the previous entry named as next, and the measurement that named it was the launcher at batch 209
  (1782 blobs): **309.7ms of a 320.0ms `mark`, 97% of it**, plus 151.1ms at the top level -- 471ms of a
  676ms batch, **70%**, and 4.9x what it cost at batch 15 for 31% more blobs.
  - **EACH BLOB IS PROBED ONCE NOW, and that is sound BY CONSTRUCTION rather than by a claim about when work
    may be skipped** -- the distinction that separates this from the `virt` attempt that silently under-marked
    half a closure. Everything the pass writes is read straight out of the classfile bytes (this_class, super,
    the direct interfaces, every `CONSTANT_Class` name, whether the pool holds a `CONSTANT_String`), and
    **`addBlob` dedups by ADDRESS and only ever writes `pdBase[pdCount]`** -- no index is re-pointed at
    different bytes for the life of a launch. A second probe could only reproduce the first one's answers.
  - **The dep list became APPEND-ONLY for the same reason** (a dep is a `{base, offset}` pair into immutable
    bytes), so `dpCount` is no longer cleared in `probeAll`. It and `stringPdIndex` move to `resetLoader`
    beside the watermark: **a watermark that outlives the table it indexes under-marks SILENTLY**, and a
    fresh `dpOwner`/`dpOff`/`dpBase` left beside a stale `dpCount` would have `ready()` reading zeroed deps
    as real ones. Same trap the virt hit lists nearly shipped.
  - **QEMU A/B, two `cmp`-confirmed-different binaries, demo suite: `probe` 12.39 -> 1.06ms at batch 64 and
    FLAT where it had climbed 4.1 -> 12.4ms; `mark` 15.1 -> 3.4ms.** Removing the GROWTH term is the point,
    not the ratio: the suite peaks at ~190 blobs against the launcher's 1782, so this is the small end.
    `pb:probed=1 of=189` is the instrument -- a batch adding one class probes one blob.
  - **PI-VALIDATED ON THE LAUNCHER (`core 166MHz`, SMP on): `[2 tests successful]` / `[0 tests failed]` /
    exit 0, and `Test run finished after 122470 ms` against 161,556ms -- 39 SECONDS OFF, 24%.** That is the
    same total which went UP on the previous increment and could not be attributed; this change targeted the
    thing that had been measured as 70% of a batch, and the total moved.

    | at batch 209 (1782 blobs) | before | after | |
    |---|---|---|---|
    | `probe` (top level) | 151.110ms | **0us** | gone |
    | `probe` (inside `mark`) | 309.699ms | **7.210ms** | 43x |
    | `mark` | 320.010ms | **17.394ms** | **18.4x** |
    | `tot` | 676.081ms | **221.280ms** | 3.1x |

    And it stops growing: `mark` had gone 68.2 -> 320.0ms across the boot (4.7x) and now goes 5.8 -> 17.4ms.
    `pb:probed=1 of=1782` on a batch adding one class -- the watermark firing at full scale.
  - **THE CLOSURE IS IDENTICAL ON HARDWARE TOO, at 1782 blobs rather than the suite's 190.** Batch for batch
    against the previous boot: `rounds`, `v:walks`, `levels`, `grew`, `cached`, `meth` all unchanged (b22
    `rounds=4 pend=48 walks=12 levels=36 grew=3`; b58 `rounds=9 walks=23 levels=52 grew=9`; b86
    `rounds=12 walks=228 levels=626 grew=36 meth=8k`), and the END STATE is byte-identical --
    `rf:skip=114380 visit=44987 clos=44390 holeEnd=44370 n:imap=855 synth=2276 clinits=388 pc:n=1243
    memo=128548 res=82350 unres=27419`, every one matching. **Only `pend` falls** (b86 1477 -> 1068, b68
    268 -> 200), which is the duplicate indy-interface pends and nothing else -- the QEMU finding reproduced
    at nine times the scale.
  - **The expected `ProcessImpl` trap still fires and is still survived** (picocli's terminal-width probe, at
    batch 25, holding the loader lock), and the boot ran on through batch 209 to exit 0. Its ABSENCE would
    have been as suspicious as a new failure.
  - **WHAT THE BOTTLENECK IS NOW, from the same log: `patch` is 132.077ms of a 221.280ms batch -- 60%** --
    with `callT` 15,142ms cumulative of which **`lookT` is 14,271ms (94%)**, and `pubT` 12,830ms. `mark` is
    down to 8%. The dispatch-tier lookup is next, and it is the item this file already records as "measured
    cold once and dismissed". **DONE -- see the entry above.**
  - **`pend` AND `grew` CANNOT GATE THIS, AND `reach` CAN -- which is why `reach=` is now on the batch line.**
    The closure counters were NOT byte-identical: `pend` fell 1565 -> 1039 at batch 2 and the virt walks with
    it, because `pendIndyIface` no longer re-pends an interface once per round. That is waste the code's own
    comments had already named ("re-pending it every round would grow the pend list for nothing"; "neither
    answer changes by being asked again") -- but **`pend` is a QUEUE LENGTH and `grew` counts level-VISITS,
    so neither can tell removed waste from lost marking**, and under-marking here is silent. `reachN` is the
    marked set, reset per batch by `markReachable`, and it is **IDENTICAL in all 65 batches**. That settled
    in one run what no amount of reading could.
  - **Everything else held:** 549 lines of program output byte-identical but for ONE stack-trace line number
    (`Loader.java:2052` -> `2059`, exactly the 7 lines this change adds above it, same pc offset `+0x1DC`);
    GC collections identical (2/3/45/54) across all four runs; 33 programs; 19 failure markers zero; host
    tests unchanged incl. `compiler: 37 checks` and `overlay-check 0 new`.
  - **THE CODE ARENA HIGH-WATER IS NOT A CLOSURE SIGNAL, and checking that took one grep rather than a
    theory:** `cur`/`peak` differed between the arms -- and differed MORE between the two BASELINE runs
    (0x2563408 vs 0x25645C0, +4536 bytes) than between baseline and change (-2360). It tracks demand between
    collections and moves with any layout shift.
  - **A NEAR-MISS WORTH KEEPING: an `assert count == 1` refused a non-unique edit anchor, and `make image`
    then ran anyway** and produced a "baseline" arm carrying no instrument -- an A/B whose arms could not be
    compared at all, which looks exactly like a clean result. Caught by checking the file, not the exit code.
    Third cousin of "an A/B whose arms are the same binary looks exactly like a change that does nothing".
  - **STILL OPEN, and now the largest items by the same log:** `lookT` is 14,753ms of a 15,665ms `callT`
    (94%) and `pubT` 13,544ms cumulative with per-batch `pub` up 6.6x -- both the same grows-with-load shape,
    both previously measured cold and dismissed.

- **THE `virt` HIT-LIST MEMO IS PI-VALIDATED -- AND THE SAME BOOT SAYS THE BOTTLENECK HAS MOVED
  (2026-09-15).** `[2 tests successful]` / `[0 tests failed]` / exit 0, with no FAULT, no parity DIFF, no
  `BOOT RE-ENTERED`, no `VIRTUALRESOLVE FAILED`, no `CAP EXCEEDED` and no bare AIOOBE -- the under-marked
  closure this area produced once before.
  - **THE SOUNDNESS SPLIT IS THE REUSABLE PART.** `virt`'s per-level work is TWO things with different
    lifetimes. The **name+descriptor match is immutable by construction** (`mtName`/`mtDesc` are fixed by
    the classfile and pend entries never change), so it is cached permanently in per-blob hit lists. The
    **shadow check is PATH-DEPENDENT** -- `virtResolved[q] = virtStamp` means "nearest definition wins, do
    not also mark a super's shadowed one", which depends on the class the walk STARTED from -- so it still
    runs on every visit. Nothing is skipped; only SEARCHING is. **A naive level memo is unsound here, and
    that is exactly what the one earlier attempt got wrong** (reach 1040 -> 449, half the closure, silently,
    with standalone runs still building the right one).
  - **QEMU: `virt` 13,742 -> 1,464ms (9.4x), `mark` 18,552 -> 7,561ms (2.5x), batch-1 `tot` 40.5 -> 32.0s.**
    Gated on IDENTITY rather than on "it looks clean": `rounds=26 pend=67889` unchanged and
    `walks=8457 levels=25129 grew=1624 cached=25129 parsed=0 meth=325k` **all identical**.
  - **THE PI TOTAL WENT UP -- 149,856ms -> 161,556ms -- AND THAT IS NOT A MEASUREMENT OF THIS CHANGE.**
    `virt` lives in batch 1; **every batch after it reports `v:walks=0`** and does no virt work whatever. A
    whole-boot total cannot judge a change confined to one batch when two thirds of the boot is something
    else that grows. The batch-1 line is what would settle it and it is above the captured window.
  - **WHAT THE LOG DOES MEASURE: ~103 SECONDS OF THE 161.6s RUN IS THE PER-BATCH LOAD PATH, AND IT GROWS
    WITH WHAT IS ALREADY LOADED.** Summing `tot` over batches 15-209 (the range captured) gives **102.8s**.
    Per batch, across a 31% growth in blobs (1356 -> 1782):

    | batch 15 -> 209 | at 15 | at 209 | |
    |---|---|---|---|
    | `mark` | 68.2ms | 320.0ms | 4.7x |
    | `probe` (top level) | 30.9ms | 151.1ms | 4.9x |
    | `patch` | 8.6ms | 132.0ms | **15.4x** |
    | `tot` | 154.6ms | 676.1ms | 4.4x |

    **31% more blobs for 4.4x the cost is the per-item-scan-of-a-growing-table shape this file has now named
    seven times.** It is also why the launcher's two tests report 65,727ms and 17,090ms: those are not sleeps,
    they are demand-loading.
  - **AND THE LOG NAMES THE FUNCTION: `probe` IS 97% OF `mark`.** At batch 209 `mark=320.0ms` with the
    bracketed `probe=309.7ms` INSIDE it -- `probeAll` + `buildNameIndex` in the round loop -- plus the
    top-level `probe` at 151.1ms. Together **471ms of a 676ms batch, 70%**. That is the same reading recorded
    at the end of the imap arc ("probe is now essentially all of mark", 292ms of 296ms) and it has grown 5x
    since while everything around it was cut. **Two more instances sit beside it:** `callT` is 15,665ms
    cumulative of which `lookT` is **14,753ms (94%)** -- the dispatch-tier lookup again, bigger now than
    before it was last indexed -- and `pubT` is 13,544ms cumulative, with per-batch `pub` growing
    1.08 -> 7.07ms (6.6x) for publishing no more code than before. `publishCode` measured 2.4% once and was
    dismissed; it is not 2.4% any more. **A function that measured cold is a statement about that closure,
    not about the code.**
  - **A HALTING TRAP FIRED MID-BOOT AND THE VM FINISHED THE RUN -- hardware proof of the lock fixes, by
    PRESENCE rather than absence.** picocli's terminal-width probe calls `ProcessBuilder.start` on its own
    thread; that lazily compiles, which runs `ProcessImpl.<clinit>`, which calls the denied native
    `ProcessImpl.init` -- **inside `lazyCompileLocked`, holding the loader lock**. Before
    `loaderForceRelease()` + the plain yield loop, that froze every subsequent load for the rest of the boot.
    Here it trapped at batch 25 and the boot ran on through batch 209 to exit 0. The `try`/`finally` at the
    nine lock sites had only ever been validated by the stuck-lock report NOT appearing; this is the real
    thing happening and being survived.
  - **NEXT, and it is worth more than everything cut so far:** `probeAll`/`buildNameIndex` inside the round
    loop. 70% of a batch and rising, over ~195 batches of a launcher boot. **DONE -- see the entry above.**

- **THE LOAD PATH WAS PROFILED TO THE FUNCTION, AND `inheritVtable` IS 15.9x FASTER (2026-09-15,
  PI-VALIDATED).** `vtab` 4,773ms -> 299.8ms; `parse` falls from 24% of phase B to 3.3%; the launcher boot
  goes 155,816ms -> **149,856ms** with `[2 tests successful]` and exit 0.
  - **THE MEASUREMENT CHAIN, each step splitting the previous winner:** all class-loading -> batch 1 (47,566ms
    of 55,506ms, **86%**) -> phase B (49%) -> `compileClass` (70%) -> place+emit (75%). Every split was a
    timer, never a reading of the code -- the method this file already credits for ending three wrong guesses
    about `patch`.
  - **THREE OF MY OWN GUESSES DIED TO IT, all of them the "obvious" shape.** Phase B's loop rescans all
    pdCount blobs per pass -- the O(everything-loaded) pattern this file says to suspect FIRST and which has
    been the answer six times -- and it measured **1.4%** (4 passes, 5,368 checks). `publishCode` walks the
    WHOLE code arena once per class, 1342 times over a growing region: **2.4%**. `parseConstPool`'s cache
    lookup is a linear scan over a table that grows to 1342: **9.9ms at a 100% hit rate**. Shape is a
    hypothesis generator, not evidence.
  - **THE REAL ONE CAME FROM READING A LOOP BOUND:** `inheritVtable` does `while (i < vtCount)` with a
    utf8EqAt per entry, and `vtCount` is cleared by `resetLoader` -- per LAUNCH, not per class -- so it holds
    every vtable entry of every class loaded so far (~27,000 at 1342 classes), walked once PER CLASS. Seventh
    instance of this file's most common defect; seventh time the remedy is a name-hash probe.
  - **ASCENDING ORDER IS LOAD-BEARING, not caution.** The scan applies matches in index order and a later
    entry overwrites the same `gvTab[slot]`, so a head-inserted bucket chain (newest first) could silently
    install a DIFFERENT implementation in a slot -- a wrong-method dispatch, not a crash. The index keeps a
    per-bucket TAIL and appends. Same care `defaultBySig`'s index needed for closure order.
  - **Correctness gated on IDENTITY:** `rounds=26 pend=67889` byte-identical to every pre-change run,
    `DIFF` = 0, the sub-split adds up exactly (10 + 339 + 300 = 649 vs parse=650), and the suite's
    slot-sensitive arms exact (`overloads named pick = 3`, `ifacedflt`/`ifacedfltch = late-default`).
    **`vtparity 0` in a quiet sweep is NOT evidence of parity passing** -- those OK lines are gated behind
    `LOAD_LOG`; `DIFF` is ungated and is the real check.

- **DEFERRING `<init>` WAS MEASURED, BUILT, AND REVERTED -- it wild-branches on hardware (2026-09-15).**
  `<init>` was the only method kind still compiled at load time (`notInit` is literally "the name is not
  `<init>`", with no reason recorded), and a non-deferred method is compiled TWICE: `sizeMethod` at a dummy
  base to learn the length, then `emitMethod` at the real one. 853 methods per launcher batch.
  - **REUSING THE DRY RUN IS NOT THE FIX, and that was measured before anything was built.** The comment
    justifying the dummy-base trick says the word COUNT is placement-independent; it says nothing about the
    WORDS. Counted: only **102 of 853** produced identical output, and **20,649 of 118,876 words differ**
    (17.4%, ~24 per method). Not "patch a few bl displacements".
  - **Deferring removed the redundancy (`dc:n` 853 -> 1) and broke the launcher:** an undefined instruction
    at a pc in the HEAP (`elr=0x04A8F678`, heap starts 0x0400_0000, code ends below 0x0300_0000), from
    picocli's `getCommandMethods`.
  - **THREE GATES PASSED ON IT, and that is the finding worth keeping.** The demo suite ran 33 programs clean
    -- including `newarm = demo.RtaMade`, the deferred-CONSTRUCTOR arm chosen for exactly this path -- QEMU
    reported no parity DIFF, and the closure was byte-identical. None of them sees a constructor reached
    through a stub inside a 1342-class closure. **A suite that exercises the SHAPE is still not the
    launcher.**
  - **IT TOOK A BISECT because two unvalidated changes shipped on one card**, and a wrong vtable slot is also
    exactly a wild branch, so neither could be blamed from the failing boot. One boot with only this reverted
    settled it. **Do not put two unvalidated changes on the same card.**
  - The redundancy is real and still open; deferring `<init>` is not the way to remove it.

- **THE JUnit CONSOLE LAUNCHER RUNS STOCK jtreg TESTS ON BARE METAL AND THEY PASS -- `[2 tests successful]`,
  `[0 tests failed]`, exit status 0 (2026-09-14, PI-VALIDATED).** The real
  `org.junit.platform.console.ConsoleLauncher`, demand-loaded from the stock jar, discovers and executes
  unmodified OpenJDK `SleepSanity` on a 166MHz Pi: `testMillis() 17228 ms`, `testMillisNanos() 61869 ms`,
  both green. Four defects, each hidden by the one before it.
  - **(1) A CONSTRUCTOR REFERENCE DID NOT INITIALIZE ITS CLASS (JVMS 5.5).** Three of the four instantiation
    routes enforced it -- compiled `new`, deferred `new` (`resolveUnresolvedNew`), reflective
    `Constructor.newInstance` (`allocInstance`) -- and the hand-emitted kind-8 thunk had `ensureClinit`
    NOWHERE. JUnit builds descriptors through `TestMethodTestDescriptor::new`, whose constructor is
    `getstatic defaultInterceptorCall; putfield interceptorCall`, so the field was null and surfaced as a
    lost lambda CAPTURE a dozen frames away.
  - **(2) `jdk/internal/lang/CaseFolding` WAS DENIED on a premise that expired.** Its own comment said "only
    CASE_INSENSITIVE regex needs them"; JUnit's `TimeoutDurationParser` compiles one. Overlaid (36-entry
    table, exact) rather than un-denied, because the stock class builds its tables with `multianewarray`,
    which this JIT has metadata for and NO LOWERING for.
  - **(3) THE LOADER LOCK LEAKED ON EXCEPTION -- and this was the timing failure, not latency.** Three
    waiters, `held` growing 29,282ms -> 229,224ms -> 299,053ms against a 218-second run, `depth` 3/3/2 never
    reaching 0, and the completed-release counter FROZEN at `rel 12865` for all three: not one outermost
    release in 270 seconds. Every thread that needed to compile blocked for the rest of the boot, so the
    watcher thread could not reach `interrupt()` and main's 10s sleep ran to completion.
    - **MECHANISM, checked not inferred:** no lock/unlock pair has an early return, and `VM.unwind`
      references none of `loaderOwner`/`loaderDepth`/`loaderUnlock`. Neither call was inside a `try/finally`,
      so an exception in the locked region exits non-locally and strands the lock. The path runs GUEST CODE:
      `lazyCompile -> drainPendingPulls -> loadClassIncremental -> loadAll`, whose tail runs queued
      `<clinit>`s through `Magic.call0` -- and JUnit throws as ordinary control flow.
    - Same shape as the recorded `clinitDepth` leak, whose decrement also sat on the success path.
  - **(4) A HALTING TRAP KEPT THE LOCK, and `try/finally` cannot reach it** -- a denylist trap spins in
    `Magic.wfe()` rather than throwing, so nothing unwinds. Fixing (3) let picocli's terminal-width thread
    reach `ProcessBuilder.start -> ProcessImpl.<clinit> -> the DENYLISTED ProcessImpl.init`; it halted
    holding the lock and the launcher WEDGED at 73 lines with the VM otherwise alive. `loaderForceRelease`
    drops the whole hold before the spin.
  - **THE INSTRUMENT IS WHAT MADE (3) FINDABLE, AND IT REFUTED MY OWN READING TWICE.** The watchdog printed
    `owner task N state S waiter M` and nothing else -- "somebody is busy". Adding WHAT the owner is doing
    (a tagged site id per acquisition, recorded on the OUTERMOST hold only), HOW LONG, the compile context,
    the DEPTH and a COMPLETED-RELEASE counter turned it into a diagnosis. My first reading was "a demand-load
    batch under the lock" -- the profile refuted it (execution-time batches are ~0.5s). My second was "leaked
    from boot" -- `rel 12865` on a QEMU run that was otherwise healthy refuted that too. `rel` frozen ACROSS
    three reports is what finally named it.
  - **`depth` ALONE WOULD NOT HAVE DONE IT:** a legitimately deep hold shows a depth above 1 and is fine.
    What separates a leak from a long hold is whether releases happen at all, which is why `rel` counts
    OUTERMOST releases only -- counting re-entries would have hidden exactly the case being looked for.
  - **THE LOAD PATH WAS PROFILED and is NOT the blocker, which is worth recording because it was the
    plausible answer:** 21 batches, 55,506ms total, and **batch 1 alone is 47,566ms -- 86% of it**
    (`B` 49%, `mark` 42% of which `virt` is 28% of the batch, `A` 9%). Every later batch is ~0.5s, far too
    cheap to eat a 5s window. Phase B is the largest item and has NEVER been sub-split; `virt` is the known
    open item this file already records.
  - **PI-VALIDATED:** `[3 containers successful]`, `[2 tests successful]`, `[0 tests failed]`, exit 0, ZERO
    `LOADER LOCK stuck` lines, and the run 29% faster (218,172ms -> 155,816ms) because threads no longer
    queue behind a stranded lock. The `ProcessImpl` denylist trap DOES fire on the Pi -- an earlier commit
    message of mine says it does not, which was wrong -- but it no longer wedges anything.
  - **STILL OPEN, and it is the next layer of the same root cause:** a task that halts for ever is a
    permanent obstruction. Having stopped it blocking the loader lock, on QEMU it then blocks
    STOP-THE-WORLD (`GC: STW TIMEOUT -- unparked core 1 (collection SKIPPED)` then `heap OOM`), because a
    task spinning in `wfe()` never reaches a yield point. The general fix is for a halting trap on a
    non-main task to TERMINATE the task rather than spin. Not seen on the Pi.

- **A CONSTRUCTOR REFERENCE NOW INITIALIZES ITS CLASS, AND THE LAUNCHER'S TESTS EXECUTE THEIR BODIES
  (2026-09-14, PI-VALIDATED).** The two `SleepSanity` tests had been failing with a `NullPointerException`
  whose innermost frame was `ReflectiveInterceptorCall.lambda$ofVoidMethod$0`. That NPE is GONE: the trace is
  now three frames of the TEST'S OWN CODE (`fail` / `testTimeout` / `testMillis`), i.e. the test method ran
  and reached a real assertion.
  - **ROOT CAUSE: JVMS 5.5 WAS ENFORCED ON THREE OF THE FOUR INSTANTIATION ROUTES AND NOT THE FOURTH.**
    Creating an instance is an active use, so `<clinit>` must precede `<init>`. Taking inventory:
    compiled `new` (compile-time note + drain), deferred `new` (`resolveUnresolvedNew` -> `ensureClinit`),
    reflective `Constructor.newInstance` (`allocInstance` -> `ensureClinit`, commented "an active use, like
    `new`") -- and **a CONSTRUCTOR REFERENCE, whose hand-emitted kind-8 thunk allocates and calls `<init>`
    itself and had `ensureClinit` NOWHERE on it.** JUnit builds its descriptors through
    `TestMethodTestDescriptor::new`, whose constructor is `getstatic defaultInterceptorCall; putfield
    interceptorCall` -- so the field was null, and the null surfaced as a lost lambda CAPTURE a dozen frames
    away.
  - **THE SAME DEFECT, FIXED ONCE BEFORE, WITH ITS SYMPTOM ALREADY RECORDED.** `resolveUnresolvedNew`'s own
    comment describes this exactly: `ArrayList.<init>` reads `DEFAULTCAPACITY_EMPTY_ELEMENTDATA`, so an
    uninitialized class produced a list whose `elementData` was null and whose first `add()` threw NPE.
  - **THE CALL GOES AT RUN TIME IN THE THUNK, NOT AT THUNK-BUILD TIME, and that half is load-bearing:**
    building a thunk is LINKING, and JVMS 5.4 forbids linking from running an initializer -- the ordering
    inversion this VM already paid four failed fixes for. It sits after the SAM arguments are stored to the
    frame and LR is saved, inside the range `addJitFrame` registers, so an initializer that allocates,
    compiles or throws unwinds correctly. NOT under the loader lock, deliberately: `clinitEntryOf` takes that
    around the COMPILE only, because an initializer may block on a monitor or spawn threads.
  - **EIGHT HYPOTHESES HAD ALREADY DIED TO EVIDENCE; THE NINTH THROUGH TWELFTH DIED HERE, AND TWO OF THEM
    WERE MINE.** "reloc recording is gated to batch compiles" (refuted by reading -- every late path reaches
    `emitMethod`, which sets it); "the constructor was never compiled" (refuted by MEASUREMENT, below);
    "the wrong same-arity overload was picked" (refuted -- it is the ONLY constructor compiled, the others
    pruned by RTA); "a kind-8 thunk skips `<clinit>`" as tested by the first probe (refuted, and the probe
    was wrong -- see below).
  - **`COMPILE_WATCH` IS WHAT ENDED IT, and it refuted MY OWN leading reading in one boot.** I had inferred
    "the constructor was never compiled" from the ABSENCE of an `ownstatic` line -- the exact move this file
    forbids, and the fourth lying diagnosis of the arc. The instrument names every method COMPILED or
    DEFERRED for one class (prefix-filtered: an unfiltered dump over a ~1,700-blob closure floods the UART and
    starves the run it is meant to diagnose, already paid for once). It reported the constructor **emitted at
    BATCH time (`late=0`)** while `<clinit>` was **DEFERRED and compiled late (`late=1`)** -- the body exists
    before the initializer runs, which named the ordering and killed the inference together.
  - **THE INSTRUMENT'S SELF-TEST FOUND A PROBLEM IN MY BUILD, NOT IN ITSELF.** Armed and pointed at a probe
    class it printed NOTHING -- indistinguishable from an instrument that cannot fire. Cause: **`make out` is
    not a target** and silently does nothing, so the image came from the previous `Loader.class`. This file
    already records that trap (it once produced two byte-identical A/B images). `make build` is the target.
  - **A LATENT BUFFER OVERFLOW FIXED ON THE WAY:** the lambda thunk buffer was a flat `allocCode(160)` while
    the kind-8 arm emits TWO WORDS PER SAM ARGUMENT (save across `Heap.alloc`, reload before `<init>`). Past
    ~13 SAM arguments it wrote PAST the allocation into whatever code the arena handed out next. Nothing
    reached had that arity, so it never fired; the buffer is sized from `ia` now, which removes the cliff
    rather than moving it one argument further out.
  - **NEGATIVE CONTROL, and it took THREE attempts to build a probe that could serve as one.** With only the
    `ensureClinit` call disabled: `untouched hasMark = 0`, `untouched mark = <null: clinit did not run before
    <init>>`; restored, it passes -- while every other arm passes in BOTH states, so the control is specific
    to the condition. **The two earlier arms reproduced the SHAPE and not the CONDITION**, the trap this file
    names repeatedly: the first printed the class's static marker on its opening line (a `getstatic` is a 5.5
    active use, so it initialized the class before the reference fired), and the second still read a marker
    afterwards -- and compiling a CROSS-CLASS `getstatic` calls `noteInitNeeded`, so the drain initialized it
    before `main` executed an instruction. **The probe initialized its own target as a side effect of
    checking it.** The working arm reads NO static of the target anywhere and asserts only through instance
    methods.
  - **PI-VALIDATED:** no `lambda$ofVoidMethod$0` anywhere; `[3 containers successful]`, `[2 tests started]`,
    both executing. QEMU: demo suite clean on THIRTEEN markers, 33 programs, `newarm = demo.RtaMade` (the
    deferred-constructor arm), `finish HML` 20/20/20, inversion 61ms, `churnMB=625 live=32 intact=32`; host
    tests unchanged incl. `compiler: 37 checks` (the writer emits no thunk, so the self-hosting fixpoint
    cannot move) and `overlay-check 0 new`.
  - **STILL FAILING, AND IT IS A DIFFERENT AND MUCH SMALLER CLASS: WALL CLOCK.** `testTimeout` starts a
    watcher that sleeps 5s then interrupts main, while main sleeps 10s expecting the interrupt;
    `fail("Exited before timeout")` means it arrived too late. The log says why, twice, around the test
    output: **`LOADER LOCK stuck >10s ... waiter 5` / `waiter 6`** -- the watcher threads blocked while their
    bodies compile. Durations agree: `testMillis` reports 55,680ms for a test that aborts at its first ~10s
    sleep. **THE CONTROL IS REAL, AND IT HAD TO BE RUN RATHER THAN CITED.** I first wrote this
    entry claiming "the same SleepSanity passes on this Pi under MetalJUnit, one of the six classes in
    `ran 44, failures 0`" -- and that was an UNEARNED citation: this file names the passing timing tests as
    `testSleep`/`testInterruptSleep`/`testJoinOnTerminatingThread`/`testInterruptJoin`, and **SleepSanity
    declares NEITHER** (it has `testMillis`/`testMillisNanos`), so those four belong to
    `SleepWithDuration`/`JoinWithDuration`. "The class is in the passing set" and "THESE METHODS pass" are
    different claims. Measured instead (`scripts/run-junit.sh 900 SleepSanity`, QEMU, 2026-09-14):
    **`ok testMillis` / `ok testMillisNanos` / `metal junit: ran 2, failures 0` / `ALL PASSED`.** Same VM,
    same unmodified test, SMALL closure. (QEMU, not the Pi -- the Pi's 44-test figure includes the class but
    was never broken out per method, which is exactly the gap that made the original citation wrong.)
  - **THAT RUN ALSO REFUTES THE PRIORITY READING**, which was the obvious suspect given this file's own
    record that a coordinator outranking the threads it waits for starves them (the `Thread.join` yield-poll
    trap: the boot flow sits at `PRIO_NORM` 512 while a spawned `java.lang.Thread` defaults to 455).
    MetalJUnit launches on the same boot-flow task with the same priorities and the watcher is interrupted
    correctly, so priority is not what differs.
  - So `Thread.sleep`/`interrupt` are sound and what differs is closure size -- first-call compilation and
    demand-load latency eating the watcher's 5s window. Stated as the leading reading, NOT as fact: proving
    it means timing the watcher's start-to-interrupt gap, or naming what holds the loader lock (the watchdog
    currently prints the owner's task id and state and NOT what it is doing, which is the next instrument).
  - **`LOADER LOCK stuck >10s ... state 4` IS NOT A DEADLOCK, for the fourth recorded time:** state 4 is
    `TASK_RUNNING`, so the owner was working. Read the state field before calling it one.

- **`jdk/internal/lang/CaseFolding` OVERLAID -- a CASE_INSENSITIVE Unicode regex compiles (2026-09-14,
  PI-VALIDATED).** Exposed by the fix above: with constructor references initializing their classes,
  `TimeoutDurationParser.<clinit>` ran for the first time and halted the launcher in a named denylist trap.
  - **THE DENIAL'S PREMISE EXPIRED, AND ITS OWN COMMENT STATED IT:** "case-folding tables ([[I via
    multianewarray): only CASE_INSENSITIVE regex needs them". JUnit compiles
    `([1-9]\d*) ?((?:[nμm]?s)|m|h|d)?` with **flags 66 = CASE_INSENSITIVE | UNICODE_CASE**, so a RANGE takes
    `Pattern.CIRangeU`, which asks which characters fold INTO it.
  - **UN-DENYING THE STOCK CLASS DOES NOT WORK, checked rather than assumed:** it builds its tables with
    `multianewarray`, an opcode this JIT carries metadata for (stack delta, operand count, length) and has
    **no lowering** for. So: overlay, and narrow the denial -- a denied class is trap-wired at PATCH TIME, so
    no link stub runs and an overlay is never consulted.
  - **THE DEEP SCAN CAUGHT WHAT MY GUESS MISSED.** Checking `Pattern`/`Matcher`/`String`/`Character` by hand
    said one method was referenced. Scanning ALL of java.base -- the population `make overlaycheck` does not
    see, the blind spot that cost `Character.getType` -- found `StringLatin1`/`StringUTF16` also call `fold`
    and `isSingleCodePoint`. Tracing those: they back `compareToFC`, which serves ONLY JDK 26's NEW
    `String.equalsFoldCase`/`compareToFoldCase`/`UNICODE_CASEFOLD_ORDER`; `equalsIgnoreCase` and
    `regionMatches(true, ...)` go through `regionMatchesCI` and never reach here.
  - So `getClassRangeClosingCharacters` is EXACT and the other three THROW -- and they throw an **Error, not
    a RuntimeException, deliberately**: narrowing the denial turns those sites from a halting denylist trap
    into an ordinary call, so a CATCHABLE exception would convert a loud failure into a silent wrong answer.
  - **EXACT, WITH NO STATED LIMIT:** the "expanded" case map is **36 entries**, dumped from the seed JVM
    rather than transcribed from a guess, held as parallel `int[]` rather than stock's `Map.ofEntries` +
    stream (36 boxed Integers and a stream pipeline inside a `<clinit>` is the shape that has cost whole
    sessions here).
  - **A 20,780-RANGE SWEEP FIRST REPORTED 2,715 MISMATCHES -- every one the same SET in a different ORDER.**
    Stock derives its key array from `Map.ofEntries(...).keySet().stream()`, and `ImmutableCollections` salts
    iteration order **per JVM run**: five runs of the stock method return the same four code points in four
    different orders, so `Pattern` cannot depend on order without being nondeterministic itself. Compared as
    SETS: **ZERO mismatches over 2,929 non-empty answers.** "The diff is only ordering" is exactly the
    reasoning that should not be taken on faith.
  - **THE PROBE'S FIRST ARMS WOULD PASS OVER AN EMPTY TABLE**, which is why the closure arms exist: JUnit's
    own range is `[1-9]`, and digits have no case, so its closure is legitimately EMPTY and a do-nothing stub
    answers it perfectly while being wrong for everything else. `[\u017E-\u0180]` contains U+017F (folds to
    `s`) and `[\u2129-\u212B]` contains U+212A (folds to `k`), **each with its own NEGATIVE**, so an
    implementation answering "everything folds in" fails too. All nine arms byte-identical to the host.

- **`java/lang/reflect/Executable` EXISTS NOW, and a `Constructor` can answer its own parameter types
  (2026-09-14).** The launcher's blocker was
  `VIRTUALRESOLVE FAILED java/lang/reflect/Constructor.getParameters()` -- which reads like a missing method on
  `Constructor` and is not.
  - **ROOT CAUSE: THE CLASS WAS ABSENT ENTIRELY.** Stock is
    `Constructor extends Executable extends AccessibleObject`; joe-ng had both `Method` and `Constructor`
    extending `AccessibleObject` DIRECTLY, with no `Executable` at all. JUnit's
    `ExtensionUtils.registerExtensionsFromExecutableParameters` holds an `Executable`, so javac emits
    `invokevirtual java/lang/reflect/Executable.getParameters` -- the receiver's chain does not contain that
    class, the call falls to the late-virtual tier, and **that tier resolves against the RECEIVER, which is why
    the report named `Constructor`**. The report was accurate about what it searched and misleading about the
    cause; this is the third time a late-tier failure has been read as an overlay gap on the class it names.
  - **THE SURFACE CAME FROM `javap` ON THE JAR, not from guessing which method trapped first.**
    `registerExtensionsFromExecutableParameters` and `AnnotationUtils.getEffectiveAnnotatedParameter` between
    them call exactly `getParameters`, `getDeclaringExecutable`, `getDeclaringClass`, `getParameterAnnotations`,
    `getParameterCount`, and `instanceof Constructor` on the result. Fixing one member per ten-minute launcher
    boot is what has made this family expensive.
  - **`Constructor` WAS THROWING AWAY THE REGISTRY INDEX IT HAD JUST LOOKED UP.** `resolve()` called
    `ctorResolve0`, used `idx` for `methodInfo0`, and stored only the buffer/access/arity -- so a constructor
    could never answer its parameter TYPES (the cached `paramChars` keeps one character each, which cannot name
    a reference type). `Executable` hoists an abstract `registryIndex()` and implements `getParameterTypes()`
    and `toGenericString()` on it ONCE, which is what makes the constructor half work at all rather than being
    a second copy of Method's.
  - **NOT TAKEN FROM THE JDK 26 SOURCE, and that is a STATED exception to rule 3.** Stock `Executable` is 833
    lines over `sun.reflect.generics.*` (a generic-signature parser and type factory) and
    `sun.reflect.annotation.*` (the annotation parser and proxy runtime) -- both denied here. The
    `ServiceLoader` precedent applies exactly: when the stock implementation is built on subsystems this VM
    deliberately does not carry, no faithful copy can work whatever its shape.
  - **THE OVERLAY CHECKER REFUSED MY FIRST CUT, and it was right four times over.** `getParameterTypes`,
    `toGenericString`, `Parameter.getType` and `Parameter.getParameterizedType` were all REFERENCED and
    silently dropped -- `Parameter.getType` by **eleven** JUnit classes, because deciding whether a resolver
    can supply a parameter is the whole job of a parameter resolver. Backlog 36 -> 28 gaps.
  - **THE HOST CONTROL REFUSED THE PROBE TWICE, and one of those was a member I INVENTED.**
    `getDeclaredMethod` honours its parameter types on a real JVM where joe-ng's resolves by name; and
    **stock `Parameter` declares no `getIndex()` at all** -- mine was an ADDED member, which `overlaycheck`
    cannot see because it only diffs DROPPED ones. Removed. An added member is still a divergence from the
    JDK-26-source rule, and the ten-second host run is what said so.
  - **`paramTypes0` IS REGISTERED UNDER `Executable` AS WELL AS `Method`:** `nativeBuf` keys on the DECLARING
    CLASS, and registering under one of two is the `LINK FAILED` already paid for once
    (`ClassLoader.resourceExists0` filed under `java/lang/Class`).
  - **STATED LIMIT, not a silent one: `getParameterAnnotations()` returns one EMPTY row per parameter.** The
    VM does not yet read `RuntimeVisibleParameterAnnotations` -- the class- and method-level walks exist and
    the parameter-level one is their unwritten sibling. What it costs precisely: `@ExtendWith` on a PARAMETER
    is not registered. An empty ROW per parameter rather than a null or short array is required, because
    `getEffectiveAnnotatedParameter` indexes the result unguarded.
  - **Three supertypes are BASELINED rather than declared** (`Member`, `AnnotatedElement`,
    `GenericDeclaration`), following what the baseline already records for `Method`, `Constructor` and
    `Field`: that whole interface family sits inside the denied `java/lang/reflect/` implementation tree,
    `GenericDeclaration.getTypeParameters` would drag in `TypeVariable`, and an interface-typed call resolves
    against the receiver through the late tier regardless.
  - **`test/jdk/junit/ParameterProbe` -- EVERY ARM GOES THROUGH AN `Executable`-TYPED VARIABLE.** Calling
    `ctor.getParameters()` on a `Constructor`-typed reference compiles to an invokevirtual on CONSTRUCTOR and
    would pass with the hierarchy unchanged: the shape without the condition, the trap recorded repeatedly
    here. The two-parameter arms are what separate a walk that reads index 0 for everything from one that
    indexes, and `getType` is pinned BY NAME because a Parameter answering the wrong type is non-null and
    silently wrong.
  - **QEMU:** every probe arm byte-identical to the HOST CONTROL, including `ctor p1 type = long` and
    `ctor declaring instanceof Constructor = 1` -- the two a Constructor could not answer before. Demo suite
    clean on THIRTEEN markers (no `DIFF`/`FAULT`/`LINK FAILED`/`BOOT RE-ENTERED`/`CAP EXCEEDED`/`unclaimed pc`/
    `VIRTUALRESOLVE`/`DENYLIST TRAP`), 33 programs, every arm exact -- `finish HML` 20/20/20, inversion 62ms,
    `churnMB=625 live=32 intact=32`, and the REFLECTIVE arms that matter most for a Method/Constructor
    reparent (`overloads named pick = 3`, `invoked = none int:7 two:42`, `reflective = unseen`,
    `reflective lambda thread = 7`, `mirror identity=1`). **No `vtparity`/`itparity` DIFF is the assertion for
    this change**, since inserting a hierarchy level renumbers both worlds' tables and a disagreement prints
    ungated. `metal junit: ran 44, failures 0`; host tests unchanged incl. `compiler: 37 checks`.
  - **PI-VALIDATED (`core 166MHz`, SMP on, launcher): THE BLOCKER IS CLEARED AND THE ENGINE RAN.** There is no
    `VIRTUALRESOLVE FAILED` anywhere in the boot, and `registerExtensionsFromExecutableParameters` appears in
    a STACK TRACE -- i.e. it EXECUTED, which is the proof this change was after, and a stronger one than its
    absence would have been. The launcher went from stopping at that call to
    `Test run finished after 103689 ms` with `[3 containers found]` / `[3 containers started]` /
    `[2 containers successful]` / `[2 tests found]`, past `%% JUnit Platform Suite` and `%% JUnit Jupiter`.
    No FAULT, no parity DIFF, no `BOOT RE-ENTERED`, no `unclaimed pc`, no `LINK FAILED`; only the known
    denylisted `UNRESOLVED STATIC`/`TRAP-WIRED`/`NULL CLASS LITERAL`/`UNREGISTERED SUPER` lines.
  - **NEXT BLOCKER, AND IT IS A DIFFERENT FAMILY REACHED *THROUGH* THIS FIX:**
    `ClassCastException: class <synthesised, implements nothing> cannot be cast to class <synthesised,
    implements nothing>`, from `registerExtensionsFromExecutableParameters` -> `stream` -> `spliterator`.
    **BOTH sides are synthesised LAMBDA TIBs with an EMPTY itable directory** -- the `LAMBDA IFACE UNRESOLVED`
    shape already recorded here: a lambda's functional interface is named only inside the indy's own
    descriptor, so a lambda whose CONSUMER is not in the batch satisfies no interface and every cast against
    that interface fails. It is reached by `Arrays.stream(getParameters())`, i.e. by code that could not run
    at all before this increment.
    - **THE MESSAGE ITSELF IS THE NEXT INSTRUMENT TO FIX.** "synthesised, implements nothing" is honestly
      MEASURED -- it is what the TIB says -- but it names neither the interface that was WANTED nor the
      lambda's implementation method, so it cannot distinguish which of the two casts failed. The
      cast-names-both-sides work (2026-09-11) solved this for registered classes and left the synthesised
      case with one undifferentiated string.
  - **`LOADER LOCK stuck >10s: owner task 0 state 4` FIRED ONCE AT BATCH 21 AND IS NOT A DEADLOCK**, for the
    reason already recorded twice: **state 4 is `TASK_RUNNING`**, so the owner was WORKING, not blocked. A
    10-second WALL-CLOCK threshold is exceeded by legitimate work in a 1,700-blob closure on a 166MHz core.
    Read the state field before calling it a deadlock.

- **THE LOAD PATH IS ~10 MINUTES FASTER: four O(everything-loaded) defects, all one shape (2026-09-14,
  ALL PI-VALIDATED).** A 165-batch launcher boot, measured at batch 165:

  | cumulative | before | after | |
  |---|---|---|---|
  | `imap` (refill) | 460,288ms | **1,626ms** | **283x** |
  | `callT` (patch re-walk) | 161,942ms | **11,092ms** | 14.7x |
  | `alloc` (mark scratch) | 27,585ms | **104ms** | **266x** |
  | per batch: `mark` | 710ms | **296ms** | 2.4x |
  | per batch: `patch` | 1,622ms | **108ms** | 15x |
  | per batch: `tot` | 6,174ms | **584ms** | 10.6x |

  **~637 SECONDS -- over ten minutes -- off one boot.**

  - **EVERY ONE WAS THE SAME DEFECT: a per-item linear scan of a table that grows all boot.** `defaultBySig`
    scanning the method registry for every empty itable slot (and every `<init>`, which short-circuits into
    the last tier); `dlCellOf` scanning the phase-A cell table per reloc site; `CodeEdges.findSite` scanning
    the edge census per site; `printFrameAt` scanning the registry AND calling `codeBlockEndAt`, itself a scan
    of every code block, per candidate. Plus `globalBufByRef` tier 2, left behind when tier 1 was indexed.
    **When something here is O(everything loaded), look for this first.**
  - **THE REMEDY IS NOT ALWAYS AN INDEX, and measuring said which:** `defaultBySig`/`dlCellOf`/tier 2 wanted a
    hash probe; `printFrameAt` wanted the inner lookup HOISTED out of the loop; the mark's scratch wanted the
    per-batch FREE removed (loadAll nulled it every batch, so an "allocate once" guard was true every time and
    did nothing). `linkStubFor` was indexed, measured NO gain, and was REVERTED -- **a real O(n^2) is not
    automatically worth fixing.**
  - **THREE WRONG GUESSES ON `patch`, ended by splitting the timer rather than reading code.** linkStubFor,
    publishCode and CodeEdges each looked plausible and were 0%, ~2% and 5%. Splitting three ways
    (callT 95%) then splitting INSIDE the call loop (**lookT 98%**) named the tier in one run. Three
    plausible linear scans lived in that one function; only measurement said which ran hot.
  - **A SUB-SPLIT THAT DOES NOT ADD UP IS NOT A SPLIT.** `mark`'s nine sub-timers summed to ~295ms of 712ms
    because three of them were computed and never printed, and all nine live INSIDE the round loop while the
    cost was in the setup before it. Printing every term, then timing the setup, found it.
  - **THE USER FOUND ONE THE TIMERS COULD NOT:** "the delay between each stacktrace line was longer and
    longer". That is work proportional to something growing as the walk proceeds -- a nested scan -- and it
    was `printFrameAt`. **It only runs on a failure path, which is exactly why it matters:** this VM diagnoses
    almost everything through printed traces, and this session already lost evidence when a wait loop killed
    QEMU mid-trace.
  - **CORRECTNESS WAS GATED ON IDENTITY, NOT ON "IT LOOKS CLEAN".** The closure is byte-identical
    (`rounds=26 pend=67905`, and `memo`/`res`/`unres`/`rf:*` all unchanged); the trace is byte-identical
    (ExcDemo's seven frames, same line numbers and offsets, `unclaimed pc` = 0). That matters because an
    unreset watermark under-marks SILENTLY -- reach 1040 -> 449 once, and standalone runs still built the
    right closure; only the suite caught it.
  - **CROSS-RUN QEMU TIMINGS ARE NOT COMPARABLE HERE**, and three comparisons were confounded by machine load
    from my own concurrent builds (`pubT` differed 8.7x between runs that could not have affected it). Only
    load-INDEPENDENT ratios stayed trustworthy. A controlled A/B produced two BYTE-IDENTICAL images (`make
    out` is not a target); `cmp` caught it. **An A/B whose arms are the same binary looks exactly like a
    change that does nothing.** The Pi, with no competing load and a known baseline, is the honest harness.
  - **WHAT IS LEFT:** `probe` is now essentially all of `mark` (292ms of 296ms) -- that is `probeAll` +
    `buildNameIndex` inside the round loop -- plus the top-level `probe` at 143ms and `patch` at 108ms. The
    launcher still stops at `VIRTUALRESOLVE FAILED java/lang/reflect/Constructor.getParameters()`, an ordinary
    overlay gap on `Constructor`, untouched by any of this.

- **THE PATCH RE-WALK: 15x ON HARDWARE, and it took THREE WRONG GUESSES to find (2026-09-14,
  PI-VALIDATED).** `patch` was 1,622ms/batch at batch 165 of a launcher boot, the largest item left after
  the imap refill. It is **108ms** now.

  | batch 165 | before | after | |
  |---|---|---|---|
  | `patch` | 1,542ms | **108ms** | **14.3x** |
  | `callT` (cumulative) | 161,942ms | **11,040ms** | 14.7x -- **151 seconds** off the boot |
  | `tot` (per batch) | 2,437ms | **1,002ms** | 2.4x |

  - **ROOT CAUSE: `dlCellOf` SCANNED THE WHOLE PHASE-A CELL TABLE**, three Utf8 compares per entry.
    `dlStubByRef` is the LAST tier of `globalBufByRef`, so it catches every site that misses the direct and
    super-chain tiers -- **AND every `<init>`, which short-circuits straight to it** because a constructor is
    never inherited. Constructor call sites are everywhere, none of those sites memoise (`rcReg` only records
    the DIRECT tier), and `dlN` grows with every class loaded. `globalBufByRef` tier 2 -- the super-chain walk,
    scanning all `rgCount` PER SUPERCLASS LEVEL -- was indexed in the same pass.
  - **SIXTH INSTANCE OF ONE DEFECT IN A SINGLE SESSION**, a per-item linear scan of a table that grows all
    boot: `pull` in the demand-load arc (6,664 -> 1,539ms), `defaultBySig` in the imap refill (460,288 ->
    1,720ms, **267x**), `CodeEdges.findSite` (1,622 -> 1,542ms, **5%**), `linkStubFor` (**no measurable
    gain**), `globalBufByRef` tier 2, and `dlCellOf` (**15x**). **When something here is O(everything loaded),
    this is the shape to look for first.**
  - **THREE WRONG GUESSES, and what ended them.** `linkStubFor`, `Heap.publishCode`'s I-cache walk, and
    `CodeEdges` were each read as plausible and each was wrong -- the first bought nothing, the third 5%.
    What worked was SPLITTING THE TIMER rather than reading code: first three ways (callT 95% vs statT 0.2%,
    pubT 2%), then INSIDE the call loop (**lookT 98%**, unresT 0.9%, tailT 0.5%). The second split named the
    tier in one run. **Three plausible-looking linear scans existed in that function; only measurement said
    which one ran hot.**
  - **DELIBERATELY NOT MEMOISED, for correctness not oversight.** Filling `rcReg` from the non-direct tiers
    would turn ~491 re-resolving sites per batch into single array reads -- larger than indexing. But if a
    subclass later registers its OWN override, the direct tier would find it while a memo kept answering the
    ancestor's body: a silent wrong-method dispatch. The scan is made cheap instead.
  - **CROSS-RUN QEMU TIMINGS ARE NOT COMPARABLE HERE, and that cost several rounds.** Three comparisons were
    confounded by machine load from my own concurrent builds: `pubT` differed **8.7x** between two runs that
    could not have affected it, and `imap` read 2,889ms against 577ms at the same batch while untouched by the
    change under test. Only the load-INDEPENDENT reading (lookT as a FRACTION of callT) stayed trustworthy.
    A controlled A/B was attempted and produced two **BYTE-IDENTICAL images** -- `make out` is not a target, so
    both arms came from stale classes; `cmp` caught it. **An A/B whose arms are the same binary looks exactly
    like a change that does nothing.** The Pi, with no competing load and a known baseline, is the honest
    harness for this kind of measurement.
  - **PI-VALIDATED (`core 166MHz`, SMP on, launcher):** no FAULT, no parity DIFF, no `BOOT RE-ENTERED`, no
    `unclaimed pc`, no `BADPATCH`; only the known denylisted `UNRESOLVED STATIC`/`TRAP-WIRED`/`NULL CLASS
    LITERAL` lines. QEMU: suite clean on ELEVEN markers incl. DANGLING/STALE with `churnMB=625 live=32
    intact=32` and **`newarm = demo.RtaMade`** -- the DEFERRED-CONSTRUCTOR arm, precisely the path `<init>`
    takes through this table. `metal junit: ran 44, failures 0`; host tests unchanged incl.
    `compiler: 37 checks`.
  - **WHAT IS THE BOTTLENECK NOW:** `mark` (711ms/batch at batch 165) and `probe` (144ms); `patch` is 108ms
    and `imap` 1,626ms cumulative. The launcher still stops at `VIRTUALRESOLVE FAILED
    java/lang/reflect/Constructor.getParameters()` -- an ordinary overlay gap on `Constructor`, untouched by
    any of this.
  - **`linkStubFor`'s INDEX WAS BUILT AND THEN REMOVED, on measurement.** It is the same linear-scan shape
    and the fix was sound, but it measured NO gain and the `unres` path it sits on is 541ms cumulative of an
    11s callT. **A real O(n^2) is not automatically worth fixing** -- shipping an index for a path that is not
    hot is unmeasured complexity, which is the rule this file already applies to instruments. The scan is back,
    with a comment recording that it was measured and left alone. `CodeEdges.findSite` was KEPT: 5% is small
    but it is real.

- **THE IMAP REFILL WAS A LINEAR REGISTRY SCAN PER EMPTY ITABLE SLOT -- 267x ON HARDWARE (2026-09-14,
  PI-VALIDATED).** `refillImaps` was **460,288ms** of a 165-batch launcher boot, against `seeds` 565ms and
  `synth` 142ms. It is **1,720ms** now: ~458 seconds, 7.6 minutes, removed from the boot.

  | batch 165 | before | after | |
  |---|---|---|---|
  | `imap` (cumulative) | 460,288ms | **1,720ms** | **267x** |
  | `clinit` (per batch) | 3,656ms | **27.5ms** | 133x |
  | `tot` (per batch) | 6,174ms | **2,515ms** | 2.45x |

  - **ROOT CAUSE: `defaultBySig` SCANNED THE WHOLE METHOD REGISTRY**, once per closure interface, for every
    itable slot that was 0. `rgCount` grows with every method compiled all boot, so the cost grew with it --
    which is exactly what the per-batch line had been saying all along: 1.3s/batch at batch 6 rising to
    3.6s/batch at batch 165. It probes the registry NAME-HASH INDEX now. Same defect and same remedy as the
    demand-load arc's `pull` pass (6,664ms -> 1,539ms on a name hash index).
  - **THE FIX BEFORE IT WAS A NO-OP, AND MEASURING IS THE ONLY REASON THAT WAS FOUND.** Computing the
    interface closure lazily measured **462,948ms -> 460,288ms (0.6%)** on hardware. The memo it added WORKS
    -- counters say ~482 of 665 imaps are skipped per batch -- but `refillItable` only does real work on slots
    that are 0, and those live ENTIRELY in the ~182 imaps still short after a repair. **The number of
    `defaultBySig` calls was therefore unchanged BY CONSTRUCTION.** I had optimised an assumption I never
    measured; splitting the timer three ways said so in one run: `fillT` was **97.9%** of the refill and the
    closure I had made lazy was **0.035%**.
  - **TWO CALL SITES, AND I ONLY REASONED ABOUT ONE.** `buildItableFor` also calls `defaultBySig`, on the
    demand-load/`<clinit>` path -- which is why `clinit` fell 133x as a side effect. Worth checking every
    caller before predicting the blast radius of a fix.
  - **THE ~182 CAN NEVER BE MEMOISED, and that is correct rather than a gap:** a slot stays 0 when nothing in
    the closure declares a body -- an abstract method the class declares itself, or a native with no VM helper
    (both recorded when `mintPrunedStub` landed). They are searched, fail, and are searched again next batch.
    Making the search cheap is the fix; pretending the slot might not be 0 is not.
  - **CLOSURE ORDER IS PRESERVED EXACTLY.** The original returns the first match in CLOSURE order, so the
    bucket chain is walked once per closure interface rather than taking the first entry the bucket yields.
    Two interfaces in one closure may both declare the same name+descriptor, and silently picking the other
    would change which default body runs.
  - **A WRONG CLAIM I MADE AND RETRACTED, recorded because the reasoning was the problem, not the data.** When
    the lazy-closure build measured flat I said the card could not have been reflashed, and gave two lines of
    "evidence" -- both were downstream of my wrong model predicting a 3.6x cut, so neither was evidence at all.
    The card HAD carried that build. **A prediction derived from an unmeasured assumption cannot be used to
    date a log.**
  - **PER-ITERATION TIMERS WERE REMOVED, NOT GATED:** two `readCNTPCT_EL0` per itable entry was noise against
    19s and is a visible share of 613ms. The four counters stay -- plain int increments, and `skip`/`holeEnd`
    are what separate "the memo is not firing" from "the memo fires and the rest is unmemoisable".
  - **PI-VALIDATED (`core 166MHz`, SMP on, launcher):** counters on silicon match QEMU almost exactly (~593
    skip, ~235 visit/clos/holeEnd per batch); no FAULT, no parity DIFF, no `BOOT RE-ENTERED`, no
    `unclaimed pc`, and only the known denylisted `UNRESOLVED STATIC`/`TRAP-WIRED`/`NULL CLASS LITERAL` lines.
    QEMU: demo suite clean on NINE markers with `ifacedflt`/`ifacedfltch` both `late-default` -- the late
    interface defaults this path resolves, and the arms that break if the index probe disagrees with the scan.
    `metal junit: ran 44, failures 0`; host tests unchanged incl. `compiler: 37 checks`.
  - **WHAT IS THE BOTTLENECK NOW:** `patch` (1,622ms/batch at batch 165) and `mark` (711ms/batch), neither
    moved by this change. The launcher still stops at `VIRTUALRESOLVE FAILED
    java/lang/reflect/Constructor.getParameters()` from `ExtensionUtils.registerExtensionsFromExecutableParameters`
    -- an ordinary overlay gap, untouched here.

- **EVERY `<clinit>` RUNS, AND THREE SILENT-CORRUPTION BUGS FELL OUT OF IT (2026-09-12).** Rule 2 is in: the
  `clinitCompilable` gate short-circuits and `CLINIT REJECTED` is gone from every boot. Each blocker it
  exposed was named by the VM and decided by READING THE JDK 26 SOURCE, not guessed.
  - **THE RECORDED POSTMORTEM OF THE BROAD RULE WAS WRONG, and this retires it.** It concluded the rule
    "trades ten null statics for a whole failing subsystem" -- that `ObjectStreamClass$Caches.<clinit>` died
    because an initializer pulled SERIALIZATION, a subsystem this VM does not carry. It was never about
    subsystem size. The whole chain `UniqueId.<clinit>` -> `ObjectStreamClass.lookup` -> `ClassCache.get` ->
    `ClassValue.get` -> `computeValue` RUNS NOW. What actually stopped it was two missing rules below, whose
    symptoms surfaced far from their cause -- and the trace that names them was only readable after the
    unwind-table fix earlier the same day.
  - **(1) A CLASS WITH NO `<clinit>` OF ITS OWN REACHED `ST_INITIALIZED` AT LOAD, SO ITS SUPERCLASS WAS NEVER
    INITIALIZED.** JVMS 5.5 requires the direct superclass first; `ensureClinit` had only `initPrereq`, a
    narrow special case (FileDescriptor for `sun/nio/ch/` and `java/net/`), and its STATE CHECK returned
    before any superclass walk. `java/io/ClassCache$1` is an anonymous `java/lang/ClassValue` subclass with
    no initializer -- exactly that shape -- so it was constructed while `ClassValue`'s statics were still
    null and `ClassValue.<init>` NPE'd reading `nextHashCode` (ClassValue.java:267).
    - **The walk must go ABOVE the state check, and be guarded by its OWN flag rather than by `state`**:
      "I have no initializer" says nothing whatever about my superclass, and `state` is precisely the field
      that lies here. Set before recursing so a cycle cannot re-enter; done once, or it is O(depth) per call.
    - **`superReg` IS RESOLVED ONCE AT REGISTRATION and is -1 for ever if the superclass was not registered
      yet** -- the normal case for a demand-loaded pair. Re-resolved by NAME on first use (the blob does not
      move, so the offset stays valid) and cached, or the corrected walk is a no-op exactly when needed.
  - **(2) `java/lang/Class.classValueMap` WAS NOT DECLARED, SO IT ALIASED SLOT 0.** `ClassValue.get` reads and
    writes that field on the Class it is keyed by; undeclared, the access read and wrote `typeAddr` -- the
    Type pointer EVERY `Class` native dereferences. The loader said so outright
    (`UNRESOLVED FIELD (aliases slot 0)`) and the corruption surfaced as an NPE inside the VM's own dispatch
    resolver. Declared exactly as JDK 26 does (Class.java:3717).
    - **ADDING IT REQUIRED WIDENING THE MIRROR.** `classMirror` allocates Class objects itself at
      header(16) + one field; a field declared in the overlay but not counted there is written PAST the
      object. That coupling is hand-maintained and is now stated at both sites.
  - **(3) RTA's PEND LIST WAS OVERFLOWING IN EVERY LAUNCHER BOOT -- CAUGHT BY THE USER, NOT BY ME.**
    `PEND LIST FULL: RTA is now INCOMPLETE` fired at LINE 26 of every log, including every earlier boot in
    this arc. A dropped ref means the class is never pulled, never registered, `classRegByName` answers -1,
    and its `<clinit>` is never enqueued -- so it manufactures exactly the symptoms above, arbitrarily far
    from the cause. The code's own comment predicted this and the cap was under-sized anyway.
    `MAXPEND` 49152 -> 262144, the drops are COUNTED (full alone cannot say short-by-ten from
    short-by-ten-thousand), and the report is UNGATED because a truncated closure is a failure.
    **SECOND TIME THIS SESSION A CAPACITY REPORT I READ PAST WAS THE ANSWER** (after `CLASS MIRROR CACHE
    FULL`). An instrument only pays if its output is READ.
  - **RULE 3 IN PRACTICE: `initIDs` and `initNative` ARE EMPTY BECAUSE THE C SAYS SO, NOT BY DEFAULT.**
    `UnixFileSystem.initIDs` caches a JNI `fieldID` and `ObjectStreamClass.initNative` a JNI global ref;
    neither has an effect outside JNI, and this VM has none -- so empty is PROVABLY right. Its sibling
    `ObjectStreamClass.hasStaticInitializer(Class)Z` is deliberately NOT wired: it does real work, and a
    plausible `false` is the silent wrong answer rules 2 and 3 exist to remove.
  - **Also: 14 `StaticProperty` keys** (six were missing; that initializer throws `InternalError` on ANY null
    key, so it halts the VM -- read from source in one pass rather than one key per ten-minute boot),
    **`Unsafe.objectFieldOffset(Class,String)`** (the CLASS-keyed sibling of the TIB-keyed VarHandle path --
    at initializer time there is no instance to read a TIB from), **`Class.isRecord`** (the
    overlay-drops-stock-members trap for the TWELFTH time), and the **64-bit CAS encodings** with
    bit-for-bit ARM ARM tests -- the 32-bit pair backing `Magic.spinLock` had shipped with NO test at all.
  - **QEMU:** demo suite clean with THIRTEEN markers zero -- incl. `aliases slot 0`, `UNREGISTERED SUPER` and
    `RTA CLOSURE INCOMPLETE`, the three that would catch this change specifically -- every arm exact
    (`finish HML` 20/20/20, inversion 64ms, all five null-concat arms, `churnMB=625 live=32 intact=32`,
    `lisp evals=600 result=610 stable=1`) and `printStackTrace` still walking to `vm/VM.boot`, which is what
    a VM-wide initialization-order change most needed to show. `metal junit: ran 44, failures 0`. Host tests
    unchanged incl. `compiler: 37 checks` and `overlay-check 0 new` (36 -> 35 gaps).
  - **LAUNCHER: into real discovery**, past `ClassValue`/`ClassCache` entirely. **The suite never launches the
    console launcher, so a clean boot claims NO REGRESSION and nothing more.**

- **THE CONSOLE LAUNCHER RUNS TESTS AND PRINTS ITS OWN SUMMARY -- discovery, execution and reporting, end to
  end on bare metal (2026-09-12).** `Test run finished after 434161 ms` / `[3 containers found]` /
  `[2 containers started]` / `[2 tests found]`, printed by stock `MutableTestExecutionSummary`. Three VM bugs,
  each hidden by the one before it, and **every one of them the same shape: a silent `if (room) { record it }`
  with no else, where losing the record looks exactly like success.**
  - **(1) BOTH JIT UNWIND TABLES SILENTLY DROPPED ENTRIES WHEN FULL**, at `JIT_FRAME_MAX = 4096` -- a cap the
    launcher's closure exceeds. Two consequences, both WRONG ANSWERS rather than degraded ones: a dropped
    FRAME entry cannot be popped, so a trace STOPS there and is indistinguishable from reaching the top; and a
    dropped HANDLER entry means **the catch block DOES NOT EXIST to the unwinder**, so an exception the
    program handles correctly escapes and is reported UNCAUGHT. That second one WAS the
    `Namespace must not be null` failure -- JUnit catches it and reports a container failure, and joe-ng was
    losing the catch. Caps -> 16384 (budget checked: `0x140000` of the `0x1F0000` scratch window, 704 KiB
    spare) and the overflow is REPORTED by name with both consequences stated.
  - **(2) `frameToElement` LEFT `declaringClass` NULL** for an image frame -- and for a pc found in no table it
    left BOTH class and method null. Its own comment stated the premise: image frames "sit above the guest
    frames the caller inspects, so their exact split doesn't matter". True for PRINTING, FALSE for any caller
    that reads the fields. JUnit's `ExceptionUtils.pruneStackTrace` does
    `className.startsWith("org.junit.start.")` on EVERY element, so the null NPE'd inside JUnit's own reporter
    -- replacing the real failure with a mystery. Never null now; the placeholder is deliberately not a
    plausible class name, so it cannot collide with a prefix a caller prunes on.
  - **(3) `java/util/Formatter` DROPPED `Formatter(Appendable)` -- THE OVERLAY-DROPS-STOCK-MEMBERS TRAP FOR THE
    ELEVENTH TIME.** Stock `PrintWriter.format` builds `new Formatter(this)` and expects the formatter to
    write THROUGH to the writer; the overlay declared only `Formatter()`. It surfaced as a `DENYLIST TRAP`
    naming a list `java/util/Formatter` is not on. The whole surface was taken from the stock BYTECODE in one
    pass rather than one member per boot -- `<init>(Appendable)`, `locale()` (compared against
    `Locale.getDefault()` BY IDENTITY, so answering the default keeps the writer's cached formatter) and
    `format(Locale,String,Object[])` -- and the three descriptors were checked byte-for-byte against what
    stock references.
    - **A formatter that ACCUMULATED instead of writing through would print NOTHING and look like a working
      call**, so the probe arms assert the text ARRIVED. One calls `printf` TWICE on the same writer, because
      `PrintWriter` caches its Formatter and a flush that failed to reset would repeat the first call's text
      -- which a single-shot arm cannot see. `[pw n=7]`, `[a1b2]`, `[x-9]`, all exact.
  - **WHAT MADE ANY OF IT FINDABLE: an instrument that says WHY the trace ended.** `captureTrace` broke out of
    its walk on `frameSizeAt == 0` with the comment "top of the JIT/image stack" -- a condition with TWO
    meanings and one appearance. It now records the stopping pc AND the word under the stopped frame, and
    reports `TRACE TRUNCATED` only when that word is a plausible code address, i.e. when there DEMONSTRABLY
    was more stack. Checked against a PASSING boot before being trusted (ExcDemo's trace is unchanged and the
    line stays silent). A three-frame trace with no caller became a named one, and "a compiled method with no
    frame entry" is what pointed at the table rather than at JUnit.
  - **STILL OPEN, AND IT IS A SCOPE BOUNDARY, NOT A GAP:** the one container failure is
    `createCloseAction` -> `getSessionLevelStore` -> `Preconditions.notNull`, caused by
    `CLINIT REJECTED java/util/concurrent/CompletableFuture` -- so `NIL` is null and `completedFuture` NPEs.
    That initializer needs **`ForkJoinPool.asyncCommonPool()`**, the landmine already backed out of once, so
    allowlisting it would open a path straight into a trap. The narrow route is to SEED `NIL` and its three
    VarHandles the way `Net.EXTENDED_OPTIONS` is seeded -- its own increment.
  - **QEMU:** demo suite clean with ZERO `DIFF`/`FAULT`/`TRAP`/`LINK FAILED`/`BOOT RE-ENTERED`/`CAP EXCEEDED`
    and -- the two that matter for this change -- zero `JIT UNWIND TABLE FULL` and zero `TRACE TRUNCATED`,
    with `printStackTrace` still walking to `vm/VM.boot`; every arm exact incl. all five null-concat arms and
    `churnMB=625 live=32 intact=32`. `metal junit: ran 44, failures 0` / `ALL PASSED`. Host tests unchanged
    incl. `compiler: 37 checks` and `overlay-check: 0 new`.
  - **A GATE THAT FAILED FOR A REASON THAT WAS NOT THE CHANGE.** `metal junit` first reported 2 failures --
    both the WALL-CLOCK timing tests, at `Duration 938771ms, expected <= 20000ms`, with the host at load
    average 7.6 from my own concurrent QEMU runs. Re-run on an idle machine: 44/0. **Confirmed by re-running
    rather than waved away** -- and running an image BUILD concurrently with `make test` is what created that
    load, and also broke one build outright (`class not registered: demo/Philosopher`), since both share
    `out/`.

- **THE PER-CLASS CLINIT ALLOWLIST *CAN* FINISH -- the population is FOURTEEN, measured (2026-09-11).**
  Two more entries (`TimeoutExtension`, `ColorPalette`) and a correction to what this file recorded one
  increment ago.
  - **THE BLOCKER: `TimeoutExtension.<clinit>` is three instructions** -- `ldc Timeout.class;
    Namespace.create(...); putstatic NAMESPACE` -- so it names ANOTHER class and the self-class-literal rule
    cannot reach it. Every store access goes through `LauncherStoreFacade.getStoreAdapter`, whose FIRST act is
    `Preconditions.notNull` on the namespace, so a skipped initializer is **"Namespace must not be null" the
    moment Jupiter sets up EXECUTION**. That is where the launcher stopped on current main -- past discovery
    entirely, deeper than any previous run.
  - **"A PER-CLASS ALLOWLIST CANNOT FINISH" WAS WRONG, and the number is what says so.** That claim came from
    "44 of 74 classes in the FIRST 400". Scanning the WHOLE jar in one `javap` pass -- **1484 classes, 293 with
    a `<clinit>`, of which exactly 14 `ldc` a class other than themselves** -- gives the real population. Six
    were already allowlisted; this increment takes it to eight.
  - **AND THE SAME SCAN EXPLAINS WHY THE BROAD RULE KEEPS FAILING, precisely rather than anecdotally.** Five of
    the remaining six must STAY rejected because they pull a subsystem this VM does not carry:
    `StringToNumberConverter` (BigInteger/BigDecimal -- the ForkJoinPool landmine), `StringToJavaTimeConverter`
    and `JavaTimeArgumentConverter` (`java/time/*`), `StringToCommonJavaTypesConverter` (`java/io/File`,
    `java/nio/file/Path`, `java/net/URL` -- denylisted), and `UniqueId` (serialization). **The broad rule runs
    exactly these five.** Their statics are read only by `@ParameterizedTest` string conversion, which nothing
    reached calls, so leaving them null is CORRECT rather than merely tolerable.
  - **`ColorPalette` was batched in rather than waited for**, because it is the same shape one phase later:
    `--disable-ansi-colors` selects `ColorPalette.NONE`, and a skipped initializer makes that null at the first
    line of test output. Fixing one class per ten-minute boot is what made this family expensive.
  - **STILL OPEN, AND IT IS A DIFFERENT MECHANISM: the namespace is STILL null.** With both entries in, the
    ONLY JUnit `CLINIT REJECTED` left is `UniqueId` (deliberate) -- and the launcher stops at the SAME
    `getStoreAdapter` line. There is no `UNRESOLVED STATIC`, `UNRESOLVED FIELD`, `LINK FAILED` or
    `NULL CLASS LITERAL` in that run beyond the four known denylisted ones, so **a rejected initializer is no
    longer the cause**. The trace is three frames and prints no caller, which is the next thing to get.
  - **I TRUNCATED MY OWN EVIDENCE AND LOST A BOOT TO IT.** The wait loop matched `Exception in thread` and
    killed QEMU while the stack was still being written a frame at a time over a 115200 baud UART -- chopping
    off the frame that names the faulting class. `scripts/run-launcher.sh` now waits 25 s after the marker,
    with the reason recorded beside it.
  - **`scripts/run-launcher.sh` EXISTS NOW.** Every previous launcher run in this arc was ad-hoc; the script
    codifies the manifest save/restore trap (a generated `ramfs/etc/init` left behind is how the tracked one
    gets committed by accident), the live-truncated log, and `--disable-ansi-colors` -- the launcher's own
    condition, since ansi AUTO takes a different picocli wrap path that proved nothing about this one.
  - **TWO STALE LAUNCHER BOOTS I NAMED AS BLOCKERS WERE ALREADY FIXED.** `findRepeatableAnnotations` was
    `annotationType()` returning null (PR #262) and the `BigDecimal.<init>` NPE behind it was
    `JUnit4VersionCheck` (the vintage-engine denial) -- both closed hours before the logs I read them from
    were superseded. **Check a log's COMMIT before naming its failure as current.**

- **THE BROAD CLINIT RULE WAS RE-TESTED AND REJECTED AGAIN -- this time with the boundary MEASURED
  (2026-09-11).** Five Jupiter initializers had been allowlisted one at a time; the scan that ended that
  approach: **of the 74 classes with a `<clinit>` in the first 400 JUnit classes, 44 `ldc` a class OTHER than
  themselves** and are therefore rejected. Each costs a ten-minute launcher boot to discover. A per-class
  allowlist cannot finish.
  - **FORM 1 -- RUN EVERY INITIALIZER (no gate at all): DIED IN 3 MINUTES**, against ~40 for the control.
    `java/io/File.<clinit>` pulls `java/io/UnixFileSystem.<clinit>`, which calls the DENYLISTED native
    `UnixFileSystem.initIDs` -- there is no filesystem under this VM. `UnixFileSystem` is not in
    `clinitBlocked`, and that list cannot realistically be completed: it would have to enumerate every
    native-calling initializer in java.base.
  - **FORM 2 -- RUN EVERY INITIALIZER OUTSIDE THE BAKE DOMAIN: GETS LESS FAR, NOT FURTHER.** It reproduces
    the recorded death exactly -- an application initializer pulls SERIALIZATION and
    `java/io/ObjectStreamClass$Caches.<clinit>` NPEs in `ClassValue.<init>` via `ClassCache.<init>`. The
    launcher error-handles that into printing its USAGE and exiting.
    **THE LOG GREW (6.8 KB -> 15.7 KB) AND THAT WAS THE USAGE DUMP, NOT PROGRESS** -- measured by which JUnit
    packages were reached: the per-class build gets to `commons/util`, `console/output`, `engine/UniqueId`;
    this one stops at `ConsoleLauncher`/`console/shadow`. **Byte count is not depth.**
  - **WHAT THE EXPERIMENT ESTABLISHED, and why it was worth running:** the old gate tested a bytecode PATTERN
    (a tag-7 class literal) as a proxy for "will this initializer run", and the pattern does not predict it.
    The real distinction is that **java.base initializers call natives that do not exist here and application
    initializers do not** -- but freeing the application side alone still pulls serialization THROUGH it.
  - **THE CONCRETE NEXT STEP, not a dead end:** make serialization unreachable (deny the
    `java/io/ObjectStream*` / `ClassCache` / `ClassValue` family -- this VM carries no serialization anyway)
    and re-run Form 2. That removes the one confirmed objection; the cost objection did not reproduce
    (`metal junit: ran 44, failures 0` in ~90s with NO gate at all, and the suite ran end to end with ZERO
    `CLINIT REJECTED` lines and every arm exact).
  - **NOT MERGED.** Both forms are regressions in a different place, and shipping one would trade a known
    blocker for an unknown one.

- **`ConcurrentLinkedQueue` ON THE METAL, and a NULL REFERENCE CONCATENATES AS "null" (2026-09-11).**
  - **CLQ was FOUR STACKED GAPS, each exposed by fixing the one before.** The launcher stopped in
    `Node.<init>` (line 193) with a `DENYLIST TRAP` whose callee was EMPTY and `TRAPWIRE index=-1` -- a
    late-resolution failure, blaming a denylist CLQ is not on.
    1. **The VarHandle shim carried only two ops.** CLQ drives its ENTIRE structure through VarHandles;
       `javap` gives the exact set (`set`/`get`/`setRelease`/`compareAndSet`/`weakCompareAndSet`), added as a
       SURFACE rather than one method per boot.
    2. **They needed SEEDING** -- signature-polymorphic call sites are never seen by RTA, so the bodies would
       be pruned and leave a 0 vtable slot behind the by-name resolve.
    3. **CLQ's `<clinit>` was rejected**: it `ldc`s `Node.class` to bind its handles -- `java/net/Socket`'s
       case exactly, already allowlisted for the same reason. Skipped, `ITEM` stayed null and `Node.<init>`
       NPE'd on the first `offer()`.
    4. **`Lookup.findVarHandle` did not exist.** CLQ calls it DIRECTLY on `Lookup`; Socket reaches the same
       binding through `MhUtil`.
  - **`Magic.dsb()` IS NOT LOWERED BY THE METAL JIT from guest code** (`JIT unsupported: reason=5`, an
    unsupported intrinsic id). `setRelease` uses a `fence0` native wired to the address the Unsafe fences
    already resolve to -- a dispatch entry, no new helper. Full barrier: no release-store intrinsic exists
    here, so a one-way form could only be wrong invisibly.
  - **STATED LIMIT: the accessors are REFERENCE-typed**, and `vtableSlotOf` resolves these BY NAME ALONE, so
    one `set` serves every `set` call site whatever its descriptor -- a VarHandle over an `int` field would
    store an int as a reference. Nothing reached does that; the fix is descriptor-based resolution.
  - **A NULL REFERENCE NOW CONCATENATES AS "null" (JLS 15.18.1) -- AND IT WAS A WILD READ.** `scStr` ran
    `strBytes(0)` then read **address 16**, low firmware memory and perfectly readable. It happened to answer
    a zero length and print nothing; ANY OTHER VALUE THERE WOULD HAVE APPENDED THAT MANY BYTES OF GARBAGE.
    The empty string was luck, not a bounded failure.
  - **`ConcatDemo` HAS BEEN IN THE BOOT SUITE SINCE M2 AND NEVER CAUGHT IT**, because every arm concatenated
    NON-NULL values. A demo that exercises a feature is not one that exercises its EDGES. Found while probing
    something else entirely (`ClqDemo` printing `peek()` on an empty queue).
  - **The new arms are chosen so a LUCKY fix fails:** a null SURROUNDED by text (an append emitting nothing
    still looks right at end-of-line), TWO nulls in a row (catches a fix that emits one and stops), and a null
    BESIDE a non-null (so the fix cannot be "always print null").
  - **QEMU:** `ClqDemo` every arm exact (size 3, peek a, poll a/b/c in order, iterated 1, contains c, second
    queue x); all five null-concat arms exact IN THE SUITE; suite end to end with ZERO markers;
    `metal junit: ran 44, failures 0`; host tests unchanged incl. `compiler: 37 checks` -- the writer lowers
    concat too, so a change perturbing its codegen would break the self-hosting fixpoint.

- **A LATE CONSTRUCTOR REFERENCE BAKED A ZERO TIB -- and `ClassCastException` now NAMES BOTH SIDES
  (2026-09-11, PI-VALIDATED).** The launcher died in JUnit with a bare `ClassCastException` at
  `EngineExecutionOrchestrator.buildEngineExecutionListener`. It was not a JUnit problem.
  - **ROOT CAUSE: RTA marks a constructor reference's target instantiated at BATCH time**
    (`collectBlob`'s `mk == 8` arm), so `X::new` inside a body compiled LAZILY names a class nothing ever
    pulled. `classRegByName` answered -1 and the kind-8 thunk indexed `clTab[-1]`; baked VM code carries no
    bounds check, so it read garbage and **BAKED A ZERO TIB AS AN IMMEDIATE**, with no reloc for a later batch
    to patch -- the same trap a class literal for an unpulled class fell into. The object came back with a
    null TIB and failed a dozen frames away, INSIDE JUNIT.
  - **Fixed with the proven late-resolution pattern**: `notePullNeeded` + the pull-and-recompile-once
    `lazyCompileLocked` already runs. Deliberately NOT a demand-load inside `buildLambdaTib` -- a load parses
    every blob and can collect, and the code buffer being written is not reachable yet, which is why that was
    tried once before and reverted.
  - **HALF THIS CHANGE IS DIAGNOSTICS, and that is what made it findable.** A `ClassCastException` naming
    NEITHER side means the only way to identify the cast is to disassemble the method. It now names both, as
    stock does -- and the version that actually solved it prints the MEASURED word:
    `class <no Type; tib=0x0> cannot be cast to ...` named the mechanism in ONE read.
  - **TWO SELF-INFLICTED DETOURS, both the same mistake:** the message first went SILENT when a side had no
    registry entry (the most interesting case), then FAULTED on a raw array (whose TIB slot holds a small TAG,
    not a pointer, and must not be dereferenced). **Every wrong reading in this arc came from inferring off
    what the report did NOT say.** Print what you measured.
  - **THE PROBE HAD TO REPRODUCE THE CONDITION, NOT THE SHAPE.** A ctor-reference factory behind an erased
    generic return -- the launcher's exact shape -- PASSES, because its target is in the batch.
    `demo/CtorRefLate` is reached only REFLECTIVELY, so RTA never walks it. **NEGATIVE CONTROL: with only the
    `cr < 0` arm reverted, that demo CRASHES.**
  - **AN INSTRUMENT THAT FIRES ON A RUN THAT THEN WORKS IS WORSE THAN NONE, and I broke that rule and fixed
    it:** the ctor-ref report fired 3x on a launcher run that carried on fine. An unpulled target is the
    EXPECTED state for any late-compiled constructor reference; only one still missing AFTER the retry is a
    failure. Silent on the first attempt now.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** no ctor-ref report, no parity DIFF, no
    `LINK FAILED`, no `MIRROR CACHE FULL`; every lambda/ctor-ref arm exact (`apply(5)=105`, deep lambda
    168/1275/13, `lambda thread ran = 42`, `reflective lambda thread = 7`); **62 collections on cold DRAM**
    with `churnMB=625 live=32 intact=32` -- the real test for a fix that discards a compiled body and
    recompiles. `metal junit: ran 44, failures 0`; host tests unchanged incl. `compiler: 37 checks`.
  - **THE VINTAGE ENGINE IS DENIED** so `ServiceLoader` skips it by name (`joe-ng: service provider not
    loadable here, skipped: org.junit.vintage.engine.VintageTestEngine`). Its `JUnit4VersionCheck` does
    `new BigDecimal(version)` and **java.math does not work here** -- a SCOPE decision, and the comment marks
    the line to delete once it does.
  - **WHY java.math WAS NOT FIXED, recorded because the finding is reusable.** BigInteger/BigDecimal/
    MutableBigInteger are the HexFormat shape (assertion idiom PLUS real init), neither `clinitBlocked` nor
    seeded, so a rejection leaves their statics null for ever. Allowlisting all three WORKS and immediately
    exposes the next layer: initialising BigInteger compiles `squareToomCook3`, which REFERENCES
    `BigInteger$RecursiveOp`, so the clinit dependency drain initialises it -- and that calls
    `ForkJoinPool.getCommonPoolParallelism()`. **Regardless of operand size.** Pulling ForkJoin in behind
    BigInteger is the "an initializer pulled a whole subsystem in" trade the broad clinit rule was rejected
    for. Denying RecursiveOp then surfaced `BigDecimal.<clinit>` ITSELF reaching Toom-Cook squaring, which
    needs ~216-int operands it has no reason to build and which the host runs fine -- unexplained. **Backed
    out rather than committed half-working: an allowlist that opens a path into a trap is a landmine.**
  - **LAUNCHER: into EXECUTION SETUP.** Past discovery entirely; the ClassCastException is gone and it now
    stops at **`ConcurrentLinkedQueue$Node.<init>`** with `TRAPWIRE index=-1` and an EMPTY callee -- a
    late-resolution failure, not a denial.
  - **ALSO DIAGNOSED, QUEUED:** `DisplayNameUtils.<clinit>` `ldc`s FOUR OTHER classes
    (`DisplayNameGenerator$Standard/$Simple/$ReplaceUnderscores/$IndicativeSentences`), so neither the
    self-class-literal rule nor the bake-domain rule reaches it -- the `DefaultJupiterConfiguration` shape.
    Its statics are read unguarded when a test name is rendered.

- **`Annotation.annotationType()`, and the CLASS-MIRROR CACHE WAS OVERFLOWING AT 256 (2026-09-11,
  PI-VALIDATED).** Two defects from one `LOAD_LOG` launcher boot; the second was spotted by the USER in a log
  line I had read past while chasing the first.
  - **`annotationType()` RETURNED NULL.** The launcher NPE'd at `AnnotationUtils:339`, which `javap` places at
    `annotationType().equals(annotationType)` -- so the receiver's `annotationType()` had answered null. The
    VM's own comment stated the gap outright: the closure interfaces' itables are zero-filled because
    *"nothing here implements `Annotation.annotationType()`"*.
  - **WHY IT SURFACED AS SOMEBODY ELSE'S NPE rather than a named trap:** JUnit's `isInJavaLangAnnotationPackage`
    does `ifnull -> return false`, so the null flowed PAST its own guard and died one line later, naming a
    JUnit method instead of this gap. A null that passes through a library's null check is reported wherever
    it is finally DEREFERENCED, which can be a different class entirely.
  - **Implemented with the shape already in use:** `buildAnnoObject` stores the annotation's own Class mirror
    in ONE EXTRA TRAILING WORD, and `annotationType()` gets the same two-instruction
    `ldr x0,[x0,#off]; ret` thunk as every element accessor. No new mechanism.
  - **THE MIRROR CACHE WAS 256 ENTRIES AND THE LAUNCHER OVERFLOWED IT MID-DISCOVERY:**
    `CLASS MIRROR CACHE FULL at 0x100 ... first overflow is org/junit/jupiter/api/parallel/ResourceLock`.
    **That is not a capacity nuisance -- it SILENTLY BREAKS IDENTITY:** past the end `classMirror` mints a
    FRESH mirror per ask, so `getClass() == X.class` answers false while `getClass() == getClass()` can still
    hold by luck, and stock code compares mirrors constantly. `MAXMIRROR` is now 4096. It had been firing 550
    lines BEFORE the NPE.
  - **THE INSTRUMENT EARNED ITS KEEP AND WAS NEARLY WASTED.** This report was added during the
    interface-typed-`getClass` arc and recorded as *"never fired"*. It fired -- in a log already scrolled
    past. **An instrument only pays if its output is READ**; the reading was there and was not done.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** no `MIRROR CACHE FULL` (the changed report checked
    against a PASSING boot), `mirror identity=1` and `literal == getClass(): 1` (the two arms the overflow
    breaks), no parity DIFF, no `LINK FAILED`, **62 collections on cold DRAM** with `churnMB=625 live=32
    intact=32` -- the real test for the new pinned thunk, since a mis-rooted one is swept under pressure and
    wild-branches rather than failing an assertion. `ticks/core c1=50 c2=50 c3=50`, `finish HML` 20/20/20,
    `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK. **The 16x bigger table cost nothing
    measurable** -- collections and live/intact identical to the previous boot.
  - **QEMU:** `annoType nonNull/isTag/name/onMeth` all exact -- `isTag` is IDENTITY, which is precisely what a
    still-overflowing cache would fail. `metal junit: ran 44, failures 0`; suite clean; host tests unchanged
    incl. `compiler: 37 checks`.

- **RECORDS ON THE METAL -- `hashCode`/`equals`/`toString` SYNTHESISED from `ObjectMethods`, and the launcher
  is into METHOD-LEVEL discovery (2026-09-11, PI-VALIDATED).** `DeclaredMethodSelector.hashCode` trapped while
  Jupiter put a method selector into a `LinkedHashSet`; `javap` settles it in one line -- the body is a single
  `invokedynamic` and the class is a RECORD, bootstrapped by `java/lang/runtime/ObjectMethods`.
  - **NOT AN UNRECOGNISED FORM -- the VM already identified it and deliberately halted.** `isRecordIndy`
    matched the bootstrap class and `lowerRecordTrap` emitted a halting trap, whose comment stated the premise
    this retires: *"they are essentially never actually invoked in the paths we run"*. A record used as a SET
    ELEMENT is hashed and compared on the very first `add`.
  - **SYNTHESISED, NOT BOOTSTRAPPED**, the same choice already made for a lambda: `ObjectMethods.bootstrap`
    returns a `MethodHandle`, machinery this VM denies, so an overlay of it could not work whatever its shape.
  - **THE COMPONENTS ARE THE CLASS'S OWN INSTANCE FIELDS, and that is a GUARANTEE:** JLS 8.10.3 forbids a
    record declaring any instance field other than the private final ones corresponding to its components. So
    no filtering, declaration order IS component order, and -- the reason this increment stayed small --
    **NOTHING PER-SITE IS BAKED**: no descriptor table, no new GC root, no compile-time resolution. Everything
    is read from the object at call time through the instance-field registry.
  - **WHAT IS SPECIFIED DECIDED HOW EXACT EACH HAD TO BE, and the three differ.** `equals` is fully specified
    (same record type, every component equal) and is exact. `Record.hashCode` explicitly leaves its ALGORITHM
    unspecified, so it need only be a consistent function of the component hashes. `Record.toString`'s format
    is likewise unspecified; this is the standard rendering. **Checking which of the three were pinned was the
    design step** -- it is what made hashCode cheap and kept equals honest.
  - **A component's own `hashCode`/`equals`/`toString` is reached by resolving against THAT object's class**,
    which is safe only because those three vtable slots are now MINTED for every class ([[baked-dispatch-vtable-holes]],
    PR #237) -- before that fix a pruned slot there was a wild branch, not a call.
  - **TWO PROBE ARMS EXIST TO CATCH AN IMPLEMENTATION THAT LOOKS RIGHT:** a DIFFERENT record type with
    identical components must not be equal (an `equals` that compares components without checking the class
    passes every other arm), and varying each component ALONE must move the hash (a hash reading only the
    first component passes "equal records hash equally"). Plus the launcher's real shape -- a record in a
    HashSet, where `add` calls hashCode then equals.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** no `LINK FAILED` for the three new helpers (the
    boot-time force-compile calls all three before `launch`, so reaching `generation 12` IS that check), no
    parity DIFF, reflective demos exact, `ticks/core c1=50 c2=50 c3=50`, `finish HML` 20/20/20, inversion
    61ms, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK.
    **The suite instantiates no record**, so the boot claims NO REGRESSION and proves the helpers link;
    `AnnoProxyProbe` proves the feature -- every arm exact first run, `rec toString = Pt[x=7, name=a]`.
    `metal junit: ran 44, failures 0`; host tests unchanged incl. `compiler: 37 checks` (the writer never
    lowers an indy, so the self-hosting fixpoint cannot move).

- **`getDeclaredClasses` CONFIRMED ON THE LAUNCHER, and `getClasses` corrected (2026-09-11, PI-VALIDATED).**
  - **The fix cleared its blocker, proven rather than inferred:** `linkresolve java/lang/Class.getDeclaredClasses`
    at log lines 1539 and 1555 -- resolved and CALLED twice -- where the previous run died at line 1544 with
    `VIRTUALRESOLVE FAILED` on exactly that method. No `VIRTUALRESOLVE FAILED` anywhere in the new run.
  - **`getClasses` walked only the DIRECT interfaces** where stock inherits member classes through
    super-interfaces transitively. **Found by reading it against its sibling, not by a failure:**
    `collectInterfaceMethods` recurses and this did not. Two walks over the same relation disagreeing about
    transitivity is the half-correct-member shape this overlay keeps being bitten by.
  - **The `StreamOpFlag` -> `EnumMap` chain ran WITHOUT the recorded NPE**, consistent with that failure being
    specific to the SUITE's shared loader state rather than to the code.
  - **A `LOAD_LOG` run is ~4x slower than the quiet one and bursty** -- it sat at one line count for twenty
    minutes and was still working; measure GROWTH (bytes and lines over a fixed interval) before calling it
    stalled. Two identical readings twenty minutes apart were misleading, not evidence.

- **`Class.getDeclaredClasses` (+ `getClasses`) -- MEMBER CLASSES READ FROM `InnerClasses`, and the launcher
  reaches JUPITER'S DESCRIPTOR TREE (2026-09-11, PI-VALIDATED).** `ReflectionUtils.visitAllNestedClasses`
  trapped looking for `@Nested` test classes. Backlog 38 -> 36.
  - **NAMED BY A `LOAD_LOG` BOOT, and the resolver's work is visible in it:** the late-virtual tier walks
    `Class`'s chain (`java/lang/Object`) then its interfaces (`java/lang/reflect/Type`) before reporting
    `VIRTUALRESOLVE FAILED java/lang/Class.getDeclaredClasses()[Ljava/lang/Class;`. It surfaces as a
    `DENYLIST TRAP` with an **EMPTY callee and `TRAPWIRE index=-1`** -- the known shape for a LATE-RESOLUTION
    failure rather than a denied class.
  - **READ FROM THE `InnerClasses` ATTRIBUTE (JVMS 4.7.6), NOT THE BINARY NAME.** The nesting PREDICATES on
    `Class` do use the name and legitimately: they ask "is THIS class a member", which `Outer$Inner` answers.
    This asks the REVERSE -- "which classes are members of this one" -- and no name answers that without
    scanning the whole classDir on a javac convention the spec does not mandate.
  - **THE `outer_class_info_index` FILTER IS THE WHOLE CORRECTNESS ARGUMENT.** A class's own `InnerClasses`
    table lists every nested class it MENTIONS -- itself when nested, and others' nested classes it merely
    references -- so "declared by me" means the entry whose outer names this class. That filter also excludes
    LOCAL and ANONYMOUS classes for free, which stock excludes too: JVMS 4.7.6 requires a ZERO outer index for
    both, and a zero `inner_name_index` for an anonymous class.
  - **IT DOES NOT INITIALIZE.** Obtaining a mirror is not one of JVMS 5.5's active uses -- `forName`
    initializes because the specification says so, `getDeclaredClasses` does not. Running the initializer of
    every nested class a caller merely LOOKS at is the "an initializer pulled a whole subsystem in" hazard that
    made the broad clinit rule untenable.
  - **TWO PASSES OVER ABSOLUTE ADDRESSES**, for `classAnnotationsAll`'s reason: pass 2 RESOLVES, a resolve may
    demand-load, and a load re-parses a blob and moves the `gcp` cursor pass 1 reads.
  - **`getClasses` LANDED BESIDE IT** though nothing reached calls it: a member a name-winning overlay does not
    declare CEASES TO EXIST, and shipping half a pair is how that trap has cost a boot ten times.
  - **THE ARM THAT DOES THE REAL WORK IS `nested sees none`:** a nested class's OWN `InnerClasses` table lists
    all its siblings, so a nested class reporting zero members is what proves the outer filter rather than
    table order. Plus `noLoc = 1` (local/anonymous excluded), private members present, `ident`/`fresh`.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** no parity DIFF (Class gained two virtuals), no
    `LINK FAILED`, and **no `MEMBER CLASS NOT LOADED`** -- the new report checked against a PASSING boot.
    `ticks/core c1=50 c2=50 c3=50`, `finish HML` 20/20/20, inversion `HIGH blocked 61ms`,
    `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK.
    **The suite calls neither method**, so the boot claims NO REGRESSION; `AnnoProxyProbe` proves the feature.
    Also `metal junit: ran 44, failures 0`, host tests unchanged incl. `compiler: 37 checks`.
  - **LAUNCHER, from the same `LOAD_LOG` boot: INTO JUPITER'S `ClassBasedTestDescriptor`** -- resolving
    `@BeforeAll`/`@AfterAll`/`@BeforeEach`/`@AfterEach` in turn, with `ConditionEvaluator` and
    `InterceptingExecutableInvoker` in the closure, and the previous increments' `Class.getDeclaredAnnotations`
    /`getAnnotations` appearing as `linkresolve` lines driven by the real engine rather than a probe.
  - **`LOAD_LOG` DOES NOT REVEAL A HIDDEN FAILURE -- failures are NEVER gated.** What it buys is what the VM
    was DOING when a quiet run goes silent. A quiet run stalling at N lines is not a suppressed report.

- **`Method.getDeclaredAnnotation` + BOTH ARRAY FORMS -- `findAnnotation`'s whole element surface, taken in
  ONE increment (2026-09-11, PI-VALIDATED).** The Method-level sibling of what `Class` gained two increments
  ago, from the same recursive `AnnotationUtils.findAnnotation`, now reached with a METHOD as the element.
  Backlog 40 -> 38.
  - **THE SURFACE CAME FROM `javap` ON THE JAR, NOT FROM GUESSING WHICH METHOD TRAPPED FIRST.**
    `findAnnotation` calls exactly three methods on its `AnnotatedElement` -- `getDeclaredAnnotation`,
    `getDeclaredAnnotations`, `getAnnotations` -- and notably NOT `getAnnotation`. Fixing one method per
    launcher run is what made this family cost a ten-minute boot each time.
  - **FOR A METHOD "DECLARED" AND "PRESENT" COINCIDE EXACTLY**, so these are exact rather than approximate --
    unlike the `Class` pair, which differ by `@Inherited`. JLS 9.6.4.3 gives `@Inherited` effect on CLASS
    declarations alone, and an overriding method does not inherit the annotations of the method it overrides.
    Stock agrees by construction: `Executable.getAnnotation` reads `declaredAnnotations()`. So `getAnnotations`
    may share the declared path and diverge from stock NOWHERE -- checked, not assumed from the symmetry.
  - **THE BOUND IS LOAD-BEARING FOR THE THIRD TIME, and this time it was VERIFIED rather than trusted:**
    `javap -s` on the compiled overlay gives `(Ljava/lang/Class;)Ljava/lang/annotation/Annotation;` and
    `()[Ljava/lang/annotation/Annotation;`, byte-for-byte the descriptors the jar references. Reached by LATE
    dispatch, since `Method` does not declare `AnnotatedElement`.
  - **`methodAnnotationsAll` keeps `classAnnotationsAll`'s TWO PASSES OVER ABSOLUTE ADDRESSES**, required
    rather than copied: `buildAnnoObject` reads `gcp[..]`, the parse CURSOR, and anything that resolves during
    a build re-parses a blob and moves it. `methodInfoPos` must therefore run BEFORE pass 1, since it parses
    too. The only difference from the class version is where the attributes live (`mp + 8`, count `u2(mp + 6)`).
  - **ALLOCATION-FREE FOR A BAD INDEX, because `VM.forceCompile` probes this native with `rgIndex -1` on
    EVERY image during loader init.** Every sibling probe there returns before allocating; one that allocated
    would be the odd one out on a path that runs before `launch` on every boot.
  - **DELIBERATELY NOT ADDED TO `AccessibleObject`:** a base answering an empty array would tell an annotated
    `Field` it has none -- the silent-wrong-answer shape. Omitted, an `AccessibleObject`-typed call takes the
    late-virtual path and resolves against the receiver, so nothing is lost.
  - **`methodInfoPos` also replaces the identical method_info walk `methodAnnoPresent` and `methodAnnotation`
    each carried** -- adding a third copy is what prompted factoring it. It matches on name AND descriptor,
    which is what separates two overloads.
  - **TWO PROBE ARMS EXIST TO CATCH A WALK THAT MERELY LOOKS RIGHT:** a method carrying TWO annotations (an
    enumeration that stops after the first, or mis-steps the first's element pairs, reports 1 -- a
    single-annotation method cannot tell those apart), and defaults read through the ARRAY path, a separate
    phase from the by-descriptor one. An un-annotated method must answer an EMPTY array, never null, because
    `findAnnotation` walks it unguarded.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** no `vtparity`/`itparity` DIFF (Method's vtable
    gained three slots), no `LINK FAILED` for the new native, and the reflective demos exact
    (`overloads named pick = 3`, `invoked = none int:7 two:42`, `reflective = unseen`,
    `reflective lambda thread = 7`, `ifacedfltch = late-default`). `ticks/core c1=50 c2=50 c3=50`,
    `finish HML` 20/20/20, inversion `HIGH blocked 61ms`, `churnMB=625 live=32 intact=32`,
    `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK (829 bytes). **The suite calls none of these
    three methods**, so the boot claims NO REGRESSION and proves the native links; `AnnoProxyProbe` on QEMU
    is what proves the feature -- every arm exact first run. Also `metal junit: ran 44, failures 0`, host
    tests unchanged incl. `compiler: 37 checks`.
  - **`scripts/sdcard.sh` BUILDS FROM THE COMMITTED MANIFEST, so flashing straight after restoring it gives a
    `demo/NetDemo` IMAGE, NOT THE SUITE** -- and that cost a Pi boot here. The suite image is the NO-MANIFEST
    build: empty `ramfs/etc/init`, build, restore. Sizes are the tell (suite 33,229,960 vs NetDemo
    33,230,008), and a NetDemo boot goes straight to WiFi with no `generation 12`.
  - **A `bytes=0` ON THAT NetDemo BOOT WAS NOT THIS CHANGE AND NOT A STANDING FAILURE:** the suite's WiFi
    finale, which uses the all-Java `net.Tcp` stack rather than stock `Socket`, pulled the full 829 bytes on
    the very next boot, with DNS returning a different Cloudflare anycast address.

- **`Class.getMethods` -- the launcher reaches `IsTestableMethod.test`, i.e. DECIDING WHETHER A METHOD IS A
  `@Test` (2026-09-10).** `ReflectionUtils.getDefaultMethods` needs the PUBLIC methods of a class including
  inherited ones; the overlay had only `getDeclaredMethods`. Backlog 41 -> 40.
  - **The dedupe key is built at ENUMERATION time, not from a `Method`**, and that is forced rather than
    stylistic: a `Method` keeps only the FIRST CHARACTER of each parameter type (`paramChars`) -- enough to
    marshal a call, not enough to tell two overloads apart. The enumeration natives hand back the full
    descriptor, so name+descriptor is available there and nowhere else.
  - **Most-derived-first is what makes overriding correct**: the subclass's method is seen first and the
    superclass's is skipped as a duplicate, with no override test needed. Interfaces are walked after the
    class chain, transitively, which is where DEFAULT methods live.
  - **Constructors and `<clinit>` are filtered by name.** The VM's enumeration is the method REGISTRY's view
    and lists them; stock's `getMethods` does not.
  - **QEMU:** `AnnoProxyProbe` arms exact -- non-empty, the INHERITED `Object.hashCode` present (a
    receiver-only enumeration would miss it), `toString` appearing ONCE rather than once per class in the
    chain, no constructors, and `Iterable.forEach` reachable through `ArrayList` (the default-method path).
    Demo suite end to end, `metal junit: ran 44, failures 0`, host tests unchanged incl. `compiler: 37 checks`.
  - **NEXT BLOCKER: `java/lang/reflect/Method.getDeclaredAnnotation`** -- the Method-level sibling of what
    `Class` just gained, from the same `AnnotationUtils.findAnnotation`, now reached with a METHOD rather
    than a class as the element.

- **`DefaultJupiterConfiguration.<clinit>` RUNS -- and the broad clinit rule was RE-TESTED and rejected on a
  CHECKED cause this time (2026-09-10).** Its initializer `ldc`s OTHER classes
  (`ExecutionMode.class`, `TestInstance$Lifecycle.class`, handed to
  `EnumConfigurationParameterConverter`), so the self-class-literal rule cannot reach it, and
  `validateConfigurationParameters` reads `UNSUPPORTED_CONFIGURATION_PARAMETERS` UNGUARDED -- a skipped
  initializer is an NPE the moment the Jupiter engine configures itself.
  - **I RE-RAN THE BROAD RULE, BECAUSE MY EARLIER REASON FOR REJECTING IT WAS NOT SOUND.** I had recorded
    "~4x longer, stalled" from a SIXTEEN-MINUTE observation -- but 4x on an already-ten-minute boot needs
    ~40 minutes, so that observation could not tell "hung" from "still going". It was not hung: the run
    recovered and reached line 286.
  - **The real objection is CORRECTNESS, and it only showed up on the long run.** Allowing every tag-7
    outside `java/`/`jdk/`/`sun/` does clear all ten rejected initializers at once and the launcher does get
    further -- and then it dies inside `java/io/ObjectStreamClass$Caches.<clinit>` -> `ClassCache.<init>`,
    because an application initializer pulled SERIALIZATION in. That is a subsystem this VM deliberately does
    not carry, so the broad rule trades ten null statics for a whole failing subsystem.
  - **A watchdog that looked like a hang was not one:** `LOADER LOCK stuck >10s` said **`state 1`**
    (`TASK_READY`) rather than the usual `state 4` (`TASK_RUNNING`), which looked like the owner starving --
    and it recovered on its own. Read the state field before calling it a deadlock.
  - **So: one targeted entry, with the measurement recorded beside it.** The general narrowing may still be
    right, but it needs the serialization pull solved first.
  - **LAUNCHER: past it, through Jupiter's descriptor and discovery classes.** Next blocker is an ordinary
    overlay gap: **`VIRTUALRESOLVE FAILED java/lang/Class.getMethods()[Ljava/lang/reflect/Method;`** from
    `ReflectionUtils.getDefaultMethods` -- the public-methods-including-inherited sibling of
    `getDeclaredMethods`, which already exists.
  - **QEMU:** demo suite end to end, `metal junit: ran 44, failures 0`, host tests unchanged incl.
    `compiler: 37 checks`.

- **A LATE INTERFACE DEFAULT NOW SEARCHES THE WHOLE SUPERCLASS CHAIN -- and the launcher reaches the JUPITER
  ENGINE (2026-09-10).** `resolveViaInterfaces` collected only the RECEIVER's directly-declared interfaces,
  then those interfaces' own super-interfaces. For a receiver that implements nothing itself that list is
  EMPTY, so the search had nothing to look in and reported the method missing.
  - **JUnit's shape, read from the classfiles rather than guessed:** `SuiteEngineDescriptor extends
    EngineDescriptor extends AbstractTestDescriptor implements TestDescriptor`, with `accept` a DEFAULT on
    `TestDescriptor` -- two classes up. Only `AbstractTestDescriptor` implements anything.
  - **A LOOKUP THAT DOES NOT WALK THE CHAIN HAS NOW BEEN THE BUG THREE TIMES** here: `globalVtableSlot`'s slot
    numbering, `vhFieldOffset`'s field offsets, and this. The collected names are ABSOLUTE Utf8 addresses,
    which is what makes the walk safe -- `collectIfaceNames` re-parses each class's constant pool and moves
    the cursor, and an offset would then be read against the wrong blob.
  - **THE FIRST REGRESSION DEMO I WROTE TESTED NOTHING, AND THE NEGATIVE CONTROL SAID SO.** A three-deep
    chain in `DefaultIfaceDemo` passed with the fix REVERTED: instantiated directly, the default resolves
    through the ordinary itable and never reaches the fallback at all. Reproducing the SHAPE is not
    reproducing the CONDITION -- the condition is a LATE resolve, which this family creates by reaching the
    code REFLECTIVELY so RTA never pulls the interface.
  - **The real test is `ifacedfltch`** (`RtaChainLeaf extends RtaChainMid extends RtaChainBase implements
    RtaLate`, reached reflectively, in `demo/ReflectRtaDemo` which the suite already runs). Reverted, it
    fails with exactly `VIRTUALRESOLVE FAILED demo/RtaChainLeaf.viaDefault()Ljava/lang/String;` while the
    existing one-level `ifacedflt` arm still passes -- which is precisely why the one-level arm never caught
    this.
  - **LAUNCHER: through the Suite engine and into the JUPITER ENGINE** -- the engine that actually runs
    `@Test` methods. Next blocker: `CLINIT REJECTED ... org/junit/jupiter/engine/config/DefaultJupiterConfiguration`
    and then an NPE in its `validateConfigurationParameters` -- a null static, but NOT covered by the
    self-class-literal rule, so its initializer names some OTHER class.
  - **QEMU:** `ifacedflt` and `ifacedfltch` both `late-default`; demo suite end to end, `metal junit: ran 44,
    failures 0`, host tests unchanged incl. `compiler: 37 checks`.

- **`Class.getDeclaredAnnotations` + `getInterfaces` -- JUnit's ANNOTATION SEARCH COMPLETES (2026-09-10).**
  `AnnotationUtils.findAnnotation` walks a class's own annotations, then its META-annotations, then its
  interfaces and enclosing class. All of it runs now; the launcher is out of `IsSuiteClass` entirely and into
  the Suite engine's selector resolution. Backlog 43 -> 41.
  - **The enumeration is TWO PASSES over ABSOLUTE addresses, and that is not tidiness.** Pass 1 records each
    entry's type descriptor and element-pair position as blob addresses; pass 2 builds. `buildAnnoObject`
    reads `gcp[nameIdx]` -- the parse CURSOR -- and anything that resolves during a build re-parses a blob and
    moves it. Blob addresses do not move; the parse state does. Same reason the written-pair and default
    phases are kept apart, and the reason a `'c'`/`'e'` element defers.
  - **An annotation whose INTERFACE is not loaded is omitted, not returned as a null element** -- there is no
    itable to give such an instance, and a null in the array reads to the caller as "present but broken". The
    existing report names it.
  - **`getInterfaces` returns the DECLARED interfaces, not the closure** -- what stock returns, and what
    `findAnnotation` needs: it recurses into each interface itself, so a flattened closure would make it walk
    the same interfaces repeatedly.
  - **WHAT AVOIDED THREE MORE TEN-MINUTE BOOTS:** rather than fix one method per launcher run, the recursive
    `findAnnotation` was disassembled and its whole `Class` surface listed --
    `getEnclosingClass`/`getSuperclass`/`isAnnotationPresent` were already present and `getInterfaces` was the
    only other gap. Evidence, not speculation: the list came from the bytecode.
  - **QEMU:** `AnnoProxyProbe` exact -- `declaredAnnos len = 1`, element is a `Tag` with value `onclass`,
    `getAnnotations` agrees, a bare class answers 0, a FRESH array each call, `ArrayList ifaces = 4` with
    `List` among them. Demo suite end to end, `metal junit: ran 44, failures 0`, host tests unchanged incl.
    `compiler: 37 checks`.
  - **NEXT BLOCKER, a DIFFERENT family:** `VIRTUALRESOLVE FAILED
    org/junit/platform/suite/engine/SuiteEngineDescriptor.accept(...)` from
    `DiscoverySelectorResolver.resolveSelectors` -- a JUnit class's OWN method failing to resolve, not an
    overlay gap.

- **`Class.getDeclaredAnnotation` -- and a spec-checked correction to my own probe (2026-09-10).** The
  `Class` overlay did not declare it, so JUnit's `AnnotationUtils.findAnnotation` trapped with
  `VIRTUALRESOLVE FAILED java/lang/Class.getDeclaredAnnotation(...)`. Backlog 44 -> 43.
  - **The native already had DECLARED semantics**, so this is exact rather than approximate: `classAnnotation`
    reads THIS class's own `RuntimeVisibleAnnotations` and nothing else. In stock the two differ only by
    `@Inherited`, which means it is `getAnnotation` that diverges here, not this method -- stated in the code.
  - **THE BOUND IS LOAD-BEARING, for the third time.** `<T extends Annotation>` erases the return to
    `Ljava/lang/annotation/Annotation;`, which is the descriptor stock references. And it matters more than
    usual here: `Class` does NOT declare `AnnotatedElement` (recorded in the overlay baseline), so JUnit's
    interface-typed call arrives by LATE dispatch against the receiver's class and resolves only on an exact
    name+descriptor match.
  - **A PROBE ARM OF MINE ASSERTED SOMETHING THE SPEC DOES NOT GUARANTEE, and the spec is what settled it.**
    `declared == getAnnotation(...)` came back 0, which looked like a bug; `Annotation`'s specification
    defines equality by ELEMENT VALUES ("an instance of the same annotation interface ... all of whose members
    are equal") and says nothing about identity across calls. Stock caches instances so identity happens to
    hold there. The arm was wrong, not the VM -- corrected to assert the values.
  - **It did expose a REAL gap, now recorded rather than papered over:** joe-ng's annotation objects inherit
    Object's IDENTITY equals, so two instances with equal members compare UNEQUAL, which the specification
    forbids. Implementing it needs element ENUMERATION -- `equals`, `hashCode` and `toString` all do -- and
    the annotation runtime can find one element by name but cannot walk them. **OPEN.**
  - **LAUNCHER: six lines further into the same method**, and the next blocker is that same enumeration:
    **`VIRTUALRESOLVE FAILED java/lang/Class.getDeclaredAnnotations()[Ljava/lang/annotation/Annotation;`** --
    the PLURAL, which `findAnnotation` uses to walk META-ANNOTATIONS. It needs a native that iterates the
    class's `RuntimeVisibleAnnotations` and builds an instance per entry.
  - **QEMU:** `AnnoProxyProbe` arms exact (declared present, value `onclass`, absent null); demo suite end to
    end, `metal junit: ran 44, failures 0`, host tests unchanged incl. `compiler: 37 checks`.

- **AN APPLICATION CLASS MAY `ldc` ITS OWN CLASS IN `<clinit>` -- the LOGGER IDIOM, and the launcher reaches
  TEST DISCOVERY (2026-09-10).** `EngineDiscoveryOrchestrator.discoverSafely` NPE'd on `logger.debug(...)`,
  called UNGUARDED, because the class's initializer had been skipped and `logger` stayed null. The VM had
  already said so: `CLINIT REJECTED (statics stay null): .../EngineDiscoveryOrchestrator`.
  - **TEN application classes in that closure were rejected for the same three instructions:**
    `ldc Own.class; invokestatic LoggerFactory.getLogger; putstatic logger`. One allowlist entry per class
    does not scale, and the list was already eight long.
  - **The rule is now: a tag-7 literal naming THIS CLASS is allowed, outside the bake domain.** A class
    naming ITSELF pulls nothing new -- it is already being loaded -- which is exactly what separates this
    from the broader rule I tried and backed out two increments ago (allow ALL tag-7 outside
    `java/`/`jdk/`/`sun/`), which made the launcher run ~4x longer without reaching where it already was. The
    closures come from initializers naming OTHER classes.
  - **Deliberately NOT flagged as the assertion idiom:** that flag exists to reject "idiom PLUS real work",
    which is right inside java.base -- those classes are `clinitBlocked` or seeded instead -- and wrong for an
    application class, where the real work IS the point and there is no substitute for running it.
  - **The bake domain keeps the old rule**, because there the premise the gate was written on still holds.
  - **LAUNCHER: INSIDE TEST DISCOVERY NOW** -- `EngineDiscoveryOrchestrator` -> `ClassSelectorResolver.resolve`
    -> `IsSuiteClass.test` -> `AnnotationSupport.isAnnotated`, i.e. the Suite engine inspecting the selected
    class through a real stream pipeline. Next blocker is an ordinary overlay gap:
    **`VIRTUALRESOLVE FAILED java/lang/Class.getDeclaredAnnotation`**, which the `Class` overlay does not
    declare (the annotation runtime behind it already exists -- `getAnnotation` is implemented).
  - **QEMU:** no application-class `CLINIT REJECTED` left in the launcher closure (34 remain, all
    `java/`/`jdk/`/`sun/`); demo suite end to end, `metal junit: ran 44, failures 0`, host tests unchanged
    incl. `compiler: 37 checks`.

- **A SYNTHESISED LAMBDA'S TIB CARRIES `java/lang/Object`'s VTABLE NOW (2026-09-10).** It was ONE WORD --
  the Type and no vtable at all -- on the premise, stated in its own comment, that "a lambda is only ever
  type-checked through its interface dir". An `Object` method on a lambda receiver therefore resolved
  NOWHERE: not in the class registry, and not in the itable directory, which holds only interface methods.
  The launcher hit it as `DISPATCH ON UNREGISTERED TYPE ... equals(Ljava/lang/Object;)Z` from
  `Stream.sorted()` -> `SortedOps$OfRef.<init>`.
  - **THE MISSING REGISTRY ENTRY IS NOT THE BUG, AND THE SPEC SAYS SO.** A real JVM's lambda class is a
    HIDDEN class (`LambdaMetafactory` uses `Lookup.defineHiddenClass`), whose specification states it "is not
    discoverable by `Class.forName`... `ClassLoader.loadClass`... or `findClass`" and "does not have a binary
    name, so there is no internal form available to record in any class's constant pool". joe-ng's lambda
    having no `clTab` entry MIRRORS that. What HotSpot still gives it is a full method table rooted at
    Object; only lookup BY NAME is suppressed. This VM had conflated "unregistered" with "no vtable".
  - **JVMS specifies the behaviour, not the layout:** selection picks the maximally-specific override in the
    receiver's hierarchy, which is rooted at Object -- so `equals` on a lambda MUST select `Object.equals`.
    Identity semantics are right here: `LambdaMetafactory` specifies the identity of a captured function
    object as "unpredictable", warning callers off depending on it.
  - **ANNOTATION TIBs GET THE SAME PREFIX, and that is required rather than opportunistic:** they are
    synthesised the same way (`allocData(16)`, unregistered, itable-only) and share the `lambdaTibRoots`
    array, so a single refill pass over that array is only SOUND if every entry has the same shape.
  - **The refill is capacity-bounded**, via a new parallel `lambdaTibVtCap`. A TIB built before
    `java/lang/Object` is registered reserves ZERO slots, and refilling it blind once Object arrives with
    nine virtuals would write past an 8-byte allocation. Same repair as `refillArrayTibVtables` -- **which is
    where this whole shape was solved once already**, for array TIBs, with the identical symptom recorded:
    "without the vtable the dispatch read past a 1-word TIB and BLR'd garbage".
  - **A SUITE-ONLY REGRESSION CAUGHT AN ARM THAT DID NOT BELONG.** The `Stream.sorted()` arm -- the
    launcher's own trigger -- passes when the demo is launched ALONE and NPEs in the suite:
    `StreamOpFlag.<clinit>` -> `EnumMap.<init>` -> `getKeyUniverse`, i.e.
    `SharedSecrets.getJavaLangAccess()` reads null in the suite's SHARED loader state. Pre-existing and
    unrelated to dispatch; the arm was removed from the suite (with the finding recorded beside it) rather
    than the suite being made to carry the whole stream pipeline. **OPEN.**
  - **QEMU:** the five Object-method arms on a lambda receiver exact (`equals` self 1 / other 0, stable
    hash, non-null `toString`/`getClass`), demo suite end to end, `metal junit: ran 44, failures 0`, host
    tests unchanged incl. `compiler: 37 checks`.
  - **LAUNCHER: past it, into ENGINE DISCOVERY** -- `DefaultLauncher.discover` ->
    `EngineDiscoveryOrchestrator.discover` -> `discoverSafely`, where it NPEs (line 171).

- **A SERVICE PROVIDER THIS VM CANNOT LOAD IS SKIPPED AND SAID SO, NOT HALTED ON (2026-09-10).** JUnit's
  `OpenTestReportGeneratingListener` is on the `TestExecutionListener` services path, and its CONSTRUCTOR
  calls `java/nio/file/Path.of` -- denied, because there is no filesystem under this VM. A denylist trap
  HALTS, so stock's "a provider that fails to construct throws `ServiceConfigurationError`" never got a
  chance to run.
  - **Three changes, and the ORDER of the failure is the point.** `org/junit/platform/reporting/` is
    denylisted, which moves the failure EARLIER -- from a trap at construction to a
    `ClassNotFoundException` at `Class.forName`, where a caller can handle it.
  - **`Class.forName` HONOURS THE DENYLIST NOW, and it did not before.** `pullClass` goes straight to the
    classDir -- the same bypass already recorded for the deferred-`new` path -- so forName loaded a denied
    class happily and the denial only bit later, at a trap-wired call site, where it halts. A denied class is
    NOT FOUND, which is the truth: it is deliberately absent.
  - **The `ServiceLoader` overlay SKIPS an unloadable provider and REPORTS it by name.** Stated divergence
    from stock, which aborts the whole service: on a VM where some classes are deliberately absent, that
    abort makes every service containing one such provider unusable, which is a worse answer than running the
    rest. Only an ABSENT class is skipped -- a class that loads but is not a subtype is still a
    `ServiceConfigurationError`, because that is a real configuration mistake rather than a missing
    capability.
  - **`stream()` now resolves at build time rather than on first `type()`**, and that costs nothing on the
    path it exists for: `ServiceLoaderUtils.filter` calls `type()` on every provider anyway. The laziness
    that is load-bearing -- not CONSTRUCTING a provider that is then filtered out -- is untouched.
  - **MY FIRST ATTEMPT WAS WRONG AND THE PROBE CAUGHT IT.** Denying the package alone changed nothing:
    `listeners count = 2` with no skip line, because forName does not consult the denylist. The arm that
    pinned the COUNT is what said so -- an arm that only checked "no crash" would have passed.
  - **QEMU:** `ServiceLoaderProbe` exact -- the skip line names the provider, `listeners count = 1`, the
    survivor (`UniqueIdTrackingListener`) resolves AND constructs, and engines/parsers are unaffected (3 and
    13). Demo suite end to end, `metal junit: ran 44, failures 0`, host tests unchanged incl.
    `compiler: 37 checks`.
  - **LAUNCHER: past the halt and into DISCOVERY.** It now stops on a different family:
    **`DISPATCH ON UNREGISTERED TYPE ... equals(Ljava/lang/Object;)Z`** with `synthesised(lambda/anno TIB)=1`
    -- an `Object` PUBLIC method invoked on a synthesised LAMBDA receiver, from
    `Stream.sorted()` -> `SortedOps$OfRef.<init>`. A lambda has a Type and an itable directory but no `clTab`
    entry, so neither the registry tier nor `resolveViaItableDir` can answer, and `equals` is not an
    interface method for the directory to hold. The likely shape of the fix is the one the interface-typed
    `getClass` bug took: resolve an Object public method against `java/lang/Object` itself.

- **A LAMBDA IN A DEEP-STACK METHOD COMPILES NOW -- and the launcher reaches REAL `ServiceLoader`
  DISCOVERY THROUGH A STREAM PIPELINE (2026-09-10).** `lowerLambda` began
  `if (deepStack) { fail(FAIL_OPCODE, 0xBA, 3); return; }` -- a flat refusal, with a `TODO` for the reason.
  - **The whole of the incompatibility was ONE LINE:** the capture store used a raw `OP_BASE + slot` register
    number, valid only while every operand is resident. Past `OP_MAX = 7` the operand stack lives in FRAME
    MEMORY, and `opSlot(slot)` is the accessor that works in both worlds -- it loads the slot's memory home
    into a circular-window register, spilling whatever it evicts. `lowerConcat` had been using it all along.
  - **Shallow codegen is BYTE-FOR-BYTE UNCHANGED**, because `opSlot` returns exactly `OP_BASE + slot` when
    the method is shallow -- which is what `compiler: 37 checks` passing asserts, the self-hosting fixpoint.
  - **THE DEMO WAS VERIFIED TO REACH THE MODE, WITH A NEGATIVE CONTROL, BEFORE BEING TRUSTED.** Seven or
    fewer live operands stay in registers and compile the shallow way, so a probe that did not pass `OP_MAX`
    would test NOTHING. With the refusal temporarily restored, `demo/DeepLambdaDemo` fails with exactly
    `JIT unsupported: reason=0 a=0xBA b=3 in demo/DeepLambdaDemo`; with it removed, all three arms are exact
    (`168`, `1275`, and an OBJECT capture `13` -- a wrong slot there is a bad reference, not a bad int).
    It is in the boot suite, so it is Pi-gated.
  - **LAUNCHER: `ServiceLoader` discovery is running FOR REAL** -- `ServiceLoader$Lazy.get` ->
    `Constructor.newInstance`, driven through `ReferencePipeline` (the stream path this VM synthesises), which
    is the overlay written two increments ago being exercised end to end rather than by a probe.
  - **NEXT BLOCKER, AND IT IS A SCOPE BOUNDARY RATHER THAN A BUG:** one discovered `TestExecutionListener`
    provider, `OpenTestReportGeneratingListener`, calls `java/nio/file/Path.of` in its CONSTRUCTOR -- and
    `java/nio/file/` is denylisted because there is no filesystem under this VM. A denylist trap HALTS, so
    stock's "a provider that fails to construct throws `ServiceConfigurationError`" never gets a chance.
    Choosing between denying the reporting package (so `Class.forName` fails cleanly and this overlay's
    `ServiceLoader` can SKIP the provider, a stated divergence from stock) and making a denylist trap
    throwable is a policy decision, not a mechanical fix.
  - **QEMU:** demo suite end to end with the three deep-lambda arms exact, `metal junit: ran 44, failures 0`,
    host tests unchanged incl. `compiler: 37 checks`.

- **THE CONSOLE LAUNCHER RUNS TO COMPLETION AND RENDERS ITS FULL USAGE TEXT (2026-09-09).** With the literal
  decode in, picocli's whole word-wrapped help -- every option, every description, the SELECTORS section --
  prints on bare metal. The wrap path that had been the blocker for three increments is closed.
  - **`ReflectionUtils.<clinit>` WAS SKIPPED, so `classNameToTypeMap` was null** and every
    `tryToLoadClass` NPE'd -- which is what aborted `execute` after the launcher had otherwise run. The VM
    named it outright: `CLINIT REJECTED (statics stay null): org/junit/platform/commons/util/ReflectionUtils`.
    Its body `ldc`s class literals (its own class for `getLogger`, and `"[Z".."[Ljava/lang/String;"` for the
    map), which the tag-7 gate rejects.
  - **Everything that initializer needs now exists**, which is why allowing it is safe today and would not
    have been a month ago: `getLogger` (java.util.logging provided), `Pattern.compile`,
    `ClasspathScannerLoader` via `ServiceLoader`, `ConcurrentHashMap.newKeySet`, and array/primitive class
    literals.
  - **A BROADER FIX WAS TRIED AND REJECTED ON MEASUREMENT, NOT ARGUMENT.** The gate's own comment says a
    rejected initializer is "clinitBlocked/seeded anyway" -- true in the bake domain, FALSE for an application
    class, where rejecting guarantees null statics for ever. Allowing tag-7 outside `java/`/`jdk/`/`sun/`
    therefore looked principled, and it is the same gap `HexFormat` fell into. Measured, it made the launcher
    run **~4x longer without reaching the point it had already reached** -- stalled at one log position for
    ten minutes at 299% CPU. Running every application initializer in a ~900-class closure is a different cost
    class. The narrowing may still be right; it needs its own increment with that cost understood.
  - **NEXT BLOCKER, NAMED:** `JIT unsupported: reason=0 a=0xBA b=3 in
    org/junit/platform/commons/util/ClasspathScannerLoader` -- `0xBA` is `invokedynamic`, an indy form the JIT
    cannot lower (neither a lambda nor a string concat). Reached only now, because ReflectionUtils'
    initializer runs and calls `ClasspathScannerLoader.getInstance()`.
  - **QEMU:** demo suite end to end (`lisp evals=600 result=610 stable=1`, charset arms exact, newKeySet, the
    interface arms, `LoggingDemo done`), `metal junit: ran 44, failures 0`, host tests unchanged incl.
    `compiler: 37 checks`.

- **NON-ASCII STRING LITERALS ARE NEVER DECODED FROM MODIFIED UTF-8 -- the root of the picocli blocker,
  MEASURED (2026-09-09).** `Loader.internString` copies a `CONSTANT_Utf8` body VERBATIM into the String's
  `value` byte[] and leaves `coder` LATIN1. A classfile stores literals in MODIFIED UTF-8, so every non-ASCII
  literal has the wrong LENGTH and the wrong CONTENTS:

  ```
                 host        metal
  "\u00ff"      len 1, 255   len 2, first char 195 (0xC3)
  "\u00e9"      len 1        len 2
  "\u20ac"      len 1        len 3
  "abc"         len 3        len 3   (ASCII is unaffected, which is why nothing noticed)
  ```

  - **How picocli exposed it.** `TextTable.copy` word-wraps by handing
    `text.plainString().replace("-", "\u00ff")` to a `BreakIterator` -- a deliberate trick to stop breaks
    after a hyphen -- and then slices the TEXT with the boundaries that come back. On joe-ng the replacement
    is TWO characters, so the replaced string grows by one per hyphen, every boundary past a hyphen shifts
    right, and `Text.substring(start, end)` produces a length that runs off the end of the shared `plain`
    buffer: **`plain.substring(33, 42)` with `count = 39`** -- exactly three hyphens' worth.
  - **FOUND BY REDUCTION, and the intermediate probe's PASSING is part of the evidence.** `TextProbe` drives
    `Ansi.Text` directly and every arm -- construction, `substring`, `append`, `concat`, `getStyledChars` --
    matches the host EXACTLY. So Text's arithmetic is sound and the fault is upstream of it. That is
    "reproducing the shape is not reproducing the condition" again: the failing sequence needs the
    `replace`, and the replace needs a non-ASCII literal.
  - **A temporary recorder in `StringBuilder` named the site in one boot** -- `site=3 (substring) a=33 b=42
    count=39` -- after an earlier print-based instrument in the same method DID NOT FIRE. **Recorded, not
    printed:** this class is used BY the printing path, so printing from inside a failed bounds check can
    recurse or perturb the state being reported; a probe read the statics from outside. Removed after use.
  - **I wrongly suspected the frame attribution** when the print instrument stayed silent, because the trace
    carries two `<unclaimed pc ... after putValue>` frames. The recorder showed the frame was RIGHT all
    along -- the print, not the attribution, was the broken thing.
  - **FIXED: `internString` DECODES.** One character at a time (1-byte, 2-byte `C0..DF`, 3-byte `E0..EF`),
    LATIN1 when every unit fits a byte and UTF16 otherwise, with `internStringObj` now writing `coder = 1`
    for the UTF16 case -- which it never did. A malformed sequence keeps its lead byte rather than consuming
    bytes it cannot verify: a decoder that ran off the end of a literal would read whatever followed it in
    the blob.
  - **Modified UTF-8, not standard, and the difference is what makes this simple:** a character outside the
    BMP is encoded as its two SURROGATES separately, so decoding them individually is exactly what a UTF16
    String wants. NUL is `C0 80`, which the 2-byte branch already handles.
  - **The UTF16 path only works because `StringUTF16.LO_BYTE_SHIFT` is SEEDED to 8** -- its `<clinit>` asks
    Unsafe for the byte order and cannot run here, and the existing seed's comment already records the symptom
    (the euro read back as `0xAC`). Checking that before writing the code is what made the full fix safe
    rather than a LATIN1-only subset.
  - **QEMU, every arm now matching the host exactly:** `"\u00ff"` len 1 char 255, `"\u00e9"` len 1,
    `"\u20ac"` len 1 (the UTF16 path), `replace sameLen = 1`, `loop inBounds = 1` -- and **picocli's usage
    RENDERS**, option table byte-for-byte identical to the host including the wrapped hyphenated description.
    Regression: demo suite end to end with the charset arms exact (`out latin1: é`, `euro len=1 char=8364`,
    `out utf16: €` -- the surface most sensitive to this change), `lisp evals=600 result=610 stable=1`,
    `metal junit: ran 44, failures 0`, host tests unchanged incl. `compiler: 37 checks`.
  - **NOT changed: the WRITER's literal interning**, which lays out image literals as ASCII bytes. No baked
    literal in the closure is non-ASCII today, so nothing is wrong now -- but it is the same bug waiting, and
    it would surface exactly as this one did.

- **THE SYSTEM PROPERTIES WERE NEVER SEEDED IN MOST CLOSURES -- two library NPEs, one cause (2026-09-09).**
  `seedStandardProps` found `Properties.setProperty` through `methodResolveRegistry`, and **`rgTab` holds one
  entry per COMPILED method** while bodies compile on FIRST CALL. This runs during loader init, so nothing had
  called `setProperty` and it was not in the registry at all -- the lookup could only ever succeed **by
  accident**, in a closure that had already compiled it for some other reason. The launcher image had, and
  worked; a smaller one left EVERY property null.
  - **It surfaced as two unrelated-looking failures far from the VM:** picocli's `Ansi.isWindows()` does
    `System.getProperty("os.name").toLowerCase()` and NPEs, and its `trimLineSeparator` does
    `result.endsWith(System.getProperty("line.separator"))` and NPEs. Same cause; neither names the VM.
  - **SIX SILENT `return`s ON THAT PATH NOW SAY WHY** (`SYSTEM PROPERTIES NOT SEEDED: <which step>`). An empty
    property map does not fail where it is created. **My first report conflated three conditions under one
    message** ("has no compiled body") and was itself an unchecked assertion -- split, it said
    `setProperty is not registered`, which is the actual cause and points somewhere else entirely.
  - **Resolved through `bufBySigU`/`compileSigOnDemand` instead of the registry**, with a new `utf8Blob`
    helper: those resolvers take CLASSFILE-SHAPED pointers (u2 length, then bytes) while VM-side code holds
    names as plain `byte[]`, and passing `Magic.addrOf(Magic.bytes(...))` reads the first two characters as a
    length -- a mistake made once before, in a class-chain fallback removed before it shipped.
  - **`seedSystemProps` MOVED TO LAST OF THE SEEDS, and that half is load-bearing.** It is the only seed that
    COMPILES a method, and compiling rebuilds the loader's cursor (`gcp`/`gbase`/`gStatics`) for
    `java/util/Properties` -- so every seed after it stood on the wrong class's state. Left in its old
    position the fix made things WORSE: the probe's own `System.out.println` calls produced nothing at all.
    Same hazard already recorded for demand-loading inside `buildLambdaTib`, reached from a new direction.
  - **`test/jdk/junit/UsageProbe` reproduces the launcher's picocli blocker in ~5 minutes instead of ~12**, and
    it renders with `Ansi.OFF` **because that is the launcher's CONDITION** (it runs with
    `--disable-ansi-colors`). With ansi AUTO the probe stopped on a DIFFERENT bug the launcher never reaches,
    which would have proved nothing about the target. The HOST CONTROL renders the same command perfectly, so
    the wrap path itself is not picocli being odd.
  - **STILL OPEN, and the next thing to chase:** `Text.getCJKAdjustedLength` -> `plain.substring(from,
    from + length)` throwing with `start = 33` (22 in the launcher), i.e. picocli's shared `plain` is shorter
    than its own `from`/`length` claim. **An instrument printing start/end/count inside that exact throw did
    NOT FIRE while the trace still named the method** -- unexplained, and worth resolving before trusting the
    frame attribution. `StringBuilder.append(CharSequence)`, `append(CharSequence,int,int)`, `put`,
    `setLength` and `charAt` all read correct.
  - **QEMU:** `prop os.name = joe-ng`, `line.separator` non-null, `System.lineSeparator()` non-null, absent
    property still null; demo suite clean with every new arm exact (`newKeySet`, the interface-typed arms,
    `LoggingDemo done`, `churnMB=625 live=32 intact=32`) and no `SYSTEM PROPERTIES NOT SEEDED` line; host
    tests unchanged incl. `compiler: 37 checks`.

- **`java.util.logging` PROVIDED -- the blocker that was neither a denial nor a dropped overlay member
  (2026-09-09).** `Logger`/`Level`/`LogRecord` live in the **`java.logging` MODULE**, and joe-ng's image
  carries only `java.base` -- so the classes were **ABSENT ENTIRELY**. The report said exactly that
  (`LINK FAILED ... class not in the classDir (nothing can load it)`), which is a THIRD diagnosis distinct
  from `DENYLISTED` and from the overlay-drops-stock-members trap, and it named the cause with no boot spent
  narrowing it.
  - **This is a PROVISION, not an overlay**, and the distinction is load-bearing for the standing
    guestsrc rule: that rule governs SHADOWING a stock class, and there is no stock class here to shadow.
    `make overlaycheck` confirms it independently -- 0 new gaps and 0 new dropped supertypes, because the
    tool finds nothing to diff against.
  - **`java/` is already on `ImageBuilder.demandLoadable`**, so no writer change was needed; javac accepts
    `--patch-module java.base=guestsrc` for a package owned by another module, which was the one structural
    risk and was checked with a throwaway compile before any of it was written.
  - **IT IS DELIBERATELY NOT SILENT: WARNING and above go to `System.err`, below is dropped.** Silence was
    the easy choice and the wrong one -- libraries report real trouble through this and then CARRY ON, so a
    dropped warning turns a nameable failure into a mystery somewhere later. JUnit's launcher logs a
    `TestEngine` that fails to load at WARNING and continues with the engines that did load; on this VM that
    is exactly the line worth having. `isLoggable` answers against the same threshold, so a dropped record is
    never formatted -- the quiet levels cost nothing.
  - **No `LogManager`, no handler chain, no hierarchy, no configuration, no resource bundles.** A logger is a
    NAME, cached so the same name gives the same object (callers hold the reference). `getResourceBundle()`
    is null, which is what stock answers for a logger created without one -- every logger here.
  - **`demo/LoggingDemo` is in the boot suite**, and its arms are the ones that could be quietly wrong:
    same-name identity, the level ORDERING (a level whose value did not order would silently change which
    records survive), record field round-trip, and the visible write -- exactly one `[WARNING]` line and no
    `[INFO]` line.
  - **QEMU:** every `LoggingDemo` arm exact; host tests unchanged incl. `compiler: 37 checks`;
    `overlay-check: 44 known gap(s), 101 known dropped supertype(s), 0 new`.

- **`ConcurrentHashMap.newKeySet()` -- the overlay-drops-stock-members trap for the TENTH time (2026-09-09).**
  The overlay declared no `newKeySet`, and a member a name-winning overlay does not declare CEASES TO EXIST:
  the call resolved nowhere and surfaced as `LINK FAILED ... class OK but no body for that name+descriptor`
  followed by a `DENYLIST TRAP` blaming a list `ConcurrentHashMap` is not on.
  - **`KeySetView` extends `AbstractSet`, not stock's `CollectionView`** -- the same binding `EntryView` and
    `ValueView` already use here, and its iterator walks a `snapshot()` so the view is weakly consistent
    rather than fail-fast, which is the CHM semantics callers rely on.
  - **`newKeySet()`, `newKeySet(int)` and `keySet(V)` all land together**, deliberately: adding only the one
    method that trapped is how this family keeps costing a boot each time. A null mapped value makes `add`
    throw `UnsupportedOperationException`, exactly as stock -- that is how `keySet()` differs from
    `newKeySet()`.
  - **`make overlaycheck` CAUGHT MY OWN NEW CLASS**, which is the supertype diff earning its keep on the tool
    that was built for exactly this: `KeySetView is missing java/io/Serializable` (a free marker stock
    declares -- FIXED by declaring it) and `is missing ConcurrentHashMap$CollectionView` (a deliberate
    different-ancestor binding -- recorded in the baseline, which is what the baseline is for). Backlog
    45 -> 44 gaps, 100 -> 101 supertypes.
  - **`demo/DefaultIfaceDemo` IS IN THE BOOT SUITE NOW, and it was not before.** The previous entry claimed
    its interface-typed `getClass`/`hashCode`/`toString`/`equals` arms were Pi-gated; they were not -- the
    suite never launched that demo, so that boot claimed NO REGRESSION and nothing more. Wired in, together
    with `newKeySet` arms in `demo/MapDemo`, so both are gated from here.
  - **QEMU:** `newKeySet size=2 added=1 dup=0 has(a)=1 has(z)=0 iter=2` / `remove=1 again=0 size=1 empty=0`,
    all four interface-typed arms `= 1`, `metal junit: ran 44, failures 0` / `ALL PASSED`, suite clean with no
    fault, parity DIFF, `LINK FAILED` or mirror report. Host tests unchanged incl. `compiler: 37 checks`.

- **`ServiceLoader` AND JAR RESOURCE STREAMS ON THE METAL -- and the INTERFACE-TYPED `getClass()` bug they
  found (2026-09-08).** The launcher is past `ServiceLoader.load`, which had been a denylist trap, and now
  stops inside `ServiceLoaderRegistry.load` on an ordinary overlay gap.
  - **`ClassLoader.getResourceAsStream` serves the classpath jar** (`resourceBytes0` -> `VMNatives.resourceBytes`
    -> `JarFs.resourceData`). This is the resource path that CAN work here: `getResource`/`getResources` return
    `java.net.URL`, which needs a protocol handler in the denied `jdk/internal/loader`, and there is no way to
    hand stock the bytes. Those two still report "present but not served" rather than answering empty.
  - **The bytes are COPIED into a guest `byte[]`** (`Loader.guestBytes`, a real `[B`): the inflate runs in the
    baked world, whose arrays carry no array Type, and guest code goes on to `checkcast` and store what it gets.
  - **`java/util/ServiceLoader` is OVERLAID, and the denial had to be NARROWED first** -- a denied class is
    trap-wired at PATCH TIME, so no link stub runs and the overlay is never consulted. **This is a stated
    exception to "guestsrc is only for classes that need natives":** stock discovery goes through
    `getResources` -> URL, scans the module graph, and instantiates through `MethodHandles`/`AccessController`
    -- three denied subsystems. The FORMAT is stock's, read from the specification (one binary name per line,
    `#` comments, blanks skipped, duplicates ignored, file order).
  - **Laziness is preserved where it is load-bearing:** `stream()` hands back a `Provider` per NAME, so
    `ServiceLoaderUtils.filter` -- which rejects on `type()` before calling `get()` -- never constructs the
    engines it discards. Only the provider FILE is read eagerly. **Scope, stated: ONE classpath jar**, so a
    second jar's providers would be invisible; stock concatenates every `META-INF/services/*` on the path.
  - **THE REAL FIND: `getClass()` ON AN INTERFACE-TYPED RECEIVER RETURNED THE WRONG OBJECT.** javac compiles
    `interfaceTyped.getClass()` to an **`invokeinterface` whose owner is the INTERFACE** -- JVMS 5.4.3.4 makes
    interface method resolution search `java/lang/Object`'s public methods too. No Object method has an itable
    slot, so the directory walk **indexed a slot holding a REAL interface method and called it**. The result
    was non-null, STABLE, and completely wrong: a mirror that failed `instanceof Class`, whose `getName()`
    then dispatched into `String.codePointAt` and threw `IndexOutOfBoundsException` from a method the program
    never calls. `hashCode`/`equals`/`toString`/`wait`/`notify`/`notifyAll` had the same hole.
  - **Fixed where resolution is decided, not at the symptom:** `lowerInvokeInterface` routes a *ref naming one
    of Object's PUBLIC methods to the VIRTUAL path, where they are intrinsics or sit in the prefix every
    vtable shares. `monitorOp` dropped its owner check for the same reason and it is unambiguous:
    `wait`/`notify`/`notifyAll`/`getClass` are FINAL in Object and an interface may not declare a default
    override-equivalent to `hashCode`/`equals`/`toString` (JLS 9.4.1.2), so name+descriptor always means
    Object's method. **The writer is untouched** (`isObjectPublicMethod` defaults false, and only
    `MetalSymbols` overrides `monitorOp`), which is why `compiler: 37 checks` still passes -- the
    byte-for-byte self-hosting fixpoint.
  - **FIVE HYPOTHESES DIED TO INSTRUMENTS, NONE TO ARGUMENT**, and each instrument is still in the tree: a
    TIB-0 mirror (new `CLASS MIRROR WITHOUT A TIB` report -- never fired), an exhausted mirror cache (new
    `CLASS MIRROR CACHE FULL` report -- never fired), a duplicate class from resolving a name twice (refuted
    by a probe arm), a mid-run `resetLoader` invalidating mirrors (`MIRROR_RESET_WATCH` showed exactly one, at
    boot), and construction through `newInstance` (the same sequence run from `main` was perfect).
  - **WHAT ACTUALLY CRACKED IT WAS INSTRUMENTING THE FAILING CODE, THEN A HOST CONTROL WITH NO BOOT.** Prints
    inside `ServiceLoader.It.next()` showed `o.getClass() == c` was **correct there** -- the same object gave
    the wrong mirror only once returned to `main`. Every working case had an `Object`-typed receiver and the
    failing one was interface-typed; `javap -c` on the probe then showed
    `invokeinterface ...DiscoverySelectorIdentifierParser.getClass` in ten seconds. **Reproducing the shape is
    not reproducing the condition** -- the earlier `forName`-twice control passed because BOTH its resolves
    were fresh.
  - **AN INSTRUMENT THAT CANNOT SAY NO PROVES NOTHING:** `first.isInstance(p0)` answering 1 was load-bearing
    evidence, and it was only worth anything after `first.isInstance("x")` was checked to answer 0.
  - **Regression is in the Pi-gated suite, not only the probe:** `demo/DefaultIfaceDemo` now calls
    `getClass`/`hashCode`/`toString`/`equals` through an interface-typed reference and compares each against
    the `Object`-typed path.
  - **QEMU:** `ServiceLoaderProbe` every arm exact (3 and 13 providers incl. the unterminated last line, empty
    for an absent service, resolve, subtype, construct, `get()` distinct instances, and the identity arms);
    `ResourceProbe` reads the 133-byte services file and splits it; `metal junit: ran 44, failures 0` /
    `ALL PASSED`; the demo suite runs end to end (`lisp evals=600 result=610 stable=1`, `SMP: 4 of 4`,
    `finish HML`, `churnMB=625 live=32 intact=32`) with no fault, parity DIFF or uncaught exception. Host
    tests unchanged incl. `compiler: 37 checks`; backlog 45.
  - **NEXT BLOCKER, NAMED: `ConcurrentHashMap.newKeySet()` -- the overlay-drops-stock-members trap for the
    TENTH time.** `LINK FAILED ... class OK but no body for that name+descriptor`, then a denylist trap
    blaming a list CHM is not on. It needs a `KeySetView`, which the overlay does not carry.

- **A LAMBDA THUNK'S TAIL BRANCH RESOLVES A NULL SLOT INSTEAD OF BRANCHING TO 0 (2026-09-08,
  PI-VALIDATED).** A hand-emitted thunk had no equivalent of `dispatchTargetGuard`: it does
  `ldr x16, vtable[slot]` then `br x16`, so a slot RTA pruned reads 0 and branches to 0 -- which the
  firmware's low-memory shim turns into a silent re-entry of the image entry.
  - **THE FAILURE IS UNTRACEABLE BY CONSTRUCTION, and that is the lesson. `br` DOES NOT WRITE x30**, so
    `BOOT RE-ENTERED`'s x30 is a STALE return address from an earlier, healthy call and **every frame the
    report derives from it is an artifact.** This bug was mis-attributed for several boots to a perfectly
    good `blr x16` in `ArraySpliterator.forEachRemaining`, and THREE readings were built on that wrong
    instruction: an unpatched relocation, an unguarded M8 indirect call, and an interface phase-A cell that
    was never armed (that last one tested with a real change and REFUTED, then reverted).
  - **WHAT FOUND IT: making the thunk CHECK before branching.** Six words turned an untraceable reboot into
    a `DENYLIST TRAP` with a real stack -- `forEachRemaining` -> `ReferencePipeline$Head.forEach` ->
    `LauncherDiscoveryRequestBuilder.filters` -- naming `action` as a method-reference lambda whose
    referent's vtable slot number is valid but whose vtable BUFFER is still 0, the body never compiled.
    **When a wild branch erases its own provenance, do not chase the provenance: make the branch refuse to
    happen.**
  - **The fix then writes itself**, because the thunk already handles the sibling case: when
    `globalVtableSlot` answers -1 it emits `movz x17, siteIndex` + the trampoline and lets the shared branch
    call it. The null arm now does the same for a valid slot number over an empty buffer -- same registers,
    same trampoline, same first-call resolution against the receiver. Five words; it cannot break a working
    thunk, firing only on a target of 0 which could not have branched anywhere useful.
  - **Instruments kept, all off-cost (they run only in the wild-branch handler, which halts):** the report
    now prints the branching INSTRUCTION (`bl 0` vs `blr xN` vs `br xN` are three different bugs), the words
    around it, `Magic.readX16` for what it branched TO, and a backward scan naming which instruction last
    wrote x16 plus whether a `cbz x16` guard exists. **The scan's first cut LIED** -- its movk mask kept the
    `hw` field and its ldr mask kept `Rn`, so neither ever matched and it reported "only movz writes x16",
    a conclusion the instrument had manufactured. **A mask must clear every VARIABLE field; a scan that
    finds nothing looks exactly like a scan with nothing to find.**
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** no `BOOT RE-ENTERED`, no fault/parity markers, and
    every method-reference and lambda arm exact -- `reflective lambda thread = 7`, `apply(5)=105`,
    `twice`/`twice`, `lambda thread ran = 42`, `capturing lambda ran = 105`. `ticks/core c1=50 c2=50 c3=50`,
    `finish HML` 20/20/20, inversion `HIGH blocked 61ms`, `churnMB=625 live=32 intact=32`,
    `gc: collections=62`, WPA2 -> HTTP 200 OK. The change rewrites EVERY method-reference thunk, so thunk
    layout and the code arena shift -- this project has twice had latent bugs surface from layout movement
    alone, which is why hardware matters here.
  - **LAUNCHER: past the wild branch and past discovery entirely, into SESSION CREATION** --
    `SessionPerRequestLauncher.execute` -> `createSession` -> `DefaultLauncherSession.<init>`. Stops on
    another case of the lambda-receiver family: `ClasspathAlignmentCheckingLauncherInterceptor.intercept`,
    a synthesised-lambda receiver whose method the itable-directory tier does not find (the
    `LauncherInterceptor$Invocation` gap).

- **A DEFAULT METHOD ON A LAMBDA RECEIVER RESOLVED NOWHERE (2026-09-08, PI-VALIDATED).**
  `java.util.stream.Sink` has ONE abstract method (`accept`, inherited from `Consumer`) and defaults for
  `begin`/`end`/`cancellationRequested` -- so javac makes a lambda a Sink, and the pipeline then calls
  `downstream.begin(size)` on it from `Sink$ChainedReference.begin`.
  - **A synthesised lambda has a Type and an itable directory but NO `clTab` entry**
    (`finishLambdaClass` builds no registry entry), so `classRegByType` answers -1 and **both** existing
    tiers -- the class-chain walk and `resolveViaInterfaces` -- are keyed on a registry index and cannot run.
    The lambda's itable holds a thunk for the SAM only; the default body lives on the interface.
  - **`resolveViaItableDir` is the missing tier:** the Type's itable DIRECTORY names the interfaces the
    receiver satisfies, so walk those, resolve there, and search their super-interfaces too (Sink's `accept`
    comes from Consumer). It runs only where the code previously returned `denylistTrap` unconditionally, so
    it can turn a hard failure into a resolution and nothing else.
  - **A PROBE THAT FAILED TO REPRODUCE IS PART OF THE EVIDENCE.** `test/jdk/junit/StreamProbe` PASSES all
    five arms -- count, toArray, map, concat, and the launcher's exact
    `concat().map().toArray(String[]::new)`. Stream evaluation is not broken; the launcher's failure is
    CLOSURE-DEPENDENT, the "works in one closure, broken in another" signature this VM keeps producing. That
    is evidence about the PROBE, so the program was instrumented instead.
  - **The first probe also had the WRONG SHAPE:** two-element lists take `List12.spliterator`'s
    `super.spliterator()` path, not the `singletonSpliterator` path the launcher takes. It cost a boot -- and
    found a REAL ADJACENT GAP still unfixed: **`AbstractImmutableList.spliterator()` inherits from the `List`
    DEFAULT and fails to resolve**, which any 2+-element immutable-list stream will hit.
  - **What named it was printing WHAT THE RECEIVER IS, not just what was called on it:**
    `synthesised(lambda/anno TIB)=1`, `super == Object`, no display, `size=0x18` (one capture), `itableDir`
    non-zero. An unregistered class cannot be named from the registry, so the Type's SHAPE is what makes that
    report actionable -- one boot instead of a chase.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** no `DISPATCH ON UNREGISTERED TYPE`, no
    `VIRTUALRESOLVE FAILED`, no `BOOT RE-ENTERED` -- and the paths this touches exact: `apply(5)=105`,
    `lambda thread ran = 42`, `capturing lambda ran = 105`, `reflective lambda thread = 7`,
    `ifacecall`/`ifacelate`/`ifacedflt`. `ticks/core c1=50 c2=50 c3=50`, `finish HML` 20/20/20, inversion
    `HIGH blocked 61ms`, `churnMB=625 live=32 intact=32`, `gc: collections=62`, WPA2 -> HTTP 200 OK. **The
    suite has no lambda carrying a DEFAULT method**, so this boot confirms no regression on the dispatch
    path; the new tier was proven on QEMU by the launcher clearing the blocker.
  - **LAUNCHER: past it, into `java/nio/file` trap-wires (denylisted, expected) and then a NEW failure** --
    a `BOOT RE-ENTERED` wild branch around `Spliterators$ArraySpliterator.forEachRemaining`. A wild branch is
    a different and nastier family than the resolution gaps of the last several fixes.

- **`Collections.singletonSpliterator`, DELEGATED rather than hand-written (2026-09-08, PI-VALIDATED).** The
  overlay dropped it, so `ImmutableCollections$List12.spliterator()` -- reached from `Collection.stream()` on
  any one- or two-element immutable list -- resolved nowhere and surfaced as a DENYLIST TRAP naming a list
  Collections is not on. The overlay-drops-stock-members trap again.
  - **It delegates to stock `Spliterators.spliterator(Object[], int)` instead of returning an anonymous
    `Spliterator`, and that is the point:** a NEW CLASS INSIDE A java.base OVERLAY has broken the boot before
    (`CAP EXCEEDED: bakeresolve-find`). `Spliterators` is public, not denylisted, not overlaid, and its
    `ArraySpliterator` has been demand-loadable since the zip arc; it adds `SIZED|SUBSIZED` itself, so the
    characteristics match stock's.
  - **LAUNCHER: RUNNING REAL STREAM EVALUATION** -- `AbstractPipeline.evaluateToArrayNode` ->
    `wrapAndCopyInto` -> `copyInto` -> `Streams$ConcatSpliterator.forEachRemaining` ->
    `StreamSpliterators$WrappingSpliterator` -> the Sink chain. It stops on a DIFFERENT family:
    **`DISPATCH ON UNREGISTERED TYPE (receiver's class not in the registry): begin(J)V`** at
    `Sink$ChainedReference.begin`, with `TRAPWIRE index=-1` -- which per this project's own note means a
    LATE-RESOLUTION failure, not a denylisted class.
  - **PI-VALIDATED:** no `CAP EXCEEDED` (the one real risk -- the new method references `Spliterators`, so
    any image reaching it grows its closure), collections arms exact (`subList`, `keySet/values/entrySet`,
    `linkedList`, `Arrays.sort`), `ticks/core c1=50 c2=50 c3=50`, `finish HML` 20/20/20, inversion
    `HIGH blocked 61ms`, `churnMB=625 live=32 intact=32`, `gc: collections=62`, WPA2 -> HTTP 200 OK.
    **The suite never calls `Collection.stream()` on a 1-2 element immutable list**, so this boot confirms no
    regression; the method itself was proven on QEMU by the launcher getting past the trap.

- **`Class.isAssignableFrom` IGNORED INTERFACES ENTIRELY (2026-09-07, PI-VALIDATED).** The mirror answered
  by walking `Type.superType` -- the SUPERCLASS chain and nothing else -- so every interface answer was
  false: `Collection.class.isAssignableFrom(List.class)`, `...(ArrayList.class)` and
  `List.class.isAssignableFrom(ArrayList.class)` all said NO.
  - **picocli's `isMultiValue()` IS that call** (`Collection.class.isAssignableFrom(field.getType())`), so a
    `List<ClassSelector>` option was treated as SINGLE-valued: picocli reflectively stored a bare
    `ClassSelector` into a List field, and JUnit's `getExplicitSelectors` then did
    `list.addAll(getSelectedClasses())` -- whose first act is `toArray()` on the argument.
  - **LOCATED BY READING, WITH NO BOOT.** The failing line is bytecode 74, the
    `addAll(getSelectedClasses())` arm, and that getter is a bare `getfield` -- so the FIELD held a scalar,
    which is a picocli decision, and picocli makes it with isAssignableFrom. **The probe was then written to
    CONFIRM the diagnosis BEFORE the fix**, which is the discipline the launcher postmortem asked for.
  - **Two shapes, two halves.** Class-implements-interface: `VM.typeAssignable` already answers it (the
    class's itable directory, plus array covariance and an O(1) display check), so the mirror ASKS THE VM
    instead of keeping a second, weaker copy of the rule. Interface-EXTENDS-interface: **a Type node cannot
    express it** -- an interface Type is a chain dead end (depth -1, no display, no superclass link, no
    itable directory of its own) -- so `Loader.ifaceExtends` walks the class registry's direct-interface
    records transitively, depth-bounded.
  - **`ifaceExtends` is consulted from the REFLECTION PATH ONLY, deliberately.**
    `instanceof`/`checkcast` always have an OBJECT receiver whose Type is a class, so they never ask the
    interface-from-interface question; a registry walk in that hot, dispatch-critical routine would buy
    nothing and risk everything.
  - **WHY THE SUITE NEVER CAUGHT IT:** its one assertion is `Number.isAssignableFrom(Integer)`, a CLASS-chain
    question, which always worked. `test/jdk/junit/AssignableProbe` now pins the interface arms, the class
    controls, and three NEGATIVES (`List <- Collection`, `Integer <- Number`, `List <- String`) so that
    "fixed" cannot quietly mean "answers true".
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** the bootstrap battery's twelve `instanceof` checks
    all PASS (they run before `launch`, straight through `typeAssignable`), `Number.isAssignableFrom(Integer)
    =1 reverse=0 self=1` including its negative, `isInstance: str=1 num=0 null=0`, `YNW`/`RP`, every
    `iface*` arm exact, **no `LINK FAILED` for the new `assignable0` native**, `ticks/core c1=50 c2=50
    c3=50`, `finish HML` 20/20/20, inversion `HIGH blocked 61ms`, `churnMB=625 live=32 intact=32`,
    `gc: collections=62`, WPA2 -> HTTP 200 OK, no parity DIFF. QEMU: probe all eleven arms exact incl.
    `picocli isMultiValue(List) = true`; suite 30 programs; `metal junit: ran 44, failures 0`.
  - **LAUNCHER: into discovery FILTERS** -- `addFilters` -> `includedClassNamePatterns` ->
    `Collection.stream()` -> `ImmutableCollections$List12.spliterator` ->
    `java/util/Collections.singletonSpliterator`, which the Collections overlay drops.

- **`StringBuilder implements CharSequence` -- the supertype diff's FIRST find (2026-09-07, PI-VALIDATED).**
  Stock is `implements Appendable, CharSequence`; the overlay declared only `Appendable`. An overlay WINS the
  name, so a stock interface it omits **ceases to exist for that class** -- nothing declaring a
  `CharSequence` parameter could bind to a StringBuilder.
  - **The identical trap to this class dropping `Appendable`** (which broke `String.replaceAll`, since stock
    `Matcher` declares its sink as `Appendable`) and to `PrintStream` dropping `OutputStream` (which silenced
    the launcher completely). **The difference is that this one was reported at BUILD TIME** by
    `make overlaycheck`'s new supertype diff, instead of by a library failing somewhere unrelated at run time.
  - `subSequence` was the only missing member -- `length`, `charAt` and `toString` were already here. It
    returns the `String` from `substring` (a String IS a CharSequence), so the existing bounds checks apply
    and an out-of-range request throws where stock throws.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite): NO PARITY DIFF**, which is the assertion for this
    change -- adding an interface widens the itable directory and `subSequence` adds a virtual slot, and a
    writer/loader disagreement prints ungated. **The log printing at all is the second check**: `System.out`
    and every string built on the way there go through this class. `count=42 ok=true`,
    `length=16 charAt(6)=4`, `Str.split[0..2] = a/b/c`, `words=25 distinct=16`, `ticks/core c1=50 c2=50
    c3=50`, `finish HML` 20/20/20, inversion `HIGH blocked 61ms`, `steps/core 61/59/59/61`, `churnMB=625
    live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK. QEMU: suite 30 programs,
    `metal junit: ran 44, failures 0`; `overlay-check` 101 -> 100 dropped supertype(s), 0 new -- the tool
    confirming its own finding closed. **Nothing in the suite passes a StringBuilder AS a CharSequence**, so
    the boot proves no regression from widening the tables; the binding itself is javac's guarantee once the
    interface is declared.

- **A LAMBDA WITH A CAPTURED RECEIVER DROPPED EVERY CAPTURE AFTER THE FIRST (2026-09-07, PI-VALIDATED).**
  `buildLambdaTib`'s `kind == 5 || kind == 9` branch is written for a METHOD REFERENCE -- zero captures
  (unbound, `String::compareTo`) or one (bound, `obj::method`). With `nc >= 1` it emitted exactly one
  instruction, `ldrx(0, 0, 16)` (x0 = the captured receiver), and **never loaded captures 1..nc-1 at all.**
  - **Correct for a one-capture method reference, silently wrong for a lambda BODY** -- whose implementation
    kind is ALSO 5 when javac targets a private nestmate instance method. The `kind 6/7` path immediately
    below does the full job; 5/9 was never generalised past a single capture.
  - **The symptom is specific: capture 0 arrives intact and every later capture is whatever the caller left
    in x1..**, because nothing wrote them. picocli hit it as an `Optional` local holding an ENUM CONSTANT --
    `VIRTUALRESOLVE FAILED CustomClassLoaderCloseStrategy$2.map(Function)Optional` in
    `ConsoleTestExecutor.createXmlWritingListener`.
  - **Fix:** for kind 5/9 with a captured receiver, do what 6/7 does -- shift the SAM args UP to
    `x(nc)..x(nc+ia-1)` (high->low, so no shift clobbers a source), then load `field[0..nc-1]` into
    `x0..x(nc-1)` with **x0 LAST**, since x0 is the object being read from. At `nc == 1` the shift is a
    no-op mov and the load is the single receiver load it used to emit, so a genuine bound method reference
    is byte-for-byte unchanged -- which is what `compiler: 37 checks` asserts.
  - **MY BISECT PRODUCED A CONFIDENT RULE AND IT WAS WRONG.** Nine arms over `CaptureProbe` said "the
    captured reference arrives as the RECEIVER's field 1, whenever the receiver has two or more fields". The
    receiver's field count mattered only because constructing a two-field receiver and calling `invoke()`
    **CLOBBERED x1..x3 before the thunk ran**; with a one-field receiver the correct values happened to still
    be sitting in those registers, so those arms **passed by luck, not by correctness**. A bisect can only
    rank ingredients by whether they perturb the accident.
  - **ONE COMPILE-TIME LINE SETTLED IT**, printed from `MetalSymbols` where no shared `Symbols` seam had to
    change: `LAMBDA idx=88 nc=4 samArgc=0 size=48 kind=5`. A four-capture lambda going down a branch that
    handles one; reading the branch then took a minute. Kept behind `LAMBDA_WATCH` (default false).
  - **Two mechanisms were eliminated by READING first**, which is why the trace was aimed where it was:
    `lambdaSize` and `paramCount` agree (both count the indy descriptor, so the object is sized right and the
    stores are at the right offsets), and `opSlot` in shallow mode returns `OP_BASE + slot` -- exactly what
    `lowerLambda`'s capture store uses, with `deepStack` refused outright.
  - **THE PROBE HAD TO REPORT EVERY CAPTURE, not just the one that crashed.** `this` arriving intact while
    `out` faulted inside `PrintStream.print` is what showed this was a DROPPED-capture bug rather than a
    wrong-value one. It prints directly rather than concatenating: building the report with `+` allocates
    enough to hit `large region OOM`, which killed the first attempt at the instrument.
  - **PI-VALIDATED TWICE.** Full suite: lambda demos exact (`apply(5)=105`, `capturing lambda ran = 105`,
    `reflective lambda thread = 7`), `ticks/core c1=50 c2=50 c3=50`, `finish HML` 20/20/20, inversion
    `HIGH blocked 61ms`, `steps/core 61/59/60/60`, `churnMB=625 live=32 intact=32`, `gc: collections=60`,
    WPA2 -> HTTP 200 OK, no parity DIFF / `BOOT RE-ENTERED` / `unclaimed pc`. **But the suite's lambdas have
    small capture lists, so that boot claims NO REGRESSION on the unchanged `nc == 1` path.** The
    `CaptureProbe` boot is what proves the repaired path on silicon: all three arms exact.
  - **LAUNCHER: into TEST DISCOVERY now** -- past `createXmlWritingListener` and all of `registerListeners`,
    through `launchTests` -> `DiscoveryRequestCreator.toDiscoveryRequestBuilder` -> `createDiscoverySelectors`
    -> `TestDiscoveryOptions.getExplicitSelectors`. Next blocker there is an `ArrayList.addAll` whose
    argument is a `ClassSelector` rather than a `Collection`.

- **`ClassLoader.getResources`: AN EMPTY ENUMERATION, AND A REPORT WHEN THAT IS A LIE (2026-09-07,
  PI-VALIDATED).** The console launcher stopped at `ClassLoader.getResources`, which the overlay did not
  declare at all.
  - **AN EMPTY ENUMERATION IS THE CORRECT ANSWER HERE, NOT A WORKAROUND**, and the bytecode is what says so:
    `LauncherConfigurationParameters.findConfigFile` does `Collections.list(cl.getResources(name))` and
    returns **null on an empty list**, whereupon its caller simply skips loading `junit-platform.properties`
    -- which this jar does not contain. Disassembled rather than assumed.
  - **What joe-ng cannot do is serve a resource that IS present**: a resource comes back as a
    `java.net.URL`, a working URL needs a protocol handler, and that needs the `jdk/internal/loader`
    machinery this VM denies. So the two cases are kept APART instead of both answering empty --
    **absent -> empty, silently; present -> the same empty enumeration, but the run SAYS SO by name.** An
    always-empty `getResources` would present "we cannot serve this" as "there is nothing here", and the
    caller would run with default configuration with no indication why. That silent-wrong-answer shape is
    the one this VM keeps getting bitten by.
  - **`JarFs.hasResource(ptr,len)` looks the path up VERBATIM** -- a resource name is not a class name, so no
    `.class` suffix and no dot rewriting -- over the `ZipDir` central directory that was already there.
    **Deliberately NOT cached:** resource lookups are rare and would evict class entries, which are asked for
    constantly. The native takes a `byte[]` following `Class.forName0`'s pattern, so no native reads a String.
  - **I REGISTERED THE NATIVE UNDER THE WRONG CLASS FIRST.** `nativeBuf`'s blocks are keyed by DECLARING
    CLASS, and `resourceExists0` went under `java/lang/Class` instead of `java/lang/ClassLoader` --
    `LINK FAILED: java/lang/ClassLoader.resourceExists0([B)J`, one wasted boot. The report named it exactly.
  - **`test/jdk/junit/ResourceProbe` exercises BOTH arms**, because the present arm would otherwise have
    shipped untested and is the one that can be wrong with nothing noticing.
  - **PI-VALIDATED TWICE, and the two boots claim different things.** The full suite (`ticks/core c1=50 c2=50
    c3=50`, `finish HML` 20/20/20, inversion `HIGH blocked 61ms`, `steps/core 61/60/60/59`, `churnMB=625
    live=32 intact=32`, `gc: collections=60`, WPA2 -> HTTP 200 OK) shows **NO `LINK FAILED` and no parity
    DIFF** -- the assertion that matters, since the overlay gained two public methods and WIDENED
    `ClassLoader`'s vtable. But the suite carries no jar-backed program, so it never runs the new code: the
    **`ResourceProbe` boot** (`classpath /lib/junit.jar entries=2135`) is what puts `hasResource` on real
    silicon against a real jar -- `absent size = 0` silently, the `not served` line for
    `META-INF/services/org.junit.platform.engine.TestEngine`, `present size = 0`, both `getResource` forms
    null, `ResourceProbe done`.
  - **LAUNCHER: past `LauncherConfigurationParameters` and ALL of `LauncherFactory`**, now at
    `ConsoleTestExecutor.registerListeners` -> `createXmlWritingListener`, on an `Optional.map` dispatch.
    `overlay-check` 47 -> 45. **Serving resources for real is the same piece of work as `ServiceLoader`
    engine discovery** (`META-INF/services/*` out of this jar); both want a resource-STREAM path that does
    not go through URL.

- **AN INTERFACE'S CONSTANTS GET STATIC CELLS, AND ITS `<clinit>` GETS ENQUEUED (2026-09-07,
  PI-VALIDATED).** An interface field is implicitly `public static final`, but **`loadStructure` builds an
  interface's registry entry itself and returns early** -- before `registerClassStructure`, which is what
  enters a class's static fields in `sgTab`. So **no interface constant had ever been registered**, and every
  cross-class `getstatic` on one resolved to nothing. The report named it outright:
  `UNRESOLVED STATIC (reads null): org/junit/platform/launcher/core/LauncherConfig.DEFAULT -- class IS
  REGISTERED but has no static cell (registration gap)`.
  - **`loadBodies` returns early for an interface too, before `runClinit`** -- so even WITH a cell the
    constant would have stayed null, because no `<clinit>` record was ever enqueued. An interface whose
    constant is not a compile-time constant has a `<clinit>` exactly like a class
    (`LauncherConfig DEFAULT = builder().build()` compiles to one). **Both halves are needed; either alone
    still reads null.**
  - The static-field registration is split out into `registerStaticFields()` and called from both paths.
    `runClinit` only CAPTURES the body (it stopped compiling in the lazy-init arc), so the interface path
    pays nothing until `ensureClinit` runs it on first active use.
  - **`armPhaseACells` is deliberately NOT added to the interface path**: it arms cells for static METHODS,
    which interfaces reach through `compileSigOnDemand`'s separate tier. Widening that here would be an
    unmeasured change to dispatch rather than the field gap being fixed.
  - **LAUNCHER: through `LauncherFactory` now.** `LauncherFactory.create` -> `LauncherConfigurationParameters
    $Builder.build` -> `propertiesFile` -> `loadClasspathResource` -> `findConfigFile`, stopping at
    **`ClassLoader.getResources`** -- jar resource enumeration, the same wall `ServiceLoader` engine
    discovery sits behind (`META-INF/services/org.junit.platform.engine.TestEngine` names the three
    engines). That is the largest remaining piece of this arc.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** **no `CAP EXCEEDED`/`MAXREG-statics`** (the new
    guard, and every interface in the image now feeds `sgTab`) and **no `vtparity`/`itparity` DIFF** -- the
    real assertion, since interface registration is where itable slot numbering is established. Every
    interface arm exact (`ifaceinst`, `ifacestat`, `ifacecall`, `ifaceprune`, `ifacelate`, `ifacedflt`),
    `subList(1,3).size=2`, `ticks/core c1=50 c2=50 c3=50`, `finish HML` 20/20/20, inversion
    `HIGH blocked 61ms`, `smp sched: 4 of 4`, `steps/core 61/60/59/60`, `churnMB=625 live=32 intact=32`,
    `lisp evals=600 result=610 stable=1`, `gc: collections=60`, WPA2 -> HTTP 200 OK. QEMU: both old
    signatures 1 -> 0; `metal junit: ran 44, failures 0`; suite 30 programs clean; host tests unchanged incl.
    `compiler: 37 checks`.

- **LINKING MUST NOT RUN AN INITIALIZER -- the `<clinit>` ordering inversion, ROOT-CAUSED AND FIXED
  (2026-09-07, PI-VALIDATED).** A class's `<clinit>` was running as a side effect of **COMPILING** another
  class's `<clinit>`. JVMS 5.4 lets loading/verification/preparation/resolution be lazy but forbids any of
  them from running an initializer; only an **EXECUTED** active use (5.5) does. **Compilation is not
  execution**, and joe-ng was treating it as if it were.
  - **`clinitEntryOfLocked` ended with `drainPendingInit()`**, which initializes every class the COMPILE
    noted -- a far wider set than the initializer's active uses, since compiling a body pulls whatever its
    constant pool names, **nest-host and inner-class references included**. Those `<clinit>`s therefore ran
    before the body being compiled had executed a single instruction.
  - **picocli is where it showed.** `GroupValidationResult$Type` merely **NAMES** its enclosing
    `GroupValidationResult`, so compiling the enum's initializer pulled GVR and drained it: `GVR.<clinit>`
    built `SUCCESS_PRESENT`/`SUCCESS_ABSENT` from `Type.SUCCESS_*`, still null because Type had assigned
    nothing. Every GVR constant carried a null `type`; `blockingFailure()` compares `type` against those
    constants, so **`null == null` reported a BLOCKING FAILURE on a SUCCESS** and handed the launcher a null
    exception to throw.
  - **Fix, two moves.** The compile-noted drain moves to **AFTER `Magic.call0`** in `runPendingClinit`, so a
    class's own statics are assigned before anything it touched initializes and reads them; and the pre-run
    pass narrows from `drainCtorInit(reg)` to **`initClinitDeps(i, reg)`** -- the PRECISE bytecode dep set,
    walked a second time now that the compile has demand-loaded the deps that could not resolve before it.
    **That second walk is what `StandardCharsets` needs** (its initializer copies `sun.nio.cs.UTF_8.INSTANCE`,
    and running the body with `sun/nio/cs/UTF_8` uninitialized left the field null).
  - **GVR now initializes inside Type's EXECUTION rather than inside its compile**, which is exactly what a
    real JVM does for a mutually-referencing pair -- and what such a pair legitimately observes is
    **partially assigned state, not null**.
  - **THERE WAS NO CYCLE, and four consecutive fixes failed because of that.** `Type` does not depend on GVR;
    it only names it. Every attempt guarded `initClinitDeps` for a dependency cycle, which was doing its job
    correctly the whole time. **One of those attempts was also gated on a `clinitDepth` that LEAKED** -- the
    decrement sat on the success path, and an initializer that throws exits `Magic.call0` non-locally, so the
    drain condition was unreachable from the first instruction. One print would have said so before three
    boots were spent on it.
  - **WHAT FOUND IT, after three wrong mechanisms:** bracketing the initializer with
    `CLINIT START`/`COMPILE<`/`COMPILE>`/`END` markers and **tagging every `ensureClinit` call site with an
    id**. That put GVR's whole initialization visibly inside `COMPILE< Type ... COMPILE> Type` and named
    `drainPendingInit` as the caller, in ONE boot. Every earlier round inferred nesting from indirect lines
    (a `NO CLINIT RECORD` that actually meant "already in flight"; a by-name search showing `ran=1`) and each
    inference pointed somewhere wrong. **When the question is "who called this, and inside what", print the
    brackets and tag the callers -- do not reconstruct it from symptoms.**
  - **LAUNCHER: the command PARSES now.** The `NullPointerException ... at or before arg[3]
    '--disable-banner'` is GONE and picocli's usage dump with it. It reaches `LauncherFactory`; next blocker
    is `LauncherConfig.DEFAULT` (registered but no static cell), and behind that **`ServiceLoader` engine
    discovery** -- denylisted, and needing `META-INF/services` enumeration out of the jar
    (`org.junit.platform.engine.TestEngine` names the three engines). That is the largest remaining piece.
  - **PI-VALIDATED (`core 166MHz`, SMP on, full suite):** `ticks/core c1=50 c2=50 c3=50`,
    `jobs/core 6/6/6/6`, `sched: 89 preemptions`, `smp sched: 4 of 4`, `steps/core 61/60/60/59`,
    `finish HML` 20/20/20, `priority inversion ... HIGH blocked 61ms`, ExcDemo's seven-frame trace,
    `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, `gc: collections=60`, WPA2 ->
    HTTP 200 OK (828 bytes). **No `CLINIT ENTRY WAS SWEPT`** -- the specific hazard of holding a compiled
    initializer entry across more work before calling it, and cold DRAM with 60 collections is where it would
    have shown. No parity DIFF, no `BOOT RE-ENTERED`, no `unclaimed pc`. QEMU: `metal junit: ran 44,
    failures 0`; full suite 30 programs clean; host tests unchanged incl. `compiler: 37 checks`.

- **THE LAUNCHER BUILDS ITS COMMAND SPEC: options are registered and parsing succeeds (2026-09-02).** The only
  output is picocli's own warnings, and the 700 s QEMU timeout then hits with it STILL WORKING -- no trap.
  Overlay backlog **54 -> 47**.
  - **`java/lang/reflect/Type` NARROWED OUT of the denial rather than overlaid**, following the standing rule
    that guestsrc is for classes that need NATIVES: `Type` is an empty marker interface with one default method
    and no natives, so the STOCK class loads as-is. It was denied only by the broad prefix that keeps out the
    reflection IMPLEMENTATION machinery, and a marker is not that. `Class` implements it (Class is a legitimate
    overlay -- it carries natives).
  - **`Field.getGenericType`/`toGenericString`, `Method.getParameterTypes`/`getReturnType`/
    `getGenericParameterTypes`/`getGenericReturnType`/`toGenericString`.** The parameter and return types come
    from the DESCRIPTOR, which the stored `paramChars`/`returnChar` cannot answer -- they keep only the first
    character, so every reference type looked alike. That is why these were omitted when `getDeclaredFields`
    landed, and why they are answerable now.
  - **Generic forms return the ERASURE, and a caller is not misled by it:** stock returns a
    `ParameterizedType` only when a `Signature` attribute is present, and every caller asks
    `instanceof ParameterizedType` first -- false here, so it takes its raw-type path. For `List<String>` the
    element type is UNKNOWN, not wrong.
  - **An ARRAY parameter resolves to null rather than to `Object.class`:** visibly wrong at the caller, where
    a plausible answer would be quietly wrong.
  - **`getGenericParameterTypes` builds a fresh `Type[]` and copies** rather than returning the `Class[]`:
    array covariance would permit it, but that leans on the VM's array-Type assignability for a cast the
    CALLER makes; allocating the right array costs one loop and no assumptions.
  - **THE DUPLICATE-OPTION WARNING IS REAL AND ITS CAUSE IS STILL UNKNOWN.** picocli warns that
    `--help`/`--version` are each registered FOUR times. **The HOST JVM running the same jar and the same
    command prints no such warning**, so it is joe-ng's, not picocli being noisy -- a ten-second control that
    settled what a boot could not.
  - **Two hypotheses ruled out by probe, not argument.** A superclass chain that revisits or fails to
    terminate would make picocli's `cls = cls.getSuperclass()` collection loop: the chain is exactly
    `Sub -> Fields -> Object`, 3 hops, terminating. And a stateful enumeration that drifts between calls:
    `getDeclaredFields()` twice on the same class gives `2,2`. **Still open**, with `getDeclaredMethods`
    (annotated SETTERS) the untested candidate.
  - **The same host control also settled an earlier question:** `execute --select-class=... --disable-ansi-colors
    --disable-banner` is a VALID command line, so the "Unknown options" seen before was a genuine VM gap (field
    annotations) and never bad arguments.
  - **QEMU:** `metal junit: ran 44, failures 0` / `ALL PASSED`; host tests unchanged incl. `compiler: 37
    checks`; backlog 54 -> 47.

- **THE JUnit CONSOLE LAUNCHER PRINTS ITS OWN OUTPUT ON BARE METAL (2026-09-02)** -- usage text, word-wrapped,
  with ANSI colour escapes:
  `Unknown options: '--select-class=SleepSanity' ...` / `Usage: junit execute` / `Execute tests` /
  `For more information, please refer to the JUnit User Guide at https://docs.junit.org/current/`.
  - **THE SILENT-OUTPUT BUG WAS AN OVERLAY DROPPING ITS STOCK SUPERCLASS.** joe-ng's `PrintStream` was declared
    `public class PrintStream` -- extending nothing -- while stock is
    `PrintStream extends FilterOutputStream extends OutputStream`. So `System.out` was NOT an `OutputStream`,
    and every library that WRAPS it (`new PrintWriter(System.out)`, `new OutputStreamWriter(...)`) could not
    bind. **The launcher produced no output at all -- not a crash, silence.** Same trap as StringBuilder
    dropping `Appendable`, one rung up: **an overlay drops the stock SUPERCLASS as silently as an interface.**
    Fixed by extending `java.io.OutputStream` directly (not `FilterOutputStream`, whose wrapped-stream field is
    unused); `write(int)` already existed, so it cost no new code.
  - **`Field.getAnnotation` + `isAnnotationPresent`** -- field annotations are how libraries declare
    CONFIGURATION (`@Option`/`@Parameters` live on fields), so without them picocli's command spec had NO
    OPTIONS: every argument came back "Unknown options" and the usage block listed none. The annotation
    runtime already existed; this is the field-level entry point into it.
  - **`Field.getType`**, recoverable now in a way it was not when `getDeclaredFields` landed: the DESCRIPTOR is
    in the classfile and the annotation runtime already resolves a descriptor to a mirror, primitives included.
    Only the type CHAR was kept before, which cannot name a reference type -- so it was omitted rather than
    answering `Object` for everything. The descriptor's ABSOLUTE address is taken before resolving, since a
    resolve may demand-load and move the cursor.
  - **`Class.enumConstantDirectory`** for `Enum.valueOf`, built from `getEnumConstants()` so the constants are
    the SAME objects the VM holds -- `valueOf(E.class,"X") == E.X` by identity. Not cached, unlike stock's
    volatile field: caching would mean a new field on `Class`, whose mirrors the VM allocates itself.
  - **NEXT: `Field.getGenericType`**, which returns a `java/lang/reflect/Type` -- and that interface is
    DENYLISTED, so it needs a denial narrowing AND `Class` implementing the interface. A bigger step than the
    last few.
  - **QEMU:** `metal junit: ran 44, failures 0` / `ALL PASSED`; host tests unchanged incl. `compiler: 37
    checks`; overlay backlog 55 -> 54.

- **`java/text/BreakIterator` overlaid, and NARROWED OUT of the `java/text/` denial (2026-09-02).** picocli
  word-wraps its help and error output with it; without it the launcher trapped in `TextTable.putValue`.
  - **Whitespace and hyphen boundaries only, stated as a real limit.** Stock line breaking follows the Unicode
    line-breaking algorithm with locale tailoring; joe-ng carries none of those tables, so text in a script
    that does not delimit words with spaces will not wrap where a reader expects. For ASCII -- every caller
    reached -- the two agree. picocli uses exactly four methods: `getLineInstance`, `setText`, `first`, `next`.
  - **THE OVERLAY ALONE DID NOTHING, and the reason is worth keeping: `java/text/` IS DENYLISTED.** A denied
    class is trap-wired at PATCH TIME, so no link stub ever runs and the overlay is never consulted -- the
    call failed with the identical `TRAPWIRE index=71` as before the overlay existed. It needed the narrow
    allowance the denylist already has for this shape (as `java/lang/reflect/Method` is allowed out of
    `java/lang/reflect/`). **An overlay cannot rescue a DENIED class; the denial has to be narrowed first.**
  - **I MISSED THE DENIAL BY SAMPLING.** I dumped the tail of the prefix list, did not see `java/text/`, and
    concluded it was absent -- it was two lines above the cut. **Third time this session a conclusion came from
    partial output** (after `grep` on a binary log, and `classIndexByName` answering a different table).
  - **Launcher: past word wrapping**, now at `java/lang/Class.enumConstantDirectory()` -- another `Class`
    overlay gap, the ordinary kind.
  - **QEMU:** `metal junit: ran 44, failures 0` / `ALL PASSED`; host tests unchanged incl. `compiler: 37
    checks`; overlay backlog 55, unchanged.

- **THE CONSOLE LAUNCHER RUNS: it parses, reports and exits (2026-09-02).** With the deep-handler fix in, three
  more gaps on the OUTPUT path fell and the launcher executed end to end -- `CommandLine.execute` ->
  `handleParseException` -> `DefaultExceptionHandler` -> `PrintWriter` -> `Runtime.exit`.
  - **`sun/nio/cs/StreamEncoder` overlaid.** Stock's `forOutputStreamWriter` calls `charset.newEncoder()`, and
    joe-ng's `Charset`/`UTF_8` overlays are IDENTITY TOKENS with no encoder behind them (the String fast paths
    only ever compare `charset == UTF_8.INSTANCE`). A real `CharsetEncoder` would mean CharBuffer, ByteBuffer,
    CoderResult and the whole nio coder protocol -- **and none of it is needed, because `String.getBytes()` IS
    the stock UTF-8 fast path** and is what `PrintStream` has always used. Routing the Writer the same way
    means a Writer and a PrintStream produce identical bytes rather than two encoders that might disagree.
    **Unbuffered on purpose:** nothing to lose when the VM halts mid-run.
  - **`Throwable.printStackTrace(PrintWriter)`** -- the writer is ignored (one sink, the UART), but declaring it
    matters because **library code REPORTS FAILURES through it**: picocli hands a caught exception to
    `throwableToColorString`, so without it the reporting path trapped and hid the original error. **A trace on
    the console beats a denylist trap that conceals the thing it was trying to tell you.** It is what made the
    next bug readable.
  - **`System.lineSeparator` is a STATIC FIELD, not a property lookup -- seeding `props` was not enough.**
    Stock `initPhase1` copies the property into a private static, and joe-ng never runs initPhase1: the map
    said `\n` while the accessor still answered NULL. `PrintWriter.newLine()` then wrote a null separator into
    `Writer.write` and NPE'd inside java.base, with the real cause two frames up. Seeded like `System.out`.
  - **Progress is now measured in picocli's own phases:** past command-spec construction, past parsing, into
    **help/usage FORMATTING** -- the next stop is `java/text/BreakIterator.getLineInstance`, which picocli uses
    for word wrapping.
  - **QEMU:** `metal junit: ran 44, failures 0` / `ALL PASSED`; host tests unchanged incl. `compiler: 37
    checks`; overlay backlog 57 -> 55.

- **A DEEP-STACK HANDLER READ THE CAUGHT EXCEPTION FROM AN UNWRITTEN SPILL SLOT -- ROOT-CAUSED AND FIXED
  (2026-09-02).** The console launcher's `athrow`-threw-`this`, finally explained, and it was one line.
  - **In `deepStack` mode the operand stack lives in FRAME MEMORY**, and a merge point calls `syncIn`, which
    declares every operand register invalid so reads reload from memory. A handler IS a merge point -- but a
    handler does not receive its exception from memory, it receives it **in a register (x9)**.
  - **The inline path got away with it and hid the bug for the whole arc.** `emitCatch` (a throw caught in the
    SAME method) does `emitLoadException` then `syncOut`, which spills slot 0 to memory -- so the handler's
    reload finds it. An exception thrown by a **CALLEE** arrives through `VM.unwind` -> `Magic.resume`, which
    only sets the register. **Nothing ever wrote that slot**, so the handler read whatever it last held.
  - **Three different symptoms, one cause, and the probe produced all three:** a previous exception (the catch
    returned ANOTHER METHOD'S message -- `deep = D`), `this` (picocli's `parse`, which then handed it to
    `maybeThrow` and threw a non-Throwable), and garbage (an undefined instruction, `ec=0`).
  - **The fix is `regHolds[0] = 0` at a handler entry** -- BOTH paths deliver the exception in x9, which IS
    slot 0's register (`OP_BASE + 0 % OP_MAX`), so recording that residency is all it takes. No store, no new
    convention, and shallow methods are untouched (`compiler: 37 checks` still passes, so the byte-for-byte
    self-hosting fixpoint holds).
  - **THE BISECT IS WHY THIS WAS FOUND AT ALL.** `HighLocalThrowProbe` runs four arms in one 3-minute boot:
    deep stack alone (A, ok), + throw/catch (B, ok), + a NESTED try/catch (D, ok), + a throw from a CALLEE
    (C, **fails**). A/B/D all pass because their exceptions are caught in the same method -- the inline path.
    **The condition is a cross-method unwind into a deep-stack handler**, which no amount of staring at
    "deep stack" or "high locals" would have isolated.
  - **Two hypotheses died on the way, both by control rather than argument:** high local slots (a probe with
    the catch variable in slot **24** passes) and "a call from the catch" (arm B already calls
    `getMessage()` from its handler and passes).
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `gc: collections=8`, no `BAD THROW`, no `vtparity DIFF`. **The GC figures are essentially identical to the
    pre-fix boot** (`lastProbes=0x10CB7A`, `roots=0x443B0`, `heap=0xC87CA`), which is what byte-identical
    shallow codegen looks like from the outside.
  - **INDEPENDENTLY CONFIRMED BY THE LAUNCHER**, which is stronger than the suite here: the console launcher's
    `BAD THROW` is GONE (0 occurrences) and it now runs past picocli entirely into `sun/nio/cs/StreamEncoder`
    -- output encoding, i.e. command parsing COMPLETED. Next blocker there is `sun/nio/cs/UTF_8.newEncoder`,
    the ordinary overlay gap again.
  - **QEMU:** probe all four arms exact; `ran 44, failures 0`; host tests unchanged incl. `compiler: 37
    checks`; overlay backlog 57.

- **`athrow` THREW `this` INSTEAD OF THE EXCEPTION PARAMETER -- located exactly (2026-09-02).** The console
  launcher's failure, chased from "wild branch" through "not a Throwable" to a named bytecode site.
  - **`BAD THROW` reports a non-Throwable AT THE THROW SITE**, which is the only place the original pc is still
    in hand: by the time the uncaught report fires, the walk has reached `VM.boot` and the pc names the boot
    frame, not the thrower. Deliberately NOT folded into `unwindLog`, which caps at the first 24 throws --
    picocli and JUnit throw `ClassNotFoundException`/`NoSuchMethodException`/`NumberFormatException` as
    ordinary CONTROL FLOW, so that budget is spent long before anything interesting happens. **23 of the 24
    logged throws were normal control flow**, which is exactly why the capped instrument could not see this.
  - **The site:** `CommandLine$Interpreter.maybeThrow`, line 13583 = bytecode offset 27, which is literally
    `aload_1; athrow`. Local slot 0 is `this` (an Interpreter), slot 1 is the `ex` parameter -- **so the throw
    took slot 0's value for slot 1.** A LOCAL-SLOT resolution bug, not an operand-stack one, which is a
    different subsystem from where I had been looking.
  - **The object confirms it independently:** its status word says **72 bytes = 7 fields**, while joe-ng's
    `Throwable` needs at least 88 (eight backtrace slots at +16..+72 plus the message at +80). So it is a real
    `Interpreter`, not an exception wearing a wrong TIB -- which refutes the mis-typed-`new` hypothesis that
    fitted every other symptom.
  - **CAVEAT, stated: the pc attribution is a nearest-body-below GUESS.** `+0x390` is 912 bytes into a method
    whose bytecode is 30 bytes, so the frame naming is unreliable even though the object's identity and the
    bytecode shape are solid. Do not build the fix on that offset.
  - **THE HIGH-LOCAL HYPOTHESIS IS REFUTED.** `maybeThrow`'s caller stores the caught exception in a local past
    the register window (x19..x28 = slots 0..9), so the overflow spill looked like the culprit.
    **`test/jdk/junit/HighLocalThrowProbe` reproduces that shape and PASSES** -- javac puts its catch variable
    in slot **24**, and the value survives the spill, the call boundary and the rethrow intact
    (`result = boom:66`). Kept as a pinned control so the search does not circle back to it.
  - **Reproducing the SHAPE is not reproducing the CONDITION** -- the lesson this VM has taught before
    (`LambdaThreadDemo` tested `new Thread(lambda)` but never a receiver whose directory lacked the entry).
    What the probe does NOT have is picocli's other conditions: an ENORMOUS method with a DEEP operand stack
    (past `OP_MAX = 7`, into the spill path), compiled LAZILY rather than in a batch. That is where to look
    next.
  - **QEMU:** `ran 44, failures 0` / `ALL PASSED`, **no `BAD THROW` on a healthy run** (checked -- an
    instrument that fires on a passing boot is worse than none); host tests unchanged; backlog 57.

- **THERE WAS NO WILD BRANCH. The unwinder was FABRICATING the stack trace (2026-09-02).** The console
  launcher's ending looked like a wild branch to a heap address; it was nothing of the kind, and the VM's own
  report is what invented it.
  - **The uncaught-exception path read Throwable's LAYOUT off whatever object it was handed** -- backtrace at
    `+16..+72`, `detailMessage` at `+80` -- without checking the object IS a Throwable. Handed a
    `CommandLine$Interpreter`, it printed that object's INSTANCE FIELDS as frame pcs. The "wild pc"
    `0x060B9DB8` was `0x060B9D70 + 0x48`: **a heap reference in a field of the thrown object itself.**
  - **It now verifies Throwable-ness first** (`Loader.throwableTypeAddr` + `VM.instanceOf`) and, when the
    object is not one, says so and decodes NOTHING -- naming the class and address instead of manufacturing a
    trace. One run then gave the real picture:
    `UNWIND: THROWN OBJECT IS NOT A THROWABLE -- CommandLine$Interpreter at 0x060B9D70`, with
    **`esr=0 elr=0 far=0`**.
  - **ALL-ZERO fault syndrome is the decisive part: there was NO hardware fault**, so no wild branch and no bad
    dispatch. `athrow` simply executed with a non-Throwable operand -- an OPERAND-STACK bug, not memory
    corruption, and a far more tractable one.
  - **`LOADER LOCK stuck >10s` was ALSO a false alarm:** `state 4` is `TASK_RUNNING`, so task 0 held the lock
    and was still WORKING, not blocked. QEMU runs ~100x slow and the launcher's closure is enormous, so a 10 s
    wall-clock threshold is exceeded by legitimate work there.
  - **I had called this "a real VM bug, a wild branch" in the previous entry. Both halves were wrong**, and
    both were the VM's own instruments misleading me: one report asserting a layout it had not checked, one
    watchdog measuring wall clock on an emulator. **Every instrument lies at least once -- including the ones
    that print a stack trace.**
  - **NEXT, and now well-scoped:** `athrow` with a non-Throwable in a picocli method. `Interpreter` is very
    likely `this`, which points at the operand stack being off by some slots -- and picocli's methods are
    enormous, which is exactly where the `OP_MAX = 7` window and the deep-spill path are stressed.
  - **QEMU:** `ran 44, failures 0` / `ALL PASSED`, no `UNWIND` lines; host tests unchanged; backlog 57.

- **`make overlaycheck-deep` -- the checker had a BLIND SPOT, and it was the biggest caller population
  (2026-09-02).** The launcher trapped on `Character.getType`, a dropped overlay member the check had never
  listed. Reason: it scanned `out/` and the RAMFS jars but **not the stock `java.base` classes**, and
  `getType`'s only caller is stock `java/time/format/DateTimeFormatterBuilder`. Every java.base class is
  demand-loadable and routinely calls into an overlaid class, so that population matters most.
  - **The deep scan finds ~1059 further members** -- the TRUE set of "would trap if reached".
  - **It is OPT-IN, and that is a scope decision rather than a cost one.** Most of java.base is never reached
    on metal (denylisted subtrees, cold paths), so folding 1059 into the baseline would bury the ~57 gaps in
    code we actually ship, **and a check nobody reads is exactly how the one that mattered got missed**.
    `make test` stays shallow; `make overlaycheck-deep` is for when a trap's caller IS stock library code.
  - **`Character.getType` + the category constants** -- ASCII only, the same stated limit as the other
    classification predicates; above U+007F it reports `UNASSIGNED` rather than a confidently wrong category.
  - **`Locale.Category` (nested, a plain class like `TimeUnit` -- no enum machinery) + `getDefault(Category)`,
    `getCountry`/`getVariant`/`getScript`/`toLanguageTag`/`getDisplayName`/`stripExtensions`/`of`.** The
    constructors had always ACCEPTED country and variant and thrown them away, so `getCountry()` would have had
    to lie; two references are cheaper than a wrong answer, and Locale carries no VM-fixed field offsets
    (unlike `Thread`).
  - **FOUR MORE BLOCKERS CLEARED IN THE SAME PASS, using the deep scan to batch instead of one boot each:**
    the reachable `Character` classification/code-point members and `StringBuilder.delete`/`codePointAt`;
    **`ProcessEnvironment`** (the environment is EMPTY and that is the TRUTH -- there is no OS beneath the VM;
    overlaid rather than denylisted precisely because the honest answer exists and a denylisted class would
    keep trapping); **`MethodHandles.lookup()` returns a SINGLETON** instead of null, with
    `Lookup.ensureInitialized` -- stock `SharedSecrets` calls it before reading EVERY access shim and catches
    only `IllegalAccessException`, which a denylist trap is not.
  - **THE VM HAS SYSTEM PROPERTIES NOW (`seedStandardProps`), and it states WHAT IT IS.** Library code reads
    these unconditionally: picocli's `Ansi.isWindows()` does
    `System.getProperty("os.name").toLowerCase()` and NPEs inside the library on a null. `os.name` and
    `java.vm.name` say **joe-ng** rather than imitating Linux -- code that branches on the platform should not
    be told something false. `line.separator` is `\n`, because `Uart.putc` is what turns that into CRLF and a
    `\r\n` here would double the carriage returns.
  - **CONSOLE LAUNCHER: through picocli's ENTIRE command-spec and subcommand setup now.** It ends in a
    CORRUPTED state rather than a clean blocker -- a `LOADER LOCK stuck >10s` watchdog and an "exception"
    whose class is `CommandLine$Interpreter`, which is not a Throwable at all. The
    `NULL CLASS LITERAL: java/net/NetworkInterface` line above it looked like the cause -- the family where a
    literal for an unpulled class bakes null for ever.
  - **THAT HYPOTHESIS WAS WRONG, AND ONE REPORT SETTLED IT.** `NULL CLASS LITERAL` still said
    "class never pulled", an UNCHECKED assertion -- the identical trap already fixed for `UNRESOLVED STATIC`
    hours earlier, left in place on the sibling message. Routed through `printWhyUnpulled`, it answers
    immediately: **`java/net/NetworkInterface` is DENYLISTED**, so that null is INTENDED and was never the bug.
    A wrong diagnosis ruled out by evidence in one run instead of a chase.
  - **What remains is a WILD BRANCH, and the pc says so:** `<unclaimed pc=0x060B9DB8>` is above the code
    ceiling (`CODE_LIMIT` = `0x0300_0000`) -- a HEAP address, not code -- with an absurd frame offset, preceded
    by `LOADER LOCK stuck >10s`. That is a real VM bug and deserves its own session rather than another
    overlay member. **Note QEMU runs ~100x slow, so a 10 s wall-clock watchdog can fire on legitimate work
    there; the wild pc is the part that cannot be explained away.**
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `gc: collections=8`. **The BOOT PHASE is what this boot tested**: `seedStandardProps` now runs during
    loader init on EVERY image -- fifteen guest Strings built and pushed through `Properties.setProperty` via
    `Magic.callN` before `main` -- and `MethodHandles.lookup()` returns a real object where it returned null
    for the life of the project. Both ran clean, well before `launch MetalJUnit`, which is where a fault in
    either would have shown. QEMU also green; host tests unchanged; shallow backlog 57.

- **Class- and enum-valued annotation elements, and an ARRAY-TYPING bug I had put there myself (2026-09-01).**
  Backlog 58 -> 57; the console launcher completed picocli's whole `CommandSpec` construction.
  - **A THIRD PHASE resolves `'c'` and `'e'` elements**, after every classfile walk has finished -- the same
    reason phases 1 and 2 are separated: resolving demand-loads, a load re-parses a blob, and that clobbers the
    `gcp`/`gbase` cursor a walk is standing on. Pending entries hold ABSOLUTE Utf8 addresses, which survive a
    re-parse because blobs do not move; only the parse STATE does.
  - **Identity holds, which is the real test:** `ty.type == Integer.class` and `ty.colour == Colour.BLUE` are
    both true -- the mirror and the enum constant are the SAME objects the rest of the VM uses, not copies.
    Enum constants go through `ensureClinit` first, because the constants ARE statics and only `<clinit>` sets
    them. Primitive class literals work too (`ty.prim = int`, defaulted).
  - **MY PREDICTION WAS WRONG AND THE RUN SAID SO.** I expected this to clear the launcher's
    `ClassCastException`; it did not move at all. The real cause was found by disassembling picocli at the
    reported line: `customSynopsis().clone()` then `checkcast [Ljava/lang/String;`.
  - **I HAD TYPED ANNOTATION ARRAYS AS `Object[]` -- traced correctly, WRONG TYPE.** A `String[]` element
    reached picocli as an Object[], and the cast failed. Arrays are now typed from the ELEMENT METHOD'S OWN
    DESCRIPTOR, which is the only place the declared type is written down: an `element_value` says what a value
    IS, never what the declaration expects. Object[] remains the fallback when the element type is
    unresolvable -- what must never happen is a RAW array, whose elements are not traced as references at all.
  - **The probe now asserts `instanceof String[]` on the array AND ON ITS CLONE** -- the exact test picocli's
    checkcast makes. The original probe printed the right strings out of a wrongly-typed array, which is
    precisely the class of bug an element-value check cannot see.
  - **`Class.newInstance()`** delegates to `getDeclaredConstructor().newInstance()` and KEEPS the checked
    exceptions, because picocli catches around it and falls back -- swallowing the failure would turn its
    fallback into dead code.
  - **CONSOLE LAUNCHER: past `updateFromCommand` and the whole CommandSpec build**, now at
    `Class.newInstance` -> added -> next run. Every remaining stop has been an ordinary reflection feature.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `gc: collections=8`, no `LINK FAILED`. The array-TIB change touches `refArrayTib`, which the whole VM's
    array typing goes through, so the suite's array `instanceof` battery in the bootstrap block is a real
    check here even though it builds no annotations. QEMU: probe exact on all four element kinds plus both
    array-type checks. Host tests unchanged.

- **`getDeclaredFields` + annotation DEFAULTS -- two more launcher blockers, backlog 60 -> 58 (2026-09-01).**
  - **`Class.getDeclaredFields`/`getFields`**, enumerated by a classfile walk of the FIELDS section.
    Deliberately NOT reusing `parseFields()`: that is the LAYOUT pass -- it assigns slots, consults the
    superclass chain and writes `gsf*`/`superUnregistered` -- so reusing it for a read-only query would re-run
    layout as a side effect of reflection. This walk touches nothing but the cursor.
    Resolved by NAME, which is sound here in a way the METHOD equivalent is not: a class cannot declare two
    fields of the same name, so there is no overload ambiguity to guard against.
  - **STATIC fields are omitted, and that DIVERGES from stock -- stated, not hidden.** A `Field` reads through
    an INSTANCE offset (`addrOf(obj) + fieldOffset`) and the field registry holds instance fields only, so a
    static would be an object whose `get()` computes a meaningless address. **Returning fewer fields is
    visible; reading a plausible number from the wrong memory is not.** The probe pins `2 (stock 3)` so the
    divergence cannot drift silently.
  - **ANNOTATION DEFAULTS (`AnnotationDefault`), and they are not optional:** an annotation use writes only the
    elements it overrides, so picocli's `@Command(name = "junit")` leaves a dozen defaulted -- which read null
    and made the library **NPE on its own annotation**. That is exactly how it surfaced.
  - **Two phases, never interleaved.** Written pairs are decoded against the ANNOTATED class's blob; defaults
    live in the ANNOTATION TYPE's classfile and reading them re-parses that blob, clobbering the `gcp`/`gbase`
    cursor phase 1 walks. Element names are snapshotted as ABSOLUTE addresses before the re-parse, because
    `ifNameOff[]` is an offset into a blob the parse is about to change out from under.
  - **The probe caught a real bug the moment it was written** -- `declaredFields = 2 (want 3)` -- which is why
    it counts AND names rather than checking non-emptiness: a walk that mis-steps its cursor still returns
    plausible objects.
  - **CONSOLE LAUNCHER: two more blockers cleared.** Past `getDeclaredFields`, past the `updateVersion` NPE
    (defaults), now a `ClassCastException` in picocli's `UsageMessageSpec.updateFromCommand` -- consistent with
    a `Class`/enum-valued element still reading null, the gap this increment deliberately left.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `gc: collections=8`, no `LINK FAILED`, no fault from the new natives being in the table. **The suite calls
    neither `getDeclaredFields` nor `getAnnotation`** -- the probe on QEMU is what proves both; this boot
    confirms NO REGRESSION, which is a different claim. QEMU probe exact: `d.size = 9` (written),
    `d.name = anon` and `d.tags = 2:xy` (both defaulted). Host tests unchanged.

- **The annotation runtime had TWO bugs a direct probe could not see -- both fixed, backlog 65 -> 60
  (2026-09-01).** `getAnnotation` worked perfectly in `AnnoProxyProbe` and STILL failed for JUnit's launcher.
  - **(1) THE TYPE-VARIABLE BOUND IS THE DESCRIPTOR.** Declared `<T> T getAnnotation(Class<T>)`, the return
    erases to `Ljava/lang/Object;`. Stock is `<A extends Annotation> A getAnnotation(Class<A>)`, which erases
    to `Ljava/lang/annotation/Annotation;` -- **a DIFFERENT METHOD**, so every stock caller resolved nowhere
    and trapped. The probe passed because it called the overlay's own signature directly.
  - **`make overlaycheck` HAD ALREADY SAID SO AND I DID NOT READ IT.** The backlog moved 65 -> 64 when it
    should have dropped by several, and `java/lang/Class#getAnnotation(Ljava/lang/Class;)Ljava/lang/annotation/
    Annotation;` was still listed in plain sight. **I built the tool for exactly this failure and then ignored
    its output** -- the count is the signal, not just the pass/fail.
  - **(2) THE ITABLE DIRECTORY NEEDED THE INTERFACE'S CLOSURE.** Every `@interface Foo` implicitly extends
    `java/lang/annotation/Annotation`, and a bounded caller emits a **`checkcast Annotation`** on the result --
    which walks that directory. With only Foo in it the cast threw `ClassCastException`. Same closure treatment
    `finishLambdaClass` gives a functional interface. The extra entries get correctly sized but ZERO-FILLED
    itables: a zero slot meets the dispatch guard (a named trap) rather than running off a short table.
  - **FIX (1) IS WHAT EXPOSED (2).** Without the bound javac emitted no checkcast, so the missing closure was
    invisible. The probe tests more now than it did when it first passed.
  - **`AccessibleObject.getAnnotation` could finally be added** -- it was held back only while nothing could
    build an instance, because a null there would have contradicted an `isAnnotationPresent` answering true.
    With `Method` overriding both, the pair agrees.
  - **CONSOLE LAUNCHER: the annotation wall is CLEARED.** It now gets past `getAnnotation` and stops at
    `Class.getDeclaredFields()` -- a reflection ARRAY, which is the next real feature.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `gc: collections=8`, **no `vtparity DIFF`** (`AccessibleObject` gained a virtual, widening its vtable, and
    both `Method` and `Field` extend it), no wild branch under collection pressure -- the closure change adds
    one itable per closure interface to every annotation TIB, all held by `lambdaTibRoots`. **The suite does
    not call `getAnnotation`**; the probe on QEMU is what proves the feature. QEMU: probe exact
    (`hello`/`7`/`abc`) now through a real `checkcast`; host tests unchanged; overlay backlog **65 -> 60**.

- **ANNOTATION INSTANCES ON THE METAL -- `getAnnotation` returns a real object (2026-09-01).** The wall the
  console launcher and 19 backlog entries sat behind. `Method.getAnnotation` and `Class.getAnnotation` now
  return an object that IS the annotation interface, so `getAnnotation(Tag.class).value()` is an **ordinary
  interface dispatch**, not a special case.
  - **NO Proxy runtime, and none needed.** An annotation object has the SAME SHAPE as a synthesised lambda
    (`finishLambdaClass`): an object whose Type carries an itable directory for one interface. A lambda has ONE
    thunk (its SAM); an annotation has one per ELEMENT -- and each is **two instructions**, because the value
    lives in the instance:
    `ldr x0, [x0, #16 + slot*8]` / `ret`.
  - **What makes it small is an IDENTITY, not a coincidence:** the interface's own itable slot numbering IS the
    value index, because both sides are `ifmSlotIn`'s numbering for the same interface. Element method at slot
    s reads value slot s. No name->value map, no boxing, no capture list.
  - **Type/TIB/itable depend only on the INTERFACE, never the values**, so they are built once per annotation
    type and shared by every instance (`annoTibFor`, cached, GC-rooted through the same `lambdaTibRoots` array
    and for the same recorded reason -- nothing scanned would otherwise reference them).
  - **The value array is TYPED as `Object[]`.** A raw 8-byte-element array is not traced as references, so the
    Strings a `String[]` element holds would be reachable from nothing the collector follows and swept out from
    under it.
  - **Scope, stated: `Class` ('c'), enum ('e') and nested-annotation ('@') elements read NULL and are not
    faked.** Resolving one demand-loads a class, and a load re-parses a blob and clobbers the `gcp`/`gbase`
    cursor this walk is standing on -- the hazard already recorded for `buildLambdaTib`. The fix is the
    note-and-retry route the static and class-literal paths use, and it belongs in its own increment.
  - **An annotation whose INTERFACE is not loaded says so** (`ANNOTATION IFACE NOT LOADED`): without the
    interface there is no itable to give the instance, and a silent null there would read as "the annotation is
    absent" -- a different and wrong statement.
  - **`test/jdk/junit/AnnoProxyProbe` asserts VALUES, not non-nullness.** A proxy returning the wrong slot
    would still be non-null, which is exactly what a broken slot-index==value-index identity would produce.
    QEMU: `value = hello`, `count = 7`, `names.length = 3`, `names = abc`, `absent = 1` -- all exact, first run.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `gc: collections=8`, no `ANNOTATION IFACE NOT LOADED`, no new `LINK FAILED`. **The GC-root sharing is what
    that boot actually tests**: annotation TIBs live in `lambdaTibRoots`, and getting that wrong sweeps a TIB
    under memory pressure and wild-branches rather than failing an assertion -- so 8 collections with lambdas
    in use throughout is the evidence. **The annotation runtime itself is NOT exercised by the suite** (
    `MetalJUnit` uses the presence-only `isAnnotationPresent`); `AnnoProxyProbe` on QEMU is what proves the
    feature. QEMU also green; host tests unchanged; backlog 65 -> 64.

- **Overlay backlog 166 -> 65 (61%) -- third pass: Throwable, ThreadGroup, AtomicBoolean, ThreadLocal, Locale,
  ByteBuffer, InetAddress (2026-09-01).** 13 more restored.
  - **`Throwable.fillInStackTrace()` is a NO-OP returning `this`, deliberately.** joe-ng captures the backtrace
    at CONSTRUCTION (the `bt0..bt7` slots the VM fills), so there is nothing to fill -- and re-capturing here
    would REPLACE a trace taken at the throw site with one taken wherever this is called, which is strictly
    worse information. Stock subclasses override it to make an exception cheap, and it was exactly those
    (`IllegalAccessException`, `InvocationTargetException`) the check flagged, because their
    `super.fillInStackTrace()` resolved nowhere. Plus `getLocalizedMessage` = `getMessage`, stock's own default.
  - **`ThreadGroup(String)`/`getName`/`isDaemon`/`setDaemon`/`getParent`** -- named groups are accepted and
    FLATTENED into the one group: the name is remembered so `getName()` is truthful, but grouping has no
    scheduling effect. Library code constructs a group to LABEL threads (supported); code expecting a group to
    ISOLATE threads would be disappointed, and there is none on metal.
  - **`AtomicBoolean` memory modes** and **`ThreadLocal.withInitial`** (an anonymous subclass overriding
    `initialValue`, exactly as stock's `SuppliedThreadLocal`).
  - **`Locale.setDefault` accepted and IGNORED, `forLanguageTag` parses the language subtag only.** joe-ng
    carries no locale data, so there is nothing a different default could select, and the one place a Locale is
    read (`Pattern`'s CASE_INSENSITIVE folding) wants ENGLISH -- already the default. **Accepting the call is
    the point**: omitting it drops the member and the call traps instead of being harmlessly inert.
  - **`ByteBuffer.allocateDirect` = `allocate`** (no off-heap region), with `isDirect()` still answering false
    rather than claiming otherwise. **`InetAddress.getLoopbackAddress`** builds 127.0.0.1 directly -- a
    loopback lookup that went to DNS would be both wrong and slow.
  - **`Semaphore.tryAcquire`/`availablePermits` STAY OUT**: there is no try-acquire or count-peek intrinsic, so
    they need VM work rather than an overlay method. Stated rather than faked.
  - **Remaining 65 is the VM-feature tail**: annotation instances (Proxy), generic signatures, reflection
    arrays, resource enumeration, `java/security`, TreeMap/TreeSet. The mechanical phase of this backlog is
    over.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `smp sched: 4 of 4`, `gc: collections=8`, no new `LINK FAILED`, and **no `vtparity DIFF`** -- the last is
    the meaningful absence, since adding `fillInStackTrace` WIDENS `java/lang/Throwable`'s vtable (the same
    shape that took parity 16 -> 18 when `initCause`/`getCause` arrived) and a mismatch prints UNGATED. Every
    one of the 44 tests runs through the exception machinery, so this is a real check rather than a quiet path.
    QEMU also green; host tests unchanged; `overlay-check: 65 known gap(s), 0 new`.
  - **Scope, stated:** the suite never constructs a named `ThreadGroup`, calls `Locale.setDefault` or asks for a
    loopback address -- those belong to the console-launcher path and were seen on QEMU. The Pi run confirms NO
    REGRESSION from widening Throwable, which is a different claim.

- **Overlay backlog 166 -> 78 (53%) -- second pass adds the reflection predicates, `Array` and `PrintStream`
  (2026-09-01).** 19 more members restored on top of the 69 below.
  - **Access-flag predicates, which the member's own flags already answer:** `Method.isBridge`/`isVarArgs`/
    `isSynthetic`/`isDefault`, `Field.isSynthetic`/`isEnumConstant`, `Constructor.isSynthetic`/`isVarArgs`.
    **`isDefault` is not a bit** -- it is "declared in an interface, neither abstract nor static" -- and
    ACC_BRIDGE/ACC_VARARGS share bit values with ACC_VOLATILE/ACC_TRANSIENT, so they are only meaningful on a
    method.
  - **`Field` narrow primitives** (`getByte`/`setByte`/`getShort`/`setShort`/`getChar`/`setChar`): a
    byte/short/char occupies a full 8-byte slot, so the value is read as a word and the CAST applies the sign
    extension for byte/short and the zero extension for char -- the stock contract. **`getFloat`/`getDouble`
    stay out on purpose:** those arrive in FP registers that joe-ng's reflective marshalling does not carry, so
    a plausible-looking accessor would return a WRONG NUMBER rather than fail.
  - **`Array.getLength`/`get`/`set`/`getLong`/`getInt` + the varargs `newInstance`,** read straight out of the
    object layout (length at +16, elements at +24) -- the same layout `arraylength`/`aaload` are lowered
    against, so they agree with ordinary bytecode by construction. **Bounds are checked here**: a reflective
    accessor that trusted its index would read or WRITE outside the object, which is the failure mode this VM
    is least able to diagnose. A multi-dimensional request is REFUSED rather than quietly given one dimension.
  - **`AccessibleObject.isAnnotationPresent`** -- false on the base, OVERRIDDEN by `Method`. Needed because a
    call through an `AccessibleObject`-typed reference resolves against the BASE, so the overlay dropping it
    dropped the member even though `Method` implements it. **`getAnnotation` deliberately NOT added beside it:**
    it must return a live annotation instance (a Proxy runtime joe-ng lacks), and returning null would claim
    "no such annotation" while `isAnnotationPresent` answers true -- a self-contradiction is worse than a gap.
  - **`PrintStream` can WRAP a stream now** (`(OutputStream)`, `(OutputStream,boolean)`, `append`, `close`,
    `write([BII)`). JUnit's `StreamInterceptor` and picocli both do `new PrintStream(someStream)` to CAPTURE
    output; ignoring the argument would leave the capture silently empty, which reads as a mysteriously failing
    assertion rather than a missing feature. **The field is safe on the SEEDED `System.out`** -- which is
    allocated with no constructor run -- because `Heap.alloc` zeroes an allocation's payload. **Checked, not
    assumed:** a garbage value there would have been handed to a virtual call. `close()` only closes a WRAPPED
    stream, since closing the UART would silence the console for the rest of the boot.
  - **Remaining 78 is mostly out of reach by design:** 19 need annotation instances (Proxy) or generic
    signatures, and most of `Class`'s 21 need reflection arrays, resource enumeration or `java/security`.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `smp sched: 4 of 4`, `gc: collections=8`, no new `LINK FAILED`. **The console output IS the evidence for
    the PrintStream field**: every line after the banner goes through `System.out`, a PrintStream allocated
    with NO constructor run, so a boot that prints at all is a boot where that field read null and routed to
    the UART. QEMU hands out zeroed DRAM and a Pi at cold power-on does not -- the zeroing here comes from
    `Heap.alloc`, not from luck, and hardware is what settles that. QEMU also green; host tests unchanged;
    `overlay-check: 78 known gap(s), 0 new`.

- **Overlay backlog worked down 166 -> 97 (42%) -- 69 dropped-but-referenced members restored (2026-09-01).**
  Every one was a member `make overlaycheck` listed as REFERENCED by something we ship yet silently absent from
  a name-winning overlay: each would have surfaced on metal as a `DENYLIST TRAP` on the day it was reached.
  - **Restored:** `Character` classification predicates (10), `Comparator` combinators + factories (8),
    `Predicate` combinators (5), `Function.identity`/`andThen`/`compose`, `Boolean.valueOf(String)`,
    `Byte`/`Short` `parse`/`valueOf`/`decode` (8), `StringBuilder` constructors + `charAt`/`setLength`/
    `substring`/`insert`/`append(char[])`/`appendCodePoint`/`deleteCharAt` (10), `Collections` statics (10),
    `Class` casts + nesting predicates + `getTypeName`/`getClassLoader`/`getEnclosingClass` (9), `TimeUnit`
    MINUTES/HOURS/DAYS + the enum surface + conversions (6), `Thread` 3-arg ctor + `getId`/`isDaemon`/
    context-classloader (6), `Random.nextInt(int)`/`nextBoolean`, `Module.getLayer`,
    `NumberFormatException(String)`.
  - **TWO MORE INSTANCES OF THE SAME TRAP SURFACED WHILE FIXING IT, AND JAVAC CAUGHT BOTH:**
    `NumberFormatException` had no `(String)` constructor and `Random` no `nextInt(int)`, so adding
    `Byte.parseByte` and `Collections.shuffle` failed to COMPILE. **javac catching it is the lucky case** --
    the same gap reached from a stock class that already compiles surfaces as a denylist trap instead.
  - **WHAT WAS DELIBERATELY *NOT* ANSWERED**, because a wrong answer is worse than a known gap:
    `StringBuilder.append(double)` (no double-to-string), `Class.getAnnotation*` (needs a Proxy runtime),
    `getProtectionDomain` (`java/security` is denylisted), `getResource*` (URL + resource enumeration), the
    generic-signature methods, and the `Collections` sorted/navigable empties (no TreeMap/TreeSet).
  - **`Thread.getId` is the identity hash, NOT a counter, and the reason is load-bearing:** this class's fields
    sit at offsets the VM HARDCODES (`@16..@56`), so adding one would shift the layout underneath it. The
    identity hash IS the address here, so it is stable per thread and distinct among LIVE threads -- the stock
    contract -- but not unique across a boot, which is stated rather than glossed.
  - **`Collections.synchronized*` return the backing collection**, which is honest for joe-ng as it stands
    (every reached caller uses the result from one task) but is NOT a general substitute -- noted in the code
    as the line to change the day a collection is genuinely shared.
  - **`Random.nextInt(int)` keeps the stock REJECTION LOOP**, which is not an optimisation: `next(31) % bound`
    is biased whenever bound does not divide 2^31, and this class's own comment promises a bit-for-bit JDK
    sequence.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `metal junit: ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `smp sched: 4 of 4`, `gc: collections=8`, no new `LINK FAILED`. **The timing tests are the ones that
    matter for this change** -- `testSleep`, `testInterruptSleep`, `testJoinOnTerminatingThread`,
    `testInterruptJoin` assert wall-clock behaviour with the REORDERED `TimeUnit` constants and the extended
    `Thread` underneath them, and QEMU (100x slower, counter near real time) is exactly where such a test can
    pass for the wrong reason. QEMU also green; `overlay-check: 97 known gap(s), 0 new -- OK`; host tests
    unchanged.

- **`make overlaycheck` -- the overlay-drops-stock-members trap is caught at BUILD TIME now (2026-09-01).**
  A `guestsrc/` overlay WINS the name, so every stock member it does not declare CEASES TO EXIST, with no build
  error and no `NoSuchMethodError`: the call resolves nowhere and surfaces on metal as
  `DENYLIST TRAP: call into a pruned (metal-absent) class`, naming a denylist the class is not even on. **That
  trap has cost NINE separate debugging sessions.**
  - **It is NOT an overlay-vs-stock diff.** Overlays are deliberately minimal -- dropping most of a stock class
    is the POINT -- so that diff is thousands of lines of intended absence and would be ignored within a week.
    It asks the question that can actually trap: **does anything we ship still REFERENCE a member the overlay
    dropped?** 107 overlays, **166 referenced-but-dropped members**.
  - **The RAMFS jars are scanned, not just `out/`** -- not optional: `ConcurrentHashMap.<init>(IFI)V` is called
    from the JUnit jar and from nowhere else in the tree.
  - **Resolution walks the OVERLAY's own super chain** (an overlay may extend something different from stock),
    preferring an overlaid ancestor at each step -- what the metal loader does -- plus interfaces for defaults.
    `<init>` is deliberately NOT inherited, which is the JVMS rule and the exact bug behind the earlier
    `globalBufByRef` fix.
  - **VERIFIED THREE WAYS BEFORE THE BASELINE WAS TRUSTED**, because a checker that lies produces a garbage
    backlog: `Character.isDigit` really is absent (reported); `Boolean.valueOf(String)` really is absent, only
    `valueOf(boolean)` exists (reported); and **`Boolean.getBoolean`, which was ADDED hours earlier, is
    correctly NOT reported** -- the passing-run check this session has now earned five times.
  - **NEGATIVE CONTROL: deleting `Boolean.getBoolean` makes it fail**, naming the member, naming the picocli
    class that calls it, exit 1. That is the bug that cost a full debug round earlier the same day, caught
    before any boot.
  - **`test/overlay/known-gaps.txt` is a BACKLOG, not an approval list** -- and says so in its own header. A
    line means the gap is known and unfixed, not safe: the two that bit us looked exactly this cold until the
    day they ran. `make test` fails on anything NEW; `make overlaycheck-update` regenerates.
  - **Host-side build-time tool only -- NO image change**, so there is nothing for a Pi boot to confirm. Host
    tests unchanged plus `overlay-check: 166 known gap(s), 0 new -- OK`.
  - **The backlog is already predictive of the console-launcher arc:** `Character.isDigit`/`isWhitespace`/
    `isLetterOrDigit`, `Class.cast`/`asSubclass`, 13 `reflect/Method` and 11 `reflect/Field` members are all on
    the path that arc is walking.

- **The `ConcurrentHashMap` blocker was NOT a denylist -- it was the overlay-drops-stock-members trap, for the
  SEVENTH time (2026-09-01).** `CHM` is **not denylisted at all**; the `DENYLIST TRAP` message ("call into a
  pruned (metal-absent) class") also fires for a **LINK STUB THAT FAILED TO RESOLVE**, and blamed a denylist
  CHM was never on.
  - **Found by instrument, in two rounds.** `resolveLinkTarget` had THREE distinct `return 0` paths sharing one
    silence. Naming them gave `class OK but no body for that name+descriptor` -- narrowed but unactionable,
    since it did not say WHICH descriptor. Adding the descriptor gave the answer outright:
    **`ConcurrentHashMap.<init>(IFI)V`**.
  - **`guestsrc/java/util/concurrent/ConcurrentHashMap` declared only `()` and `(int)`;** stock has five
    constructors. `(int,float,int)` therefore resolved NOWHERE. Added `(int,float)`, `(int,float,int)` and
    `(Map)`. `loadFactor`/`concurrencyLevel` are accepted and IGNORED -- which is what they are, sizing hints
    with no observable effect on the Map contract, and joe-ng's map is not striped. **Deliberately not passed
    to `super`:** the float is never used in arithmetic, so this adds no floating-point codegen to a path that
    had none.
  - **Two more fell straight behind it, identical trap:** `AtomicReferenceArray.getOpaque` (the WHOLE
    memory-mode family added -- opaque/acquire/release/plain/weak-CAS/compareAndExchange -- rather than one
    method per boot, since every mode differs from plain only in ORDERING that a single aligned word access
    already satisfies) and `jdk/internal/util/DecimalDigits.appendPair`. **NINE TIMES** now for this trap.
  - **MY NEW REPORT CRIED WOLF ON A GREEN RUN -- 26 `LINK FAILED` lines on a boot where all 44 tests PASS.**
    `resolveLinkTarget` IS CALLED IN A LOOP: the late-virtual path walks the receiver's superclass chain, and a
    0 at one level is the normal way of saying "not declared here, try the super". The reason is now RECORDED
    (`lnkFailWhy`) and printed only by `resolveLinkStub`, which has no other tier to try. Silent on a green
    run again. **Fourth too-eager instrument this session** (after `plausibleCode`, `Heap.LARGE_LIMIT`, and the
    `UNREGISTERED SUPER` guard): **a report that cries wolf on a passing boot is worse than none.**
  - **NEXT WALL IS A REAL FEATURE, not an overlay gap:** `Class.getAnnotation(Class)` must return an annotation
    INSTANCE, which needs a Proxy runtime joe-ng does not have -- the same reason `@MethodSource("name")`'s
    element value is unreadable. `ServiceLoader` discovery is still behind that.
  - **PI-VALIDATED (`core 166MHz`, SMP on):** `metal junit: ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `smp sched: 4 of 4`, `gc: collections=8`, and **zero `LINK FAILED`** -- the check that matters for the
    corrected report, since MORE classes reach the late-virtual chain walk on hardware than on QEMU, so a
    still-too-eager report would show here first. QEMU the same, 84 lines. Host tests unchanged.
  - **The CHM fix itself is NOT exercised by that boot** -- the suite never constructs
    `ConcurrentHashMap(int,float,int)`; that path is the console launcher's and was proven on QEMU. The Pi run
    confirms NO REGRESSION, which is a different claim and worth keeping straight.

- **TOWARD THE REAL JUnit `ConsoleLauncher` ON METAL: ten blockers cleared, NOT YET RUNNING (2026-09-01).**
  Goal: replace the hand-written `MetalJUnit` with stock `org.junit.platform.console.ConsoleLauncher`,
  demand-loaded from the RAMFS jar. It is FOUND, LAUNCHED, and now executes inside `ConsoleLauncher.main` ->
  `CommandFacade.run`. **Every failure so far has been a nameable gap, not an architectural wall.**
  - **Caps (each hit in turn, each raised):** `MAXBLOB` 1024 -> 4096, `MAXCLASS` -> 4096, `MAXVT` 16384 ->
    65536, `MAXREG` -> 24576, `MAXLAZY` 8192 -> 32768. **`DEMAND_ZERO_SPAN` 24 -> 96 MiB WITH them**, because
    the code comment ties the two: the pre-zeroed span is sized so no batch can outgrow it, and a bigger
    MAXBLOB without it reintroduces the cold-DRAM wild branches that span exists to prevent.
  - **`java/lang/Module` (new overlay) + `Class.getModule()`.** joe-ng has no module layer, so the single
    UNNAMED module is the TRUE answer, not a stub: `ModuleUtils.getModuleVersion` short-circuits on
    `isNamed()` and returns `Optional.empty()`. `getDescriptor()` returns null per the JDK's own contract.
  - **`Class.getPackage()` -> null**, which `PackageUtils.getAttribute` explicitly supports
    (`Optional.ofNullable`). Fabricating a blank `Package` would have been the lie.
  - **`seedSystemProps()`** -- stock `System.initPhase1` never runs, so `props` is null and the first
    `setProperty` NPEs inside java.base. Unlike the `System.out` precedent this CANNOT be a bare allocation:
    `Properties` extends `Hashtable`, so a TIB-only object just moves the NPE into `put`. The `<init>` is
    resolved and run.
  - **`Unsafe.storeFence/loadFence/fullFence`** -> one full `dsb` (`VMNatives.unsafeFence`). Stronger than
    required is always correct, and these are cold paths where a one-way barrier would buy nothing and could
    be wrong invisibly.
  - **THE DENYLIST TRAP NAMES ITS CALLEE NOW (`denied callee: <class>.<method>`)** -- two words per trap-wire
    site. It used to print `index=126` and nothing else, a number meaningful only with the verbose patch-time
    dump from the SAME boot; turning that dump on **flooded the UART and starved the run it was meant to
    diagnose** (the "boot lines cost seconds" lesson, paid again). **This was the highest-leverage change of
    the arc:** the next two blockers each fell in ONE round after it.
  - **`Boolean.getBoolean` -- THE OVERLAY-DROPS-STOCK-MEMBERS TRAP FOR THE SIXTH TIME** (after
    StringBuilder/Appendable, `Class.getPrimitiveClass`, the wrappers' `TYPE`, `Throwable.initCause`,
    `Character.toString`). Undeclared -> resolves nowhere -> denylist trap, NOT a missing-method error.
    `parseBoolean`/`toString(boolean)` added beside it.
  - **`Collections.unmodifiableList`/`unmodifiableCollection`/`unmodifiableMap`/`emptyList`/`emptyMap`/
    `singletonList`** -- same trap, same class as the earlier `enumeration` gap. Returning the backing
    collection is exact wherever the result is only READ, which is every path joe-ng runs; a mutating caller
    would silently succeed instead of throwing, and that is documented rather than quietly aliased.
  - **A NEW REPORT ARM FIRED ON SOMETHING UNRELATED:** `java/time/Duration.MAX -- class IS REGISTERED but has
    no static cell (registration gap)`. A distinct defect the old unconditional "class never pulled" wording
    would have mislabelled.
  - **STILL BLOCKED, and stated: `java/util/concurrent/ConcurrentHashMap.<init>` is DENYLISTED** -- a bigger
    piece than the last several (un-denylist and pull a large concurrency closure, or write a synchronized
    HashMap-backed overlay whose surface is wide enough that a partial one would re-earn the silent-drop
    trap). **Beyond it sits `ServiceLoader` engine discovery**, which needs `META-INF/services` resource
    enumeration out of the jar -- the largest remaining piece. Also open in this closure:
    `LAMBDA IFACE UNRESOLVED org/junit/platform/launcher/LauncherInterceptor$Invocation`, and two
    `UNREGISTERED SUPER (fields alias)` warnings (`URLClassLoader extends SecureClassLoader`,
    `SerializablePermission extends BasicPermission`).
  - **No regression at any step:** `metal junit: ran 44, failures 0` / `ALL PASSED` and host tests unchanged
    (A64 94, object-model 22, class-reader 171, refmap 13, compiler 37, crypto 17, zip 91 -- 0 failures).
  - **PI-VALIDATED over TWO cold boots** (`core 166MHz`, SMP on): both `ran 44, failures 0` / `ALL PASSED`,
    `SMP: 4 of 4`, `smp sched: 4 of 4`, no `BOOT RE-ENTERED`, wild branch or `heap OOM`. **The repetition is
    the evidence**: the two boots differ underneath (`gc: collections=7` vs `8`, different probe/heap counts),
    which is exactly the cold-DRAM and timing variation the enlarged `DEMAND_ZERO_SPAN` has to cover, and
    which QEMU structurally cannot show -- it hands out ZEROED DRAM, so a short span reads clean there and
    wild-branches differently on each real power-on.

- **A vtable slot from an UNRELATED class -- the `setMethod` failure, ROOT-CAUSED AND FIXED (2026-09-01).**
  `ZipOutputStream.close()` threw `IllegalArgumentException: invalid compression method`, from
  `setMethod(I)V`, which nothing in the test calls.
  - **`globalVtableSlot`'s tier 2 matched a method by NAME+DESCRIPTOR ACROSS ANY CLASS** and returned that
    class's slot number. **A slot number is an index into ONE class's flattened vtable** and means nothing in
    another's. The probe caught it on adjacent lines: `ZipInputStream.close()V -> slot 18` and
    `ZipOutputStream.close()V -> slot 18`. At most one can be right; in `ZipOutputStream` slot 18 is
    `setMethod(I)V`, so closing the stream called `setMethod` with a header value.
  - **Tier 2 is now the referenced class's OWN SUPERCLASS CHAIN, most-derived first** -- which is what virtual
    dispatch means. A miss returns -1 and the caller lowers a **late-dispatch site that resolves against the
    RECEIVER's class**, which is exactly right for the cases the old fallback was guessing at.
  - **MY FIRST FIX WAS INCOMPLETE AND THE TEST SAID SO.** I kept the cross-class match for when the ref class
    is unregistered, reasoning that `invokeinterface` needed it. It did not fix the bug, because
    `ZipOutputStream` genuinely IS unregistered at compile time (the trace shows `newresolve` -- a deferred
    `new`), so the guess still fired. **Checking instead of assuming settled it:** `symbols.vtableSlot` is
    called ONLY from `invokevirtual` (`Baseline:1617`); `invokeinterface` goes through `ifSlotOf`. The
    cross-class guess served nothing and is deleted.
  - **`LOAD_LOG` paid for itself on its first real use.** Flipping it on printed the whole resolve sequence
    and showed `close`/`finish` resolving NOWHERE before the throw -- an ABSENCE again -- which moved the
    suspicion off the link stubs and onto slot selection. The `logVtableSlot` tier markers ('Q' qualified,
    'F' fallback, now 'H' chain) then named the tier in one boot.
  - **Pi (`core 166MHz`, SMP on):** `metal junit: ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `smp sched: 4 of 4`, `classpath /lib/junit.jar entries=2135`, `gc: collections=7`, the three denylisted
    `UNRESOLVED STATIC` lines and nothing else -- **no regression from removing a dispatch path used across
    the whole VM.** QEMU the same. Host tests unchanged (A64 94, compiler 37, zip 91 -- 0 failures).
    `DataDescriptorIgnoreCrcAndSizeFields` now runs from line 55 to line 65 -- through the whole zip build and
    byte-patching -- and stops at the SEPARATE `ZipInputStream.readAllBytes` denylist trap.

- **The late pull works on the ON-DEMAND compile paths too -- PI-VALIDATED 2026-09-01, SMP ON.**
  `java/nio/ByteOrder.LITTLE_ENDIAN` read null even though the class is in the image, so the `ByteBuffer`
  stayed BIG-endian and the zip header came out byte-swapped.
  - **ROOT CAUSE: `lzCompiling` is set only around `lazyCompileLocked`'s `compile()`.** The other late paths
    -- **`compileMethodOnDemand` (what `Method.invoke` uses)** and `compileSigOnDemand` -- set
    `compileReuseTib` but not `lzCompiling`, so a `getstatic` there noted nothing, never pulled, and fell
    straight to the zero cell. **Every reflectively reached body was affected**; the zip tests are only where
    it showed.
  - **The retry needs no new machinery:** everything each method uses is re-derived from its own arguments,
    so the retry is that method called again with the class present. `compileMethodOnDemand` also had **no
    `rcMark`/`rsMark` rewind at all**, so the abandoned body's reloc sites would have been patched into memory
    the sweep may already have reused. `odRetried` bounds it to one attempt.
  - **THE REPORT WAS ASSERTING A CAUSE IT NEVER CHECKED.** The line ended `"class never pulled"`
    unconditionally. It now states what it observes -- **denylisted / absent from the classDir / registered
    but no static cell / in the classDir but resolved outside the retry window** -- and the last arm named
    this bug in ONE boot after two rounds of reading had not.
  - **That reclassified the three long-standing `UNRESOLVED STATIC` lines**
    (`CodingErrorAction.REPLACE`, `Normalizer$Form.NFD`/`NFC`): all three say **DENYLISTED (reading null is
    intended)**. They were never defects, and had been read as such for weeks.
  - **MY FIRST CUT OF THE INSTRUMENT LIED.** It asked `classIndexByName`, which searches the LOADED registry
    (`clTab`), not the image directory, and so reported "absent from the classDir" for a class the image
    plainly carries. It asks `VM.dirBytes` now. **An instrument that conflates two tables is worse than none:
    it fabricates a cause.** I also mis-read a `grep` of `kernel8.img` as proof the class was in the
    directory -- it was counting CONSTANT-POOL REFERENCES. Third time this month a new instrument was wrong.
  - **The `UNRESOLVED FIELD (aliases slot 0)` lines for `Pattern$TreeInfo.minLength` are GONE too, and I had
    predicted they would remain.** Same root cause: with the on-demand pull working, `Pattern$TreeInfo` is
    pulled and the field resolves instead of aliasing slot 0. A silent-corruption path closed as a side
    effect of the static fix -- the guard added one commit earlier is what made it visible enough to notice.
  - **Pi (`core 166MHz`, SMP on):** `metal junit: ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`,
    `smp sched: 4 of 4`, `classpath /lib/junit.jar entries=2135`, `gc: collections=7`; the
    `TimeUnit.NANOSECONDS` and `StandardCharsets.UTF_8` lines GONE, the three denylisted ones correctly
    labelled, and nothing else on the wire. Host tests unchanged.
  - **STILL FAILING, and separately:** `DataDescriptorIgnoreCrcAndSizeFields` now gets PAST the byte order
    and dies in `ZipOutputStream.setMethod` with `invalid compression method` -- a different defect the
    unresolved static had been masking.

- **The load-time chatter is OFF by default, and 44 STOCK jtreg TESTS PASS ON THE PI (2026-08-31).**
  `metal junit: ran 44, failures 0` / `ALL PASSED` on hardware (was 38) in a **~100-line boot log**. A boot that resolves normally now says nothing
  about it: the demo suite went 2400 -> 1116 lines, and a `MetalJUnit` run 3000+ -> 90. What is left on the
  wire is the program's own output and anything that actually went wrong.
  - **`Loader.LOAD_LOG` (default false)** gates `linkresolve`/`newresolve`/`staticresolve`/`ifaceresolve`/
    `lambdaslot late`/`baked`/`bakeresolve`/`clinit-lazy`/`typeadopt`/`arrayadopt`/`staticadopt`/
    `lifecycle OK`/`batch N:` and the `vtparity`/`itparity` **OK** lines. **`LIFETIME_TRACE` is false now**
    too -- ~20 lines of allocator histograms PER BATCH.
  - **FAILURES ARE NOT GATED, deliberately.** A parity DIFF still prints, and prints its own header (that is
    what `vtParityHeader`/`ifParityHeader` are for -- the header used to be unconditional and the OK/DIFF
    text merely followed it). Every trap, `UNRESOLVED STATIC`, `NULL CLASS LITERAL` and fault is unchanged.
    The flag suppresses the "went fine" half only: these lines are what made several bugs findable, and a
    log nobody can read is the state those bugs lived in.
  - **Two new guards for silent-corruption paths**, both of the shape this VM keeps getting wrong (answer 0
    and carry on): `UNREGISTERED SUPER (fields alias)` when a class DECLARING fields extends an unregistered
    super -- own fields would be laid at slot 0, on top of the inherited ones -- and `UNRESOLVED FIELD
    (aliases slot 0)` when `globalFieldOffset` matches nothing and returns 16.
  - **The super guard immediately reported `vm/MyExc extends java/lang/RuntimeException`, and that one is
    FINE** -- MyExc declares no fields, and its class comment records that the writer roots `java/*` supers
    on purpose. The report is now conditional on the subclass declaring fields, which is what makes the
    words "fields alias" true. **A new warning's first job is to be checked against a passing boot.**
  - **Suite unchanged otherwise:** `finish HML`, priority inversion, `steps/core 60/59/59/62`, `sum20=210`,
    lambda-thread 42, overload demo exact, `ifacelate`/`ifacedflt`, class literals, WordCount,
    `churnMB=625 live=32 intact=32`, and only the three known `UNRESOLVED STATIC` lines. Host tests
    unchanged.
  - **A GATING LEAK I INTRODUCED, found by READING the quiet log.** The `itparity` OK print was inside
    `if (LOAD_LOG)` and the `vtparity` one was not, so a quiet boot emitted a column of bare ` OK 9` lines
    whose `vtparity <class>` header had been suppressed -- **worse than either state**, since the number
    named nothing. Only visible once the surrounding noise was gone.
  - **+6 TESTS: `DeflaterClose` and `InflaterClose` PASS (3+3) and are wired in.** They had been failing
    through `MetalJUnit` with counters reading back HEAP ADDRESSES (`expected: <3> but was: <67885768>` =
    0x040BE0C8) while passing through the hand-written `ZipJUnitAll`; that symptom is GONE. **The route to
    them was the new field guard firing on something else** -- `Pattern$TreeInfo.minLength` (a nested class's
    field resolving to slot 0) is the same shape as an int counter reading a reference, which sent me back to
    a family I had parked after two refuted hypotheses.
  - **The other six zip classes stay out, with NAMED causes instead of a mystery:**
    `java/nio/ByteOrder.LITTLE_ENDIAN` reads null, so the ByteBuffer stays BIG-endian and the zip header
    comes out byte-swapped (`IllegalArgumentException: invalid compression method` -- the instrument names
    the cause on the line above the failure); and a `DENYLIST TRAP` at `ZipInputStream.readAllBytes`.
    **`java/nio/ByteOrder` IS in `guestsrc`**, so the late-pull path simply did not fire at that site -- that
    is the next thing to chase, not a missing class. **`IntegralPowTest` cannot load at all**
    (`CANNOT LOAD: NullPointerException`): its `<clinit>` needs `java.math.BigInteger`.
  - **QEMU could not finish eight zip classes in 900 s**, so the Pi is the only harness that can judge that
    set -- one more reason not to wire them in before the two causes are fixed.
  - **Pi:** `ran 44, failures 0` / `ALL PASSED`, `SMP: 4 of 4`, `smp sched: 4 of 4`,
    `classpath /lib/junit.jar entries=2135`, the four known `UNRESOLVED STATIC` lines, the two new
    `UNRESOLVED FIELD` lines, `gc: collections=7`, and nothing else.

- **38 STOCK OpenJDK jtreg `@run junit` TESTS RUN ON THE PI: `ran 38, failures 0` / `ALL PASSED`
  (2026-08-31).** Six unmodified test classes -- `SleepSanity`, `SleepWithDuration`, `JoinWithDuration`,
  `RegionMatches`, `NextTokenWithNullDelimTest`, `SplitWithDelimitersTest` -- with the JUnit API and engine
  classes demand-loaded at runtime out of a RAMFS copy of the real console-standalone jar (`classpath
  /lib/junit.jar entries=2135`), none of it baked into the image. `SplitWithDelimitersTest` contributes all
  22 `@ParameterizedTest` argument sets. **The timing tests are real:** `testSleep`, `testInterruptSleep`,
  `testJoinOnTerminatingThread`, `testInterruptJoin` assert wall-clock behaviour against a 166 MHz core.
  - **A CLASS LITERAL FOR AN UNPULLED CLASS BAKED NULL.** `typeOfClass` ends
    `return r >= 0 ? clTab[r].type : 0L`, and `emitAddr` bakes the mirror as an IMMEDIATE -- so unlike a call
    there is no reloc for a later batch to patch: 0 means the literal is null FOR EVER, and the first use is
    an NPE somewhere else entirely. `assertThrows(IllegalThreadStateException.class, ...)` is the shape: the
    class is not pulled until something THROWS one, long after the test body compiled. Fixed with the same
    note-pull-recompile route a static of an unpulled class takes; `staticresolve
    java/lang/IllegalThreadStateException` now precedes the test and it passes.
  - **Diagnosed WITHOUT a boot, by finding the discriminator.** `javap -c -l` put `AssertThrows.java:57` at
    bytecode 10 = `expectedType.isInstance(actualException)` -- an invokevirtual on the literal. The PASSING
    `assertThrows` in `SleepWithDuration` expects `NullPointerException.class`, which IS in the boot closure.
    Same code path, different closure: that settled it before spending a cycle.
  - **MY OWN GUARD REJECTED VALID OBJECTS -- the second too-tight bound this session.**
    `virtualResolve`'s receiver check used `Heap.BASE .. Heap.LARGE_LIMIT`, but `LARGE_LIMIT` is only where
    the SECONDARIES' arenas BEGIN (`Heap.arenaBase`: core N >= 1 lives at `0x1000_0000 + (N-1)*0x0400_0000`).
    It therefore rejected every object cores 1-3 allocate -- most of what a spawned thread touches -- and
    turned a WORKING call into a denylist trap (`BAD RECEIVER recv=0x180072B0`, core 3's arena). New
    `Heap.managedTop()` is the correct bound and says so, because `LARGE_LIMIT` is the obvious wrong reach.
  - **Twice in one session a guard I added was too strict** (this, and `plausibleCode` rejecting BAKED image
    bodies and killing `String.getBytes`). Both were caught only because the instrument broke something that
    had been working. **Check a new guard against a PASSING run, not only against the failure it targets.**
  - **Pi: `metal junit: ran 38, failures 0` / `ALL PASSED`**, with `SMP: 4 of 4`, `smp sched: 4 of 4`, three
    deduped `UNRESOLVED STATIC` lines and nothing else new. QEMU: the three thread classes `ran 13, failures
    0`. Host tests unchanged.

- **`SleepSanity` PASSES — the run-trampoline's blind itable scan was the wild branch. PI-VALIDATED
  2026-08-31, SMP ON.** `metal junit: ran 2, failures 0` / `ALL PASSED` on QEMU, and every threaded demo in
  the suite green on hardware.
  - **The bug was in `buildRunTramp`, and its own comment stated the false premise:** *"Unguarded past the
    sentinel: the tramp is only ever entered with real Runnables, whose dir always carries the entry."* For
    `SleepSanity`'s watcher thread it did not, so the hand-emitted scan **ran PAST the 0 terminator** into
    adjacent memory and branched to whatever it read there.
  - **That explains every observation the earlier probes could not.** The constant was identical across
    different images because the scan walked past the directory into the same fixed region every time; the
    conservative stack scan found no callers because the fault is inside the trampoline on a FRESH task,
    before anything is pushed; and `demo/LambdaThreadDemo` passed because those receivers' directories DO
    carry the Runnable entry, so the scan never reaches the sentinel -- **the demo tested the shape but not
    the condition.**
  - **The old stub also baked Runnable's Type in as a `movz`/`movk` IMMEDIATE**, built once and cached for
    the life of the VM. Looked up fresh now.
  - **The stub is a frame, a call and the dispatch; the lookup is Java** (`Loader.resolveRun`), which
    bounds-checks every intermediate, STOPS at the sentinel, and on failure names the receiver's class and
    returns `denylistTrap` instead of a garbage word. The helper address is writer-stashed like `taskExit`.
  - **A class-chain fallback was written and REMOVED before shipping:** it passed
    `Magic.addrOf(Magic.bytes("run"))` where `resolveLinkTarget` wants a Utf8-shaped pointer (u2 length then
    bytes), so it would have read garbage. The corrected itable path alone is what passes.
  - **Pi (`core 166MHz`, full suite, SMP on):** EVERY thread path exercises the new lookup and all are green
    -- philosophers, `finish HML`, `priority inversion ... 62ms`, `smp threads steps/core 60/60/60/60`,
    `spawned currentThread==self 1 getName=worker-1`, and `lambda thread ran = 42` / `capturing lambda ran =
    105` / `reflective lambda thread = 7`. 29 batches all parity OK, no `RUNTRAMP:` lines, no faults,
    `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK.
  - **Method: the isolated demo that PASSES is not a control unless it reproduces the CONDITION.**
    `LambdaThreadDemo` was built to test `new Thread(lambda)` and did -- but the fault needed a receiver whose
    directory lacks the entry, which it never had. Four hypotheses died to instruments before this one; what
    finally worked was making the suspect code SELF-CHECKING rather than guessing what it did.

- **A late compile PULLS the classes its statics name, and recompiles once — the closure gap CLOSED.
  PI-VALIDATED 2026-08-31, SMP ON.** The gap behind the previous entry's zero cell: RTA computes its closure
  at batch time, so a static read by a body compiled LATER names a class nothing ever pulls.
  - **PULL AFTER THE COMPILE, NOT DURING IT.** A load parses every blob and can collect, and the buffer being
    compiled is not reachable yet -- it would be swept out from under us (the hazard recorded for
    `buildLambdaTib`). `globalStaticAddr` NOTES the class instead, exactly as `noteInitNeeded`/
    `drainPendingInit` already do for the class a getstatic must INITIALIZE; `lazyCompileLocked` drains the
    list once the compile is over and compiles the same body AGAIN with the class present.
  - **Retrying INSIDE `lazyCompileLocked` is what makes discarding the first body safe:** nothing has
    registered it, cached it, or stored it into a TIB slot yet, so no caller can ever have run it. Its reloc
    sites are dropped with it (`rcCount`/`rsCount` rewind) -- patching them afterwards would write into a
    buffer the sweep may already have reused. Once only.
  - **THE BATCH PATH HAD THE SAME HOLE, SILENTLY.** `patchRelocsFrom` patched a static site only
    `if (addr != 0)`, leaving the emitted `movz 0` otherwise -- so a batch-compiled body whose class never
    arrived read ADDRESS 0 too. It points at the zero cell now: deliberately silent and not a verdict, since
    `patchRelocs()` revisits every site from 0 at each batch end and a class loaded later still patches it.
  - **`demo/LambdaThreadDemo` covers three thread shapes nothing else did.** Every other thread demo
    (SmpDemo, PrioDemo, PipDemo) passes a NAMED class, so a Runnable that is a LAMBDA -- whose itable is
    synthesised by `buildLambdaTib` rather than built from a classfile -- had never been exercised at all.
    Plain, capturing, and created inside a reflectively-reached body.
  - **Pi (`core 166MHz`, full suite, SMP on):** `lambda thread ran = 42`, `capturing lambda ran = 105`,
    `reflective lambda thread = 7`, all exact; 29 batches all parity OK, `SMP: 4 of 4`,
    `ticks/core c1=50 c2=50 c3=50`, `sched: 89 preemptions`, `steps/core 61/60/59/60`, `finish HML`,
    `priority inversion ... 62ms`, ExcDemo's seven-frame trace, ManyArgs, the overload and interface demos,
    class literals, WordCount, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`,
    WPA2 -> HTTP 200 OK. Three deduped `UNRESOLVED STATIC` lines, no faults.
  - **QEMU:** `SleepSanity` prints `staticresolve java/util/concurrent/TimeUnit` and `staticresolve
    java/lang/CharacterDataLatin1` and runs on past `Pattern.compile` into the JUnit assertion machinery,
    where it used to die.
  - **STILL OPEN: `SleepSanity` does not PASS.** What remains is a SEPARATE wild branch at `new Thread(...)`
    inside its `testTimeout`, on a freshly spawned task (the stack scan finds no callers). **Ruled out with
    evidence, not argument** -- the closure gap (fixed; the `staticresolve` lines prove it); an unresolved
    static on either the late or the batch path (both read the zero cell); a lambda Runnable, a capturing
    one, and one created in a reflectively-reached body (all three PASS in the new demo, on hardware); the
    run-trampoline chain (every link printed valid -- `dir[0]` matched, itable, slot 0, entry real code); a
    stale itable slot; a literal pool (`emitAddr` is movz/movk, there is none). **Four of those were my own
    confident hypotheses, and each died to an instrument rather than to reasoning.**

- **An unresolved static in a LATE compile read FIRMWARE MEMORY, not null — the SleepSanity wild branch,
  ROOT-CAUSED AND FIXED. PI-VALIDATED 2026-08-31, SMP ON.** The symptom was an instruction abort at
  `0xAA1F03E1580000C0` -- not an address, **two A64 instruction words** -- at a pc no table claims, with an
  empty backtrace and no caller anywhere in the report.
  - **`globalStaticAddr` returns 0 for a static whose class is not loaded**, on the documented premise that
    "the reloc will patch it". That holds for a BATCH compile and is FALSE for a LATE one (lazy body,
    on-demand method, deferred `<clinit>`): batch `patchRelocs` is long past, so 0 is not "patch me later",
    it is the final answer. Emitted as-is the `getstatic` reads **ADDRESS 0** -- the firmware's low-memory
    shim -- whose instructions come back as a plausible-looking 64-bit value. Handed to a dispatch, that is a
    wild branch.
  - **`java/lang/CharacterDataLatin1.instance` is the case:** nothing statically reachable calls
    `StringLatin1.toUpperCase`, so it compiles after its batch and the class is never pulled. **The suite's own
    demos call `toUpperCase` and PASS** (`clinit-lazy java/lang/CharacterDataLatin1` + `HeLLo -> HELLO` in
    StrOpsDemo/WordCount), because there it IS in the batch -- the same "works in one closure, broken in
    another" signature as every other member of this family.
  - **An unresolved late static now points at a permanently-zero cell:** the field reads NULL and the use
    throws an ordinary NPE with a real stack, at the site that actually needs the class. Reported ONCE per
    field (88 lines for two fields on the first boot).
  - **THE LATE PATHS WERE THE UNGUARDED DISPATCHES.** Every inline call site gets `dispatchTargetGuard`; the
    three trampolines that TAIL-BRANCH to a resolved target had nothing. All three are guarded now (link stub,
    late-virtual, lazy compile) plus `virtualResolve`'s RECEIVER, and each names what failed.
  - **THE GUARD BROKE THE BOOT IT WAS ADDED TO DIAGNOSE.** `plausibleCode` first tested for the code arena
    alone, which rejects a **BAKED java.base body** -- those live in the IMAGE, below `Heap.CODE_BASE`. It
    killed `String.getBytes` on first use. The predicate now matches `dispatchTargetGuard` exactly: 4-aligned
    and below the code ceiling. **Every instrument lies at least once; check it against a passing boot.**
  - **The scan that cracked it: "no code-looking words on the stack".** A wild branch at a non-code pc now
    scans the faulting stack conservatively for callers. Getting NOTHING back is what proved the fault was on
    a **freshly spawned task**, which redirected the whole investigation -- three earlier hypotheses (a stale
    baked Runnable Type in the run-tramp, a bad itable slot, a literal-pool miss) each died to an instrument,
    not to argument. The run-tramp chain printed VALID at every link: `dir[0]` matched, `itable=0x0422B5C0`,
    `slot=0`, `entry=0x02043148` real code.
  - **Two more silent instances surfaced the moment the report existed:**
    `java/nio/charset/CodingErrorAction.REPLACE` (needed by String) and `java/text/Normalizer$Form.NFD`/`NFC`
    (needed by Pattern). All three have been reading firmware memory on EVERY boot.
  - **Pi (`core 166MHz`, full suite, SMP on):** exactly three `UNRESOLVED STATIC` lines and nothing else new;
    `SMP: 4 of 4`, `jobs/core 6/6/6/6`, `ticks/core c1=50 c2=50 c3=50`, `sched: 89 preemptions`,
    `steps/core 61/59/60/60`, `finish HML`, `priority inversion ... 62ms`, ExcDemo's seven-frame trace,
    `sum20/weighted20/tally17/wide` all exact, overload demo exact, `ifacelate`/`ifacedflt`, class literals,
    WordCount, 28 batches all parity OK, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610
    stable=1`, WPA2 -> HTTP 200 OK. No FAULT/TRAP/WILD BRANCH/BOOT RE-ENTERED/unclaimed pc.
  - **STILL OPEN, and stated: `SleepSanity` does not PASS.** The class is still never pulled; the wild branch
    is gone and the failure is now a named trap with a nine-frame stack. Closing the closure gap means pulling
    the class **BEFORE** a late compile, never DURING one -- a load parses every blob and can collect, and the
    in-progress code buffer is not reachable yet (the hazard already recorded for `buildLambdaTib`).

- **`invokeinterface` RESOLVES LATE — the last RTA blind spot at the site is closed. PI-VALIDATED 2026-08-31,
  SMP ON.** RTA runs at batch time, so an interface named only by a body compiled LAZILY -- after its batch --
  is never pulled. The call site then holds a target interface Type of 0 AND the receiver's itable DIRECTORY
  has no entry for it, so the scan fell off the 0-terminator as a bare NPE. `newresolve`/`linkresolve` already
  covered a deferred `new` and a deferred call; this is the interface counterpart.
  - **`java/lang/String.split` is the case in hand:** it compiles on demand and does
    `list.subList(0,n).toArray(result)`, an `invokeinterface` on `java/util/List`, which appears NOWHERE in
    that image. **The tell was an ABSENCE, not an error** -- and the suite's own ArrayList demo prints
    `subList(1,3).size=2 sub[0]=q sub[1]=r` perfectly, which is what isolated the fault to the LAZY-COMPILE
    CONTEXT rather than to `subList`.
  - **The miss path needed NO new machinery.** An interface call's target is whichever class NEAREST THE
    RECEIVER declares that name+descriptor -- exactly what the existing `VIRTUAL_RESOLVE` trampoline answers
    (the one the null-vtable-slot guard already substitutes). At the 0-terminator the compiler puts the site
    index in x17 and the trampoline in x16 and jumps to the dispatch's own `blr`: x17 held the directory
    cursor and x16 the interface Type, both dead there, and x0 (the receiver) is untouched by the search. The
    itable load and its guard are skipped -- x16 is already a call target. Three instructions, no new state.
  - **Interface DEFAULTS needed one more tier.** No class declares a default, so the receiver's chain cannot
    find one -- and a class-typed receiver reaches a default THROUGH THE ITABLE precisely because it has no
    vtable slot for it, so a missed directory strands exactly the case the chain walk cannot answer.
    `virtualResolve` falls back to the interfaces of the receiver's chain, two levels deep (a class's own,
    then each of those interfaces' own), covering `ArrayList -> List -> Collection` without recursion.
  - **The interface name ADDRESSES are collected BEFORE any resolving.** `resolveLinkTarget` parses whatever
    class it is handed and clobbers `gcp`/`gbase`, so an offset read afterwards would be against the WRONG
    BLOB. Absolute addresses survive that; offsets do not.
  - **New `ifaceresolve` line, unconditional like `linkresolve`/`newresolve`.** The absence of any line at all
    is what hid this whole class of bug.
  - **`demo/RtaLate` + `demo/RtaLater` are a TRUE reproduction, not a demo that would pass anyway:** the boot
    log prints `newresolve demo/RtaLater` and NEVER mentions `demo/RtaLate`, so the interface really is absent
    and the directory really is missed. This is NOT what the existing `ifaceprune` arm pins -- there the
    interface IS present and only the slot's ENTRY was empty, and the two guards throw different exceptions
    (directory miss = NPE, implausible slot = AIOOBE).
  - **Pi (`core 166MHz`, full suite, SMP on):** `ifacelate = late-iface`, then
    `linkresolve demo/RtaLater.viaDefault` / `linkresolve java/lang/Object.viaDefault` (the chain runs to the
    root and fails) / `linkresolve demo/RtaLate.viaDefault` / `ifaceresolve demo/RtaLater.viaDefault` /
    `ifacedflt = late-default` -- the whole walk, visible. Plus `SMP: 4 of 4`, `jobs/core 6/6/6/6`,
    `ticks/core c1=50 c2=50 c3=50`, `sched: 89 preemptions`, `steps/core 60/61/60/59`, `finish HML`,
    `priority inversion ... HIGH blocked 62ms`, ExcDemo's seven-frame trace, 28 batches all parity OK,
    `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK.
  - **QEMU:** `SplitWithDelimitersTest` 15/22 -> `metal junit: ran 22, failures 0` / `ALL PASSED`, with
    `java/util/List` STILL never loaded -- it resolves without pulling the interface.
  - **Known limit:** a default inherited three interfaces deep still fails, and says so by name.

- **`@ParameterizedTest` on the metal, and the TWO VM BUGS it found — PI-VALIDATED 2026-08-31, SMP ON.**
  Wiring more stock jtreg `@run junit` tests through `MetalJUnit` needed `@ParameterizedTest`/`@MethodSource`,
  and building it uncovered two defects with nothing to do with JUnit.
  - **`@MethodSource` is supported in its DEFAULT-NAME form ONLY** — the factory is the static method with the
    test's name. Not a shortcut: reading `@MethodSource("name")`'s element VALUE needs a live annotation
    instance (a Proxy), which this VM has no runtime for, while `isAnnotationPresent` is answered from the
    classfile. Stream, Iterable AND plain-array factories all work (a stock factory returns `Arguments[]` as
    readily as `Stream<Arguments>`; the array is indexed directly, costing no stream closure).
  - **OVERLOADS COLLAPSED UNDER `getDeclaredMethods()`.** It walked the classfile by NAME and resolved each
    entry by NAME, so a class with two same-named methods handed back the SAME `Method` twice and the other
    overload was unreachable — parameter count, annotations and body alike. A `@ParameterizedTest` is exactly
    that shape (test + same-named factory): it reported the TEST's annotation for both while the factory could
    not be found at all. Fixed end to end — `Class.declaredMethodDescAt0` gives the n-th method's DESCRIPTOR
    beside its name, `Method.resolve(Class,String,String)` resolves on both, and **`compileMethodOnDemand`
    takes a descriptor filter**. That last piece is not optional: without it the registry lookup was exact but
    the on-demand compile behind it was still a coin toss between two bodies, and the symptom did not move.
  - **A DEFERRED `new` DID NOT INITIALIZE ITS CLASS.** JVMS 5.5 makes instance creation an active use, so
    `<clinit>` must run before the object exists and certainly before the `<init>` the JIT calls next.
    `resolveUnresolvedNew` never called `ensureClinit`, so the initializer ran only when some LATER path forced
    it (a link-resolved call into the same class) — by which time `<init>` had read its statics as 0.
    `java/util/ArrayList.<init>` reads `DEFAULTCAPACITY_EMPTY_ELEMENTDATA`, so a deferred `new ArrayList<>()`
    produced a list with a null `elementData` whose first `add()` threw NPE. **The boot log had been saying it
    outright** — `newresolve` and `<init>` ahead of `clinit-lazy` for one class; it now reads
    `newresolve -> clinit-lazy -> <init>`.
  - **An uncaught exception with an EMPTY backtrace now names the fault that made it.** `captureTrace` cannot
    walk from a pc it was never given, which in practice means a wild branch (`blr 0` aborts, `throwFromFault`
    turns the address trap into an NPE, and `unwind` is handed ELR = 0). Reported bare that is an exception
    name with NO location — the least diagnosable thing this VM can print — so `fault0Esr/Elr/Far` is printed.
  - **Pi (`core 166MHz`, full suite, SMP on):** `demo/OverloadReflectDemo` prints `overloads named pick = 3`,
    `by parameter count 0/1/2 = 1/1/1`, `invoked = none int:7 two:42`, all exact; `SMP: 4 of 4`,
    `jobs/core 6/6/6/6`, `ticks/core c1=50 c2=50 c3=50`, `sched: 89 preemptions`, `smp sched: 4 of 4`,
    `steps/core 61/59/60/60`, `finish HML`, `priority inversion HML 62ms`, 28 batches all parity OK, ExcDemo's
    seven-frame trace, `newresolve demo/RtaMade` + `linkresolve demo/RtaMade.<init>`, every class literal
    correct, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK.
  - **QEMU:** `SplitWithDelimitersTest` runs all 22 argument sets (was 2 x `NO FACTORY`), 15 pass;
    `RegionMatches` 2/2 and `NextTokenWithNullDelimTest` 1/1 pass through `MetalJUnit`.
  - **STILL OPEN — the `invokeinterface` twin of late resolution.** The 7 remaining `SplitWithDelimitersTest`
    failures are a bare NPE from the **itable directory-miss sentinel** inside
    `String.split`'s `list.subList(0,n).toArray(result)`. `java/util/List` appears NOWHERE in that boot log:
    `String.split` is compiled LAZILY, after its batch, so nothing pulled the interface it dispatches on.
    `newresolve`/`linkresolve` have no interface counterpart. **The suite's own ArrayList demo prints
    `subList(1,3).size=2 sub[0]=q sub[1]=r`** — so `subList` is fine and the fault is purely the lazy-compile
    CONTEXT. Also still open: `SleepSanity` wild-branches to `0xAA1F03E1580000C0` after
    `linkresolve java/util/Locale.getLanguage` (so the three `Thread` `*Run` companions cannot be retired yet),
    and `AccessFlagNullCheckTest` compiles but DENYLIST TRAPs on `AccessFlag.valueOf`.
  - **Of 273 stock `@run junit` tests in `java/lang` + `java/util`, 61 compile against the guest overlay**
    (excluding any needing `@library`/`@modules`/`jdk.test.lib`); most of the rest need tz/locale data or the
    module machinery.
  - **Method note: `grep` treats a UART log as BINARY** (stray control bytes) and silently prints nothing.
    Several greps "proved" the demo never ran while its output was there all along — use `grep -a`.

- **`Character.toString` — FIXED, PI-VALIDATED 2026-08-31.** The overlay never declared it, so it inherited
  `Object`'s and printed `java.lang.Character@71`. A missing overlay member does NOT trap -- it silently
  inherits, and here the right answer was in the output all along: `0x71` is `hashCode()`, which for
  `Character` is the char itself. Boolean/Byte/Short all carry theirs. **The overlay-drops-stock-members trap
  for the FIFTH time** (StringBuilder/Appendable, Class.getPrimitiveClass, the wrappers' TYPE,
  Throwable.initCause), and the class comment now says so.
  - **`vtparity java/lang/Character` stays at 12** -- `toString` is one of Object's nine virtuals, so an
    override FILLS an existing slot instead of widening. Contrast `Throwable.initCause`, which took parity
    16 -> 18 because those were genuinely new methods. Unchanged parity here is correct, not a null result.
  - QEMU: `LambdaAdaptProbe` reports `char -> q`, direct and through `Method.invoke`. Pi: full suite clean,
    27 batches all parity OK, `Character OK 12` throughout -- the suite prints no Character, so the boot
    confirms NO REGRESSION and the probe is what proves the fix.
- **FOUR consecutive clean full-suite Pi boots with SMP ON** (stop-the-world fixes, coverage restore,
  argument overflow, this): `SMP: 4 of 4`, `jobs/core 6/6/6/6`, `ticks/core c1=50 c2=50 c3=50`,
  `sched: 89 preemptions`, `steps/core ~60` each, `finish HML`, `priority inversion HML 62ms`, ExcDemo's
  seven-frame trace, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP
  200 OK. That retires the "one clean boot" caveat on the SMP arc.

- **More arguments than argument registers — FIXED, PI-VALIDATED 2026-08-31.** x0..x15 carry arguments, so a
  17-parameter method had nowhere to put the 17th and the JIT refused to compile it: `JIT unsupported:
  reason=11` (`FAIL_ARG_COUNT`), from BOTH halves of the same limit. That is what stopped one SMP suite boot.
  - **Past `MAX_ARG_REGS` the LAST register changes meaning:** x0..x14 carry arguments 0..14 and **x15 carries
    a POINTER** to the rest, which the caller stages in its own frame.
  - **Deliberately NOT the AAPCS scheme of pushing overflow arguments below SP.** `VM.unwind` reads a frame's
    saved LR at `[sp+0]` and pops by the recorded `frameSize`, so a caller that moved SP across a call would
    leave the walker off by the overflow whenever an exception unwound through it -- silently, and far from
    the cause. `ExcDemo`'s seven-frame trace on the validating boot is what says the walker and the compiler
    still agree, now that `frameSize` includes the new area.
  - **The area sits at the END of the frame**, after the operand spill, so every other offset is unchanged and
    a method making no such call compiles BYTE-FOR-BYTE as before -- which keeps the self-hosting fixpoint,
    and is what `compiler: 37 checks` passing asserts.
  - **Only the deep-operand path needed changing:** pushing more than `MAX_ARG_REGS` arguments takes the
    operand stack past `OP_MAX`, which is exactly what sets `deepStack`. The register path keeps the guard
    rather than assuming that -- it has no memory to put an overflow argument in and would pass garbage.
  - **`demo/ManyArgsDemo` (in the suite)** pins four shapes: a 20-argument static call; an INSTANCE call,
    where the receiver takes x0 so the boundary falls one argument earlier than the parameter count suggests;
    a weighting BY POSITION, so a permuted or duplicated argument changes the answer rather than passing by
    luck; and a bare `long` past the boundary -- one argument register but TWO local slots, the stepping this
    VM has got wrong before.
  - **Pi (`core 166MHz`, full suite, SMP on):** `sum20 = 210`, `weighted20 = 2870`, `tally17 = 1153`,
    `wide = 7000000155`, with `SMP: 4 of 4`, `jobs/core c0=6 c1=6 c2=6 c3=6`, `ticks/core c1=50 c2=50 c3=50`,
    `steps/core 60/60/60/60`, `finish HML`, `priority inversion ... HML ... 62ms`, 27 batches all parity OK,
    `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK.
  - **The demo's first run printed `wide = 7000000155 (want 7000000138)` and the VM was RIGHT** -- 1..15 sums
    to 120, not 103. Check the expected constant before believing a one-arm failure.

- **Three lambda-adaptation faults, found by a stock `@run junit` test — PI-VALIDATED 2026-08-30 (SMP off).**
  Running the unmodified jtreg `java/util/StringTokenizer/NextTokenWithNullDelimTest` through `MetalJUnit`
  failed with NPE, while every hand-written replay of its exact call sequence PASSED — including reflectively.
  Three separate bugs were stacked behind that one symptom.
  - **(1) A method REFERENCE returning a primitive into a generic SAM was not boxed.** javac adapts a lambda
    BODY itself (the synthetic method carries the instantiated signature), so only a method reference reaches
    `LambdaMetafactory` unadapted — and joe-ng synthesises the lambda class, so the conversion must be emitted
    into the thunk. `assertDoesNotThrow(st::hasMoreTokens)` handed the boolean 1 back **as an address**. New
    `vm/VMBox.box(JI)J` calls the wrappers' own `valueOf`, so a boxed value from a method reference is the same
    object (cache and all) as an ordinary autobox. **Float/double are excluded on purpose** — the result
    arrives in d0, not x0 — and such a reference is REPORTED by name rather than mis-adapted.
  - **(2) A lambda's functional interface was pulled by NOTHING.** It is named only as the return type inside
    the indy's own descriptor, never as a `CONSTANT_Class`. It normally arrives with the lambda's CONSUMER
    (which names it in an `invokeinterface`) — which is why lambdas work at all. A lambda whose consumer is
    not in the batch resolved to Type 0, **which is also the itable directory's END SENTINEL**, so the lambda
    satisfied no interface and every dispatch on it threw NPE from the directory miss, inside whatever library
    received it. Pulled now in three places: with the class (`pullIndyIfaces`), with RTA's ref collection
    (`collectBlob`), and as an ordinary dependency (`probeAll` — whose dep list needed an explicit BASE, since
    the name lives mid-descriptor with no Utf8 length prefix of its own).
  - **(3) A bound method reference on a LATE-pulled class branched into the heap.** `globalVtableSlot` answers
    -1 when the class has no vtable numbering yet, and `8 + -1*8` is 0 — TIB[0], the Type pointer. Such a thunk
    now goes through the late-dispatch trampoline, resolving against the receiver at first call.
  - **A demand-load INSIDE `buildLambdaTib` was built and reverted.** A load parses every blob and can collect,
    and the in-progress code buffer is not reachable yet — it was swept, and the method ran off a zeroed buffer
    (`ec=0` at offset +0). Pulling with the CLASS avoids the question entirely.
  - **A guard I added was a WILD READ, and only hardware showed it.** `nameAlreadyPending` scanned `pdNameOff[]`
    for blobs added since the last `probeAll` — and `probeAll` is what fills that array, so a just-pulled blob's
    entry is whatever the previous batch left there. Harmless against QEMU's zeroed DRAM; on a Pi it read
    arbitrary memory and produced a DIFFERENT failure on each of two boots. It was also unnecessary (`addBlob`
    already dedupes by ADDRESS). The pull goes through `registerNameFromDir` now, which dedupes that way and
    **honours the DENYLIST** — `pullClass(byte[])` goes straight to the classDir, the same bypass recorded for
    the deferred-`new` path.
  - **Both halves of the old guard were load-bearing.** Removing it outright re-added an already-registered
    class's blob, re-registered the class, and printed `lifecycle DIFF java/lang/String state=2` then a wild
    branch. The surviving check asks the CLASS REGISTRY (`regBySigU`), which is valid state at that moment.
  - **Instruments, because an index is not a name:** a null lazy compile now prints the class, method and
    descriptor it failed on (`CAP EXCEEDED ... count=834` was a slot in a table rebuilt every batch), an
    unresolved lambda interface prints its NAME (it was a silent 0), a lambda thunk taking the late-slot path
    says so, and `MetalJUnit` prints the CAUSE of an assertion that WRAPS a throwable.
  - **Pi (`core 166MHz`, full suite, SMP off):** every batch clean, `churnMB=625 live=32 intact=32`,
    `lisp evals=600 result=610 stable=1`, `linkresolve demo/RtaUnseen.tag` / `newresolve demo/RtaMade` /
    `linkresolve demo/RtaMade.<init>` all intact, class literals correct, WPA2 -> HTTP 200 OK (828 bytes).
    **SMP off is not a property of this change** — the suite fails with SMP on for a reason that reproduces on
    main; see the entry below.
  - **QEMU:** `metal junit: ran 3, failures 0` for `RegionMatches` + `NextTokenWithNullDelimTest`, the first
    stock `@run junit` tests to run on the metal. `test/jdk/junit/LambdaAdaptProbe` pins all three faults over
    six primitive kinds, run directly AND through `Method.invoke` — **only the reflective arm reproduces (2)
    and (3)**, because a reflectively reached method compiles after the batch that would have pulled what it
    needs. `scripts/run-junit.sh` drives `MetalJUnit` on QEMU.
  - **Of 273 stock `@run junit` tests in `java/lang` + `java/util`, 65 compile against the guest overlay**;
    most of the rest need tz/locale data, `@ParameterizedTest`, or the module machinery.

- **Stop-the-world: three defects fixed; the remaining SMP hang is now ISOLATED to PipDemo-in-the-suite
  (2026-08-30, branch `smp-stop-the-world`, NOT merged).** Reading `stopTheWorld` found three independent ways
  the world can fail to stop, each letting mark-and-sweep run against a live mutator:
  - **(1) On timeout it collected ANYWAY.** The doc comment said "rather than mark against a running mutator
    we give up after ~1 s"; the code counted, printed and returned, and `gcCollect` marched on. That is the
    corruption mechanism. It SKIPS the collection now -- the allocator retries once then halts with a loud
    `heap OOM`, a strictly better failure than a corrupted heap.
  - **(2) It waited for a HARDCODED THREE cores.** A core that left the run queue (drained by `smpStop`, or
    one that never joined -- `smp sched: 3 of 4`) has no timer and no yield point, so it can never park:
    waiting for it times out EVERY collection, for ever. Now it waits for the cores actually scheduling, and
    the entry check asks `coreSched[]` rather than `smpSched` -- `stopSmpScheduling` clears that flag after
    waiting only ~1 s for the secondaries to drain, so a straggler left it 0 while still mutating.
  - **(3) `gcParked[]` was a FLAG, and flags go stale.** A core clears its flag only AFTER leaving the park,
    so between two collections the flags still read 1 and a stop-the-world that believed them would collect
    with three cores running. It is a GENERATION now, published BEFORE `gcStop` with a `dsb` between -- without
    that ordering a core can observe the stop but the old generation and park uncounted.
  - **Pi result: `GC: STW TIMEOUT` is GONE** (it preceded every previous SMP-on failure), and so are
    `BOOT RE-ENTERED` / `unclaimed pc` / `heap OOM`. The suite now HANGS SILENTLY instead, at
    `clinit-lazy demo/PipDemo` -- reproduced on QEMU, so the Pi is no longer needed to chase it.
  - **What the hang is NOT:** watchdogs added to `gcPark` (>10 s parked) and `VM.loaderLock` (>10 s waiting,
    naming the owner and its state) did not fire in a 300 s run. And **PipDemo STANDALONE with SMP on passes**
    -- `finish HML`, `HIGH blocked 65ms`, twice, in 2 s. So it is the SUITE CONTEXT, exactly as this demo's
    own entry warns: its failures have only ever been visible there.
  - **Not a regression of the fix.** Pre-fix, the same boot FAULTED at PipDemo; the control on main died at
    `Thread.start`. Preventing the corruption unmasked a hang that was already there.
  - **A console-lock attempt was built and REVERTED.** Ordinary `Uart.write` is unlocked, so every SMP log
    interleaves byte-by-byte and the evidence is destroyed in exactly the boots that need reading. Locking it
    naively deadlocks (a core parked mid-message never releases), so the attempt masked IRQs for the message
    -- which is MILLISECONDS with interrupts off per line at 115200 baud, and wrecked the scheduler demos. The
    idea is right; it needs a per-core buffer flushed under a short lock, not a lock held across the UART.
  - **QEMU cannot gate SMP work:** merged main itself flakes at the scheduler set-piece about one boot in
    three (measured 2 of 3 passes on main). Measure the baseline before reading any SMP result as a regression.

- **THE SMP SUITE FAILURE IS FIXED, WITH FULL CORE COVERAGE — PI-VALIDATED 2026-08-31, SMP ON.**
  `SMP: 4 of 4 cores up`, `smp jobs 321010` / `jobs/core: c0=6 c1=6 c2=6 c3=6` (was all core 0),
  **`ticks/core: c1=50 c2=50 c3=50`** -- the secondaries' own preemptive timers, the one line QEMU
  structurally cannot show -- `sched: 89 preemptions`, `smp sched: 4 of 4`,
  `steps/core: c0=61 c1=60 c2=59 c3=60`, `finish HML (want HML) steps 20/20/20`, `priority inversion ...
  finish HML ... HIGH blocked 62ms`, 26 batches all parity OK, `churnMB=625 live=32 intact=32`,
  `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK. No STW TIMEOUT, watchdog, BOOT RE-ENTERED,
  `unclaimed pc` or `JIT unsupported`. The console is legible again too: the byte-by-byte interleaving was
  cores racing through the set pieces, not a UART problem.
 `priority ... finish HML
  (want HML) steps L/M/H = 20/20/20`, `priority inversion (guest Thread): finish HML ... HIGH blocked 62ms`
  (PipDemo, the demo that hung), all 26 batches with every parity OK, `churnMB=625 live=32 intact=32`,
  `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK. No STW TIMEOUT, no watchdog, no BOOT RE-ENTERED,
  no `unclaimed pc`. Four defects, in two groups:
  - **Stop-the-world let the collector run against a mutator, three ways.** (1) On timeout it **collected
    anyway** -- the doc comment said it gave up, the code counted, printed and marched on; that is the
    corruption mechanism, and it SKIPS now (the allocator retries once then halts with a loud `heap OOM`).
    (2) It waited for a **hardcoded three** cores; a core that left the run queue has no timer and no yield
    point, so waiting for it times out EVERY collection for ever -- it waits for the cores actually
    scheduling, and the entry check asks `coreSched[]`, not `smpSched` (which `stopSmpScheduling` clears
    after only ~1 s of draining, leaving a straggler mutating). (3) **`gcParked[]` was a FLAG, and flags go
    stale** -- a core clears its own only AFTER leaving the park, so a second collection reads leftover 1s and
    concludes the world is stopped. It is a GENERATION now, published BEFORE `gcStop` with a `dsb` between;
    without that ordering a core can see the stop but the old generation and park uncounted.
  - **`resetTaskTable` wiped the table under the secondaries.** It points EVERY core's `coreTask` at task 0
    and drops `taskCount` to 1, so doing it while cores 1-3 schedule puts four cores on the boot flow's single
    stack -- the "one task, two cores" hazard `claimable` exists to prevent. `prioDemo` did exactly that
    IMMEDIATELY BEFORE launching PipDemo, whose first act is a `gate` handshake: main then waited on a monitor
    nobody was left to notify. `stopSmpScheduling`'s own doc says draining is what makes the reset safe, and
    `smpThreadsDemo` brackets itself correctly; **the drain lives inside `resetTaskTable` now**, because two
    callers did not remember. It also explains the `finish LMH` seen earlier: the wipe destroyed priority
    ordering too.
  - **Why the suite had secondaries at all:** `launchSmp()` only looks for an `smp=` line, so an EMPTY BUT
    PRESENT `/etc/init` -- exactly how the no-manifest suite image is built -- reads as "SMP on". That is also
    why `smp=0` never gated the suite and `VM.SMP_ENABLED=false` did.
  - **The suite's SMP set pieces were DEAD, and that was pre-existing, not this change.** `smp jobs` ran
    entirely on core 0 (`jobs/core: c0=24 c1=0 c2=0 c3=0`), `ticks/core` was all zeros and `smp threads` said
    `1 of 4` -- and merged main gives the same on QEMU, so it long predates this work. Cause: `launchSmp()`
    consulted only `smp=`, so the EMPTY manifest handed cores 1-3 to the LAUNCH path at boot, BEFORE
    `smpDemo = 1` was set. The secondaries went straight to the scheduler loop, skipped `smpWork`/
    `pcCoreMain` entirely, and the suite's own `bringUpSecondaries` then found only core 0 (`SMP: 1 of 4
    cores up`). That block exists for a launched PROGRAM: with no `main=` there is no program to give the
    cores to, so `launchSmp()` requires one now. Restored: `jobs/core: c0=4 c1=6 c2=9 c3=5`,
    `smp sched: 4 of 4`, `steps/core: c0=59 c1=60 c2=61 c3=60`. (`ticks/core` stays 0 on QEMU -- no timer PPI
    reaches a secondary there.)
  - **I called this a regression of my own change and it was not.** The evidence that settled it was one
    grep of an existing control log, not a new boot: main gives `1 of 4` too.
  - **NOT REPRODUCED, NOT FIXED:** the previous boot died with `JIT unsupported: reason=11 a=0x11 b=1` --
    `FAIL_ARG_COUNT`, callee half, a method with 17 parameters against `MAX_ARG_REGS = 16`. It did not recur
    on the passing boot with no relevant change between them, so it is intermittent and still open. `jitFail`
    names the class now.
  - **Method, at a cost of four wasted boots: BUILD THE CONTROL FIRST.** Three Pi boots were spent chasing
    this as a regression of the branch under test; one control boot on `main` settled it. And QEMU cannot gate
    SMP work -- merged main flakes at the scheduler set-piece about one boot in three (measured).
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
- **BOOT RE-ENTERED: a natively instantiated class needs a hole-free vtable (2026-08-30, PI-VALIDATED).**
  Printing a `StackTraceElement` from guest code wild-branched to the image entry — no exception, no name.
  - **Narrowed, then named.** `getStackTrace()` itself works (the probe prints `frames = 5` first), so the
    crash is `"at " + element`. The report's `x30=0xBA8E0` is an image address the frame printer cannot name;
    `JOENG_SYMMAP=1` places it inside `java/lang/String.valueOf(Ljava/lang/Object;)` at +0x38 — its
    `obj.toString()` dispatch. **That symmap lookup is the technique for any unnameable image PC.**
  - **BAKED code carries no `dispatchTargetGuard`** (`implicitChecks()` is false for the writer, by design)
    yet baked `String.valueOf(Object)` dispatches on whatever guest object it is handed. `StackTraceElement`
    is instantiated NATIVELY by `frameToElement`, so RTA pruned its `toString` and the slot stayed 0. `blr 0`
    is a branch to address 0, which the firmware's low-memory shim turns into image re-entry.
  - **Fix reuses `stubOnly`** — give a blob's virtuals deferral stubs so its vtable has no holes WITHOUT
    marking them reachable (pulls nothing). It was gated on one blob (`Class.forName`'s incremental path) and
    now also covers `nativelyInstantiated` classes. `seedNativelyReached` already marked ONE method
    (`getMethodName`) for this reason; marking one method of a class the VM hands out whole is arbitrary.
  - **`java/lang/Class` is deliberately NOT listed** (the other native instantiation): its mirrors work
    today, and widening without a failing case would be a change with no evidence. That is the list to add to.
  - **NOT fixed, and stated:** baked java.base dispatching unguarded on guest objects is the general hazard.
    Any other pruned `toString` reachable from baked code reproduces this. Closing it means guarding the bake
    domain (perturbs the self-hosting fixpoint) or minting far more widely (3-4x the code arena).
  - **Pi (`core 166MHz`, full suite):** all parity OK, ExcDemo's seven-frame trace, `linkresolve
    demo/RtaMade.<init>`, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`,
    WPA2 -> HTTP 200 OK. The suite does not print a StackTraceElement through `String.valueOf`, so this
    confirms NO REGRESSION; the fix itself is QEMU-proven by `AssertMsgProbe`.

- **A deferred `new` SKIPPED its constructor — ROOT-CAUSED AND FIXED (2026-08-30, PI-VALIDATED).** `MetalJUnit`
  reported `NullPointerException` for a test whose assertion is correct in a probe, including reflectively.
  The NPE was in `java/util/Formatter.format` on its own `out` field, under `String.formatted`, under
  `AssertionFailureBuilder.formatValues`.
  - **A `ctorRan` flag set in the constructor read 0.** The object arrives with its constructor never having
    run, so every field is 0. The condition is in the log: `String.formatted` is compiled BEFORE
    `java/util/Formatter` is registered, so its `new Formatter()` defers (`newresolve java/util/Formatter`)
    and the `invokespecial <init>` beside it never executes. An image where Formatter is in the batch has no
    `newresolve` line and constructs normally — that difference is the whole reproduction.
  - **NOT the documented cause.** `isRealSpecial` skipping an `<init>` for an unregistered class was fixed by
    `classLoadable`; instrumenting it to print EVERY skip showed it firing only for an unrelated
    `java/nio/charset/UnmappableCharacterException`, never for `Formatter.<init>`. The link-stub path is out
    too: `linkresolve java/util/Formatter.format` appears while `.<init>` never does. So the call was emitted
    and still did not run.
  - **ROOT CAUSE: `globalBufByRef`'s superclass-chain tier applied to `<init>`.** Tier 1 (class+name+desc)
    misses for an unregistered class; tier 2 then walks the ref class's SUPER CHAIN for the same
    name+descriptor — right for an inherited static/special (`ArrayList.subListRangeCheck` really on
    AbstractList), WRONG for a constructor, because constructors are never inherited. `Formatter.<init>()V`
    matched `java/lang/Object.<init>()V` — always registered, Object being the one eagerly compiled class —
    whose body is a NO-OP. The call resolved silently to it. `<init>` now skips the walk and falls through to
    the stub tier. **The missing log line was the evidence all along:** `linkresolve …format` appeared while
    `.<init>` never did.
  - **The workaround was REMOVED** — `Formatter` is a plain final field again and still reports
    `FAIL deliberateFailure -> org.opentest4j.AssertionFailedError: expected: <1> but was: <2>` /
    `metal junit: ran 4, failures 1`. That is what proves the VM fix rather than the guard.
  - **Pi evidence is direct, in the suite:** `demo/RtaMade` is a real deferred `new`, and the boot now prints
    `newresolve demo/RtaMade` / `linkresolve demo/RtaMade.<init>` — a line absent from every earlier boot.
    That demo had been constructing a half-built object all along.
  - **Method notes.** `printStackTrace()` works from guest code where `getStackTrace()` wild-branched
    (`BOOT RE-ENTERED` after `bakeresolve java/lang/Throwable.stackTrace0` — also still open). And a null
    guard that PRINTS what it found answers "did the constructor run" and unblocks the run in one boot.
  - **Pi (`core 166MHz`, full suite):** all parity OK incl. `java/lang/Throwable OK 18`, ExcDemo's
    seven-frame trace intact, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`,
    WPA2 -> HTTP 200 OK.

- **A JUnit assertion failure carries its message now (2026-08-30, PI-VALIDATED).** Three bugs between the
  runner and a readable failure, found one behind the other.
  - **Late virtual dispatch did not walk the SUPERCLASS CHAIN.** `resolveLinkTarget` answers only for the
    class it is handed (`bufBySigU`, `nativeBufAt` and `compileSigOnDemand` all work off that class's own
    blob), so an INHERITED method resolved nowhere. `AssertionFailedError` inherits `initCause`/
    `getStackTrace` from `Throwable`: the guard fired on the pruned slot, the resolve looked only at
    `AssertionFailedError`, and the call landed in `denylistTrap`. Walking most-derived-first IS virtual
    dispatch. **`TRAPWIRE index=-1` was the tell** — no patch-time site is recorded for a late-resolved call,
    so a denylist trap with no index is NOT a denylisted class. A failed resolve now names the class and
    signature it could not find.
  - **`java/util/Formatter` was a STUB returning `""`,** and its own comment stated the premise: "because the
    format path is compiled but never executed on metal, the trivial bodies are never actually run". False —
    JUnit's `formatValues` builds the whole message through `String.formatted`. Now a real minimal printf
    (`%s %S %d %x %X %o %c %b %B %% %n`); flags/width/precision are parsed and DROPPED rather than
    mis-applied, and an unknown conversion is emitted verbatim rather than vanishing.
  - **`java/lang/Throwable` took a `cause` in two constructors and DISCARDED it,** with neither `initCause`
    nor `getCause` declared — so `initCause` was not in the vtable at all. The overlay-drops-stock-members
    trap for the FOURTH time (after StringBuilder/Appendable, Class/getPrimitiveClass, the wrappers' TYPE).
    vtparity 16 -> 18 across every exception class. The `cause` field goes after `detailMessage`: bt0..bt7
    (obj+16..+72) and the message (obj+80) are hardcoded in the VM.
  - **javac had been reporting the third one every build** — `no virtual method
    initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable; in java/lang/AssertionError` — in a `bake-stub`
    line that was being filtered out as noise. **Read the bake-stub lines.**
  - **Both message fixes were proven directly, not inferred:** `FormatProbe` (every conversion, `%%`, null
    args, unknown conversions) and `AssertMsgProbe` (`Assertions.assertEquals(2,3)` direct AND through
    `Method.invoke`, both `message = [expected: <2> but was: <3>]`).
  - **Pi (`core 166MHz`, full suite):** `vtparity java/lang/Throwable OK 18` and every exception class at 18
    in all 26 batches, ExcDemo's seven-frame trace intact, `churnMB=625 live=32 intact=32`,
    `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK (828 bytes).
  - **STILL OPEN:** `SampleTest.deliberateFailure` reports NPE through the MetalJUnit runner while the
    identical assertion is correct in the probe, including reflectively — a closure/pruning difference in
    that image, not the message path. And a `BOOT RE-ENTERED` wild branch after
    `bakeresolve java/lang/Throwable.stackTrace0`, hit while printing traces from the runner; the print was
    backed out rather than chased.

- **A backtrace crossing a resolve trampoline is trustworthy now (2026-08-30, PI-VALIDATED).** A MetalJUnit
  failure reported NPE at `Loader.virtualResolve`'s bare `Magic.load64` — in check-free image code, which
  cannot throw one. The whole trace was an artifact. The three trampolines (lazy-compile, link-resolve,
  late-virtual) establish a real 144-byte frame and then CALL — `lazyCompile` runs the whole compiler,
  `virtualResolve` demand-loads and can run a `<clinit>` — so an exception underneath one unwinds THROUGH it,
  but none had a frame-table entry. The walk could not size the frame, stopped, and printed stale stack words
  as callers.
  - **Two things were wrong and either alone kept it broken.** (1) `VM.unwind` reads the saved LR at `[sp+0]`
    (a handler's callee-saved locals from `[sp+8]`), but the trampolines put x0..x15 at `[sp+0..120]` and LR
    at `[sp+128]` — registering as-is would have made a saved x16 the return address. Store order is
    irrelevant, so it was a pure offset change. (2) `resetLoader` zeroes `jitFrameCount` per batch while the
    lazy/virtual trampolines are built ONCE and cached for the life of the VM, so build-time registration
    alone would have fixed batch 1 and left every later batch broken — the harder half to notice.
    `registerTrampFrames()` re-publishes after every reset; `linkTramp` is the odd one out, cleared by
    `resetLoader` and so re-registered when rebuilt.
  - Each entry covers only where the frame is LIVE (after `sub sp`, up to `add sp`), so the tail-branch —
    which tears the frame down before `br` — is correctly excluded. The lambda thunk gets one too.
  - **Result: 3 frames + an unclaimed one became 12 named frames**, and the real failure was something else
    entirely — a DENYLIST TRAP in `AssertionFailureBuilder.maybeTrimStackTrace`, with three tests passing and
    the fourth reaching the assertion machinery as intended.
  - **The lesson: an IMPOSSIBLE trace — an implicit exception reported in check-free image code — means the
    WALK is broken, not the frame it names.** Chasing the named frame first cost a round trip.
  - **Pi (`core 166MHz`, full suite):** `demo/ExcDemo`'s `printStackTrace` walks `level3 -> level2 -> level1
    -> main -> Loader.launch -> VM.run -> VM.boot` with no `<unclaimed pc=`; 26 batches, all parity OK,
    `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`, WPA2 -> HTTP 200 OK (828 bytes).

- **A null dispatch slot RESOLVES now instead of throwing (2026-08-29, PI-VALIDATED on the full suite).**
  `Pattern.compile("abc")` from any `<clinit>` reached reflectively died with a bare AIOOBE, while the same
  call at top level worked. **RTA runs at batch time, so a class instantiated only from a LAZILY compiled body
  is never seen to be instantiated** — its virtuals are never marked and its vtable keeps holes. `Pattern` is
  exactly that: `Pattern.compile` itself compiles on demand, so the `new Pattern` inside it is invisible to the
  batch's reachability pass, and `Pattern.<init>` then dispatches through the hole.
  - **The vtable twin of the itable gap `mintPrunedStub` closed**, fixed with the lever recorded there
    ("mint on first dispatch, not at itable-build time"). `dispatchTargetGuard` now has TWO outcomes: a
    misaligned/out-of-range target is not code at all and still throws AIOOBE; a NULL slot means only "never
    compiled", so the slot is REPLACED with the resolve trampoline and the site's own `blr` calls it — which
    preserves x0..x15, resolves against the RECEIVER's class, and tail-branches. A slot never dispatched
    through costs nothing; minting every slot up front measured 3-4x the code arena. This also closes the old
    "`globalVtableSlot` answers 0 on no match" gap — it answers -1 now.
  - **A site table shared by every dispatch guard MUST dedup, and not for size reasons:** the compiler visits
    each site twice (size pass, emit pass), so without dedup the two passes baked DIFFERENT indices for the
    same call. The old overflow behaviour (return index 0) silently mis-resolved to whatever site 0 was;
    `capHalt` instead. `MAXVSITE` 4096 -> 16384.
  - **Naming a frame no table claims is what cracked it.** An unclaimed frame printed a bare `<image/native>`
    with nothing to chase; it now prints its pc plus the nearest registered body below it and that body's
    block end, which separates "nothing claims this" from "claimed but the pc ran past the recorded end".
    That put the fault 0xAA8 past the last registered buffer. Two one-boot experiments finished it: flip the
    guard's exception class to see whether the guard was involved at all, then split its four branches across
    two throw sites to see WHICH fired.
  - **The cheapest discriminator was a warm-up line.** Calling `Pattern.compile` once at top level first made
    every nested case pass — one boot turned "Pattern is broken" into "FIRST-TIME initialization in this
    context is broken" and pointed at the vtable. `test/jdk/junit/NestedClinitProbe` pins the boundary
    (Trivial/Alloc/StringBuilder nest fine; Pattern did not).
  - **Pi (`core 166MHz`, full suite):** 26 batches, every `vtparity`/`itparity` OK, no exception/trap/fault and
    no `unclaimed pc=`/`MAXVSITE`, `churnMB=625 live=32 intact=32`, `lisp evals=600 result=610 stable=1`,
    WPA2 -> DHCP -> DNS -> TCP -> HTTP 200 OK (828 bytes).
  - **A WiFi failure on the FIRST of those two boots was NOT a regression** — `no mac` (the `cur_etheraddr`
    ioctl response lost among post-JOIN event frames) zeroed the MAC, so every MIC was wrong and the AP
    dropped all six msg2 attempts. An unchanged re-boot of the SAME card reached HTTP 200 OK. That path is
    intermittent; re-boot before building a control image.

- **Array + primitive class literals — FIXED (2026-08-28, PI-VALIDATED on the full suite).**
  `String[].class` gave a mirror with `isArray()` hardcoded false and an empty name; `int.class` was NULL.
  Now `[Ljava.lang.String;` / `[I` / `[[I`, all nine primitives named, `int.class == Integer.TYPE`, and
  `literal == getClass()`.
  - **Two unrelated mechanisms behind one symptom.** Arrays: every piece already existed (`typeOfClass`
    resolves a `[`-literal to a real array Type) and nothing was wired to them — `isArray()` was a literal
    `return false` and `classNameString` searched only the CLASS registry, which array Types are not in.
    Primitives: **`int.class` is not an `ldc` at all** — javac emits `getstatic Integer.TYPE`, and the writer
    cannot bake that field because the seed JVM's value is a HOST `java.lang.Class`, so the snapshot stores 0.
  - **The primitive element char is recovered by IDENTITY against the per-atype TIB cache**, not from the
    Type: element size cannot separate `byte[]`/`boolean[]` or `int[]`/`float[]`, and identity also works for
    a writer-BAKED array Type the loader merely adopted, which no metal-side field would have been set on.
  - **A primitive Type is a real tagged heap node, not a small sentinel** in the mirror's Type word: every
    `Class` native dereferences that word, so a tiny value would need guarding at each one.
  - **The overlay trap again, exactly as StringBuilder/Appendable:** the first run died in
    `java/lang/Void.<clinit>` because the `Class` overlay had dropped `getPrimitiveClass`, and four overlaid
    wrappers (Short/Byte/Character/Boolean) had dropped `TYPE`. Implementing `getPrimitiveClass` beats seeding
    — the stock wrappers then initialise themselves. `TYPE` must be non-final and UNINITIALIZED, or its own
    `<clinit>` nulls it back out after the VM fills it in.
  - **Unblocks** `getDeclaredMethod(name, parameterTypes)` overload selection, which was built and reverted as
    useless because "every distinguishing parameter type is an array or a primitive and a caller cannot
    express one". A caller can express both now.
- **Demand-load speed — `loadAll` 1712s -> 5.33s on the zip suite, 321x (2026-08-28, PI-VALIDATED).**
  `markReachable` was ~99% of every demand-load and **flat**: adding ONE class cost the same 219 s as adding
  thirteen, because the whole closure was re-derived from scratch each batch. First batch 357s -> 2.7s;
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
  - **The per-class boot lines were SECONDS of every boot — best line-per-line fix of the arc.** `load` and
    `phaseA` print once per class (~850 lines for a 448-class closure at 115200 baud). Gated behind
    `LOAD_TRACE` (independent of `LOAD_PROFILE`, so a profiling boot is not measuring its own printing):
    **`pull` 1,309 → 40.4ms, `A` 2,597 → 301ms, `struct` 345 → 54ms**, everything else unchanged — ~3.5s of
    the boot was serial traffic. **`A` had been flat at ~2.6s through every other increment because it was
    almost entirely printing.** A flag, not a deletion: the `load` list has identified several bugs here.
  - **`virt` 2,510 → 1,766ms: one FAILED attempt and one that worked, and the difference matters.** The
    failed one claimed "a chain whose every level is loaded can never grow, so that class can go incremental"
    — a claim about when work can be SKIPPED. Measured 2.76x, under-marked HALF the closure (`reach`
    1040 → 449), killed `demo/StrOpsDemo` with a bare AIOOBE in `String.split`; **cause never identified**,
    reverted. The one that worked caches facts immutable by construction and makes no skipping claim:
    `ensureMethodTable` (a blob's method table is fixed by its classfile, yet `matchLevel` re-derived it —
    parse, walk, and an FNV hash per method — on every level visit) and `cachedSuperOf`.
    **A bisect settled in one run what two rounds of reading could not.**
  - **`cachedSuperOf` measured ZERO alone and 9% after the method table landed** — same code, same closure.
    "This optimization is worthless" is a statement about context, not about code.
  - **Still the largest item: `virt` at 1,766ms — 65% of the mark, 40% of the first batch.** What is left is
    the walk itself; cutting it needs the sound invalidation rule attempt 1 failed to find.
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
  **Limitation (SUPERSEDED 2026-09-17 -- see the top card):** this originally read
  "callee-saved locals are NOT restored during the walk". `Magic.resume` DOES
  restore all of x19..x28 now; what stayed broken far longer was the
  RECONSTRUCTION feeding it -- an unwind past a BAKED frame dropped that frame's
  saved registers, silently. No `finally`-specific handling beyond catch-all
  entries.
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

- **Run the HOST JVM control first when a library misbehaves.** Running the same jar and the same command
  line on a stock JVM takes ten seconds and separates "our VM is wrong" from "the library does that anyway".
  In the launcher arc this settled the four-times `--help` warning instantly -- and it was run late, after
  boots had been spent. It is the FIRST action on any library-misbehaviour symptom, not the last.
- **Reduce to a probe as the SECOND step, right after naming the symptom.** A launcher boot is ~10 minutes;
  a probe boots in seconds. The lambda-capture bug went from open to fixed in an afternoon because it was
  reduced first; earlier bugs in the same arc were chased at full-launcher scale for days.
- **A new test should be an adversarial SHAPE probe, not another feature demo.** The suite's demos prove a
  feature exists; they do not prove it survives a large program. Reproducing the SHAPE is not reproducing the
  CONDITION -- a 4-capture lambda, a cross-method throw into a deep-stack handler, a reflective read of an
  INHERITED field, two classes with same-named statics, a mutually-referencing `<clinit>` pair. Each is ~30
  lines, and every one of them would have failed on a shipping image before the launcher ever ran. **The
  probes written to diagnose an arc ARE its regression suite -- run them, do not just keep them.**
- **A CITED RESULT IS NOT A MEASURED ONE, and "the class is in the passing set" is not "these methods pass".**
  I supported a conclusion in this file with "the same SleepSanity passes under MetalJUnit, one of the six
  classes in `ran 44, failures 0`" -- true about the CLASS, and silent about whether the two methods at issue
  were among the passes. They were (`scripts/run-junit.sh 900 SleepSanity` -> `ran 2, failures 0`), so the
  conclusion survived; it did not have to. Re-running the control cost four minutes on QEMU, which is always
  cheaper than a conclusion resting on a citation nobody re-read. This is the sibling of the recorded
  "check a log's COMMIT before naming its failure as current".
- **A diagnostic must print the state it MEASURED, never one it assumed.** Three separate reports lied during
  the launcher arc (the unwinder decoding a Throwable layout it never checked; `UNRESOLVED STATIC` asserting
  "class never pulled"; a `CLINIT LOST` report that could not tell "never ran" from "already ran"), and each
  redirected the search. A report that states an unmeasured cause is worse than no report.
- **A bisect assumes the culprit is inside the change.** When every single-variable removal still fails in a
  NEW place, the change is a perturbation, not a cause. And over a bug that depends on stale registers, a
  bisect ranks ingredients by whether they disturb the accident -- arms can pass by LUCK. Ask what a passing
  arm left undisturbed, not only what it removed.
- **`make overlaycheck` does NOT see a dropped superclass or interface** (it diffs members only). That blind
  spot has now cost two of the worst bugs in the project -- StringBuilder dropping `Appendable`, and
  PrintStream dropping `OutputStream`, the latter producing TOTAL SILENCE from the launcher. Until the tool
  diffs the supertype chain, check it by hand whenever an overlay is added or edited.

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
