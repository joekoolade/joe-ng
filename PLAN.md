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
  - ✅ **slice 3 — FAT32 write (`FAT`).** `board.bcm2711.Fat32` mounts the boot partition (MBR
    partition table → FAT32 BPB), scans the root directory for `KERNEL8.IMG` (8.3 name), follows its
    cluster chain via the FAT, and overwrites those clusters with a buffer — the file-level write the
    self-build uses to persist `image'`. Verified by a round-trip (write a pattern to the whole chain,
    read it back byte-identical → marker `FAT`) and on disk (`kernel8.img`'s cluster 5 holds the
    written `0xFA700000+i` sequence). Just enough FAT to rewrite one existing file in place: no
    allocation, no directory growth, no long-name handling. (Growing the closure with the SD/FAT
    drivers also forced excluding the *blob/class-file byte content* from the `FIX` compare — those are
    verbatim embedded input the writer echoes, not compiler output, so like the mutable statics they
    are outside the reproduction claim; the class-table *directory* it computes is still compared.)
  - ✅ **slice 4 — the self-hosting loop (`PST` + reboot).** `persistImage` chains it all: reproduce
    `image'`, write it over the SD card's `KERNEL8.IMG` cluster chain, verify the readback, then
    `board.bcm2711.Reset.reboot()` arms the BCM2711 PM watchdog for a full reset — on real hardware the
    firmware reloads the image joe-ng just wrote and boots it, reproducing itself again. Verified end
    to end: the marker sequence runs `… FIX IMG SD WR FAT PST`, and the `kernel8.img` extracted from
    the SD image afterward (via its FAT chain) is **byte-identical to the original** — joe-ng persisted
    a byte-perfect image of itself. (QEMU's `raspi4b` doesn't emulate the PM watchdog and `-kernel`
    wouldn't reload from the card, so the actual reboot-and-reload is a real-hardware step; the reset
    code is in place and the write it depends on is proven.) Fixed a real memory-map bug the growth
    exposed: the mailbox buffer sat at `0xE0000`, which the enlarged image now occupied, so the boot
    mailbox call corrupted the class-table bytes there; moved the mailbox (8 MiB), `PTR_CELL`, and heap
    (9 MiB) up to give the image room. **M5.5d complete — "drop the seed JVM" is now literal.**
  - ✅ **generation-counter demo.** A scratch SD sector (sector 1, in the MBR gap before the
    partition) holds a `gen!`-tagged counter that survives reboots. Each boot reads + prints its
    `generation N`, reproduces + persists itself, bumps the counter to `N+1`, and reboots — so on real
    hardware the number climbs automatically, visible proof the metal-written image is what booted.
    Demoed in QEMU by re-running on the same SD (each run = one boot): `generation 0 → 1 → 2 → 3`, and
    the SD's `kernel8.img` stays byte-identical to the original after every self-write.

**Honest assessment.** M5.5a–c is a large but bounded port — mechanically similar to the
`Baseline` split (collections→registries, `ClassFile`→`ClassReader`, strings→Utf8), just
over 575 lines of layout logic instead of lowering. M5.5d is a genuinely new subsystem
(storage) and belongs with M6 widening. The compiler closure (M5.4) was the hard,
novel part; M5.5a–c is more of the same well-understood surgery.

### M6+ — Widening
GC (bump → real collector); GIC-400 interrupts + timer; SMP (wake cores 1–3);
exceptions; class-library subset; framebuffer via VideoCore mailbox.

- **M6 interrupts — infrastructure built, activation gated (QEMU delivery gap).** The whole EL1
  physical-timer + GIC-400 IRQ path exists and is fixpoint-compatible (the metal writer recompiles
  all of it byte-identically — `FIX` still passes): eight new `magic/Magic` sysreg intrinsics
  (`readCNTFRQ_EL0`/`readCNTPCT_EL0`/`writeCNTP_TVAL_EL0`/`writeCNTP_CTL_EL0`/`enableIrq`/`disableIrq`/
  `readDaif`, plus the `msrDaifClr/Set` and `CNTx_EL0`/`DAIF` encoders); `board.bcm2711.Gic` (GIC-400
  distributor + CPU interface init, enable, ack/EOI); a runtime-generated IRQ vector stub (save
  x0–x30 → `BL irqHandler` → restore → `ERET`) installed at the EL1h IRQ/FIQ vector entries;
  `VM.irqHandler` (ack, count a tick, re-arm) with its address writer-stashed like `reportFault`; and
  `setupTimerIrq` (arm a ~1 ms tick, unmask). **Blocked under QEMU raspi4b:** the timer asserts INTID
  30 at the distributor (`ISPENDR` bit30) but its CPU interface never signals the core (`AHPPIR`
  empty), so no IRQ is taken — verified group 0 and group 1, priority 0, `PMR=0xFF`, `CTLR` both ways,
  DAIF I+F unmasked, EL1 confirmed, vectors at entries 5+6. Activating the path also perturbs the
  cross-method-unwind test (a taken IRQ vs `Magic.resume`'s stack fixup). So `setupTimerIrq` is kept
  reachable (a dead call, for discovery/stashing) but not run — the timer *read* works
  (`timer 62MHz`).

  **Datasheet resolution (BCM2711 peripherals ch.6, `RP-008248-DS`).** Figure 7 + §6.5.1 settle
  both real-HW failures:
  - The GIC-400 is the *default* controller; when selected, each core's PNS timer IRQ wires
    **straight to the GIC as PPI INTID 30** — the ARM-local `TIMER_CNTRL`/`IRQ_SOURCE` router is
    *bypassed*. That is why the ARM-local attempt read `src=0`: in this config the router never sees
    the timer. The GIC path and the ARM-local path are mutually exclusive, chosen by `enable_gic`.
  - GIC base (Low-Peripheral) = `0xFF840000` → GICD `0xFF841000`, GICC `0xFF842000` (all confirmed).
  - The earlier GIC `igrp=0` was the group bit not sticking: for non-secure EL1 to own PPI 30 the
    firmware's **secure armstub must set up the GIC**, which only happens with `enable_gic=1` in
    `config.txt` (which the test board lacked). Without it the group-1 write is RAZ/WI.
  - Fixed a real driver bug: `Gic` acknowledged via the *secure-aliased* `AIAR`/`AEOIR` (0x20/0x24);
    non-secure must use plain `GICC_IAR`/`GICC_EOIR` (0x0C/0x10). Now corrected.

  VM switched to the GIC path (`Gic.init(30)`; `irqHandler` acks `GICC_IAR`, ticks on INTID 30,
  `GICC_EOIR`). Debug dump now prints `ppi30[grp/en/pend]` + `lastid`. Under QEMU: `grp=1 en=1
  pend=1 cntp_ctl=0x5` (timer fires, reaches the distributor, is group-1+enabled) but the CPU
  interface still doesn't forward (`lastid=0xFFFF`) — a QEMU GICv2 modeling gap, not our setup.
  Next: real Pi 4 with `enable_gic=1` — `grp=1` will confirm the armstub ran and delivery should tick.

### Milestone-B — demand-loaded class-library subset on metal (collections + strings + streams + lambdas)

A long, incremental arc (all commits tagged `Milestone-B:`) building an **idiomatic, JDK-free class
library** that the on-metal `Loader` demand-loads and JIT-compiles from embedded `.class` blobs, verified
QEMU→real-Pi-4 each slice, and reproduced byte-for-byte by the self-build (`FIX`/`IMG` held throughout).
Everything is `--patch-module java.base=guestsrc` so classes carry their real names. Capstone:
`demo/MapDemo.wordCount()` — a real word-count (String.split → HashMap tally via `merge` → Stream `reduce`).

**What runs (mini, real-shaped):**
- **`java/lang/String`** (LATIN1, real `byte[] value`+`byte coder`): length/charAt/equals/hashCode/isEmpty/
  indexOf/substring/startsWith/compareTo/trim/replace/split/join/toUpperCase/toLowerCase, `implements
  Comparable<String>`.
- **Collections** behind a real interface chain `List extends Collection extends Iterable`, `Map`: two
  polymorphic `List` impls (`ArrayList`, singly-linked `LinkedList`) with add/get/set/size/isEmpty/indexOf/
  contains/remove(int|Object)/iterator; `HashMap implements Map` (open-addressing) with put/get/containsKey/
  remove(**backward-shift** deletion)/getOrDefault/computeIfAbsent/merge/forEach/keySet/values; `Iterator`
  + enhanced-for; `Collections.sort(List[, Comparator])`; `Comparable` bridge + `Comparator`.
- **Stream** (mini eager `demo/Stream`): filter/map intermediate, forEach/reduce/toList terminals, sourced
  from a List *or* a Map's keySet()/values(); `java/util/function/{Predicate,Function,Consumer,
  BinaryOperator,BiConsumer}`.
- **invokedynamic — full matrix:** string-concat; lambdas {0,1,2}-arg × {no-capture, capture}; all four
  method-reference kinds (static, unbound-instance, bound-instance, constructor); mutable-capture via a
  captured `int[]`.

**Loader/compiler extensions this arc required (the non-trivial ones):**
- **Reachable-only compilation** + **cross-class relocation pass** (bl / movz+movk fixups) — so a demo's
  closure compiles only what `main` reaches, and cross-class calls/statics resolve after cycle force-loads.
- **Transitive itable directories** — `buildItableDir` closes over super-interfaces (`clIfaceReg`/
  `ifaceClosure`), so a call site typed to a super-interface (`Iterable.iterator` on an ArrayList) resolves.
- **Lambda/method-ref thunks** in `buildLambdaTib`: static tail-call (kind 6, general in captures+SAM
  argc); **vtable-dispatch** for instance refs (kind 5/9, unbound shifts args / bound loads captured
  receiver); **alloc+`<init>`+return frame** for constructor refs (kind 8, the one thunk that makes calls).
- **Bridge methods** (`String.compareTo(Object)`→typed) resolve through the vtable + name/desc imap with
  no special-casing. Sign-extended-int fixes (`l2i`/`iushr`/int-compare), concat spill, `isNonLeaf`,
  implicit NPE/AIOOBE, `if_acmp`/`ifnull`, Object-root vtable inheritance (equals/hashCode) all landed here.

**Recurring gotchas:** an interface referenced only via `implements`/
`extends` must still be an embedded blob (else itableDir stores `interfaceType=0` == sentinel → wild
`blr`); any `.equals()`/`.hashCode()` on an `Object` ref needs `java/lang/Object` seeded; a bound method
ref on a possibly-null receiver makes javac emit `Objects.requireNonNull` → drags real `java.util.Objects`
into a **compile-all** closure and fails on an unsupported `ldc` deep in java.base (fixed by a
provably-non-null receiver `new X()::m`); avoid 2-class cycles where each calls the other's methods.

**Open TODOs:** `loadList`/`loadMap` are compile-ALL, so they can't pull real java.base (Integer/Arrays/…)
— to go reachable-only, `collectRefs` must follow invokedynamic to mark lambda bodies + SAMs (today it
follows only 0xB6-B9).

---

### WiFi arc — the CYW43455 as an internet device (all-Java SDIO driver + TCP/IP + WPA2 crypto)

Bring the Pi 4's on-board WiFi (Cypress CYW43455, SDIO) online and use it as an internet device — driver,
TCP/IP stack, and WPA2 crypto all in Java over `magic.Magic` MMIO, **no C**, consistent with the project's
hard constraints. **Real-hardware-only from the first SDIO command** (QEMU `raspi4b` does not emulate the
chip; its `0xFE300000` block is the SD card), so UART logs are the only scope and every step is heavily
instrumented. `make test` + a QEMU boot still gate that the rest of the image is intact — the WiFi path is
gated on `Uart.coreHz` and skipped under QEMU. **Verified end-to-end on a real Pi 4.**

- **M0 — enablers.** `board/bcm2711/Gpio` (setAlt/setPull read-modify-write; GPIO34–39 ALT3), a generic
  `Mailbox.tag()` (+ GPIO-expander WL_ON, the *measured* EMMC clock), `Magic.dcIVAC`, `VM.delayUs/delayMs`
  (CNTPCT-based, for pre-scheduler driver code), and a **standalone** `board/bcm2711/Sdio` SDHCI driver
  (CMD52/53 IO_RW_DIRECT/EXTENDED, R4/R5, 4-bit @ ~25–50 MHz, PIO not DMA, real clock divider from the
  mailbox) — kept separate from the boot-critical `Emmc` (~100 duplicated lines is cheap insurance).
- **M1 — chip alive.** CMD5 enumerate; upload `brcmfmac43455-sdio.bin` + NVRAM `.txt` + `.clm_blob` from
  RAMFS via CMD53 into chip SRAM; release the internal ARM from reset; poll ALP/HT clock (CHIPCLKCSR) +
  F2-ready; **SDPCM** framing (hw `[len:16][~len:16]` + sw seq/channel/credit-based flow control) and the
  **BCDC** control layer (ioctl/iovar with request-id matching). Prints chip id `0x4345` rev 6,
  "FIRMWARE UP (F2 ready)", and the `ver` iovar (`wl0 … 7.45.265`).
- **M2 — join an open network.** `clmload` the CLM blob, set country + `WLC_UP`, a *targeted* event mask
  (not all-`0xFF`, or the RX loop floods); **escan** → parse `E_ESCAN_RESULT` events into an SSID list;
  open join (`WLC_SET_INFRA=1`, `wsec=0`, `wpa_auth=0`, `WLC_SET_SSID`) → wait `E_LINK` up. SSID from
  `ramfs/etc/wifi.conf`.
- **M3/M4 — data path + TCP/IP (built from scratch).** SDPCM channel-2 data + a 4-byte BDC header; then
  ARP, IPv4, ICMP (ping the gateway), UDP+DHCP (lease → ip/gw/subnet/DNS), DNS, and TCP (pseudo-header
  checksum, one in-flight segment) → **HTTP/1.0 GET** → prints `HTTP/1.1 200 OK` + the body (829 bytes from
  example.com). The "internet device" **acceptance test — passed on real hardware.**
- **M5 — WPA2-PSK: WORKS on real hardware (host supplicant, DONE).** A full **JDK-free WPA2 supplicant** runs
  the 4-way handshake in Java: SHA-1, HMAC-SHA1, PBKDF2 (PMK), PRF (PTK), AES-128 + RFC-3394 key-unwrap (GTK)
  in `crypto/*` (17 vectors in `test/crypto/CryptoTest`), plus msg1..msg4 + `WLC_SET_KEY`. Config = `wsec=4`
  (CCMP), `wpa_auth=0x80` (WPA2-PSK), `auth=0`, associate unkeyed, run the host 4-way, install PTK/GTK →
  CCMP flows → DHCP → **HTTP 200 OK over WPA2**. The earlier "banked — firmware won't relay host EAPOL"
  conclusion was **wrong**; five stacked bugs hid it, each found by pairing UART traces with monitor
  captures of the 4-way:
  1. EAPOL sent at BDC **priority 7** (AC_VO) was silently dropped on the unauthorized controlled port —
     brcmfmac classifies EAPOL to **priority 0** (AC_BE); so do we now (msg2 finally leaves the chip).
  2. `ourMac` was read at DHCP time, **after** `fourWay`, so the PTK/MIC (and msg2's Ethernet source) were
     computed from a **zero** station MAC. Read it before the handshake.
  3. The **authenticator address** must be msg1's Ethernet source (= the real BSSID, verified on air), not a
     mis-parsed `WLC_GET_BSSID` (which returned the router's LAN MAC on this split-MAC ATT gateway).
  4. PBKDF2(4096) ran **inside** `fourWay` after association, and `get_bssid`/`get_channel` ioctls sat
     between msg1 and msg2 → **~14 s** latency. The AP restarts the 4-way with a fresh ANonce ~once a second
     and silently drops stale replies. **Precompute the PMK pre-association** and keep the msg1→msg2 path
     bare → **~6 ms**; the MIC was then verified bit-exact against an independent Python reference.
  5. msg2's key-data **RSN IE capabilities** were `0x0000`, but the firmware's **association** RSN IE is
     `0x000c` (PTKSA replay-counter field) — the AP compares the two and silently drops any mismatch (a
     downgrade-protection check). Match it (confirmed from a beacon + association capture).
  This firmware has **no in-chip supplicant** (`sup_wpa` → -23, `WLC_SET_WSEC_PMK` → -2), so the
  `WPA2_OFFLOAD` path (`sup_wpa`=1 + PMK push, brcmfmac's PSK offload) is kept as a gated alternative but
  disabled. The debugging that cracked this is a case study in "the UART can't see the air" — every capture
  falsified one hypothesis (msg2 *is* transmitted → MIC *is* correct → it *is* timely and ACKed → RSN-IE
  mismatch), converging on the exact cause.
- **M6 — IRQ-driven RX (DONE, on main).** F2 receive moved off busy-polling onto the SDIO card interrupt
  (GIC SPI 158 = VC IRQ 62). The card interrupt is a **level** line, so it is gated at the **GIC**
  (`GICD_ICENABLER`/`ISENABLER`), *not* the SDHCI — masking at the SDHCI (Signal- or Status-Enable) never
  de-asserts it and stormed core 0. The ISR (`Cyw43.onIrq`, dispatched from `VM.schedule`) disables the SPI
  and posts `WIFI_SEM`; the WiFi task blocks in **`VM.semWaitTimeout`** — block on a semaphore *or* a CNTPCT
  deadline (so a lost frame times out instead of hanging; `pickNext` wakes a BLOCKED task whose `taskWake`
  deadline passed) — and on wake reads the frame, clears the SDIOD + SDHCI status, and re-arms the SPI. Every
  RX loop (first frame, ioctl, scan, join, DHCP/ARP/ICMP/DNS/TCP, EAPOL) now blocks in `waitFrameIrq`,
  keeping each loop's existing time-based deadline. The chip only asserts once the CYW43 **SDIOD-core
  Intmask** (backplane `0x18004000 + 0x24` = FrameInt|MailboxInt|Fcchange = `0xE0`) is set. WiFi runs as the
  **boot finale** on a re-armed minimal scheduler (`installSchedVectors`; `taskCount=1` — task 0 only, no
  demo tasks; re-arm the timer; `enableIrq`), so the UART trace is clean and the WiFi task sleeps off-CPU
  between frames. **Verified on a real Pi 4:** full feature showcase → join `joe-ng-open` → HTTP 200 OK, no
  storm, no demo-task noise.
  - **mini-UART baud is self-calibrating — never hardcode a divisor.** The VPU core clock is not predictable
    (it differed across firmware builds and SD cards); `Mailbox` asks `GET_CLOCK_RATE_MEASURED` (0x00030047,
    *not* `GET_CLOCK_RATE`, which echoes the requested rate) and `Uart.baudDivisor()` computes it at boot.
    Serial must be CRLF. See the `wifi-driver-arc` memory for the full hard-won detail.

Files: `board/cyw43/Cyw43` (driver + full stack + supplicant), `board/bcm2711/{Sdio,Gpio,Gic,Mailbox}`,
`crypto/*`, firmware blobs in `ramfs/lib/firmware/brcm/`, credentials in gitignored `ramfs/etc/wifi.conf`.

---

### Reflection arc — `Class`, `ClassLoader`, and access-checked reflection (load a class from a file and run it)

Goal: expose joe-ng's existing metacircular loader through the **standard `java.lang.Class` / `ClassLoader` /
`java.lang.reflect.*` APIs**, add reflective method/constructor invocation, and **enforce Java access control
on reflection** — the headline user goal being *load a `.class` from a file and run it*. Access enforcement is
not an add-on: joe-ng's protection is **language type-safety + verification, not hardware rings** (§3), so
reflection — the main way to *bypass* language access control — MUST be access-checked, or private state and
methods become freely reachable and the language-level security boundary collapses. Test-driven acceptance is
the JDK suites in `test/jdk/java/lang/Class` and `test/jdk/java/lang/ClassLoader` (triaged: ~22 GREEN, ~29
YELLOW, ~38 RED — RED = modules / SecurityManager / jars / native libs / annotation+generic-signature
reflection / resource loading, all out of scope).

**The substrate already exists** (do not rebuild it): `Loader.launch`/`pullClass`/`entryPoint`/`markReachable`
(RTA)/`loadAll` (two phases: `loadStructure` → `loadBodies`/`compileClass`)/`globalMethodBuf` + `Magic.call*`
already load a class by NAME from the embedded classDir (`VM.findClass` binary search), JIT-compile its
closure, and run `main`. One `java.lang.Class` mirror is materialised+cached per VM Type (`Loader.classMirror`,
`X.class == obj.getClass()`). **Field** reflection exists: the instance-field registry keeps access_flags +
type descriptor (`fldAccess`/`fldDescOff`), surfaced via `VM.fieldMods`/`fieldTypeChar` and
`Class.getDeclaredField`. Caller-class resolution exists: `Magic.readLR()` → `VM.classAtPc` →
`Loader.classMirrorAtPc`. `FieldUpdaterCheck` already does real caller-based **private-field** access
enforcement — reflection generalises exactly that. RAMFS files are readable today (`FileInputStream` →
`VM.fileFind`, read-only). Gaps: (a) no way to load a class from *runtime-provided bytes* (all pull paths go
by name through the classDir); (b) `java/lang/ClassLoader` + `java/lang/reflect/*` + `jdk/internal/reflect/`
are **denylisted** (need overlays + narrow-ALLOW, exactly like the VarHandle shim); (c) **zero** Method /
Constructor reflection — only Field.

Two halves of "access-check enforcement", both required:
1. **Visibility filtering at lookup** — `getField`/`getFields`/`getMethods`/`getConstructors`/`getClasses`
   return **public-only** (incl. inherited); `getDeclared*` return **all** members (no filtering). Validated
   by these two dirs (`getField/Exceptions`, `getFields/Sanity`, `getMethod/Exceptions`, `getClasses/Sanity`).
2. **Invoke-time access checks** — a non-public `Method`/`Field`/`Constructor` used without `setAccessible`
   from an unrelated caller throws `IllegalAccessException`; `setAccessible(true)` (when permitted) skips the
   check. The *reflective-invoke* enforcement tests live under `java/lang/reflect/` (NOT these two dirs), so
   pull a couple from there (`Field`/`Method` setAccessible+invoke) as M2's security gate; these dirs cover
   the `setAccessible`+field-hiding half via `getDeclaredField/ClassDeclaredFieldsTest`.

**M1 — `Class.forName` + the Class query surface + field-lookup visibility filtering.**
- *First task, gating everything: prove incremental load* — load a class AFTER `launch` without wiping the
  running program (the persistent global registries `rg*`/`cl*`/`globalBuf` must survive `resetLoader`; the new
  class's cross-refs resolve against them). If this holds the arc is unblocked.
- `Class.forName(String[, boolean, ClassLoader])` → `VM.forName` (dots→slashes, `pullClass`/incremental
  `loadAll`, return mirror; `ClassNotFoundException`). `Class.getModifiers`/`getSimpleName`/`getCanonicalName`/
  `getPackageName`/`isArray`/`isPrimitive`/`isInterface`/`isSynthetic`/`getComponentType`/`arrayType`,
  `Class.forPrimitiveName`. `getField`/`getFields`/`getDeclaredFields` over the field registry with
  **public-only filtering** for the non-`Declared` forms (array `length` pseudo-field handled).
- **Acceptance:** `getModifiers/{ResolveFrom,ForInnerClass,ForStaticInnerClass,StripACC_SUPER}`,
  `forName/{InvalidNameWithSlash,InitArg,ForNameNames→runner}`, `ForPrimitiveName`, `NameTest`,
  `attributes/ClassAttributesTest`, `IsSynthetic`, `getPackageName/Basic→runner`, and the visibility-filtering
  field lookups `getField/{Exceptions,ArrayLength}`, `getFields/Sanity→runner`, `getDeclaredField/Exceptions`.
- Touchpoints: `guestsrc/java/lang/Class.java`, new `VM.forName`/`VM.classFields*` natives + 4-touchpoint
  wiring, `Loader` field/registry accessors, `ClassNotFoundException`/`NoSuchFieldException` overlays.
- **STATUS — core DONE (verified on QEMU, `demo/ForNameDemo`).** Delivered:
  - **Incremental load (gating) DONE.** The registries are append-only, so `pullClass`+`entryPoint`+`loadAll`
    re-invoked WITHOUT `resetLoader` extends the live program (prior blobs skipped via `pdDone`/`pdDoneB`; the
    running code + heap survive). Two enablers: (a) `runClinits` gained a **`clinitRunFrom` watermark** so a
    2nd `loadAll` runs only the newly-queued `<clinit>`s (else it double-inits the whole program); (b)
    `markReachable` gained **`gEntrySeedAll`** + `seedAllMethodsOf` so a forName'd class compiles ALL its
    methods (reflection may invoke any), not just the RTA closure of a `main`.
  - **`Class.forName(String[,boolean,ClassLoader])` DONE.** Guest overlay → `VM.forName`(byte[]) →
    `Loader.forNameMirror`: rejects `/` (→ CNFE), `.`→`/`, returns the cached mirror if loaded else
    `loadClassIncremental`. `ClassNotFoundException`/`ReflectiveOperationException` overlays added. (Limitation:
    always initializes — the `initialize=false` deferred-init of `forName/InitArg` is NOT yet honoured; needs
    load-without-clinit + M3 `ClassLoader.getClassLoader()`.)
  - **Query surface DONE:** `getModifiers` (new `VM.classModifiers` native; strips `ACC_SUPER`; reads a nested
    class's `InnerClasses` attribute — so `Inner`→private, `Protected`→protected), `isInterface`, `isSynthetic`,
    `getSimpleName`/`getPackageName`/`getCanonicalName` (pure-Java on `getName()`), + a `java/lang/reflect/
    Modifier` overlay. `isArray`/`isPrimitive` return false (array/primitive mirrors not yet modelled).
  - **Field-lookup filtering DONE:** `getField` = public-only (`ACC_PUBLIC` + `NoSuchFieldException`),
    `getDeclaredField` = any-access (already existed). `NoSuchFieldException` overlay added.
  - **General fix (not reflection-specific):** `collectRefs` now pulls a class used ONLY as a `ldc`
    class-literal (`X.class`) — previously such a class was never demand-loaded, so its Type/mirror was null.
  - **Narrow-ALLOW** added for `java/lang/reflect/Modifier` + `java/lang/reflect/Field` (both `Loader
    .isDenylisted` and `writer/ReachScan`), past the `java/lang/reflect/` deny (VarHandle-shim precedent).
  - **Deferred (need later work):** inherited-public-field `getField` superclass walk; `getFields`/
    `getDeclaredFields` returning a `Field[]` (needs a field-enumeration native — folds into M2); array-class +
    primitive mirrors (blocks `getField/ArrayLength`, `ForPrimitiveName`, `NameTest` array/primitive cases);
    deferred-init (`forName/InitArg`); wiring the exact JDK test files as manifest mains (need HashMap/Map.of/
    TestNG runners — the demo exercises the same API).
  - **✅ "INCREMENTAL-LOAD" BUG FIXED — it was the exception unwind, not heap corruption.** For a long time this
    looked like a layout-sensitive heap corruption in the 2nd `loadAll` (spurious NPE/AIOOBE at a later
    `getField`/throw). It was actually the `RESUME` intrinsic restoring only the exception HANDLER's own
    callee-saved locals (`nloc`) on a cross-method unwind, leaving the caller's registers (clobbered by the popped
    frames) un-restored → a caller local came back a leaked code address. The incremental load only mattered
    because it produced a deep-enough cross-method unwind. **Fix: `RESUME` restores all `x19..x28` from the
    reconstructed `unwindLocBuf`** (M2 session). `ForNameDemo` now runs in the natural fields-LAST order. Along the
    way, `seedAllMethodsOf` was removed (forName seeds only `<clinit>`; other methods compile lazily) and
    `getModifiers` moved to a load-time cache (`clModifiers`) — both kept. Separate still-open cosmetic issue: the
    garbage stack-trace backtraces (wrong athrow-site `sp` passed to `captureTrace`).

**M2 — `Method` / `Constructor` / `Field` invoke + ACCESS ENFORCEMENT (the core milestone).**
- `Method`/`Constructor` objects from the method registry (`rg*` holds class/name/desc/**compiled buffer**/
  static-flag). **`Method.invoke(recv, Object[])`**: marshal+unbox args per descriptor into the `Magic.callN`
  register convention (slot k → x(1+k); category-2 widths), virtual-dispatch via the receiver TIB vtable slot
  (reuse `invokevirtual` lowering) or static → buffer, then **box** the return. **`Constructor.newInstance`** =
  `Heap.alloc(instanceSize)` + store TIB + invoke `<init>` (what MapCheck/ConcurrentRemoveIf needed).
  **`Field.get/set/getInt/…`** via `VM.vhFieldOffset` + `Magic.load*/store*`; reference `get` needs a small new
  `Magic` **long→Object reinterpret** intrinsic (the one gap `AtomicReferenceFieldUpdater.get` hit — full
  8-touchpoint Magic-intrinsic wiring).
