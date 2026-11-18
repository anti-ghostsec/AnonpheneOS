#!/usr/bin/env python3
"""Wire AnonpheneOS into a synced GrapheneOS tree.

    scripts/apply.py <path to the source tree>

It copies vendor/anonphene and the two apps into the tree, then hooks them in.
Every change is found by looking for the code it attaches to, not by line numbers, and is
marked with ANONPHENE-HOOK so running this again changes nothing. If upstream code moved and an
anchor can't be found, it stops and says which one, before touching any file. Nothing is
downloaded and nothing outside the given tree is written.
"""

import re
import shutil
import sys
from pathlib import Path

MARK = "ANONPHENE-HOOK"
HERE = Path(__file__).resolve().parent.parent


class HookError(Exception):
    pass


def find_one(tree, pattern):
    hits = sorted(tree.glob(pattern))
    if len(hits) != 1:
        raise HookError(f"expected one file matching {pattern}, found {len(hits)}")
    return hits[0]


# ---- each hook returns {path: new text} or raises HookError; nothing is written until all pass ----


def hook_product(tree):
    """Every device product gets vendor/anonphene/anonphene.mk."""
    p = tree / "build/make/target/product/handheld_product.mk"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    s = p.read_text()
    if MARK in s:
        return {}
    s = s.rstrip("\n") + (
        f"\n\n# {MARK}: AnonpheneOS\n"
        "$(call inherit-product-if-exists, vendor/anonphene/anonphene.mk)\n"
    )
    return {p: s}


SETTINGS_KEYS = '''
        // {mark}: AnonpheneOS share list (see vendor/anonphene/lib/sharelist).
        /** @hide */
        @Readable
        public static final String ANON_SHARE_TARGETS = "anon_share_targets";
        /** @hide */
        @Readable
        public static final String ANON_SHARE_HIDE_PEOPLE = "anon_share_hide_people";
        /** @hide Apps given a blank phone number, package names separated by commas. */
        @Readable
        public static final String ANON_PHONE_NUMBER_BLANK = "anon_phone_number_blank";
        /** @hide Apps allowed to use mobile data, package names separated by commas. */
        @Readable
        public static final String ANON_MOBILE_DATA_ALLOWED = "anon_mobile_data_allowed";
        /** @hide 1 (default): the Secure Folder goes through, and its VPN inside, this user's VPN. */
        @Readable
        public static final String ANON_FOLDER_VIA_MAIN_VPN = "anon_folder_via_main_vpn";
'''


def hook_settings(tree):
    """The share-list keys become known, readable Secure settings."""
    p = tree / "frameworks/base/core/java/android/provider/Settings.java"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    s = p.read_text()
    if f"{MARK}: AnonpheneOS share list" in s:
        return {}
    m = re.search(r"public static final class Secure extends NameValueTable\s*\{\n", s)
    if not m:
        raise HookError("Settings.java: class Secure not found")
    return {p: s[: m.end()] + SETTINGS_KEYS.format(mark=MARK) + s[m.end():]}


HIDE_PEOPLE = '''
        // {mark}: no people or conversations in the share sheet when the user chose so.
        final long anonToken = android.os.Binder.clearCallingIdentity();
        try {{
            if (android.provider.Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    "anon_share_hide_people", 1, {user}) != 0) {{
                return new ParceledListSlice<>(java.util.Collections.emptyList());
            }}
        }} finally {{
            android.os.Binder.restoreCallingIdentity(anonToken);
        }}
'''


def hook_hide_people(tree):
    """ShortcutService.getShareTargets is where the share sheet gets its people and chats."""
    p = tree / "frameworks/base/services/core/java/com/android/server/pm/ShortcutService.java"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    s = p.read_text()
    if f"{MARK}: no people" in s:
        return {}
    m = re.search(
        r"public\s+ParceledListSlice(?:<[^>]*>)?\s+getShareTargets\s*\(([^)]*)\)\s*\{\n", s)
    if not m:
        raise HookError("ShortcutService.java: getShareTargets(...) not found")
    params = [x.strip() for x in m.group(1).split(",")]
    user = None
    for prm in params:
        words = re.sub(r"@\w+(\([^)]*\))?", "", prm).split()
        if len(words) == 2 and words[0] == "int":
            user = words[1]
    if user is None:
        raise HookError("ShortcutService.getShareTargets: no int user id parameter")
    if "mContext" not in s:
        raise HookError("ShortcutService.java: mContext not found")
    return {p: s[: m.end()] + HIDE_PEOPLE.format(mark=MARK, user=user) + s[m.end():]}


