#!/usr/bin/env sh
# Boot the REAL JUnit ConsoleLauncher on QEMU, demand-loaded from ramfs/lib/junit.jar.
#
# Usage: scripts/run-launcher.sh <seconds> [log-path]
#
# A launcher boot is ~10 minutes, so the serial log is written live to the given path (default
# /tmp/launcher.log) and truncated up front -- a stale log from a previous run reads exactly like this one,
# which has caused real confusion here before.
#
# The committed manifest is always restored, including on failure: ramfs/etc/init is TRACKED, and leaving a
# generated one behind is how it gets committed by accident.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SECS="${1:-900}"
OUT="${2:-/tmp/launcher.log}"

SAVED="$(mktemp)"
cp ramfs/etc/init "$SAVED"
restore() { cp "$SAVED" ramfs/etc/init; rm -f "$SAVED"; }
trap restore EXIT INT TERM

# --disable-ansi-colors is the launcher's own condition: picocli renders a different wrap path with ansi AUTO,
# and chasing that one proved nothing about this one.
printf 'main=org/junit/platform/console/ConsoleLauncher\nargs=execute --select-class=SleepSanity --disable-ansi-colors --disable-banner\nclasspath=/lib/junit.jar\n' > ramfs/etc/init

echo "== build launcher image =="
java --add-opens java.base/java.lang=ALL-UNNAMED -cp out writer.BuildRuntimeImage out /tmp/launcher.img >/dev/null
ls -l /tmp/launcher.img

: > "$OUT"
echo "== boot QEMU (${SECS}s) -- live log: $OUT =="
qemu-system-aarch64 -M raspi4b -kernel /tmp/launcher.img -serial null -serial stdio -display none -no-reboot \
    >"$OUT" 2>&1 &
PID=$!
i=0
while [ "$i" -lt "$SECS" ]; do
    if grep -qaE "main returned normally|DENYLIST TRAP|Exception in thread|JIT unsupported" "$OUT"; then
        # GRACE PERIOD, and it is not politeness: the marker appears on the FIRST line of a report whose stack
        # trace is still being written a frame at a time over a 115200 baud UART. Killing on the marker chops
        # the trace off mid-frame -- which cost a whole boot here, because the frame that names the FAULTING
        # class is the last one printed.
        sleep 25
        break
    fi
    sleep 5
    i=$((i + 5))
done
kill "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true

echo "== ending, waited ${i}s =="
tail -25 "$OUT"
echo "== full log: $OUT =="
