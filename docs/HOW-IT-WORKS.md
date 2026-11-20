# How AnonpheneOS works

Where each feature lives, and what it relies on.

| Folder | What's in it |
|---|---|
| `vendor/anonphene/` | Product makefile, system properties, boot script, SELinux rules, framework overlay, privileged-permission list, the share-list library |
| `packages/AnonSecureFolder/` | The Secure Folder app |
| `packages/AnonPrivacy/` | *AnonpheneOS privacy* in Settings: share lists, cleaning, shredding |
| `hooks/` | Code copied into GrapheneOS's own modules |
| `scripts/apply.py` | Copies the above into a GrapheneOS tree and hooks it up |
| `tests/` | Checks that run on any PC with Python 3 |

## No logs

- `vendor/anonphene/config/anonphene.prop` sets `persist.log.tag=S` and `log.tag=S`. Android's
  log library checks this before anything reaches `logd`, so every `Log.*` call, native or Java,
  from the system or any app, is dropped. logd's buffers are kept at their smallest, persisted
  logs (`logpersistd`) are off, and kernel and audit messages aren't copied into the log.
- `vendor/anonphene/init/anonphene.rc` empties `/data/tombstones`, `/data/anr`,
  `/data/system/dropbox`, `/data/misc/logd` and `/data/misc/perfetto-traces` at every boot,
  then covers each with a small RAM filesystem. Anything written there later is gone at power
  off. Leftover bug reports, boot stats, process stats and graphics stats are deleted too.
- Usage history goes to RAM the same way: which apps were used and when (`UsageStatsService`
  is pointed at a RAM folder by `scripts/apply.py`, and history an older build left on disk is
  deleted once each user unlocks), which apps used the camera, microphone or location and when
  (app-op history), per-app battery and data use, statsd's metrics, boot stats, process and
  graphics stats, and memory dumps. Settings' battery and data-usage screens start again at
  every boot, and a data limit counts only since the last boot.
- If anything sets `persist.log.tag` to another level, the boot script puts it back to `S`.
- Tracing is off (`persist.traced.enable=0`), tombstoned keeps no tombstones or ANR traces,
  and the Privacy app keeps Android's dropbox to one entry of at most 64 KB for one minute.
- The overlay turns off notification history, content capture and the app-prediction service.
- AnonpheneOS's own apps have no logging calls at all (`tests/test_tree.py` checks this).

## Secure Folder

The Secure Folder is Android's private profile (what Android calls Private Space), handled by
`packages/AnonSecureFolder`:

