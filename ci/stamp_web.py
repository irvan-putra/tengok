#!/usr/bin/env python3
"""Stamp web asset URLs in docs/index.html with content hashes.

GitHub Pages serves assets with a 10-minute cache, so a changed app.js can
reach returning visitors late. Appending ?v=<md5[:8]> of each local asset
makes every content change a new URL. This script rewrites the stamps; the
qualify workflow runs it with --check so a forgotten stamp fails CI.

Usage:
    python3 ci/stamp_web.py           # rewrite stamps in place
    python3 ci/stamp_web.py --check   # exit 1 if any stamp is stale
"""
import hashlib
import re
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parent.parent / "docs"
PAGES = [DOCS / "index.html", DOCS / "selftest.html"]

# Local asset references that get a stamp: src="js/x.js" or href="style.css",
# with or without an existing ?v= stamp. Remote URLs are never touched.
ATTR = re.compile(r'((?:src|href)=")((?:js/)?[A-Za-z0-9._-]+\.(?:js|css))(\?v=[0-9a-f]{8})?(")')


def stamp_for(rel):
    digest = hashlib.md5((DOCS / rel).read_bytes()).hexdigest()[:8]
    return "?v=" + digest


def restamp(text, check):
    stale = []

    def repl(m):
        pre, rel, _old, post = m.groups()
        if not (DOCS / rel).is_file():
            return m.group(0)
        new = stamp_for(rel)
        if m.group(3) != new:
            stale.append(rel)
            if check:
                return m.group(0)
            return pre + rel + new + post
        return m.group(0)

    out = ATTR.sub(repl, text)
    return out, stale


def main():
    check = "--check" in sys.argv
    all_stale = []
    for page in PAGES:
        out, stale = restamp(page.read_text(), check)
        all_stale += [page.name + ": " + x for x in stale]
        if stale and not check:
            page.write_text(out)
    if check:
        if all_stale:
            print("stale asset stamps: " + ", ".join(all_stale))
            print("run: python3 ci/stamp_web.py")
            return 1
        print("asset stamps are current")
        return 0
    print("stamped: " + ", ".join(all_stale) if all_stale else "already current")
    return 0


if __name__ == "__main__":
    sys.exit(main())
