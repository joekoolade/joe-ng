#!/bin/bash
#
# Copyright (c) 2026 Joseph Kulig.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Created: 2026-10-26
#
# The no-manifest demo suite on QEMU: the full regression's suite step.
#
# Usage: scripts/demo-suite.sh <tag> [checkout]
#   <tag>       names the files: /tmp/arm-<tag>.{build,w,img,log,txt}
#   [checkout]  the joe-ng tree to build (default: this script's own). The control run passes ../joe-ng-wt.
#
# It empties ramfs/etc/init for the build (the suite runs when /etc/init names no program) and restores it before
# booting -- `init-ok` says the restore matched. It stops on the suite's end marker or a failure marker, or after
# 5400 s. If the log stops growing for 300 s it samples every core's registers through the QEMU monitor
# (/tmp/qregs-<tag>-<n>.txt) before stopping, and the image is built with JOENG_SYMMAP so those PCs can be named
# from /tmp/arm-<tag>.w.
#
# Was a /tmp script, rebuilt by hand after macOS purged it (2026-10-25): /tmp is not a place for the instrument the
# full regression depends on. Run it ALONE -- each raspi4b QEMU uses ~3 host cores, and running it beside
# Regress -j 4 is what produced GC watchdog lines and time-outs.
set -u
T=$1
TREE=${2:-$(cd "$(dirname "$0")/.." && pwd)}
cd "$TREE" || exit 1
cp ramfs/etc/init /tmp/i-$T.s && : > ramfs/etc/init
make build jdktests plugins > /tmp/arm-$T.build 2>&1
JOENG_SYMMAP=1 java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
    -cp out writer.BuildRuntimeImage out /tmp/arm-$T.img > /tmp/arm-$T.w 2>&1
echo "wr=$?"
cp /tmp/i-$T.s ramfs/etc/init
git diff --quiet ramfs/etc/init && echo init-ok
rm -f /tmp/qmon-$T.sock
qemu-system-aarch64 -M raspi4b -kernel /tmp/arm-$T.img -serial null -serial stdio -display none -no-reboot \
    -monitor unix:/tmp/qmon-$T.sock,server,nowait > /tmp/arm-$T.log 2>&1 &
Q=$!
t=0
last=0
still=0
while [ $t -lt 5400 ]; do
    sleep 15
    t=$((t + 15))
    if LC_ALL=C tr -d '\r' < /tmp/arm-$T.log | LC_ALL=C grep -aqE \
        "self-build retired|^Exception in thread|DENYLIST TRAP|^ *FAULT |BOOT RE-ENTERED|LINK FAILED|STW TIMEOUT|JIT unsupported"; then
        break
    fi
    sz=$(stat -f %z /tmp/arm-$T.log)
    if [ "$sz" = "$last" ]; then
        still=$((still + 15))
    else
        still=0
        last=$sz
    fi
    if [ $still -ge 300 ]; then
        echo "STALL at ${t}s (log unchanged 300 s) -- sampling every core's registers"
        for i in 1 2 3 4 5 6 7 8; do
            printf 'info registers -a\n' | nc -U -w 2 /tmp/qmon-$T.sock > /tmp/qregs-$T-$i.txt 2>&1
            sleep 2
        done
        break
    fi
done
sleep 2
kill $Q 2>/dev/null
echo "stopped after ${t}s"
LC_ALL=C tr -d '\r' < /tmp/arm-$T.log > /tmp/arm-$T.txt
LC_ALL=C grep -ac "\[main returned normally\]" /tmp/arm-$T.txt
LC_ALL=C grep -a "churnMB\|lisp: evals\|finish HML\|smp sched" /tmp/arm-$T.txt | cut -c1-110
n=$(LC_ALL=C grep -an "churnMB" /tmp/arm-$T.txt | cut -d: -f1)
[ -n "$n" ] && awk -v n=$n 'NR>=n && NR<=n+9' /tmp/arm-$T.txt | LC_ALL=C grep -a "gc: collections" | cut -c1-30
for m in "^ *FAULT " "esr=0x" "BOOT RE-ENTERED" "DENYLIST TRAP" "LINK FAILED" "VIRTUALRESOLVE FAILED" "unclaimed pc" \
         "heap OOM" "STW TIMEOUT" "Exception in thread" "JIT unsupported" "UNRESOLVED NEW" "CAP EXCEEDED" \
         "DISPATCH ON UNREGISTERED" "^FAIL " "not implemented" "NullPointerException" "LOADER LOCK stuck" "RUNTRAMP" \
         "CTOR SKIPPED" "TAIL TARGET" "LAZY INDEX" "UNBOXABLE"; do
    printf "%s=%s | " "$m" "$(LC_ALL=C grep -ac "$m" /tmp/arm-$T.txt)"
done
echo
LC_ALL=C grep -a -A3 "^Exception in thread" /tmp/arm-$T.txt | head -5 | cut -c1-150