- **Hidden.** `hide_privatespace_entry_point=1`, and `scripts/apply.py` makes Launcher3's
  `PrivateProfileManager.isPrivateSpaceHidden()` always true, so the launcher never has a
  Private Space section, locked or unlocked (stock Android 15 shows one while it's unlocked).
  The folder's apps appear only in the folder's own grid (`FolderActivity`), which is excluded
  from recents and blocks screenshots.
- **Invisible to other apps.** Android already hides the private profile from apps' user and
  profile lists. `scripts/apply.py` also makes the Secure Folder app itself invisible to every
  app that isn't part of the system (`AppsFilterBase.shouldFilterApplication`), so no app can
  tell from its share target, disguised icon or tile that a folder exists. The built-in launcher,
  Phone app, share sheet and Settings still see it; a third-party launcher can't show its icon
  or disguised icon, and a third-party dialer can't open it with your code.
- **Own lock, own key.** `KeyForm` takes a passphrase (12 to 256 characters) and any number of
  keyfiles. `FolderKey` (`vendor/anonphene/lib/folderkey`) turns them into a 256-bit credential
  with Argon2id (256 MB, 4 passes, 4 lanes, random 32-byte salt); keyfiles go in as Argon2id's
  secret, made from their sorted BLAKE2b-512 digests. Secure Folder sets that as the profile's
  lock with `LockPatternUtils`, and unlocks by starting the profile without Android's prompt
  (`QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL`) and checking the credential, which opens its
  storage. Stock Android 15 always demands its own prompt for a private profile, so
  `scripts/apply.py` adds a gate to `UserManagerService.requestQuietModeEnabled`
  (`hooks/frameworks/AnonSecureFolderGate.java`): only Secure Folder, signed with the platform
  key and asking for a private profile of its own user, may start it without the prompt. Its
  storage stays locked until the credential is checked. Only the salt and the Argon2id settings are stored. Wrong tries are counted before
  each check and can delete the folder.
- **Locks with the phone.** `private_space_auto_lock=0` stops the profile whenever the phone
  locks, and stopping it evicts its key from memory.
- **Ways in.** `SecretCodeReceiver` takes any `*#*#…#*#*` code and compares it with your salted
  hash in constant time. The icon, the disguised icon and the Quick Settings tile are
  components that stay disabled until you switch them on.
- **Moving files.** `ShareOutActivity` is enabled only inside the folder and `ShareInActivity`
  only outside. Each hands the shared items to `ReceiveActivity` in the other user with
  one-time read access, and it saves them into that user's Downloads. Nothing is staged in a
  cache on either side.
- **Deleting.** `removeUser` removes the profile; vold securely discards its key blobs, so a
  copy of the flash taken later can't be decrypted.

Secure Folder is installed in the profile too (`sysconfig/anonphene-user-types.xml`), for its
"Outside Secure Folder" target. Apps added to the folder are installed for the profile from the copy already on the phone
(`installExistingPackage`). Each gets its own empty data.

## Share list

- The list is one per-user secure setting, `anon_share_targets`, in a small text format
  described in `ShareList.java`. `tests/share_list_vectors.json` holds the cases it must pass.
- `scripts/apply.py` copies `ShareList.java` and `hooks/IntentResolver/AnonShareFilter.java`
  into the share sheet (IntentResolver), and adds the filter to the permission check inside
  `ResolverListController.filterIneligibleActivities`, before targets are ranked.
- One side only, as on Motorola: `hooks/IntentResolver/AnonOwnSide.kt` makes the share sheet's
  profile list hold only the side it was opened on. From the main side the folder isn't there;
  inside the folder, the folder is the only profile. So there are never Personal / Private tabs,
  and crossing over goes only through "Into Secure Folder" / "Outside Secure Folder".
- The sender is found from the uid that opened the share sheet. A uid shared by several system
  packages has no single sender, so the every-app list applies to it.
- People and conversations come from `ShortcutService.getShareTargets`. With
  `anon_share_hide_people=1` (the default) it returns nothing, so no app, the share sheet
  included, gets them. The overlay also turns off the app-prediction service that would rank
  them.
- Both keys are added to `Settings.Secure` as readable, so the share sheet is allowed to read
  them.

## Phone number

- `LegacyPermissionManagerService.checkPhoneNumberAccess` is where Android decides who may read
  the number. `scripts/apply.py` turns its two side doors (READ_PHONE_STATE for apps built for
  Android 10 or older, and READ_SMS) into "empty data" (`MODE_IGNORED`), so those apps get no
  number but don't crash. The phone-number permission and the default SMS app still pass.
- `TelephonyPermissions.checkReadPhoneNumber`, which every number-returning call goes through
  (`getLine1Number`, `SubscriptionManager.getPhoneNumber`, a subscription's number), returns
  "empty" for apps listed in `anon_phone_number_blank`, before any other check.

## Secure Folder and the VPN

Design and reasoning: [design/VPN.md](design/VPN.md).

- `Vpn.createUserAndRestrictedProfilesRanges` decides which apps a VPN covers. The hook adds the
  user's Secure Folder: all of it, or only the VPN app running inside it while one runs. The
  folder's own VPN then covers every folder app except its own VPN app. "Block connections without
  VPN" uses the same ranges, so it covers the folder too.
- `AnonVpnRoute` (registered by `VpnManagerService` for every user's VPN) recomputes all of this
  whenever any VPN starts, stops or changes, or the switch changes.
- netd's `FwmarkServer` would normally put a VPN app's protected tunnel sockets on the bare
  network. For the folder's VPN app it puts them on the main VPN instead, read from the
  `anon.vpn.carried` property, or on the unreachable network when the main VPN is always-on but
  down.
- Switch: `anon_folder_via_main_vpn`, Settings → AnonpheneOS privacy → Secure Folder uses the
  main VPN. On by default.

## Mobile data

- `NetworkPolicyManagerService.isRestrictedByAdminUL` is the check behind the mobile-data
  restriction a device administrator can set: it closes metered networks to an app in the
  foreground and the background. The hook adds `AnonMobileData.blocks(uid)`: an installed app
  not in `anon_mobile_data_allowed` is blocked; built-in apps never are.
- `AnonMobileData` watches that setting and package changes, and recomputes every app's rules
  when either changes.

## Secure deletion

- **Secure Folder**: deleting it erases the profile's keys (above).
- **Shred files** (`Cleaner.shred`): overwrites each chosen file with random bytes, syncs it,
  deletes it, then asks vold to trim, so the flash controller can erase the freed blocks.
- **Clear traces now**: empties the clipboard and MediaStore's trash, then trims.
- **At boot**: the trash is emptied, and the crash and log folders are cleared (above).

On flash storage none of this can promise that no stale copy of a block survives inside the
controller. What does hold is that every file has its own key derived from the user's key, and
deleting the file drops the data needed to rebuild it.

## Lightweight

`anonphene.mk` leaves out screensavers, live wallpapers, printing, the browser bookmark
provider and system tracing. The two AnonpheneOS apps use only framework widgets (no AndroidX,
no Compose), have no internet access, and run nothing in the background except a short job at
boot.
