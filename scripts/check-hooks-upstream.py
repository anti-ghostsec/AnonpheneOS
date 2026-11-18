#!/usr/bin/env python3
"""Run scripts/apply.py against the real upstream files it changes, fetched from AOSP.

    scripts/check-hooks-upstream.py [ref]        (default: refs/heads/main)

Downloads only those few files, from android.googlesource.com, into a temporary tree and
applies every hook there. It shows whether each anchor still matches upstream code without a
full source sync. GrapheneOS is based on AOSP but changes some files, so a full sync of the
GrapheneOS tag is still the final word. Nothing is written outside the temporary tree.
"""

import base64
import importlib.util
import sys
import tempfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE = "https://android.googlesource.com/platform"
FILES = {
    "build/make/target/product/handheld_product.mk": "build/+/{ref}/target/product/handheld_product.mk",
    "frameworks/base/core/java/android/provider/Settings.java":
        "frameworks/base/+/{ref}/core/java/android/provider/Settings.java",
    "frameworks/base/services/core/java/com/android/server/pm/ShortcutService.java":
        "frameworks/base/+/{ref}/services/core/java/com/android/server/pm/ShortcutService.java",
    "frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java":
        "frameworks/base/+/{ref}/services/core/java/com/android/server/pm/UserManagerService.java",
    "packages/modules/IntentResolver/java/src/com/android/intentresolver/ResolverListController.java":
        "packages/modules/IntentResolver/+/{ref}/java/src/com/android/intentresolver/ResolverListController.java",
    "packages/modules/IntentResolver/java/src/com/android/intentresolver/domain/interactor/UserInteractor.kt":
        "packages/modules/IntentResolver/+/{ref}/java/src/com/android/intentresolver/domain/interactor/UserInteractor.kt",
    "packages/apps/Launcher3/src/com/android/launcher3/allapps/PrivateProfileManager.java":
        "packages/apps/Launcher3/+/{ref}/src/com/android/launcher3/allapps/PrivateProfileManager.java",
}


def fetch(path):
    req = urllib.request.Request(f"{BASE}/{path}?format=TEXT", headers={"User-Agent": "anonphene-hook-check"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return base64.b64decode(r.read())


def main(argv):
    ref = argv[1] if len(argv) > 1 else "refs/heads/main"
    spec = importlib.util.spec_from_file_location("apply", HERE / "apply.py")
    apply = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(apply)
    with tempfile.TemporaryDirectory() as d:
        tree = Path(d)
        for dst, src in FILES.items():
            try:
                data = fetch(src.format(ref=ref))
            except OSError as e:
                print(f"couldn't fetch {dst}: {e}", file=sys.stderr)
                return 2
            (tree / dst).parent.mkdir(parents=True, exist_ok=True)
            (tree / dst).write_bytes(data)
        ok = True
        for hook in apply.HOOKS:
            try:
                changed = hook(tree)
                print(f"ok    {hook.__name__}: {len(changed)} file(s)")
            except apply.HookError as e:
                ok = False
                print(f"FAIL  {hook.__name__}: {e}")
    print("All hooks match upstream." if ok else "Some hooks need updating for upstream.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
