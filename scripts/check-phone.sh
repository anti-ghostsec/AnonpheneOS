#!/usr/bin/env bash
# Says which AnonpheneOS edition a connected phone can take. Read-only: it only reads
# properties over adb and changes nothing on the phone.
#
#   scripts/check-phone.sh
#
# Needs USB debugging on, and "Allow" tapped on the phone.
set -euo pipefail
adb=${ADB:-adb}
command -v "$adb" >/dev/null || adb="$HOME/.local/opt/android-sdk/platform-tools/adb"

prop() { "$adb" shell getprop "$1" 2>/dev/null | tr -d '\r'; }

state=$("$adb" get-state 2>/dev/null || true)
if [[ "$state" != device ]]; then
    echo "No phone ready. Plug it in, turn on USB debugging, and tap Allow on the phone." >&2
    exit 1
fi

brand=$(prop ro.product.brand); model=$(prop ro.product.model); device=$(prop ro.product.device)
sdk=$(prop ro.build.version.sdk); abi=$(prop ro.product.cpu.abi)
oem_supported=$(prop ro.oem_unlock_supported); oem_allowed=$(prop sys.oem_unlock_allowed)
flash_locked=$(prop ro.boot.flash.locked); treble=$(prop ro.treble.enabled)
vndk=$(prop ro.vndk.version)

echo "Phone:            $brand $model ($device), Android API $sdk, $abi"
echo "OEM unlocking:    supported=${oem_supported:-?} allowed=${oem_allowed:-?}"
echo "Bootloader:       $([[ $flash_locked == 1 ]] && echo locked || echo "unlocked or unknown ($flash_locked)")"
echo "Treble (GSI):     ${treble:-?} (vendor ${vndk:-?})"
echo

pixels="oriole raven bluejay panther cheetah lynx tangorpro felix shiba husky akita tokay caiman komodo comet tegu frankel blazer mustang rango"
if [[ ${brand,,} == google && " $pixels " == *" $device "* ]]; then
    echo "=> Full AnonpheneOS: scripts/build.sh $device <tag>"
    echo "   (check GrapheneOS's devices page that $device is still supported)"
elif [[ "$oem_supported" == 0 ]]; then
    echo "=> This phone's bootloader can't be unlocked, so its OS can't be replaced."
elif (( ${sdk:-0} < 29 )); then
    echo "=> AnonpheneOS needs Android 10 or newer as the phone's original system."
elif [[ "$treble" == true && "$abi" == arm64* ]]; then
    echo "=> AnonpheneOS GSI: scripts/build.sh gsi <tag>, then docs/DEVICES.md."
    [[ "$oem_allowed" != 1 ]] && echo "   First turn on Developer options -> OEM unlocking (greyed out = carrier-locked)."
else
    echo "=> No AnonpheneOS edition fits this phone (no Treble or not arm64)."
fi
