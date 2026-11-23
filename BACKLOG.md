# joe-ng — backlog

Open work items, highest value first. When one is taken, its result goes in `STATUS.md` as an increment card and
the item leaves this list; new findings that are not fixed on the spot come here.

## WiFi

- **Key install stalls on slow boots (2026-11-14).** On some boots the firmware leaves ioctls unanswered for ~7 s
  after link-up. Both `WLC_SET_KEY`s then time out at 1.5 s each (~3 s per join; the keys do take effect), and
  the first DHCP discover went unanswered too. Measure with serial and the `/net` "WiFi join" rows. The join
  retry (`Cyw43.associate`, 3 attempts) has never been exercised on the Pi.
- **Firmware went silent at `WLC_SET_SSID` (2026-11-14, low priority).** Reading the MAC and `wpaie` just before
  `WLC_SET_SSID` (after `WLC_UP`) left that ioctl unanswered and no join event for 15 s. Not the tx credit window
  (txSeq 19, txMax 59 measured). Nothing reads there now; unexplained.

## Web server

- **A `/run` stop cannot reach a thread blocked in a JDK call that ignores interrupts (2026-11-14):** the forced
  stage acts at the program's own loop headers (Baseline stop polls), so a thread parked in, say, a socket read
  stops only when the call returns. While such a stop is PENDING, every poll in every run calls into the VM (~30-38
  cycles per loop pass on the Pi, against 1-2 with none pending): a per-task flag the poll could read inline would
  remove that, if a run left blocked like this ever matters.
- **A `/run` thread other than main that calls `System.exit` ends only itself (2026-11-14):** main carries on to its
  own end; the run reports the exit. There is no safe way to stop main from another thread.

## Collector

- **Memory corruption in `demo/RunProbe` under `gcroots=0` (found 2026-11-22, frames/GC arc S6d-3).** With the
  conservative stack-root scan, RunProbe dies right after its stop-poll bench while a stop is pending: on `main`
  (`d7d8054`) 3 of 3 boots -- a wild branch to a pc made of two instruction words (`0xAA0003E99402A878`), or QEMU's
  `Guest moved used index` (the virtio-net used ring overwritten) and a hang. Every other probe passes under
  `gcroots=0`, and RunProbe passes under every other arm. Never seen before because no increment since S5 ran a
  `gcroots=0` arm. Unexplained; it is the A/B switch, not the default.
- **DONE in frames/GC arc S5a (2026-11-21): cores 1-3's arenas are collected,** and any core collects when its arena
  is full (STATUS.md). Kept below as the record of what it cost before.
- **(Was:) Cores 1-3's arenas are never collected (2026-11-14).** They are root-scanned whole: pauses grow from 0.5 to
  2.4 s over a session (root scan 92 -> 972 ms), they grow ~12.7 MB per boot upload, and they halt with "heap OOM"
  at 64 MB. A long pause can outlast a socket read timeout (`507 ... Read timed out`). The biggest remaining
  upload limit. It is also the WHOLE cause of the late ACKs: every ACK that waited >= 100 ms in the transmit
  queue spanned a collection (36 of 36 over four uploads; `/net` "WiFi tx waits >= 100 ms across a collection"),
  and those waits pass the sender's retransmit timeout (`TcpExt DupData` 1-49 per upload). The collection attempt
  (a second bitmap at `0x1E00_0000`, per-core sweeps) passed QEMU but hung the Pi twice and was dropped -- QEMU
  gives launched programs no timer ticks, so it cannot validate this; every trial needs serial.
