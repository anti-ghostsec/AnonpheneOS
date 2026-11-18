#!/usr/bin/env python3
"""Compile-check AnonpheneOS's Java without an Android source tree.

    scripts/java-check.py

Uses a JDK and an Android SDK (platform android-35 and build-tools) found in
ANDROID_HOME / JAVA_HOME, or in ~/.local/opt. Each app's resources are linked with aapt2 to get
its R class, then its Java is compiled against the SDK's android.jar.

With Android's system-API stub library present (ANONPHENE_SYSTEM_JAR, or
~/.local/opt/aosp-bits/system-android-35.jar, from AOSP's prebuilts/sdk), the apps are compiled
against it plus tools/stubs, and every error counts. Without it:

The SDK leaves out Android's hidden and system APIs, which the apps use as platform apps. Errors
about those are expected and listed in HIDDEN below; any other error fails the check. So this
catches typos, wrong types and missing imports, but not mistakes in the hidden calls themselves:
only a build in the full tree does that.

Pure-Java libraries (no Android at all) are compiled with no exceptions, and their test
runners are run.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = Path.home() / ".local/opt"

# Hidden or system APIs: "cannot find symbol" errors naming these are expected.
HIDDEN = {
    # UserManager / users
    "getProfiles", "UserInfo", "isPrivateProfile", "getUserHandle", "getUserInfo", "createProfile",
    "USER_TYPE_PROFILE_PRIVATE", "UserOperationException", "removeUser", "myUserId", "getUserId",
    "SYSTEM", "of", "QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL", "getProfileParent",
    # ActivityManager / Context / packages
    "startProfile", "startActivityAsUser", "createContextAsUser", "installExistingPackage",
    "maybeAddUserId", "getStringForUser", "getIntForUser", "ArraySet",
    # storage, properties, lock settings
    "ServiceManager", "IStorageManager", "SystemProperties", "LockPatternUtils",
    "LockscreenCredential", "ILockSettings", "VerifyCredentialResponse", "RequestThrottledException", "getIdentifier",
}


def find_tool(env, pattern):
    if os.environ.get(env):
        return Path(os.environ[env])
    hits = sorted(LOCAL.glob(pattern))
    return hits[-1] if hits else None


def run(cmd, **kw):
    return subprocess.run([str(c) for c in cmd], capture_output=True, text=True, **kw)


def javac_errors(javac, out_dir, sources, classpath=None):
    cmd = [javac, "-d", out_dir, "-Xmaxerrs", "1000", "-encoding", "UTF-8", "--release", "17"]
    if classpath:
        cmd += ["-cp", classpath]
    r = run(cmd + sources)
    # Each error starts "path:line: error: message" and the symbol follows on a "symbol:" line.
    errors = []
    for block in re.split(r"\n(?=\S+\.java:\d+: error:)", r.stdout + r.stderr):
        if ": error:" in block:
            errors.append(block.strip())
    return errors


def expected(err):
    sym = re.search(r"symbol:\s+(?:class|method|variable|static)\s+(\w+)", err)
    if sym and sym.group(1) in HIDDEN:
        return True
    pkg = re.search(r"package ([\w.]+) does not exist", err)
    if pkg and (pkg.group(1) in ("com.android.internal.widget", "android.os.storage")
                or pkg.group(1) in HIDDEN):
        return True
    # Hidden overloads: the method exists publicly with other parameters.
    m = re.search(r"method (\w+) in class \w+ cannot be applied", err)
    if m and m.group(1) in HIDDEN | {"requestQuietModeEnabled"}:
        return True
    return False


def system_jar():
    p = Path(os.environ.get("ANONPHENE_SYSTEM_JAR", LOCAL / "aosp-bits/system-android-35.jar"))
    return p if p.is_file() else None


def check_app(app, javac, sdk, tmp, strict=None):
    """strict: (system-API jar, compiled stubs) for a check with no expected errors."""
    base = ROOT / "packages" / app
    aapt2 = sorted((sdk / "build-tools").glob("*/aapt2"))[-1]
    jar = strict[0] if strict else sdk / "platforms/android-35/android.jar"
    gen, cls = tmp / app / "gen", tmp / app / "cls"
    gen.mkdir(parents=True)
    cls.mkdir()
    r = run([aapt2, "compile", "--dir", base / "res", "-o", tmp / app / "res.zip"])
    if r.returncode:
        return [f"aapt2 compile: {r.stderr.strip()}"]
    r = run([aapt2, "link", "-I", jar, "--manifest", base / "AndroidManifest.xml", "--java", gen,
             "-o", tmp / app / "out.apk", tmp / app / "res.zip",
             "--min-sdk-version", "35", "--target-sdk-version", "35"])
    if r.returncode:
        return [f"aapt2 link: {r.stderr.strip()}"]
    sources = [str(p) for p in base.rglob("*.java")] + [str(p) for p in gen.rglob("*.java")]
    # Libraries an app links statically.
    bp = (base / "Android.bp").read_text()
    if "anonphene-sharelist" in bp:
        sources += [str(p) for p in (ROOT / "vendor/anonphene/lib/sharelist/src").rglob("*.java")]
    if "anonphene-folderkey" in bp:
        sources += [str(p) for p in (ROOT / "vendor/anonphene/lib/folderkey/src").rglob("*.java")]
    if strict:
        return javac_errors(javac, cls, sources, f"{strict[0]}{os.pathsep}{strict[1]}")
    return [e for e in javac_errors(javac, cls, sources, jar) if not expected(e)]


def check_libs(javac, java, tmp):
    """Plain-Java libraries: compile with no exceptions, then run their test runners."""
    problems = []
    for lib in sorted((ROOT / "vendor/anonphene/lib").iterdir()):
        sources = [str(p) for p in lib.rglob("*.java")]
        if not sources:
            continue
        out = tmp / ("lib-" + lib.name)
        out.mkdir()
        problems += javac_errors(javac, out, sources)
        for runner in lib.rglob("*Check.java"):
            pkg = re.search(r"^package ([\w.]+);", runner.read_text(), re.M)
            name = (pkg.group(1) + "." if pkg else "") + runner.stem
            r = run([java, "-cp", out, name], cwd=ROOT)
            if r.returncode:
                problems.append(f"{name} failed:\n{r.stdout}{r.stderr}")
    return problems


def main():
    jdk = find_tool("JAVA_HOME", "jdk-*")
    sdk = find_tool("ANDROID_HOME", "android-sdk")
    if not jdk or not sdk:
        print("java-check: no JDK or Android SDK found; set JAVA_HOME and ANDROID_HOME.", file=sys.stderr)
        return 2
    javac, java = jdk / "bin/javac", jdk / "bin/java"
    tmp = Path(tempfile.mkdtemp(prefix="anon-java-"))
    try:
        problems = check_libs(javac, java, tmp)
        strict = None
        sysjar = system_jar()
        if sysjar:
            stubs = tmp / "stubs"
            stubs.mkdir()
            problems += javac_errors(javac, stubs, [str(p) for p in (ROOT / "tools/stubs/src").rglob("*.java")], sysjar)
            strict = (sysjar, stubs)
        for app in ("AnonSecureFolder", "AnonPrivacy"):
            problems += [f"{app}: {e}" for e in check_app(app, javac, sdk, tmp, strict)]
    finally:
        shutil.rmtree(tmp)
    for p in problems:
        print(p, end="\n\n")
    if problems:
        print(f"{len(problems)} problem(s).", file=sys.stderr)
        return 1
    print("Java compiles against Android's system API" if strict else
          "Java compiles (apart from Android's hidden APIs)", end="")
    print(", and library checks pass.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
