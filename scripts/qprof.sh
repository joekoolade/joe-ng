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
# Created: 2026-10-28
#
# Profile one program on QEMU by sampling the busy cores from the host.
#
# Usage: scripts/qprof.sh <MainClass> [outdir] [intervalSec] [maxSec]
#   <MainClass>   what /etc/init runs (main=<MainClass>), e.g. InvokeGapProbe
#   [outdir]      default /tmp/qprof-<MainClass>
#   [intervalSec] seconds between samples (default 0.25)
#   [maxSec]      stop after this long (default 900)
# Then:  perl scripts/qprof-tally.pl <outdir> [fromSec toSec] [top]
#
# The VM has no profiler of its own, and the batch lines do not time lazy compiles. This builds an image with
# JOENG_SYMMAP (so every image-code address can be named), boots it with a QEMU monitor socket, and on each tick
# records every busy core's PC, LR and each image-code word in the top 2 KB of its stack. The loader, compiler,
# GC and runtime are image code, so the samples separate VM work by method; JIT-compiled guest code shows only
# as <arena>. Every serial line is timestamped (outdir/log.txt), so a program's own output marks the windows.
#
# Found both 2026-10-28 InvokeGapProbe costs: the loader's linear name scans (#449, 266 s -> 111 s) and the
# allocator's first-fit walk (#450, -> 87 s). Run `make build` first; run it ALONE (no Regress beside it).
set -u
M=$1
OUT=${2:-/tmp/qprof-$M}
IV=${3:-0.25}
MAX=${4:-900}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$OUT"
echo "main=$M" > "$OUT/init"
cd "$ROOT" || exit 1
JOENG_SYMMAP=1 java -Xss64m --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
    -cp out writer.BuildRuntimeImage out "$OUT/image.img" --init "$OUT/init" > "$OUT/build.txt" 2>&1 || {
    echo "image build failed: $OUT/build.txt"
    exit 1
}
grep -a ' symmap ' "$OUT/build.txt" | awk '{print $2, $3, $4}' > "$OUT/syms.txt"
perl "$ROOT/scripts/qprof.pl" "$OUT/image.img" "$OUT/syms.txt" "$OUT" "$IV" "$MAX"
echo "samples: $(wc -l < "$OUT/stacks.txt")   log: $OUT/log.txt"
tail -1 "$OUT/log.txt"
