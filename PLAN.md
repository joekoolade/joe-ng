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
  - ◐ **4.4c — `MetalSymbols implements Symbols`**, the other half of the seam.
    `vm/MetalSymbols` resolves each cp index to a concrete address *now* (the metal
    has already loaded its deps): calls → `Loader.resolveCallBuf`; helper calls →
    the writer-stashed `VM.heapAlloc`/`allocArray`/`gcCollect`/`instanceOfAddr`/
    `checkCastAddr`/`unwindAddr`; tib/type/interfaceType/staticField → `Loader.tibOfClass`/
    `typeOfClass`/`ifaceTypeOfMethod`/`staticAddr` loaded via `loadImm64`; the int/bool
    queries → `fieldOffsetOf`/`objectSizeOf`/`vtableSlotOf`/`ifSlotOf`/`isRealSpecial`;
    `fail` halts. The needed `Loader` resolvers are now package-visible. **M5Gap: 20/20
    methods self-compile.** Dead code until 4.4e wires it, so the image is byte-identical.
    Deferred to 4.4d/e (stubbed with TODOs): interned-string and exception-slot
    addresses on metal, magic-intrinsic recognition, and the object-model/dispatch
    reconciliation — the metal indexes the imap by global slot while the shared core
    searches an itable directory (`ObjectModel.TYPE_ITABLE_DIR_OFFSET`), so the metal's
    Type/vtable/itable layout must line up with `ObjectModel` before the core can drive
    invokeinterface on metal.
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
    - ☐ **d.3 — the metal code sink**: `Loader` emits through a `CodeBuffer` that flushes
      to `cout`, replacing the bare `emit(word)`, and converge the branch model
      (writer forward-fixups vs. the metal's two-pass `pass1`) onto the fixup model.
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
      - ☐ **step 3 — instanceof/checkcast via helpers.** Once JIT'd `Type`s are
        `ObjectModel`, the metal's inline walks can give way to the `VM.instanceOf`/
        `checkCast` calls the core emits (the addresses are already stashed, 4.4c).
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
