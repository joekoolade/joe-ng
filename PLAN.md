# joe-ng — Project Plan (metacircular, all-Java, bare-metal AArch64 / Raspberry Pi 4)

A metacircular Java VM whose **foundation is a boot-image writer**: a program
that takes Java classes and produces a raw `kernel8.img` that runs directly on a
**Raspberry Pi 4 Model B (4 GB)** — BCM2711, quad Cortex-A72, ARMv8-A/AArch64 —
with **no operating system underneath**.

**Everything is written in Java.** The assembler, the compiler, the boot-image
writer, and the entire runtime — including the bare-metal boot and
exception-level setup — are Java. No C, no external assembler, no linker, no
GRUB, no third-party toolchain. Raw hardware access is done through a self-written
VM-magic layer (unboxed `Address`/`Word`/`Offset`, privileged-op intrinsics).

Learning from first principles: read the ARM manuals and BCM2711 docs and the
*ideas* behind Jikes RVM / JOE freely, but write every line yourself.

---

## 0. The one unavoidable seed (stated honestly)

A self-hosted system needs something to run its own builder the first time. For
joe-ng that is exactly two things you do **not** build and do **not** choose:

1. **A stock JVM** — runs the boot-image writer for the very first image. It is a
   *seed only*: once joe-ng can run the writer itself (Milestone M5), the seed is
   no longer needed. Nothing you write depends on it beyond bootstrap.
2. **The Pi's GPU firmware** — the SoC's built-in loader copies `kernel8.img` to
   `0x80000` and starts the ARM cores. It is not replaceable and not a build tool.

Beyond those, no other tool touches joe-ng. This is the honest meaning of
"no other tools": everything in the *creation* of the VM is Java you author.

---

## 1. The metacircular loop (the whole design in one picture)

```
  [seed JVM]
      │  runs
      ▼
  boot-image writer (Java)
      │  reads Java .class files (your own classfile parser, in Java)
      │  lays out objects / TIBs / statics in the guest object model
      │  invokes the baseline compiler (Java) → raw AArch64 machine code
      │  relocates everything to load address 0x80000
      ▼
  kernel8.img  (raw bytes: entry code + compiled methods + object graph)
      │  Pi firmware loads at 0x80000, enters at EL2
      ▼
  compiled VM.boot (was Java, now A64):  EL2→EL1, stack, MMU, UART … runs Java
      │  contains a compiled copy of the baseline compiler + classfile parser
      ▼
  runtime class loading: joe-ng compiles NEW .class files itself, on bare metal
      │  eventually runs the boot-image writer itself →
      ▼
  joe-ng builds joe-ng  (self-hosting; seed JVM no longer required)
```

The key insight that keeps this "all Java, no other tools": the **classfile
parser** and the **baseline compiler** are ordinary Java classes. The writer runs
them on the seed JVM to build the first image; the image *contains compiled
copies of them*, so the running VM can parse and compile more classes on the
metal. Same code, two contexts. That closure is the point of the project.

---

## 2. Components — all Java, no exceptions

- **Classfile parser** (Java): reads `.class` bytes → methods, fields, constant
  pool, bytecode. Used by the writer *and* by the runtime for class loading.
  Building it early is not throwaway — it's needed in both contexts.
- **VM-magic layer** (Java): unboxed `Address`/`Word`/`Offset`/`Extent` +
  pragmas (`@Uninterruptible`, `@Intrinsic`, `@Entrypoint`, `@Inline`). Plus
  **AArch64 privileged-op intrinsics** so even boot code is Java: system-register
  access (`MRS`/`MSR` for `HCR_EL2`, `SPSR_EL2`, `ELR_EL2`, `SCTLR_EL1`, `TTBR0/1`,
  `MAIR`, `TCR`), barriers (`DSB`/`ISB`), `ERET`, `WFE`, cache/TLB ops.
- **AArch64 assembler** (Java): encodes A64 instructions to raw 32-bit words.
  Emits *bytes*, not assembly text — there is no external `as`.
- **Baseline compiler** (Java): bytecode → A64 machine code, compile-only
  (metacircular VMs have no interpreter/OS to fall back on; the first thing that
  runs on metal must already be machine code). Runs on the seed JVM inside the
  writer, and later runs in-image as the runtime JIT.
