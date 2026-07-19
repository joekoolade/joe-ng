#!/usr/bin/env sh
# Thin wrapper — the Makefile is the build front door now (compile + test + emit
# kernel8.img). Kept so existing references and muscle memory keep working; run
# `make` / `make test` / `make image` / `make qemu` directly for individual steps.
#
# The JDK here is the seed host (PLAN.md §0): it runs the boot-image writer for
# the first image. It is not part of the VM and disappears at self-hosting (M5).
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec make -C "$ROOT" all
