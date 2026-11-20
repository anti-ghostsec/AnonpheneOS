# Design: VPNs and the Secure Folder

Status: steps **1 and 2 are written** (`scripts/apply.py`: `hook_vpn_folder`, `hook_vpn_netd`;
`hooks/frameworks/AnonVpnRoute.java`), matched against AOSP's current source, not yet run in a
built image. Step 3 is still a proposal. Three changes:

1. **The folder uses the main VPN.** With no VPN of its own, the Secure Folder's traffic goes
   through the main side's VPN instead of straight onto the network.
2. **A folder VPN runs inside the main VPN.** If you start a VPN (or Tor) inside the folder, its
   tunnel is carried by the main VPN: two hops, on by default.
3. **More than one VPN on a side**, with a choice per app.

## How Android does it today

Read from Android's current source (AOSP `main`):

- **One VPN per user.** `VpnManagerService` keeps one `Vpn` per user (`mVpns`, keyed by user ID)
  and makes one for every user that starts, profiles included. The Secure Folder is its own user,
  so it gets its own VPN slot.
- **A VPN only covers its own user.** `Vpn.createUserAndRestrictedProfilesRanges` gives a VPN the
  apps of its user and of that user's *restricted* profiles (a kind of profile for shared tablets),
  nothing else. So the main side's VPN doesn't cover the Secure Folder: **with no VPN inside the
  folder, its apps go straight onto Wi-Fi or mobile data**, under your real IP address. Most people
  wouldn't expect that.
- **A VPN app's own tunnel skips every VPN.** A VPN app sends its tunnel with `protect()`, and
  netd (`FwmarkServer`, `PROTECT_FROM_VPN`) then puts that socket on the default physical network.
  So even when both sides have a VPN, the folder's VPN tunnel goes out next to the main VPN, not
  through it.

## Is chaining more logical? Who sees what

Main side VPN "A", folder VPN or Tor "B", for an app in the folder:

| | Today: folder without VPN | Today: folder with VPN B | Proposed: B through A (default) | Proposed: folder without VPN |
|---|---|---|---|---|
| Carrier / Wi-Fi owner sees | Every site the app talks to | A connection to B | A connection to A only | A connection to A only |
| A's provider sees | Nothing | Nothing | An encrypted tunnel to B: not what's in it, not where it goes | The folder's traffic (as for main-side apps) |
| B's provider sees | — | **Your real IP** and where the traffic goes | A's IP, not yours, and where the traffic goes | — |
| Who knows both who you are and what you do | Carrier, sites | B's provider | **Nobody, unless A and B work together** | A's provider (as for main-side apps) |

So the worry that two providers each "see the data" doesn't hold for chaining. A sees only an
encrypted tunnel, B never learns your real address, and each knows less than one VPN alone would.
That's the reason Tor and VPN users chain on purpose.

The costs:
- **Slower and more battery**, since every packet goes through two tunnels.
- **A learns that you use B** (it sees the tunnel). Someone who controls both, or watches both
  ends' timing, could link them. Don't pick two services run by the same company.
- **If A drops, the folder is offline too.** That's the safe way round: the folder never falls back
  to your bare connection.

**Recommendation:** yes, chain by default. It's safer for people who don't know how profiles and
VPNs interact, and it's what most people who want a VPN plus Tor are after. Keep one switch for
those who want the two sides fully apart: *Settings → AnonpheneOS privacy → Secure Folder →
"Folder traffic goes through the main VPN"*, on by default. With it off, the folder behaves as on
stock Android, where A never sees that the folder exists.

## 1. The folder uses the main VPN

**Where:** `Vpn.createUserAndRestrictedProfilesRanges` and the two places that keep it current
when profiles come and go (`onUserAdded`, `onUserRemoved`).

**Change:** a user's VPN also covers that user's Secure Folder (its private profile) while the
switch is on and the folder has no VPN of its own running. When a VPN starts inside the folder,
`VpnManagerService` asks the main side's `Vpn` to recompute its ranges and drop the folder's apps;
when the folder's VPN stops, they're added back. The folder's apps are never covered by two VPNs
at once.