- **Boot-image writer** (Java): the foundation. Object-model layout + method
  compilation + relocation to `0x80000` + raw `kernel8.img` emission + its own
  linking. No `ld`, no `objcopy`.
- **Object model** (Java): object header, TIB (type info block), field/array
  layout, statics area, stack-frame layout, code area.
- **Runtime** (Java): `VM.boot`, memory manager, class loader, (later) scheduler,
  exceptions.
- **Board layer** (Java): BCM2711 MMIO via magic — mini-UART/PL011, GPIO,
  mailbox, later GIC-400 and timers.

---

## 3. Target facts (locked)

- **CPU:** 4× Cortex-A72, ARMv8-A; run **AArch64**.
- **Image:** raw `kernel8.img`, **loaded at `0x80000`**; `config.txt` needs
  **`arm_64bit=1`** (64-bit is not auto-enabled on the Pi 4). No `bootcode.bin`.
- **Entry EL:** firmware/armstub enters at **EL2** → your Java boot code drops to
  **EL1** (`HCR_EL2.RW=1`, set `SPSR_EL2`/`ELR_EL2`, `ERET`).
- **Privilege model: single level, EL1 (supervisor), no EL0.** The whole image —
  VM, runtime, and application — runs together at EL1. There is no user mode and
  no user/supervisor crossing; hardware ring protection is replaced by language
  type-safety, bytecode verification, and GC. This is the source of the speed
  argument (no syscalls, no page-table switch on context switch).
- **Cores:** all four start; park cores 1–3 (`WFE` on `MPIDR_EL1`) until SMP.
- **Peripherals:** MMIO base **`0xFE000000`**; GPIO `0xFE200000` (PU/PD via
  `0xFE215000`); PL011 UART0 at `0xFE201000`; mini-UART (AUX/UART1) is the
  simplest first console.
- **MMU:** off for the first boot; then a flat 1:1 map — **Normal cacheable**
  for RAM, **Device-nGnRnE** for the `0xFE000000` window; map high memory (>1 GB)
  since you have 4 GB.

---

## 4. How to get early signal when the *writer* is the foundation

You can't defer the writer — so instead make its first target trivial. Build the
writer, assembler, compiler, and object model in their thinnest possible form
against a one-method runtime, ship a booting image, then grow the runtime. Each
milestone keeps the full metacircular pipeline intact and just widens what Java
it can handle.

### M0 — Writer emits a booting image (target: 1–2 wks)
- Java assembler encodes a handful of A64 instructions; writer packs them + an
  image header and writes raw `kernel8.img`; relocation to `0x80000`.
- Image is a spin loop (`WFE`). Boots under QEMU `raspi4b` and on real hardware.
- **Done when:** your Java-written writer produces an image the Pi runs. No C, no
  external assembler in the loop.

### M1 — First light: compiled Java prints over UART (target: 1–2 wks)
- Baseline compiler compiles **one** Java method, `VM.boot`, that: drops EL2→EL1,
  sets a stack, inits mini-UART, and writes "hello from joe-ng" via a magic
  `Address` — all Java, privileged ops via intrinsics.
- Writer compiles it, lays it at the entry point, emits the image.
- **Done when:** the string prints, driven by compiled Java on bare metal. This
  is the metacircular pipeline working end to end.

### M2 — Real object model + multi-class runtime (target: 3–5 wks)
- Object header + TIB + statics; compiler covers the bytecodes a small runtime
  needs (calls, fields, arrays, int/long ops, control flow).
- Writer lays out a graph of several classes/methods with correct references.
- **Done when:** a multi-method, multi-class Java runtime boots and runs.

### M3 — Heap + `new` (target: 3–5 wks)
- Bump allocator (no GC yet) so object allocation works on metal; references,
  arrays, `instanceof`.
- **Done when:** boot code can `new` objects and call methods on them.

### M4 — Runtime class loading (the stated goal, fully realized) (target: weeks)
- The image contains compiled copies of the classfile parser + baseline
  compiler. Append a class area to the image (or load over UART); the running VM
  parses and compiles **new** `.class` files on bare metal and runs their `main`.
- **Done when:** joe-ng takes Java classes it has never seen and runs them, on the
  metal, with no OS.

### M5 — Self-hosting closure (drop the seed JVM) (target: weeks)
- Run the boot-image writer *inside* joe-ng, so joe-ng builds its own next image.
- **Done when:** the seed JVM is no longer needed to produce an image. Fully
  metacircular, fully self-contained.

