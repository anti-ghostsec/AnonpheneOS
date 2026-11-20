#!/usr/bin/env bash
# Sign an AnonpheneOS GSI with your own keys.
#
#   scripts/sign-gsi.sh <source dir>
#
# Run after `scripts/build.sh gsi <tag>` has built the target files. Needs your keys in
# <source dir>/keys/gsi, made once with GrapheneOS's own tool (keep them outside this repo and
# back them up offline):
#
#   cd <source dir> && script/generate-keys gsi
#
# An unsigned GSI carries AOSP's public test keys: anyone could sign an "update" to a system app
# (Secure Folder included) or give their own app system permissions. So this re-signs every APK and
# APEX with your keys, using the exact list GrapheneOS's release script signs, read from the synced
# tree so it follows each release. Then it checks that nothing is still signed with a key outside
# your key folder, and refuses to make images if anything is.
#
# Output: <source dir>/releases/gsi/{gsi-signed-target_files.zip,gsi-img.zip}. Flash system.img and
# vbmeta.img from gsi-img.zip as docs/DEVICES.md describes.
set -euo pipefail

src=$(cd "${1:?source dir}" && pwd)
cd "$src"

[[ -d keys/gsi ]] || { echo "sign-gsi: no keys in $src/keys/gsi. Make them once: cd $src && script/generate-keys gsi" >&2; exit 1; }
target_files=$(ls out/target/product/generic_arm64/obj/PACKAGING/target_files_intermediates/*-target_files*.zip 2>/dev/null | head -n1)
[[ -n $target_files ]] || { echo "sign-gsi: no GSI target files; run scripts/build.sh gsi <tag> first" >&2; exit 1; }

# The signing command, as GrapheneOS's release script runs it: from `sign_target_files_apks` to
# the line that names its input and output files (left out; ours are added below).
sign_args=$(awk '/^sign_target_files_apks /{f=1} f{print} f && !/\\$/{exit}' script/generate-release.sh | sed '$d')
[[ $sign_args == sign_target_files_apks* && $sign_args == *--extra_apks* ]] \
    || { echo "sign-gsi: can't find the signing command in script/generate-release.sh" >&2; exit 1; }
# Variables that command uses, set earlier in the same script.
AVB_ALGORITHM=$(sed -n 's/^AVB_ALGORITHM=\([A-Z0-9_]*\)$/\1/p' script/generate-release.sh | head -n1)
[[ -n $AVB_ALGORITHM ]] || { echo "sign-gsi: can't find AVB_ALGORITHM in script/generate-release.sh" >&2; exit 1; }

# Decrypted keys live only in RAM and are removed on exit.
KEY_DIR=$(mktemp -d /dev/shm/anonphene-sign.XXXXXXXXXX)
trap 'rm -rf "$KEY_DIR"' EXIT
cp keys/gsi/* "$KEY_DIR"
script/decrypt-keys "$KEY_DIR"

export PATH="$src/out/host/linux-x86/bin:$src/prebuilts/build-tools/linux-x86/bin:$src/prebuilts/build-tools/path/linux-x86:$PATH"
out=releases/gsi
mkdir -p "$out"
signed=$out/gsi-signed-target_files.zip
rm -f "$signed" "$out/gsi-img.zip"

eval "${sign_args%\\} \"\$target_files\" \"\$signed\""

# Every APK and APEX must now be signed with a key from KEY_DIR (or be a prebuilt that is
# signed by its maker: PRESIGNED / EXTERNAL). A test key left anywhere stops here.
bad=$(unzip -p "$signed" META/apkcerts.txt META/apexkeys.txt \
    | grep -oE '(certificate|private_key|public_key|container_certificate|container_private_key)="[^"]*"' \
    | grep -vE "=\"(PRESIGNED|EXTERNAL|)\"" \
    | grep -vF "=\"$KEY_DIR/" || true)
if [[ -n $bad ]]; then
    rm -f "$signed"
    echo "sign-gsi: these are still signed with keys that aren't yours; no images made:" >&2
    echo "$bad" | sort -u >&2
    exit 1
fi

img_from_target_files "$signed" "$out/gsi-img.zip"
echo
echo "Signed with your keys: $src/$out/gsi-img.zip (system.img, vbmeta.img)"