FILTER_CHECK = "isComponentFiltered(ai.getComponentName())"


def hook_share_list(tree):
    """IntentResolver drops targets the share list doesn't allow, with its own permission checks."""
    ir = tree / "packages/modules/IntentResolver"
    if not ir.exists():
        raise HookError("missing packages/modules/IntentResolver")
    out = {}
    src = find_one(ir, "java/src/**/ResolverListController.java")
    s = src.read_text()
    if "AnonShareFilter.blocks(" not in s:
        m = re.search(r"filterIneligibleActivities\s*\(", s)
        if not m:
            raise HookError("ResolverListController.java: filterIneligibleActivities(...) not found")
        at = s.find(FILTER_CHECK, m.end())
        if at < 0 or s.find(FILTER_CHECK, at + 1) >= 0:
            raise HookError(f"ResolverListController.java: {FILTER_CHECK} not found once")
        for field in ("mContext", "mLaunchedFromUid"):
            if not re.search(rf"\b{field}\b", s):
                raise HookError(f"ResolverListController.java: field {field} not found")
        end = at + len(FILTER_CHECK)
        s = (s[:end] + "\n                    // " + MARK + ": AnonpheneOS share list.\n"
             "                    || com.android.intentresolver.AnonShareFilter.blocks(\n"
             "                            mContext, mLaunchedFromUid, ai.getComponentName())" + s[end:])
        out[src] = s

    # The filter and the format it reads, as sources of IntentResolver itself.
    java = ir / "java/src"
    copies = {
        java / "com/android/intentresolver/AnonShareFilter.java":
            HERE / "hooks/IntentResolver/AnonShareFilter.java",
        java / "app/anonphene/sharelist/ShareList.java":
            HERE / "vendor/anonphene/lib/sharelist/src/app/anonphene/sharelist/ShareList.java",
    }
    for dst, origin in copies.items():
        text = origin.read_text()
        if not dst.exists() or dst.read_text() != text:
            out[dst] = text
    return out


FOLDER_GATE = '''
        // {mark}: AnonpheneOS Secure Folder starts its hidden profile without Android's prompt
        // and checks the folder's credential itself straight after (its own passphrase and
        // keyfiles screen). Only that app, signed with the platform key, and only for a private
        // profile of the caller's own user.
        if (!{enable} && ({flags} & UserManager.QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL) != 0
                && com.android.server.pm.AnonSecureFolderGate.allows(mContext, {pkg},
                        android.os.Binder.getCallingUid(), {user})) {{
            final long anonToken = android.os.Binder.clearCallingIdentity();
            try {{
                setQuietModeEnabled({user}, false, {target}, {pkg});
            }} finally {{
                android.os.Binder.restoreCallingIdentity(anonToken);
            }}
            return true;
        }}
'''


def hook_folder_gate(tree):
    """UserManagerService lets Secure Folder open its private profile with its own lock screen."""
    p = tree / "frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    out = {}
    s = p.read_text()
    if f"{MARK}: AnonpheneOS Secure Folder" not in s:
        m = re.search(r"public\s+boolean\s+requestQuietModeEnabled\s*\(([^)]*)\)\s*\{\n", s)
        if not m:
            raise HookError("UserManagerService.java: requestQuietModeEnabled(...) not found")
        names = {}
        for prm in m.group(1).split(","):
            words = re.sub(r"@\w+(\([^)]*\))?", "", prm).split()
            if len(words) == 2:
                names.setdefault(words[0], []).append(words[1])
        try:
            (pkg,), (enable,), (target,) = names["String"], names["boolean"], names["IntentSender"]
            user, flags = names["int"]
        except (KeyError, ValueError):
            raise HookError("UserManagerService.requestQuietModeEnabled: unexpected parameters")
        for needed in ("void setQuietModeEnabled(", "mContext"):
            if needed not in s:
                raise HookError(f"UserManagerService.java: {needed} not found")
        out[p] = s[: m.end()] + FOLDER_GATE.format(
            mark=MARK, pkg=pkg, enable=enable, target=target, user=user, flags=flags) + s[m.end():]
    gate = p.parent / "AnonSecureFolderGate.java"
    text = (HERE / "hooks/frameworks/AnonSecureFolderGate.java").read_text()
    if not gate.exists() or gate.read_text() != text:
        out[gate] = text
    return out


