# Flashing joe2 to a real Raspberry Pi 4

QEMU is a test aid, not ground truth (PLAN.md §6). This is how to run the same
`kernel8.img` on real silicon and watch it over serial. You need a Pi 4 (4 GB),
a micro-SD card, and a **USB-to-TTL serial (3.3 V!) adapter**.

## 1. Assemble the SD-card files

```
scripts/sdcard.sh
```

This builds `kernel8.img` and downloads the two GPU-firmware files into `sdcard/`:

| file          | what it is                                         |
|---------------|----------------------------------------------------|
| `kernel8.img` | joe2 (our image)                                   |
| `config.txt`  | `arm_64bit=1`, `enable_uart=1`, `core_freq=250`    |
| `start4.elf`  | Pi 4 GPU firmware (the one seed we don't build)    |
| `fixup4.dat`  | Pi 4 GPU firmware                                   |

If you have no network on this machine, grab `start4.elf` and `fixup4.dat`
manually from <https://github.com/raspberrypi/firmware/tree/master/boot> and
drop them in `sdcard/`.

## 2. Format + copy

- Format the SD card as a single **FAT32** partition (the Pi 4 boot ROM reads
  FAT32). On macOS Disk Utility: "MS-DOS (FAT)". On Linux: `mkfs.vfat`.
- Copy everything in `sdcard/` to the root of that partition:
  - macOS:  `cp sdcard/* /Volumes/<NAME>/ && diskutil eject /Volumes/<NAME>`
  - Linux:  `cp sdcard/* /media/$USER/<NAME>/ && sync && umount /media/$USER/<NAME>`

No `bootcode.bin` is needed on the Pi 4 (unlike the Pi 3). No device tree is
loaded (`config.txt` leaves `device_tree=` empty) — joe2 owns all the hardware.

## 3. Wire up the serial adapter

joe2 prints on the **mini-UART (UART1)**, GPIO14/15. With the Pi powered OFF,
connect (see the 40-pin header pinout):

| Pi header               | adapter |
|-------------------------|---------|
| GND — physical pin 6    | GND     |
| GPIO14 / TXD — pin 8    | **RX**  |
| GPIO15 / RXD — pin 10   | **TX**  |

TX↔RX cross over. **Do not** connect the adapter's 3.3 V/5 V power — power the Pi
from its own USB-C supply. Never use a 5 V-logic adapter; the Pi's GPIO is 3.3 V.

## 4. Open the serial console (115200 8N1)

Find the adapter's device, then:

- macOS:  `ls /dev/tty.usbserial-*` → `screen /dev/tty.usbserial-XXXX 115200`
- Linux:  `ls /dev/ttyUSB*`        → `screen /dev/ttyUSB0 115200`
  (or `picocom -b 115200 /dev/ttyUSB0`, or `minicom -b 115200 -D ...`)

Exit `screen` with `Ctrl-A` then `k` (macOS) / `Ctrl-A Ctrl-\` .

## 5. Power on

Insert the SD card, plug in power. Within a second or two you should see:

```
hello from joe2
k
AB
3
7
W?
YNW
RP
E
U
R
*M
```

Then the board parks (`wfe`). That's the full feature run — compiled Java on bare
metal — on real hardware. The lines walk the milestones: the banner + object
model + arrays + statics + `<clinit>` + class hierarchy + `instanceof` +
interfaces (`hello…`→`RP`), exceptions (`E`, `U`), the mark-sweep GC (`R`), and
finally the **on-metal runtime class loader** (`*M`) — `*` (0x2A) from two
cross-class Guest/Helper classes parsed, JIT-compiled and linked on the metal,
and `M` from `java.lang.Math.max` loaded out of the JDK's `java.base`. The last
line is the newest and most demanding, so if silicon stops before `*M`, note the
last character: it pinpoints how far the metacircular loader got.

## Troubleshooting

- **Nothing at all.** Re-seat the SD card and check the FAT32 files are at the
  partition root (not in a subfolder). Confirm `start4.elf`/`fixup4.dat` are
  present. Try a known-good SD card.
- **Garbled / wrong characters (but steady stream).** The baud is off because the
  core clock differs from what the divisor assumes. joe2 now targets a **500 MHz**
  core (`core_freq=500`, `Bcm2711.BAUD_115200 = 541`) — this is what real Pi 4
  silicon uses; the old Pi 3 recipe (`core_freq=250` / `270`) ran ~2× too fast and
  garbled. If 500 MHz still garbles, set `core_freq=250` + `BAUD_115200 = 270` and
  rebuild, or sweep other divisors. mini-UART baud = `core_clock / (8*(divisor+1))`.
- **A little output then it stops.** A hang in the boot/EL-drop/UART path — that's
  real-silicon behavior QEMU didn't exercise. The last character printed tells you
  how far it got.
- **Red LED only, no green blink.** Firmware isn't loading the image — usually a
  bad `config.txt` or missing firmware file.
