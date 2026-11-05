r"""Catch invalid Kotlin string escapes without waiting for CI.

This project generates shell text from Kotlin (wrappers, the rehash loop), and
shell is full of characters that are harmless in a shell string but not in a
Kotlin one. `\#` is the classic: needed nowhere, rejected by the compiler, and
easy to write by reflex when escaping `${...}`.

Valid Kotlin escapes: \t \b \n \r \f \' \" \\ \$ \uXXXX

Usage:
    python scripts/check-kotlin-escapes.py
"""
import pathlib
import re
import sys

VALID = set("tbnrf'\"\\$u")
ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java"


def main() -> int:
    bad = []
    for f in sorted(ROOT.rglob("*.kt")):
        for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            for m in re.finditer(r"\\.", line):
                if m.group(0)[1] not in VALID:
                    bad.append((f, i, m.start() + 1, m.group(0), line.strip()))

    if not bad:
        print("no invalid escapes")
        return 0
    for f, ln, col, seq, ctx in bad:
        rel = f.relative_to(ROOT.parent.parent.parent.parent)
        print(f"{rel}:{ln}:{col}  {seq}   | {ctx[:100]}", file=sys.stderr)
    print(f"{len(bad)} invalid escape(s)", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