- **Access enforcement:** `AccessibleObject` base with `setAccessible(boolean)`+`override` flag; a
  `Reflection.verifyMemberAccess` equivalent generalising `FieldUpdaterCheck`'s private-only check to all four
  levels (public→always; private→same top-level/nestmates; protected→same package **or** subclass;
  package-private→same package), package derived from the binary name, caller via `classAtPc`. `getMethod`/
  `getMethods`/`getConstructors`/`getClasses` **public-only filtering**; `Class.asSubclass`/`cast`.
- **Acceptance:** `getDeclaredMethod/Exceptions`, `getMethod/Exceptions` (public-only), `ArrayMethods`,
  `NullBehaviorTest→runner`, `getClasses/Sanity` (public-only member classes), `getDeclaredField/
  ClassDeclaredFieldsTest` (**setAccessible + `classLoader` field-hidden**), plus 1–2 pulled from
  `java/lang/reflect/` for the **`IllegalAccessException`-on-invoke security gate**.
- Touchpoints: new `reflect/{Method,Constructor,AccessibleObject}` overlays + `Field` get/set, `VM.invoke*`/
  `VM.newInstance0` marshalling natives, one new `Magic` object-reinterpret intrinsic, primitive `TYPE`
  handling (`int.class` … for `getMethod(name, Class...)`). (`String.valueOf(Object)` / `String + Object`
  concat is confirmed working — see STATUS below.)
