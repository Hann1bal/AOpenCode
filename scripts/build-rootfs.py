#!/usr/bin/env python3
"""Build the bundled Alpine userland that ships inside the APK.

The agent on the device has no package manager and, without this, no git, no
bash, no python. Alpine publishes musl-built packages that run under the same
loader we already ship for opencode, so we can bundle a real userland.

What this does:

  1. Resolves a seed package list against the Alpine APKINDEX, transitively,
     including the `so:libfoo.so.N=...` shared-library virtuals that make up
     most of the dependency graph.
  2. Downloads and merges every package into one rootfs tree.
  3. Writes it as a zip, because the app has no `tar` to unpack with.

Symlinks cannot survive that zip: `java.util.zip.ZipInputStream` has no way to
create one. They are listed in a sidecar manifest inside the same archive, and
the app recreates them with `Os.symlink()`, which works in app-private storage.
Absolute link targets are rewritten to be relative to the rootfs root, since
`/bin/busybox` will not exist at that path on Android.

Usage:
    python3 scripts/build-rootfs.py --out app/src/main/assets/runtime/env.zip
"""

import argparse
import os
import posixpath
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

# What the agent actually needs. Everything else comes in as a dependency.
#
#   busybox  coreutils/applets and, importantly, `env` for shebangs
#   bash     scripts that are not POSIX sh
#   git      without it every project is non-VCS
#   curl     transfers from the shell
#   wget     some tools call wget rather than curl
#   python3  scripts the agent writes for itself
#   jq       JSON from the shell
#   openssh-client  git@ clones
#   GNU grep/sed/gawk/diffutils  busybox's versions differ in ways scripts notice
#   less     pagers
#   tar/gzip/xz/zip/unzip  archive handling
SEEDS = [
    "busybox",
    # Alpine's busybox does not install /usr/bin/env; coreutils does, and every
    # `#!/usr/bin/env bash` script in the tree needs it. Also the GNU coreutils
    # proper, which is what scripts expect.
    "coreutils",
    "bash",
    "git",
    "git-lfs",
    # git's interactive add (git add -p) is a perl script in this version.
    "perl",
    "curl",
    "wget",
    "python3",
    "jq",
    "openssh-client",
    "grep",
    "sed",
    "gawk",
    "diffutils",
    # Alpine keeps find and xargs here, not in coreutils.
    "findutils",
    "tar",
    "gzip",
    "xz",
    "zip",
    "unzip",
    "less",
    "procps",
    "ca-certificates",
    "tzdata",
    # Runtimes, so the agent can actually run what it writes.
    "nodejs",
    "npm",
    "py3-pip",
    "py3-setuptools",
    "py3-wheel",
    # Search, inspection and transfer tools that make a shell usable.
    "fd",
    "sqlite",
    "file",
    "tree",
    "yq-go",
    "moreutils",
    "parallel",
    "rsync",
    "bind-tools",
    "openssl",
]

# Development, documentation and cache payloads: never useful at runtime.
EXCLUDE_PARTS = (
    "usr/share/man",
    "usr/share/doc",
    "usr/share/info",
    "usr/share/aclocal",
    "usr/include",
    "var/cache",
    "etc/apk",
)
EXCLUDE_SUFFIXES = (".build-info", ".a")

# `pkg>=1.2`, `so:libfoo.so.1=1.2.3`, `~`, `<=`
CONSTRAINT_RE = re.compile(r"^([^=<>~]+?)\s*(?:[=<>~].*)?$")

# Multi-call binaries take their applet name from argv[0] but also accept it as
# the first argument (`coreutils ls -l`). Everything else that is aliased has to
# have argv[0] be its real name, which a symlink through the loader cannot give
# it, so those aliases are materialised as copies instead.
MULTICALL = {"busybox", "coreutils"}

# Directories whose entries are commands. Library soname links live elsewhere and
# are left as symlinks on purpose.
BIN_DIRS = ("bin/", "usr/bin/", "sbin/", "usr/sbin/")