#### M5 progress
Two of the three pipeline stages are already shared — the same source runs on the
seed JVM and is compiled into the image:
- `classfile/ClassReader` — the classfile format, read from a `byte[]`. The
  writer's `ClassFile` and the on-metal loader both use it.
- `asm/A64Enc` — the instruction encodings as pure integer arithmetic. `A64` adds
  validation for the writer; the on-metal JIT emits through it directly.

The middle stage, `compiler/BaselineCompiler`, is the remaining work. Plan below.

#### M5.1 — Migrating BaselineCompiler (the plan)

**Measured, not guessed.** Running joe-ng's own compiler over
`BaselineCompiler.class` (with a class resolver, so unresolved-class noise is
excluded) gives the real gap:

```
65 methods total — 28 compile today, 37 blocked
  14  `new` while the operand stack is non-empty   (compiler limitation)
  12  reference a JDK class (String, ArrayList, List, IllegalStateException)
   9  unsupported opcodes: invokedynamic x4, tableswitch x2,
                           lookupswitch x1, aconst_null x1, caload x1
   2  exceed the 10-local register ceiling
```

Two findings reshape the obvious plan:

1. **The biggest blocker is not the JDK dependency.** It is `lowerNew`'s
   `expectEmpty("new")` — the compiler refuses `new` unless the operand stack is
   empty, which blocks 14 methods. That is our own limitation, fixable
   independently, and worth fixing regardless of self-hosting.
2. **The JDK usage is shallower than it looks.** Of 21 `invokedynamic` sites, 15
   are `makeConcatWithConstants` — string building inside *error messages*, which
   the metal does not need at all. Only 6 are real lambdas (the `Fixup`
   closures).

**Stage 1 — grow the compiler (no BaselineCompiler edits).** Pure capability,
independently useful, verifiable by re-running the gap harness.
- ✅ **Done:** `lowerNew` now spills live operands across `Heap.alloc` exactly as
  ordinary calls do; `expectEmpty` survives only for the frameless entry method.
  Added `aconst_null` (0x01).
- ✅ **Done:** the 10-local ceiling is lifted. Slots 0..9 stay in x19..x28; the
  rest live in the frame and are loaded/stored around each use (`inReg`/`localMem`),
  so a method within the old limit gets byte-identical code. This also fixed a
  latent bug: the prologue looped to `maxLocals`, so a method declaring 11 slots
  saved and restored **x29 (the frame pointer)** as if it were a local. Three image
  methods did exactly that (`VM.markRange`, `Loader.load2`, `Loader.findMethod`);
  they compiled only because nothing ever *accessed* slot 10.
- ✅ **Done:** `caload`/`castore` (0x34/0x55), adding `LDRH`/`STRH` to the
  assembler (bit-for-bit checked, including the halfword-alignment rejection), plus
  `ifnull`/`ifnonnull` (0xC6/0xC7) — identical to `ifeq`/`ifne` because null is 0
  in this object model. `saload`/`sastore` remain open: `short` is signed, so they
  need `LDRSH` rather than the zero-extending `LDRH`.

**Stage 1 is complete.** Measured effect: 28 → **32 methods compiling**, and the
three blockers it targeted are gone (`new` with a live stack, the local ceiling,
the easy missing opcodes). The image is unchanged except where the local fix
removed the redundant x29 save, and QEMU still runs to `*M`. What remains is
almost entirely Stage 2 territory: **23 methods blocked on JDK references** and
**8 on `invokedynamic`**, plus 2 switch statements.

*Measured after the first item:* the `new`-with-live-stack blocker went from 14
methods to **0**. The count that compiles moved only 28 → 29, because those
methods now run further and hit their *next* blocker — JDK references rose 12 → 22
and `invokedynamic` 4 → 8 as previously-hidden code became reachable. That is the
expected shape of a layered blocker analysis, and it re-weights the plan: Stage 2
is now the dominant remaining work.

**Stage 2 — de-string and defunctionalize BaselineCompiler.**
- ✅ **Done:** the branch `Fixup`s are defunctionalised — a closure per branch
  became a kind tag plus its one operand (register or condition), patched by
  `encodeBranch` with if/else rather than a `switch` expression, which would have
  lowered to the still-unsupported `tableswitch`. `Fixup` also stopped being a
  `record`, whose synthesised `equals`/`hashCode` carry their own `invokedynamic`.
