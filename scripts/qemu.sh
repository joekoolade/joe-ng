#!/usr/bin/env sh
# Smoke-test the image under QEMU raspi4b. This is a TEST AID ONLY — QEMU's
# BCM2711 peripheral emulation is partial and is NOT ground truth (PLAN.md §6,
# CLAUDE.md). Real validation is a Pi 4 over USB-TTL serial from M0 onward.
#
# M1 image prints "hello from joe2" then parks. The mini-UART (UART1) is QEMU's
# SECOND serial, so we route serial0 (PL011) to null and serial1 to stdio.
# Success: the line prints and the guest sits parked (no reset). Ctrl-A then X
# to exit. (Real Pi 4 over USB-TTL serial is the actual ground truth.)
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG="${1:-$ROOT/kernel8.img}"

[ -f "$IMG" ] || { echo "no image at $IMG — run scripts/build.sh first" >&2; exit 1; }

exec qemu-system-aarch64 \
    -M raspi4b \
    -kernel "$IMG" \
    -serial null -serial stdio \
    -display none \
    -no-reboot
