# joe2 — Project Plan (metacircular, all-Java, bare-metal AArch64 / Raspberry Pi 4)

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
joe2 that is exactly two things you do **not** build and do **not** choose:

1. **A stock JVM** — runs the boot-image writer for the very first image. It is a
   *seed only*: once joe2 can run the writer itself (Milestone M5), the seed is
   no longer needed. Nothing you write depends on it beyond bootstrap.
2. **The Pi's GPU firmware** — the SoC's built-in loader copies `kernel8.img` to
   `0x80000` and starts the ARM cores. It is not replaceable and not a build tool.

Beyond those, no other tool touches joe2. This is the honest meaning of
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
  runtime class loading: joe2 compiles NEW .class files itself, on bare metal
      │  eventually runs the boot-image writer itself →
      ▼
  joe2 builds joe2  (self-hosting; seed JVM no longer required)
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
  sets a stack, inits mini-UART, and writes "hello from joe2" via a magic
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
- **Done when:** joe2 takes Java classes it has never seen and runs them, on the
  metal, with no OS.

### M5 — Self-hosting closure (drop the seed JVM) (target: weeks)
- Run the boot-image writer *inside* joe2, so joe2 builds its own next image.
- **Done when:** the seed JVM is no longer needed to produce an image. Fully
  metacircular, fully self-contained.

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
joe2/
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
