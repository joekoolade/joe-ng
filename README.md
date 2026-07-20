# joe-ng

A **metacircular Java VM** that runs on bare metal. Java classes go in; a raw
`kernel8.img` comes out and boots on a Raspberry Pi 4 with **no operating system
underneath it**.

The unusual part is what *isn't* here. There is no C, no external assembler, no
linker, no GRUB. The AArch64 instruction encoder, the bytecode compiler, the
classfile parser, the boot-image writer, and the EL2→EL1 boot sequence are all
ordinary Java, written from scratch. joe-ng emits raw A64 instruction *words* and
does its own layout, relocation and image emission.

Exactly two things are taken rather than built: a stock JVM to run the writer
initially (it goes away at self-hosting), and the Pi's GPU firmware that loads
`kernel8.img`. Nothing else touches it.

```
  your .class files
      │
  boot-image writer (Java, on the seed JVM)
      │   parses classfiles, lays out objects / TIBs / statics,
      │   compiles bytecode → raw AArch64 words, relocates to 0x80000
      ▼
  kernel8.img  (entry code + compiled methods + object graph)
      │   Pi firmware loads it at 0x80000 and enters at EL2
      ▼
  compiled VM.boot, now A64:  EL2→EL1, stack, UART … runs Java on the metal
      │   the image contains compiled copies of the parser and compiler,
      ▼
  so the VM loads and JIT-compiles new .class files itself, on bare metal
      │
      ▼
  eventually: joe-ng builds joe-ng, and the seed JVM is no longer needed
```

That last step is the point of the whole design. The classfile parser and the
baseline compiler are just runtime classes, so the writer can run them on the
seed JVM *and* ship compiled copies inside the image.

## Try it

Needs a JDK (17+), `make`, and `qemu-system-aarch64` for the emulator path.

```sh
make test    # build + run the test suite (158 bit-for-bit and behavioural checks)
make qemu    # build kernel8.img and boot it under QEMU raspi4b
```

For real hardware, `scripts/sdcard.sh` assembles a bootable FAT32 payload and
[`scripts/flash.md`](scripts/flash.md) covers the wiring (GPIO14/15, 115200 8N1)
and troubleshooting.

## What it prints, and why each line is there

This is the feature run — every line is a milestone proving itself on the metal,
identical under QEMU and on a real Pi 4 (bar the clock, which QEMU reports as
`core 0MHz` since it doesn't implement the measured-rate mailbox tag).

```
hello from joe-ng     compiled VM.boot: EL2→EL1, stack, UART, interned string literal
core 166MHz           VPU core clock read from firmware over the mailbox (see below)
k                     heap object + constructor + field, read back via vtable dispatch
AB                    a real heap byte[]: allocation, arraylength, element access
3                     static fields (getstatic/putstatic) in the image statics area
7                     <clinit> — static initialisers run eagerly at boot
W?                    class hierarchy: Dog's override vs Animal's base, one vtable slot
YNW                   instanceof (yes / no) and a checkcast, walking the Type chain
RP                    interfaces: Robot vs Phone dispatched through a Speaker reference
E                     an exception thrown and caught in the same method
U                     an exception caught after unwinding across a stack frame
R                     mark-sweep GC: this allocation was served from the free list
*M                    the runtime class loader (below)
```

That last line is the headline. The `*` comes from classfiles the VM had never
seen at build time — embedded as raw bytes, then parsed, JIT-compiled to A64 and
linked **on bare metal**, exercising `new`, fields, all four `invoke*` forms,
`<clinit>`, class hierarchies with flattened vtables, `instanceof`, and interfaces
with itables. The `M` is `java.lang.Math.max` pulled straight out of the seed
JDK's `java.base` and compiled on the metal the same way.

## Status

Working, and confirmed on real Pi 4 silicon — not just under emulation.

| Milestone | State |
|---|---|
| M0 — writer emits a booting image | done |
| M1 — first light: compiled `VM.boot` prints over UART | done |
| M2 — object model, multi-class, real cross-class calls | done |
| M3 — heap, `new`, instance fields | done |
| M4 — runtime class loading on bare metal | done |
| M5 — self-hosting (drop the seed JVM) | started |
| M6 — GC, exceptions | collector + unwinding working |

The on-metal class loader is feature-complete for single inheritance. M5 proper
has begun: [`classfile/ClassReader`](src/classfile/ClassReader.java) is the first
genuinely shared component — strictly JDK-free (no String, no collections), so the
*same source* runs on the seed JVM and compiles into the image. Remaining work is
migrating the writer's `ClassFile` and `BaselineCompiler`, which still lean on JDK
collections and strings.

## Layout

```
src/asm/          A64 instruction encoder → raw 32-bit words, and a code buffer
src/compiler/     bytecode → A64 baseline compiler (compile-only, no interpreter)
src/classfile/    classfile parsers — including the shared, JDK-free ClassReader
src/objectmodel/  the guest object model: headers, TIBs, field/array layout
src/writer/       layout, relocation, and raw kernel8.img emission
src/vm/           VM.boot, heap, GC, exception unwinding, on-metal class loader
src/magic/        intrinsics: privileged ops, raw memory, unsafe type adapters
src/board/bcm2711/ mini-UART, GPIO, VideoCore mailbox
test/             homegrown harness — no third-party test framework
```

## Reading further

- [`PLAN.md`](PLAN.md) — the milestone plan and the design rationale. Source of truth.
- [`CLAUDE.md`](CLAUDE.md) — standing constraints and a detailed record of what
  works, including the traps. Two worth knowing:
  - The mini-UART baud is derived from the VPU core clock, which is *not*
    predictable — it changed between firmware builds and even between SD cards.
    Three hardcoded divisors each worked on one setup and produced garbage on the
    next. joe-ng now asks the firmware for the **measured** rate at boot, because
    `GET_CLOCK_RATE` cheerfully echoes back the rate you asked for (200 MHz) while
    the silicon runs at another (166 MHz).
  - Interface dispatch cannot use a fixed vtable slot: two classes may implement
    the same method at different slots. The regression test is built so it *fails*
    without an itable, rather than passing by luck.
- [`SOURCES.md`](SOURCES.md) — the references consulted (ARM ARM, BCM2711 docs).

## A note on the code

This is a from-first-principles learning project. Every encoding is checked
bit-for-bit against the ARM Architecture Reference Manual before it is relied on,
because a mis-lowered store corrupts memory invisibly. Jikes RVM and JOE were
referenced for *concepts* only; every line here was written for this project.
