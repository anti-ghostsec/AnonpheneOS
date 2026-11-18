"""The share-list format, checked against the shared vectors, plus a Java/Python sync check."""

import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import sharelist_ref as ref  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
VECTORS = json.loads((Path(__file__).parent / "share_list_vectors.json").read_text())
JAVA = (ROOT / "vendor/anonphene/lib/sharelist/src/app/anonphene/sharelist/ShareList.java").read_text()


class Vectors(unittest.TestCase):
    def test_cases(self):
        for case in VECTORS["cases"]:
            with self.subTest(case["name"]):
                sl = ref.ShareList.parse(case["text"])
                for source, expected in case["limits"]:
                    self.assertEqual(sl.limits(source), expected, f"limits({source})")
                for source, pkg, cls, expected in case["allows"]:
                    self.assertEqual(sl.allows(source, pkg, cls), expected, f"allows({source}, {pkg}, {cls})")
                self.assertEqual(sl.serialize(), case["serialized"])
                # What's written reads back the same.
                self.assertEqual(ref.ShareList.parse(sl.serialize()).serialize(), case["serialized"])

    def test_package_names(self):
        for name, expected in VECTORS["packageNames"]:
            self.assertEqual(ref.is_package_name(name), expected, name)

    def test_targets(self):
        for t, expected in VECTORS["targets"]:
            self.assertEqual(ref.is_target(t), expected, t)


class JavaMatches(unittest.TestCase):
    """The Java class can't be compiled here; at least its keys and shape must match."""

    def test_keys(self):
        self.assertIn('SETTING = "anon_share_targets"', JAVA)
        self.assertIn('SETTING_HIDE_PEOPLE = "anon_share_hide_people"', JAVA)
        self.assertIn('ANY_APP = "*"', JAVA)

    def test_settings_hook_uses_same_keys(self):
        apply = (ROOT / "scripts/apply.py").read_text()
        for key in re.findall(r'SETTING(?:_HIDE_PEOPLE)? = "([a-z_]+)"', JAVA):
            self.assertIn(f'"{key}"', apply)

    def test_balanced_braces(self):
        # One pass, so quotes inside comments and slashes inside strings don't confuse it.
        token = re.compile(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
        code = token.sub(" ", JAVA)
        self.assertEqual(code.count("{"), code.count("}"))
        self.assertEqual(code.count("("), code.count(")"))


if __name__ == "__main__":
    unittest.main()
