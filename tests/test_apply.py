"""scripts/apply.py on a small fake source tree."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("apply", ROOT / "scripts/apply.py")
apply = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apply)

FILES = {
    "build/make/target/product/handheld_product.mk": "PRODUCT_PACKAGES += Launcher3\n",
    "frameworks/base/core/java/android/provider/Settings.java": (
        "public final class Settings {\n"
        "    public static final class Secure extends NameValueTable {\n"
        "        public static final String ANDROID_ID = \"android_id\";\n"
        "    }\n"
        "}\n"),
    "frameworks/base/services/core/java/com/android/server/pm/ShortcutService.java": (
        "public class ShortcutService {\n"
        "    final Context mContext;\n"
        "    @Override\n"
        "    public ParceledListSlice<ShareShortcutInfo> getShareTargets(String packageName,\n"
        "            IntentFilter filter, @UserIdInt int userId) {\n"
        "        return real(packageName, filter, userId);\n"
        "    }\n"
        "}\n"),
    "frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java": (
        "public class UserManagerService {\n"
        "    private final Context mContext;\n"
        "    @Override\n"
        "    public boolean requestQuietModeEnabled(@NonNull String callingPackage, boolean enableQuietMode,\n"
        "            @UserIdInt int userId, @Nullable IntentSender target, @QuietModeFlag int flags) {\n"
        "        return realCheck(callingPackage, enableQuietMode, userId, target, flags);\n"
        "    }\n"
        "    private void setQuietModeEnabled(int userId, boolean enable, IntentSender target, String pkg) {}\n"
        "}\n"),
    "packages/apps/Launcher3/src/com/android/launcher3/allapps/PrivateProfileManager.java": (
        "public class PrivateProfileManager {\n"
        "    public boolean isPrivateSpaceHidden() {\n"
        "        return getCurrentState() == STATE_DISABLED;\n"
        "    }\n"
        "}\n"),
    "packages/modules/IntentResolver/java/src/com/android/intentresolver/domain/interactor/UserInteractor.kt": (
        "class UserInteractor(private val userRepository: UserRepository, val launchedAs: UserHandle) {\n"
        "    val profiles: Flow<List<Profile>> =\n"
        "        userRepository.users.map { users ->\n"
        "            users.mapNotNull { it.toProfile() }\n"
        "        }\n"
        "    val availability: Flow<Map<Profile, Boolean>> =\n"
        "        combine(profiles, userRepository.availability) { profiles, availability ->\n"
        "            profiles.associateWith { availability.getOrDefault(it.primary, false) }\n"
        "        }\n"
        "}\n"),
    "packages/modules/IntentResolver/java/src/com/android/intentresolver/ResolverListController.java": (
        "public class ResolverListController {\n"
        "    private final Context mContext;\n"
        "    private final int mLaunchedFromUid;\n"
        "    public ArrayList<ResolvedComponentInfo> filterIneligibleActivities(\n"
        "            List<ResolvedComponentInfo> inputList, boolean returnCopy) {\n"
        "        for (int i = inputList.size()-1; i >= 0; i--) {\n"
        "            ActivityInfo ai = inputList.get(i).getResolveInfoAt(0).activityInfo;\n"
        "            if (granted != PackageManager.PERMISSION_GRANTED\n"
        "                    || isComponentFiltered(ai.getComponentName())) {\n"
        "                inputList.remove(i);\n"
        "            }\n"
        "        }\n"
        "        return null;\n"
        "    }\n"
        "}\n"),
}


def make_tree(d, skip=()):
    for rel, text in FILES.items():
        if rel in skip:
            continue
        p = Path(d) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)


def snapshot(d):
    return {str(p.relative_to(d)): p.read_text() for p in Path(d).rglob("*") if p.is_file()}


class Apply(unittest.TestCase):
    def test_hooks_go_in(self):
        with tempfile.TemporaryDirectory() as d:
            make_tree(d)
            apply.apply(d)
            t = Path(d)
            mk = (t / "build/make/target/product/handheld_product.mk").read_text()
            self.assertIn("inherit-product-if-exists, vendor/anonphene/anonphene.mk", mk)
            st = (t / "frameworks/base/core/java/android/provider/Settings.java").read_text()
            self.assertLess(st.index("class Secure"), st.index("ANON_SHARE_TARGETS"))
            self.assertLess(st.index("ANON_SHARE_TARGETS"), st.index("ANDROID_ID"))
            ss = (t / "frameworks/base/services/core/java/com/android/server/pm/ShortcutService.java").read_text()
            self.assertIn('"anon_share_hide_people", 1, userId)', ss)
            self.assertLess(ss.index("anon_share_hide_people"), ss.index("return real("))
            rl = next(t.rglob("ResolverListController.java")).read_text()
            self.assertIn("|| isComponentFiltered(ai.getComponentName())\n", rl)
            self.assertIn("|| com.android.intentresolver.AnonShareFilter.blocks(\n"
                          "                            mContext, mLaunchedFromUid, ai.getComponentName())) {", rl)
            um = (t / "frameworks/base/services/core/java/com/android/server/pm/UserManagerService.java").read_text()
            self.assertIn("AnonSecureFolderGate.allows(mContext, callingPackage,", um)
            self.assertIn("setQuietModeEnabled(userId, false, target, callingPackage);", um)
            self.assertIn("(flags & UserManager.QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL)", um)
            self.assertLess(um.index("AnonSecureFolderGate"), um.index("return realCheck("))
            self.assertTrue((t / "frameworks/base/services/core/java/com/android/server/pm/AnonSecureFolderGate.java").is_file())
            ppm = next(t.rglob("PrivateProfileManager.java")).read_text()
            self.assertLess(ppm.index("if (true) return true;"), ppm.index("return getCurrentState()"))
            ui = next(t.rglob("UserInteractor.kt")).read_text()
            self.assertIn("userRepository.users.map { all -> anonOwnSide(all, launchedAs) }.map { users ->", ui)
            self.assertIn("(availability.anonById(it.primary) ?: false)", ui)
            self.assertTrue(next(t.rglob("UserInteractor.kt")).with_name("AnonOwnSide.kt").is_file())
            ir = t / "packages/modules/IntentResolver/java/src"
            self.assertTrue((ir / "com/android/intentresolver/AnonShareFilter.java").is_file())
            self.assertTrue((ir / "app/anonphene/sharelist/ShareList.java").is_file())
            self.assertTrue((t / "vendor/anonphene/anonphene.mk").is_file())
            self.assertTrue((t / "packages/apps/AnonSecureFolder/AndroidManifest.xml").is_file())
            self.assertTrue((t / "packages/apps/AnonPrivacy/.anonphene-copy").is_file())

    def test_second_run_changes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            make_tree(d)
            apply.apply(d)
            before = snapshot(d)
            self.assertEqual(apply.apply(d), [])
            self.assertEqual(snapshot(d), before)

    def test_missing_anchor_touches_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            make_tree(d)
            rl = Path(d) / "packages/modules/IntentResolver/java/src/com/android/intentresolver/ResolverListController.java"
            rl.write_text("public class ResolverListController { }\n")
            before = snapshot(d)
            with self.assertRaises(apply.HookError) as e:
                apply.apply(d)
            self.assertIn("filterIneligibleActivities", str(e.exception))
            self.assertEqual(snapshot(d), before)

    def test_foreign_folder_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as d:
            make_tree(d)
            mine = Path(d) / "vendor/anonphene/keep.txt"
            mine.parent.mkdir(parents=True)
            mine.write_text("someone else's")
            with self.assertRaises(apply.HookError):
                apply.apply(d)
            self.assertEqual(mine.read_text(), "someone else's")
            self.assertNotIn("ANONPHENE-HOOK", (Path(d) / "build/make/target/product/handheld_product.mk").read_text())


if __name__ == "__main__":
    unittest.main()
