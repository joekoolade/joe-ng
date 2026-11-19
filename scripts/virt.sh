#!/usr/bin/env bash
# Build a joe-ng image for QEMU's `virt` machine and boot it on the console.
#
# The Pi image cannot run on `virt` (RAM starts at 0x4000_0000 there, not 0), so the writer builds a separate one
# with --board virt: a start-up routine turns the MMU on so the image's 0x80000-based addresses hold, and the UART,
# GIC and timer drivers take their `virt` paths. See src/board/Board.java and src/writer/VirtPrelude.java.
#
# By default it runs under HVF -- hardware virtualisation on Apple silicon (-accel hvf -cpu host), several times
# faster than emulation. --tcg emulates a Cortex-A72 instead (any host). Four cores (PSCI CPU_ON).
#
# Usage: scripts/virt.sh [--tcg] [--net] [--fwd HOST:GUEST]... [MAIN [ARGS...]]
#   MAIN   the program /etc/init launches (e.g. demo/HelloDemo, SslEngineProbe); without it the image keeps the
#          ramfs/etc/init the tree already has.
#   --net  give the machine a network: a virtio-net card on QEMU user networking (DHCP hands out 10.0.2.15; the
#          host's loopback is 10.0.2.2; DNS at 10.0.2.3) and net=1 in /etc/init, so the VM brings it up before
#          MAIN runs (board.virt.VirtioNet). Needs MAIN.
#   --fwd HOST:GUEST  forward the host's TCP port HOST to the guest's GUEST (implies --net), e.g. --fwd 8080:80
#          for demo/WebServerDemo, then http://localhost:8080/ on the Mac.
# Quit QEMU with Ctrl-A X.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACCEL=hvf
NET=0
FWD=""
while [ $# -gt 0 ]; do
    case "$1" in
        --tcg) ACCEL=tcg; shift ;;
        --net) NET=1; shift ;;
        --fwd)
            [ $# -ge 2 ] || { echo "virt.sh: --fwd needs HOST:GUEST" >&2; exit 2; }
            NET=1
            FWD="$FWD,hostfwd=tcp::${2%%:*}-:${2##*:}"
            shift 2 ;;
        *) break ;;
    esac
done
if [ "$NET" = 1 ] && [ $# -eq 0 ]; then
    echo "virt.sh: --net needs MAIN (it writes net=1 into the image's /etc/init)" >&2
    exit 2
fi
if [ "$ACCEL" = hvf ] && { [ "$(uname -s)" != Darwin ] || [ "$(uname -m)" != arm64 ]; }; then
    echo "virt.sh: HVF needs an Apple-silicon Mac; emulating instead (--tcg)" >&2
    ACCEL=tcg
fi

IMG=/tmp/joeng-virt.img
INIT_ARGS=()
if [ $# -gt 0 ]; then
    INIT=/tmp/joeng-virt.init
    printf 'main=%s\n' "$1" > "$INIT"
    shift
    if [ $# -gt 0 ]; then
        printf 'args=%s\n' "$*" >> "$INIT"
    fi
    if [ "$NET" = 1 ]; then
        printf 'net=1\n' >> "$INIT"
    fi
    INIT_ARGS=(--init "$INIT")
fi

make -C "$ROOT" build >/dev/null
(cd "$ROOT" && java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
    -cp out writer.BuildRuntimeImage out "$IMG" --board virt ${INIT_ARGS[@]+"${INIT_ARGS[@]}"} > /tmp/joeng-virt-build.txt)
echo "== $IMG built (writer log: /tmp/joeng-virt-build.txt); booting under $ACCEL; Ctrl-A X quits ==" >&2

if [ "$ACCEL" = hvf ]; then
    MACHINE=(-M virt -accel hvf -cpu host)
else
    MACHINE=(-M virt -cpu cortex-a72)
fi
NETDEV=()
if [ "$NET" = 1 ]; then
    NETDEV=(-netdev "user,id=n0$FWD" -device virtio-net-device,netdev=n0)
fi
exec qemu-system-aarch64 "${MACHINE[@]}" -smp 4 -m 4G -device virtio-rng-device ${NETDEV[@]+"${NETDEV[@]}"} \
    -global virtio-mmio.force-legacy=false -kernel "$IMG" -display none -serial mon:stdio -no-reboot