def norm_dep(token: str) -> str:
    """Strip the version constraint so `so:libfoo.so.1=1.2` keys as `so:libfoo.so.1`."""
    match = CONSTRAINT_RE.match(token.strip())
    return match.group(1) if match else token.strip()


def rel_member(name: str) -> str:
    """A tar member as a root-relative POSIX path, or '' when it is unsafe.

    APK tars prefix members with `./`. Absolute members are rewritten relative to
    the rootfs root, which is what makes Alpine's `bin/sh -> /bin/busybox`
    resolve inside our tree instead of to the host's `/bin`.
    """
    parts = []
    for part in name.replace(os.sep, "/").split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            return ""
        parts.append(part)
    return "/".join(parts)


def norm_rel(path: str) -> str:
    """normpath for a root-relative path; '' if it would escape the root."""
    normalized = posixpath.normpath(path.replace(os.sep, "/"))
    if normalized.startswith("..") or normalized == "/":
        return ""
    return normalized.lstrip("/")


def parse_apkindex(path: Path):
    """Parse an Alpine APKINDEX into package dicts plus a provides index."""
    packages = {}
    providers = {}
    stanza = {}
    for raw in path.read_text(errors="replace").splitlines() + [""]:
        if not raw.strip():
            name = stanza.get("P")
            if name:
                pkg = {
                    "name": name,
                    "version": stanza.get("V", ""),
                    "depends": stanza.get("D", "").split(),
                    "provides": stanza.get("p", "").split(),
                    "repo": stanza.get("_repo", "main"),
                }
                packages[name] = pkg
                providers.setdefault(name, []).append(name)
                for token in pkg["provides"]:
                    providers.setdefault(norm_dep(token), []).append(name)
            stanza = {}
            continue
        if len(raw) > 1 and raw[1] == ":":
            stanza[raw[0]] = raw[2:]
    return packages, providers


def pick_provider(name: str, providers, packages, selected, blocked) -> str | None:
    """Choose which package satisfies `name`.

    Prefers something already selected, then the smallest non-development
    candidate, so a `so:libfoo.so.1` edge does not drag in `foo-dev`.
    """
    candidates = [c for c in providers.get(name, []) if c not in blocked]
    if not candidates:
        return None
    for cand in candidates:
        if cand in selected:
            return cand

    def rank(cand: str):
        penalty = 0
        for suffix in ("-dev", "-static", "-dbg", "-doc", "-static-dbgsym"):
            if cand.endswith(suffix):
                penalty += 100
        if cand.startswith("so:"):
            penalty += 10
        return (penalty, cand)

    return sorted(candidates, key=rank)[0]


def resolve(seeds, packages, providers):
    """Breadth-first closure over the dependency graph.

    `!pkg` is Alpine's negative dependency: `openssh-client-default` declares
    `!openssh-client-krb5` so the two variants cannot coexist. Honouring it is
    what keeps one `ssh` on the PATH.
    """
    selected = []
    seen = set()
    blocked = set()
    missing = []
    queue = list(seeds)
    while queue:
        token = queue.pop(0)
        key = norm_dep(token)
        if key.startswith("!"):
            blocked.add(key[1:])
            continue
        provider = pick_provider(key, providers, packages, seen, blocked)
        if provider is None:
            # `cmd:` and `cfg:` virtuals are advisory; a real `so:` miss is not.
            if key.startswith(("cmd:", "cfg:")):
                continue
            missing.append(key)
            continue
        if provider in seen:
            continue
        seen.add(provider)
        selected.append(provider)
        queue.extend(packages[provider]["depends"])
    return selected, missing


def download(url: str, dest: Path):
    with urllib.request.urlopen(url, timeout=180) as response, open(dest, "wb") as out:
        shutil.copyfileobj(response, out)


