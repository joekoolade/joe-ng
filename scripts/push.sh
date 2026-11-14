#!/usr/bin/env sh
# Update a running Pi's boot files over the network, with a trial boot before anything live changes.
#
# Usage: scripts/push.sh [-n] [-k] [-f] [-i image] [-e init] [-t secs] <pi-host>
#   -n        dry run: build, compare with the card, say what would be pushed, push nothing
#   -k        the kernel only (not config.txt, the firmware or the armstubs)
#   -f        push an image even if it lacks this checkout's boot token or the web server
#   -i image  push this kernel8.img instead of building one
#   -e init   the /etc/init baked into the built image (default scripts/push-init: the web server, WiFi on)
#   -t secs   how long to wait for the trial boot to answer (default 120)
#
# The steps, against guestsrc/demo/BootUpdate's endpoints:
#   1. build out/push-kernel8.img (make image IMAGE_INIT=...), read its build id from the 32-byte trailer
#   2. ask the Pi for the SHA-256 of each boot file on its card; files that already match are not sent
#   3. POST /boot/discard (an earlier push's leftovers), then PUT each changed file as <name>.new with X-Sha256
#   4. POST /reboot?tryboot=1: the firmware boots the staged files ONCE, from tryboot.txt
#   5. wait for the Pi to go down, then for GET /version to answer
#   6. POST /boot/commit, which the Pi refuses unless it is running the staged kernel; then re-hash the live files
#
# Nothing live changes before step 6. If the trial never answers, a power cycle boots the old files.
# The token is ramfs/etc/boot.token (baked into the image the Pi runs), or $JOE_BOOT_TOKEN.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRY=0
KERNEL_ONLY=0
FORCE=0
IMAGE=""
INIT="$ROOT/scripts/push-init"
WAIT=120
while getopts nkfi:e:t: o; do
    case $o in
        n) DRY=1 ;;
        k) KERNEL_ONLY=1 ;;
        f) FORCE=1 ;;
        i) IMAGE=$OPTARG ;;
        e) INIT=$OPTARG ;;
        t) WAIT=$OPTARG ;;
        *) sed -n '2,10p' "$0" >&2; exit 2 ;;
    esac
