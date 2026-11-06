"""Extract SHIM_JS from WebViewShim.kt and syntax-check it with node.

The shim is the only JS in this project and it is embedded in a Kotlin string,
so nothing in the build verifies it parses. A syntax error there does not fail
the build -- it silently un-patches the page, which then looks like a WebView
bug rather than a typo.
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path("app/src/main/java/dev/opencode/mobile/WebViewShim.kt")

text = SRC.read_text(encoding="utf-8")
m = re.search(r'private const val SHIM_JS = """(.*?)"""', text, re.DOTALL)
if not m:
    print("could not locate SHIM_JS")
    sys.exit(1)

js = m.group(1)
print(f"extracted {len(js)} bytes of shim JS")

with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as f:
    f.write(js)
    path = f.name

r = subprocess.run(["node", "--check", path], capture_output=True, text=True)
if r.returncode == 0:
    print("node --check: OK")
else:
    print("node --check: FAILED")
    print(r.stderr[:2000])
    sys.exit(1)

# The error hooks must actually be present, otherwise the extraction silently
# dropped them and the check passed on the wrong content.
for needle in ("window.addEventListener('error'", "unhandledrejection", "__ocShimInstalled"):
    print(f"  contains {needle!r}: {needle in js}")
