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

### M5 — Self-hosting closure (drop the seed JVM) (compiler closure done; writer-on-metal remaining)
- Run the boot-image writer *inside* joe-ng, so joe-ng builds its own next image.
- **Compiler closure ✅:** one `compiler/Baseline` compiles in both worlds — the
  writer and the on-metal JIT — verified on QEMU. The metacircular *compiler* loop
  is closed (M5.4); see progress below.
- **Done when:** the seed JVM is no longer needed to produce an image — i.e. the
  boot-image *writer* also runs on metal. Fully metacircular, fully self-contained.

#### M5 progress
**The compiler closure is done (M5.4 ✅): one baseline compiler now serves both
worlds.** All three pipeline stages are shared source — run on the seed JVM by the
writer and compiled into the image for the on-metal JIT:
- `classfile/ClassReader` — the classfile format, read from a `byte[]`. The
  writer's `ClassFile` and the on-metal loader both use it.
- `asm/A64Enc` — the instruction encodings as pure integer arithmetic. `A64` adds
  validation for the writer; the shared core and on-metal JIT emit through it.
- `compiler/Baseline` — the code generator, `ClassFile`-free (cp view via
  `ClassReader`, every symbolic reference behind the `Symbols` seam). The writer
  drives it via `BaselineCompiler`+`WriterSymbols`; the metal's `Loader.emitMethod`
  drives the *same class* via `MetalSymbols`. M5Gap: `Baseline` self-compiles 66/66.
  `Loader.emitOp` is deleted — there is no second compiler. Verified on QEMU: the
  metal JIT compiles `Guest`/`Math` through `Baseline`, exercising `new`, virtual/
  interface/static dispatch, class+interface `instanceof`, string literals, magic
  intrinsics, and `throw`/`catch` (see §M5.4).

**Remaining for full M5 (drop the seed JVM):** run the boot-image *writer* itself
(`ImageBuilder` — object/TIB/itable layout, relocation, `kernel8.img` emission) on
metal, so joe-ng builds its own next image. The compiler and classfile parser it
needs already run on metal; what's left is the layout/link/emit machinery and a
metal filesystem/blob source for the input classes. Plan for the compiler stage,
now complete, below.

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
first agree a calling convention and a symbol-resolution strategy. That decision is
§M5.2.

#### M5.2 — Calling convention: the decision

The two compilers disagree on where a method's state lives.

| | writer (`BaselineCompiler`) | metal (`Loader`) |
|---|---|---|
| locals | x19..x28 (callee-saved), overflow to frame | x1..x8 (caller-saved) |
| operand stack | x9..x15 | x9..x15 |
| arguments | x0..x7, moved into locals by the prologue | land directly in the local registers |
| frames | prologue/epilogue per method | none |
| cost of a call | spill only the *live* operand values | spill x30 + x1..x15 unconditionally |

**Measured:** a call in JIT'd code costs **35–40 instruction words**, of which 32 are
the unconditional spill and reload of sixteen registers. The writer spills only the
operand values actually live at that point — usually zero to two. That is roughly a
**10–30× difference per call site**, and `new` pays it too.

**Two consequences of the metal's frameless design that are easy to miss:**
1. *Exceptions cannot unwind through JIT'd code.* `VM.unwind` walks frames using the
   writer-built frame table (`codeStart, codeEnd, frameSize`); JIT'd methods have no
   frames and no entries, so there is nothing to pop.
2. *GC can miss live references in JIT'd code.* `Magic.gc()` spills x19..x28 so refs
   held in locals become scannable on the stack. The JIT keeps locals in **x1..x8**,
   which that spill does not cover — a reference held only in a JIT'd local at a
   collection point is invisible to the collector. Latent today because nothing
   collects during loaded code, but real.

**Option A — unify on the writer's convention** (callee-saved locals + frames).
- Far better code: no 32-instruction spill per call.
- Fixes both consequences above for free: JIT'd methods get frame-table entries, so
  unwinding works through them, and locals land in the registers `Magic.gc()`
  already spills.
- More locals (10 + frame overflow, vs 8).
- Cost: the JIT must emit prologues/epilogues and track frame layout, and
  `Magic.call0`/`call2` must pass arguments in x0..x7 rather than the loader's
  slot convention.

**Option B — unify on the metal's convention** (frameless, spill around calls).
- Simpler codegen; leaf methods are very cheap.
- But it would inflate every call in the image by ~32 words, cap locals at 8, and
  *remove* the frames that exception unwinding is built on. A regression for the
  side that is currently correct.

**Option C — parameterise the core by convention.**
- Keeps both, but the convention pervades register allocation, prologues, call
  sequences and spills, so nearly every emit path would branch. It also defeats the
  point: the goal is *one* compiler, and this leaves two behaviours to test and two
  ways for self-hosting to be subtly wrong.

**Decision: Option A.** The metal's convention exists for expedience — it was the
cheapest thing that worked before frames existed — not by design. The writer's is
the more developed model, produces far better code, and is already the one the
runtime's own metadata (frame table, handler table, GC spill) assumes. Unifying on it
turns two latent gaps into working features rather than porting a limitation.

*Migration:* ✅ **done.** The on-metal JIT now follows the writer's convention:
per-method frames (`setFrame`/`emitPrologue`/`emitEpilogue`), locals in x19.., and
arguments arriving in x0..x7 and moved into locals by the prologue. `Magic.call2`
passes its arguments in x0/x1 accordingly. Because locals are callee-saved, a call
spills only the caller-saved operand registers into the frame's spill area rather
than sixteen registers into a scratch frame:

| JIT'd sequence | before | after |
|---|---|---|
| static call | 35 + args | **15 + args** |
| invokevirtual | 38 + args | **18 + args** |
| invokeinterface | 40 + args | **20 + args** |
| `new` | 40 | **20** |

Roughly half the code, and the per-call cost no longer dominates. Verified by QEMU
still reaching `*M`, which exercises prologue/epilogue, callee-saved locals, argument
moves, operand-only spills, virtual and interface dispatch (receiver now in x0),
`new`, `instanceof` and cross-class linking.

*Both latent gaps are now closed.* JIT'd locals live in x19..x28, exactly what
`Magic.gc()` spills, so references in loaded code are scannable. And unwinding
through JIT'd frames works: the loader appends each framed JIT'd method's
`{codeStart, codeEnd, frameSize}` to a runtime `jitFrameTable` (`VM.addJitFrame`),
and `frameSizeAt` consults it after the writer's table, so `VM.unwind` can pop a
JIT'd frame exactly as it pops a compiled one. Proven on the metal: after the
loader runs, a boot-time self-check confirms `frameSizeAt` resolves a real
registered JIT frame in range and rejects a PC just past it — the new `F` line.
(The JIT itself still emits no `athrow`/exception tables, so a JIT'd method cannot
yet *originate* or *catch* an exception; the frame table is what lets one
*propagate through* a JIT'd frame to a handler further up.)

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
genuinely one compiler. This is the biggest single piece of M5, so it is broken
into verifiable increments (each keeps the image byte-identical or QEMU at `*M`):

- ✅ **4.1 — `CodeBuffer` JDK-free.** The core's emit target: `int[]` grown by hand
  (was `List<Integer>`), encodings via `A64Enc` not `A64`. `A64.loadImm64` now
  returns `int[]`. Prerequisite for the core to emit on the metal. Image identical.
- ✅ **4.2 — the symbol seam.** `compiler/Symbols` is the interface: the compiler
  names a symbolic target by <em>constant-pool index</em> (or, for its own
  synthesised runtime calls, a helper id) and the implementation decides what to
  emit — a `call`/`callHelper` BL, or a `tib`/`type`/`interfaceType`/`staticField`/
  `string`/`exceptionSlot` address load. The writer implementation (`WriterSymbols`,
  an inner class) emits a placeholder and records `CallSite`/`TibRef`/… exactly as
  before; all eight record-creations now live *only* there, so the ~2400 lines of
  lowering beneath it never touch a relocation record. `emitCall` keeps the calling
  convention and delegates just the BL; `getstatic`/`putstatic`/`ldc`/`new`/
  `athrow`/`instanceof`/`invokeinterface` route their symbolic emit through the
  seam. Byte-identical image. The metal implements the same interface in 4.4,
  emitting resolved addresses from its registries — and that is where the deferred
  stage-2 `String`-key and collection removals finally land, since the core above
  the seam no longer holds either.
- ✅ **4.3 — a shared cp/bytecode view.** `ClassReader` is now the single authority
  on constant-pool *entry* layout: it gained JDK-free, offset-based decoders
  `refClassNameOff`/`refNameOff`/`refDescOff` (the `*ref → class` and
  `*ref → NameAndType → name/descriptor` chains), plus `stringUtf8Off`/`intValue`.
  The metal `Loader`'s three duplicate readers (`mrefNameOff`/`mrefDescOff`/
  `refClassNameOff`) and `staticAddr`'s inline copy now delegate to them over
  `gbytes` (the heap copy `constantPool` already parses) — the `gcp` offsets are
  blob-relative and content-identical, so the returned offsets stay valid against
  `gbase` for the cross-blob `utf8EqAt` links. `ClassReaderTest` cross-validates
  every member ref / String / Integer entry against `ClassFile` on the seed JVM
  (class-reader 39 → 85 checks). Image behavior unchanged: QEMU still reaches
  `*M F`. Remaining: the metal's *cross-blob* comparisons stay address-based
  (registries hold `long` addresses, not `byte[]`) — unifying those is entangled
  with 4.4.
