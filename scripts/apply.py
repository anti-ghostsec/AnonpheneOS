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


HOOKS = [hook_product, hook_settings, hook_hide_people, hook_share_list, hook_share_tabs,
         hook_folder_gate, hook_launcher]


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
