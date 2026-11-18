#!/usr/bin/env python3
"""Look for private details before a commit: home paths, user names, e-mail addresses (other
than GitHub noreply ones), IP and MAC addresses, keys and tokens.

    scripts/check-private.py [files...]     (default: every file git would commit)

Exits 1 and lists each hit. Add words of your own (a name, a hostname, a Wi-Fi name) one a
line to ~/.config/anonphene/private-words; that file is never part of the repo.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

PATTERNS = {
    "home path": re.compile(r"/home/[a-z_][a-z0-9_-]*/|/Users/[A-Za-z][\w.-]*/"),
    "e-mail": re.compile(r"[A-Za-z0-9._%+-]+@(?!users\.noreply\.github\.com)[A-Za-z0-9-]+\.[A-Za-z.]{2,}"),
    "IPv4": re.compile(r"\b(?!(?:127|0|10\.0\.2)\.)(?:\d{1,3}\.){3}\d{1,3}\b"),
    "MAC": re.compile(r"\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b"),
    "private key": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "token": re.compile(r"\b(?:ghp|gho|github_pat|glpat|xox[abp])_[A-Za-z0-9_]{20,}"),
    "WireGuard": re.compile(r"^\s*PrivateKey\s*=", re.M),
}
# Version numbers and similar that look like addresses but aren't.
ALLOW_IP = {"0.0.0.0", "255.255.255.255"}


def files_to_check(args):
    if args:
        return [Path(a) for a in args]
    root = Path(__file__).resolve().parent.parent
    try:
        out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard"], cwd=root,
                             check=True, capture_output=True, text=True).stdout
        return [root / f for f in out.splitlines() if f]
    except (OSError, subprocess.CalledProcessError):
        return [p for p in root.rglob("*") if p.is_file() and ".git" not in p.parts]


def own_words():
    p = Path(os.path.expanduser("~/.config/anonphene/private-words"))
    if not p.is_file():
        return []
    return [w.strip() for w in p.read_text().splitlines() if w.strip() and not w.startswith("#")]


def check(paths, words):
    hits = []
    for p in paths:
        try:
            text = p.read_text(errors="strict")
        except (UnicodeDecodeError, OSError):
            continue  # binary; images are checked by eye
        for name, rx in PATTERNS.items():
            for m in rx.finditer(text):
                if name == "IPv4" and (m.group(0) in ALLOW_IP or any(int(x) > 255 for x in m.group(0).split("."))):
                    continue
                line = text.count("\n", 0, m.start()) + 1
                hits.append(f"{p}:{line}: {name}")
        low = text.lower()
        for w in words:
            if w.lower() in low:
                hits.append(f"{p}: one of your private words")
    return hits


def main(argv):
    hits = check(files_to_check(argv[1:]), own_words())
    for h in hits:
        print(h)
    if hits:
        print(f"{len(hits)} possible private detail(s). Fix them before committing.", file=sys.stderr)
        return 1
    print("No private details found.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