- ✅ **4.4 — lift the lowering into the core** and route both `BaselineCompiler` and
  `vm/Loader` through it, then delete `Loader`'s `emitOp`. One compiler at last.
  The calling convention is already unified (M5.2), so what remains is that the
  lowering must stop resolving symbols with `String` keys / `ClassFile` objects
  above the seam — the metal resolves the same references through Utf8-offset
  registries. Ordered, each slice verified byte-identical (writer) and `*M F` (metal):
  - ✅ **4.4a — symbol seam: numeric query methods.** The values the lowering
    resolved by `String`/`ClassFile` now come back through `Symbols` as `int`
    queries: `fieldOffset(fieldCp)`, `objectSize(classCp)`, `vtableSlot(methodCp)`,
    `interfaceSlot(ifaceMethodCp)`. `resolve`/`resolver` are now reached only from
    inside `WriterSymbols` — symbol resolution sits wholly below the seam. Byte-
    identical image. (The remaining above-seam checks are name-*identity* predicates
    — `magic/Magic`, root-`<init>` — which compare names rather than resolve a value;
    they fold naturally into 4.4b, where name access itself moves onto `ClassReader`.)
  - ✅ **4.4b — parse view: `BaselineCompiler` off `ClassFile` onto `ClassReader`.**
    So the compiler reads `byte[]`+offsets like the metal already does (4.3), not
    JDK `ClassFile` objects. Progress (each byte-identical):
    - ✅ **b.1 cp view + constants.** `BaselineCompiler` holds `classBytes`/`cpOff`/
      `cpTag` (handed over by `ClassFile`, which now retains its bytes); `ldc`/`ldc2_w`
      read constants via `ClassReader.intValue`/`longValue` + the tag table.
    - ✅ **b.2 descriptor arg parsing off strings.** `ClassReader.descParamCount`/
      `descReturnKind` decode a descriptor's Utf8 in place; `emitCall` takes
      `paramCount`/`returnsValue` (so helper calls need no `"(JJ)I"` literals —
      metal has no `ldc`-string) and the invoke lowerings use them.
    - ✅ **b.3 call-classification predicates.** `isIntrinsicCall`/`intrinsicEmitsCall`/
      `isSkippableInit` join the seam; `invokestatic`/`invokespecial`/`isNonLeaf`
      branch on booleans, not `String.equals`. `resolve`/`staticKey`/WriterSymbols
      keep their `ClassFile` — they are writer-side, below the seam.
    - ✅ **b.4 the last two shared-lowering `ClassFile` uses.** `lowerIntrinsic` now
      dispatches on an `int` id (`compiler/Intrinsics`) resolved per world behind
      `Symbols.intrinsicId` — the writer keeps its `String` switch, the shared side
      branches on the id. `athrow` iterates the exception table as parallel `int`
      arrays (`exStartPc`/`exEndPc`/`exHandlerPc`/`exCatchType`), which the writer
      driver flattens from `ClassFile.ExceptionEntry[]` in `loadExceptionTable`.
      **The shared lowering (step + all lower*/emit* helpers) now references no
      `ClassFile`** — every remaining use is in `WriterSymbols`/`resolve`/`staticKey`
      or the writer drivers. Byte-identical image throughout.
    - ✅ **b.5 core/writer split.** The shared lowering now lives in `compiler/Baseline`
      — a class holding **no `ClassFile`** (only `classBytes`/`cpOff`/`cpTag` + `Symbols`
      + the lowering), constructed `(classBytes, cpOff, cpTag, symbols)` with a
      `compileBody(code, descOff, isStatic, maxLocals, base, isEntry)` entry. Done in
      two byte-identical stages:
      - ✅ **stage A** — `WriterSymbols` extracted to its own class (with `resolve`,
        the String keys, the six relocation-record lists).
      - ✅ **stage B** — the ~1400-line lowering moved into `Baseline`; `BaselineCompiler`
        is now a 100-line writer driver that parses with `ClassFile`, flattens the
        exception table + `descOff` (new `ClassFile.Method.descOff`) into the core's
        primitives, and zips the core's handler word-ranges with catch classes into
        `CompiledMethod`. `emitPrologue` reads params via `ClassReader` over the
        descriptor Utf8 (no `paramTypes`/`ClassFile.Method`). Image byte-identical;
        QEMU `*M F`.
      - ✅ **stage C (metal-compat, part 1)** — removed the constructs the metal
        compiler can't handle at all: the `switch`es in `step`/`lowerIntrinsic`/
        `opLen`/`binop`/`arrayElemSize` became if/else-if chains and ternaries, the
        `Bin` enum became `BIN_*` int constants, and `Math.min`/`Math.max` became
        conditionals. Image byte-identical. Measured with `tools/M5Gap`: the core
        `Baseline` now compiles **60 of its 70 methods** with joe-ng's own compiler.
      - ◐ **stage C part 2 — the last self-host gaps** (measured with M5Gap on `Baseline`):
        - ✅ **diagnostics** — `bad`/`unsupported`/`unsupportedOpcode` (String concat +
          `String.format` → invokedynamic 0xBA; JDK exception classes) are gone. A
          fatal compile error now goes through `Symbols.fail(reason, a, b)`, which never
          returns: `WriterSymbols` throws the rich diagnostic (same messages, so M5Gap
          still classifies by the `opcode 0xNN at bc=` prefix), the metal will halt.
          Fixing this surfaced a masked `dup_x1` (0x5A) from `sp++`/`--sp` on a field in
          `pushReg`/`popReg`; rewritten to `sp += 1` / `sp -= 1`. **`Baseline` now
          compiles 63 of its 65 methods**; the only remaining blocker is:
        - ✅ **anewarray (0xBD)** — added: a reference array allocates exactly like a
          `long[]` (8-byte pointer elements via `Heap.allocArray`), the element-class
          operand unused (`aaload`/`aastore` are untyped). `opLen`/`isNonLeaf` updated,
          `CompilerTest` covers it. Fixing it unmasked one more self-host gap —
          `addFixup`'s `fixups[i] = new Fixup(a,b,c,d)` peaked at operand depth 8, one
          past the 7 operand registers (`OP_MAX`); binding the `Fixup` to a local first
          drops it to 6.
        - ✅ **The core is now fully self-compilable: `tools/M5Gap` reports
          `compiler/Baseline` at 66/66 methods, 0 blocked** (one method uses >10 locals
          and compiles via the overflow-locals path). Byte-identical image; QEMU `*M F`.
          This closes 4.4b: the code generator both is metal-instantiable (no `ClassFile`)
          and compiles under joe-ng's own compiler — ready for 4.4c (`MetalSymbols`).
  - ✅ **4.4c — `MetalSymbols implements Symbols`**, the other half of the seam.
    `vm/MetalSymbols` resolves each cp index to a concrete address *now* (the metal
    has already loaded its deps): calls → `Loader.resolveCallBuf`; helper calls →
    the writer-stashed `VM.heapAlloc`/`allocArray`/`gcCollect`/`instanceOfAddr`/
    `checkCastAddr`/`unwindAddr`; tib/type/interfaceType/staticField → `Loader.tibOfClass`/
    `typeOfClass`/`ifaceTypeOfMethod`/`staticAddr` loaded via fixed-width MOVZ+MOVK; the
    int/bool queries → `fieldOffsetOf`/`objectSizeOf`/`vtableSlotOf`/`ifSlotOf`/
    `isRealSpecial`; `fail` halts. The needed `Loader` resolvers are package-visible.
    Every method is a real resolver — the initial stubs are all implemented:
    - **interned strings** → `Loader.internString` allocates a heap `byte[]` (the
      writer's array layout) and bakes in its address.
    - **magic intrinsics** → `Loader.isMagicOwner` (Utf8 compare) + `magicId`
      (packed-name compare for the memory/`bytes` ops a JIT'd class might use; an
      unrecognised magic op halts).
    - **exception slot** → a heap word, with `Loader.compileMethod` now extracting the
      method's real exception table so JIT'd `try/catch` fires (was `NO_EX`).
    All exercised on metal by `Guest.answer` → '*' (`Magic.bytes("*")[0]`, a
    `throw new MyExc()`/`catch`). The object-model/dispatch reconciliation that this
    depended on is 4.4e steps 1–2.
  - ◐ **4.4d — a metal-ready emit stack, code sink, and branch convergence.**
    Measured the shared emit stack with M5Gap: 222/232 methods self-compile; the sole
    blocker is `A64`'s 14 `throw new IllegalArgumentException(...)` — String concat
    (invokedynamic) plus a JDK exception class. It can't be funnelled to a metal-safe
    fault either: `throw` needs a `Throwable`, and every `java/lang` exception's
    constructor chain is unresolvable on metal. So the shared/metal path must use the
    validation-free `A64Enc`, and `A64` stays the writer's checking wrapper.
    - ✅ **d.1 — complete `A64Enc`** with the friction-free encodings the core/metal use
      (andReg/orrReg/eorReg/sdivReg/lslv/lsrv/asrv, addRegLsl, sxtb/sxth/uxth, cset/
      csinv, br, ret(rn), cbnz, ldrw/ldrb/strb, dsb/isb/wfe/eret, align16, loadImm64).
      `A64` now delegates to each, so `A64Test` transitively verifies them and the
      writer image stays byte-identical (A64 84 checks green).
    - ✅ **d.2 — routed `Baseline`/`MetalSymbols` off `A64` onto `A64Enc`.** Both now
      name only the validation-free encoder. Frictions resolved: `A64Enc` gained
      int-based `msr`/`mrs` + `sysReg` and packed per-register int constants (no `Sys`
      record), the condition-code/`XZR` constants, and `movToSp`/`movFromSp`/`tbz`/
      `tbnz`; the core's branch call sites were converted from byte to *word*
      displacements (dropping the `*4`) to match `A64Enc`'s branch convention. `A64`
      delegates its `msr`/`mrs`/`tbz` to the new `A64Enc` entries so `A64Test` still
      covers them. **M5Gap over the whole shared emit stack — `Baseline`,
      `MetalSymbols`, `CodeBuffer`, `A64Enc`, `ClassReader`, `ObjectModel`, … — is now
      190/190, 0 blocked.** The compiled methods are unchanged (compiler tests check
      exact encodings) and QEMU still reaches `*M F`; the image differs only by
      `A64Enc`'s new static constants (it is already in the metal image via `Loader`).
    - ✅ **d.3 — the metal code sink** (subsumed by 4.4e). The metal no longer has its
      own sink or branch model: `emitMethod` runs `Baseline`, which emits into its own
      `CodeBuffer` and resolves branches with its forward-fixups; the metal just blits
      the resulting words to `cout`. The bare `emit(word)` and two-pass `pass1` are gone.
  - ✅ **4.4e — route `Loader.emitMethod` through the shared `Baseline`, delete `emitOp`.**
    The payoff step — validated end-to-end by QEMU, not byte-identity. Investigation
    mapped exactly what it needs:
    - ✅ **fixed-width metal address loads.** `MetalSymbols` now emits a fixed 2-word
      MOVZ+MOVK for every tib/type/static load (was variable-width `loadImm64`), so a
      compiled method's size is placement-independent — a prerequisite for the metal's
      **size → place → emit** phasing (`sizeMethod` places `mBuf[i]` before `emitMethod`).
    - ◐ **the object-model reconciliation (the crux).** JIT'd objects must match
      `ObjectModel` so `VM.instanceOf` and the shared core read them like image objects.
      - ✅ **step 1 — `Type` layout.** `buildTib` now builds a 24-byte
        `{instanceSize@0, super@8, itableDir@16}` `Type`; `emitInstanceof`/
        `emitCheckcast` walk `super` at `Type+8` and `emitInvokeVirtual` reads the imap
        from `Type+16`. The flat imap still occupies the `itableDir` slot. QEMU `*M F`.
      - ✅ **step 2 — the itable directory.** Interfaces now get a `Type` + `clType`
        entry when loaded; `parseFields` captures each class's implemented-interface
        list; `buildTib` builds an `ObjectModel` `{interfaceType, itable}` directory at
        `Type+16`. Since `Guest`/`Alpha`/`Beta`/`Greeter` are all metal-loaded (one
        world), the global-slot imap stays consistent, so every directory entry shares
        that flat imap as its itable and `ifSlotOf` keeps returning the global slot —
        the directory just adds the interfaceType-keyed lookup the core searches for.
        The metal's own `emitInvokeVirtual(iface)` reads `dir[0].itable` (one extra
        load; `wordsFor` +1) to keep dispatching until the core takes over. QEMU still
        prints `Guest.answer`→'*'. The metal's metadata is now `ObjectModel`-conformant,
        and `interfaceType` resolves (interfaces have `clType`), so the core's
        `invokeinterface` will work on JIT'd objects.
      - ✅ **step 3 — instanceof/checkcast via helpers** (subsumed by 4.4e). The metal's
        inline `emitInstanceof`/`emitCheckcast` are deleted; a JIT'd `instanceof` now
        lowers, through `Baseline`, to a `VM.instanceOf`/`checkCast` call — which reads
        the JIT'd object's now-`ObjectModel` `Type` exactly as it reads image objects.
        Not yet exercised on metal (no JIT'd guest uses `instanceof`); a guest test that
        does would confirm it end-to-end — a natural next follow-up.
    - ✅ **wire `emitMethod`.** `emitMethod` now compiles each method with
      `compiler/Baseline` — the same code generator the writer uses. The work-set
      carries `descOff`/`isStatic` per method; `extractCode` copies a method's bytecode
      into a `byte[]`; `compileMethod(i, base)` runs `Baseline(gbytes, gcp, gcpTag,
      MetalSymbols).compileBody(...)`. Fixed-width metal address loads make the word
      count placement-independent, so `sizeMethod` compiles at base 0 to reserve
      `mBuf[i]` and `emitMethod` re-compiles at the real base and blits to `cout`, then
      registers the JIT frame from `Baseline.frameSize()`. `CodeBuffer.toBytes` was
      decoupled from `A64` (→ `A64Enc.wordsToLittleEndian`) so the now-in-image
      `CodeBuffer` doesn't drag the `A64.Sys` record's invokedynamic `toString` in. The
      old `emitOp`/`pass1`/`emit*`/`setFrame` lowerings and their globals are deleted.
    - **✅ DONE.** QEMU prints `Guest.answer`→'*' (`new` + `invokeinterface`),
      `Math.max`→'M', and `*M F` — the on-metal JIT is driven by the same `Baseline`
      the writer runs. **One compiler, both worlds: the self-hosting fixpoint on metal.**

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
self-hosting. ✅ Reached — the metal JIT runs `Baseline`; `Guest`/`Math` compile
on metal across `new`, virtual/interface/static dispatch, class+interface
`instanceof`, string literals, magic intrinsics, and `throw`/`catch`.

### M5.5 — the boot-image writer on metal (scoped, not started)

The compiler and classfile parser now run on metal; what's left for full M5 is the
**boot-image writer** — `writer/ImageBuilder` (575 lines): object/TIB/itable/string
layout, cross-reference relocation, unwind-table generation, `kernel8.img` emission.
Then joe-ng builds its own next image and the seed JVM is gone.

**The gap, measured** (M5Gap over `writer/*`): **6 of 39 methods compile; 33 blocked.**
Unlike the runtime (JDK-light by design), the writer is JDK-*heavy* — its whole job
is name→address bookkeeping:
- **collections** — `Map`/`Set`/`List` (`HashMap`, `LinkedHashMap`, `LinkedHashSet`,
  `ArrayList`) drive every layout table (`wordOffset`, `typeWord`, `tibWord`,
  `strWord`, `staticWord`, `itableDirWord`, …). 28 methods reference a JDK class.
- **String keys** — everything is keyed by `String` (`"owner.name+desc"`), with
  concat (invokedynamic, 4 methods) building keys and messages.
- **file IO** — `java.nio` reads input `.class` files and writes the image.

**Sub-problems, roughly in dependency order:**
1. **Layout registries without collections.** Replace ImageBuilder's `Map<String,Integer>`
   tables with primitive arrays keyed by **Utf8 offset** — exactly the pattern
   `vm/Loader` already uses for its class/method/field registries. This is the bulk of
   the work and subsumes the String-key problem (identity by Utf8 compare, not `String`).
2. **Parse via `ClassReader`, not `ClassFile`.** ImageBuilder resolves classes through
   the JDK-based `ClassFile`; on metal it must read `byte[]`+offsets through the shared
   `ClassReader` — the same b.1–b.5 migration already done for `Baseline`, applied to the
   writer's own parsing (owner/name/desc lookups, vtable/field/interface walks).
3. **Diagnostics** — route the writer's error strings through a fault seam / drop them,
   as the compiler's did (§b.4/C.2).
4. **Input (blob source).** Where the classes-to-image come from on metal. Cheapest
   first: the **embedded blobs** the loader already carries (a fixed self-rebuild set),
   deferring a real filesystem. A general source needs an SD/FAT driver — M6+ territory.
5. **Output (image sink).** Where the built `kernel8.img` goes. Cheapest first: build it
   **in a heap buffer** and prove it byte-for-byte equals the seed-built image (a pure
   in-memory fixpoint check, no persistence). Writing it to SD for a real reboot needs a
   block driver — again M6+.

**Pragmatic milestones (smallest verifiable first):**
- **M5.5a — writer core off collections+ClassFile**, still run on the *seed JVM*,
  producing a byte-identical image. Pure refactor, fully verifiable off-metal (the same
  discipline that de-risked the compiler split). **In progress:**
  - ✅ `util` package (`Vec`/`StrIntTable`/`StrSet`) — shared JDK-free containers.
  - ✅ `ImageBuilder`'s own maps/sets/lists → `util` (8 maps→`StrIntTable`, 7 sets→`StrSet`,
    lists→`Vec`); `forEach` lambdas → indexed loops (kills the layout-loop invokedynamic).
  - ✅ Compiler relocation contract off collections: `CompiledMethod`'s six `List<*Ref>` +
    `List<HandlerRange>` → `Vec`; `WriterSymbols` builds/returns `Vec`.
  - ✅ `ClassFile` class-model queries (`virtualMethods`/`vtable`/`interfaceMethods`/
    `interfaceSlot`/`allInterfaces`) off `java.util` → `Vec`/`StrSet`; `Arrays.copyOfRange`
    → manual copy; the `Function<String,ClassFile>` resolver → a nested `ClassFile.Resolver`
    interface (callers pass the resolver *object*, not `this::resolve` — no invokedynamic).
    **`compiler/*` is now fully JDK-free; `ClassFile`'s only JDK left is the seed-only
    `parse(Path)` file load + the byte[]-ctor bad-magic `IOException` (the metal
    exception/fail model, deferred to c).**
  - ⬜ Remaining: `ImageBuilder`'s `classes` parse-cache (`Map<String,ClassFile>`) + its
    file-IO (`classesDir`/`parse`/`StandardCharsets`) — both the *seed driver* role that
    M5.5c replaces with blob access, so shape depends on the metal-driver design.
