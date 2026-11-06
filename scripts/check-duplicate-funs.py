#!/usr/bin/env python3
"""Fail on a Kotlin file that declares the same function name and arity twice.

There is no local Kotlin compiler here, so CI is the only type gate, and one
of the cheapest ways to lose a build cycle is a duplicate declaration.
This happened with restartBackend(): a new private helper was added next to
an identical one that already existed further down the same file, which is
a hard "conflicting overloads" error that no escape or brace check can see.

Same-file, same-name, same-arity collisions are reported. Overloads that
genuinely differ in parameter count are allowed, since those are legal
Kotlin; differing only in parameter *types* would also be legal, but this
codebase has no such pair, so flagging same-arity is worth the noise.
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

# MULTILINE is required: without it `^` only anchors at the very start of the
# file, which silently made an earlier version of this check match nothing.
FUN_RE = re.compile(
    r"^[ \t]*(?:(?:private|internal|public|protected|open|override|inline|suspend|companion)[ \t]+)*"
    r"fun[ \t]+(?:<[^>]*>[ \t]*)?(\w+)[ \t]*\(",
    re.MULTILINE,
)

SRC = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java"


def params_at(call_open: int, text: str) -> int:
    """Number of top-level parameters in the parenthesised list starting at `call_open`."""
    depth = 0
    buf = ""
    out: list[str] = []
    i = call_open
    while i < len(text):
        ch = text[i]
        if ch in "([{":
            depth += 1
            if depth == 1:
                buf = ""
                i += 1
                continue
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                if buf.strip():
                    out.append(buf)
                break
        elif ch == "," and depth == 1:
            out.append(buf)
            buf = ""
            i += 1
            continue
        if depth >= 1:
            buf += ch
        i += 1
    return len([p for p in out if p.strip()])


def main() -> int:
    dupes: list[str] = []
    for path in sorted(SRC.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="replace")
        seen: dict[tuple[str, int], list[int]] = defaultdict(list)
        for m in FUN_RE.finditer(text):
            open_paren = text.index("(", m.end() - 1)
            key = (m.group(1), params_at(open_paren, text))
            line_no = text.count("\n", 0, m.start()) + 1
            seen[key].append(line_no)
        for (name, n), lines in sorted(seen.items()):
            if len(lines) > 1:
                dupes.append(
                    f"{path.relative_to(SRC.parent)}: "
                    f"{name}/{n} declared at lines {', '.join(map(str, lines))}"
                )

    if dupes:
        print("duplicate function declarations (conflicting overloads):")
        for d in dupes:
            print(f"  {d}")
        return 1
    print("no duplicate function declarations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
