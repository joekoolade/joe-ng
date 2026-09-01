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

# The serial log goes straight to /tmp/junit.log rather than to a mktemp file copied at the end, so it is
# READABLE WHILE THE RUN IS STILL GOING -- a boot takes minutes, and the interesting line is often printed long
# before the run ends. Announced before the boot for the same reason: told only at the end, the path is no use
# while you are waiting. Truncated up front so a stale log from a previous run can never be mistaken for this
# one (that cost real confusion: the old copy-at-the-end left the PREVIOUS run's output in place throughout).
OUT=/tmp/junit.log
: > "$OUT"
echo "== boot QEMU (${SECS}s) -- live log: $OUT =="
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
echo "== full log: $OUT =="