- **M5.5b — compile the ported writer with M5Gap → 39/39**, closing the metal gaps.
  **In progress** (gap over `writer/ImageBuilder`: 8/20 → 11/20). Closed the gaps that
  were missing *compiler capability* or were self-contained refactors:
  - ✅ `dup2` (0x5C) + `irem` (0x70, via new `A64Enc.msub`) in `Baseline` — the
    `arr[i] op= x` / byte-packing idioms. Closed `writeBytes`. (Compiler is in-image, so
    this changes `kernel8.img`; QEMU-verified, not byte-identity.)
  - ✅ `util.IntVec` (primitive growable int[]) — `generateInitClasses` off `Vec<Integer>`
    boxing. Byte-identical. (Now trips the operand-depth gap below.)
  - ✅ **String-literal path → `byte[]` content keys.** New `util.Bytes` (content
    eq/concat/join), `util.ByteKeySet`, `util.ByteKeyIntTable`. `StrRef.text` and the
    `strings`/`strWord` tables now key on bytes; `stringWords`/`writeStringObject` drop
    the `getBytes` re-encode. Closed both. Byte-identical.
  - **Key finding — the method/class/static key migration is fused with M5.5c, not
    separable here.** `MetalSymbols` records *no* relocations, so the String-keyed
    contract (`CallSite`/`Tib`/`Static`/`TypeRef`, `BaselineCompiler.key`) is
    seed-only — good — but `ImageBuilder`'s discovery (`build`/`use`/`addTypeClass`/
    `vtableLength`/`implementedUsedInterfaces`, 16 sites) is bound to `ClassFile`'s
    **String API** (`vtable(String)`, `allInterfaces(String)`, `resolve(String)`;
    `ClassFile` internally keys `superClass`/`interfaces` as String). Re-keying those on
    `byte[]` standalone only adds throwaway byte[]↔String conversions in methods that are
    M5Gap-blocked on `ClassFile` regardless, and closes just ~2 leaf methods (`ownerOf`,
    `fillStatic`). The String boundaries *vanish* once discovery moves to `Loader`'s
    byte-offset registries — so the key migration belongs **with the M5.5c discovery
    rewrite**, done together. (Explored and reverted to keep the tree byte-identical.)
  - ✅ **Operand-stack depth (compiler).** `generateInitClasses`'s 9-arg
    `new CompiledMethod` tripped Baseline's 7-register operand cap. Bundled the six
    relocation Vecs into a mutable no-arg `BaselineCompiler.Relocations` holder →
    CompiledMethod is a 4-arg constructor. Closed it. Byte-identical, and a cleaner
    contract. (Chosen over invasive operand-stack spilling.)
  - **M5.5b is effectively complete at 12/20** — every *platform-independent* gap is
    closed. The residual 8 are all M5.5c-bound and close there by construction:
    *seed file-driver* (`<init>` `HashMap`, `resolve`/`lambda` path concat, `compile`
    `RuntimeException`, `lookup` `ClassFile.parse`) becomes blob access; and the
    *ClassFile-discovery* trio (`build`/`use`/`ownerOf`) becomes `Loader`-registry
    discovery, at which point the layout tables + relocation records go byte-offset —
    the **key migration**, done as part of that rewrite rather than as standalone churn.
