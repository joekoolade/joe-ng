#!/usr/bin/env sh
# Assemble everything the Pi 4 needs to boot joe-ng into ./sdcard/, ready to copy
# onto a FAT32 boot partition. Fetches the two GPU-firmware files (start4.elf,
# fixup4.dat) from the official Raspberry Pi firmware repo — those are the one
# external seed joe-ng does not build (PLAN.md §0), not part of the VM.
#
# Usage: scripts/sdcard.sh          (build image + assemble sdcard/)
# It then copies the boot files to /Volumes/bootfs (macOS), verifies them and ejects the card.
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

# Copy ONLY what the Pi boots from. sdcard/ also collects saved images (kernel8-<tag>.img) from earlier runs;
# a `cp sdcard/*` copied all of them, filled the 512 MB boot partition, and stopped before the verify below.
CARD=/Volumes/bootfs
BOOT_FILES="kernel8.img config.txt start4.elf fixup4.dat"
for b in "$ROOT"/armstub/*.bin; do
    BOOT_FILES="$BOOT_FILES $(basename "$b")"
done
# cp -X: NO extended attributes. macOS writes each file's xattrs (com.apple.provenance) to a "._<name>" AppleDouble
# sidecar on FAT, and on 2026-10-24 that sidecar write failed on this card -- "could not copy extended attributes
# ... Attribute not found", then "No space left on device" with 200 MB free -- while the data itself had landed.
# The Pi reads none of the sidecars, so they are not written at all.
for f in $BOOT_FILES; do
    cp -X "$OUT/$f" "$CARD/$f"
done
ls -tl "$CARD" | head -n 10

# VERIFY BEFORE EJECTING. A matching byte COUNT is not a matching FILE, and once the card is ejected the
# reader drops it off the bus entirely -- so there is no second chance to check: a remount afterwards fails
# with "Failed to find disk". A boot from a half-written card looks exactly like a boot from a good one that
# regressed, and this project has already lost a boot to reading output that predated the flash.
#
# EVERY file is checked and every failure named, then the failures are rewritten ONCE: on 2026-10-24 kernel8.img
# came back different from offset 959489 at the same length, and the check used to stop at that first failure,
# so a second bad file (armstub8-joe.bin) only showed after a hand-run recheck.
verify()
{
    bad=""
    for f in $BOOT_FILES; do
        if cmp -s "$OUT/$f" "$CARD/$f"; then
            echo "  OK   $f"
        else
            echo "  FAIL $f DIFFERS" >&2
            bad="$bad $f"
        fi
    done
}
echo "== verifying card contents =="
verify
if [ -n "$bad" ]; then
    echo "== rewriting:$bad ==" >&2
    for f in $bad; do
        cp -X "$OUT/$f" "$CARD/$f"
    done
    sync
    verify
    if [ -n "$bad" ]; then
        echo "  STILL DIFFERS after a rewrite:$bad -- do NOT boot this card (replace it)" >&2
        exit 1
    fi
fi

# AND AGAIN FROM THE MEDIUM. A read straight after a write can be answered from the page cache, so the check
# above can pass on bytes the card never stored. Unmount and remount the same partition (named by the volume
# itself, seconds ago -- not a remembered disk number), then compare once more before ejecting.
sync
DEV=$(diskutil info "$CARD" | awk '/Device Identifier:/ {print $3}')
n=0
until diskutil unmount "$CARD" >/dev/null; do                # Spotlight can dissent, as it can for eject below
    n=$((n + 1))
    if [ $n -ge 5 ]; then
        echo "  unmount FAILED after $n tries -- the card is verified once; eject it by hand" >&2
        exit 1
    fi
    sleep 3
done
diskutil mount "$DEV" >/dev/null
echo "== verifying after remount ($DEV) =="
verify
if [ -n "$bad" ]; then
    echo "  DIFFERS after remount:$bad -- the card did not store what was written; do NOT boot it" >&2
    exit 1
fi

# Eject by VOLUME, not by a device number: the disk number is assigned at insertion and can name another disk.
# The first attempt can fail with "at least one volume could not be unmounted" while macOS is still indexing
# the files just written (nothing held them open when it was checked); a retry a few seconds later succeeds.
sync
n=0
until diskutil eject "$CARD"; do
    n=$((n + 1))
    if [ $n -ge 5 ]; then
        echo "  eject FAILED after $n tries -- the card is verified; eject it by hand" >&2
        exit 1
    fi
    sleep 3
done