- ✅ **Done:** diagnostics are quarantined. Every message now goes through a few
  `bad`/`unsupported` helpers, so the concatenation (and one `String.format`) sits
  in three methods instead of scattered across eight. Exception *types* are
  preserved, so an unsupported opcode still throws `UnsupportedOperationException`
  and stays loud. These helpers move to the writer-side wrapper in stage 4.
- ✅ **Done:** the branch-fixup table is an array plus a count, not an `ArrayList`.
  It never escapes the compiler, and every future shape of it needs branch
  patching, so this survives the split.
- ⚠️ **Deferred to stage 4 — the ordering in this plan was wrong.** The remaining
  `String` keys and the other six collections are not internal details; they *are*
  the `BaselineCompiler`→`ImageBuilder` contract. Every record that escapes through
  `CompiledMethod` carries a `String` key, and `ImageBuilder` uses those keys as map
  keys for its whole layout pass — a method worklist, class sets, statics keys (16
  call sites).

  Converting them in place would mean giving `ImageBuilder` integer identities
  (constant-pool indices, plus small ids for compiler-synthesised helpers like
  `Heap.alloc`) and having it resolve index→name at every use. That is *worse* on
  the writer side, which has a JDK and will never be compiled into the image, purely
  to serve the metal side.

  And stage 4 supersedes it anyway: in the split, the shared core should not emit
  relocation records on the metal path at all — it should ask a resolver object what
  address to emit, and the writer's implementation of that resolver keeps its
  Strings and collections. Doing the conversion now is churn stage 4 would redo.

**Revised order: do stage 4's split next, and let the remaining stage-2 items fall
out of it.** The prerequisite is unchanged and now blocking: the two compilers must
first agree a calling convention (writer: callee-saved locals with per-method
frames; metal: x1..x8 with no frame, spilling around calls) and a symbol-resolution
strategy. That decision is the real next piece of work.

*Measured after the two completed items:* `invokedynamic` sites **21 → 10**, and
methods blocked on it **8 → 5**. Both changes left the emitted image byte-for-byte
identical, so they are provably behaviour-preserving.

Note the headline count moved the *wrong* way — 32 → 31 compiling, of 69 rather
than 67 methods — because the helpers are themselves three new methods that carry
the quarantined concatenation, and because methods freed from an opcode blocker
now reach the JDK-reference blocker behind it. That blocker is now dominant at
**29 methods**, and it is precisely the two remaining items: `String` keys and
collections. Those are invasive rather than local, and they interact with the
calling-convention question, so they are the natural next sitting.

**Stage 3 — the switch statements.** 3 methods lower to `tableswitch`/
`lookupswitch`. Either add jump-table support (a real feature, and the natural
shape for opcode dispatch) or rewrite those switches as if/else chains. Decide by
measuring; if/else is simpler and the JIT is not yet performance-critical.

**Stage 4 — split, then delete the duplicate.** Apply the `ClassReader`/`A64Enc`
pattern: a JDK-free core doing bytecode→A64, and a writer-side wrapper holding the
`ClassFile` model, diagnostics and `ImageBuilder` integration. Then the on-metal
loader's bespoke codegen is *deleted* in favour of the core — the moment there is
genuinely one compiler.

**The crux, and the real risk.** The two compilers do not merely differ in
dependencies, they differ in *calling convention*: the writer puts locals in
callee-saved x19.. with a proper frame per method, while the on-metal JIT keeps
locals in x1..x8 and the operand stack in x9..x15 with no frame, spilling
everything around calls. Symbol resolution differs too (writer: string keys
relocated by `ImageBuilder`; metal: registries holding resolved addresses).
**Unifying the convention is a prerequisite for Stage 4, not a detail to settle
during it** — merging the code before agreeing the convention would produce a
compiler that is correct in neither context.

**Done when:** the shared core compiles *itself* and the output is identical to
the seed JVM's compilation of it — a fixpoint, which is the honest proof of
self-hosting.

### M6+ — Widening
GC (bump → real collector); GIC-400 interrupts + timer; SMP (wake cores 1–3);
exceptions; class-library subset; framebuffer via VideoCore mailbox.

---

## 5. Design decisions to lock day one

