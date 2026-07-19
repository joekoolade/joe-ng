#!/usr/bin/env sh
# Assemble everything the Pi 4 needs to boot joe2 into ./sdcard/, ready to copy
# onto a FAT32 boot partition. Fetches the two GPU-firmware files (start4.elf,
# fixup4.dat) from the official Raspberry Pi firmware repo — those are the one
# external seed joe2 does not build (PLAN.md §0), not part of the VM.
#
# Usage: scripts/sdcard.sh          (build image + assemble sdcard/)
# Then:  cp sdcard/* /Volumes/BOOT/  (macOS)  or  /media/$USER/bootfs/ (Linux)
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/sdcard"
FW="https://github.com/raspberrypi/firmware/raw/master/boot"

echo "== building kernel8.img =="
make -C "$ROOT" image >/dev/null

mkdir -p "$OUT"
cp "$ROOT/kernel8.img" "$ROOT/config.txt" "$OUT/"

echo "== fetching Pi 4 GPU firmware (start4.elf, fixup4.dat) =="
for f in start4.elf fixup4.dat; do
    if [ -f "$OUT/$f" ]; then
        echo "  have $f"
    else
        echo "  downloading $f"
        curl -fsSL "$FW/$f" -o "$OUT/$f"
    fi
done

echo
echo "sdcard/ ready:"
ls -l "$OUT"
echo
echo "Copy these to the FAT32 boot partition of an SD card, insert into the Pi 4,"
echo "connect a USB-TTL serial adapter (see scripts/flash.md), and power on."
