# How strong is it?

## The Secure Folder's lock

The folder isn't locked with a 4-digit PIN. It's locked with:

1. **A passphrase of 12 to 256 characters.** It can't be digits only or a handful of
   repeated characters. A few unrelated words is the easiest strong choice.
2. **Any number of keyfiles (up to 64), if you want them.** Any file that never changes works:
   a photo, a document, a random file on a USB stick or SD card. Every one is needed every time,
   in any order. Secure Folder never writes down which files they were.
3. **The phone's security chip.** The passphrase and keyfiles go through Argon2id (256 MB of
   memory, 4 passes, a fresh 32-byte salt) to make a 256-bit credential. Android then binds that
   to the phone's secure hardware, which limits how fast and how often guesses can be tried.

To get in, someone needs your passphrase **and** every keyfile **and** the phone itself with
its chip intact. A copy of the phone's storage is useless on its own: guessing has to go
through the chip.

Android's own lock prompt is never used for the folder, so it can't be used to guess at it,
and Secure Folder never sets up a fingerprint or face for it. You can have the folder delete itself after
3, 5, 10 or 20 wrong tries in a row (⋮ → Delete after wrong tries). A wrong passphrase and a
missing keyfile give the same message, so nothing says which part was wrong. The count, the
limit and the folder's Argon2id salt are kept in the folder profile's own system settings, not in
Secure Folder's app data, so clearing the app's storage can't reset the count or turn the limit
off, and each try is counted before it's checked.

If you forget the passphrase or lose a keyfile, the folder can't be opened by anyone,
including you. There's no recovery, by design.

## Compared with VeraCrypt

| | VeraCrypt volume | AnonpheneOS Secure Folder (Pixel) | AnonpheneOS Secure Folder (GSI phone) |
|---|---|---|---|
| Passphrase | Long passphrases | 12 to 256 characters | 12 to 256 characters |
| Keyfiles | Yes, any number, any order | Yes, up to 64, any order | Same |
| Key stretching | Its own key derivation | Argon2id, 256 MB, 4 passes | Same |
| A copy of the disk | Can be attacked offline, as fast as the attacker's hardware goes | Useless without the phone's security chip | Depends on the phone's own secure hardware |
| Wrong-guess limits | None (offline) | Enforced by the security chip, plus optional self-delete | Enforced by the phone's own secure hardware (weaker than Titan), plus optional self-delete |
| Hidden volume (deniability) | Yes | Not yet | Not yet |
| Locks itself | When you dismount | Every time the phone locks | Same |

On a Pixel, the folder is stronger than a VeraCrypt volume with the same passphrase and
keyfiles, because guesses have to go through the security chip. Without that chip the folder
is about as strong as VeraCrypt: the passphrase and keyfiles carry it all. On every phone, the
passphrase is what matters most.

VeraCrypt has one thing the folder doesn't have yet: a hidden volume, which lets you deny a
second volume exists. A duress passphrase that silently deletes the folder is planned.

## Quantum computers

Everything that keeps your data secret is symmetric cryptography: AES-256 for the files
(Android's file-based encryption), Argon2id and BLAKE2b for the folder's key. The best known
quantum attack on these (Grover's algorithm) at most halves their strength, leaving AES-256
with about 128 bits, which no computer can search. No public-key cryptography protects stored
data, and that's the kind a quantum computer would break.

Public-key cryptography is used only to sign the system (verified boot and updates). A large
quantum computer could, in theory, forge a signature. That would let someone make a fake
update; it wouldn't let them read anything already on the phone.

## Someone with the phone and tools plugged in

Forensic tools that plug into a phone mostly need it to be in one of two states:

- **Before the first unlock after a restart:** every file on the phone is encrypted with keys
  that aren't in memory yet. Nothing can be read, the folder included. Getting in means
  guessing the phone's lock through the security chip.
- **Unlocked at least once since the restart:** the main side's keys are in memory, so tools
  aim for this state. The Secure Folder is still locked whenever the phone is (it stops, and its
  key leaves memory), so the folder stays out of reach.

To keep the main side out of reach too:

- **Settings → AnonpheneOS privacy → Restart to lock everything** before you hand the phone
  over, cross a border, or leave it anywhere.
- GrapheneOS's own settings (Pixels): turn **Auto reboot** down to a few hours, and set the
  **USB-C port** to charging only, so nothing plugged in can talk to the phone while it's locked.
- Use a long passphrase for the phone too, not only for the folder.
- Keep USB debugging off. Turn it on only when you're working on the phone yourself.

## Your phone number

On stock Android an app can get your number in more ways than the "phone number" prompt
suggests: older apps through the general "make and manage phone calls" permission, apps with
SMS access, the default phone and SMS apps, the SIM's carrier app, and, off the phone entirely,
your carrier, which can attach your number to an app's mobile data or confirm it to the app's
server without asking you.

AnonpheneOS:

- gives the number only to apps holding the phone-number permission and to the default SMS app.
  The general phone permission and SMS access give empty data instead, without breaking the app;
- lets you give any app a blank number, whatever it holds (Settings → AnonpheneOS privacy →
  Phone number). That page lists every app that can get it, and why;
- makes mobile data a permission, off for installed apps (Settings → AnonpheneOS privacy →
  Mobile data). Their traffic then goes over Wi-Fi or not at all, so the carrier never sees it.

## What it can't protect against

- Someone watching you type the passphrase (a camera, over your shoulder).
- Malware already running on the main side while it's unlocked.
- Being forced to unlock it. The planned duress passphrase is meant for that.
- A keyboard app that records what you type. Use the built-in keyboard; it has no internet
  access on GrapheneOS.
- On GSI phones: anyone who can flash a modified system, since the bootloader stays unlocked.
  Don't leave a GSI phone with someone you don't trust and then go on using it.
