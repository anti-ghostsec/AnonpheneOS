# Trying the apps on the Android emulator

The two AnonpheneOS apps can run on Google's plain Android 15 emulator image without building
the OS. Everything they do runs for real, except the parts that need AnonpheneOS's framework
hooks (below).

## Set up (once)

With a JDK and the Android SDK in `~/.local/opt` (or `JAVA_HOME` / `ANDROID_HOME`):

```bash
sdkmanager "emulator" "system-images;android-35;default;x86_64"
avdmanager create avd -n anonphene -k "system-images;android-35;default;x86_64" -d pixel_7
```

Two files from AOSP's own source server, kept outside the repo in `~/.local/opt/aosp-bits/`:
`system-android-35.jar` (AOSP `prebuilts/sdk/35/system/android.jar`, the system-API stubs) and
the emulator's public test platform key, `platform.pk8` and `platform.x509.pem` (AOSP
`build/target/product/security/`). That key is public. Never sign anything for a real phone with it.

## Build, start, install

```bash
scripts/build-apks.py ~/.local/opt/aosp-bits/platform.pk8 ~/.local/opt/aosp-bits/platform.x509.pem
emulator -avd anonphene -no-snapshot -memory 2048
adb shell cmd connectivity airplane-mode enable
adb install -r out/apks/AnonSecureFolder.apk
adb install -r out/apks/AnonPrivacy.apk
adb shell am start -n app.anonphene.securefolder/.FolderActivity
adb shell am start -n app.anonphene.privacy/.PrivacyActivity
```

Signed with the image's platform key, both apps get their system permissions as normal installs.

## What works there, and what doesn't

Tested on the emulator:

- Making the folder: a 4-digit PIN is refused, the passphrase goes through Argon2id, Android
  stores the result as the folder's real lock, and the folder is created locked.
- A wrong passphrase is refused and the folder stays stopped.
- The credential from the passphrase is accepted by Android as the folder's lock.
- The app grid, adding apps (each gets the private badge), Lock now (the profile stops).
- Dialer code: the right code opens the folder, a wrong one does nothing.
- Ways in: the disguised "Tools" icon appears in the app drawer when switched on.
- Into / Outside Secure Folder: files move both ways into the other side's Downloads.
- Privacy page, share lists (every app and per app), shredding a file.

Needs an AnonpheneOS build (the hooks in `scripts/apply.py`), so it can't be shown on stock Android:

- **Unlocking with Secure Folder's own screen.** Stock Android 15 always shows its own prompt
  for a private profile; AnonpheneOS's UserManagerService gate lets Secure Folder alone skip it.
  On stock Android Secure Folder says so instead.
- **No launcher section while the folder is open.** Stock Android 15 adds a "Private" section to
  the app drawer while it's unlocked; AnonpheneOS's Launcher3 hook removes it.
- **The filtered share sheet, hidden people and no Personal / Private tabs**, and **no logs**.
