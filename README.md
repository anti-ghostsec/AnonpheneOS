# AnonpheneOS

> 🧪 **Early, early testing.** Nothing here is ready to use. No phone image has been built yet;
> the two system apps run on the Android emulator, and everything else is source that hasn't
> been built. Don't put anything you care about on it.

A private, light Android built on top of [GrapheneOS](https://grapheneos.org): in full on Pixel
phones, and as a generic system image (GSI) for most other phones whose bootloader can be
unlocked (see [Phones](#phones)). Its own apps are dark, plain and quiet, in the style of Enclave
and Whisker. It keeps everything GrapheneOS does (hardened memory allocator, sandboxed Google Play
only if you want it, network and sensor permissions, storage scopes, duress PIN, auto-reboot) and
adds:

- **No logs anywhere.** Nothing an app or the system logs is kept, in memory or on disk. Crash
  reports, ANR traces, dropbox entries and trace files live in RAM and are gone at power off.
- **A Secure Folder that stays hidden and can't be guessed.** A separate profile with its own
  encryption key, locked with a 12 to 256 character passphrase plus any number of keyfiles,
  stretched with Argon2id and bound to the phone's security chip. No 4-digit PINs. It isn't a
  tab or a section in the app drawer. You open it the ways Motorola's Secure Folder allows: a
  dialer code you choose, an icon, a disguised icon, or a Quick Settings tile, each one on only
  if you switch it on.
- **A share sheet that shows only the apps you chose.** Pick the apps offered when sharing from
  any app, and give single apps their own list. People and recent chats aren't shown.
- **Your phone number only goes where you say.** Only the phone-number permission (or being
  the default SMS app) gives an app your number; older apps' general phone permission and SMS
  access give a blank one. You can give any app a blank number instead of the real one, and see
  every app that can get it, and why.
- **Mobile data is a permission, off by default.** Installed apps use Wi-Fi until you allow them
  mobile data, so your carrier never carries their traffic and can't attach or confirm your
  number for them ("silent number verification").
- **The Secure Folder goes through your VPN.** With no VPN of its own, the folder uses the main
  side's VPN, and the main side's "Block connections without VPN" covers it. A VPN or Tor you run
  inside the folder is carried inside the main VPN, so your carrier sees only the main VPN, and the
  folder's VPN never learns your real address. One switch turns it off
  ([design](docs/design/VPN.md)).
- **Secure deletion.** Deleting the Secure Folder erases its key from the phone's secure hardware.
  Files can be shredded (overwritten, deleted, then the free space trimmed), and the photo trash
  is emptied at every boot instead of after 30 days.
- **Lighter.** Screensavers, live wallpapers, printing and system tracing are left out, and no
  system app of AnonpheneOS's own has internet access.

> **Heads up:** AnonpheneOS is an independent project and isn't affiliated with or endorsed by
> GrapheneOS. It hasn't been audited by an independent security firm, and it hasn't been run
> on a phone yet (see [Known limits](#known-limits)). If being found out could put you in
> danger, use GrapheneOS itself until AnonpheneOS has releases with a track record.

---

## What's different from GrapheneOS

| | GrapheneOS | AnonpheneOS |
|---|---|---|
| System and app logs | Kept in RAM (logd), readable over adb | Not kept: every log call is a no-op |
| Crash reports, ANR traces, dropbox | Written to `/data` | RAM only, emptied at every boot |
| Tracing (perfetto) | Available | Off |
| Private profile | Private Space, shown at the bottom of the app drawer | Secure Folder: hidden, no tab, own app grid |
| Private profile's lock | PIN, pattern or password | Passphrase (12 to 256 characters) plus optional keyfiles, Argon2id |
| Ways into the private profile | App drawer, Settings | Dialer code, icon, disguised icon, Quick Settings tile (your choice) |
| Share sheet | Every app that can receive, plus people, with Personal / Private tabs | Only the apps on your share list (per app if you like), no people, no tabs: only the side you're on, as on Motorola |
| Sharing between profiles | Android's own way | Also "Into Secure Folder" / "Outside Secure Folder" share targets |
| Secure Folder and VPN | The main VPN doesn't cover the private profile; a VPN inside it goes out on its own | The folder uses the main VPN; a VPN inside it runs inside the main VPN (switchable) |
| Phone number | Apps with the phone, SMS or phone-number permission | Only the phone-number permission or the default SMS app; any app can be given a blank number |
| Mobile data | Every app with internet access | Installed apps only once you allow it (built-in apps always) |
| Photo trash | Emptied after 30 days | Emptied at every boot, or now from Settings |
| Shredding files | No | Settings → AnonpheneOS privacy → Shred files |
| Lock everything at once | Restart from the power menu | Settings → AnonpheneOS privacy → Restart to lock everything |
| Phones | Supported Pixels | Supported Pixels in full; most unlockable phones as a GSI |

## The Secure Folder

Open it once from **Settings → AnonpheneOS privacy → Secure Folder**. It asks for a passphrase
(12 to 256 characters) and, if you like, keyfiles: any files that never change, such as a photo
or a file on a USB stick. Every keyfile is needed every time, in any order. Then it asks for a
dialer code.

- **Its own lock screen.** Passphrase plus keyfiles go through Argon2id (256 MB, 4 passes) into
  a 256-bit credential that Android binds to the phone's security chip. Android's own PIN prompt
  is never used for the folder. Optionally, it deletes itself after 3 to 20 wrong tries in a row.
  How this compares with VeraCrypt, and what it means against quantum computers and forensic
  tools: [docs/SECURITY.md](docs/SECURITY.md).
- **Ways in** (⋮ → Ways in): dial `*#*#<your code>#*#*`; a *Secure Folder* icon; a disguised
  *Tools* icon; a *Tools* Quick Settings tile. Only the dialer code is on at first. Your code is
  stored as a salted hash, and any other code does nothing, so the dialer doesn't show whether a
  folder exists.
- **Its own grid of apps.** The main launcher never shows them: no work-style tab, no Private
  Space section. Add apps from ⋮ → Add apps; each gets its own empty data. Long-press to remove.
- **Locks with the phone.** When the phone locks, the folder stops and its key leaves memory.
  Its notifications stop with it. ⋮ → Lock now locks it at once.
- **Moving files.** Inside the folder, share to *Outside Secure Folder* to save into the main
  side's `Download/From Secure Folder`. Outside, share to *Into Secure Folder* (unlock the folder
  first) to save into the folder's `Download/Secure Folder`.
- **Deleting it** (⋮ → Delete Secure Folder) removes its apps and files and erases its key.

More detail: [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md#secure-folder).

## The share list

**Settings → AnonpheneOS privacy**:

- **Share list for every app**: tick the apps the share sheet may offer. Off means every app is
  offered, as on stock Android.
- **Share list for one app**: pick an app (say, the camera) and give it its own list; it then
  ignores the every-app list. *Use the every-app list* drops it again.
- **Hide people and chats**: on by default. The share sheet never suggests contacts or recent
  conversations; you pick the person inside the app you share to.

The sending app is taken from who really opened the share sheet, not from what it claims.

## Works with the other anti-ghostsec apps

- **[Enclave](https://github.com/anti-ghostsec/Enclave)** runs inside the Secure Folder. Its own
  folder browser works there, and its share list shows *Outside Secure Folder* to get files out.
- **[Whisker](https://github.com/anti-ghostsec/whisker)** and
  **[Torus](https://github.com/anti-ghostsec/Torus)** work on either side. Give them the Network
  permission (GrapheneOS's per-app toggle) and, for Torus hosting from the phone, let it run in
  the background.
- Logging in these apps is silenced by the system as well, whatever their own settings say.

## Phones

- **Pixels that GrapheneOS supports:** the full build, with your own verified-boot keys and a
  relocked bootloader. Strongest.
- **Any other phone that shipped with Android 10 or newer and has an unlockable bootloader:** the
  GSI. Every AnonpheneOS feature works, and the folder's lock still goes through the phone's
  own secure hardware. The bootloader usually stays unlocked, and the kernel and firmware stay
  the phone maker's.
- **Phones whose bootloader can't be unlocked:** nothing can replace their OS.

`scripts/check-phone.sh` reads a connected phone's settings over adb (changing nothing) and says
which fits. Installing: [docs/DEVICES.md](docs/DEVICES.md).

## Building

See [docs/BUILDING.md](docs/BUILDING.md). In short, on a Linux PC with about 32 GB of RAM and
350 GB free:

```bash
scripts/build.sh <Pixel codename | gsi | emulator> <GrapheneOS release tag>
```

The phone never needs that much: it only receives the finished image, about 1 to 2 GB. A rented
cloud machine for a few hours is the usual way to build without such a PC.

There are no prebuilt images yet. Releases will be signed with AnonpheneOS's own keys and built
from `v*` tags.

## Tests

The parts that can be checked without an Android tree are checked on any PC with Python 3:

```bash
python3 -m unittest discover -s tests
```

With a JDK and the Android SDK installed, they also run `scripts/java-check.py`: it compiles
all the Java against the SDK and runs the crypto checks (Argon2id against RFC 9106's test vector
and the reference tool, BLAKE2b against RFC 7693 and Python's hashlib).

The apps themselves can be built and run on Google's Android 15 emulator, without building the
OS: [docs/EMULATOR.md](docs/EMULATOR.md) lists what's been tested there and what needs a real
AnonpheneOS build.

`scripts/check-hooks-upstream.py` fetches only the files the hooks change from AOSP's own
source server and runs every hook on them, so a moved anchor shows up before a 300 GB sync.

They also cover the share-list format (the same cases the Java code must pass), the source hooks on
a small fake tree (they go in once, and a moved anchor changes nothing), the manifests and
resources, the no-logs settings, and a scan for private details.

## Known limits

- **Ways in need the built-in apps.** Secure Folder's app is invisible to apps that aren't part
  of the system, so its icon and disguised icon show only in the built-in launcher, and the
  dialer code works only from the built-in Phone app.
- **The OS hasn't been built yet.** The two apps compile against Android's system API and run on
  the Android 15 emulator (docs/EMULATOR.md), but the hooks into GrapheneOS's code have only been
  tried on small copies of that code, and nothing has run on a phone.
- **The share sheet's one-side-only hook (no Personal / Private tabs) is written against the
  share sheet's real source but not yet run**: it's Kotlin, which this PC can't compile, and it
  only takes effect in a built image.
- **Forget the passphrase or lose a keyfile, and the folder is gone.** There's no recovery.
- **No hidden volume or duress passphrase yet** for the folder (GrapheneOS's duress PIN for the
  whole phone still works on Pixels).
- **Hooks into GrapheneOS's code are found by name.** `scripts/apply.py` looks for the methods it
  attaches to (`ResolverListController.filterIneligibleActivities`,
  `ShortcutService.getShareTargets`, `Settings.Secure`). If a GrapheneOS release renames one, it
  stops and says which, without changing anything.
- **Mobile data has three gaps.** Built-in apps are always allowed, so calls, texts and the
  carrier's own services work. Android decides "mobile data" by whether a network is metered:
  a mobile plan your carrier marks unmetered isn't covered, and a metered Wi-Fi (a hotspot) is
  closed to those apps too. A VPN running over mobile data counts as mobile data as well.
  And an app can have a built-in app fetch for it: a link it opens in the browser (Custom Tabs)
  or a download it hands to Android's download manager uses mobile data. Some carriers add your
  number to web requests made over mobile data ("header enrichment"), so a site opened that way
  can learn it. Apps in the Secure Folder never get mobile data (the folder has no Settings to
  allow them).
- **The VPN routing for the Secure Folder hasn't run yet.** It changes Android's VPN service and
  netd, so it only works in a built image. One part (sending a folder VPN through a main VPN
  that's set up but down to the "unreachable" network, and blocking the folder VPN app's other
  traffic meanwhile) depends on how netd treats that network and needs checking on a device. One VPN per side remains Android's limit until step 3 of the design is built.
- **The default phone and SMS apps and the SIM's carrier app can always read the number**
  unless you give them a blank one, which may break MMS or the carrier's features.
- **A blank number covers Android's phone-number calls only.** Your number can also be found in
  the call log (call-log permission), in MMS messages (SMS permission), or by an app that can
  place calls dialing the carrier's "my number" code. Don't give those permissions to apps you
  wouldn't give the number to. Apps in the Secure Folder follow the main side's choices.
- **The keyboard, clipboard and accessibility services are shared with the Secure Folder.**
  Android runs the main side's keyboard and accessibility services for the folder too, and the
  clipboard is shared between sides. Use a keyboard without internet access, turn
  off accessibility services you don't fully trust, and don't copy what must stay in the folder.
- **The GSI is weaker than the Pixel build.** Its bootloader stays unlocked, so someone with the
  phone could flash a modified system unnoticed, and the folder is only as strong as the
  phone's own secure hardware plus your passphrase and keyfiles. It's also less tested.
- **No logs also means no bug reports.** When something crashes, there's nothing to send. To
  debug, build a `userdebug` image and set `persist.log.tag` yourself on a test phone.
- **One hidden profile.** Android allows one private profile per phone. A Private Space made in
  Settings has to be deleted before the Secure Folder can be made.
- **Shredding on flash can't be complete.** The flash controller keeps old copies of blocks that
  no app can reach. What makes deleted files unreadable is that each file has its own encryption
  key; shredding adds an overwrite and a trim on top.
- **Sharing out of the folder hands over a plain copy.** The receiving side's Downloads holds it
  unencrypted by the folder's key (it's still covered by the phone's own encryption).
- **The dialer code needs a dialer that sends secret codes.** The included one does; some
  third-party dialers don't.
- **No update server yet.** Updates mean building and flashing a new image.

## License

MIT or Apache-2.0, at your option, for AnonpheneOS's own code. GrapheneOS and AOSP keep their
own licenses.
