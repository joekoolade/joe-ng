# joe2 — project memory for Claude Code

joe2 is a **metacircular Java VM** whose foundation is a **boot-image writer**
that turns Java classes into a raw `kernel8.img` running **bare-metal on a
Raspberry Pi 4 (BCM2711, quad Cortex-A72, AArch64)** with **no OS underneath**.

Read `PLAN.md` for the full plan — it is the source of truth. This file is just
the standing rules and current state so we don't re-litigate them each session.

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
  loads `kernel8.img`. Nothing else touches joe2.
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

- **Phase: M2 in progress — multi-class runtime with real cross-class calls.**
- **M0 (done):** all-Java pipeline end to end. `asm/A64` encoder + `asm/CodeBuffer`
  + `writer/BootImageWriter` emit a raw, header-less `kernel8.img`;
  `writer/BuildSpinImage` = the 8-byte `wfe; b .-4` park loop at `0x80000`.
- **M1b (done):** `vm/EmitBoot` emits the first-light routine — reads CurrentEL,
  drops EL2→EL1 via ERET, enables FP (CPACR_EL1.FPEN), sets SP, brings up the AUX
  mini-UART (GPIO14/15 ALT5 + config), prints "hello from joe2\r\n", parks in wfe.
  `writer/BuildBootImage` emits it (344-byte image). **Prints correctly under
  QEMU `raspi4b`** (mini-UART = serial1). `asm/A64` now also encodes MRS/MSR
  (+boot sysregs), ERET, DSB/DMB/ISB, LDR/STR/LDRB/STRB, ADD/SUB imm, MOV,
  B.cond/CBZ/CBNZ/TBZ/TBNZ — 61 bit-for-bit checks in `test/asm/A64Test`.
  Build/test/emit: `scripts/build.sh`; QEMU smoke test: `scripts/qemu.sh`.
  **Not yet run on real hardware; mini-UART baud divisor needs on-silicon
  calibration (QEMU ignores it).**
- **M1c DONE (the metacircular half):** `writer/BuildCompiledBootImage` compiles
  `vm.VM.boot()` from javac bytecode — EL2→EL1 drop, FP enable, stack, mini-UART
  bring-up, and the print loop — into a `kernel8.img` that **prints "hello from
  joe2" under QEMU raspi4b** (functional check: `scripts/qemu-check.sh`). This is
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
  compiled as a multi-method program that **still prints "hello from joe2"** under
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
- **M2 remaining:** static fields (`getstatic`/`putstatic`), `instanceof`/
  `checkcast`, char/short arrays (need `ldrh`/`strh`), class hierarchies (super
  calls, vtable inheritance), and real String literals (`ldc` String → interned
  char[] + String object) to retire the `message()` bridge. GC is still M6.
- Milestones (see PLAN.md §4): M0 writer emits booting image → M1 first light
  (compiled `VM.boot` prints over UART) → M2 object model + multi-class → M3
  heap + `new` → M4 runtime class loading → M5 self-hosting (drop seed JVM) →
  M6+ GC, interrupts, SMP, exceptions, class library.

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
