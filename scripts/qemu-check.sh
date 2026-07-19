#!/usr/bin/env sh
# Functional smoke test: boot kernel8.img under QEMU raspi4b and assert it prints
# the expected banner over the mini-UART (serial1). QEMU is a test aid, not
# ground truth (real Pi 4 over USB-TTL serial is) — but this catches regressions
# in the assembler/compiler/writer automatically.
#
# Usage: scripts/qemu-check.sh [image] [expected-substring]
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG="${1:-$ROOT/kernel8.img}"
WANT="${2:-hello from joe2}"
OUT="$(mktemp)"

[ -f "$IMG" ] || { echo "no image at $IMG — run scripts/build.sh first" >&2; exit 1; }

qemu-system-aarch64 -M raspi4b -kernel "$IMG" \
    -serial null -serial stdio -display none -no-reboot >"$OUT" 2>&1 &
PID=$!
sleep 3
kill "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true

if grep -q "$WANT" "$OUT"; then
    echo "PASS: image printed \"$WANT\""
    rm -f "$OUT"
else
    echo "FAIL: expected \"$WANT\", got:"; cat "$OUT"; rm -f "$OUT"; exit 1
fi