**"Block connections without VPN"** (always-on lockdown) on the main side then covers the folder
too, so if the main VPN drops, folder apps are cut off rather than going out bare.

**Size:** small. Java only, in two files, as a hook in `scripts/apply.py` like the others.

## 2. A folder VPN runs inside the main VPN

**Where:** the same Java range code, plus netd's socket marking (`FwmarkServer.cpp`, C++).

**Change:**
- The main side's VPN covers exactly one folder app while the folder has a VPN running: the folder's
  VPN app itself. The folder's own VPN covers every other folder app, as normal.
- In netd, a socket that the folder's VPN app protects with `protect()` goes to the network
  that covers that app (the main VPN), instead of the default physical network. Only for a VPN app
  whose own user is a Secure Folder whose parent has a VPN running, so no VPN anywhere else changes.
- netd already records which app may protect sockets for which VPN (`allowProtect`,
  `canProtect`); the hook adds one more lookup next to it, not a new mechanism.
- The same goes for a network the folder's VPN app picks itself (`Network.bindSocket`,
  `bindProcessToNetwork`): VPN apps may bypass VPNs on every network, so an explicit choice of
  Wi-Fi or mobile data is sent through the main VPN too, instead of out bare.

**Fails closed:** if the main side has a VPN set up but it isn't up (it dropped, crashed or is
reconnecting, always-on or not), the folder VPN's tunnel has nowhere to go and the folder stays
offline. It never falls back to the bare network while the switch is on. Only when the main side
has no VPN at all (none set up, or turned off in Settings, which clears it) does the folder's VPN
connect directly, as on stock Android. A VPN app switched off from inside the app itself stays
set up, so the folder stays offline until it's back on or the switch is turned off.

**Size:** medium. A C++ change in netd plus the Java above.

**As written:** netd has no idea which app runs which VPN, so system_server publishes it in one
system property, `anon.vpn.carried` (`uid:netId` pairs; SELinux lets only system_server set it
and netd read it). netd reads it only when a VPN app's protected socket connects. The pairing is
published as soon as the folder's VPN is set up, before it connects, so its first handshake is
already carried. No VPN lock is ever taken inside another: `AnonVpnRoute` snapshots each VPN's app
under that VPN's own lock, then recomputes everyone's covered apps from the snapshot.

## 3. More than one VPN on a side

**Today:** Android's whole VPN model assumes one VPN per user: one `Vpn` object, one "Connected"
VPN in Settings, one always-on choice. Several VPN *networks* at the same time already exist (one
per user), so the network stack can carry them. What's missing is several per user.

**Change:**
- `VpnManagerService` keys VPNs by (user, slot) instead of user, with a small number of slots (say 4).
- Each VPN app you set up gets a slot; *Settings → AnonpheneOS privacy → VPNs* shows which apps use
  which VPN, and apps not assigned use the side's main VPN (slot 0).
- Each slot's VPN covers only the apps assigned to it, so ranges never overlap, the same rule as in 1.
- The consent dialog, always-on and lockdown work per slot.
- Chaining (2) generalises: any slot can be set to run inside another.

**Until then:** one VPN app that routes apps to different servers itself (sing-box, v2rayNG),
or Orbot's per-app Tor, does much of this from a single slot.

**Size:** large. It touches `VpnManagerService`, `Vpn`, `ConnectivityService`'s VPN bookkeeping,
Settings, and the VPN consent flow. Build it after 1 and 2 have run on real phones.

## Testing

None of this can be seen on Google's stock emulator image, since it changes Android itself. It
needs an AnonpheneOS image (emulator or phone). Checks for each step:

- **Step 1:** with a VPN on the main side only, a folder app's public IP is the VPN's. Stopping the
  main VPN with lockdown on leaves the folder offline.
- **Step 2:** with VPN A on the main side and B in the folder, a folder app's IP is B's. B's server
  sees connections from A's IP. Capturing traffic on the phone's real interface shows only A's
  tunnel. With A stopped, the folder is offline.
- **Step 3:** two apps on one side assigned to different VPNs show different IPs at the same time.
