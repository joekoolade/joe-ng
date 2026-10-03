# joe-ng — project memory for Claude Code

joe-ng is a **metacircular Java VM** whose foundation is a **boot-image writer**
that turns Java classes into a raw `kernel8.img` running **bare-metal on a
Raspberry Pi 4 (BCM2711, quad Cortex-A72, AArch64)** with a metacircular runtime underneath it.

Read `PLAN.md` for the full plan — it is the source of truth. This file is just
the standing rules (the per-increment status record is in `STATUS.md`) so we don't re-litigate
them each session.

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
   direction of travel is to IMPLEMENT or STUB it, not to prune further. All systems on the deny list will eventually be removed.

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
- **No underlying OS.** Bare metal. The OS functions will be implemented as metacircular runtime.
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

Lives in **`STATUS.md`** -- the per-increment record (what each change fixed, how it was measured, QEMU and
Pi results), newest first. Read the top of it before starting work: it carries the measured figures new work
is compared against (closure counters, `gc: collections=46` at the churn demo, marker lists) and the
recorded traps.

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
- **AN ABSOLUTE LINE NUMBER IS A LYING INSTRUMENT (2026-09-20).** The EPL-2.0 header pass moved every
  recorded stack-trace line by 11, and repairing this file by +11 was MEASURED and is wrong in eight of nine
  cases -- the numbers had already rotted through ordinary edits, drifting from **-164 to +259**, and only
  `Cyw43` and `PipDemo` were +11, because nothing has edited those two files since. Source POINTERS are
  anchored by name now with the number a hint only (`the one symbols.vtableSlot site, ~1873`), while QUOTED
  ARTIFACTS -- the `ProcessImpl` stack trace, `PipDemo.main:95`, the `Loader.java:2052 -> 2059` A/B -- are
  left VERBATIM, because shifting a record of what a boot PRINTED to keep a pointer tidy falsifies a log.

- **A FETCHED REMOTE REF GOES STALE WITH TIME, AND `merge-base --is-ancestor` ANSWERS ABOUT YOUR LAST FETCH
  (2026-09-20).** A branch was handed over as "a clean fast-forward"; it was 72 commits BEHIND `origin/main`
  and the push would have been rejected. The peer session HAD fetched -- `150261f` really was the tip when it
  measured -- and four days passed between that fetch and the merge. **I first recorded this as "never
  fetched", which was an inference from a stale ref and not a measurement**; the peer checked its own
  transcript and corrected it. The remedy is the same either way and is the part to keep: **fetch immediately
  before any ff-only claim**, because the failure is a timestamp gap, not a missing command -- so looking for
  the absent fetch finds nothing.

- **A bisect assumes the culprit is inside the change.** When every single-variable removal still fails in a
  NEW place, the change is a perturbation, not a cause. And over a bug that depends on stale registers, a
  bisect ranks ingredients by whether they disturb the accident -- arms can pass by LUCK. Ask what a passing
  arm left undisturbed, not only what it removed.
- **`make overlaycheck` DOES diff the supertype chain now -- this agreement used to say it did not, and that
  is stale (corrected 2026-09-21).** The blind spot cost two of the worst bugs in the project -- StringBuilder
  dropping `Appendable`, and PrintStream dropping `OutputStream`, the latter producing TOTAL SILENCE from the
  launcher -- and it was closed by the supertype diff (see its FIRST find, StringBuilder/CharSequence). It is
  not theoretical: a throwaway `java/lang/invoke/MethodType` overlay tried on 2026-09-21 was refused with
  **`4 NEW dropped supertype(s)`**, naming `Serializable`, `Constable`, `TypeDescriptor` and
  `TypeDescriptor$OfMethod`. Read the count as well as the pass/fail -- the backlog moving by less than
  expected is itself a signal, which is how `Class.getAnnotation`'s erased-bound bug hid in plain sight.

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
