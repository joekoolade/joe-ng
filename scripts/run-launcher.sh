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
#
# EXIT CODE SAYS WHICH OF THREE ENDINGS HAPPENED, AND THAT IS NOT A NICETY -- IT IS A DEFECT THIS SCRIPT
# ALREADY CAUSED. Every ending used to exit 0, so a harness scoring arms by grepping the log could not tell
# a boot that FAILED from one that merely ran out of wall clock, and a contended rate run here scored a
# 720-second timeout (batch 94, no fault marker anywhere) as a FAILURE. That is a fabricated data point, and
# it pointed the wrong way: it read as the change under test being WORSE than its control.
#   0  the launcher finished     -- `main returned normally` / `Test run finished`
#   1  a fault the VM cannot survive -- `Exception in thread`, `JIT unsupported`, `LOCALS UNDERSIZED`,
#                                      `BOOT RE-ENTERED`, `heap OOM`, `STW TIMEOUT`, `ESR EC=`
#   2  the budget ran out with NO marker at all -- says NOTHING about the image, only about the machine
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SECS="${1:-900}"
OUT="${2:-/tmp/launcher.log}"

# The endings, kept as two separate patterns so the exit code can tell them apart. `DENYLIST TRAP` is in
# NEITHER, for the reason spelled out at the wait loop below.
DONE_RE="main returned normally|Test run finished"
FAIL_RE="Exception in thread|JIT unsupported|LOCALS UNDERSIZED|BOOT RE-ENTERED|heap OOM|STW TIMEOUT|ESR EC="

SAVED="$(mktemp)"
cp ramfs/etc/init "$SAVED"
restore() { cp "$SAVED" ramfs/etc/init; rm -f "$SAVED"; }
trap restore EXIT INT TERM

# --disable-ansi-colors is the launcher's own condition: picocli renders a different wrap path with ansi AUTO,
# and chasing that one proved nothing about this one.
printf 'main=org/junit/platform/console/ConsoleLauncher\nargs=execute --select-class=SleepSanity --disable-ansi-colors --disable-banner\nclasspath=/lib/junit.jar\n' > ramfs/etc/init

echo "== build launcher image =="
java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED -cp out writer.BuildRuntimeImage out /tmp/launcher.img >/dev/null
ls -l /tmp/launcher.img

: > "$OUT"
echo "== boot QEMU (${SECS}s) -- live log: $OUT =="
qemu-system-aarch64 -M raspi4b -kernel /tmp/launcher.img -serial null -serial stdio -display none -no-reboot \
    >"$OUT" 2>&1 &
PID=$!
i=0
while [ "$i" -lt "$SECS" ]; do
    # `DENYLIST TRAP` IS NOT A TERMINAL MARKER AND MUST NOT BE ONE. The ProcessImpl.init trap fires at
    # batch 21 of EVERY healthy launcher boot -- picocli's terminal-width thread reaches a denied native,
    # the trap force-releases the loader lock and the boot runs on to batch 139. Breaking on it killed the
    # run 25s later, every time, and a log that stops at batch 21 reads exactly like a VM that died there.
    # That is the same "I truncated my own evidence" failure this script's own header was written about.
    # Break on the REAL endings instead: the launcher's own completion, or a fault it cannot survive.
    if grep -qaE "$DONE_RE|$FAIL_RE" "$OUT"; then
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

# A FAULT OUTRANKS COMPLETION, because both can be present: the launcher prints `Test run finished` and
# exits 0 on a boot that also carried a fault marker, and scoring that as a pass is how a regression ships.
if grep -qaE "$FAIL_RE" "$OUT"; then
    CODE=1
    WHY="FAULT -- $(grep -aoE "$FAIL_RE" "$OUT" | sort -u | tr '\n' ' ')"
elif grep -qaE "$DONE_RE" "$OUT"; then
    CODE=0
    WHY="the launcher finished"
else
    CODE=2
    WHY="TIMED OUT after ${SECS}s with no marker -- this says nothing about the image, only about the machine"
fi

echo "== ending, waited ${i}s: $WHY (exit $CODE) =="
tail -25 "$OUT"
echo "== full log: $OUT =="
exit "$CODE"
