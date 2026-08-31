#!/usr/bin/env sh
# Run stock OpenJDK `@run junit` tests on QEMU through MetalJUnit (the annotation-driven metal runner).
#
# Usage: scripts/run-junit.sh <seconds> <Class> [Class...]
#
# The classes must already be compiled into the classDir (out/) -- add their sources to JDKTESTS, or use
# scripts/junit-stage.sh to copy + compile a test from the JDK tree first. This only sets the manifest,
# builds a throwaway image (/tmp/junit.img -- kernel8.img untouched) and boots QEMU.
#
# The NetDemo manifest is always restored, including on failure: ramfs/etc/init is TRACKED, and leaving a
# generated one behind is how it gets committed by accident.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SECS="$1"
shift
CLASSES="$*"

SAVED="$(mktemp)"
cp ramfs/etc/init "$SAVED"
restore() { cp "$SAVED" ramfs/etc/init; rm -f "$SAVED"; }
trap restore EXIT INT TERM

printf 'main=MetalJUnit\nargs=%s\nclasspath=/lib/junit.jar\n' "$CLASSES" > ramfs/etc/init

echo "== build image: $CLASSES =="
java --add-opens java.base/java.lang=ALL-UNNAMED -cp out writer.BuildRuntimeImage out /tmp/junit.img >/dev/null
ls -l /tmp/junit.img

echo "== boot QEMU (${SECS}s) =="
OUT="$(mktemp)"
qemu-system-aarch64 -M raspi4b -kernel /tmp/junit.img -serial null -serial stdio -display none -no-reboot \
    >"$OUT" 2>&1 &
PID=$!
i=0
while [ "$i" -lt "$SECS" ]; do
    grep -qE "^(ALL PASSED|FAILURES)$" "$OUT" && break
    sleep 1
    i=$((i + 1))
done
kill "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true

echo "== output (launch..end), waited ${i}s =="
sed -n '/^launch /,$p' "$OUT" | grep -vE "^  load |^load |terminating on signal"
cp "$OUT" /tmp/junit.log
rm -f "$OUT"
echo "== full log: /tmp/junit.log =="
