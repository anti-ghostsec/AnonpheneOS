"""Reference copy of vendor/anonphene/lib/sharelist ShareList.java, in Python.

It exists so the share-list format can be checked on a machine without an Android tree:
tests/test_sharelist.py runs it against share_list_vectors.json, the same cases the Java
class must pass. Keep the two in step.
"""

ANY_APP = "*"


def _part_ok(p):
    if not p or p[0].isdigit():
        return False
    return all(c.isascii() and (c.isalnum() or c == "_") for c in p)


def is_package_name(s):
    if not s or len(s) > 255:
        return False
    parts = s.split(".")
    return len(parts) >= 2 and all(_part_ok(p) for p in parts)


def is_target(s):
    if s is None:
        return False
    if "/" not in s:
        return is_package_name(s)
    pkg, _, cls = s.partition("/")
    if "/" in cls:
        return False
    if cls.startswith("."):
        cls = "x" + cls
    if not cls or cls.endswith("."):
        return False
    if not all(c.isascii() and (c.isalnum() or c in "_.$") for c in cls):
        return False
    return is_package_name(pkg)


class ShareList:
    def __init__(self, rules=None):
        self.rules = dict(rules or {})

    @classmethod
    def parse(cls, text):
        rules = {}
        if text is None:
            return cls(rules)
        for raw in text.split("\n"):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            eq = line.find("=")
            if eq <= 0:
                continue
            source = line[:eq].strip()
            if source != ANY_APP and not is_package_name(source):
                continue
            targets, ok = [], True
            for t in line[eq + 1:].split(","):
                t = t.strip()
                if not t:
                    continue
                if not is_target(t):
                    ok = False
                    break
                if t not in targets:
                    targets.append(t)
            if ok:
                rules[source] = targets
        return cls(rules)

    def rule_for(self, source):
        if source is not None and source in self.rules:
            return self.rules[source]
        return self.rules.get(ANY_APP)

    def limits(self, source):
        return self.rule_for(source) is not None

    def allows(self, source, pkg, cls):
        rule = self.rule_for(source)
        if rule is None:
            return True
        if pkg in rule:
            return True
        if cls is None:
            return False
        if f"{pkg}/{cls}" in rule:
            return True
        prefix = pkg + "."
        return cls.startswith(prefix) and f"{pkg}/{cls[len(pkg):]}" in rule

    def serialize(self):
        out = []
        keys = sorted(self.rules)
        if ANY_APP in self.rules:
            out.append(f"{ANY_APP}={','.join(self.rules[ANY_APP])}\n")
        for k in keys:
            if k != ANY_APP:
                out.append(f"{k}={','.join(self.rules[k])}\n")
        return "".join(out)