LAUNCHER_HIDE = '''
        // {mark}: the hidden profile is AnonpheneOS's Secure Folder, which lists its apps in its
        // own grid. The launcher never shows it: no tab, no section, locked or unlocked.
        if (true) return true;
'''


def hook_launcher(tree):
    """Launcher3 never shows the private profile's section."""
    l3 = tree / "packages/apps/Launcher3"
    if not l3.exists():
        raise HookError("missing packages/apps/Launcher3")
    p = find_one(l3, "src/**/allapps/PrivateProfileManager.java")
    s = p.read_text()
    if f"{MARK}: the hidden profile" in s:
        return {}
    m = re.search(r"public\s+boolean\s+isPrivateSpaceHidden\s*\(\s*\)\s*\{\n", s)
    if not m:
        raise HookError("PrivateProfileManager.java: isPrivateSpaceHidden() not found")
    return {p: s[: m.end()] + LAUNCHER_HIDE.format(mark=MARK) + s[m.end():]}


def hook_share_tabs(tree):
    """IntentResolver shows only the side it was opened on: no Personal / Private tabs."""
    ir = tree / "packages/modules/IntentResolver"
    if not ir.exists():
        raise HookError("missing packages/modules/IntentResolver")
    out = {}
    p = find_one(ir, "java/src/**/domain/interactor/UserInteractor.kt")
    s = p.read_text()
    if f"{MARK}: one side only" not in s:
        users = re.search(r"\n(\s*)userRepository\.users\.map \{ users ->", s)
        avail = "availability.getOrDefault(it.primary, false)"
        if not users:
            raise HookError("UserInteractor.kt: userRepository.users.map { users -> not found")
        if s.count(avail) != 1:
            raise HookError(f"UserInteractor.kt: {avail} not found once")
        ind = users.group(1)
        s = (s[: users.start()] + f"\n{ind}// {MARK}: one side only (AnonOwnSide.kt).\n{ind}"
             + "userRepository.users.map { all -> anonOwnSide(all, launchedAs) }.map { users ->"
             + s[users.end():])
        s = s.replace(avail, "(availability.anonById(it.primary) ?: false)")
        out[p] = s
    dst = p.parent / "AnonOwnSide.kt"
    text = (HERE / "hooks/IntentResolver/AnonOwnSide.kt").read_text()
    if not dst.exists() or dst.read_text() != text:
        out[dst] = text
    return out


NUMBER_LEGACY = (
    "\n{ind}// {mark}: on AnonpheneOS only the phone-number permission (or being the default SMS\n"
    "{ind}// app) gives the number. {what} alone gives empty data instead.\n"
    "{ind}if (result == PackageManager.PERMISSION_GRANTED) {{\n"
    "{ind}    result = AppOpsManager.MODE_IGNORED;\n"
    "{ind}}}\n"
)