done
shift $((OPTIND - 1))
if [ $# -ne 1 ]; then
    sed -n '2,10p' "$0" >&2
    exit 2
fi
PI=$1
URL="http://$PI"

if [ -n "${JOE_BOOT_TOKEN:-}" ]; then
    TOKEN=$JOE_BOOT_TOKEN
elif [ -f "$ROOT/ramfs/etc/boot.token" ]; then
    TOKEN=$(tr -d ' \r\n' < "$ROOT/ramfs/etc/boot.token")
else
    echo "no token: create ramfs/etc/boot.token (and rebuild the Pi's image) or set JOE_BOOT_TOKEN" >&2
    exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

sha() { shasum -a 256 "$1" | awk '{print $1}'; }
now() { date +%s; }

# One request. Sets CODE to the HTTP status (000 when there was no answer) and BODY to the reply. Not called inside
# $(...): a subshell's CODE would be lost.
req()
{
    method=$1
    path=$2
    shift 2
    CODE=$(curl -sS -o "$TMP/body" -w '%{http_code}' -X "$method" -H "X-Boot-Token: $TOKEN" "$@" "$URL$path" \
        </dev/null 2>"$TMP/err") || CODE=000
    BODY=$(cat "$TMP/body" 2>/dev/null || true)
}

# ---- 1. the image -------------------------------------------------------------------------------------------
if [ -z "$IMAGE" ]; then
    IMAGE="$ROOT/out/push-kernel8.img"
    echo "== building $IMAGE (init $INIT) =="
    make -C "$ROOT" image IMG="$IMAGE" IMAGE_INIT="$INIT" >"$TMP/build.log" 2>&1 || {
        tail -n 30 "$TMP/build.log" >&2
        echo "build failed" >&2
        exit 1
    }
fi
if [ "$(tail -c 32 "$IMAGE" | head -c 16)" != "joe-ng build id" ]; then   # $(...) drops the magic's newline
    echo "$IMAGE has no build-id trailer: not a joe-ng image, or one from before build ids" >&2
    exit 1
fi
NEW=$(tail -c 16 "$IMAGE")
echo "image $IMAGE: build $NEW, $(wc -c < "$IMAGE" | tr -d ' ') bytes"

# An image without the token or the web server boots, but can never be committed or updated again over the network:
# the trial would come up silent and need a power cycle. Catch that before sending 40 MB.
if ! grep -q -a -F "$TOKEN" "$IMAGE"; then
    msg="the image does not carry this token: the Pi could not commit it, nor take a later push"
    [ $FORCE -eq 1 ] && echo "warning: $msg" >&2 || { echo "$msg (-f to push anyway)" >&2; exit 1; }
fi
if ! grep -q -a -F "main=demo/WebServerDemo" "$IMAGE"; then
    msg="the image does not run demo/WebServerDemo: the trial boot would never answer /version"
    [ $FORCE -eq 1 ] && echo "warning: $msg" >&2 || { echo "$msg (-f to push anyway)" >&2; exit 1; }
fi

# ---- 2. what differs ----------------------------------------------------------------------------------------
RUNNING=$(curl -sS --max-time 10 "$URL/version" 2>"$TMP/err") || {
    echo "$URL/version did not answer: $(cat "$TMP/err")" >&2
    exit 1
}
echo "pi $PI: running build $RUNNING"

# "live-name local-path" pairs, the same set scripts/sdcard.sh copies to a card.
: > "$TMP/files"
echo "kernel8.img $IMAGE" >> "$TMP/files"
if [ $KERNEL_ONLY -eq 0 ]; then
    echo "config.txt $ROOT/config.txt" >> "$TMP/files"
    for f in start4.elf fixup4.dat; do
        if [ -f "$ROOT/sdcard/$f" ]; then
            echo "$f $ROOT/sdcard/$f" >> "$TMP/files"
        else
            echo "  (no sdcard/$f here: scripts/sdcard.sh fetches it; the card's copy is left alone)"
        fi
    done
    for b in "$ROOT"/armstub/*.bin; do
        echo "$(basename "$b") $b" >> "$TMP/files"
    done
fi

: > "$TMP/push"
while read -r name path; do
    mine=$(sha "$path")
    theirs=$(curl -sS --max-time 120 "$URL/boot/sha256/$name" 2>/dev/null | tr -d '\r\n' || true)
    if [ "$theirs" = "$mine" ]; then
        echo "  same     $name"
    else
        echo "  CHANGED  $name"
        echo "$name $path $mine" >> "$TMP/push"
    fi
done < "$TMP/files"

if [ ! -s "$TMP/push" ]; then
    echo "the card already has these files; nothing to push"
    exit 0
fi
if [ $DRY -eq 1 ]; then
    echo "dry run: nothing pushed"
    exit 0
fi
KERNEL_PUSHED=0
grep -q '^kernel8.img ' "$TMP/push" && KERNEL_PUSHED=1

# ---- 3. stage -----------------------------------------------------------------------------------------------
req POST /boot/discard
if [ "$CODE" != 200 ]; then
    echo "POST /boot/discard: $CODE $BODY" >&2
    exit 1
fi
while read -r name path mine; do
    size=$(wc -c < "$path" | tr -d ' ')
    t0=$(now)
    printf '  PUT %s.new (%s bytes) ... ' "$name" "$size"
    req PUT "/boot/$name.new" --max-time 900 -H "X-Sha256: $mine" -T "$path"
    t=$(( $(now) - t0 ))
    if [ "$CODE" != 200 ]; then
        echo "FAILED"
        echo "$CODE $BODY $(cat "$TMP/err")" >&2
        req POST /boot/discard
        echo "staged files discarded; nothing live changed" >&2
        exit 1
    fi
    if [ "$t" -gt 0 ]; then
        echo "ok, ${t} s ($((size / 1024 / t)) KB/s)"
    else
        echo "ok"
    fi
    echo "$BODY" | sed -n 's/^timing /      pi: /p'
done < "$TMP/push"

# ---- 4. trial boot ------------------------------------------------------------------------------------------
req POST '/reboot?tryboot=1'
if [ "$CODE" != 200 ]; then
    echo "POST /reboot?tryboot=1: $CODE $BODY" >&2
    echo "the files are staged; nothing live changed (scripts/push.sh again, or discard from $URL/boot)" >&2
    exit 1
fi
T0=$(now)
echo "trial boot started"

# ---- 5. wait for the trial ----------------------------------------------------------------------------------
# Down first: an answer from before the reset is the OLD image, which matters when no kernel was sent (the id does
# not change then, so it cannot tell the two boots apart).
went_down=0
while [ $(( $(now) - T0 )) -lt 20 ]; do
    if ! curl -s --max-time 2 -o /dev/null "$URL/version"; then
        went_down=1
        break
    fi
    sleep 1
done
if [ $went_down -eq 0 ]; then
    echo "the Pi still answers 20 s after the reboot request: it did not reset" >&2
    exit 1
fi
seen=""
up=0
while [ $(( $(now) - T0 )) -lt "$WAIT" ]; do
    seen=$(curl -s --max-time 4 "$URL/version" | tr -d '\r\n' || true)
    if [ -n "$seen" ]; then
        up=1
        break
    fi
    sleep 3
done
if [ $up -eq 0 ]; then
    echo "trial image did not come up in $WAIT s -- power-cycle to boot the old files" >&2
    exit 1
fi
echo "up after $(( $(now) - T0 )) s: build $seen"
if [ $KERNEL_PUSHED -eq 1 ] && [ "$seen" != "$NEW" ]; then
    echo "the Pi came back on build $seen, not the pushed $NEW: the firmware did not take the trial boot" >&2
    echo "nothing live changed; the files are still staged" >&2
    exit 1
fi

# ---- 6. commit ----------------------------------------------------------------------------------------------
req POST /boot/commit
if [ "$CODE" != 200 ]; then
    echo "POST /boot/commit: $CODE $BODY" >&2
    echo "not committed -- power-cycle to boot the old files" >&2
    exit 1
fi
echo "$BODY" | sed 's/^/  /'
bad=0
while read -r name path mine; do
    theirs=$(curl -sS --max-time 120 "$URL/boot/sha256/$name" 2>/dev/null | tr -d '\r\n' || true)
    if [ "$theirs" = "$mine" ]; then
        echo "  live OK  $name"
    else
        echo "  live DIFFERS $name: card $theirs, pushed $mine" >&2
        bad=1
    fi
done < "$TMP/push"
if [ $bad -ne 0 ]; then
    exit 1
fi
echo "committed: build $NEW is live on $PI's card"