- **M5.5c — run the writer on metal into a heap buffer** over the embedded blobs, and
  assert the bytes equal the seed-built image: the self-build **fixpoint**, no
  persistence, no new drivers.

  **Scoped (grounded in the M5.5b findings).** The pivotal fact: the metal `vm/Loader`
  (1622 lines) *already is* a metal class-loader — it parses `.class` blobs through the
  shared `ClassReader`, flattens vtables (superclass-first + overrides), builds the
  interface/itable registry, computes field layout + object size, constructs each
  class's `Type`/`TIB`, interns strings, and resolves every reference — all over
  **byte-offset registries** (`rgClassOff/rgNameOff/rgDescOff`, `clNameOff`, …). What it
  does *not* do is emit a **relocatable AOT image**: fixed offsets from `0x80000`,
  recorded-then-patched relocations, the `kernel8.img` word layout. Loader JITs into
  *live heap* with runtime addresses (`MetalSymbols` resolves immediately, records
  nothing). So M5.5c is not "run `ImageBuilder` verbatim on metal" — it is porting
  `ImageBuilder`'s ~650 lines of AOT layout onto the class-model Loader already exposes.
  This is the **M5.4 compiler-unification pattern applied to the class model**: one metal
  class-model serving both the runtime linker (Loader) and the image writer.

  Sub-steps, smallest-verifiable-first:
  1. **Unify the class-model (discovery rewrite) — subsumes the M5.5b key migration.**
     `ImageBuilder`'s `ClassFile`-bound discovery (`build`/`use`/`ownerOf` + the seed
     `resolve`/`lookup`; `ClassFile.vtable`/`allInterfaces`/field layout) → Loader's
     byte-offset registry queries, which already compute all of it on metal. As identity
     becomes a byte offset, the layout tables + relocation records go off `String`/`byte[]`
     content onto offsets — the key migration, done *here* where the `ClassFile` String
     boundaries disappear rather than as standalone churn. Closes
     `build`/`use`/`ownerOf`/`resolve`/`lookup`.
     - ✅ **1a: seam extracted.** `ClassModel` interface + `SeedClassModel` (ClassFile
       impl); `ImageBuilder`'s 13 class-model queries route through it. Byte-identical.
     - ✅ **1a.2: class source metal-shaped.** `ImageBuilder`'s file I/O + `Path` +
       `HashMap<String,ClassFile>` → a name→bytes `ClassRegistry` (lazy parse cache, pure
       lookup, no I/O); `build`/`compile`/`lookup` drop `throws IOException`. Seed-host file
       walking moves to `BuildRuntimeImage`. This is step 2's I/O-removal, pulled forward as
       an off-metal, byte-identical slice — so the off-metal-verifiable part of M5.5 now ends
       here, not at 1a. Remaining registry work (fill from embedded blobs) is step 2.
     - **1b: metal `ClassModel` — approach B (fresh over the step-2a table).** Decided
       against the `Loader`-registry reuse: a `MetalClassModel` that reads class bytes from
       the class table (by name) via the shared `classfile.ClassReader`, mirroring
       `ClassFile`'s exact flattening, is self-contained, marker-verifiable query-by-query,
       and — by porting the *same* algorithm — far likelier to reproduce the seed writer's
       byte-identical output at the fixpoint than `Loader`'s differently-ordered registries.
       - ✅ **1b.1: interface neutralized (byte-identical).** `ClassModel` returns
         writer-owned `VSlot`/`Method` records instead of `classfile.ClassFile.VSlot`/`Method`,
         so a metal impl (which has no `ClassFile`) can satisfy it. `SeedClassModel` copies
         into them; `ImageBuilder` consumes them. Image SHA unchanged.
       - **1b.2: `MetalClassModel` impl, marker-verified per query.** New `vm/MetalClassModel`
         loads a class from the table by name (`bytesOf`) and answers queries via `ClassReader`.
         - ✅ **leaf queries.** `isRoot`, `superIs`, `instanceFieldCount`, `hasClinit` —
           single-class, no chain walk. A metal `K` marker checks them against known shapes
           (Dog→Animal super, Cell's 1 field, Config's `<clinit>`, Object is root); verified in
           QEMU, independent of the full writer.
         - ✅ **chain walks.** `vtable` (flatten super-first + override-in-place, via `byte[][]`
           scratch — metal supports reference arrays), `interfaceMethod{Count,Slot}`,
           `implementsInterface` (allInterfaces), `findImplIs` (walks supers, Code-attr check) —
           mirror `ClassFile`'s recursion. A metal `V` marker checks 15 hierarchy facts (Dog
           overrides `Animal.sound` in slot 0 with owner Dog; Robot implements Speaker, Dog
           doesn't; Cell's two virtuals; Speaker's one itable slot). Verified in QEMU. The full
           `ClassModel` query set now runs on metal, independent of the writer.
       - ⬜ **1b.3: byte-offset identity / key migration** for `ImageBuilder`'s layout tables +
         relocations — lands with steps 3/4, verified end-to-end by the fixpoint.
  2. **Blob source (input).** Today only the *guest* classes are embedded as blobs; the
     writer reads the rest (`vm/*`, `compiler/*`, `asm/*`, `classfile/*`, `util/*`,
     `objectmodel/*`, `magic/*`) from `.class` files. The class-name→bytes registry
     (`ClassRegistry`) that replaces the file I/O + `classes` `HashMap` already landed in
     1a.2.
     - ✅ **2a: class table embedded + metal lookup verified.** The writer now emits the
       **compile-reachable class set** (22 classes, ~65 KB — image 118 KB → 187 KB) as a
       name-indexed table: a directory of `{nameAddr, nameLen, bytesAddr, bytesLen}`
       entries plus the name/`.class` bytes, exposed via `vm/VM.classDir`/`classCount`.
       `ClassRegistry.reached()` supplies the set (classes it parsed during discovery). A
       metal `VM.classTableReady()` looks every class up **by its own stored name**,
       asserts it resolves back to its own bytes with intact `0xCAFEBABE` magic, and
       lights a `C` QEMU marker — the self-build's input path, proven on metal.
     - ⬜ **2b: metal consumption — couples to 1b/3.** `ImageBuilder.resolve` still returns
       a seed-only `ClassFile`; a metal `ClassRegistry`/`Loader` reading the table lands
       with the class-model unification. Also fold the 6 runtime-load blobs + `Math` into
       the table so the metal writer draws its *entire* input from one place.
  3. **Metal layout engine (discover → size → place → compile → patch → heap buffer).** The
     seed `ImageBuilder.compile` can't be reused on metal (it drives `BaselineCompiler` over a
     seed-only `ClassFile`), so the writer is a native port driving the shared `Baseline` core
     directly, over the class table + `MetalClassModel`.
     - ✅ **3a: relocating compile — `MetalWriterSymbols`.** The metal twin of
       `compiler.WriterSymbols`: where `MetalSymbols` (the JIT) resolves references to live
       addresses and records nothing, this emits fixed-width placeholders (`bl(0)` /
       `reserveAddr`) and *records* the relocation sites, resolving cp refs from its own
       `(classBytes, cpOff)` via `ClassReader` (not Loader's globals). A metal `B` marker
       drives `Baseline` over `Uart.write` exactly as `Loader` drives the JIT and asserts the
       result: one recorded call, a placeholder `bl` at its site, callee resolved as `putc`,
       clean compile. Verified in QEMU.
     - **3b: heap-buffer sink + layout driver.**
       - ✅ **3b.1: build + execute a call closure.** `VM.selfBuildClosureAndRun` discovers the
         `{Uart.putc, Uart.putRaw}` closure by BFS over `MetalWriterSymbols`' recorded calls,
         places each method contiguously, compiles at its base, allocs a `Heap` buffer, patches
         every `bl` to its callee's base (matching callees to placed methods by Utf8-content
         key), `dsb`/`isb`, and *executes* the built `putc` — which prints `~` over the UART. An
         `L` marker follows (all calls resolved). `MetalWriterSymbols` gained real `Magic`
         intrinsic resolution (the 7 memory ops) so `putRaw` compiles. QEMU shows `~L`: the
         metal writer built working code and it ran. (Compiler limits hit en route: 7 operand
         slots → static context + low-arity helpers; no `pop2` → assign the void call's return.)
       - **3b.2: scale to the full closure — one reloc kind / region at a time.**
         - ✅ **static fields.** `MetalWriterSymbols.staticField` now records the ref identity
           (owner+field Utf8 offsets); a `VM.selfBuildStaticsAndRun` driver builds
           `Counter.{bump,get}`, lays out a zeroed statics slot, patches each getstatic/putstatic
           address load to it (`movz`/`movk`, as `CodeBuffer.patchAddr`), then runs `bump()`×3 +
           `get()` → 3. A metal `S` marker verifies it in QEMU. First non-call reloc kind + first
           data region on metal.
         - ✅ **object allocation (`new` → `tib` + Type/TIB + helper calls).** Where
           `MetalClassModel` first drives the *layout*: `MetalWriterSymbols` now implements
           `objectSize`/`fieldOffset`/`isSkippableInit` via `MetalClassModel` (field layout added:
           `instanceFieldOffset`) and records `tib` sites. `VM.selfBuildNewAndRun` builds
           `Cell.make` (=`new Cell(v).value`) + `Cell.<init>`, lays out Cell's `Type`
           (instanceSize from `instanceFieldCount`) + a minimal `TIB`, patches the `new`'s TIB
           address load, the `Heap.alloc` **helper** BL (to the writer-stashed `VM.heapAlloc`),
           and the `<init>` call, then runs `make(0x37)`→`0x37`. Metal `O` marker (verified QEMU).
         - ✅ **`type` (instanceof/checkcast).** `MetalWriterSymbols.type` records the class
           identity; `layoutClassRegions` collects `type`-referenced classes too (reusing the
           Type/TIB region), and `patchNewAndWrite` patches `type` sites to the class's Type
           address. `VM.selfBuildInstanceofAndRun` builds `Cell.selfCheck` (=`new Cell(0)
           instanceof Cell`), patches the `new`, the `type` Type-load, and the `VM.instanceOf`
           helper, then runs it → `1` (the object's `TIB→Type` matches the target). Metal `T`.
         - ✅ **`string` (ldc literal).** `MetalWriterSymbols.string` records the literal's Utf8
           offset; `patchNewAndWrite` interns it as a heap `byte[]` (`internLiteral`, mirroring
           `Loader.internString`) and patches the `ldc`-string address load to it.
           `VM.selfBuildStringAndRun` builds `Cell.tag` (=`Magic.bytes("Z")[0]`) and runs it →
           `'Z'` (`baload` off the interned array). Metal `g`.
         - ✅ **invokevirtual (`vtableSlot` + full TIB vtable).** Completes the object model:
           `MetalWriterSymbols.vtableSlot` → `MetalClassModel.vtableSlot`; `MetalClassModel`
           exposes the flattened vtable slots (`vtableSlotName/Desc`); `addClassRegion` now sizes
           the TIB to the vtable and fills each slot with the *placed* method's address (ordered
           by the class model). `VM.selfBuildVirtualAndRun` builds `Cell.viaVirtual`
           (=`new Cell(v); c.get()`) + `get`/`inc`, and dispatches `get()` through the TIB →
           `0x37`. Metal `D`.
         - ✅ **invokeinterface (`interfaceType`/`interfaceSlot` + itables).** The biggest data
           region. `MetalWriterSymbols` records `interfaceType` sites + resolves `interfaceSlot`
           via `MetalClassModel.interfaceMethodSlot`; `MetalClassModel` exposes interface-method
           iteration (`interfaceMethodNameAt/DescAt`). `layoutClassRegions` now runs two passes —
           interface Types first, then class Types/TIBs — and `addClassRegion` builds each
           implementor's itable **directory** ({interfaceType, itable}* + a zeroed sentinel) and
           **itables** (each interface method → the placed impl address, via `implementsInterface`
           + `findPlacedBytes`), setting `Type.itableDir`. `VM.selfBuildInterfaceAndRun` builds
           `Robot.probe` (=`Speaker s = new Robot(); s.speak()`) and dispatches through the itable
           → `'R'`. Metal `i`.
         - ✅ **`exceptionSlot` (throw/catch) — the last reloc kind.** `MetalWriterSymbols` records
           `exceptionSlot` sites; the driver allocates the closure's in-flight-exception word and
           `patchNewAndWrite` patches the store/load sites to it. `VM.selfBuildExceptionAndRun`
           builds `MyExc.probe` (=`try { throw new MyExc(); } catch (MyExc e) { return 1; }`) — a
           same-method try/catch, so `athrow` resolves it inline (no cross-method unwind) via the
           exception slot + catch-type `type` load + `VM.instanceOf` — and runs it → `1`. Metal `e`.
         - **Every relocation kind is now covered on metal** (calls, static, `new`, `type`,
           `string`, invokevirtual, invokeinterface, exception). Marker line: `…S O T g D i e`.
       - **3b.3: cross-class discovery (BFS).** The single-class drivers generalize to multi-class
         closures — the discovery the seed `ImageBuilder.build` does.
         - ✅ **calls + statics.** A class cache (`loadClass`, parse-once) + a class-aware method
           table (`enqueueMethod`/`findMethodG` keyed by class+name+desc); the compile loop sets a
           per-method `cB/cOff/cTag/cAfterCp` cursor (sequential, so `findMethodBody`/`compileInto`
           are unchanged) and BFS-discovers callees by resolving each `call`'s (class,name,desc)
           from the caller's bytes. `VM.selfBuildCrossAndRun` builds `Cell.readCounter`
           (=`Counter.bump(); return Counter.get()`) across Cell+Counter, resolves the cross-class
           calls + the shared `Counter.count` static, and runs it → `1`. Metal `X`.
         - ✅ **new + virtual across classes.** The single-class drivers fold into a reusable
           `buildClosure(entry)`: BFS discovers callees *and* each `new`-ed class's vtable methods
           (`MetalClassModel.vtableSlotOwner` added); `layoutClassRegionsG`/`addClassRegionG` lay
           out cross-class Types/TIBs (vtable slots filled by `findMethodG` across classes);
           `patchCrossAndWrite` patches the TIB loads. `Animal.dogSound` (=`new Dog().sound()`,
           Dog in another class) dispatches through its TIB → `'W'`. Metal `y`. (`Cell.readCounter`
           now also goes through `buildClosure`.)
         - ✅ **interface (+ type/string/exception) across classes.** `buildClosure`'s layout runs
           two passes (interface Types, then class Types/TIBs + itable dirs — `buildItableDirG`/
           `buildItableG` resolve each impl via the class's vtable + `findMethodG`), and
           `patchCrossAndWrite` patches `type` (class or interface Type), `string`, `interfaceType`,
           and `exceptionSlot`. `Cell.viaSpeaker` (=`new Robot(); s.speak()`, Robot+Speaker in other
           classes) dispatches through the itable → `'R'`. Metal `J`. All kinds now cross-class.
         - ✅ **capstone: `Cell.capstone` → 262.** One closure spanning Cell, Robot, Speaker, Dog,
           Animal, MyExc, Counter (7 classes, ~13 methods) exercising *every* kind at once — new,
           invokevirtual, invokeinterface, instanceof, ldc-string, cross-class call, throw/catch,
           cross-class static — built by BFS, all regions laid out, all relocations patched, run →
           262. Metal `!`. (`Guest.answer` would be ideal but Guest/Alpha/Beta/Greeter are
           runtime-load blobs, not in the compile-reachable class table — folding those in is a
           step-3b.4 input concern.) **The metal layout engine is functionally complete.**
       - **3b.4: remaining breadth.**
         - ✅ **eager `<clinit>` init (`@`).** `discoverFrom`/`enqueueClinit` pull in a used class's
           `<clinit>` (via `MetalClassModel.hasClinit`, keyed off static/tib refs), and `buildClosure`
           runs every discovered `<clinit>` before the entry (closed-world eager init).
           `Cell.readConfig` (reads `Config.mark`) returns `0x37` — proving `Config.<clinit>` ran, vs
           the zeroed default.
         - ✅ **cross-method unwind (`u`).** `buildClosure` now registers every metal-built method's
           frame + try/catch machine-ranges into the jit unwind tables (`registerFramesAndHandlers`
           → `addJitFrame`/`addJitHandler`), so a throw in one built method unwinds into another's
           catch. Needed a self-PC relocation: `athrow`'s "PC inside this method" (fed to `VM.unwind`)
           was baked at base 0 by the compile-once-then-relocate writer, so `frameSizeAt` couldn't
           locate the throwing frame. Added a `Symbols.codePc` seam (default = resolve now, as the
           image/JIT compile at the final base; `MetalWriterSymbols` overrides it to record a site the
           writer patches to the final address). `MyExc.catchIt` (calls `throwIt`, which throws with no
           local handler) returns `1`. Catch-type resolved via `typeAddrOfClassCp` (Class cp → Type addr).
         - ✅ **runtime-load blobs folded into the writer's input (`G`).** The class table was only
           the compile-reachable set; the runtime-load blobs (Guest/Greeter/Alpha/Beta/MyExc/Math)
           were embedded raw but absent from it, so the metal writer's `MetalClassModel` could not
           resolve them. `ImageBuilder.addBlob` now carries the class name and folds each blob's class
           into the class table (`classDir`). The metal writer builds `Guest.answer`'s whole closure —
           nine methods across five formerly JIT-only classes, every reloc kind, double-implementor
           itable dispatch (Alpha slot 0 / Beta slot 1), class + interface `instanceof`, a JIT'd string,
           and a try/catch — and runs it → `42`. Caught a latent bug: `addClassRegionG` never wrote
           `TYPE_SUPER_OFFSET`, and `Heap.alloc`'s `zeroPayload` skips the header region (where
           `superType@8` lives), so a Type reused from prior heap inherited a stale non-zero super —
           sending `instanceOf`'s super-chain walk into garbage on the first *interface* `instanceof`.
           Now set to the laid-out super's Type (0 for roots).
         - ✅ **generated `initClasses`.** The eager-init used a Java-side call loop; the metal writer
           now *emits* a synthetic `initClasses` method — save `LR`, `BL` each discovered `<clinit>`
           in discovery order, restore, `ret` — as real A64 (`runGeneratedInitClasses`), reproducing
           the seed writer's `generateInitClasses` shape rather than driving the calls from Java, then
           calls it once before the entry. Proven by the `@` marker (`Cell.readConfig` → `0x37`, i.e.
           `Config.<clinit>` ran through the generated method). A closure with no `<clinit>` emits
           nothing. (The seed places this as a named `vm/VM.initClasses()V`; the metal analog is a
           per-closure buffer — folding it into the named whole-image layout is the fixpoint's job.)
       - ⬜ **breadth.** Embedded blobs beyond the class table; `int[] image` sink at `0x80000`-relative
         bases. Couples to 1b.3.
  4. **Fixpoint compare.** Run the metal writer from the same entry, produce `image′` in
     heap, and assert it word-equals the running kernel image at `0x80000` (the very image
     the metal booted from). Byte-equal ⇒ **fixpoint**: joe-ng compiled the exact image it
     is running. A single loud QEMU marker (e.g. `FIX`) on success.
     - ✅ **essence proven — per-method byte-identity (`=`).** `VM.selfFixpointInstanceOf`
       recompiles `VM.instanceOf` (stashed at `instanceOfAddr`, 137 words, relocation-free) on
       metal and asserts it is **byte-identical** to the running image's own copy — the metal
       writer reproduces the exact machine code it is executing. (Caught a sign-extension trap:
       `int[]` loads sign-extend while `Magic.load32` zero-extends, so the compare masks to 32
       bits.)
     - ✅ **relocated byte-identity (`+`).** `VM.selfFixpointCheckCast` recompiles `VM.checkCast`
       (stashed at `checkCastAddr`) — one reloc, a call to `VM.instanceOf` — patches that BL to the
       image's own `instanceOfAddr`, and is byte-identical to the image. The metal writer
       *relocates* exactly as the seed did. Both proofs share `fixpointEquals`.
     - ✅ **code-region layout reproduced (`Z`).** `VM.discoverImage` reproduces the seed
       `ImageBuilder`'s method discovery from `vm/VM.boot` — the same FIFO worklist (dedup-at-enqueue
       == the seed's dedup-at-dequeue), callees and synthesised helper calls **merged by emission
       order** (the seed unifies both in one `callSites` list; the metal writer splits them, so they
       re-merge by ascending word index), eager `<clinit>`s via `use()`, and each newly instantiated
       class's flattened vtable — then sizes every method with the shared `Baseline` and appends the
       generated `initClasses` last. Result: **485/485 methods identical in name, order, and size**
       to the seed, and all seven stashed method-address anchors (`reportFault`/`gcCollect`/`alloc`/
       `allocArray`/`instanceOf`/`checkCast`/`unwind`) land at their exact image addresses — the metal
       writer reconstructs the code region's `0x80000`-relative placement it booted from. `fixpointCodeLayout`
       checks the anchors on metal; the full 485/485 match is host-verified by diffing the ordered
       (size,key) lists. Exposed and fixed three latent codegen gaps the self-build forced into
       agreement: (a) `MetalWriterSymbols` recognised only 7 of the 33 `magic/Magic` intrinsics and
       never flagged `gc`/`call0`/`call2` as call-emitting → wrong sizes for `boot`/`run`; now mirrors
       `WriterSymbols` exactly. (b) `Baseline`'s `baload` zero-extends (ASCII) while the JVM sign-extends,
       and javac omits the redundant `i2b` after a `(byte)` cast — so the metal-resident compiler read a
       negative `bipush` operand (`& -8`) as `248`; the `bipush` handler now masks-then-casts to force an
       explicit `sxtb`. (c) `i2l` was a no-op assuming ints stay sign-extended in their 64-bit register,
       but int shift/or (e.g. `ClassReader.intValue`) leaves the high half zero — so large negative
       instruction encodings (`0xD65F03C0`) materialised in 2 words not 4; `i2l` now emits `sxtw`. Also
       fixed `MetalClassModel.MAX_SLOTS` (32 → 128; `MetalWriterSymbols`/`Baseline` have >32 virtual
       methods, and metal has no array-bounds checks, so the vtable scratch silently corrupted).
     - ✅ **data-region layout reproduced (`H`).** `VM.layoutDataRegions` reproduces the seed's layout
       of every region after the code — Types (instantiated + type-ref classes and their whole super
       chains, via `addTypeClass`), TIBs, interned strings, statics, itables, unwind frame/handler
       tables, blobs, and the class table — from the sets `discoverImage` now collects in the seed's
       per-method order (strings, type/interface refs, catch classes, statics, unwind counts). Every
       stashed boundary + count lands exactly: `staticsStart`/`staticsEnd`/`frameTable`/`handlerTable`/
       `guestBytes` (first blob)/`classDir`, and `frameCount` (487)/`handlerCount` (2). Needed the same
       call/helper-style merge for statics: the seed's `exceptionSlot` adds a `vm/VM.$exception` slot to
       the *same* `staticRefs` list, which the metal writer records separately — so `staticField` and
       `exceptionSlot` sites re-merge by emission order (that one missing slot was the whole statics
       region's 8-byte drift). The entire `0x80000`-relative image map — code and data — is now
       reconstructed on metal.
     - ✅ **code-region content byte-identical (`$`).** `VM.fixpointCode` compiles all 485 boot-closure
       methods at their image bases and resolves every relocation kind to image addresses over the
       reproduced layout — calls/helpers to method offsets, `tib`/`type`/`interfaceType` to the Types/
       TIBs regions, `static`/`exceptionSlot` to statics slots, `string` to the interned-byte[] region,
       `codePc` to the self-PC — then word-compares each against the running image. All 485 match
       (`initClasses` is regenerated with image-address `BL`s and compared too). Exposed one more
       signed-byte codegen bug: `iinc`'s delta `(byte) code[pos+2]` relied on the JVM's sign-extending
       `baload` (this compiler's zero-extends), so a negative increment (`shift -= 4`) compiled as
       `ADD #252` instead of `SUB #4` when the metal-resident compiler recompiled `printHex`; masked +
       cast to force an explicit `sxtb`, matching the `bipush` fix. The metal writer now reproduces the
       exact machine code it is executing, across the whole code region.
     - ✅ **whole-image fixpoint reached (`FIX`).** `VM.firstDataMismatch` materialises every immutable
       data region at its reproduced address and word-compares it to the running image: Type records
       `{instanceSize, superType, itableDir}`, TIB vtables, itable directories + itables (interface
       method → impl address, via the flattened vtable), interned string `byte[]` objects, unwind frame
       `{start,end,frameSize}` and handler `{start,end,handler,catchType}` entries, the embedded blobs,
       and the class-table directory. All byte-identical. Combined with the code content (`$`) and layout
       (`Z`/`H`), **joe-ng reconstructs on bare metal the exact image it is running** — the self-build
       fixpoint. The one region excluded is the **statics data segment**: it is the program's mutable
       memory (the running image has written `Config.mark`, incremented counters, updated `freeHead`,
       …), so its live bytes are not comparable against a static image; its layout and immutable
       writer-stashed values (`staticsStart`/`frameTable`/`classDir`/helper addresses/…) are validated
       by `H`. This is the expected boundary for any self-hosting system — you cannot byte-compare a
       running program's data segment against its on-disk form.

  **Assessment.** Large but well-understood — the novel/hard part (a metal class-model +
  compiler) already exists in Loader; M5.5c is layout + unification + blob plumbing over
  it, verifiable in-image by the fixpoint compare. No new subsystems (storage is M5.5d).
- **M5.5d — persist + reboot**: an SD/FAT block driver to write the image and a real
  self-hosted boot. This is where "drop the seed JVM" becomes literally true.
  - ✅ **slice 1 — materialise `image'` (`IMG`).** `VM.materializeImage` builds the clean
    reproduced image into a heap buffer: the immutable regions (code + Types/TIBs/itables/
    strings/unwind/blobs/class table), proven byte-identical by `FIX`, are copied from the
    running image; the mutable statics segment is reset to its *as-written* values (zero
    except the writer-stashed addresses/counts). `fixpointMaterialize` verifies it — the
    immutable regions match the live image and the statics are clean (`Config.mark` is 0 in
    `image'` but `0x37` in the live copy where its `<clinit>` ran). This buffer is exactly the
    `kernel8.img` the seed would emit, ready to write to storage.
  - ✅ **slice 2 — EMMC/SD single-sector read (`SD`).** `board.bcm2711.Emmc` brings up the SDHCI
    controller and card (software reset, ~400 kHz identification clock, bus power, then the
    CMD0/CMD8/ACMD41/CMD2/CMD3/CMD7/CMD16 handshake) and reads a 512-byte block by polled PIO
    (`CMD17`, read the DATA FIFO). It **auto-detects the controller base** — the Pi 4 wires the SD
    slot to EMMC2 (`0xFE340000`), but QEMU's `raspi4b` puts the card on the legacy EMMC
    (`0xFE300000`); it picks whichever reports a card present (STATUS bit16). Verified by reading
    block 0 and checking the boot-sector signature `0xAA55` at byte 510 (present on the test SD and
    any real MBR/FAT card). Test: `qemu-system-aarch64 -M raspi4b -kernel kernel8.img -sd <img>`.
    (Two QEMU-vs-hardware gotchas found: the card sits on the *legacy* EMMC under QEMU, and a generic
    SDHCI gates command response on `CONTROL0` bus power, which the Pi firmware normally owns.)
  - ✅ **slice 2b — EMMC single-sector write (`WR`).** `Emmc.writeBlock` issues `CMD24` and pushes
    128 words into the DATA FIFO, waiting on `WRITE_RDY` then `DATA_DONE`. Verified by a round-trip —
    write a pattern to a scratch block, read it back byte-identical — and by inspecting the SD image
    on disk: block 4096 holds the written `0x5EED1234+i` sequence. The driver can now both read and
    write the medium it will persist the image to.
  - ⬜ **slice 3 — FAT32 write.** Mount the boot partition, find `kernel8.img`, overwrite its
    clusters with `image'`.
  - ⬜ **slice 4 — reboot.** Watchdog/PSCI reset so the firmware reloads the metal-written
    `kernel8.img`; on the next boot it reproduces itself again → true self-hosting.

**Honest assessment.** M5.5a–c is a large but bounded port — mechanically similar to the
`Baseline` split (collections→registries, `ClassFile`→`ClassReader`, strings→Utf8), just
over 575 lines of layout logic instead of lowering. M5.5d is a genuinely new subsystem
(storage) and belongs with M6 widening. The compiler closure (M5.4) was the hard,
novel part; M5.5a–c is more of the same well-understood surgery.

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
  - `Magic.icIALLU()` (`IC IALLU`) + `Magic.dcCVAU(addr)` (`DC CVAU`) implemented (M5.5c);
    `Heap.publishCode(start,end)` uses them for JIT publish — correct hygiene if the caches are
    on, kept as such. (This was *first* suspected as the cause of the real-Pi hang after `R`, but
    it was not — see next.)
  - **Actual real-Pi hang after `R` — uninitialized heap memory (fixed M5.5c).** `Heap.alloc`
    returned memory without zeroing it, so freshly-`new`'d objects/arrays held whatever was in
    RAM. QEMU boots with zeroed RAM (so assumed-zero fields read 0 and it worked); the real Pi's
    RAM is garbage, so an uninitialized-but-assumed-zero field — the JIT compiler's
    `Baseline.fixupCount` — started as junk and the branch-fixup loop spun forever while
    compiling `Guest.answer()`. Fix: `Heap.alloc` now zeroes each allocation's payload (past the
    `{TIB,status}` header), honoring Java's default-init. **Confirmed in QEMU** by poisoning the
    heap with garbage before use: reproduces the hang without the fix, runs clean with it.
    Localized on hardware via UART phase-markers (`loadAll`→class→`compileClass`→method→
    fixup-loop); those markers were then removed. **Confirmed on a real Pi 4:** the board now
    prints the full sequence through `S` — the M4 loader (`*M`) and every M5.5c marker
    (`C K V B ~L S`, incl. the metal-writer-built `~` executing) run on real silicon, not just
    QEMU. (`CurrentEL` read `0x1` on both QEMU and the Pi — unexplained, but moot now that
    nothing faults; revisit if EL2 vectoring is ever needed.)
- `writeSCTLR_EL1(enable)` → set `M` (MMU), `C`/`I` (caches) bits, then `isb()`

**E. Exceptions + interrupts (EL1)**
- `writeVBAR_EL1(&vectors)` → your Java-emitted 2 KB-aligned vector table
- `daifClr(mask)` / `daifSet(mask)` → unmask/mask IRQ/FIQ/SError
- in handlers: `readESR_EL1()`, `readFAR_EL1()`, `readELR_EL1()`, `readSPSR_EL1()`
  - **Implemented as a fault diagnostic (M5.5c):** `Magic.writeVBAR_EL1` + `readESR_EL1`/
    `readELR_EL1`/`readFAR_EL1`/`readCurrentEL` (the last was dead — declared, never lowered).
    `VM.installFaultVectors` builds a 2 KiB-aligned Heap vector table (16 entries → `B reportFault`),
    publishes it, `MSR VBAR_EL1`, `isb`; `VM.reportFault` prints `el/esr/elr/far` then parks. Turns a
    silent boot fault into a printed report. **Handler proven** by branching to a vector entry
    directly (prints the report). **Open:** in QEMU raspi4b an injected `SVC` did *not* route to
    `VBAR_EL1` and `CurrentEL` read `0x1` (spec-impossible) — a QEMU/EL quirk (we may be running at
    EL2, where EL1 vectors don't fire); the `el=` field in the report resolves this on real hardware.

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
