# joe-ng

A **metacircular Java VM** that runs on bare metal. Java classes go in; a raw
`kernel8.img` comes out and boots on a Raspberry Pi 4 with **no operating system
underneath it**.

The unusual part is what *isn't* here. There is no C, no external assembler, no
linker, no GRUB. The AArch64 instruction encoder, the bytecode compiler, the
classfile parser, the boot-image writer, and the EL2→EL1 boot sequence are all
ordinary Java, written from scratch. joe-ng emits raw A64 instruction *words* and
does its own layout, relocation and image emission.

Exactly two things are taken rather than built: a stock JVM to run the writer,
and the Pi's GPU firmware that loads `kernel8.img`. Nothing else touches it. The
seed JVM is a permanent build-time tool, like `javac`: the writer is not meant to
run on the metal.

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
```

That last step is the point of the whole design. The classfile parser and the
baseline compiler are just runtime classes, so the writer can run them on the
seed JVM *and* ship compiled copies inside the image.

## Try it

Needs JDK 26, `make`, and `qemu-system-aarch64` for the emulator path. The build
bakes the seed JDK's own `java.base` into the image, and the `guestsrc/` overlays are
taken from JDK 26 source; the two must be the same feature release.

```sh
make test    # build + run the test suite (158 bit-for-bit and behavioural checks)
make qemu    # build kernel8.img and boot it under QEMU raspi4b
```

For real hardware, `scripts/sdcard.sh` assembles a bootable FAT32 payload and
[`scripts/flash.md`](scripts/flash.md) covers the wiring (GPIO14/15, 115200 8N1)
and troubleshooting.

## Run the web server on a Pi 4

`demo/WebServerDemo` is a small HTTP server written against stock
`java.net.ServerSocket`, running over joe-ng's own WiFi driver and TCP/IP stack.
Its front page shows the date and time, which comes from SNTP at WiFi bring-up.
It links to live VM statistics pages, each read from the VM's counters when the
page is requested:

| Page | Shows |
| --- | --- |
| `/memory` | heap and code arena |
| `/gc` | collections, pauses, sweeps |
| `/locks` | monitors and semaphores |
| `/threads` | every task, its state and priority |
| `/net` | IP/TCP/UDP counters and WiFi transmit stats |
| `/boot` | the SD card's boot files; upload, trial-boot and commit new ones (below) |

It needs a Pi 4 (the onboard CYW43455 WiFi), an SD card with a FAT32 boot
partition, and a WPA2-PSK or open network. A USB-TTL serial adapter is optional,
but it's how you learn the board's address.

1. **Give it your network.** Copy the example and fill in your SSID and password.
   `ramfs/etc/wifi.conf` is gitignored, so it is never committed.

   ```sh
   cp ramfs/etc/wifi.conf.example ramfs/etc/wifi.conf
   $EDITOR ramfs/etc/wifi.conf      # ssid=...  psk=...
   ```

2. **Make it the program the image launches.** `ramfs/etc/init` names the main
   class. `net=1` brings up WiFi, DHCP and SNTP before `main` runs.

   ```sh
   printf 'main=demo/WebServerDemo\nnet=1\n' > ramfs/etc/init
   ```

   An optional `args=` line sets the port (default 80) and the time zone
   (default UTC). For example, `args=8080 America/Chicago`.

3. **Build and write the card.** `scripts/sdcard.sh` runs `make image`, fetches
   the Pi's GPU firmware (`start4.elf`, `fixup4.dat`) into `sdcard/`, then, on
   macOS, copies the boot files to `/Volumes/bootfs`, verifies them byte for
   byte, and ejects the card. On other systems, copy `kernel8.img`, `config.txt`,
   `start4.elf`, `fixup4.dat` and `armstub/*.bin` from `sdcard/` onto the boot
   partition yourself.

   ```sh
   scripts/sdcard.sh
   ```

4. **Boot it and find the address.** On the serial console (115200 8N1), the
   server reports when it's ready:

   ```
   wifi: bring-up
   ...
   web: listening on port 80
   web: open http://192.168.1.247/
   web: waiting for connections
   ```

   Then browse to that address, or:

   ```sh
   curl http://192.168.1.247/
   curl http://192.168.1.247/threads
   ```

   Without serial, look up the board's DHCP lease in your router's client list.
   Each request is logged on the console as it is served.

The server answers one connection at a time and closes each one
(`Connection: close`). An accepted socket has a read timeout, so a browser's
speculative connection that never sends a request can't stall it.

If the serial log shows `wifi: join timeout`, check the SSID and password in
`wifi.conf`. `web: no network interface yet` means the join failed before the
server started.

### Update the card over the network

Once the Pi is running the web server, it can write its own boot files, so the
card stays in the board. Updates need a shared token, which is baked into the
image. Create it before the build in step 3, so the first card already carries
it:

```sh
openssl rand -hex 16 > ramfs/etc/boot.token   # gitignored
```

Without the token, the `/boot` page is read-only and every change is refused.

From then on, one command builds an image and makes it live:

```sh
scripts/push.sh 192.168.1.247
```

It sends only the boot files whose SHA-256 differs from the card's, as
`<name>.new`. Then it reboots the Pi into them once, using the firmware's
`tryboot.txt`. Only when the new image answers `/version` with its own build id
does it commit: each file is renamed over the old one, and `config.txt` goes
last. If the new image never comes up, nothing live has changed, and a power
cycle boots the old files.

`push.sh -n` shows what would be sent without sending it. The same steps are
buttons on `http://<pi>/boot`, for uploading a file from a browser.
[`scripts/flash.md`](scripts/flash.md) has the details.

To go back to the default program, restore `ramfs/etc/init`, for example with
`git checkout ramfs/etc/init`.

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
| M5 — one parser and compiler shared by the writer and the metal | done |
| M6 — GC, exceptions | collector + unwinding working |

M5 is closed. The JDK-free pipeline,
[`classfile/ClassReader`](src/classfile/ClassReader.java), `A64Enc` and
`compiler/Baseline`, is the *same source* in both worlds: the writer runs it on the
seed JVM, and the image carries compiled copies that the VM uses on the metal.
Running the writer itself on the metal was dropped as a goal on 2026-08-28, so the
seed JVM stays (see [`PLAN.md`](PLAN.md) §M5).

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

## Licence

[Eclipse Public License 2.0](LICENSE) — the same licence as Jikes RVM and
OpenJ9, which keeps joe-ng compatible with the JVM prior art it draws concepts
from.