def hook_phone_number(tree):
    """READ_PHONE_STATE (apps built for Android 10 and older) and READ_SMS no longer give the number."""
    p = tree / "frameworks/base/services/core/java/com/android/server/pm/permission/LegacyPermissionManagerService.java"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    s = p.read_text()
    if f"{MARK}: on AnonpheneOS only the phone-number permission" in s:
        return {}
    m = re.search(r"public\s+int\s+checkPhoneNumberAccess\s*\(", s)
    if not m:
        raise HookError("LegacyPermissionManagerService.java: checkPhoneNumberAccess(...) not found")
    body = s[m.end():]
    # 1. READ_PHONE_STATE for old apps: "if (result == GRANTED) { return result; }" right after
    #    the READ_PHONE_STATE check.
    legacy = re.search(r"android\.Manifest\.permission\.READ_PHONE_STATE,[^;]*;\n(\s*)if \(result == "
                       r"PackageManager\.PERMISSION_GRANTED\) \{\n\s*return result;\n\s*\}\n", body)
    # 2. READ_SMS: "if (checkPermissionAndAppop(..READ_SMS..) == GRANTED) { return GRANTED; }"
    sms = re.search(r"\n(\s*)if \(checkPermissionAndAppop\(packageName, android\.Manifest\.permission\.READ_SMS,"
                    r"[^{]*\{\n\s*return PackageManager\.PERMISSION_GRANTED;\n\s*\}\n", body)
    if not legacy or not sms or sms.start() < legacy.end():
        raise HookError("LegacyPermissionManagerService.checkPhoneNumberAccess: READ_PHONE_STATE / READ_SMS checks not found")
    ind = legacy.group(1)
    new_legacy = body[legacy.start():legacy.end()]
    new_legacy = re.sub(r"if \(result == PackageManager\.PERMISSION_GRANTED\) \{\n\s*return result;\n\s*\}\n",
                        NUMBER_LEGACY.format(ind=ind, mark=MARK, what="READ_PHONE_STATE").lstrip(), new_legacy)
    sind = sms.group(1)
    call = re.search(r"if \((checkPermissionAndAppop\(packageName, android\.Manifest\.permission\.READ_SMS,[^{]*?)\)\s*\{",
                     body[sms.start():sms.end()], re.S)
    new_sms = (f"\n{sind}// {MARK}: READ_SMS alone gives empty data, not the number.\n"
               f"{sind}if (({call.group(1)}) && result != PackageManager.PERMISSION_GRANTED) {{\n"
               f"{sind}    result = AppOpsManager.MODE_IGNORED;\n{sind}}}\n")
    body = (body[:legacy.start()] + new_legacy + body[legacy.end():sms.start()] + new_sms + body[sms.end():])
    return {p: s[: m.end()] + body}


PHONE_BLANK = '''
        // {mark}: apps you chose to give a blank phone number get empty data, as if the
        // permission's app op were off (Settings > AnonpheneOS privacy > Phone number).
        final long anonToken = Binder.clearCallingIdentity();
        try {{
            final String anonBlank = android.provider.Settings.Secure.getStringForUser(
                    context.getContentResolver(), "anon_phone_number_blank",
                    android.os.UserHandle.getUserId({uid}));
            final String[] anonPkgs = anonBlank == null ? null
                    : context.getPackageManager().getPackagesForUid({uid});
            if (anonPkgs != null) {{
                for (String anonEntry : anonBlank.split(",")) {{
                    for (String anonPkg : anonPkgs) {{
                        if (anonPkg.equals(anonEntry.trim())) return false;
                    }}
                }}
            }}
        }} finally {{
            Binder.restoreCallingIdentity(anonToken);
        }}
'''


def hook_phone_blank(tree):
    """TelephonyPermissions.checkReadPhoneNumber: chosen apps get a blank number."""
    p = tree / "frameworks/base/telephony/common/com/android/internal/telephony/TelephonyPermissions.java"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    s = p.read_text()
    if f"{MARK}: apps you chose to give a blank phone number" in s:
        return {}
    m = re.search(r"public\s+static\s+boolean\s+checkReadPhoneNumber\s*\(([^)]*)\)\s*\{\n", s)
    if not m:
        raise HookError("TelephonyPermissions.java: checkReadPhoneNumber(...) not found")
    names = [re.sub(r"@\w+(\([^)]*\))?", "", x).split() for x in m.group(1).split(",")]
    if ["Context", "context"] not in names or ["int", "uid"] not in names:
        raise HookError("TelephonyPermissions.checkReadPhoneNumber: expected (Context context, ..., int uid, ...)")
    if "import android.os.Binder;" not in s:
        raise HookError("TelephonyPermissions.java: android.os.Binder not imported")
    return {p: s[: m.end()] + PHONE_BLANK.format(mark=MARK, uid="uid") + s[m.end():]}