- **A heap OOM on cores 1-3 wedges the board instead of throwing (2026-11-19).** demo/FrameBench via `/run`
  (task 5, core 3, ~50 MB of garbage) printed `heap OOM` while task 5 held the loader lock in a first-call compile
  (`ctx~java/math/BigInteger`); core 0's next collection then hit `GC: STW TIMEOUT -- unparked core 3 task 5 ...
  loaderOwner=5 (collection SKIPPED)`, the loader lock stayed held (`held 606408ms`, owner RUNNABLE), and the
  board stopped answering ping. Same root as the item above; the failure mode is the separate bug: an OOM should
  throw `OutOfMemoryError` in the allocating task, not leave it spinning with the loader lock.
- **A full core-0 heap leaves core 0 ~2.5x slower until reboot (2026-11-21, pre-existing).** With the volume trigger
  off (`gcvolume=0` in `/etc/init`, or `Heap.GC_TRIGGER_BYTES = 0` on main), core 0's heap fills before each
  collection (~55 MB live after) and, from about the third to fifth such collection, FrameBench via `/run` runs every
  kernel ~2.5x slower -- fib included, which allocates nothing -- for the rest of the boot. MEASURED on main (run 7 of
  9, after collection 5) and on S5a (5 of 5 boots). NOT the clock: the firmware reports 1,500 MHz, 64-68 C and
  throttle flags 0 (`/threads`). Not an interrupt storm (tick rate and the RX task's busy time unchanged), not a code
  sweep (0 bytes swept), not a stats page. The default 16 MB trigger never fills the heap, so it does not appear
  there (5 runs per boot, 540-560 ms). Unexplained.
- **Large region fragments to a halt under `gcvolume=0` with JIT maps on (2026-11-21, S5).** Core 0's 64 MiB large
  region holds 37-42 MB live and refuses a 1-4 MiB request with ~22 MB free in pieces -> `large region OOM`
  (regression batch ReflectUndenyProbe..SecretKeyFactoryProbe; fails with conservative roots + maps too, not on main
  without maps). The small heap was as fragmented when tried as a fallback. Non-moving collector; S7 compaction is
  the real fix. Also: what is the ~37 MB live (two image TIBs, ~45 blocks of ~320 KB and ~100 of ~132 KB)? Same
  question as the item below.
- **Pi did not come back from a soft `/reboot` once; a cold boot ran at 600 MHz (2026-11-21).** The `gcroots=0` S5
  image hung on 1 of 4 reboots (no ping until power-cycled; no serial captured). After that power-on the firmware
  measured the ARM clock at 600 MHz until a soft reboot (then 1,500). Neither attributed; capture serial next time.
- **41.5 MB live in the large-object region during an upload (2026-11-14).** Of ~60 MB live; the trace takes a
  steady ~413 ms. Unexplained -- measure what holds it before changing anything.

## QEMU targets with Ethernet

Upstream QEMU's `raspi4b` has no network (no GENET, no PCIe; USB never enumerates, so `usb-net` fails too), so
networking cannot be tested under emulation today. Candidate machines with a working NIC (2026-11-18):

- **`virt` with `-cpu cortex-a72`.** Closest to the Pi 4: same core, GICv2 (`gic-version=2`, like the GIC-400),
  PL011 UART (at `0x0900_0000` instead of `0xFE20_1000`), generic timer. Network is `virtio-net-device` on
  virtio-mmio, so no PCIe driver is needed. RAM starts at `0x4000_0000`, so this needs a board layer for the
  memory map and device addresses.
- **`xlnx-zcu102`.** Cortex-A53 with the Cadence GEM Ethernet: a real SoC NIC model, but Xilinx-specific.
- **`sbsa-ref`.** Server-style board, e1000e NIC on PCIe; needs PCIe enumeration plus an e1000e driver.

## Boards

- **An Intel (x86-64) board (2026-11-19).** The platform arc (P1-P7, PRs #595-#602) put every board-specific piece
  behind `board.Platform.OPS` and the platform constants, so a new board is a new platform class plus a writer
  start-up routine. x86-64 is the larger step, because more than the board changes: the VM and compiler are
  AArch64 throughout. It needs an x86-64 encoder and Baseline back end, a `compiler.Target` beyond the generic
  timer choice, a start-up routine (long mode and page tables from a multiboot or UEFI hand-off), the local APIC
  and IOAPIC behind `board.Irq`, a 16550 console behind `board.Uart`, the APIC or HPET timer, SIPI secondary start
  behind `startSecondaries`, and virtio-net over PCI for `net.Link`. Start under QEMU `q35`.

## Optimizing compiler

**Waits for the memory-frame / precise-GC arc (PLAN.md, decided 2026-11-19), which comes first.** A phased plan for an optimizing tier above Baseline (2026-11-19). There is no interpreter, so Baseline is both the
deoptimization target and the test oracle.

- **O1: an SSA IR that round-trips.** Bytecode -> SSA over a basic-block CFG, naive scheduling, linear-scan
  allocation, emission through Baseline's assembler; no optimizations yet. Opt code must behave exactly like
  Baseline. The hard part is what it must produce: GC reference maps at every safepoint, exception tables, bci maps
  and deopt framestates. Run an IR verifier after every phase, in the writer on the host JVM.
- **O2: local cleanups and AArch64 lowering.** Canonicalize/fold, GVN, DCE; shifts folded into operands, scaled
  addressing (`[base, idx, lsl #3]`), `CBZ`/`CBNZ`/`TBZ`, `MADD`, `CSEL` for simple diamonds. The A72 is out of
  order, so register allocation and removing loads and checks matter more than instruction scheduling.
- **O3: inlining that needs no speculation.** Static, private, final and `invokespecial` calls, constructors, small
  accessors; magic intrinsics inlined everywhere.
- **O4: profiling and speculation.** Invocation and back-edge counters in Baseline code trigger recompiles; receiver
  type profiles at `invokevirtual`/`invokeinterface`; CHA devirtualization that registers dependencies, so runtime
  class loading invalidates the right methods; deopt by OSR into a Baseline frame. Compile synchronously at the
  threshold first; a compiler thread on another core can come later.
- **O5: heavier optimizations.** Implicit null checks (leave page 0 unmapped; the data-abort handler maps the
  faulting PC to a bci and throws `NullPointerException`), bounds-check elimination by dominance and range analysis,
  escape analysis with scalar replacement, loop-invariant code motion, then loop peeling.

Constraints that hold across all phases:

- **Deopt.** The Baseline frame layout (locals and stack slots at fixed FP offsets) is the canonical frame. Baseline
  emits a bci -> PC map for every method and can be entered at any bci; deopt builds a Baseline frame from the
  framestate and branches to that PC.
- **VM-internal code.** Methods that must not be interrupted or allocate get no added polls or allocations and are
  never inlined into code that breaks their rules. Magic intrinsics are pinned nodes, never floated or removed as
  dead. `DMB`/`DSB`/`ISB` are full fences: no load or store moves across them, and GVN does not merge loads across
  them.
- **Code install.** `DC CVAU` -> `DSB ISH` -> `IC IVAU` -> `DSB ISH` -> `ISB`, on all four cores. Callers go through
  a per-method entry slot, so installing and invalidating opt code is one store plus a barrier, with no code
  patching.
- **Testing.** Run methods under both tiers and compare results and serial output. Boot-image stress modes:
  opt-compile everything, inline randomly with a seed, deopt at every safepoint. A boot image whose runtime,
  compiler and collector were opt-compiled by the writer is the end-to-end test.