def excluded(rel: str) -> bool:
    if any(rel == part or rel.startswith(part + "/") for part in EXCLUDE_PARTS):
        return True
    return rel.endswith(EXCLUDE_SUFFIXES)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="destination zip")
    parser.add_argument("--alpine", default="v3.20")
    parser.add_argument("--mirror", default="https://dl-cdn.alpinelinux.org/alpine")
    parser.add_argument("--arch", default="aarch64")
    parser.add_argument("--seeds", help="comma-separated list replacing the default seeds")
    parser.add_argument(
        "--no-symlinks",
        action="store_true",
        help="compute the link manifest without creating links (Windows dev boxes, where os.symlink needs privileges)",
    )
    args = parser.parse_args()

    seeds = [s.strip() for s in args.seeds.split(",")] if args.seeds else list(SEEDS)
    out_path = Path(args.out).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    work = Path(tempfile.mkdtemp(prefix="rootfs-"))
    try:
        indexes = {}
        merged = {}
        merged_providers = {}
        for repo in ("main", "community"):
            index_path = work / f"APKINDEX-{repo}"
            download(f"{args.mirror}/{args.alpine}/{repo}/{args.arch}/APKINDEX.tar.gz", index_path)
            with tarfile.open(index_path) as tar:
                tar.extract("APKINDEX", path=work, filter="data")
            os.replace(work / "APKINDEX", index_path)
            packages, providers = parse_apkindex(index_path)
            for pkg in packages.values():
                pkg["repo"] = repo
            indexes[repo] = packages
            for key, names in providers.items():
                merged_providers.setdefault(key, [])
                merged_providers[key].extend(names)
            merged.update(packages)
            print(f"    {repo}: {len(packages)} packages")

        # A seed may be a real package or a virtual provide: `openssh-client` is
        # not a package name in Alpine, it is what `openssh-client-default`
        # declares in its `p:` field.
        unknown = [s for s in seeds if s not in merged and s not in merged_providers]
        if unknown:
            print(f"ERROR: unknown seed packages: {', '.join(unknown)}", file=sys.stderr)
            return 2

        selected, missing = resolve(seeds, merged, merged_providers)
        if missing:
            print(f"WARNING: unresolved dependencies (skipped): {', '.join(sorted(set(missing)))}")
        print(f"==> Resolved {len(selected)} packages for {len(seeds)} seeds")

        rootfs = work / "rootfs"
        rootfs.mkdir()
        total_bytes = 0
        # Links are collected rather than created as they are seen: `bin/sh` may
        # arrive in the first package while the `busybox` it points at comes in
        # the last one. Files first, links once the whole tree exists.
        pending_links = []

        def add_link(kind, link_rel, raw_target, link_dir_rel):
            if kind == "sym":
                target_rel = (
                    rel_member(raw_target)
                    if raw_target.startswith("/")
                    else norm_rel(posixpath.join(link_dir_rel, raw_target))
                )
            else:
                target_rel = rel_member(raw_target)
            if not target_rel or excluded(target_rel):
                return
            pending_links.append((kind, link_rel, target_rel))

        for name in sorted(selected):
            pkg = merged[name]
            apk = work / f"{name}.apk"
            url = f"{args.mirror}/{args.alpine}/{pkg['repo']}/{args.arch}/{name}-{pkg['version']}.apk"
            download(url, apk)
            total_bytes += apk.stat().st_size
            with tarfile.open(apk) as tar:
                for member in tar:
                    rel = rel_member(member.name)
                    if not rel or excluded(rel):
                        continue
                    dest = rootfs / rel
                    if member.isdir():
                        dest.mkdir(parents=True, exist_ok=True)
                    elif member.isfile():
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        with tar.extractfile(member) as src, open(dest, "wb") as out:
                            shutil.copyfileobj(src, out)
                        os.chmod(dest, member.mode & 0o777 or 0o644)
                    elif member.issym():
                        add_link("sym", rel, member.linkname, posixpath.dirname(rel))
                    elif member.islnk():
                        add_link("hard", rel, member.linkname, posixpath.dirname(rel))
            print(f"    + {name}-{pkg['version']}")

        # Rounds, because a link may point at another link
        # (`libfoo.so -> libfoo.so.1 -> libfoo.so.1.0`) and the intermediate
        # has to exist before the outer one resolves.
        links = []
        made = set()
        pending = pending_links
        for _ in range(6):
            if not pending:
                break
            remaining = []
            progressed = False
            for kind, link_rel, target_rel in pending:
                target = rootfs / target_rel
                if not target.exists() and target_rel not in made:
                    remaining.append((kind, link_rel, target_rel))
                    continue
                link = rootfs / link_rel
                link.parent.mkdir(parents=True, exist_ok=True)
                if link.is_symlink() or link.exists():
                    link.unlink()
                if not args.no_symlinks:
                    if kind == "sym":
                        os.symlink(target, link)
                    else:
                        try:
                            os.link(target, link)
                        except OSError:
                            shutil.copyfile(target, link)
                links.append((link_rel, target_rel))
                made.add(link_rel)
                progressed = True
            pending = remaining
            if not progressed:
                break
        skipped_links = pending

        # Aliases of ordinary binaries become real copies, not links: `gunzip`
        # decides what it is by argv[0], and a symlink through the loader hands
        # it the target's name instead.
        link_map = {l: t for k, l, t in pending_links if k == "sym"}

        def real_target(rel: str, depth: int = 0) -> str:
            """Follow a chain of planned links to the file at the end of it."""
            nxt = link_map.get(rel)
            if nxt is None or depth > 8:
                return rel
            return real_target(nxt, depth + 1)

        copies = 0
        copied = set()
        for kind, link_rel, target_rel in pending_links:
            if kind != "sym":
                continue
            # Only command aliases. Library soname links (`libfoo.so.1 ->
            # libfoo.so.1.2.3`) stay symlinks: the loader resolves them by name
            # and copying every one would triple the tree.
            if not link_rel.startswith(BIN_DIRS):
                continue
            if posixpath.basename(target_rel) in MULTICALL:
                continue
            if posixpath.basename(link_rel) == posixpath.basename(target_rel):
                continue
            target = rootfs / real_target(target_rel)
            if not target.is_file():
                continue
            dst = rootfs / link_rel
            if dst.is_symlink() or dst.is_file():
                dst.unlink()
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(target, dst)
            copies += 1
            copied.add(link_rel)

        # The app recreates links from the manifest, so anything we just turned
        # into a copy has to leave it — otherwise the link would come back over
        # the copy and undo the whole point.
        links = [(a, b) for a, b in links if a not in copied]

        # Alpine assembles the CA bundle with an apk trigger that never runs
        # here, so the tree only carries the individual root certificates.
        # Concatenate them into the bundle every TLS client looks for.
        ca_dir = rootfs / "usr/share/ca-certificates/mozilla"
        if ca_dir.is_dir():
            roots = sorted(ca_dir.glob("*.crt"))
            bundle = rootfs / "etc/ssl/certs/ca-certificates.crt"
            bundle.parent.mkdir(parents=True, exist_ok=True)
            with open(bundle, "wb") as out:
                for crt in roots:
                    out.write(crt.read_bytes())
            shutil.copyfile(bundle, rootfs / "etc/ssl/cert.pem")
            print(f"    CA bundle: {len(roots)} roots -> etc/ssl/certs/ca-certificates.crt")

        files = [
            str(p.relative_to(rootfs)).replace(os.sep, "/")
            for p in sorted(rootfs.rglob("*"))
            if p.is_file() and not p.is_symlink() and not excluded(
                str(p.relative_to(rootfs)).replace(os.sep, "/")
            )
        ]

        manifest = "".join(f"{link}\t{target}\n" for link, target in links)
        with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
            # First entry so the app can read it without holding the whole archive.
            zf.writestr("__SYMLINKS__.txt", manifest)
            for rel in files:
                zf.write(rootfs / rel, rel)

        for rel, target in skipped_links:
            print(f"    note: dropped dangling symlink {rel} -> {target}")

        print(
            f"==> {out_path} — {len(files)} files, {len(links)} symlinks, "
            f"{copies} alias copies, {out_path.stat().st_size / 1e6:.1f} MB "
            f"(downloaded {total_bytes / 1e6:.1f} MB)"
        )
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