- **STATUS — Field + Method + Constructor invoke, access enforcement, and on-demand compile ALL DONE
  (QEMU-verified).** Delivered:
  - **`Magic.fromAddr(long)→Object`** long→Object reinterpret intrinsic (inverse of `addrOf`, same no-op
    lowering; `Intrinsics.FROM_ADDR`, `Baseline`, `Loader.magicId`). **`Magic.callN(buf,argsPtr)`** general
    N-arg call (loads x0..x7 from an 8-long buffer, `blr`; callee ignores extra regs) — the invoke call path.
  - **`Field.get/set` + typed `getInt/setInt/getLong/getBoolean` DONE** (`demo/FieldReflectDemo`): offset via
    `VM.vhFieldOffset`, primitives boxed/unboxed per typeChar, reference `get` via `fromAddr`. `Field` now
    extends a new `AccessibleObject` (setAccessible flag stored, rules TBD). Overlays: `IllegalAccessException`.
  - **`Method` + `Class.getDeclaredMethod` + `Method.invoke` DONE** (`demo/MethodReflectDemo`): static (2 int
    args), instance (reads a field), reference-returning, and void methods all invoke correctly. Method registry
    gained **`rgAccess`** (per-method access_flags). Natives `Method.methodResolve0`/`methodInfo0` →
    `VM.methodResolve`/`methodInfo` (registry lookup by class+name, descriptor param-chars + return-char + buffer
    + access). Marshalling/call/boxing is guest-side in the `Method` overlay. Overlays added: `Method`,
    `Constructor`(narrow-allowed, TBD), `NoSuchMethodException`, `InvocationTargetException`. Narrow-ALLOW for
    `java/lang/reflect/{Method,Constructor,AccessibleObject}` in both denylist sites.
  - **Access enforcement DONE (fully works) — `demo/AccessDemo`.** `AccessibleObject.checkAccess` enforces all
    four levels (public / same-class / private / protected = same-package-or-subclass / package-private =
    same-package; package from the binary name; caller via `readLR`→`classAtPc`); `setAccessible(true)` bypasses.
    Wired into `Field.get/set` + `Method.invoke`. QEMU: public field/method → OK, a private member accessed
    cross-class → `IllegalAccessException`, then `setAccessible(true)` → OK, for BOTH fields and methods; runs to
    completion.
  - **✅ EXCEPTION-UNWIND BUG FIXED (the blocker for M1 incremental load + M2 + M4).** Root cause was the
    `RESUME` intrinsic restoring only the handler's own callee-saved locals (`nloc`) on a cross-method unwind,
    leaving the caller's registers (clobbered by the popped frames) un-restored → a caller local came back a
    leaked code address → spurious AIOOBE/NPE, layout-sensitively. Fix: `RESUME` now restores ALL `x19..x28`
    from the reconstructed `unwindLocBuf`. This was the SAME bug behind the M1 "incremental-load corruption"
    (ForNameDemo fields-LAST now runs clean) AND the M2 access-enforcement crash. Details in [[reflection-arc-m1]].
  - **ON-DEMAND COMPILATION DONE.** A method invoked ONLY reflectively is never RTA-reached, so it isn't
    compiled. `methodResolve` now falls back to `compileMethodOnDemand`: it re-establishes the (already
    structure-loaded) class's compile state — `parseConstPool`/`parseFields`/`parseVtable` + reuse
    `clStatics[reg]`/`clTib[reg]` — finds the method by name, compiles JUST it (`compileReuseTib=true`, so the
    class's already-filled TIB is untouched since invoke dispatches to the buffer directly), `registerAll` +
    `patchRelocs`. Verified: MethodReflectDemo + AccessDemo run with NO warm-up (add/scale/getMsg/noop and
    public/private Sum are invoked purely reflectively and compile on demand). Limitation: the method's
    cross-class callees must already be compiled (no dependency pull here); a same-class callee is compiled
    alongside it.
  - **`Constructor.newInstance` DONE** (`demo/CtorReflectDemo`): `Class.getDeclaredConstructor(Class...)` →
    `Constructor.resolve(this, paramTypes.length)` matches an `<init>` by **arity only** (no param-type
    matching yet, so `int.class` primitive mirrors aren't needed — the placeholder Class literals are
    ignored). `newInstance(Object...)` allocates a fresh instance via `allocInstance0`→`VM.allocInstance`
    (`Heap.alloc(16 + clFieldCount*8)` + store the class's `clTib` in the header — same shape as `emitNew`),
    marshals the new object into slot 0 as the `<init>` receiver then the args per the resolved descriptor's
    param chars, and `Magic.callN`s the `<init>` buffer (void return). The `<init>` compiles ON DEMAND
    (`constructorResolve` → `ctorResolveRegistry`, else the same re-establish-state + compile path as
    `compileMethodOnDemand`, using `descParamCountRaw` to arity-match the raw descriptor). Access enforcement
    reuses `AccessibleObject.checkAccess`. Natives (all in the `java/lang/reflect/Constructor` `nativeBuf`
    arm): `ctorResolve0`→`VM.constructorResolve(JJ)I`, `methodInfo0`→`VM.methodInfo` (shared with `Method`),
    `allocInstance0`→`VM.allocInstance(J)J`. QEMU: no-arg `<init>` (`size=1`), two-arg `<init>` (int+ref →
    `size=42`, ref identity preserved), param counts `0`/`2` — all run to completion. New overlay
    `reflect/Constructor` + `java/lang/InstantiationException`.
  - **`String.valueOf(Object)` / `String + Object` concat DONE** — was already fixed (turned out to be the
    SAME layout-sensitive corruption cured by the RESUME register-restore fix), just never re-verified.
    Confirmed on metal (QEMU): every `String.valueOf` overload resolves to the RIGHT one by descriptor
    (`valueOf(int/char/boolean/long/Object)` all correct), and `String + <ref>` concat works for a custom
    `toString`, `null`, a boxed value typed as `Object`, and the default `Object.toString`. javac pre-lowers
    `"x=" + obj` to `invokestatic String.valueOf(Object)` before the `StringConcatFactory` indy, so the indy
    only ever sees a real `String` arg (which `scStr` already handled). **Folded in:** the reflection demos
    (`FieldReflectDemo`/`MethodReflectDemo`/`CtorReflectDemo`) now print reference results/fields via natural
    `+ obj` concat (`ref=hi`/`getMsg=the-message`/`label=widget-label`) instead of the old identity/unbox
    workarounds — exercising `valueOf(Object)` directly inside the reflection closure.
  - **Remaining:** overload resolution by parameter types (needs primitive `int.class` mirrors); virtual
    override dispatch (currently direct-buffer); `getMethod`/`getMethods`/`getConstructors` public-only
    filtering.

**M3 — `ClassLoader` + `defineClass(byte[])` (route runtime bytes into the loader).**
- Narrow-ALLOW `java/lang/ClassLoader` (top of `Loader.isDenylisted` + `ReachScan.isDenied`, like VarHandle);
  overlay minimal `ClassLoader` with `loadClass`(→ M1 `forName`) + **`defineClass(name, byte[], off, len)`**:
  copy the guest `byte[]` to a heap blob (`toBytes`), `addBlob` + `loadStructure`/`loadBodies` for the one
  class + demand-pull of not-yet-loaded deps, return the mirror. Single application loader, **no delegation
  hierarchy / unloading**.
- **Acceptance:** `defineClass/DefineClassByteBuffer` (bytes, no jars), `ExceptionHidingLoader` (custom
  `ClassLoader.findClass`), `findSystemClass/Loader`.
- Touchpoints: `guestsrc/java/lang/ClassLoader.java`, `VM.defineClass0` native, `Loader.defineFromBytes`,
  denylist narrow-ALLOW in both sites.
- **STATUS — DONE (QEMU-verified).** `ClassLoader.defineClass(byte[])` materializes a class from SUPPLIED
  classfile bytes on the metal and runs it. `demo/DefineClassDemo` embeds the raw 293-byte `plugin/Plugin
  .class` (compiled offline, **not in the loader's classDir** — so `forName` can't reach it; the byte[] is the
  only source), hands it to an `AppLoader extends ClassLoader`'s `defineClass`, then drives it purely through
  M2 reflection: `getName()` → `plugin.Plugin`, `getDeclaredConstructor().newInstance()` builds an instance
  (its `<init>` sets `base=40`), `getDeclaredMethod("answer").invoke(p, 2)` → **42** (`base + 2`). The class's
  methods (`<init>`/`answer`) compile ON DEMAND (M2) when reflectively resolved — `defineFromBytes` seeds only
  `<clinit>` reachable, like an incremental `forName`. Mechanism:
  - **`Loader.defineFromBytes(byteArr, off, len)`**: copy the guest `byte[]` payload into a fresh
    `Heap.allocData(len)` blob (a clean offset-0 base the loader reads raw), `addBlob`, seed `<clinit>` as the
    reachability root, `loadAll()` (demand-pulls the class's not-yet-loaded dependency closure from the
    classDir), then find the just-defined class by its unique `clBase` and return its Type. The classfile's own
    `this_class` names the class, so the `name` arg is advisory; no duplicate-definition check, no delegation.
  - **`VM.defineClass(JJJJ)J`** wraps it and returns the `Class` mirror (`classMirror`); `defineClassAddr`
    stashed by `ImageBuilder`, boot force-compile guard, `Loader.nativeBuf` arm for
    `java/lang/ClassLoader.defineClass0`.
  - **Overlay `java/lang/ClassLoader`** (JDK-free, single application loader): `loadClass`→`Class.forName`,
    `findClass` (subclass hook, default throws), `protected final defineClass(name,byte[],off,len)`→
    `defineClass0` native (throws `ClassFormatError` on 0), `getSystemClassLoader` singleton. Narrow-ALLOWed in
    both denylist sites (`Loader.isDenylisted` + `ReachScan`); `jdk/internal/loader` + `java/security` stay
    denied (no delegation/unloading/protection-domains).
  - **Remaining for the full acceptance set:** custom-`findClass` loader test shape (`ExceptionHidingLoader`)
    and a duplicate-definition guard are not yet exercised; M4 wires the file-read + enum-reflection end-to-end.

**M4 — end-to-end: read a `.class` from a file and run it (with access checks live).**
- `demo/ReflectLoad`: `ramfs/plugins/Plugin.class` embedded; boot reads its bytes (`FileInputStream
  .readAllBytes`), `ClassLoader.defineClass`, then reflectively `getDeclaredConstructor().newInstance()` +
  invoke an instance method (and/or `getMethod("main",String[].class).invoke(null,args)`), printing over UART.
  Verify on QEMU + a real Pi 4. Also lands `IsEnum` + `getEnumConstants/BadEnumTest` (enum reflection).
- **STATUS — M4a (file->defineClass->run) DONE (QEMU + real Pi 4 confirmed).** `demo/ReflectLoad` reads
  `/plugins/plugin/FilePlugin.class` off the embedded RAMFS (`FileInputStream.readAllBytes`), `defineClass`es
  it (M3), and drives the never-before-seen class through M2 reflection: QEMU prints `read 306 bytes...`,
  `defined plugin.FilePlugin`, `scale(7)=121` (`<init>` sets `seed=100`, `scale(7)`=`100+7*3`), all compiled on
  demand. The plugin is compiled into `ramfs/plugins/` (a generated, gitignored subtree) from `plugins-src/`
  via a `make plugins` step — **NOT** into the classDir, so it exists ONLY as a file the guest reads +
  `defineClass`es, never reachable by `forName`. File bytes -> Class -> instance -> method, for a class the VM
  never saw at image-build time. **Verified on a real Pi 4** (`core 166MHz`, clean UART trace ending in
  `scale(7)=121` / `[main returned normally]`) — not just QEMU.
- **STATUS — M4b (enum reflection) DONE (QEMU-verified).** `Class.isEnum()` = the `ACC_ENUM` bit via the
  existing `getModifiers` native (nested enums carry it in the enclosing `InnerClasses` entry).
  `Class.getEnumConstants()` returns the enum's compiler-synthesised `values()` array, reached through M2
  reflection (`getDeclaredMethod("values").invoke(null)`) rather than the stock `getEnumConstantsShared`
  cache — sidestepping the broken `Enum.valueOf`/`enumConstantDirectory` HashMap path (which AIOOBEs; the enum
  **base** — `Enum` superclass, enum `<clinit>` constructing the constants, `values()`/`name()`/`ordinal()` —
  all work). `demo/EnumReflectDemo`: `isEnum=1`, `constants=3` (`MERCURY=0`/`VENUS=1`/`EARTH=2`), and the
  negative case (`String.isEnum()==false`, `getEnumConstants()==null`). The `(Object[]) values.invoke(null)`
  array-covariance checkcast is handled. **Remaining:** `Enum.valueOf(Class,String)` (the map path) still
  AIOOBEs — a separate fix, not needed for `isEnum`/`getEnumConstants`.

**Scope / RED (skip):** modules (`GetModuleTest`, `forName/modules`, getResource/modules), SecurityManager/
protection-domain (`ProtectionDomainRace`), jars/URLClassLoader (`GetSystemPackage`, `forNameLeak`,
`LoadNullClass`), native/JNI (`loadLibrary*`, `nativeLibrary`, `LibraryPathProperty`), system-loader
(`CustomSystemLoader`, `RecursiveSystemLoader`), parallel-capable/deadlock, annotation+generic-signature
reflection (`GenericStringTest`, `TestPrimitiveAndArrayModifiers`), classfile-generation tests
(`GetSimpleNameTest`, `NonJavaNames`). Build order: M1 → M2 (unlocks everything) → M3 → M4; each independently
QEMU-verifiable, M4 also on the Pi 4.

---

## M8 — Dynamic lazy loading (the JikesRVM model) — NEW DIRECTION (2026-08-17)

A deliberate re-architecture of class loading, adopting the JikesRVM
(`joekoolade/JOE` `rvm/src`) design. Replaces the eager, closed-world,
reachability-driven, per-batch loader with **dynamic, lazy, compile-on-first-use**
loading and a **full-featured Java loader** over a **self-hosted `java.base`**.

### The reconciliation: "JDK-free core" and "full-Java loader" are LAYERS

JikesRVM's loader uses `String`/collections/exceptions/reified `RVMClass` objects
not because a JDK sits under it, but because the VM provides its own `java.base` on
the metal. "JDK-free" therefore means *free of the external **seed** JDK at
runtime* (self-hosted) — **not** "no rich library." Three layers:

- **Layer 0 — JDK-free base:** `magic`/`asm`/`objectmodel`/`vm` core (GC, scheduler,
  boot), **the compiler**, and the boot-image writer. Primitives + custom structures
  only — no `String`/collections/exceptions. Runs before/under the class library.
- **Layer 1 — `java.base` on metal:** the **stock OpenJDK** classes, self-hosted.
  Class *metadata* is baked into the boot image; method *bodies compile lazily on
  first use*.
- **Layer 2 — loader:** ordinary **full-featured Java** (reified
  `RVMClass`/`RVMMethod`/`RVMField`, `HashMap`s, exceptions, reflection) on top of
  Layer 1. Hosts the 4-phase lifecycle.

The Layer-0 compiler stays JDK-free yet compiles Layer-1 bytecode (a compiler needs
no `HashMap` to *emit* code for one); the loader (Layer 2) drives it across a
primitive-typed seam.

### Locked decisions (2026-08-17)

1. **`java.base` = stock OpenJDK** compiled in (not a hand-written minimal library).
2. **Laziness = compile each method on first call** (per-method granularity), not
   eager whole-closure AOT.
3. **Self-hosted** with joe-ng's own `java.base` on metal.

Key consequence of (2): the boot image needs only java.base **metadata** (structure
+ TIBs with lazy stubs), not eagerly-compiled bodies — so the lazy engine comes
FIRST and makes self-hosting stock `java.base` tractable.

### Staged order (strangler — eager loader stays behind a flag; flip demos one at a
time; **Pi-validate each flip**)

1. **Lazy method-compile engine** — compile-on-first-call.
   - **1a — the lazy-dispatch trampoline:** compile eagerly but **install into the
     TIB slot on first call** via a per-method stub → proves the self-modifying
     dispatch, I-cache maintenance, and ABI on metal *without* the context-restore
     problem. Flagged, default off (image byte-identical when off).
   - **1b — defer the compile itself:** restore a method's compile context at
     call time from the parse cache (`pc*` keyed by blob base) + the persistent
     `cl*`/`rg*`/field registries, then `compile()` the one method. This is
     genuine compile-on-first-use.
   - **1c — static/special path:** a member entrypoint/offset table (JikesRVM
     `memberOffsets`, seeded `NEEDS_DYNAMIC_LINK`, **data-patched**, not
     code-patched → no I-cache maintenance on the call site).
2. **Boot core stock `java.base` metadata + lazy bodies.**
3. **Reified full-Java loader** — replace the 493 flat statics / `rg*`/`cl*`/`g*`
   registries in `src/vm/Loader.java` with `RVMClass`/`RVMMethod`/`RVMField`
   objects + `HashMap` dedup + interned `Atom` names.
4. **4-phase lifecycle** (load → resolve → instantiate → initialize) as explicit
   states, advanced on demand by (1); optionally O(1) type checks (superclass
   display + `doesImplement` bitmap, replacing the linear `Type`-chain walk).
5. **Retire the eager loader** (`markReachable`/`resetLoader`/`patchRelocs`/`MAX*`
   caps) once the dynamic path runs the demo suite + NetDemo on real Pi.

### Hard problems (named up front)

- **Bootstrap closure:** the loader uses `HashMap`, so `HashMap` must be resolved
  *before* the loader runs — by the offline writer. Getting the boot-image
  pre-resolved closure exactly right is the delicate heart (it is what makes
  JikesRVM's boot-image writer complex).
- **GC of live metadata:** reified `RVMClass`/`HashMap`/atoms become permanent heap
  roots the collector must trace and never reclaim — replaces the per-batch reset.
- **Layer-0/Layer-2 seam:** the JDK-free compiler must never call loader
  collections; define the primitive-typed ABI early or the layering leaks.

### Contrast with the current (eager) loader

| | current joe-ng | M8 target |
|---|---|---|
| when | eager, whole reachable closure per batch | lazy, per method on first call |
| linking | `patchRelocs` at batch end (direct `BL`) | offset table / TIB stub, resolve on first use |
| loader state | 493 flat statics, primitive-array registries | reified `RVMClass`/`RVMMethod`, `HashMap`s |
| loader language | JDK-free (10-local ceiling) | full-featured Java over Layer 1 |
| lifecycle | load+compile fused | load → resolve → instantiate → initialize |
| memory | per-batch heap reclaim | GC-traced permanent metadata |

### Stage 3 — the reified loader (STARTED 2026-08-17)

The lazy engine (1a–1c, deferral, phase-A cells, metadata-only classes) is done and lazy
loading is the shipped default for 40 `java.base` classes. Stage 3 is the last major piece:
replace the loader's **493 flat statics / parallel-array registries** with **reified objects**
(`RVMClass`/`RVMMethod`/`RVMField`-style), moving `src/vm/Loader.java` toward the JikesRVM
model. It has **two halves**:

1. **Reification (achievable now, JDK-free):** turn each parallel-array registry into an array
   of small objects. The loader is JDK-free but not *object*-free — our own compiler supports
   `new`/`getfield`/`putfield` on image classes, so the loader can allocate small reified
   holders (primitive fields only) on the metal. Keep each class small ([[keep-classes-simple]]).
   - **First increment DONE:** `vm/DynLink` reifies the phase-A dynamic-linking table (the five
     `dl*` arrays → one `DynLink[]`). Proves the loader allocates + uses reified objects on
     metal (40 metadata-only classes still arm; HTTP 200). Next: `LazyMethod` (the 11 `lz*`
     arrays), then the big registries (`rg*` method / `cl*` class / `sg*` static-field / `g*`
     current-compile context) → `RVMMethod`/`RVMClass`/`RVMField`.
2. **Full-Java-over-`java.base` (bootstrap-blocked, later):** for the loader to use *stock*
   `String`/`HashMap`/exceptions (not just our own holders), that `java.base` core must be
   compiled into the **boot image**, pre-resolved, and usable *before* the loader runs — the
   loader is what boots `java.base`, so it can't demand-load its own dependencies. This is the
   classic metacircular bootstrap and the hard part (JikesRVM's boot-image writer is exactly
   this). Deferred until the reification (half 1) is well advanced.

Migration is incremental and behavior-preserving: reify one registry at a time, QEMU + Pi
validate each, keep the flat-static and reified forms from coexisting (replace, don't dual-write).

#### Full bootstrap — first probe (2026-08-17)

Reification half done (7 objects). Started the **full-Java-over-`java.base`** half with a
gated probe (`VM.BOOTSTRAP_PROBE`, default off): make `VM.run` reach `bootstrapProbe()` →
`java/lang/Math.max`, forcing the writer's reachable-closure BFS to compile stock `Math`
**into the image** (resolved via `ClassRegistry`, which already holds it).

**Result — the mechanism works, and the first blocker is precise:** the writer *did* reach
`Math` and began compiling it into the image (so the compile-into-image path is real). It
failed on `compiling java/lang/Math.<clinit>()V: ldc class-literal not compiled by the host
writer`. Two facts fall out:
- The writer forces a **used class's `<clinit>`** to compile at build time.
- The **host (writer-side) compiler has gaps** the *runtime* loader doesn't — here `ldc`
  class-literal — so a `<clinit>` the runtime loader handles fine (it demand-loads `Math`
  today) can't be built into the image.

**Two viable paths (decide next):**
1. **Writer bakes `java.base`** — close the host-compiler gaps (`ldc` class-literal, …) AND
   stop forcing `<clinit>` at build time (compile the class's *methods*, defer/skip `<clinit>`
   to a runtime init pass, exactly as the runtime loader already does for native/complex
   `<clinit>`s). More writer work, but keeps one build-time image.
2. **Two-loader bootstrap (JikesRVM-style)** — a minimal JDK-free bootstrap loader (what we
   have) loads + resolves a `java.base` core at boot; then a full-Java loader (using that
   core) takes over. Avoids extending the host writer; matches JikesRVM's boot sequence.

The probe stays committed (off) as the reproducer. This is the genuine hard remainder —
each subsequent step is its own investigation + increment.

#### Full bootstrap, path 1 — static-state snapshot (2026-08-17)

Path 1 chosen and running. After methods-only baking (`ImageBuilder.bakeNoClinit` defers a
stock class's `<clinit>`; 6 pure-leaf Math/Integer methods baked + probed), the next ceiling
was **statics**: a baked method that *reads* a static needs the value its `<clinit>` would
have produced, and that `<clinit>` can't be host-compiled (class literals, natives).

**Increment 1 DONE — primitive statics snapshotted from the seed JVM.** The writer runs on a
seed JVM where those classes are already initialized, so `writer/StaticSnapshot` reflects the
initialized value (`Class.forName` + `Field.get`, needs `--add-opens java.base/java.lang`)
and the writer writes it into the class's image static slot (`fillStatic`) — the JikesRVM
boot-image-writer move. Proof: stock `java/lang/StringUTF16.getBytes` computes its copy-loop
start as `srcBegin + (1 >> LO_BYTE_SHIFT)`, and `LO_BYTE_SHIFT` is set only by a `<clinit>`
that calls the native `isBigEndian()`. The writer force-roots `getBytes` into the closure
(`BAKE_ROOTS`, address stashed in `VM.utf16GetBytesAddr` since javac can't name the
package-private class; called via `Magic.callN` — `callN`/`addrOf` added to WriterSymbols)
and `bootstrapProbe` extracts "JOE!" from a hand-built UTF-16 array. Snapshot on → `JOE!
PASS`; snapshot disabled (negative control) → the loop reads the zero high bytes → `FAIL`.
The runtime demand-load path for the same class is untouched (87 phase-A cells as before).

**Next increments:** object statics (arrays/Strings — bake the referenced object into the
image heap, deep snapshot: e.g. `Integer$IntegerCache.cache`, `Integer.digits`), then widen
the baked set, then have `vm/Loader` itself call baked `java.base` (step 4).

**Increment 2 DONE — object statics (primitive arrays deep-snapshotted).** A `bakeNoClinit`
class's reference-typed static is now baked as a real object: `StaticSnapshot.reference`
reflects the seed JVM's value, the writer lays out an array object in a new baked-objects
image region (null TIB + status + length + little-endian elements, `writeArrayObject` — the
same shape as interned strings, generalized over element size), and the static slot points
at it. Primitive arrays only; any other non-null reference fails the build loudly. Proof:
stock `Integer.formatUnsignedInt` indexes `Integer.digits` (a `<clinit>`-built `byte[]`) —
baked, the probe prints `cafe PASS` from `0xCAFE`; with the slot left null (negative
control) the un-null-checked `baload` reads low RAM → garbage + FAIL. Next: scalar objects
with fields + TIBs (`Integer$IntegerCache.cache` needs baked `Integer` objects), reference
arrays, Strings.

**Increment 3 DONE — scalar objects + reference arrays (whole object graphs).**
`bakeDiscover` BFS-walks the seed JVM's object graph from each baked static (identity-deduped,
so aliasing and cycles are safe); `bakedWords`/`writeBakedObject` lay out and write all three
shapes — primitive arrays, reference arrays (8-byte pointer elements resolved through the
graph), and scalar objects (fields at the model's slots via the SAME registered classfile the
compiler resolves `getfield` against, so offsets agree by construction; TIB filled when the
image lays one out for the class, else null like interned strings). A new `BAKE_STATICS`
mechanism force-adds a static to the referenced set and stashes the SLOT's address in a VM
static — used for `Integer$IntegerCache.cache` because stock `valueOf`'s never-taken
`new Integer` branch would drag every Integer virtual (toString → String/Unsafe closure) into
the host compile. Probe: slot → baked `Integer[]` → element 170 → stock `Integer.intValue()`
prints `*` (42). Two lessons: `java/lang/Byte` (first attempt) resolves to the GUEST overlay
in the registry, whose mini code explodes the closure — only overlay-free stock classes
(Integer, Long, Math, StringUTF16) are cleanly bakeable; and any stock `valueOf` needs
writer-side native stubbing before it can compile. Next: bake `valueOf` itself (native stubs
or pruned branches), Strings, TIBs for baked-only classes, then `vm/Loader` uses baked
`java.base`.

**Increment 4 DONE — bake stubs: `Integer.valueOf` compiles, uncompilable fringe traps.**
Three writer mechanisms: (1) `compileOrStub` — a stock-java.base (`bakeDomain`: java/jdk/sun)
method that fails to host-compile (native/abstract = no Code, unsupported opcode, missing
class/helper) bakes as a trap stub (save LR, `BL VM.bakeTrap` → loud halt) instead of failing
the build; failures outside the bake domain stay fatal. A stubbed `<clinit>` is dropped from
`VM.initClasses` (deferred) and its class's referenced statics flow through the snapshot
(`clinitDeferred`). (2) **Contained vtable pull** — a bake-domain instantiated class's vtable
methods park in `pendingVtable`; after the called closure drains, unreached slots bake as
stubs *without a compile attempt*. Without this, one `new Integer` cascade-compiled half of
java.base (660 stubs, TreeMap/Pattern pulled in, snapshot trying to bake Pattern node graphs);
with it, exactly Integer's 13 unreached virtuals stub. (3) `isSkippableInit` is now
caller-aware: the vm-side "super() into a JDK class" shim still skips, but java.base calling
a java.base `<init>` is a real compiled call — `valueOf(200)`'s `new Integer` runs stock
`Integer.<init>` → mini `Number.<init>` → `Object.<init>` and the field actually stores.
Also: `instanceof`/`checkcast` against an ARRAY type now fails the compile explicitly (the
host writer lays out no array Types) so such methods stub rather than mis-answer. Probe:
`valueOf(42)` returns the very baked `cache[170]` object; `valueOf(200)` heap-allocates with
the REAL Integer TIB (equal to the baked objects' TIB) and `intValue()` reads 200. Gotcha
fixed en route: the BFS body's `continue` for already-sized keys skipped an end-of-body
drain — the pendingVtable pop had to move to the loop top under a widened loop condition.
Next: Strings, real vtables/TIBs for baked classes (stubs make the pull safe now), widen the
baked method set, then `vm/Loader` uses baked `java.base`.

**Increment 5 DONE — real vtables for baked classes: virtual dispatch on baked objects.**
The deep-snapshot graph discovery moved from layout INTO the compile fixpoint: the BFS drains
the called worklist + parked vtable stubs, then discovers the object graphs of all
deferred-`<clinit>` reference statics seen so far, and **every baked scalar's class joins
`tibClasses`** — real TIB + Type, unreached vtable slots baked as trap stubs — looping until
nothing new appears. Baked objects no longer ever carry a null TIB. Key semantics: virtual
dispatch TARGETS are not BL-reached, so a method meant to be dispatched through a baked TIB
must be rooted (`BAKE_ROOTS`) or its slot stays a trap stub — the lazy-trap default per slot.
Proof: stock `Integer.equals` (rooted) runs `instanceof` (Type-chain walk over a baked
object), checkcast, and a genuine `invokevirtual intValue()` through the argument's TIB →
`1,0` for 42==42 / 42==43; and `Long$LongCache.cache` is baked while NOTHING in the compiled
closure ever `new`s a Long — its Longs carry a TIB purely via the fixpoint, and `Long.equals`
dispatches `longValue()` through it → `1`. Negative control: un-rooting `Long.longValue` made
that dispatch land in its stub → live `BAKE TRAP (lr=...)` halt — the trap semantics proven
on the metal. Next: Strings, widen the baked set, then `vm/Loader` uses baked `java.base`.

**Increment 6 DONE — baked Strings: `ldc` in stock code makes real String objects.**
Two discoveries set the shape: the runtime `java/lang/String` IS stock (no guest overlay — the
lazy arc runs stock String on-metal), so there is no layout question — the baker's
field-by-name read (value/coder/hash/hashIsZero) aligns with the registered classfile by
construction. And the vm-side `ldc "..."` contract (raw byte[] through `Magic.bytes`) is
WRONG for stock code, which needs a real object. So `WriterSymbols.string` now splits by
caller: a bake-domain method's `ldc` records into a new `stringObjs` reloc list; the writer
interns the host String by literal bytes (JLS-style, one object per text), bakes it through
the existing deep-snapshot graph (a String is just a scalar whose `value` field is a baked
byte[]; `java/lang/String` joins tibClasses via the scalar rule — its ~100-slot vtable parks
and stubs), and patches each site with the baked object's address. vm code keeps the byte[]
path untouched. Also: the emit pass's symbol table no longer `lookup()`s stubbed keys (a
stub's method may not exist in the registered classfile — e.g. stock AssertionError.<init>
(Object) vs the guest overlay). Probe: stock `String.valueOf(boolean)` (pure ldc) returns
the interned literal; `length()`/`charAt()` — with virtual targets `coder()`/`isLatin1()`
rooted and `COMPACT_STRINGS` snapshotted off the auto-deferred `<clinit>` — print `true`
back char by char. Negative control (byte[] literal instead of object): `length()`'s
dispatch wild-branches through the null TIB → caught by the boot re-entry guard. Next:
widen the baked set (String utilities now unblocked), then `vm/Loader` uses baked `java.base`.

**Increment 7 DONE — widened bake: toString/toHexString/String.equals, zero new mechanism.**
Four roots added and everything simply worked — the measure that the machinery is now complete
for this class of code: `Integer.toString(int)` and `Long.toString(long)` build REAL Strings
on the metal heap (the registry resolves the **guest DecimalDigits overlay** — metal-friendly
digit math, not the Unsafe-table stock — while stock `newStringWithLatin1Bytes` and the
private `String(byte[],byte)` constructor run as compiled stock code), `Integer.toHexString`
exercises the unsigned/letters path, and `String.equals` compares content across two DISTINCT
heap Strings (`toString(42)` twice → different objects, equal content → true; vs "beef" →
false). Probe line: `toString(-2026)=-2026, Long 1<<40=1099511627776, toHex=beef, equals=1,0`.
118 stubs total. Remaining: the arc endgame — `vm/Loader` resolves calls into baked
`java.base` instead of demand-compiling its own copies.

**Increment 8 DONE — THE ENDGAME: the Loader USES baked `java.base`.** The writer emits a
**baked-method LINK table** ({classUtf8, nameUtf8, descUtf8, code} per entry; names as
{u2 len}{bytes} runs, the classfile-Utf8 shape) of every writer-compiled stock method that is
safe to run on LOADER-world receivers, and `Loader.lazyCompile` consults it FIRST
(`bakedBuf`): on a hit the lazy stub/TIB slot is patched with the image's compiled buffer and
no on-metal compile happens — the JikesRVM boot-image contract, live. The safety filter is
mechanical: primitive/void return only (a reference return would leak a writer-TIB object
into the loader world, where un-rooted virtuals are trap stubs), and none of the
world-crossing constructs — no `instanceof`/`checkcast` (writer Types ≠ loader Types) and no
`invokevirtual`/`invokeinterface` (**writer vtables include private methods, the loader's
exclude them — slot numbers diverge**; found by inspection, tracked per-method via a new
`virtualDispatch` reloc flag set in `WriterSymbols.vtableSlot`). Field offsets and BL targets
are world-independent, so everything else links: 31 methods. For deferral entries (bytecode
captured, no name) a new `findNameByCode` walks the method table inverse to
`findMethodByOffsets`. QEMU: 4 live cross-world links during the NetDemo demand-load —
`String.coder`, `Integer.intValue`, `String.isLatin1`, `StringLatin1.charAt` — loader-created
objects flowing through writer-compiled stock code, then the normal endpoint;
`BAKED_LINK=false` restores the old behavior bit-for-bit (flag default ON). Widening the
linkable set (object returns, virtual dispatch) needs ONE String/Integer class across both
worlds — unified Types + vtable numbering — the natural next arc.

#### World unification (arc started 2026-08-18)

**Increment 1 DONE — the vtparity boot invariant, and a corrected diagnosis.** The writer now
emits a **vtable-signature table** for every bake-domain class it lays a TIB for, and the
loader verifies its own freshly-built flattening against it slot-for-slot at structure time —
`vtparity <class> OK n` / `DIFF count a/b` / `DIFF slot s` — so writer/loader slot-numbering
parity (the precondition for cross-world virtual dispatch in linked baked code) is checked at
every boot, before any dispatch can land wrong. **What the probe found immediately:** the
endgame's private-method theory was WRONG (the loader has included private methods since the
nestmates change — the doc comment was stale); the real divergence is the **inherited
prefix**: the loader flattens the loaded `java/*` super chain — Object's ~6 slots prefix every
vtable, Object+Throwable's 12 prefix the throwables — while the writer's `isRoot` stop
discards those supers entirely, shifting every writer index (`String 86/92`, `Integer/Long
14/20`, throwables `0/12`). A trial relaxation of the link filter's `virtualDispatch`
exclusion confirmed it live: linked `String.length` dispatched `coder()` at the writer's slot
into a loader TIB → wild branch → caught by the boot re-entry guard. The exclusion is
restored (with the corrected rationale); the linked set stays at 31. **Increment 2:** the
writer adopts chain flattening over registered `java/*` supers (and inherited-first field
layout, the same own-only assumption) so vtparity reads OK — then the relaxation is sound.

**Increment 2 DONE — writer chain-flattening: vtparity reads OK across the board.**
`ClassFile.vtable` now flattens the WHOLE registered super chain (stop at `sup == null`, not
`isRoot`): guest Object's 9 virtuals prefix every vtable, overrides land in place — exactly
the loader's numbering. The co-fix landed with it: field layout is chain-aware
(`ClassFile.chainFieldBase`; `WriterSymbols.fieldOffset`/`objectSize`, the Type
`instanceSize`, and the deep-snapshot baker's size/walk all lay inherited fields FIRST, like
the loader). A new `Resolver.canResolve` seam keeps resolver-less fixture compiles on the old
flat view. Consequences absorbed: every writer TIB grew by the Object-chain slots (their
impls = guest Object methods, compiled or bake-stubbed — pre-existing writer code never
dispatched them, so stubs are pure additions); `CompilerTest`'s vtable pins moved to the new
numbering (Animal/Dog `sound` at slot 9 of 10); the baked-link table picked up 4 newly
compiled guest Object methods (35). QEMU: **all eight `vtparity` lines flip DIFF → OK**
(`String OK 92`, `Integer/Long OK 20`, `StringBuilder OK 19`, throwables `OK 12`), all 7
probes PASS, links fire, tests green, normal endpoint. **Increment 3:** lift the
`virtualDispatch` link exclusion — now provably sound — so `String.length`/`charAt` link.

**Increment 3 DONE — the `virtualDispatch` exclusion lifted: cross-world virtual dispatch is
live.** With slot numbering algorithmically identical (increment 2) and boot-verified
(vtparity), plain `invokevirtual` inside linked baked code is sound: a writer slot number IS
the loader slot number. The exclusion clause and the `virtualDispatch` reloc flag are gone;
only `instanceof`/`checkcast`/`invokeinterface` (writer Types/itables, still distinct nodes)
keep a method out of the link table. 35 → **38 linked methods**, and the exact case that
wild-branched pre-parity now works: linked `String.length` dispatches `coder()` through the
LOADER String's TIB at the parity slot. Second-order win: linked `String.charAt` BLs the
writer's `StringLatin1.charAt` directly, so the loader no longer lazy-compiles it at all —
the baked closure absorbs work from the lazy path. Remaining unification rungs: Type/TIB
adoption (one class identity → cross-world `instanceof`, lifts the typeRefs exclusion),
then statics.

**Increment 4 DONE — Type adoption: ONE Type node per baked class across both worlds.**
Two fixes in one move. First, a latent writer hole: `addTypeClass`'s `isRoot` stop meant
`java/*` classes had NO writer Type at all — every baked `instanceof` site patched the same
out-of-range address (accidentally consistent, zero discrimination between java/* classes).
Now the full registered super chain gets real, distinct, chained Type nodes (Type fill stops
at `sup == null`; vm-class chains continue into guest Object's Type too). Second, the
adoption itself: the vtSig table widened from bake-domain∩tibClasses to bake-domain
non-interface **typeClasses** (all instantiated classes, type-check targets, and their full
chains), its spare 4th slot now carrying the writer Type address; the loader's phase A
(`checkVtParity` → `gAdoptType` → `allocTib`) ADOPTS that node as the class's runtime Type
instead of allocating its own. The loader's two Type-field stores are idempotent on adopted
nodes (instanceSize by field parity; superType because supers adopt too), and `itableDir`
stays loader-owned (the writer never reads it). QEMU: **15 `typeadopt` lines** — Object,
Number, Throwable, the whole exception chain, String/Integer/Long/StringBuilder — and
vtparity coverage widened 8 → **15 classes, all OK** (including `Object OK 9`). All 7 probes
PASS, 38 links fire, tests green, normal endpoint. **Increment 5:** lift the `typeRefs` link
exclusion — `instanceof`/`checkcast` in linked code now compare the shared node — so
`String.equals`/`Integer.equals` link.

**Increment 5 DONE — the `typeRefs` exclusion lifted: cross-world `instanceof` live.** With
Type adoption, `instanceof`/`checkcast` against a CLASS in linked baked code compares the
shared node on loader receivers — sound. The analysis surfaced one refinement:
`VM.instanceOf` answers INTERFACE targets from the Type's `itableDir`, which is loader-owned
on adopted nodes (writer interface Types would never match), so methods type-checking
against an interface stay excluded — the filter now checks each typeRef target's
interface-ness — as does `invokeinterface`. 38 → **41 linked methods** (`String.equals`,
`Integer.equals`, `Long.equals`). QEMU shows the payoff live: `baked java/lang/Integer.equals`
links and runs in the boot path — its `instanceof Integer` walking loader Integers' Type
chains into the shared adopted node. All 7 probes PASS, no vtparity DIFFs, tests green,
normal endpoint. Remaining exclusions: interface type-checks + `invokeinterface` (itable
unification), then statics — after which object-returning links (`valueOf`/`toString`)
become the last frontier.

**Increment 6 DONE — interface Type adoption + two more `isRoot` bugs down.** Interfaces now
join the adoption table (with `slotsAddr = 0` as the no-vtable marker — the loader's
interface phase-A branch adopts the writer's Type node, `typeadopt java/lang/Comparable`),
making cross-world interface `instanceof` sound: `VM.instanceOf` answers interface targets by
comparing the ifaceType KEYS in a Type's itableDir, and the loader's dir entries now hold the
shared node. The interface-target `typeRefs` exclusion is lifted; only `invokeinterface`
remains excluded (the two worlds index itables differently: loader = global interface-method
index, writer = per-interface slot). Getting the probe green surfaced THREE writer gaps, all
the same `isRoot` family: (1) itable directories were built only from `invokeinterface`
targets, so a pure `instanceof SomeInterface` read false even writer-side — instanceof
interface targets now join `usedInterfaces`; (2) `ClassFile.allInterfaces` never walked a
`java/*` class (empty interface set for Integer!); (3) `ClassFile.findImpl` likewise — it
could never find `Integer.compareTo(Object)` for the itable fill. Probe 8:
`Integer instanceof Comparable, Object = 1,0` — a baked Integer (as Object) matches via
Integer's itable directory, a plain Object doesn't. Debugging lesson: a same-second edit
left `out/.stamp` equal to the source mtime, so make skipped recompiles and QEMU tested
stale images — `touch` before rebuilding when iterating fast.

**Increment 7 DONE — writer-world `invokeinterface` proven; the loader itable refactor
deliberately deferred.** Probe 9 dispatches `compareTo` on the boxed 7 through Integer's
itable — the inline directory search keys on the shared Comparable Type and indexes the
per-interface slot, landing in the newly-rooted `Integer.compareTo(Object)` bridge
(checkcast + invokevirtual to the typed `compareTo(Integer)`, also rooted): `<,0 PASS`.
This is the first live exercise of the itable CONTENT the `findImpl` chain fix made
buildable. The compareTo pair also joined the link table (44 methods — prim return,
class-only checkcast, parity-safe dispatch). **Scope decision:** unifying `invokeinterface`
itself needs the loader to move from its global interface-method index (flat MAXIFM imaps)
to per-interface itable slots — but the imap is load-bearing across `refillImaps` (default-
method repair), the baked run-trampoline (assumes the shared table at dir[0]), lambda
synthesis, and EnumMap seeding, and much of that is Pi-only-verifiable. Per the batching
lesson (PR #71), that refactor is its own future increment; the `interfaceRefs` link
exclusion stays until then.

**Increment 8 DONE — the loader itable refactor: per-interface slots everywhere, the LAST
link exclusion lifted.** Both worlds now index itables identically: FLATTENED per-interface
method lists (each super-interface's flattened run first, in `interfaces[]` order, then own
declarations; dedup keeps the inherited position — flattening, not own-only lists, is what
lets a call typed to a super-interface, e.g. a BinaryOperator lambda invoked as BiFunction,
index the right slot). Writer: `ClassFile.interfaceMethods`/`interfaceSlot` are now static,
resolver-based, flattened; the vtSig table carries interface signature lists (itparity).
Loader: `registerInterface` captures the flattened run into the ifm registry
(`RVMClass.ifmStart/ifmCount`, `ifmAppendUnique`; MAXIFM 512→2048); `ifSlotOf` resolves
per-interface; `buildItableFor` replaces the flat MAXIFM imap with per-interface tables
(defaults via `defaultBySig`); `refillImaps` repairs by walking dir entries keyed by TYPE
(order-independent); the run-trampoline scans the directory for Runnable's (shared) Type
instead of assuming dir[0]; lambdas build per-entry itables slotted by the SAM's signature
per interface; `checkIfParity` verifies each baked interface's numbering at registration
(`itparity <iface> OK n`). The `interfaceRefs` link exclusion is GONE — the only remaining
link gate is the primitive-return filter (object leaks await statics/allocation unification).
Validated beyond the NetDemo boot (9 probes PASS, itparity Comparable OK) with manifest-
swapped QEMU runs of the itable-heavy demos: ThreadDemo (the reworked run-tramp: `joined`),
SortProbe (comparator dispatch), LispDemo (lambda itables: `(twice inc 40) = 42`), and
WordCount (LinkedHashMap iterators + a live `String.equals` bake-link mid-demo) — all clean
returns, zero DIFFs, zero traps.

**Increment 9 DONE — statics unification: ONE home per static field.** Baked (vtSig) classes
now get **dense per-class static blocks** in the writer's statics region — one slot per
DECLARED static, in declaration order, which IS the loader's slot numbering — keyed through
`staticWord` so every existing mechanism (getstatic patches, writer fills, the seed-JVM
snapshot, `BAKE_STATICS` stashes) resolves into the block untouched. The vtSig entry widened
to 6 longs (`{classUtf8, slotsAddr, count, typeAddr, staticsAddr, staticCount}`), and the
loader **adopts the block** as `clTab[].statics` right after `findVtSig` (before
`registerClassStructure` captures per-field addresses into `sgTab`) — with a count guard
that degrades to the loader's own block and prints `staticadopt DIFF` on mismatch (none
fire). Consequences: the loader's `<clinit>` runs now initialize the SHARED slots (both
worlds see one value — the point); deferred classes carry the snapshot, which now covers
EVERY declared primitive of a deferred baked class (loader-compiled readers see the block
too), not just writer-referenced fields; blocks sit inside `staticsStart/End` so the GC root
scan covers loader-written heap pointers. Validation: NetDemo boot (all 9 probes PASS, no
DIFFs) + the four-demo battery (ThreadDemo/SortProbe/LispDemo/WordCount) all clean.
Remaining: object-returning links — now gated only on TIB-slot content (a writer-TIB object
in loader hands can dispatch into bake stubs), the arc's last rung.

**Increment 10 DONE — object-returning links: the arc's last rung, and its thesis made
mechanism.** Bake stubs are no longer traps: each is an arg-preserving RESOLVE trampoline
(save x0..x7+LR, `movz` its stub-table index, `BL VM.bakeResolve`, tail-branch the result).
`VM.bakeResolve` reads the new stub table ({classUtf8, nameUtf8, descUtf8, memo} per stub),
and `Loader.resolveBakeStub` demand-loads the class into the running program
(`loadClassIncremental`) and finds a callable buffer three ways — registered compiled buffer,
phase-A static cell, or the class's TIB slot (all self-compiling if still lazy) — memoized in
the table. This is only sound because of everything before it: the lazily-compiled method and
the baked caller agree on field offsets, vtable slots, itable slots, Types, AND statics. With
the fringe closed, the primitive-return link gate is LIFTED: 44 → **56 linked methods**,
including `valueOf`, `toString`, `toHexString`, `String.valueOf`. The boot log shows the
whole story in two lines: `baked java/lang/Integer.valueOf` (an object-returning link hands
a BAKED Integer to loader code) followed by `bakeresolve java/lang/Integer.hashCode` (the
dispatch on it lands in a stub and resolves lazily instead of trapping). Demo battery:
LispDemo resolves `Integer.toString` live, WordCount resolves `Integer.hashCode` — correct
outputs, zero traps. **The world-unification arc is complete**: one vtable numbering, one
dispatch fabric (virtual + interface), one Type per class, one home per static, and a lazy
bridge over the uncompilable fringe — the loader and the baked image are one VM.

### Stage 5 — retire the eager loader (STARTED 2026-08-18)

Stage 4 (O(1) type checks, PRs #99/#100, + the reified 4-phase lifecycle, PR #101) closed
out the metadata model. Stage 5 retires the eager per-batch machinery itself —
`markReachable`/`resetLoader`/`patchRelocs`/`MAX*` caps — by first making the lazy path
the default and the eager path the pinned exception, then shrinking the exception list
one Pi-validated class at a time until the eager machinery is dead code.

**Increment 1 — lazy by DEFAULT (the allowlist inverts). DONE, PI-VALIDATED (PR #102).**
Real Pi 4: 16 bootstrap probes PASS, `lifecycle OK 162`, WPA2-PSK join + DHCP
(192.168.1.247), **HTTP 200 OK with the full body over stock `java.net.Socket`**
(`bytes=828`), clean `[main returned normally]` — no cap halt, no trap fired, no wild
branch. `stage2Gated` no longer names
the metadata-only classes: ANY demand-loaded java.base class (`java/`/`jdk/`/`sun/`
prefixes) is metadata-only unless `eagerKept` pins it to the eager path. The eager-keep
list is the conservative complement of everything the widening arc (PRs #59–#65, #71)
proved or suspected risky: the socket-native stack and its adjacent prefixes (`java/net/`,
`sun/nio/`, `sun/net/`, `java/nio/`, `java/io/FileDescriptor*`, the VarHandle/invoke
shims, `jdk/internal/{access,misc,ref,event}/`, `java/lang/ref/`, the reflection floor,
`java/util/concurrent/` — including PR #71's regressed TimeUnit — Thread, System, Class,
Object) plus the Throwable hierarchy (exact `Throwable` + name-suffix
`Exception`/`Error`, since the unwinder resolves handlers against compiled bodies
mid-throw). `<init>`/`<clinit>` still compile at load exactly as before, so the hand-tuned
clinit ordering is untouched — the flip only defers plain method bodies, which the
stage-2 safety invariant covers (lazy compiles only CALLED methods, a subset of what
eager compiled). Plumbing: `MAXLAZY` 1024→8192 (the NetDemo batch alone arms ~907 cells;
the old cap was already within ~10% of overflow) and a `capHalt` overflow guard on
`emitDeferredStub`, which wrote `lzTab[lzN]` unguarded.

**Two latent lazy-path bugs the flip exposed** (both found on WordCount, whose
`String.split` reaches `ArrayList.subList` — a call chain no class in the old 40 could
make). Neither is specific to the classes now gated; both were waiting for the first
lazily compiled body that needed them:

1. **A lazy compile's own relocs were never patched.** `MetalSymbols.call` emits `bl 0`
   plus a reloc record when a callee doesn't resolve at compile time, and only
   `patchRelocs()` (batch end, in `loadAll`) ever rewrites those. A body compiled on
   first call runs *after* that patch, so its `bl 0` stayed — and a `bl` to absolute 0
   lands in the firmware's low-memory shim, which jumps to the image entry: the
   `BOOT RE-ENTERED at EL1` wild-branch halt, with no hint of the real callee. Fix:
   `patchRelocsFrom(rcStart, rsStart)` patches just the sites a compile recorded (the
   trap-site table is not reset), and `lazyCompile` brackets its `compile()` with the
   marks. A `capHalt("lazy-compile-null")` now also catches a 0 return, which the
   trampoline would otherwise `br 0` into the same shim.
2. **Inherited statics missed their cell.** javac names an inherited static through the
   subclass (`ArrayList.subListRangeCheck`, declared on `AbstractList`). The cell lookup
   matched the ref'd class only, and the registry fallback can't help either — a
   metadata-only class's celled statics are never compiled or registered. Fix:
   `dlCellFor` walks the ref class's super chain (JVMS resolution order), mirroring the
   walk `globalBufByRef` already had for the eager path. The old
   `stage2Gated(ref-class)` pre-gate is gone with it: membership in the `dl*` table is
   itself the gate, and it is the *declaring* class that must be gated, not the ref'd one.

**Increment 2 — shrink `eagerKept`: the reflection floor and the Throwable hierarchy.
DONE, PI-VALIDATED (PR #103).** Real Pi 4: 16 probes PASS, `lifecycle OK 162`, WPA2 join
+ DHCP, **HTTP 200 OK with the full body** (`bytes=828`), clean return. The boot log shows
the flip directly — `phaseA: 2 cells … java/lang/Class` and phase-A lines for the whole
exception hierarchy (`Throwable`, `Exception`, `Error`, `NullPointerException`,
`ClassCastException`, `InternalError`, …), all metadata-only, with no `bakeresolve-find`.
Both come off the eager list. They are the QEMU-verifiable half of the exception list —
the reflection demos (`ForName`/`Method`/`Field`/`Ctor`/`Enum`/`Access`/`DefineClass`/
`ReflectLoad`) and the exception demos (`Trace`/`Unwind`/`StackTrace`/`InfraProbe`) cover
them without a Pi. Removed: `java/lang/reflect/`, `java/lang/Class*`, `java/lang/Throwable`,
and the name-suffix `Exception`/`Error` rules (with `utf8HasSuffix`, now unused). What
remains on the list is exactly the socket-native stack and its adjacent prefixes, which
only real hardware can exercise.

**The gap it exposed — a baked body calling a PROVIDED NATIVE.** With `Throwable` lazy,
its `printStackTrace` is no longer compiled by the loader; the writer-baked body runs
instead, and inside it the call to `printStackTrace0` is a bake stub. `resolveBakeStub`'s
three tiers (`rg*` registry → `dl*` cells → vtable slots) all search for *bytecode*, and a
native has none — so it halted with `bakeresolve-find`. The provided-native table was
reachable only from the compile-time path, keyed by constant-pool offsets in the current
blob. `nativeBufAt(clsBase, clsOff, nameBase, nameOff)` now takes a `(base, offset)` pair
per name, so `nativeBuf` passes the blob's cp offsets and `resolveBakeStub` passes its
absolute `{u2 len}{bytes}` runs at offset 0 — one table, both worlds. This generalizes:
any metadata-only class whose baked body calls a native needs it, `Throwable` was just
the first to arrive.

**Increment 3 — shrink `eagerKept`: the reference/cleaner/event subsystem. DONE,
PI-VALIDATED (PR #104).** Real Pi 4: 16 probes PASS, `lifecycle OK 162`, WPA2 join + DHCP,
HTTP 200 OK with the full body (`bytes=828`), clean `close()` and return. All nine classes
carry phase-A lines (`Reference` 4 cells, `SocketRead/WriteEvent` 6 each, `Cleaner` and
`CleanerFactory` 1 each) — so the cleaner and the read/write event probes ran lazily on the
live socket path, which is the part QEMU cannot reach. Off the list:
`java/lang/ref/`, `jdk/internal/ref/`, `jdk/internal/event/` — nine classes
(`Cleaner`(+`$Sync`,`$Cleanable`), `CleanerFactory`, `PhantomCleanable`, `Reference`,
`PhantomReference`, `Event`, `SocketReadEvent`, `SocketWriteEvent`). One subsystem, taken
together: the `Cleaner`/`CleanerFactory` overlays are small pure-Java stand-ins (register
returns a synchronous `Sync`; no daemon thread, no `ReferenceQueue`), and the event classes
are stock all-static no-op probes. The `Reference`/`PhantomReference` bodies are never
called at all — the overlay bypasses them — so laziness means they are simply never
compiled.

Unlike increments 1–2 this is **socket-path material**: `NioSocketImpl` registers a cleaner
and calls `cleaner.clean()` on `close()`, and reads/writes hit `SocketRead/WriteEvent`.
QEMU can only prove the absence of collateral damage (it stops at `connect`); the Pi run is
what actually exercises these. Batching the trio is a deliberate exception to the
one-at-a-time rule for socket-adjacent classes: they are one functional unit, and every
failure mode we have seen here (`bakeresolve-find`, `DENYLIST TRAP`, `CAP EXCEEDED`) names
the offending class, so attribution survives.

**Increment 4 — shrink `eagerKept`: concurrency + `Unsafe`. DONE, PI-VALIDATED (PR #105).**
Real Pi 4: 16 probes PASS, `lifecycle OK 162`, WPA2 join + DHCP, HTTP 200 OK with the full
body (`bytes=828`), clean return. `ReentrantLock`, `TimeUnit`, the atomics and `Unsafe` all
carry phase-A lines — so `NioSocketImpl`'s per-read/write lock and `Unsafe`'s array-offset
statics came up lazily on the live socket path, and `TimeUnit` (the PR #71 regressor)
cleared its own run. Off the list:
`java/util/concurrent/` (the no-op `ReentrantLock`/`Semaphore`/`LockSupport` overlays,
`TimeUnit`, the atomics, `ConcurrentHashMap`) and `jdk/internal/misc/` (the `Unsafe`
overlay). `Unsafe` looks load-bearing but is not: its `<clinit>` assigns the
`ARRAY_*_BASE_OFFSET`/`INDEX_SCALE` statics that `ArraysSupport` reads cross-class, and
`<clinit>` is *never* deferred — the lazy path always compiles and runs it at load. What
defers is its method bodies, which are one-line `Magic.loadX(addrOf(obj) + offset)`
wrappers. The atomics' natives (`fieldOffset0`, `callerClass0`) resolve through
`nativeBufAt` (increment 2) from either world.

`TimeUnit` was in the batch that regressed on real HW in PR #71, so it gets its own Pi run
here rather than riding along with a wider change. `java/lang/Thread` deliberately stays
eager for now: the scheduler can enter Thread code from a context switch, and a first-call
compile there would re-enter the loader from an interrupt — that needs its own increment
and its own reasoning, not a batch.

**Still on `eagerKept` after this:** `jdk/internal/access/` (SharedSecrets and the access
shims `FileDescriptor.<clinit>` must register first), `java/lang/Thread`,
`java/lang/System`, `java/lang/Object`, and the socket floor (`java/net/`, `sun/nio/`,
`sun/net/`, `java/nio/`, `java/io/FileDescriptor`, `java/lang/invoke/`,
`jdk/internal/invoke/`).

**Increment 5 — shrink `eagerKept`: `System`, `Thread` and the access shims. DONE,
PI-VALIDATED (PR #106).** Real Pi 4: 16 probes PASS, `lifecycle OK 162`, WPA2 join + DHCP,
HTTP 200 OK with the full body, clean return. `System` 31 cells, `SharedSecrets` 63,
`Thread` 8, `MetalJavaLangAccess` 0 — the fd access shim resolved lazily on the live socket
path, confirming the `<clinit>`-never-defers argument holds where it matters. Off the
list: `java/lang/System` (31 cells), `java/lang/Thread` (8, + `ThreadLocal`/`ThreadGroup`)
and `jdk/internal/access/` (`SharedSecrets` 63, `MetalJavaLangAccess`). After this only the
socket floor and `java/lang/Object` remain.

`Thread` was held back in increment 4 on the theory that the scheduler might enter Thread
code from a context switch, where a first-call compile would re-enter the loader from an
interrupt. Checking rather than assuming: **`VMScheduler` implements the Thread services
itself** (`taskInterrupted`, `isAlive`, the `join` core, `holdsLock`) and treats the thread
object as an opaque `Object` — the call direction is guest `Thread` → `VMScheduler`, never
the reverse. Guest `run()` is entered at task start, in ordinary context, where a lazy
compile is no different from any other first call. So the concern does not apply and Thread
goes with this batch.

`SharedSecrets` keeps its `<clinit>` ordering for the same reason `Unsafe` did: initializers
are never deferred, so `FileDescriptor.<clinit>` still registers
`JavaIOFileDescriptorAccess` at load, before `NioSocketImpl` reads it. Only the accessor
bodies defer.

**Increment 6 — shrink `eagerKept`: the charset/buffer/fd data layer. DONE, PI-VALIDATED
(PR #107).** Real Pi 4: `socket connected`, HTTP 200 OK with the full body (`bytes=829`),
clean return.

**The PR #71 conclusion is superseded.** A first Pi run of this image failed at
`connect()` — which looked like #71 repeating. It was not: a diagnostic re-flash (the same
image plus `LAZY_TRACE`) ran clean AND traced the suspect code working on hardware —
`String.encodeUTF8`, `StringCoding.countPositives`, `Charset.defaultCharset`,
`ByteBuffer.put`/`get`/`address`, `SharedSecrets.set`/`getJavaIOFileDescriptorAccess` — and
a re-run of the unmodified image passed too. The failure was a transient TCP connect
(a dropped SYN lands in exactly that path). So #71 was the lazy-path bugs fixed in
increments 1–2, not an inherent conflict with the denylist mechanism.

**Two debugging lessons worth keeping.** (1) TRAPWIRE index 3 is
`jdk/internal/util/Exceptions.filterNonSocketInfo` — the exception *message formatter*,
reached only from a `NioSocketImpl` throw site. That trap means "connect failed, then tried
to describe why", never "a lazy class was called". (2) Because QEMU has no CYW43, its
healthy ending is that same trap, so **a regressed Pi run and a healthy QEMU run are
byte-identical**, down to the pc chain. One red Pi run on the socket path is not proof —
re-run the unmodified image before bisecting.

Off the list:
`java/nio/` (the `ByteBuffer` overlay, `Charset`), `sun/nio/cs/` (the `UTF_8`/`ISO_8859_1`/
`US_ASCII` overlay singletons) and `java/io/FileDescriptor`. `sun/nio/` narrows to
`sun/nio/ch/`, so the dispatcher stack stays eager while the data layer under it goes lazy.

**This is the PR #71 retry.** That batch — charsets plus `IOStatus` — was the one that
regressed on real hardware with `DENYLIST TRAP: call into a pruned class` at
`NioSocketImpl.connect`, and it is why the widening arc stopped at 40 classes. Two
root-cause fixes have landed since, either of which could have been that failure: a lazy
compile's own relocs were never patched (increment 1), and an inherited static never found
its cell (increment 1) or a baked body's native never resolved (increment 2) — all three
produce exactly a call that resolves to nothing and gets trap-wired. Retrying the charsets
now tests that directly. `IOStatus` stays behind for the `sun/nio/ch/` increment.

**Increment 7 — shrink `eagerKept`: the invoke shims. DONE, PI-VALIDATED (PR #108).**
Real Pi 4: `socket connected`, HTTP 200 OK with the full body (`bytes=828`), clean return.
`VarHandle` 1 cell, `MhUtil` 3, `MethodHandles` 1, `Lookup` 0, `ExtendedSocketOptions` 1 —
so `Socket`'s signature-polymorphic `STATE.getAndBitwiseOr` / `IN`/`OUT.compareAndSet`
resolved by name into a *deferred stub* and compiled on first call, on a live connection.
Off the list: `java/lang/invoke/`
(the `VarHandle` and `MethodHandles` overlays), `jdk/internal/invoke/` (`MhUtil`) and
`sun/net/` (the `ExtendedSocketOptions` no-op overlay, the `PlatformSocketImpl` interface).

The invoke shims looked like the one place laziness could collide with a *compile-time*
mechanism, since `VarHandle`'s call sites are signature-polymorphic — `Socket` calls
`getAndBitwiseOr:(Ljava/net/Socket;I)I` while the overlay declares
`(Ljava/lang/Object;I)I`, so the normal name+descriptor match misses and
`vtableSlotOf` resolves those ops **by name** (`varHandleSlotByName`), with
`markReachable` seeding them so their TIB slots get filled. Neither part depends on when
the body compiles: slot numbering comes from the vtable registry built at phase A, and
seeding marks the method reachable so the deferral path still installs a stub in the slot.
Deferral just makes that slot's contents a stub instead of a body, which is the ordinary
Stage-2 shape.

**Only `java/net/` + `sun/nio/ch/` (and `java/lang/Object`) remain.**

**Increment 8 — `eagerKept` down to one class: the socket-native stack goes lazy. DONE,
PI-VALIDATED (PR #109).** Real Pi 4: `socket connected`, HTTP 200 OK with the full body
(`bytes=828`), clean `close()` and return. The whole stack ran metadata-only — `Net` 49
cells, `IOUtil` 27, `Socket` 8, `NioSocketImpl` 6, `IOStatus` 6, `NativeThread` 5,
`InetAddress` 5, `Inet6Address` 10 — and no trap fired on `close()`, which is where the
`lambda$closerFor$0` fix had to hold. Off the
list: `java/net/` and `sun/nio/ch/` — `Socket`, `SocketImpl`, `SocksSocketImpl`,
`DelegatingSocketImpl`, the `InetAddress` family, `NioSocketImpl`, `Net` (49 cells),
`IOUtil` (27), `NativeThread`, `IOStatus`, `SocketDispatcher`, `Util`,
`SocketOptionRegistry`. **`java/lang/Object` is all that remains**, and it stays for a
structural reason rather than caution: its 9 virtuals are the prefix of *every* vtable in
both worlds, so those slots are what writer-baked and loader-compiled code agree on.

**Two bugs this increment exposed, both invisible until the socket stack itself was lazy:**

1. **A reloc into a celled static had no resolution tier.** `patchRelocs`' resolver
   (`globalBufByRef`) scans the method registry and the super chain, but a metadata-only
   class's celled statics are never eagerly compiled *or registered*, so both miss and the
   site is trap-wired. The one that bites is `NioSocketImpl.lambda$closerFor$0` — an indy
   thunk whose target is a synthetic static, and the lambda *is* the Cleaner action that
   `close()` runs, so it would have fired on hardware while QEMU (which never connects)
   stayed silent. Fixed by a last tier, `dlStubByRef`: the phase-A cell holds callable code
   (the stub, or the body once first-called), which is exactly what `bufBySigU` already
   does for bake stubs. Confirmed by the trap table returning to main's contents.
2. **Lazily compiled bodies were invisible to stack traces.** `printFrameAt` names a PC by
   the nearest *registered* buffer at or below it, and a fresh lazy body is not in the
   registry — so the whole socket stack reported as `java/lang/InternalError.<init>` with
   six-digit offsets. `rememberLazyBody` now updates the method's registry entry in place
   (or creates one for a celled static), which also lets later direct calls link straight
   to the body instead of through the stub. Traces read correctly again.

**Increment 9 — lazy, full stop: the prefix gate goes. DONE, PI-VALIDATED (PR #110).**
Real Pi 4: `socket connected`, HTTP 200 OK with the full body (`bytes=828`), clean return —
with `phaseA: 1 cells … demo/NetDemo` in the log, i.e. the demo class itself metadata-only
and its `main` resolved through the new cell lookup. `stage2Gated` no longer requires a
`java/`/`jdk/`/`sun/` prefix, so **every** demand-loaded class is metadata-only — demos and
plugins included — with `java/lang/Object` the sole exception. The prefix was scaffolding
from when laziness was gated to a handful of named java.base utilities; there was never a
reason for guest code to be the eager one.

It exposed the same missing tier a third time, now at the launcher: `globalMethodBuf`
looked up `main(String[])` in the method registry, and a celled static has no registry
entry, so the image booted to `launch: no main(String[]) in demo/NetDemo`. Same fix shape
as `dlStubByRef` and `bufBySigU`'s second tier — `dlStubByName` reads the cell, which holds
callable code either way. Three sites have now needed this (bake-stub resolution, reloc
patching, launcher lookup); a future cleanup should collapse them into one resolver.

### What "retire the eager loader" actually means

The staged plan said stage 5 ends by deleting `markReachable`/`resetLoader`/`patchRelocs`/
the `MAX*` caps. That wording conflated two things which turn out to be independent:

- **Eager whole-closure compilation** — gone. No ordinary method body is compiled at load
  any more; every one compiles on first call. That is the part stage 5 was for.
- **The demand-load batch machinery** — still load-bearing, and not because of eagerness.
  `resetLoader` allocates the per-batch tables and reclaims the batch heap; `markReachable`
  prunes *which classes get pulled* (a MAXBLOB concern, not a compile concern);
  `patchRelocs` still resolves the cross-class calls of the one thing that is still
  compiled at load — `<init>` and `<clinit>` — and `patchRelocsFrom` serves every lazy
  compile. The `MAX*` caps are just table sizes.

So the honest end state is that the eager *compiler* is retired while the batch *loader*
remains the architecture. What is genuinely dead is the staging scaffolding: `LAZY_TIB`,
`LAZY_COMPILE`, `LAZY_DEFER`, `LAZY_PHASEA` and `LAZY_STATIC` are all compile-time false,
with `lazyArmTib`/`buildLazyStub`/`lazyArmCompile`/`deferrable` unreachable behind them, and
`LAZY_STAGE2` is now always true. Deleting those — and folding the three cell-lookup sites
into one resolver — is the next increment.

**Increment 10 — delete the staging scaffolding. DONE, PI-VALIDATED (PR #111).** Real Pi 4:
`socket connected`, HTTP 200 OK with the full body, clean return — identical behavior, as a
pure deletion should be. Pure cleanup, no behavior change: the
QEMU boot output is byte-identical to increment 9's. Removed the five compile-time-false
flags from the 1a/1b/1c arc — `LAZY_TIB`, `LAZY_COMPILE`, `LAZY_DEFER`, `LAZY_PHASEA`,
`LAZY_STATIC` — along with everything unreachable behind them (`lazyArmTib`,
`buildLazyStub`, `lazyArmCompile`, `deferrable`, `lazyCellFor`, `classRegByNameAt`, and
1c's registry-scan fallback in `lazyStaticCell`). `LAZY_STAGE2` was always true, so its
conditions collapse: `armPhaseACells` runs unconditionally, `mDefer` is always allocated,
and `sizeMethod`/`emitMethod` test the flag alone. `LAZY_TRACE` stays — it is a real
debugging tool, and it earned its keep twice in this arc. Net −230 lines.

Those flags were the strangler scaffolding: each staged one property of compile-on-first-
call (self-modifying dispatch, context restore, the offset table, genuine deferral,
metadata-only classes) behind a default-off switch so the shipped image stayed byte-
identical until the step was proven. With laziness unconditional they only described
history, which is what PLAN.md is for.

**Increment 11 — one cell resolver, and the status catches up. STAGE 5 COMPLETE,
PI-VALIDATED (PR #112).** Real Pi 4: `socket connected`, HTTP 200 OK with the full body
(`bytes=825`), clean return — so all eleven increments are hardware-validated, not just the
ten before it. The cell
lookup had grown four copies as each caller discovered it needed one (bake-stub resolution,
reloc patching, the inherited-static walk, the launcher). Three of them key on
`(base, offset)` runs and now share `dlCellOf`; the fourth keys on literal `byte[]`s, so it
keeps its own comparison but sits beside them. QEMU output byte-identical again.

`CLAUDE.md`'s status section is updated to describe the finished shape rather than the
widening arc, including the two things a future session most needs: that the batch loader
is not the eager compiler, and that a healthy QEMU log is byte-identical to a failed Pi
connect.

### Stage 5 in one paragraph

Ten increments, each Pi-validated on real hardware, moved the loader from "compile the
whole reachable closure at load" to "compile each method the first time it is called".
The order was: flip the default and keep an `eagerKept` exception list (#102), then empty
that list a prefix at a time — reflection and Throwable (#103), reference/cleaner/event
(#104), concurrency and `Unsafe` (#105), `System`/`Thread`/access shims (#106), the
charset/buffer/fd data layer (#107), the invoke shims (#108), the socket-native stack
(#109) — then drop the last restriction so guest code is lazy too (#110), delete the
staging scaffolding (#111), and unify the resolver (#112). Five real bugs surfaced, all of
them latent under eager compilation and four of them invisible to QEMU. `java/lang/Object`
remains the single eager class, for a structural reason that is unlikely to change.

### Lazy initialization (arc started 2026-08-19)

Stage 5 made every method *body* compile on first call. Class *initialization* is still
eager: `runClinits()` runs every enqueued `<clinit>` at batch end, in dependency order, so a
program pays for initializers it may never need. JVMS 5.5 says a class initializes on first
**active use** — `new`, a static field access, a static method call, reflection — and that
is what this arc moves toward.

**The trigger set is the whole problem.** Three of the four triggers have a natural hook in
this VM and one does not:

| trigger | hook |
|---|---|
| static method call | **free** — every static already routes through a phase-A cell whose first call enters `lazyCompile` |
| virtual call | **free** — same engine, via the TIB deferral stub |
| `new C` | needs a barrier emitted at the `new` site |
| `getstatic`/`putstatic` of C's field | **no hook** — the address is resolved at compile time and the load is direct |

So the correctness rule for any class made lazy-init today is: *nothing outside the class
may read its statics*, because a cross-class `getstatic` has no barrier to fire.

**Increment 1 — the barrier, on two classes. DONE, PI-VALIDATED (PR #114).** Real Pi 4:
`lifecycle OK 160 (+2 lazy-init pending)`, then `clinit-lazy jdk/internal/ref/CleanerFactory`
immediately before `socket connected` — the barrier firing on the live socket path, with the
`close()` that follows using the cleaner it built. HTTP 200, `bytes=829`, clean return. `ensureClinit(reg)` runs a gated class's
pending initializer from `lazyCompile`, before the compile context is restored (the
initializer's own calls re-enter `lazyCompile`, and each nested compile clobbers `g*`).
`runClinits` leaves gated initializers pending; the lifecycle sweep leaves those classes at
`ST_INSTANTIATED` and reports them as `lifecycle OK <n> (+<k> lazy-init pending)`, since
pending is a legitimate state while short-of-INSTANTIATED still is not.

The two gated classes were chosen to show both outcomes and to satisfy the statics rule:
`jdk/internal/ref/CleanerFactory` (its `commonCleaner` is private, read only by `cleaner()`)
initializes **late** — NetDemo's boot log shows `clinit-lazy jdk/internal/ref/CleanerFactory`
after `lifecycle OK`, when the socket registers its cleaner. `java/lang/ConditionalSpecialCasing`
(private tables, read only by its own case-mapping methods) is **never** initialized in
NetDemo, and its absence is visible: the `baked java/lang/Integer.valueOf` and `bakeresolve
java/lang/Integer.hashCode` lines that used to appear at the end of boot are gone, because
they were that initializer boxing code points into its `HashMap`.

**A bug the first run caught.** `ensureClinit` initially ran *any* pending initializer for
the class rather than only a gated one. A lazy compile can happen **during** `runClinits`
(an initializer calling a deferred method), so the barrier pulled `StandardProtocolFamily`
and `ArraysSupport` ahead of the initializers they depend on. Non-gated classes must stay
under `runClinits`' dependency-ordered control; the gate check is what keeps the two regimes
from interleaving.

**Increment 2 — close the other three triggers; widen to 10 classes. DONE, PI-VALIDATED
(PR #115).** Real Pi 4: `lifecycle OK 152 (+10 lazy-init pending)`, HTTP 200, clean return.
Hardware showed one more than QEMU could: `StandardProtocolFamily`, `CleanerFactory` and
`TimeUnit` initialize around `connect`, and **`ExtendedSocketOptions` initializes during
`close()`** — the SO_LINGER path QEMU never reaches. Four of ten initialize; six never run. The remaining triggers
are covered without emitting a single runtime barrier instruction, by exploiting the fact that
almost all code now compiles lazily:

- **`getstatic`/`putstatic` and `new`** resolve their owner at *compile* time
  (`staticAddr` → `globalStaticAddr`, `tibOfClass`). Initializing the owner when the
  *referencing method is compiled* is still strictly before that method can *run*, so
  `noteInitNeeded` records the owner during the compile and `drainPendingInit` initializes it
  immediately after — inside `lazyCompile`, after the buffer is memoized so an initializer may
  call straight back in. Recording is gated on `lzCompiling`, because a load-time compile (an
  initializer) must leave ordering to `runClinits`. This is conservative — it initializes even
  if the branch holding the access never executes — but sound and free.
- **An eager initializer depending on a lazy class**: `runClinits` has already passed that
  class over, so `clinitDepBlocked` now calls `ensureClinit` on each dependency it walks. That
  is exactly the JVMS rule (the dependent initializer's use of C triggers C), and without it
  the dependent would read unset statics.

With all four triggers covered, the gate widens from 2 to 10: `StrictMath`, `Locale`,
`HashMap$TreeNode`, `HashSet`, `CharacterDataLatin1`, `StandardProtocolFamily`, `TimeUnit`,
`ExtendedSocketOptions` join the original two. NetDemo reports `lifecycle OK 152 (+10
lazy-init pending)` and initializes **three** of them on demand — so seven initializers that
used to run at every boot now never run at all.

**Increment 3 — invert: lazy init by default. DONE, PI-VALIDATED (PR #116).** Real Pi 4:
`lifecycle OK 146 (+16 lazy-init pending)`, HTTP 200, clean return — and hardware fired
**eleven** barriers where QEMU fires seven. The extra four are all past `connect`:
`Preconditions` as the socket connects, `Arrays` during the read, and
`StandardSocketOptions`, `Boolean` and `ExtendedSocketOptions` during `close()`. So of the 16
deferred initializers, 11 run on demand and 5 never run at all. The same strangler shape stage 5 used eleven
times. `lazyClinitGated` becomes `!clinitEagerKept`, so every class initializes on first
active use unless it is pinned.

What stays pinned is the **socket bring-up order, which is hand-tuned and not derivable from
bytecode**. The load-bearing case is `FileDescriptor.<clinit>`: it registers the
`JavaIOFileDescriptorAccess` that `NioSocketImpl` and `NativeDispatcher` read back through
`SharedSecrets` — an edge no dependency scan can see, because it runs through a registry
rather than a direct reference, which is why `runClinits` special-cases it to run *first*. A
barrier firing on first use cannot reproduce that ordering. The dispatchers and `Socket` sit
in the same hand-ordered bring-up; `Unsafe` and `ArraysSupport` supply the array offsets that
much of java.base reads through statics.

**The demo suite caught the trigger this arc had not implemented: reflection.** Inverting made
two demos fail in the same way — ForNameDemo lost its `Plugin.<clinit> ran, marker=42` line and
EnumReflectDemo lost its constants. Both are JVMS 5.5 active uses that no compile-time hook can
see: `Class.forName(name)` must initialize, and `Class.getEnumConstants()` reaches `values()`
reflectively, which reads the `$VALUES` that only the initializer sets. Fixed at the two
funnels — `forNameMirror` (both the already-loaded and incrementally-loaded paths) and
`methodResolve` (the resolver behind `getDeclaredMethod`/`Method.invoke`), plus `allocInstance`
for reflective instantiation. This is the value of a broad demo suite over a single acceptance
test: a purely socket-shaped check would have shipped it.

NetDemo: `lifecycle OK 146 (+16 lazy-init pending)`, of which **seven** initialize on demand —
`java/lang/String`, the three `sun/nio/cs` charsets, `StandardProtocolFamily`,
`CleanerFactory`, `TimeUnit` — and nine never run. Against the 29 initializers this batch used
to run at every boot, that is 13 still eager plus 7 fired on demand, four of them after
`launch`.

**Increment 4 — name the invisible edge; `clinitEagerKept` goes empty. DONE, PI-VALIDATED
(PR #117).** Real Pi 4: `lifecycle OK 133 (+29 lazy-init pending)`, HTTP 200, `bytes=827`,
clean return — with **seventeen** barriers firing where QEMU fires eleven. The ordering the
increment exists to preserve is visible in the log: `clinit-lazy java/io/FileDescriptor`
immediately before `clinit-lazy java/net/Socket`, and well before `sun/nio/ch/NioSocketImpl`.
`SocketDispatcher` initializes as the GET is sent, and `StandardSocketOptions`, `Boolean`,
`ExtendedSocketOptions` and `UnixDispatcher` during `close()`. Of the 29 deferred initializers,
17 run on demand and 12 never run at all. Increment 3 kept the
socket bring-up eager because one of its dependencies is invisible: `FileDescriptor.<clinit>`
registers a `JavaIOFileDescriptorAccess` into `SharedSecrets` that `NioSocketImpl` and the
dispatchers read back at their own `<clinit>` time. The edge runs through a registry, so it is
in neither class's bytecode — no dependency scan and no compile-time barrier can find it, and
`runClinits` encoded it as "run FileDescriptor first, unconditionally".

Keeping a dozen classes eager to preserve one edge is over-approximation. `initPrereq` states
the edge instead: initializing any `sun/nio/ch/` or `java/net/` class initializes
`FileDescriptor` first. With that, the whole socket stack initializes on demand and
`clinitEagerKept` returns `false` — **every class in the system now initializes on first active
use.**

Worth recording how the first version of this was wrong-but-passing. With the list shrunk to
`FileDescriptor` alone, QEMU looked perfect — but `FileDescriptor` never appeared as a lazy
init, because `runClinits`' fd-first special case still ran it eagerly and bypassed the skip
entirely. `initPrereq` was dead code justified by a plausible story. Making the fd-first
pre-run respect the gate is what turned it load-bearing, and the log now proves it: `clinit-lazy
java/io/FileDescriptor` appears immediately *before* `clinit-lazy java/net/Socket`.

NetDemo on QEMU: `lifecycle OK 133 (+29 lazy-init pending)`, eleven fired — including
`FileDescriptor`, `Socket`, `NioSocketImpl` and `DelegatingSocketImpl`, all of which used to run
at boot.

### GC metadata — precise tracing (arc started 2026-08-20)

The mark-sweep collector is conservative in *both* directions, and only one of them has a good
reason to be. **Roots** — the stack, the statics region, the secondary arenas — must stay
conservative: there are no stack maps, so a JIT'd frame's live references are only findable by
probing every word (which is also why objects are never moved). The **trace** side has no such
excuse. Today `gcCollect`'s fixpoint loop calls `markRange(o + 16, o + size)` on every marked
block, so a `byte[]` is probed word by word exactly like an `Object[]`, and any payload word that
happens to hold a value in `[Heap.BASE, heapTop)` on an 8-byte boundary that is a real block base
retains that block.

Two costs follow. **Time:** a live 64 KiB `byte[]` costs 8192 pointer probes *per trace round*,
and the trace runs to a fixpoint — the classfile blobs, string values and JIT scratch that dominate
this heap are exactly the blocks that can hold no references at all. **Precision:** a `long` field
holding an address-shaped value (a code-buffer address, an `fd`-like handle, a hash) retains a dead
object, and everything that object reaches, for the rest of the run.

The VM already knows the answer for every block it allocates — the object model carries element
sizes, array Types and (for classes) a field layout. This arc puts that knowledge where the
collector can use it. It explicitly does **not** make roots precise and does **not** move objects;
those need stack maps, and this is their prerequisite, not a substitute.

**What the header word already tells you.** Three block shapes live in this heap, discriminated by
the word at `+0` with no new metadata at all:

| word at +0 | shape | payload |
|---|---|---|
| `0` | raw `Heap.allocData` struct — Type, TIB, itable dir, imap, statics block, classfile copy | starts at +16, no type at all |
| `<= MAX_RAW_ARRAY_TIB` (1/2/4/8) | raw array: the word IS the element size | length @16, elements @24 |
| a pointer | a typed object: TIB[0] is its Type (array Types carry `ARRAY_TYPE_TAG` + element size) | fields @16, or array elements @24 |

**Increment 1 — type-directed dispatch; arrays become precise. DONE, PI-VALIDATED.** Real Pi 4,
`main=demo/GcDemo`: `churnMB=625 live=32 intact=32`, then `gc: collections=3
lastProbes=0x0D8F62 lastReclaimed=0x0B8FAFD8` — the probe count matches QEMU's to the digit, and
the rotating live set survived all three collections with the narrowed scan. A NetDemo flash of the
same build joined WPA2, returned HTTP 200 with `bytes=828`, and moved none of the boot asserts. `scanBlock` replaces the blanket
`markRange` in the trace loop and routes by the table above. An array whose elements are narrower
than a word cannot hold a reference, so its payload is skipped *entirely*; word-wide elements are
scanned over the element range only. Element **size** alone decides — a `long[]` is still scanned
like an `Object[]`, because an array Type's element-Type slot reads 0 both for a primitive element
and for a reference element the loader could not resolve, and soundness beats precision until that
distinction is verified. Scalars stay conservative (that is increment 2). The `gc` log line gains
`probes=` — candidate words examined per collection — which is the metric the whole arc moves.

Measured on QEMU with `main=demo/GcDemo` (625 MB of churn through the arena, three collections),
building the same tree twice with only the dispatch flipped:

| collection | probes, conservative | probes, type-directed | |
|---|---|---|---|
| 1 | 2,533,551 | 888,650 | −64.9% |
| 2 | 2,705,616 | 888,677 | −67.2% |
| 3 | 2,631,885 | 888,674 | −66.2% |

`walked=19282 marked=3790 freed=15492 bytes=194,490,488` and `churnMB=625 live=32 intact=32` are
**identical** in both builds — the same objects live and die, only the work to decide it changed.
The precise counts barely move between collections because what remains is the fixed root scan
(stack + statics + secondary arenas); the part that varied *was* the `byte[]` payloads.

**Increment 2 — scalar reference maps. DONE, PI-VALIDATED.** Real Pi 4, `main=demo/GcDemo`:
`churnMB=625 live=32 intact=32`, then `gc: collections=3 lastProbes=0x0D12C7 roots=0x2EF
heap=0x0D0FD8 nomap=0x0AF1` — `roots` matches QEMU's 751 exactly and `nomap` lands within three
blocks of it, so both worlds traced the same shapes the same way. The Type node grows two words
(`TYPE_REFMAP_OFFSET` at 64/72, `TYPE_SIZE` 64 → 80): bit 0 the "computed" marker, bit `1+slot` set
when that field slot may hold a pointer. The writer computes it (`ClassFile.refMap`) over the
chain-aware slot numbering — whole super chain first, root-most class first — and the loader
rebuilds it for metal-only classes as `super's map | own bits` (phase A is super-first, so the
super's Type is already built; same shape as `buildDisplay`/`buildImplBitmap`). Baked classes get
it free, because the loader already **adopts** the writer's Type node: one map per class across
both worlds.

**The rule is "may hold a pointer", not "is a Java reference": `L`, `[` AND `J`.** This VM keeps raw
addresses in `long` fields — `RVMClass.tib`, `.type`, `.statics`, `.base`, and the same pattern in
`RVMMethod`/`DynLink`. Those words are the *only* root a metal-built TIB, Type or statics block has,
so a map that honoured Java types would have swept the live metadata out from under the running
program on the first collection. Everything else (`I`/`Z`/`C`/`B`/`S`/`F`/`D`) is skipped, and that
is where the precision comes from: an `int` holding a size, an offset or a hash lands in
`[0x04000000, heapTop)` easily, and each one was a plausible false root.

Every degradation is toward *conservative*, never toward under-scanning: a class wider than 126
slots, a chain with an unresolvable ancestor, a zeroed Type (lambda Types, the Types `SelfBuild`
emits) — all publish no marker, and the collector scans the whole payload. `test/classfile/RefMapTest`
pins the bits against hand-written layouts (13 checks), `vm/RVMClass` among them, because a wrong
bit here is not a slow collection but a freed live object.

Measured on QEMU (`main=demo/GcDemo`, map honoured vs map ignored, same build otherwise), now
splitting the metric — and the split is the finding:

| | probes | roots | heap (trace) | nomap (blocks scanned blind) |
|---|---|---|---|---|
| map ignored | 888,860 | 751 | 888,109 | 10,027 |
| map honoured | 856,793 | 751 | 856,042 | 2,804 |

Roots are **751 words** — the conservative half this arc can never fix is a rounding error, and
essentially the entire cost is the trace side. Blocks scanned with no metadata at all fell 72%,
while probes fell only 3.6%, and those two numbers together point straight at increment 3: what
remains is a few thousand raw `allocData` structs, but they include the 4 KiB imaps, so ~97% of the
surviving probes are in blocks that have no type at all rather than in mapped objects.

**Increment 3 — the measurement retired the planned increment 3. DONE, PI-VALIDATED** (real Pi 4,
`main=demo/GcDemo`: `churnMB=625 live=32 intact=32`, then `gc: collections=3 lastProbes=0x45F76
roots=0x2F1 heap=0x45C85 nomap=0x3DC` — every metric identical to QEMU's digit for digit). The plan was a kind tag for raw
`allocData` structs (status-word bits 1–2 are free beside the mark bit), on the inference that those
2,804 unmapped blocks held most of the remaining probes. Instrumenting the residue *before* writing
the tag showed the inference was wrong, and by two orders of magnitude:

| trace probes, after increment 2 | | blocks |
|---|---|---|
| word-element arrays | 828,327 (96.8%) | 1,182 (avg 701 words) |
| mapped scalars | 21,477 (2.5%) | — |
| **untyped blocks** — the kind-tag target | **6,238 (0.7%)** | 2,804 (biggest 824 bytes) |

The unmapped blocks are *numerous but tiny*; the cost is in reference arrays — the loader's registry
tables. And `rounds=3`: the trace was a **fixpoint**, re-walking the entire heap and re-scanning
every marked block until a pass marked nothing new, so each live table was probed three times.
Kind tags would have bought 0.7%; scanning each block once buys the multiplier.

So increment 3 is a **mark worklist**. A block is pushed the moment it is marked (`tryMark`) and
scanned once when popped; scanning pushes what it finds, so the drain ends exactly when the
reachable set closes. The queue is 213k entries in the scratch window between the JIT unwind tables
and the heap cells — outside the managed heap, because a collector must not allocate while
collecting. Overflow is a *speed* fallback, never a correctness one: the old fixpoint loop is kept
and runs if the queue ever fills, with the queue refilling underneath it so later passes still take
the fast path.

Result on QEMU (`main=demo/GcDemo`): trace probes **856,042 → 285,829 (−66.6%)**, matching the
`rounds=3` prediction exactly, and the three whole-heap walks (19,282 blocks each) are gone with
them. `lastReclaimed` is unchanged and `churnMB=625 live=32 intact=32` still holds — same
reachability, a third of the work. `demo/LispDemo` is the second workload, deliberately unlike
GcDemo's flat `byte[]` churn: cons cells and environment frames, a deep pointer graph of exactly the
shape a worklist traverses differently from a heap sweep. It still answers `(fact 10) = 3628800`,
`(fib 18) = 2584`, `(sum 100 0) = 5050`, `(twice inc 40) = 42`. The kind tag stays available for later; at 0.7% it is a cleanup,
not an increment.

**Increment 4 — prove the precision, not just the speed.** A demo whose object is retained only by
an address-shaped `long` field: conservative tracing keeps it forever, precise tracing frees it —
the difference visible in `freed=`/`bytes=` rather than argued from the code.

### The demo suite's fault — three bugs behind one data abort (2026-08-20)

With `ramfs/etc/init` removed the boot falls through to the **demo suite**, the only path that runs the
whole battery (float/double, natives, collections, WordCount, GC, Lisp) in one image. It had rotted into
a data abort in `Loader.u1` — a two-line helper — reported as `(no registered method)` with a raw
address, and every arc's validation had quietly narrowed to one manifest program at a time.

It was three independent bugs stacked, each invisible until the one before it was fixed.

**1. `java/lang/Object` was not in every batch.** The boot log had been printing `vtparity
java/lang/String DIFF count 92/86` for three separate demos and carrying on. Making the parity check
name the *missing* slots turned it into a diagnosis in one run: `getClass`, `wait()`, `wait(J)`,
`notify`, `notifyAll`, `clone` — precisely Object's virtuals that String does not override. Twelve of
the 28 batch drivers seeded Object's blob by hand with a comment about canonical slots; the other
sixteen did not, so their classes flattened vtables from slot 0 while *adopting* a baked Type whose
numbering assumes Object's nine-slot prefix. `ensureObjectBlob()` in `loadAll` makes the invariant the
loader's rather than each driver's.

**2. `Object.<init>` then became a real call.** Every constructor starts with
`invokespecial java/lang/Object.<init>`, and `isRealSpecial` answered "real call" for any *registered*
class. Object had never been registered in those batches, so the call had been dropped by accident, not
by rule — and with (1) fixed it began resolving to a body that reachability pruning had, correctly, not
compiled. `java/lang/String$CaseInsensitiveComparator.<init>` sized a call to a target that does not
exist. `Object.<init>` is empty by definition and the writer's compiler already skips it; `isRealSpecial`
now says so explicitly.

**3. The lazy tables outlived the batch reclaim.** `resetLoader` rebuilds every registry — `clTab`,
`rgTab`, `sgTab`, the reloc tables — but not `dlTab`/`lzTab`, the phase-A dynamic-linking cells. Those
objects live in the demand-load heap that the same reset *rewinds and zeroes*, so the next batch's
`dlCellOf` walked the previous batch's `DynLink` entries, read `blob = 0` out of the zeroed memory, and
compared a name against a classfile at address 0 — landing, a few frames later, on a Utf8 byte pattern
used as a pointer. They are now dropped inside the reclaim itself, where the memory backing them dies;
batches that run *before* reclaim is armed keep their still-valid cells, which the philosophers' surviving
tasks dispatch through.

**Pi-validated (2026-08-20).** The suite image on a real Pi 4 runs the whole battery with no
`vtparity … DIFF`, no `FAULT` and no `STALE REGISTRY REF` across ~40 batches: the float/double *and*
natives demos complete (`Float.floatToRawIntBits(1.5f) = 1069547520`, all three `arraycopy` cases),
`words=25 distinct=16` from WordCount, `é`/`€` through the charset path, `churnMB=625 live=32
intact=32`, `lisp: evals=600 result=610 stable=1` with 5 collections mid-computation, then the WiFi
finale bringing up the CYW43 and joining WPA2. Hardware also exercises what QEMU cannot: real timer
ticks (`ticks/core: c1=50 c2=50 c3=50`, `sched: 89 preemptions`, against 0 of each under emulation)
and the philosophers running on real threads.

Worth noting for the GC arc: its numbers hold *inside the suite*, which is a harder test than the
isolated GcDemo manifest — same image, forty batches, live loader metadata throughout. Pi
`probes=0x45B0E roots=0x2CB heap=0x45843 nomap=0x3AF` against QEMU's standalone `0x45F76 / 0x2F1 /
0x45C85 / 0x3DC`, and the first collection reports `reaped=0x6` — the six dead philosopher tasks'
stacks, a path the standalone demo never reaches.

**The diagnostics were the actual deliverable.** Each bug was found by making the VM say something it
had not been saying, and all four survive in the tree:

- the fault reporter walks the faulting stack (`reportFaultStack`) and names frames through the **image**
  symbol table, not just JIT'd ones — `vm/Loader.u1(Loader.java:215)` instead of `(no registered method)`;
- it prints **which class the loader was compiling**, which is the first question worth asking when the
  fault is inside a shared helper;
- `vtparity` names the missing slots on a count mismatch, instead of printing `92/86` and continuing;
- `utf8EqAt` halts *named* on a zero blob pointer (`STALE REGISTRY REF`) rather than reading a wild
  address — the guard is called before the load, while `x30` still identifies the caller, which is how
  bug 3 was found after bugs 1 and 2 had each moved the crash somewhere new.

### Code arena: compaction, or the cheaper thing? (arc started 2026-08-21)

The metadata-lifetime arc ended with one lever untried: the code arena sits at **6.68 MB against 2.44 MB
live**, and increment 10 established that a trailing-run trim recovers nothing because live methods are
scattered. The stated next step was compaction — move code, patch every reference to it. That is the
hardest thing in this area: branch targets, TIB slots, phase-A cells, `long` fields, JIT unwind tables
keyed by address, **return addresses on the stack**, and the scheduler's saved PCs for parked tasks.

**Increment 1 — measure the gap before building the hard thing.** Compaction is the right tool only if the
arena is *fragmented*. If the free list is simply holding capacity the program reached at peak demand,
moving code recovers nothing and the work is wasted. So: per-allocation counters (served from the free
list vs forced to grow the arena, and the bytes those growths added) plus a free-list survey (blocks,
bytes, largest block, blocks under 256 B), printed on every reclaim.

**The answer is fragmentation, and it is stark** (QEMU, end of the suite; clean run, 3,211 lines, 0
faults, `churnMB=625 live=32 intact=32`, `gc during lisp: collections=5`):

| | |
|---|---|
| arena / used / live | 6.68 / 2.51 / **2.44 MB** |
| free list | **4.25 MB in 2,810 blocks** |
| largest free block | 1.17 MB |
| free excluding that one | 3.08 MB in 2,809 blocks — **average 1,149 B** |
| blocks under 256 B | 2,462 = **88% of all free blocks** |
| allocations served from the list | 16,415 (94%) |
| allocations that had to grow the arena | **1,021**, average request **6,878 B** |
| bytes those growths added | **6.70 MB — the entire arena** |

Read the last two rows together: **every byte of the arena was added by an allocation that could not find
a fit**, while 4.25 MB sat free in crumbs averaging about a kilobyte. The requests that fail are the big
ones (~6.9 KB); the free space is shaped wrong for them.

**And the cause was already named in this file.** Increment 5 of the previous arc found that splitting
without coalescing "grinds the heap to fragments" and fixed it — **for the data heap only**. `VMGc`'s data
sweep merges runs of adjacent dead blocks (`runStart`/`runSize`); the code sweep frees each block
individually and **never merges neighbours**, while `takeFreeCode` splits every reuse. Same allocator
mistake, same arena, one side fixed and the other not.

⇒ **Increment 2 is coalescing adjacent free code blocks, not compaction.** It is the fix the evidence
points at, it is a fraction of the work, and it may remove the need for compaction entirely — if merging
those 2,809 crumbs back into multi-KB blocks lets the 1,021 failing allocations find fits, the arena stops
growing at all. Compaction stays on the shelf until a post-coalescing measurement says the *remaining* gap
justifies moving code. One wrinkle to solve there: the registry is in allocation order and splits append
out of order, so merging needs an address-ordered view of a headerless arena.

**Increment 2 — coalescing: the fragmentation is gone, and the arena barely moves.** `Heap.coalesceCodeFree`
merges runs of adjacent free blocks after each sweep, the code-arena counterpart of the data sweep's run
merging. The obstacle was navigation, not merging: the code arena is headerless, so it cannot be walked
block by block, and the registry is in allocation order with every split appending its remainder at the
end, so it is not in address order either. A rebuilt address→index hash map (131,072 slots at
`0x0364_0000`, half full at worst) supplies what the data heap gets free from its status words; the pass
then walks the arena in address order — blocks tile it contiguously — folds each free run into its first
entry, and drops the absorbed entries. It stops rather than guessing if it ever meets an address the
registry does not describe; across a whole suite it never did.

| | before | after |
|---|---|---|
| free blocks | 2,810 | **171** |
| blocks under 256 B | 2,462 (88%) | **68 (40%)** |
| largest free block | 1.17 MB | 1.33 MB |
| free bytes | 4.25 MB | 3.55 MB |
| **arena** | **6.68 MB** | **5.99 MB** |
| allocations forced to grow | 1,021 | 1,233 |
| bytes those growths added | 6.70 MB | 6.01 MB |

**The free list is healthy now — and the arena fell only 10%.** That is the finding, and it is not the one
the increment-1 measurement predicted. Defragmenting removed 94% of the free blocks without removing the
growth: allocations still had to extend the arena 1,233 times.

**Why, and what it means for compaction.** The arena's size is a HIGH-WATER mark, and the water rises
*between* collections. A batch compiles thousands of methods before any sweep runs; whatever free space
existed when the batch started is all it has, and when that runs out the arena grows — no matter how tidy
the free list is. Coalescing (like the trim, like compaction) only acts at collection time, so none of
them can lower a peak set by demand inside a batch.

**PI-VALIDATED (2026-08-22), and hardware recovers TWICE what emulation showed.** Whole battery clean:
no `FAULT`, no `CAP EXCEEDED`, no `STALE`, and — the check that mattered — **no `code coalesce: unmapped
block`**, so the address-ordered walk described every block in the arena across 24 batches.
`churnMB=625 live=32 intact=32`, `gc during lisp: collections=5`, `ExcDemo`'s four frames, WPA2 →
**HTTP 200, 828 bytes**.

| Pi | before (increment 12) | after |
|---|---|---|
| code arena | 6.68 MB | **5.30 MB — a 21% cut, 1.38 MB recovered** |
| free | — | 4.72 MB in **159 blocks**, largest 2.58 MB, 56 tiny |
| `codeUsed` / `codeLive` | 1.82 / 1.75 MB | 0.67 / 0.58 MB |
| reuse / bump / bumped bytes | — | 16,203 / 1,233 / 5.32 MB |

QEMU measured 10%; hardware measures **21%**. The allocation *sequence* is identical on both (16,203 reuses
and 1,233 bumps to the digit), so the difference is which methods are live when each collection runs, not a
different workload. Note also that `codeUsed` fell by more than the arena did: exact-size splits out of big
merged blocks stop carrying the slack that the old crumbs forced allocations to keep. The block-count
collapse (2,810 → 171) is a QEMU-only comparison — increment 1's survey never ran on hardware before
coalescing existed — but the Pi's 159 blocks land in the same place.

**PI-VALIDATED (2026-08-23), whole battery.** No `FAULT`, no `STALE`, no `CAP EXCEEDED`: the full demo
suite, `churnMB=625 live=32 intact=32`, `ExcDemo`'s four frames, `lisp: evals=600 result=610 stable=1`,
WPA2 → DHCP → DNS → TCP → **HTTP 200, 828 bytes**, ending at `(self-build retired; host writer only)`.
So the in-flight-buffer fix and the large-object region both hold on silicon.

Through batch 23 hardware tracks emulation digit for digit — 44.7 MB held, `reuse 1301–1353 / bump 284`.
Three readings differ, and the first is the one that matters:

**CEILING FIXED, Pi-validated (2026-08-23).** With the sweep trimming the region's bump pointer, the Lisp
phase now reaches **37.1 MB against the same 64 MB reservation** — where it previously pinned at 64.0 MB
with nothing left. Peak across the run is 44.7 MB, so there is 30% headroom at the worst moment instead of
zero. `lgTrim` returned **119.0 MB** cumulative and `lgLive` held at **3.72 MB**; hardware and emulation
agree on both to the byte (`0x7709000` and `0x3B9000`). Whole battery clean: no `FAULT`, no `STALE`,
`churnMB=625 live=32 intact=32`, `ExcDemo`'s four frames, `gc during lisp: collections=7`, WPA2 → **HTTP
200, 828 bytes**, ending at `(self-build retired; host writer only)`.

- **(historical) The region hit its ceiling during Lisp.** `largeTop=0x3FFF000` was **64.0 MB — the entire reservation**
  (`LARGE_LIMIT − LARGE_BASE`), with bump at 574. It did not fail: the sweep kept up and allocation
  continued out of the free list. But there is no headroom, and `allocLarge` returning 0 twice is a halt.
  QEMU peaked at 44.7 MB and never showed this. Options, cheapest first: move the split down (96/96 rather
  than 128/64), or trim the large region's bump past a trailing free run — remembering that increment 10
  measured trailing-run trims recovering nothing for CODE, and this region may or may not be the same shape.
- `gc during lisp: collections=7` matches QEMU exactly against the baseline's 5, so those two extra
  collections are real, not an emulation artifact — most likely the young-buffer grace holding a pass longer.
- `gc: collections=0` in the churn line where it read 3. That counter tracks PRESSURE-triggered collections
  in the small arena, and large allocations no longer pressure it; the collections themselves are still
  visible in the `[gc walked=…]` lines. Believed semantic rather than broken — worth confirming, not
  assuming.

**A correction to what this section first claimed.** Two Pi runs showed a garbled counter — `reuse=;115/574`
and later `reuse=:150/1539` — and both times I wrote it off as single-character UART corruption on the
grounds that "printDec cannot produce a non-digit since the four-digit bug was fixed". That fix was made on
the `alloc-size-histogram` branch and never merged; this branch came off `main` and still had the old
four-digit `printDec`, which prints a value ≥ 10000 as one character plus three digits (`':'` is `'0'+10`,
`';'` is `'0'+11`). The readings were the bug, not noise. Corrected values: **11,115 / 574** and
**10,150 / 1,539**, not the 1,115 and 150 I read. The fix is now on this branch too.

The lesson is not about `printDec`. It is that "that cannot happen because I fixed it" is worth one command
to verify, especially when the same anomaly appears twice in the same field.

⇒ **Compaction is not the lever either, for the same reason the trim was not.** It would produce a tidier
arena at each collection and could not touch the 1,233 growth events that set the high-water. The remaining
gap — 5.99 MB arena against 2.54 MB in use — is capacity held against peak in-batch demand, and the levers
that would actually move it are collecting *during* a batch, or compiling less code per batch. Both are
real options; neither is compaction. **The compaction arc closes here**, with the fragmentation fixed as a
genuine (if smaller than hoped) win and the reason recorded.

**Increment 3 — the large-object region. WORKS, one bug open.** The measurements that led here, in order:
counts said small-object crumbs; bytes said large-object near-misses; peak-time state said *32 bytes short
with 5.04 MB free*; adjacency said merging cannot reach those blocks (0 of 70 sampled failures); and
quantising requests in the shared region moved the near-miss up one quantum for 44.8 MB of slack (2,500
failures → 2,392). Each reading overturned the fix the previous one implied.

**Why the shared region could never work.** Free blocks come from sweep-merged runs and split remainders.
In a region shared with arbitrary-size small objects, both are arbitrary sizes, so rounding *requests* to a
lattice the *supply* does not share is futile. Segregate large objects into a region where every block is a
page multiple and merged runs and remainders are page multiples too: demand and supply share one lattice
and exact reuse becomes structural. That is what buddy allocators buy with alignment, bought here with
segregation instead.

Core 0's arena splits at `0x0C00_0000`: small below, ≥16 KiB above, page-quantised, with its own bump
pointer, free list and sweep (`Heap.allocLarge`, `VMGc.sweepLargeRegion`).

| | baseline | large region |
|---|---|---|
| large allocations reusing a block | ~0 | **1,353** |
| large allocations growing the arena | 2,500 | **284** |
| worst failure | 5.04 MB free, largest block 32 B short | — the near-miss cannot form |

**The bug it exposed, and the reason it took four isolation runs.** `drainMarkStack` bounded every popped
object with `o + size <= Heap.PTR_CELL` — the SMALL region's top. Large objects failed that test, so each
was *marked and then silently never scanned*, and anything reachable only through one was reclaimed while
live. It reads as a sanity check ("never scan past a corrupt size") and was one, until a second region
existed; the same bound sat in the fixpoint fallback's walk. Fixed by `regionTopOf(o)` and `scanMarkedIn`.

The isolation that hid the region from the GC faulted *identically*, which is what made the collector look
innocent — with the region hidden, large objects were never marked at all, so their referents died the same
way. **One symptom, two causes**, and the isolation could not separate them. Two of those runs were also
spent on a misread: `blob 0x1` is a flag from `badRead(baseA == 0 ? 0 : 1, ...)`, not an address.

**Both of #136's open questions closed — and both were measurement defects, not VM defects.**

- **`gc: collections=0` was a real gap.** `gcPressure` means "collections forced by allocation pressure"
  and was incremented only in the SMALL arena's slow path; the large region's slow path calls `Magic.gc()`
  for the identical reason and did not count it. The demo printed 0 while its own log showed eleven. Now
  counts both and reads **11**. Half the earlier guess was right — GcDemo's churn did move to the large
  region so the small arena never fills — but the guess stopped one step short of the missing increment.
  Note 11 against a pre-region baseline of 3 is also a REAL behavioural change: a 64 MB region fills sooner
  than a 192 MB arena, so the same churn collects more often. Cheaper collections, more of them.
- **The ~22 stale statics pointers were the probe reporting itself.** Slot `0x110530` is `VMGc.zeroLo` —
  the collector's own field, holding the low bound of the code span that very sweep zeroed for the I-cache
  publish. It points into just-freed code BY DEFINITION. Excluding the collector's own span bounds gives
  **`stale=0/0/0`**: no real stale pointers exist.

  The route there is the useful part. A persist-across-collections test, built expecting "benign transients
  rewritten before use", returned **19 of 20 persisted, 0 rewritten** — reading as *worse* than feared.
  Only naming the slot showed it was the SAME address every time, which is what a rotating instrument field
  looks like and not what scattered stale pointers look like. **When an instrument reports something
  alarming, check whether the instrument is inside the thing it measures.** Both of this arc's false alarms
  were that: a counter blind to a new code path, and a scanner seeing its own bookkeeping.

**(RESOLVED — this paragraph described the in-flight-buffer fault while it was still open.)** With
`RECLAIM_CODE_BY_GC` on, the suite used to fault at 2,990 lines executing zeros in a swept buffer. The cause
was buffers freed while their address was still in flight, fixed by allocate-black (`CODE_YOUNG`), and the
flag has been **on by default since increment 11** and Pi-validated repeatedly since — most recently a whole
battery at 3,373 lines, 0 faults, with the code sweep live throughout. Nothing here is open.

It stood as a stale "Open:" for several commits after the fault died, and I repeated it as a remaining item
in a PR body. A note that describes a bug is worth deleting the moment the bug is fixed; otherwise it reads
as a live hazard to whoever finds it next.

### GC of live metadata — retiring the batch reclaim (arc started 2026-08-20)

M8's "hard problems" named this one: reified metadata becomes permanent heap state the collector must
trace, *replacing the per-batch reset*. Today `resetLoader` rewinds core 0's bump pointer to a watermark
and the code arena to another, zeroing both, because the demand model assumes **nothing survives a batch
but the image**. That assumption is exactly what stops joe-ng loading a class into a running program
without a batch boundary — and it is what bit in the demo-suite arc, where `dlTab`/`lzTab` outlived the
memory underneath them.

Three pieces, and they are not equally hard:

- **The data heap** is already reachability-ready. Every loader registry is an ordinary heap object held
  from a `Loader` static, and every one is *replaced* at batch start, so the previous batch's classes,
  TIBs, Types, itables, statics blocks and classfile copies are unreachable by construction. The
  collector could take them.
- **The code arena** is not GC-managed at all: buffers live at `0x0200_0000..0x0300_0000`, outside the
  heap, and the addresses pointing at them are `long` fields the collector deliberately does not follow
  off-heap (see the refMap `J` rule). Code needs either its own marking or a reason not to need it.
- **The JIT unwind tables** sit in fixed scratch, keyed by code-address ranges, and are reset per batch —
  which is also why a surviving method would lose its stack-trace entry.

**Increment 1 — measure before designing** (the discipline that twice redirected the precise-tracing arc).
Per-batch footprint accounting, plus a `RECLAIM_BY_GC` switch that leaves the data heap where it is and
runs a collection at the *end* of the reset instead — after every registry has been replaced, so the
previous batch's metadata is unreachable and dies as ordinary garbage. Default off; the image is
unchanged with it off.

The numbers reframe the problem — and the *shape* of the distribution is the finding, not the average.
Through batch 18 the suite looked like 7–9 MB of data and 10–60 KB of code per batch, which suggested
the code arena might need no reclamation at all. The last six batches say otherwise:

| batch | data | code | what it is |
|---|---|---|---|
| 19 | 64 MB | 4.3 MB | the regex/String-ops closure |
| 20–21 | 6.4 MB | ~25 KB | ordinary batches |
| 22 | 63 MB | 4.2 MB | WordCount's closure (regex again) |
| 24 | 183 MB | 110 KB | GcDemo, churning the arena on purpose |
| **total (24)** | **457 MB** | **9.5 MB** | |

So both arenas need the collector, for different reasons:

- **Data: 457 MB against a 192 MB arena.** No-rewind-no-collect is not merely tight, it is impossible;
  the heap must be reclaimed by *something*, and reachability is the only candidate that also lets a
  class outlive its batch.
- **Code: 9.5 MB against a 16 MB arena — 60% full, with no headroom.** A single regex-bearing batch
  compiles 4+ MB. My first reading of this (that code was ~680 KB and the arena could simply hold
  everything) came from the first eighteen batches and was wrong: the tail is where the code lives.
  Code reclamation is therefore in scope, and it is the harder half, because a code buffer's only
  references are `long` fields the collector deliberately does not follow off-heap.

**Increment 2 — the live set answers it, and turns up the real blocker.** The collector now reports `live=`, the bytes that survived a
collection, which separates the two readings of a high water mark. With `RECLAIM_BY_GC` on, across the
first twelve batches the live set is **flat at 4.03–5.79 MB** while the heap above the watermark climbs
3.3 → 28.5 MB. Metadata is dying correctly; the water mark is *garbage between collections plus
fragmentation* — one collection per batch reset, against 7–9 MB of allocation per batch, and a first-fit
free list that cannot always place a fresh 4 KiB imap or a 60 KB classfile copy, so the bump pointer
creeps. That is a **collection-frequency and placement** problem, not a lifetime problem, and it means
retiring the data rewind is sound: nothing the loader drops is being kept alive.

The expensive batches say the same thing more strongly. At the regex closure the live set peaks at
**14.2 MB** and falls straight back to 4 MB on the next pass, while the mark stands at 86.5 MB and later
116.6 MB — so even the worst batch retains an eighth of the mark. Twenty-four passes, zero faults, no OOM,
`churnMB=625 live=32 intact=32` from GcDemo and Lisp's answers all correct.

**But the rewind cannot simply be deleted, for a reason the measurement surfaced: compiled code holds
heap pointers the collector cannot see.** `MetalSymbols.emitAddr` bakes a TIB, Type, interface Type or
class-literal address into the instruction stream as a **MOVZ + MOVK pair** — the address is split across
two instruction immediate fields, so scanning the code arena as *data* cannot recover it, and the
collector does not scan it at all. Today that is invisible because the rewind kills a batch's code and its
metadata together; the registries (`RVMClass.tib`/`.type`/`.statics`, `long` fields the refMap `J` rule
keeps scannable) hold everything alive while the batch is current. The moment either code outlives its
batch or freed memory is reused promptly, a TIB whose only remaining reference is a code immediate becomes
a dangling pointer. **Code-embedded roots therefore have to come before either the code rewind is retired
or the data heap is trimmed** — which redefines increment 3.

The second cost is throughput. With the rewind gone the swept heap no longer resets per batch, so
collections walk a 100 MB+ heap instead of a rewound one, and an allocation-heavy program pays for it:
`demo/LispDemo` crawls where the rewind build sails through. It is slow, not stuck — with a competing QEMU
instance killed the run advanced through `(fact 10)`, `(fib 18)`, `(sum 100 0)` and `(twice inc 40) = 42`
— but the suite's own `gc during lisp: collections=` line (5 under the rewind) is the number to compare,
and heap size is what drives it.

With `RECLAIM_BY_GC` on, each reset frees 8.7–22 MB and the heap above the watermark tracks
`0x32AFC8 → 0x44B070 → 0x789390 → 0x789390 → 0x8F9438 → 0xC01C58 → 0x111F400 → 0x111F400` — ~18 MB after
eight batches where the rewind-mode cumulative was already ~130 MB. It plateaus in steps rather than
converging flat, which is the expected shape for a non-moving collector: the bump pointer is the
high-water of live-plus-garbage between collections, so it settles near the largest batch's footprint
plus whatever is genuinely retained. Whether that "whatever" is real retention or conservative false
roots is the question increment 2 answered above: it is neither — it is garbage awaiting collection.

**Increment 3 — code-embedded roots. DONE.** `Loader.noteCodeRoot`, called from the one place a heap
address enters the instruction stream (`MetalSymbols.emitAddr`), records TIB / Type / interface-Type /
class-literal addresses into a table in fixed scratch at `0x0310_0000`. It sits *outside* the managed heap
on purpose — a root table the collector can reclaim is not a root table — in the free band between
`CORE_FLAGS` and the secondary cores' stacks. Image and statics addresses are filtered out: they are
permanent and need no root. On overflow it warns and sets `codeRootOverflow` rather than halting, and
increment 4 must refuse to reclaim while that flag is set, because the unrecorded entries are precisely
the references nothing else holds.

`VMGc.markCodeRoots` scans it **after** the ordinary trace has drained, so anything it newly marks is a
block the rest of the reachability graph did not cover. That count is reported as `codeOnly=`.

**The measurement says the hazard is latent, not active: `codeOnly=0` in every GcDemo collection.**
Mid-batch, each TIB is also reachable through its registry entry (`RVMClass.tib`/`.type`/`.statics` are
`long` fields, kept scannable by the refMap `J` rule), so nothing survives *only* through a code immediate
today. It becomes real in increment 4's configuration — registries replaced while the code that references
them survives — which is exactly when this table stops being insurance and starts being load-bearing.

Two sizing lessons came out of the first run. The table overflowed at 131k entries, because the compiler
runs a sizing pass and an emit pass over the same bytecode and both were recording; gating on
`relocRecording` — the flag that already marks the real-base emit pass — halves the entries with no new
state. And the regex closure alone bakes on the order of 100k addresses, so the table is now 4 MiB
(524,288 entries).

Verified end to end on QEMU: the full suite, 3,187 lines, **zero faults and zero overflow warnings**,
`codeOnly=0` in all three GcDemo collections, `churnMB=625 live=32 intact=32`, and
`lisp: evals=600 result=610 stable=1` with `gc during lisp: collections=5` — **the same collection count
as the rewind baseline**, which is the check that recording roots costs the default path nothing.

**Increment 4 — the trim, and the allocator underneath.** Two results and a blocker.

*The trim works.* `VMGc` now computes the address past the highest **marked** block and hands the tail
back by lowering the bump pointer rather than threading thousands of dead blocks onto the free list
(`trimmed=`). It refuses to trim if the walk meets a corrupt size, mirroring the sweep's own guard. The
ratchet is gone: where increment 1 climbed 3.3 → 4.5 → 7.9 → 7.9 → 9.2 → 12.6 → 18 → 18 MB, the same
passes with the trim read 3.3 → 4.5 → 7.9 → **4.5** → 6.1 → 9.2 → 14.8 → **11.4** MB — rising *and
falling* with the live set instead of only rising.

**A correction to the first write-up of this increment.** It claimed a peak of 14.1 MB against 116.6 MB
untrimmed — an eight-fold cut. That figure was an artifact: the peak was extracted by sorting hex strings
*lexically*, which mis-orders variable-length values (`EC090` sorts above `1B42768`). Computed numerically
over the same 25 passes, the trim moves the **median** heap top 27.3 → 18.0 MB and the **peak** 182.9 →
115.6 MB. The ratchet really is gone and the typical case improves, but the peak is set by the two regex
closures and GcDemo, which allocate tens of MB inside a single batch and are unaffected by trimming
*between* batches. `churnMB=625 live=32 intact=32` holds with zero faults.

*The code-root table is load-bearing.* First pass with prompt reuse enabled: **`codeOnly=3`**. Three
blocks survived only because a code immediate referenced them — pre-arm metadata whose registries were
replaced long ago, with permanent code still pointing at it. Increment 3 measured `codeOnly=0` and called
the hazard latent; enabling reuse is exactly what wakes it, and the table caught it on the first
collection.

*The blocker is the allocator, not the collector.* `Heap.allocLocked` satisfies a request from the free
list by returning the block **whole** — no split, no remainder handed back (`return f; // status already
holds the block size`). A 64 KiB freed block servicing a 32-byte cons cell stays 64 KiB. Under the rewind
this is invisible, because the free list is discarded every batch and allocation is essentially pure bump.
Without the rewind every post-collection allocation comes from that list, each consuming an arbitrarily
oversized block, so usable capacity collapses and collections fire continuously — which is precisely where
`demo/LispDemo` crawls. **Retiring the rewind by default waits on block splitting (and probably
coalescing) in the allocator**; that is increment 5, and it is an allocator change, not a GC one.

The evidence is a non-result, and worth stating as one: the trimmed no-rewind build ran the first 25
batches cleanly in about the time the rewind build takes for the *whole* suite, then spent 80 minutes
inside `demo/LispDemo`'s 600-eval loop without finishing, and was stopped there. Everything before Lisp
is measured; the `gc during lisp: collections=` comparison against the rewind's 5 is still unmeasured,
and belongs to increment 5, where splitting is supposed to fix it.

**Increment 5 — splitting and coalescing. DONE, and it removes the blocker.** `Heap.allocLocked` now
carves the remainder off a reused free block and returns it to the list (keeping the minimum-block rule:
a remainder must hold its own `{TIB, status}` header), and `VMGc`'s sweep merges runs of adjacent dead
blocks into one free block instead of threading them on individually. Splitting without coalescing would
grind the heap into unusable fragments; together they make free memory actually reusable.

The result is the whole point of the arc so far: **the no-rewind suite completes.** Where increment 4 spent
80 minutes inside `demo/LispDemo` without finishing, the same configuration now runs
`lisp: evals=600 result=610 stable=1` with **`gc during lisp: collections=5` — identical to the rewind
baseline** — reaches `(self-build retired; host writer only)`, and does it in about the time the rewind
build takes. `churnMB=625 live=32 intact=32`, zero faults, 25 collection passes.

The heap profile improves in the ordinary case and is unchanged in the extremes: median top 18.0 → **3.3
MB**, peak 115.6 → 104.9 MB. The median is what a long-running program lives in; the peak belongs to the
batches that allocate tens of MB in one go, which no between-batch policy can lower.

| configuration | median top | peak top | Lisp |
|---|---|---|---|
| no trim (inc 1) | 27.3 MB | 182.9 MB | did not finish |
| trim (inc 4) | 18.0 MB | 115.6 MB | did not finish |
| **trim + split/coalesce (inc 5)** | **3.3 MB** | 104.9 MB | **completes, 5 collections** |

**Increment 6 — retire the data rewind.** `RECLAIM_BY_GC` becomes the default: the demand-load heap is
reclaimed by reachability and `resetLoader` no longer rewinds it. Everything the arc measured had to be
true first, and no single piece would have sufficed — the live set is genuinely small (4–6 MB against a
mark that used to climb past 100 MB), the addresses compiled code bakes into its instruction stream are
recorded where the collector can see them, the collector trims its bump pointer past the highest survivor,
and the allocator splits and coalesces so freed memory is actually reusable.

Code-root overflow becomes **fatal** under the new default. While the rewind ran, the table was insurance
and a full table only warranted a warning; now it is the only record of those addresses, so a dropped
entry would let the collector free a block that live code still points at — corruption found later and
somewhere else. It halts with the count, like every other loader-table overflow. The fix is a larger
window, not a weaker guarantee.

The **code** arena still rewinds per batch. Code has no reachability story: its buffers live outside the
managed heap, and the addresses pointing at them are `long`s the collector deliberately does not follow
off-heap. Setting the flag false restores the whole-heap rewind, which is always safe — it discards
everything above the watermark — and remains the fallback if metadata lifetime is ever suspect.

**Pi-validated (2026-08-21).** The suite image with the data rewind retired runs the whole battery on a
real Pi 4: no `CAP EXCEEDED`, no `FAULT`, no `STALE REGISTRY REF` across 24 batches, `churnMB=625 live=32
intact=32` with 3 collections, `lisp: evals=600 result=610 stable=1` with **`gc during lisp:
collections=5`** — the rewind baseline — and the WiFi finale joining WPA2 and returning **HTTP 200,
828 bytes**, ending at `(self-build retired; host writer only)`.

The numbers track QEMU closely enough to trust both: peak heap top **104.6 MB** (QEMU 104.9), live set
steady at **~3.8 MB**, cumulative demand 511 MB data / 9.5 MB code (QEMU 457 / 9.5 — hardware adds the
WiFi path). `codeOnly=3` appears on the first reclaim pass exactly as under emulation, so the
code-embedded roots are load-bearing on silicon too. `trimmed=0x170228` (1.4 MB) shows the collector
handing back a tail inside GcDemo rather than only between batches.

The dirty-DRAM worry did not materialise: the rewind used to pre-zero the demand heap so an
uninitialised or out-of-bounds read met a deterministic zero, and QEMU (whose RAM starts zeroed) could
never have tested its absence. A cold power-on on real silicon did.

**Increment 12 — zero on sweep: the rewind's last property, restored.** The batch rewind used to zero
every code byte above the watermark, so a dangling code pointer met zeros. Increment 11 retired the rewind
and did not carry that over; this does, by zeroing each buffer as the sweep frees it.

**What it actually buys is the failure mode, not safety.** A swept buffer is unreachable by definition and
reuse overwrites it with real code soon after, so the window is narrow. But inside that window the two
behaviours are very different: a stale code pointer now lands on `0x00000000`, which decodes as UDF and
traps into the fault reporter naming the address, where before it branched into the middle of whatever
method used to live there and ran it. One is a bug report; the other is unbounded and looks like anything
at all. That is worth a pass over dead code.

It is deliberately **not** conditional on the sweep being right. If the collector ever frees a method that
is still live, zeroing turns a run that limps into one that stops at the instruction that did it — the
behaviour this arc wants while code reclamation is young.

**One implementation note worth keeping.** `Heap.publishCode` invalidates the *entire* instruction cache
per call (`ic ialluis`), so publishing per swept buffer would repeat a full I-cache flush thousands of
times per collection to no benefit. The sweep tracks the lowest and highest swept address instead and
publishes once over the span. The span may cover live methods between the swept ones; that costs those a
refetch and nothing else.

**And it turns a construction argument into a test.** Freeing a still-live method used to be survivable —
the buffer kept its instructions until something reused it, so a wrong sweep could pass the suite. With
zeroing it cannot: the next call through that method executes zeros and traps. So the suite completing is
now evidence the sweep frees nothing live across ~40 batches, where before it was evidence of nothing in
particular.

**PI-VALIDATED (2026-08-21), whole battery.** No `FAULT`, no `CAP EXCEEDED`, no `STALE REGISTRY REF`
across 24 batches, `churnMB=625 live=32 intact=32`, `lisp: evals=600 result=610 stable=1` with `gc during
lisp: collections=5`, `ExcDemo`'s four frames correct, WPA2 → **HTTP 200, 829 bytes**, ending at
`(self-build retired; host writer only)`.

The readings are increment 11's to within noise — arena 6.68 MB (7,001,928 bytes vs 7,003,368), `codeUsed`
1.81 / `codeLive` 1.74 MB, `codeRoots` peak 43,314 (vs 43,332) — so **zeroing costs nothing measurable**
and reclamation is unchanged. QEMU: 3,211 lines, 0 faults, same readings. Host tests: 323 checks, 0
failures.

**Hardware is the only place this was actually tested.** QEMU models no instruction cache, so
`publishCode`'s invalidate is a no-op there and a wrong span would never show. On the Pi the swept-span
publish is real work, and the run is clean.

**Increment 11 — the code rewind is retired: `RECLAIM_CODE_BY_GC` is the default.** The sweep has been
correct behind the flag since increment 8 and Pi-validated with ownership in increment 9; this makes it the
shipped path, so both halves of `resetLoader`'s rewind are now gone and a batch's compiled code dies by
reachability exactly like its metadata.

Flipping the constant was not the whole change. **One line in the reset was written for a world where a
batch's code always dies with it**:

```java
VM.dropJitTablesAbove(codeHeapMark);   // frame/handler entries for the dead code would
                                       //   ALIAS the next batch's reused addresses
```

Under the rewind that is exactly right — everything above the mark is dead, so every unwind entry above it
is stale. Under reclamation it is wrong in the dangerous direction: the entries above the mark no longer
all belong to dead code, and a **surviving** method stripped of its frame size and catch handlers is a
mis-unwound stack the next time an exception crosses it. The sweep already retires entries per swept range
(`VM.dropJitTablesIn`), which is the same hygiene at method granularity, so the wholesale drop is now gated
on the rewind being in use.

Worth naming the shape of that bug, because it is the shape of the whole arc: **the rewind's correctness
was load-bearing in places that never mentioned it.** Each retirement finds another line whose safety came
from "nothing survives a batch" rather than from an argument about the object in front of it. This one was
invisible to the suite — QEMU and the Pi both passed with the drop in place, because a mis-unwound stack
needs an exception to cross a surviving JIT frame in a *later* batch than the one that compiled it, and no
demo does that. It is an argument from construction, not a test result.

**PI-VALIDATED (2026-08-21), whole battery.** The suite image with the code rewind retired runs the
entire demo battery on a real Pi 4: no `FAULT`, no `CAP EXCEEDED`, no `STALE REGISTRY REF` across 24
batches, `churnMB=625 live=32 intact=32`, `lisp: evals=600 result=610 stable=1` with **`gc during lisp:
collections=5`** — the rewind baseline, so retiring the code rewind costs the allocation-heavy path
nothing — `ticks/core 50 50 50` with `sched: 89 preemptions`, and the WiFi finale joining WPA2 and
returning **HTTP 200, 825 bytes**, ending at `(self-build retired; host writer only)`.

| | Pi | QEMU |
|---|---|---|
| code arena held (`cur - mark`) | 6.68 MB | 6.68 MB |
| `codeUsed` / `codeLive` | 1.82 / 1.75 MB | 2.51 / 2.44 MB |
| `codeRoots` peak | 43,332 of 262,144 (16.5%) | 60,785 (23%) |

`codeUsed` a few percent above `codeLive` is the load-bearing reading: the free list absorbs reuse, so the
6.68 MB arena is capacity held against peak demand rather than a leak — and hardware and emulation agree
on the arena size to the byte. `ExcDemo`'s four-frame trace is correct, which is where the per-range
`dropJitTablesIn` hygiene shows in output.

The root count is the one number that moves between runs: 43,305 / 43,332 / 43,236 here, matching
increment 9's Pi run (43,272) and the post-revert QEMU run exactly, while one QEMU run of this image
measured 60,785. It tracks how much code each batch compiles under a given thread schedule. Hardware sits
at 16.5% of capacity; the conservative bound from the outlier is 23%, so call the headroom ~4x and not the
~6x hardware alone suggests.

**One property of the rewind deliberately not preserved.** The rewind zeroed dead code, so a dangling code
pointer met zeros — `blr 0` is caught — instead of a previous method's instructions. Reclaimed buffers are
not zeroed: the window is small (reuse overwrites the block with real code) but it is not nil, and the
failure mode for a stale code pointer is now "executes something arbitrary" rather than "traps". Zeroing on
sweep would restore it at the cost of a pass over dead code plus I-cache maintenance per collection; it is
recorded here rather than done, because it defends against a bug class the sweep is supposed to make
impossible rather than one that has been observed.

**Increment 9 — code-root ownership. DONE, PI-VALIDATED.** On hardware `codeRoots` reads 13,579 →
43,467 → 43,503 → 43,272 — rising *and falling*, matching QEMU — against 216,423-and-climbing before
ownership. No `CAP EXCEEDED`, `ExcDemo`'s four frames correct, `churnMB=625 live=32 intact=32`, Lisp's 5
collections, WPA2 → HTTP 200, `self-build retired`. The monotonic growth is gone on silicon.

**Increment 9 — code-root ownership. DONE.** Each entry becomes `{addr, owner}`, the owner being the
buffer that baked the address in, recorded from `emitMethod` — the only place `relocRecording` is set, so
the guard that keeps the sizing pass from recording doubles as the guarantee that `owner` cannot go stale.
Sweeping a method now calls `Loader.dropCodeRootsIn(start, end)` beside `dropJitTablesIn`.

| | code-root entries | of capacity |
|---|---|---|
| before ownership | 216,423, climbing monotonically | 41% |
| **with ownership** | **43,236 — rises and falls** | **16.5%** |

The count tracks live code instead of accumulating (13,386 mid-run, 43,305 at peak, 43,236 after), a 5×
reduction against a capacity *halved* by the doubled entry size — so effective headroom improved about
tenfold, and the fatal `CAP EXCEEDED` a longer program would have hit is off the path. Suite: 3,212 lines,
zero faults, `codeLive 1.75 MB / used 1.82 MB`, `churnMB=625 live=32 intact=32`, `gc during lisp:
collections=5`, and `ExcDemo`'s four-frame trace still correct, so dropping roots alongside the unwind
entries did not disturb the unwind path.

Worth stating what this does *not* prove. Dropping is sound only because a swept method's baked-in
addresses were reachable solely through its instruction stream — true by construction, since `emitAddr` is
the one place they enter code and `owner` is recorded under the same guard, but an argument from the code
rather than something the suite would catch if wrong. The failure would be a heap object freed while a
live method still points at it: exactly the hazard increment 3's table was built for, surfacing later and
elsewhere.

**Increment 10 — the code-arena trim: MEASURED ON HARDWARE, REVERTED.** The trim is gone; the reasoning
below is kept because the measurement is the point.

On QEMU it looked like a modest win (6.71 → 5.46 MB). **On a real Pi it reclaimed nothing**: `codeTrim`
read 0 at every collection and the arena stayed pinned at 6.64 MB against 1.75 MB live — a 3.8× ratio
against 3.5× *without* the trim, i.e. no better, and the QEMU gain was an artifact of that run's
allocation ordering leaving the top of the arena free.

| | arena | live | ratio |
|---|---|---|---|
| Pi, no trim (increment 8) | 6.71 MB | 1.90 MB | 3.5× |
| **Pi, with trim** | **6.64 MB** | 1.75 MB | **3.8×** |
| QEMU, with trim | 5.46 MB | 1.26 MB | 4.3× |

So the trailing-run limitation is not a partial constraint here, it is total: a live method sits near the
top of the arena and pins everything beneath it, every time. **Compaction is not the way to close the
remaining 4× — it is the only mechanism that does anything at all**, and it means moving code and patching
branch targets, TIB slots, phase-A cells and the `long` fields the refMap `J` rule keeps scannable. The
trim was reverted rather than left inert, with the reasoning recorded at the point in `VMGc` where it
would otherwise be reintroduced.

**Increment 10 — the original write-up, kept for the record.** The sweep lowers `CODE_PTR_CELL` past a
trailing run of swept buffers, the way the data heap's sweep trims its own: everything at or above the
highest ALLOCATED block's end is free (blocks are disjoint, so a block starting above that line cannot
extend below it), those registry entries are dropped with the space, and free entries below the line stay
as holes for reuse.

It works, and it barely helps:

| | arena (`cur - mark`) | live |
|---|---|---|
| no trim (increment 8, on hardware) | 6.71 MB | 1.90 MB |
| **with trim** | **5.46 MB** | 1.26 MB |

Still 4.3× live, where the same trim brought the *data* heap's median to within a few percent. **The trim
reclaims only a trailing run, and live methods in the code arena are scattered by construction** — every
batch compiles a handful that survive (`String.length`, `Math.max`, the baked-link targets) interleaved
with hundreds that do not, so one survivor near the top pins everything beneath it.

The right reading is that the trim was the wrong lever for code. The free list already makes the interior
holes reusable — `codeUsed` 1.33 MB against `codeLive` 1.26 MB says reuse is working — so the arena's
5.46 MB is **capacity held, not memory wasted**, and it is bounded by peak demand rather than growing.
Closing the remaining 4× needs **compaction**: moving code and patching every reference to it — branch
targets, TIB slots, phase-A cells, and the `long` fields the refMap `J` rule keeps scannable. That is a
harder problem than anything else in this arc, and it argues for relative branches plus an indirection
table rather than a sweep. Not attempted here.

**Increment 7 — code liveness, measured first.** Retiring the code rewind needs per-buffer liveness, and
before building a code collector the arc's usual question: how much compiled code is still reachable?

Two pieces answer it. `Heap.allocCode` now records every buffer as `{start, size}` in a **code-block
registry** — the code arena is a headerless bump region, unlike the data heap whose status word makes it
walkable, so nothing could enumerate compiled methods after the fact. And `tryMark`, which already sees
every candidate word, sets a bit in a **code-reachability bitmap** (one per 8 bytes of the 16 MiB arena)
when a word points into code instead of rejecting it. After the trace drains, a walk of the registry
totals the blocks with any bit set. Both tables live in fixed scratch, outside the managed heap, for the
reason the code-root table does: the collector must not be able to reclaim its own bookkeeping.

**About 70% of all compiled code is garbage, and the share grows with runtime.** Early batches:
13.8 KB live / 20.9 KB used (66%), falling through 26% to 17.5% as batches accumulate. Late batches:

| live | used | live % | reclaimable |
|---|---|---|---|
| 1.28 MB | 5.00 MB | 25.7% | 3.72 MB |
| 2.80 MB | 9.24 MB | 30.3% | 6.44 MB |
| 3.30 MB | 9.51 MB | 34.7% | 6.21 MB |
| 2.70 MB | 9.51 MB | 28.4% | **6.81 MB** |

Live code plateaus near 3 MB while the total keeps climbing, so **"make the arena bigger" is the wrong
reading**: the arena is not the constraint, the waste is, and it scales with how long the program runs
rather than with how much code is in use. The measurement is conservative in the safe direction — one bit
anywhere inside a method marks the whole method live, and code addresses held in `long` fields count as
references — so the reclaimable share is *at least* this.

Still needed for reclamation: a code free list with splitting and coalescing (the increment-5 lesson
applies verbatim, or code fragments exactly as data did); dropping JIT unwind entries for reclaimed
ranges, since their entries are keyed by code address and would otherwise alias a later method compiled
at the same address; and tying code-root entries to the buffer that created them, instead of clearing the
whole table with the arena as `resetLoader` does today.

**Increment 8 — the code sweep.** All three, behind `RECLAIM_CODE_BY_GC` (default off while it is
measured). The **free list is the registry**: the arena has no headers to thread a list through, but the
registry already enumerates every buffer, so a free bit in its size word turns it into one; `allocCode`
first-fits over it and splits the remainder. **`VM.dropJitTablesIn(lo,hi)`** drops frame, local and handler
entries whose code lies in a swept range — keyed by machine address, they would otherwise answer for
whatever is compiled there next, which is the aliasing the batch rewind avoided by dropping everything
above the code mark at once. And a **sweep floor** at the loader's code watermark keeps the boot vector
table, the scheduler's switch stubs and the run trampoline out of reach: they are entered from hardware
registers and stub-internal branches no scan can see.

Code-root ownership was *not* built. A swept method's roots linger, which only ever over-retains heap
objects and can never free something live — sound, at the cost of precision and a table that now grows
monotonically.

The arena stops accumulating and tracks its live set:

| | code in use at GcDemo | live |
|---|---|---|
| increment 7 (no sweep) | 9.51 MB | 2.70 MB |
| **increment 8 (sweeping)** | **2.65 MB** | 2.70 MB |

`codeUsed` tracks `codeLive` within a few percent at every pass, where without sweeping it sat pinned at
9.51 MB while live hovered near 3 MB — about **6.9 MB reclaimed**, matching increment 7's 6.8 MB estimate.
Full suite: 3,212 lines, zero faults, no `CAP EXCEEDED`, `churnMB=625 live=32 intact=32`,
`gc during lisp: collections=5`, through to `(self-build retired; host writer only)`.

**Pi-validated (2026-08-21).** The sweep holds on hardware: no `FAULT`, no `CAP EXCEEDED`, no `STALE
REGISTRY REF` across the whole battery, `churnMB=625 live=32 intact=32`, `lisp: evals=600 result=610
stable=1` with 5 collections, WiFi to HTTP 200 / 828 bytes, ending at `(self-build retired; host writer
only)`. `codeUsed` tracks `codeLive` — 1.88 MB live / 1.96 MB used at GcDemo, 1.83 / 1.90 at the last
pass — and `ExcDemo`'s `level3 → level2 → level1 → main` trace comes back with correct line numbers, which
is the direct evidence that `dropJitTablesIn` removed the right unwind entries and no stale one answered
for a reused address. That was the subtle failure mode, and it did not happen.

**The skipped ownership work is now quantified, and it is worse than "imprecise": the code-root table
reached 216,423 of 524,288 entries — 41% full — in a single suite run.** A run twice this long trips
`CAP EXCEEDED: CODEROOTS`, which is fatal by design. Per-buffer ownership is therefore the next required
increment, not a refinement.

A second gap the run makes visible: `code arena: cur-mark = 6.71 MB` while `codeUsed` is 1.9 MB. The
arena's bump pointer never descends — freed buffers are reused through the free list rather than by
lowering it — so `cur` is only a high-water mark. The data heap got a trim in increment 4; code wants the
same, and it pairs naturally with the ownership fix.

Two results to read correctly. `codeFreed` is large on the first sweep and zero afterwards — sweeping is
incremental, so the standing evidence is `codeUsed ≈ codeLive`, not the per-pass delta. And the code-root
table survived a whole suite uncleared, but that is one workload against a table that only grows; it is
the likeliest thing to bite a longer-running program, and the honest fix is per-buffer ownership. The
sweep floor's necessity is likewise asserted, not tested: nothing below the watermark was ever swept, so
the boot stubs were never at risk in this run.

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
