#!/usr/bin/env sh
# Assemble everything the Pi 4 needs to boot joe-ng into ./sdcard/, ready to copy
# onto a FAT32 boot partition. Fetches the two GPU-firmware files (start4.elf,
# fixup4.dat) from the official Raspberry Pi firmware repo — those are the one
# external seed joe-ng does not build (PLAN.md §0), not part of the VM.
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
cp "$ROOT/kernel8.img" "$ROOT/config.txt" $ROOT/armstub/*.bin "$OUT/"

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
cp sdcard/* /Volumes/bootfs
ls -tl /Volumes/bootfs | head -n 10

# VERIFY BEFORE EJECTING. A matching byte COUNT is not a matching FILE, and once the card is ejected the
# reader drops it off the bus entirely -- so there is no second chance to check: a remount afterwards fails
# with "Failed to find disk". A boot from a half-written card looks exactly like a boot from a good one that
# regressed, and this project has already lost a boot to reading output that predated the flash.
echo "== verifying card contents =="
for f in kernel8.img config.txt; do
    if cmp -s "sdcard/$f" "/Volumes/bootfs/$f"; then
        echo "  OK   $f"
    else
        echo "  FAIL $f DIFFERS -- do NOT boot this card" >&2
        exit 1
    fi
done

diskutil eject /dev/disk4s1