#!/usr/bin/env bash
# Build AnonpheneOS.
#
#   scripts/build.sh <target> <GrapheneOS release tag> [source dir]
#
# <target> is one of:
#   a Pixel codename (e.g. tokay)  the full build, for that phone only (strongest)
#   gsi                            a generic arm64 system image for most other phones
#                                  with an unlockable bootloader (see docs/DEVICES.md)
#   emulator                       an x86_64 image for the Android emulator on a PC
#
# Needs a Linux PC with about 32 GB of RAM (64 GB is comfortable) and 350 GB of free
# disk, plus the `repo` tool. Use the newest tag from GrapheneOS's releases page that
# supports your device. The build itself never uploads anything.
set -euo pipefail

target=${1:?a Pixel codename, gsi or emulator}
tag=${2:?GrapheneOS release tag}
src=${3:-$HOME/anonphene-src}
here=$(cd "$(dirname "$0")/.." && pwd)

mem_gb=$(awk '/MemTotal/ {print int($2/1048576)}' /proc/meminfo)
if (( mem_gb < 28 )); then
    echo "build.sh: this PC has ${mem_gb} GB of RAM; an Android build needs about 32 GB." >&2
    exit 1
fi

if [[ $target == gsi && ! -d "$src/keys/gsi" ]]; then
    # Checked before hours of building: a GSI must be signed with your own keys.
    echo "build.sh: a GSI needs your signing keys in $src/keys/gsi first." >&2
    echo "          After the sync, make them once: cd $src && script/generate-keys gsi" >&2
    echo "          (or run this once to sync, make the keys, then run it again)." >&2
    [[ -d "$src/.repo" ]] && exit 1
    gsi_keys_missing=1
fi

mkdir -p "$src"
cd "$src"
if [[ ! -d .repo ]]; then
    repo init -u https://github.com/GrapheneOS/platform_manifest.git -b "refs/tags/$tag"
fi
repo sync -j"$(nproc)" --force-sync --no-clone-bundle --no-tags

# Verify the tag is signed by GrapheneOS before building from it.
(cd .repo/manifests && git verify-tag "$tag")

python3 "$here/scripts/apply.py" "$src"

if [[ ${gsi_keys_missing:-0} == 1 ]]; then
    echo "build.sh: synced. Make your GSI keys now: cd $src && script/generate-keys gsi" >&2
    echo "          then run this again to build and sign." >&2
    exit 1
fi

source build/envsetup.sh
case "$target" in
    gsi)
        # One system image; the phone keeps its own kernel, vendor and firmware.
        # Target files, then re-signed with your keys: an unsigned image carries AOSP's public
        # test keys, which would let anyone sign a fake update to a system app.
        lunch gsi_arm64-cur-user
        m target-files-package otatools-package
        "$here/scripts/sign-gsi.sh" "$src"
        echo
        echo "Flashing it on a phone: see docs/DEVICES.md."
        ;;
    emulator)
        # userdebug so the emulator can be driven over adb for tests and screenshots.
        lunch sdk_phone64_x86_64-cur-userdebug
        m
        echo
        echo "Built. Start it with: emulator -no-snapshot"
        echo "This image is signed with AOSP's public test keys: for testing only, never for a phone."
        ;;
    *)
        # Vendor files from Google's factory image, as GrapheneOS builds do.
        lunch "${target}-cur-user"
        m aapt2
        adevtool generate-all -d "$target"
        m vendorbootimage vendorkernelbootimage target-files-package
        echo
        echo "Built. Sign it with your own keys next: see docs/BUILDING.md."
        ;;
esac
