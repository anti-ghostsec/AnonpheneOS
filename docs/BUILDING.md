# Building AnonpheneOS

AnonpheneOS is GrapheneOS's source with this repo's parts copied in and hooked up by
`scripts/apply.py`. You build it the way GrapheneOS is built, for one Pixel at a time.

## What you need

- Linux (Debian or Ubuntu), x86-64.
- About **32 GB of RAM** (64 GB is comfortable) and **350 GB of free disk**. Less RAM fails
  part-way through; `scripts/build.sh` checks before starting.
- `repo`, `git`, `python3`, `yarn` and `adevtool`'s requirements, as listed in GrapheneOS's
  build guide.
- GrapheneOS's signing key in your GPG keyring, so the tag can be checked.

## 1. Build

```bash
scripts/build.sh <device> <GrapheneOS release tag> [source dir]
```

- `<device>` is the codename, e.g. `tokay` (Pixel 9), `komodo` (Pixel 9 Pro XL).
- `<GrapheneOS release tag>` is the newest tag on GrapheneOS's releases page for your device.
- The source goes to `~/anonphene-src` unless you name a folder.

It syncs GrapheneOS at that tag, checks the tag's signature, runs `scripts/apply.py`, fetches
the vendor files from Google's factory image with `adevtool`, and builds the target files.

`scripts/apply.py` can also be run on its own on a synced tree. It changes nothing if any hook
can't find the code it attaches to, and running it twice changes nothing the second time.

## 2. Make your own signing keys (once)

Release images are signed with your own keys, never with test keys. Follow GrapheneOS's build
guide, "Generating release signing keys", and keep the keys **outside this repo** (the
`.gitignore` refuses `keys/`, `*.pk8` and `*.x509.pem` anyway). Keep an offline backup: a
phone with a locked bootloader only takes updates signed with the same keys.

## 3. Sign and flash

Sign the target files and make the factory image with GrapheneOS's
`script/finalize.sh` and `script/generate-release.sh`, as in its build guide.

Flash with the bootloader unlocked, then add your key's hash as the custom AVB key and lock the
bootloader again, so verified boot holds.

## 4. Check it

On the phone, after setup:

- **Settings → AnonpheneOS privacy → Logs** says *None kept (log level S)*.
- With adb (developer options, test phones only): `adb logcat -d` prints nothing past boot, and
  `adb shell mount | grep tmpfs` lists `/data/tombstones`, `/data/anr`, `/data/system/dropbox`.
- Share a photo from Files: only the apps on your share list appear, and no people.
- Make the Secure Folder, lock the phone, unlock it: the folder is locked again and the app
  drawer shows no trace of it.

## Before committing

```bash
python3 -m unittest discover -s tests
scripts/check-private.py
git diff --cached
```