- **Compile-only, no interpreter.** With no OS/interpreter beneath, the first code
  on metal must be compiled; a single baseline compiler serving both the writer
  and the runtime is the simplest metacircular shape. (An interpreter could be
  added later as a tier, but it isn't the foundation.)
- **Parse classfiles yourself** rather than reflecting over seed-JVM classes.
  You need a classfile parser in the runtime anyway (M4), so writing it in Java
  first serves both the writer and the VM and avoids coupling the object model to
  the seed JVM's internals.
- **Boot code is Java + magic**, not an assembly file. Privileged ops
  (`MSR`/`MRS`, `ERET`, barriers, `WFE`) are magic intrinsics the compiler lowers.
  This keeps "everything is Java" literally true for the boot path.
- **Seed JDK:** any modern stock JDK (17/21) — it's only a bootstrap host.
- **Host language = guest language = Java.** The writer and compiler are part of
  the runtime source tree, so they get compiled into the image unchanged.

---

### 5.1 Boot-path magic intrinsics (EL1 / supervisor)

Every privileged operation below is a magic intrinsic the compiler lowers to a
single A64 instruction, so `VM.boot` and the vector table stay Java. Grouped by
what the boot path needs, in roughly the order it needs them.

**A. Identify where we are / park the other cores**
- `readCurrentEL()` → `MRS x, CurrentEL` (are we at EL2 as expected?)
- `readMPIDR()` → `MRS x, MPIDR_EL1` (core id in Aff0; cores 1–3 → `wfe()` loop)
- `wfe()` / `sev()` → `WFE` / `SEV` (park and wake secondary cores)

**B. Drop EL2 → EL1 (do this once, on the primary core)**
- `writeHCR_EL2(0x8000_0000)` → set `HCR_EL2.RW` so EL1 runs AArch64
- `writeCNTHCTL_EL2(...)` + `writeCNTVOFF_EL2(0)` → let EL1 use the generic timer
- `writeCPTR_EL2(...)` → don't trap FP/SIMD to EL2 (Java has float/double)
- `writeSCTLR_EL1(safe)` → known reset value, MMU/caches off for now
- `writeSPSR_EL2(0x3C5)` → target PSTATE = EL1h, DAIF masked
- `writeELR_EL2(&continueInEL1)` → where to resume
- `eret()` → `ERET` (the actual drop)

**C. Stack + BSS (now at EL1)**
- `writeSP(top)` → set `SP_EL1`; pick `SPSel` via `writeSPSel(1)`
- BSS zeroing is just magic `Address.store` in a loop — no intrinsic needed

**D. MMU + caches (flat map: Normal for RAM, Device-nGnRnE for 0xFE000000)**
- `writeMAIR_EL1(attrs)` → memory attribute encodings
- `writeTCR_EL1(cfg)` → granule (4 KB), T0SZ/T1SZ, IPS for 4 GB
- `writeTTBR0_EL1(pgtbl)` (and `TTBR1_EL1` if you split)
- `writeCPACR_EL1(fpen)` → enable FP/SIMD at EL1 (or you trap on Java floats)
- `dsb()` / `isb()` → `DSB SY` / `ISB` around every system-register change
- `tlbiVMALLE1()`, `icIALLU()`, `dc(...)` → TLB / I-cache / D-cache maintenance
- `writeSCTLR_EL1(enable)` → set `M` (MMU), `C`/`I` (caches) bits, then `isb()`

**E. Exceptions + interrupts (EL1)**
- `writeVBAR_EL1(&vectors)` → your Java-emitted 2 KB-aligned vector table
- `daifClr(mask)` / `daifSet(mask)` → unmask/mask IRQ/FIQ/SError
- in handlers: `readESR_EL1()`, `readFAR_EL1()`, `readELR_EL1()`, `readSPSR_EL1()`

**F. Generic timer (when you add preemption)**
- `readCNTFRQ()` → `CNTFRQ_EL0`; `writeCNTP_TVAL(...)`, `writeCNTP_CTL(...)`

**G. Ordering / raw memory (the everyday magic)**
- `dmb()` for MMIO ordering; `Address.load/store` byte/half/word/dword,
  with device-ordered variants for the `0xFE000000` peripheral window.

The assembler must encode `MRS`/`MSR` (system-register moves), `ERET`, `DSB`/
`DMB`/`ISB`, `WFE`/`WFI`/`SEV`, `TLBI`, `IC`/`DC`, and the load/store family —
that set is enough for the entire boot path.

## 6. Top risks

- **The A64 assembler + compiler is the long pole** — no reference exists (Jikes
  was IA-32/PPC). Mitigate by keeping M1's bytecode surface minimal and growing.
- **Object-model relocation** in the writer is the classic silent bug — keep the
  first model tiny; dump and diff image layouts.
- **EL2→EL1 + MMU memory attributes** on the peripheral window are a hang-prone
  zone — UART-first observability so every failure is visible.
- **Magic lowering correctness** — a mis-lowered `Address.store` corrupts memory
  invisibly; unit-test the assembler's encodings against the ARM ARM.
- **QEMU ≠ silicon** — QEMU `raspi4b` peripheral emulation is partial; validate
  on a real Pi 4 from M0. (QEMU is a test aid, not part of building the VM.)

---

## 7. Reference materials (docs only — first-principles study)

- **ARM Architecture Reference Manual (ARMv8-A / A64)** — instruction encodings
  (for your assembler) and the exception model.
- **BCM2711 / BCM2835 ARM Peripherals** — MMIO map, UART, GPIO, mailbox, GIC-400.
- **Raspberry Pi `config.txt` / boot** docs — `arm_64bit`, load address,
  `kernel8.img`.
- Jikes RVM / JOE papers and writeups — *concepts* for the writer, magic, TIB /
  object model, and baseline compiler.

---

## 8. Suggested repo layout (all Java)

```
joe-ng/
├── PLAN.md
├── config.txt                  # arm_64bit=1
├── src/
│   ├── magic/                  # Address/Word/Offset + pragmas + AArch64 privileged intrinsics
│   ├── asm/                    # A64 assembler: encode instructions to raw words
│   ├── compiler/               # baseline bytecode -> A64 (compile-only)
│   ├── classfile/              # classfile parser (used by writer AND runtime)
│   ├── objectmodel/            # header, TIB, statics, stack/code layout
│   ├── writer/                 # boot-image writer: layout + relocate + emit kernel8.img
│   ├── vm/                     # VM.boot, class loader, memory mgr, (later) scheduler
│   └── board/bcm2711/          # UART, GPIO, mailbox, (later) GIC-400, timers
└── scripts/                    # SD flashing + serial/net boot; QEMU runner (test only)
```

---

## 9. First-week checklist

- [ ] Seed JDK (17/21) building the source tree; QEMU `raspi4b` + a real Pi 4 with
      USB-TTL serial ready.
- [ ] `config.txt` with `arm_64bit=1`; confirm firmware loads a raw image at `0x80000`.
- [ ] Java A64 assembler encoding ~a dozen instructions, verified bit-for-bit
      against the ARM ARM.
- [ ] Writer emits a raw `kernel8.img` spin loop that boots (M0).
- [ ] Sketch the object header + TIB layout and the magic-intrinsic list for the
      boot path (system registers, `ERET`, barriers, `WFE`).

---

## Decided

- **Object-model shape (resolved 2026-07-18; gates M2).** Source of truth:
  `src/objectmodel/ObjectModel.java`.
  - **References:** direct 64-bit pointers (not handles), 8-byte aligned,
    `null = 0`. Chosen for access speed (one load per field); a future moving GC
    updates the pointers. 8-byte alignment leaves 3 free low bits for GC/lock
    tagging. No compressed references for now.
  - **Header: two words (16 bytes).** `+0` = TIB pointer; `+8` = status word
    (identity hash / GC state / thin-lock), reserved and unused until ~M6.
  - **Fields** start at `+16` (one 8-byte slot each for now; packing later).
    **Arrays:** `+16` length, `+24` elements.
  - **TIB** (itself a word array): slot 0 → `Type` (name, superclass,
    instance size, array element type, field reference-map, vtable length);
    slots 1.. = virtual method code addresses (vtable).
  - All offsets/sizes live only in `ObjectModel` so the layout is a one-file
    change and the writer's layout-dump/diff can catch relocation bugs.

## Open questions to resolve

- **Class area delivery for M4:** appended to the image vs loaded over UART/net.
- **When to enable the MMU:** keep it off through M3 for simplicity, or bring up a
  flat map earlier for cache performance and correct device memory?
- **SMP timing:** single-core through M5 (recommended), or wake all four cores
  sooner?