def hook_mobile_data(tree):
    """NetworkPolicyManagerService: apps may use mobile data only once allowed."""
    p = tree / "frameworks/base/services/core/java/com/android/server/net/NetworkPolicyManagerService.java"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    out = {}
    s = p.read_text()
    if f"{MARK}: AnonpheneOS mobile data" not in s:
        m = re.search(r"private\s+boolean\s+isRestrictedByAdminUL\s*\(\s*int\s+uid\s*\)\s*\{", s)
        ret = "return restrictedUids != null && restrictedUids.contains(uid);"
        if not m or s.find(ret, m.end()) < 0 or s.find(ret, m.end()) > m.end() + 400:
            raise HookError("NetworkPolicyManagerService.java: isRestrictedByAdminUL(int uid) not as expected")
        at = s.find(ret, m.end())
        s = (s[:at] + "// " + MARK + ": AnonpheneOS mobile data permission (AnonMobileData.java).\n"
             "        return (restrictedUids != null && restrictedUids.contains(uid))\n"
             "                || AnonMobileData.blocks(uid);" + s[at + len(ret):])
        r = re.search(r"public\s+void\s+systemReady\s*\([^)]*\)\s*\{", s)
        start = "mMultipathPolicyTracker.start();"
        at = s.find(start, r.end()) if r else -1
        if at < 0 or at > r.end() + 800:
            raise HookError("NetworkPolicyManagerService.systemReady: mMultipathPolicyTracker.start() not found")
        for needed in ("void updateRulesForRestrictBackgroundUL()", "mUidRulesFirstLock", "final Handler mHandler"):
            if needed not in s:
                raise HookError(f"NetworkPolicyManagerService.java: {needed} not found")
        end = at + len(start)
        s = (s[:end] + "\n            AnonMobileData.start(mContext, mHandler, () -> {\n"
             "                synchronized (mUidRulesFirstLock) {\n"
             "                    updateRulesForRestrictBackgroundUL();\n"
             "                }\n"
             "            });" + s[end:])
        out[p] = s
    dst = p.parent / "AnonMobileData.java"
    text = (HERE / "hooks/frameworks/AnonMobileData.java").read_text()
    if not dst.exists() or dst.read_text() != text:
        out[dst] = text
    return out


VPN_HELPERS = '''
    // {mark}: helpers for AnonVpnRoute (the Secure Folder and the main VPN, docs/design/VPN.md).
    /** {{set-up VPN app or null, connected VPN app or null}}, read under this Vpn's lock. */
    synchronized String[] anonPackages() {{
        final String prepared = mPackage == null || VpnConfig.LEGACY_VPN.equals(mPackage)
                ? null : mPackage;
        return new String[] {{prepared, mNetworkAgent != null && mConfig != null ? prepared : null}};
    }}

    int anonOwnerUid() {{
        return mOwnerUID;
    }}

    int anonUserId() {{
        return mUserId;
    }}

    int anonNetId() {{
        final NetworkAgent agent = mNetworkAgent;
        final android.net.Network net = agent == null ? null : agent.getNetwork();
        return net == null ? 0 : net.getNetId();
    }}

    synchronized void anonRefreshRanges() {{
        if (mNetworkAgent != null && mConfig != null && mNetworkCapabilities != null) {{
            mNetworkCapabilities = new NetworkCapabilities.Builder(mNetworkCapabilities)
                    .setUids(createUserAndRestrictedProfilesRanges(mUserId,
                            mConfig.allowedApplications, mConfig.disallowedApplications))
                    .build();
            doSendNetworkCapabilities(mNetworkAgent, mNetworkCapabilities);
        }}
        if (mLockdown) setVpnForcedLocked(true);
    }}
'''

VPN_SELF = '''
        // {mark}: a Secure Folder's VPN doesn't carry its own app: the owner's VPN does.
        final String anonSelf = AnonVpnRoute.folderVpnPackage(userId);
        if (anonSelf != null && AnonVpnRoute.chainsOut(mContext, userId)) {{
            if (allowedApplications != null) {{
                allowedApplications = new java.util.ArrayList<>(allowedApplications);
                allowedApplications.remove(anonSelf);
            }} else {{
                disallowedApplications = disallowedApplications == null
                        ? new java.util.ArrayList<>() : new java.util.ArrayList<>(disallowedApplications);
                if (!disallowedApplications.contains(anonSelf)) disallowedApplications.add(anonSelf);
            }}
        }}
'''

VPN_FOLDER = '''
        // {mark}: this user's Secure Folder rides this VPN: all of it, or only the VPN running
        // inside it, whose tunnel this VPN then carries.
        for (int anonFolder : AnonVpnRoute.carriedFolders(mContext, userId)) {{
            final String anonOwn = AnonVpnRoute.folderVpnPackage(anonFolder);
            addUserToRanges(ranges, anonFolder,
                    anonOwn == null ? null : java.util.List.of(anonOwn), null);
        }}
'''


