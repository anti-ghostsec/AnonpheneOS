"""Checks on the repo's own files: manifests, resources, no-logs settings, permissions."""

import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
A = "{http://schemas.android.com/apk/res/android}"
APPS = ["AnonSecureFolder", "AnonPrivacy"]


def manifest(app):
    return ET.parse(ROOT / "packages" / app / "AndroidManifest.xml").getroot()


class Xml(unittest.TestCase):
    def test_every_xml_parses(self):
        for p in ROOT.rglob("*.xml"):
            with self.subTest(str(p.relative_to(ROOT))):
                ET.parse(p)

    def test_string_names_unique_and_used_ones_exist(self):
        for app in APPS:
            res = ROOT / "packages" / app / "res/values/strings.xml"
            names = [e.get("name") for e in ET.parse(res).getroot()]
            self.assertEqual(len(names), len(set(names)), f"{app}: duplicate string")
            used = set()
            for src in (ROOT / "packages" / app).rglob("*.java"):
                used |= set(re.findall(r"(?<!android\.)R\.string\.(\w+)", src.read_text()))
            used |= set(re.findall(r"@string/(\w+)", (ROOT / "packages" / app / "AndroidManifest.xml").read_text()))
            self.assertEqual(used - set(names), set(), f"{app}: missing strings")

    def test_drawables_exist(self):
        for app in APPS:
            base = ROOT / "packages" / app
            used = set(re.findall(r"@drawable/(\w+)", (base / "AndroidManifest.xml").read_text()))
            for src in base.rglob("*.java"):
                used |= set(re.findall(r"(?<!android\.)R\.drawable\.(\w+)", src.read_text()))
            for d in used:
                self.assertTrue((base / "res/drawable" / f"{d}.xml").is_file(), f"{app}: {d}")

    def test_classes_named_in_manifest_exist(self):
        for app in APPS:
            m = manifest(app)
            pkg = m.get("package")
            src = ROOT / "packages" / app / "src" / pkg.replace(".", "/")
            for tag in ("activity", "receiver", "service"):
                for e in m.iter(tag):
                    name = e.get(A + "name")
                    self.assertTrue((src / f"{name.lstrip('.')}.java").is_file(), f"{app}: {name}")
            for e in m.iter("activity-alias"):
                target = e.get(A + "targetActivity").lstrip(".")
                self.assertTrue((src / f"{target}.java").is_file())


