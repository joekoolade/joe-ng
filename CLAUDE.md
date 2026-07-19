# joe2 — project memory for Claude Code

joe2 is a **metacircular Java VM** whose foundation is a **boot-image writer**
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

- **Phase: M4 done + M5 started + loading java.base classes on the metal.**
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
  - **Still to do on-metal:** a real `Type` chain for `instanceof`/`checkcast` on
    loaded objects, loaded interfaces with itables, and dependency auto-ordering.
  - Still a SEPARATE compiler from the writer-side one — true self-hosting needs a
    single JDK-free ClassFile+BaselineCompiler used in both contexts (large rewrite).
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
- **String literals DONE — `message()` bridge retired.** `ldc "..."` interns the
  literal as a real heap-layout **byte[] object** in the image (writer lays out
  `[null TIB][status][length][ASCII bytes]`, 8-aligned; the `ldc` address load is
  relocated like TIB refs). `Magic.bytes(String):byte[]` is a compile-time type
  adapter lowered to a no-op (joe2 has no `java.lang.String` yet, so this lets
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
- mini-UART baud is pinned via `core_freq=250` in config.txt with
  `Bcm2711.BAUD_115200 = 270`; if silicon output is garbled, the core clock
  differs — fall back to 500 MHz / divisor 541. Not yet confirmed on real silicon.

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
