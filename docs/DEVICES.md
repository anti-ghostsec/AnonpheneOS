# Which phones, and how to install

## Check your phone first

Plug it in with USB debugging on (Settings → About phone → tap Build number 7 times; then
Settings → System → Developer options → USB debugging), tap **Allow** on the phone, and run:

```bash
scripts/check-phone.sh
```

It only reads the phone's settings and says which edition fits. Nothing is changed.

## The three cases

| Phone | Edition | Strength |
|---|---|---|
| A Pixel that GrapheneOS supports | **Full AnonpheneOS** (`scripts/build.sh <codename> <tag>`) | Strongest: your own verified-boot keys, relocked bootloader, Titan M2 chip guards the Secure Folder |
| Any other phone that shipped with Android 10 or newer and has an unlockable bootloader | **AnonpheneOS GSI** (`scripts/build.sh gsi <tag>`) | Every AnonpheneOS feature works. The folder's lock still goes through the phone's own secure hardware (every Android 10 phone has one, usually weaker than a Pixel's Titan chip). The bootloader usually stays unlocked, and the kernel and firmware stay the phone maker's |
| Phones whose bootloader can't be unlocked | None | No one can replace their OS; use apps like Enclave on them |

The GSI is less tested than the Pixel build and may need fixes on a given phone. Cameras,
fingerprint readers and other phone-specific parts work as well as the phone maker's vendor
files allow.

## Unlocking the bootloader

**This erases everything on the phone.** Move what you need off it first.

1. Developer options → **OEM unlocking** on. If it's greyed out, the phone is carrier-locked.
2. `adb reboot bootloader`
3. Then:
   - Pixel: `fastboot flashing unlock`, and confirm with the volume and power keys.
   - Motorola: `fastboot oem get_unlock_data`, request a code on Motorola's unlock site with
     that output, then `fastboot oem unlock <code>`. Many carrier models aren't eligible.
   - Others: see the maker's own instructions; some don't allow it at all.

`adb` and `fastboot` are in the Android SDK's `platform-tools`, or in Debian:
`sudo apt install adb fastboot`.

## Installing on a Pixel

Sign the build with your own keys and flash the factory image (docs/BUILDING.md), then lock
the bootloader again with your key installed, so verified boot holds:

```bash
fastboot flash avb_custom_key avb_pkmd.bin
fastboot flashing lock
```

## Installing the GSI

`scripts/build.sh gsi <tag>` signs the GSI with your own keys (`scripts/sign-gsi.sh`; make the
keys once with `script/generate-keys gsi` in the source folder). Never flash an unsigned GSI: it
carries AOSP's public test keys, so anyone could sign a fake update to a system app. Unzip
`releases/gsi/gsi-img.zip` in the source folder and flash its `system.img`:

```bash
adb reboot bootloader
fastboot reboot fastboot
fastboot flash system system.img
fastboot reboot bootloader
fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
fastboot -w
fastboot reboot
```

`vbmeta.img` comes from the phone maker's firmware for your exact model. Phones with A/B slots
or other quirks may need more steps; look up your model's GSI instructions. Don't lock the
bootloader with a GSI installed unless your phone accepts your own verified-boot key (a few
makers allow `fastboot flash avb_custom_key`); most phones then refuse to boot.

## Emulator

`scripts/build.sh emulator <tag>` builds an image for the Android emulator on a PC. It's for
trying things out and testing. It has no security chip and is never as secure as a phone.
