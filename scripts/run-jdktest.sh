#!/usr/bin/env sh
# Run a stock OpenJDK jtreg test (the @run main kind — no testng/junit) as a joe-ng guest program on QEMU.
#
# Usage: scripts/run-jdktest.sh <src.java> <MainClass> [args] [--keep]
#   <src.java>   path under /Users/joe/git/jdk/test/jdk/... (or an absolute path)
#   <MainClass>  the public class with main() (unnamed package -> just the class name)
#   [args]       optional program args (space-separated, quoted)
#   --keep       leave the copied test in test/jdk/ and DON'T restore the manifest (for wiring into JDKTESTS)
#
# It copies the test into joe-ng's test/jdk/ mirror, compiles it against the guest java.base overlay into the
# classDir (out/), points ramfs/etc/init at it, builds a throwaway image (/tmp/jdktest.img -- kernel8.img
# untouched), boots QEMU for ~25 s, and prints the launch..end slice. The NetDemo manifest is restored unless
# --keep is given. Real ground truth is a Pi 4; this is the fast triage loop.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SRC="$1"
CLASS="$2"
ARGS="${3:-}"
KEEP="${4:-}"
JDKROOT="/Users/joe/git/jdk/test/jdk"

case "$SRC" in
    /*) ABS="$SRC" ;;
    *)  ABS="$JDKROOT/$SRC" ;;
esac
[ -f "$ABS" ] || { echo "no such test: $ABS" >&2; exit 1; }

REL="${ABS#$JDKROOT/}"
DST="test/jdk/$REL"
mkdir -p "$(dirname "$DST")"
cp "$ABS" "$DST"

echo "== ensure base built =="
make build plugins >/dev/null

echo "== compile $CLASS against the guest overlay =="
javac -implicit:none -sourcepath '' --patch-module java.base=guestsrc --add-reads java.base=ALL-UNNAMED \
    -cp out -d out "$DST"

cp ramfs/etc/init /tmp/init.saved.$$
if [ -n "$ARGS" ]; then
    printf 'main=%s\nargs=%s\n' "$CLASS" "$ARGS" > ramfs/etc/init
else
    printf 'main=%s\n' "$CLASS" > ramfs/etc/init
fi

echo "== build image =="
java -cp out writer.BuildRuntimeImage out /tmp/jdktest.img >/dev/null

if [ "$KEEP" != "--keep" ] && [ "$ARGS" != "--keep" ]; then
    cp /tmp/init.saved.$$ ramfs/etc/init
fi
rm -f /tmp/init.saved.$$

echo "== boot QEMU (~25s) =="
OUT="$(mktemp)"
qemu-system-aarch64 -M raspi4b -kernel /tmp/jdktest.img -serial null -serial stdio -display none -no-reboot \
    >"$OUT" 2>&1 &
PID=$!
sleep 25
kill "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true

echo "== output (launch..end) =="
sed -n '/^launch /,$p' "$OUT" | grep -vE "^  load |terminating on signal"
rm -f "$OUT"
