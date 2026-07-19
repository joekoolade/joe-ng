#!/usr/bin/env sh
# Build joe2 with a stock JDK (the seed) — compile the all-Java writer + assembler,
# run the bit-for-bit A64 encoding tests, then emit kernel8.img.
#
# The JDK here is the seed host (PLAN.md §0): it runs the boot-image writer for
# the first image. It is not part of the VM and disappears at self-hosting (M5).
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/out"
IMG="$ROOT/kernel8.img"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "== compiling (seed javac) =="
# shellcheck disable=SC2046
javac -d "$OUT" $(find "$ROOT/src" "$ROOT/test" -name '*.java')

echo "== A64 encoding tests (bit-for-bit vs ARM ARM) =="
java -cp "$OUT" asm.A64Test

echo "== compiler tests (bytecode -> A64) =="
java -cp "$OUT" compiler.CompilerTest "$OUT"

echo "== emitting kernel8.img (M1c: boot compiled from vm.VM.boot bytecode) =="
java -cp "$OUT" writer.BuildCompiledBootImage "$OUT" "$IMG" | tail -1
# (writer.BuildBootImage still emits the equivalent hand-assembled first-light image)

echo "== image =="
ls -l "$IMG"