def hook_vpn_folder(tree):
    """Vpn and VpnManagerService: the Secure Folder rides, and its VPN chains through, the main VPN."""
    base = tree / "frameworks/base/services/core/java/com/android/server"
    vpn, vms = base / "connectivity/Vpn.java", base / "VpnManagerService.java"
    for p in (vpn, vms):
        if not p.exists():
            raise HookError(f"missing {p.relative_to(tree)}")
    out = {}
    s = vpn.read_text()
    if f"{MARK}: helpers for AnonVpnRoute" not in s:
        for needed in ("NetworkAgent mNetworkAgent", "String mPackage", "int mOwnerUID", "int mUserId",
                       "VpnConfig.LEGACY_VPN",
                       "VpnConfig mConfig", "NetworkCapabilities mNetworkCapabilities", "boolean mLockdown",
                       "void setVpnForcedLocked(boolean", "doSendNetworkCapabilities(", "mContext"):
            if needed not in s:
                raise HookError(f"Vpn.java: {needed} not found")
        cls = re.search(r"\npublic class Vpn \{\n", s)
        meth = re.search(r"Set<Range<Integer>> createUserAndRestrictedProfilesRanges\(@UserIdInt int userId,"
                         r"\s*@Nullable List<String> allowedApplications,\s*@Nullable List<String> disallowedApplications\)\s*\{\n", s)
        state = re.search(r"protected void updateState\(DetailedState detailedState, String reason\) \{\n", s)
        if not (cls and meth and state):
            raise HookError("Vpn.java: class, createUserAndRestrictedProfilesRanges(...) or updateState(...) not found")
        ret = s.find("        return ranges;\n", meth.end())
        if ret < 0 or ret > meth.end() + 2500:
            raise HookError("Vpn.createUserAndRestrictedProfilesRanges: return ranges; not found")
        # Insert from the end of the file backwards, so earlier offsets stay valid.
        edits = sorted([(cls.end(), VPN_HELPERS), (meth.end(), VPN_SELF), (ret, VPN_FOLDER),
                        (state.end(), "        AnonVpnRoute.changed(); // " + MARK + "\n")], reverse=True)
        for at, text in edits:
            s = s[:at] + text.format(mark=MARK) + s[at:]
        out[vpn] = s
    t = vms.read_text()
    if "AnonVpnRoute.register(" not in t:
        put, drop = "mVpns.put(userId, userVpn);", "mVpns.delete(userId);"
        if t.count(put) != 1 or t.count(drop) != 1:
            raise HookError("VpnManagerService.java: mVpns.put/delete(userId) not found once")
        t = t.replace(put, put + "\n            com.android.server.connectivity.AnonVpnRoute.register(mContext, userId, userVpn);")
        t = t.replace(drop, drop + "\n            com.android.server.connectivity.AnonVpnRoute.unregister(userId);")
        out[vms] = t
    dst = vpn.parent / "AnonVpnRoute.java"
    text = (HERE / "hooks/frameworks/AnonVpnRoute.java").read_text()
    if not dst.exists() or dst.read_text() != text:
        out[dst] = text
    return out


FWMARK_HELPER = """
// {mark}: AnonpheneOS Secure Folder VPN chaining (docs/design/VPN.md). system_server publishes
// "uid:netId" pairs in anon.vpn.carried: the VPN app running inside a Secure Folder (uid) has
// its protected tunnel sockets carried by its owner's VPN (netId), or by the unreachable network
// when that VPN is meant to be on but isn't up. Read only for VPN apps' protected sockets.
static unsigned anonCarrierNetId(uid_t uid) {{
    const std::string carried = android::base::GetProperty("anon.vpn.carried", "");
    if (carried.empty()) return NETID_UNSET;
    for (const std::string& entry : android::base::Split(carried, ",")) {{
        const std::vector<std::string> kv = android::base::Split(entry, ":");
        unsigned u = 0, net = 0;
        if (kv.size() == 2 && android::base::ParseUint(kv[0], &u) &&
            android::base::ParseUint(kv[1], &net) && u == uid) {{
            return net;
        }}
    }}
    return NETID_UNSET;
}}
"""