class Privacy(unittest.TestCase):
    def test_no_internet_in_our_apps(self):
        for app in APPS:
            perms = {e.get(A + "name") for e in manifest(app).iter("uses-permission")}
            self.assertNotIn("android.permission.INTERNET", perms, app)

    def test_no_backups(self):
        for app in APPS:
            a = manifest(app).find("application")
            self.assertEqual(a.get(A + "allowBackup"), "false", app)

    def test_privileged_permissions_are_granted(self):
        granted = {}
        x = ET.parse(ROOT / "vendor/anonphene/permissions/privapp-permissions-anonphene.xml").getroot()
        for p in x.iter("privapp-permissions"):
            granted[p.get("package")] = {e.get("name") for e in p.iter("permission")}
        # Permissions only a privileged app gets, which must be in the privapp list.
        privileged = {
            "android.permission.MANAGE_USERS", "android.permission.CREATE_USERS",
            "android.permission.INTERACT_ACROSS_USERS", "android.permission.INTERACT_ACROSS_USERS_FULL",
            "android.permission.MODIFY_QUIET_MODE", "android.permission.INSTALL_EXISTING_PACKAGES",
            "android.permission.INSTALL_PACKAGES", "android.permission.REBOOT",
            "android.permission.WRITE_SECURE_SETTINGS", "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
            "android.permission.CHANGE_COMPONENT_ENABLED_STATE", "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
            "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_CLIPBOARD_IN_BACKGROUND",
        }
        for app in APPS:
            m = manifest(app)
            wanted = {e.get(A + "name") for e in m.iter("uses-permission")} & privileged
            self.assertEqual(wanted - granted.get(m.get("package"), set()), set(), app)

    def test_logging_is_silenced(self):
        props = dict(l.split("=", 1) for l in (ROOT / "vendor/anonphene/config/anonphene.prop").read_text().splitlines()
                     if l.strip() and not l.startswith("#"))
        self.assertEqual(props["persist.log.tag"], "S")
        self.assertEqual(props["log.tag"], "S")
        self.assertEqual(props["persist.traced.enable"], "0")
        self.assertEqual(props["tombstoned.max_tombstone_count"], "0")

    def test_trace_folders_live_in_ram(self):
        rc = (ROOT / "vendor/anonphene/init/anonphene.rc").read_text()
        for d in ("/data/tombstones", "/data/anr", "/data/system/dropbox", "/data/misc/logd",
                  "/data/misc/perfetto-traces"):
            self.assertRegex(rc, rf"mount tmpfs tmpfs {re.escape(d)} ", d)
            self.assertIn(f"restorecon_recursive {d}", rc)
        for level in "VDIWEFA":
            self.assertIn(f"on property:persist.log.tag={level}\n    setprop persist.log.tag S", rc)

    def test_vpn_property_is_locked_down(self):
        te = (ROOT / "vendor/anonphene/sepolicy/anonphene.te").read_text()
        ctx = (ROOT / "vendor/anonphene/sepolicy/property_contexts").read_text()
        self.assertIn("anon.vpn.carried    u:object_r:anon_vpn_prop:s0 exact string", ctx)
        self.assertIn("set_prop(system_server, anon_vpn_prop)", te)
        self.assertIn("get_prop(netd, anon_vpn_prop)", te)
        # Only system_server may set it; nothing else gets set_prop on it.
        self.assertEqual(len(re.findall(r"set_prop\(\w+, anon_vpn_prop\)", te)), 1)
        route = (ROOT / "hooks/frameworks/AnonVpnRoute.java").read_text()
        self.assertIn('PROP = "anon.vpn.carried"', route)
        self.assertIn('"anon.vpn.carried"', (ROOT / "scripts/apply.py").read_text())

    def test_no_logging_calls_in_our_code(self):
        for p in list((ROOT / "packages").rglob("*.java")) + list((ROOT / "hooks").rglob("*.java")):
            text = p.read_text()
            self.assertNotRegex(text, r"\bLog\.[vdiwe]\(|printStackTrace\(|System\.(out|err)\.", str(p))

    def test_java_brackets_balance(self):
        token = re.compile(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
        for p in list(ROOT.rglob("*.java")):
            code = token.sub(" ", p.read_text())
            for a, b in ("{}", "()", "[]"):
                self.assertEqual(code.count(a), code.count(b), f"{p.name}: {a}{b}")

    def test_both_apps_share_one_dark_theme(self):
        for f in ("values/colors.xml", "values/themes.xml", "drawable/anon_button.xml",
                  "drawable/anon_button_secondary.xml", "drawable/anon_field.xml", "drawable/anon_popup.xml"):
            a = (ROOT / "packages/AnonSecureFolder/res" / f).read_text()
            b = (ROOT / "packages/AnonPrivacy/res" / f).read_text()
            self.assertEqual(a, b, f)
        theme = (ROOT / "packages/AnonSecureFolder/res/values/themes.xml").read_text()
        self.assertIn('parent="@android:style/Theme.DeviceDefault"', theme)  # dark, not DayNight
        self.assertIn('<item name="android:forceDarkAllowed">false</item>', theme)
        for app in APPS:
            self.assertEqual(manifest(app).find("application").get(A + "theme"), "@style/Anon", app)

    def test_secure_folder_screens_are_secure(self):
        src = (ROOT / "packages/AnonSecureFolder/src/app/anonphene/securefolder/FolderActivity.java").read_text()
        self.assertIn("FLAG_SECURE", src)

    def test_no_private_details(self):
        r = subprocess.run([sys.executable, str(ROOT / "scripts/check-private.py")],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


class Java(unittest.TestCase):
    """Runs scripts/java-check.py when a JDK and Android SDK are on this PC."""

    def test_java_compiles_and_crypto_checks_pass(self):
        r = subprocess.run([sys.executable, str(ROOT / "scripts/java-check.py")], capture_output=True, text=True)
        if r.returncode == 2:
            self.skipTest("no JDK or Android SDK")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
