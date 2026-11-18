#!/usr/bin/env python3
"""Build AnonpheneOS's two system apps as APKs on an ordinary PC, for the emulator.

    scripts/build-apks.py <platform.pk8> <platform.x509.pem> [out dir]

This doesn't need the Android source tree. It uses:
  - a JDK and the Android SDK's build-tools (JAVA_HOME / ANDROID_HOME, or ~/.local/opt),
  - Android's system-API stub library, ANONPHENE_SYSTEM_JAR or
    ~/.local/opt/aosp-bits/system-android-35.jar (from AOSP's prebuilts/sdk),
  - tools/stubs: compile-only stand-ins for the few Android-internal classes used.

The key must be the platform key of the image the apps go on: for Google's emulator images,
AOSP's public test key. Never use it for a real phone. Output goes to out/apks by default.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = Path.home() / ".local/opt"
APPS = {
    "AnonSecureFolder": ["folderkey"],
    "AnonPrivacy": ["sharelist"],
}


def tool(env, pattern):
    if os.environ.get(env):
        return Path(os.environ[env])
    hits = sorted(LOCAL.glob(pattern))
    return hits[-1] if hits else None


def run(cmd):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"failed: {' '.join(map(str, cmd[:3]))} ...\n{r.stdout}{r.stderr}")
    return r


def system_jar():
    p = Path(os.environ.get("ANONPHENE_SYSTEM_JAR", LOCAL / "aosp-bits/system-android-35.jar"))
    return p if p.is_file() else None


def compile_stubs(javac, jar, tmp):
    out = tmp / "stubs"
    out.mkdir()
    run([javac, "-proc:none", "--release", "17", "-cp", jar, "-d", out]
        + list((ROOT / "tools/stubs/src").rglob("*.java")))
    return out


def build(app, libs, javac, bt, jar, stubs, key, cert, out_dir, tmp):
    base = ROOT / "packages" / app
    work = tmp / app
    gen, cls, dex = work / "gen", work / "cls", work / "dex"
    for d in (gen, cls, dex):
        d.mkdir(parents=True)
    run([bt / "aapt2", "compile", "--dir", base / "res", "-o", work / "res.zip"])
    run([bt / "aapt2", "link", "-I", jar, "--manifest", base / "AndroidManifest.xml",
         "--java", gen, "-o", work / "unsigned.apk", work / "res.zip",
         "--min-sdk-version", "35", "--target-sdk-version", "35",
         "--version-code", "1", "--version-name", "0.1"])
    sources = list(base.rglob("*.java")) + list(gen.rglob("*.java"))
    for lib in libs:
        sources += list((ROOT / "vendor/anonphene/lib" / lib / "src").rglob("*.java"))
    run([javac, "-proc:none", "--release", "17", "-cp", f"{jar}{os.pathsep}{stubs}", "-d", cls] + sources)
    run([bt / "d8", "--release", "--min-api", "35", "--lib", jar, "--classpath", stubs,
         "--output", dex] + list(cls.rglob("*.class")))
    with zipfile.ZipFile(work / "unsigned.apk", "a") as z:
        z.write(dex / "classes.dex", "classes.dex")
    run([bt / "zipalign", "-f", "-p", "4", work / "unsigned.apk", work / "aligned.apk"])
    apk = out_dir / f"{app}.apk"
    run([bt / "apksigner", "sign", "--key", key, "--cert", cert, "--out", apk, work / "aligned.apk"])
    return apk


def main(argv):
    if len(argv) not in (3, 4):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    key, cert = Path(argv[1]), Path(argv[2])
    out_dir = Path(argv[3]) if len(argv) == 4 else ROOT / "out/apks"
    jdk, sdk, jar = tool("JAVA_HOME", "jdk-*"), tool("ANDROID_HOME", "android-sdk"), system_jar()
    if not (jdk and sdk and jar):
        print("build-apks: needs a JDK, the Android SDK and the system-API jar (see above).", file=sys.stderr)
        return 2
    bt = sorted((sdk / "build-tools").iterdir())[-1]
    os.environ["JAVA_HOME"] = str(jdk)
    os.environ["PATH"] = f"{jdk / 'bin'}{os.pathsep}{os.environ['PATH']}"
    out_dir.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="anon-apks-"))
    try:
        stubs = compile_stubs(jdk / "bin/javac", jar, tmp)
        for app, libs in APPS.items():
            print(build(app, libs, jdk / "bin/javac", bt, jar, stubs, key, cert, out_dir, tmp))
    finally:
        shutil.rmtree(tmp)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