def hook_vpn_netd(tree):
    """netd FwmarkServer: a Secure Folder VPN's protected sockets go through the owner's VPN."""
    p = tree / "system/netd/server/FwmarkServer.cpp"
    if not p.exists():
        raise HookError(f"missing {p.relative_to(tree)}")
    s = p.read_text()
    if "anonCarrierNetId(" in s:
        return {}
    inc = "#include <android-base/properties.h>\n"
    ns = re.search(r"\nnamespace android(::net)? \{\n|\nnamespace net \{\n", s)
    line = "fwmark.netId = mNetworkController->getDefaultNetwork();"
    if inc not in s or not ns or s.count(line) != 2:
        raise HookError("FwmarkServer.cpp: includes, namespace, or the two protected-socket default-network lines not found")
    s = s.replace(inc, inc + "#include <android-base/parseint.h>\n#include <android-base/strings.h>\n", 1)
    ns = re.search(r"\nnamespace android(::net)? \{\n|\nnamespace net \{\n", s)
    s = s[: ns.end()] + FWMARK_HELPER.format(mark=MARK) + s[ns.end():]

    def carried(m):
        ind = m.group(1)
        return (f"{ind}if (const unsigned anonNet = anonCarrierNetId(client->getUid());\n"
                f"{ind}        anonNet != NETID_UNSET) {{\n"
                f"{ind}    // {MARK}: tunnel carried by the owner's VPN (see anonCarrierNetId).\n"
                f"{ind}    fwmark.netId = anonNet;\n"
                f"{ind}    fwmark.explicitlySelected = true;\n"
                f"{ind}}} else {{\n"
                f"{ind}    {line}\n"
                f"{ind}}}")
    s = re.sub(r"^([ \t]*)" + re.escape(line), carried, s, flags=re.M)
    if "client->getUid()" not in s:
        raise HookError("FwmarkServer.cpp: client->getUid() not used")
    return {p: s}


HOOKS = [hook_product, hook_settings, hook_hide_people, hook_share_list, hook_share_tabs,
         hook_folder_gate, hook_launcher, hook_phone_number, hook_phone_blank, hook_mobile_data,
         hook_vpn_folder, hook_vpn_netd]


OURS = ".anonphene-copy"


def copy_ours(tree):
    """vendor/anonphene and packages/apps/Anon* become copies of this repo's (Soong doesn't
    follow symlinked folders). A folder there that isn't one of our copies is never replaced."""
    copies = {
        tree / "vendor/anonphene": HERE / "vendor/anonphene",
        tree / "packages/apps/AnonSecureFolder": HERE / "packages/AnonSecureFolder",
        tree / "packages/apps/AnonPrivacy": HERE / "packages/AnonPrivacy",
    }
    for dst in copies:
        if (dst.exists() or dst.is_symlink()) and not (dst / OURS).is_file():
            raise HookError(f"{dst.relative_to(tree)} exists and isn't ours; move it away first")
    for dst, origin in copies.items():
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(origin, dst, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        (dst / OURS).write_text("Copied by AnonpheneOS scripts/apply.py; edit the repo's copy.\n")


def apply(tree):
    tree = Path(tree).resolve()
    changes = {}
    errors = []
    for hook in HOOKS:
        try:
            changes.update(hook(tree))
        except HookError as e:
            errors.append(f"{hook.__name__}: {e}")
    for dst in (tree / "vendor/anonphene", tree / "packages/apps/AnonSecureFolder",
                tree / "packages/apps/AnonPrivacy"):
        if (dst.exists() or dst.is_symlink()) and not (dst / OURS).is_file():
            errors.append(f"copy_ours: {dst.relative_to(tree)} exists and isn't ours; move it away first")
    if errors:
        raise HookError("nothing was changed:\n  " + "\n  ".join(errors))
    for path, text in changes.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".anon-tmp")
        tmp.write_text(text)
        if path.exists():
            shutil.copymode(path, tmp)
        tmp.replace(path)
    copy_ours(tree)
    return sorted(str(p.relative_to(tree)) for p in changes)


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    try:
        changed = apply(argv[1])
    except HookError as e:
        print(f"apply.py: {e}", file=sys.stderr)
        return 1
    for c in changed:
        print(f"changed {c}")
    print("AnonpheneOS hooks are in place.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
