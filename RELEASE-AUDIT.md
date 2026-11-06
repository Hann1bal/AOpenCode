# AOpenCode — release readiness audit

Scope: publishing the APK outside the two HUAWEI devices it was developed on.
Everything marked **verified** was measured from the tree or a real device.
Everything marked **inferred** is a reasoned risk that still needs a test.

---

## Verdict

The app works. It does not currently qualify for public distribution, and two
of the blockers are not code problems but consequences of the architecture:

1. **Google Play is not reachable with this design.** `targetSdk = 28` is
   required for `exec()` of the bundled runtime, and no current Play policy
   accepts a target that old. Sideloading, a self-hosted update channel, or an
   enterprise/MDM channel is the realistic path.
2. **The signing key is public.** `keystore/aopencode.p12` and its password
   are both in a public repo, which means anyone can ship an update that
   installs over existing installs and inherits their data.

Neither is urgent while the audience is a handful of people on a tailnet.
Both become serious the moment the link is posted anywhere public.

---

## P0 — blocks publication

### 1. Public signing key (verified)

`keystore/aopencode.p12` is committed, and `app/build.gradle.kts:100-102`
falls back to the literal password `aopencode`. `scripts/make-keystore.py`
documents this as deliberate: *"This key is not a secret and is not meant to
be."*

That reasoning holds only against the alternative it was written to avoid
(a different key per build, hence `INSTALL_FAILED_UPDATE_INCOMPATIBLE`). It
does not hold against an attacker:

- Package identity on Android **is** the signing key. Anyone holding this key
  can publish `dev.opencode.mobile`.
- A signature-compatible APK installs **over** an existing install and keeps
  its data directory. That includes `SharedPreferences` with `backendToken`,
  the 240 MB runtime binary and its `.opencode-sha`, session state, and the
  pairing store.
- So a hostile APK does not need to break in. It is installed by the update
  mechanism as a legitimate upgrade, and reads what the previous version
  left behind.

Fix, in order:

- Generate a fresh key outside the repo; store base64 in a CI secret
  (`KEYSTORE_BASE64` is already wired).
- Delete `keystore/aopencode.p12` from the repo **and from history**
  (`git filter-repo`), or make the repo private and re-publish the tree.
- Remove the `?: "aopencode"` fallbacks so a missing secret fails the build
  instead of silently signing with a known key.
- Do this **before** wide distribution. Rotating later means existing installs
  cannot update in place and every user has to uninstall first.

### 2. `targetSdk = 28` rules out Play (verified)

`app/build.gradle.kts:11,55-61` pins target 28 because apps targeting API 29+
cannot `execve()` files inside their own home directory, which is how the
musl runtime is launched. Play requires new apps and updates to target a
recent API level (its policy tracks the latest Android release; anything near
28 has been inadmissible for years).

Consequences to accept explicitly:

- No Play listing. Distribution is sideload + a self-hosted update feed.
- Every non-Play install needs `REQUEST_INSTALL_PACKAGES` and the user
  enabling "install unknown apps", which is itself a trust hurdle.
- Raising target later requires a different exec strategy (native `lib*.so`
  loader in `nativeLibraryDir`, or a bundled linker), not a number change.

Do not attempt to raise this as a quick fix. It is a separate project.

### 3. `versionCode = 1` forever (verified)

`app/build.gradle.kts:64-67` hardcodes versionCode 1 with the comment that a
bumped code "would force an uninstall first". That is true only for a debug
key. With a stable release key, a strictly increasing versionCode is exactly
what makes in-place upgrades work.

Shipping every release as code 1 means:

- Package managers that compare versions see no upgrade. Some ROMs refuse or
  no-op the install; `pm install -r` behaves inconsistently across vendors.
- Any store or update feed that requires monotonic versions rejects it outright.
- There is no way to express "this is newer" to the OS.

Fix: derive versionCode from the tag (`1.0.44` → `10044`, or a monotonic CI
counter) and keep `versionName` human-readable.

### 4. arm64-only coverage (verified)

`abiFilters += "arm64-v8a"` because the backend binary is `linux-arm64-musl`.

Will not install on:

- 32-bit ARM devices (`armeabi-v7a`) — still a real population on Android 8-10
  budget phones and older tablets.
- `x86_64` — ChromeOS Android runtime, Android x86, and **the default Android
  emulator AVD**. This blocks testing, which matters more than the users.

Phones sold since ~2017 are overwhelmingly arm64, so the user-facing gap is
small. The testing gap is not: without an x86_64 build there is no emulator
path at all, so every regression has to be found on physical hardware.

Decide deliberately: either build an `x86_64` runtime for emulator/ChromeOS,
or state "arm64 Android phones only" in the release notes and drop the
emulator from the plan.

---

## P1 — fix before wide distribution

### 5. Remote diagnostics switch (verified)

The switch is off by default and the token is per-install random — both
correct. What remains is that the switch turns the device into a remote shell:

- `BackendManager` binds `0.0.0.0` and `DiagServer` listens on 4180.
- `/diag/exec` runs arbitrary commands as the app uid.
- The token is rendered in a selectable monospace `TextView`, i.e. shoulder-
  surfable and easy to paste into the wrong place.

Hardening, cheapest first:

- Auto-disable after a fixed lifetime (e.g. 2 hours) with a visible countdown.
  A switch left on by accident is the realistic failure, not a brute-force.
- Show which interfaces are listening when it is on, and warn explicitly if
  the device is on a network that is not a known tailnet.
- Prefer loopback-only `DiagServer` plus `tailscale serve`/SSH tunnel rather
  than a public bind, so the attack surface is the tunnel, not the port.
- Log every `exec` with a timestamp so a compromise is at least auditable.

### 6. Cleartext permitted globally (verified)

`network_security_config.xml` sets `cleartextTrafficPermitted="true"` for all
hosts. The justification in the file is sound for the developer's setup
(user-supplied endpoints, LAN/Tailscale IPs, no domain matching for CIDRs),
but for a general audience it means a network attacker can read the API key
and every prompt whenever the user typed an `http://` base URL.

Minimum viable mitigation:

- Detect `http://` in the configured base URL and show a persistent
  "unencrypted connection" indicator on the setup screen and in the app.
- Offer to save only `https://` endpoints by default, requiring an explicit
  override for cleartext.
- Document that Tailscale is the recommended transport for non-TLS endpoints.

### 7. `REQUEST_INSTALL_PACKAGES` declared but unused (verified)

The permission is in the manifest, but the update notification opens a
browser rather than installing. Two problems:

- Declaring a dangerous permission with no code path that uses it is a
  rejection reason in any review process and a red flag to a security-minded
  user.
- The update flow is worse than the permission implies: the user is sent to a
  browser to fetch an APK manually.

Implement the in-app install (`DownloadManager` + `FileProvider` +
`ACTION_VIEW` on the downloaded APK), or remove the permission.

### 8. First-run download is 240 MB (verified)

Consent is asked before downloading, resume works via `Range`, and sha256 is
verified — all correct. Remaining gaps for a general audience:

- Default to Wi-Fi-only and make the size explicit in the consent dialog.
  On metered roaming this is a bill, not an inconvenience.
- GitHub Releases is the only source. It rate-limits, and a release
  deletion or a renamed tag bricks first-run for everyone. Publish a mirror,
  or at least a second URL in the manifest with the same sha256.
- `env.zip` (75.6 MB) ships inside the APK, so first run needs the network
  only for the binary. That is the right split; keep it.

### 9. WebView engine variance (verified as a risk class)

The service-worker incident (v1.0.38 → v1.0.42) established that vendors
diverge in ways that break the SPA:

- HUAWEI ships `com.huawei.webview` and stores data under
  `app_hws_webview/Default/`, not `app_webview`.
- The SPA registers a service worker that then owns every subsequent
  navigation, so a stale worker survives app updates.

This is not a solved bug, it is a standing hazard class. Before claiming
"works everywhere":

- Add a minimum WebView version check via
  `WebView.getCurrentWebViewPackage()` (already used for display in
  `BackendManager.webViewVersion():430`) and refuse with a clear message
  below the floor, rather than showing a blank screen.
- Test on each engine: Google WebView (Play-updated), HUAWEI, Samsung
  Internet-backed, and a Tencent X5 engine if Chinese ROMs are in scope.
- Keep the pre-WebView purge and the `register` neuter — they are load-bearing
  on at least one vendor and harmless elsewhere.

### 10. Vendor battery killing (inferred, high likelihood)

Huawei, Xiaomi, Oppo, Vivo and Samsung ROMs aggressively kill background
services and freeze sockets. `FOREGROUND_SERVICE_SPECIAL_USE` +
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are declared and a exemption button
exists, but on MIUI/ColorOS the standard exemption dialog is not enough —
those ROMs have a separate per-app autostart setting outside the AOSP API.

Needed: a per-vendor instructions screen keyed off `Build.MANUFACTURER`, and
a test pass on at least Xiaomi and Samsung.

---

## P2 — quality bar

### 11. Accessibility

- `11sp` text with `alpha 0.55`/`0.6` (engine info, workspace info, diag
  body) fails WCAG contrast at any size. Use a real muted color token with a
  measured contrast ratio ≥ 4.5:1 rather than alpha.
- The diagnostics `SwitchMaterial` needs a `contentDescription`-equivalent
  state announcement; TalkBack should read state on toggle.
- The token is `textIsSelectable` monospace, which is right, but TalkBack
  reads a 64-char hex string character-by-character. Provide a "copy token"
  button instead of relying on selection.
- Verify touch targets are ≥ 48dp; the text buttons in the peers screen are
  dense.

### 12. Hardcoded user-facing strings

`setDiagnosticMode` in `PeersActivity.kt` passes literal English into
`Toast.makeText` instead of `R.string`. Every user-visible string should go
through `strings.xml` or it cannot be translated.

### 13. No LICENSE file (verified)

There is no `LICENSE` at the repo root. The app bundles a fork of opencode,
plus ripgrep and a musl-based rootfs. Before publishing:

- Identify each component's license and its obligations (attribution,
  share-alike, non-commercial clauses).
- Ship the notices inside the app, not only in the repo.
- Check the upstream project's stance on a redistributed fork under a
  different name ("AOpenCode") before distributing widely.

### 14. Privacy policy

Any public distribution channel, including a GitHub release page, warrants a
privacy policy. This app is unusually easy to write honestly — no telemetry,
no third-party SDKs, all data local or user-configured — which is a selling
point, not a burden.

### 15. R8 / minification (verified enabled, not verified safe)

`isMinifyEnabled = true` and `isShrinkResources = true` on release. Reflection
and JS-bridge surfaces are the usual casualties. Confirm on a release build
specifically that the JS interface, view binding, and any `Class.forName`
paths survive — a debug build passing does not imply a release build does.

---

## What is already right

Worth not regressing:

- `allowBackup="false"` — the backend token and session data do not leave the
  device via adb backup or cloud backup.
- Runtime integrity: sha256 is the version key, so an identical rebuild never
  re-downloads, and a corrupt partial is deleted rather than resumed.
- `assets/runtime/opencode` is absent and CI fails if it comes back, which is
  what keeps the APK at ~83 MB instead of ~324 MB.
- The manifest is checked to point at the fork, so a stock-binary
  misconfiguration fails the build rather than silently shipping without
  `UpstreamSync`.
- Diagnostics default off, token per-install random, rotation supported.
- Camera declared `required="false"`, so the app is not filtered off
  camera-less devices.
- Backend failures are surfaced in the UI rather than only in a truncated
  notification.

---

## Device test matrix

Minimum coverage before claiming broad compatibility. Unchecked boxes are
currently unverified.

| axis | cases | status |
|---|---|---|
| ABI | arm64-v8a | verified (2 devices) |
| ABI | armeabi-v7a | not installable — decide |
| ABI | x86_64 / emulator | not installable — blocks testing |
| Android | 8.0 (API 26) | untested |
| Android | 10 (API 29) | untested |
| Android | 12 (API 31) | verified (HUAWEI DBY-W09) |
| Android | 14-16 | untested |
| WebView | Google WebView | untested |
| WebView | HUAWEI `com.huawei.webview` 114 | verified |
| WebView | Samsung Internet | untested |
| WebView | Tencent X5 | untested (only if CN ROMs in scope) |
| ROM | HUAWEI EMUI/HarmonyOS | verified |
| ROM | Xiaomi MIUI/HyperOS | untested — battery/autostart risk |
| ROM | Samsung One UI | untested |
| ROM | AOSP / Pixel | untested — the reference case |
| Network | Wi-Fi | verified |
| Network | Tailscale | verified |
| Network | metered / roaming | untested — 240 MB consent path |
| Network | offline first run | untested |
| Update | in-place over previous version | verified with versionCode 1 — retest after fixing #3 |
| Update | runtime carried across update | verified (no re-download) |

---

## Ordered checklist

Do in this order; earlier items change what later ones cost.

1. Decide the distribution channel. If it is not Play, say so in the README
   and stop treating targetSdk 28 as a problem to solve now.
2. Generate a private signing key, move it to CI secrets, delete the
   committed keystore and its history, remove the password fallbacks.
   **Before** anyone else installs.
3. Make versionCode monotonic and re-test in-place upgrade.
4. Decide the ABI story: add x86_64 for emulator/ChromeOS, or document
   arm64-only.
5. Harden the diag switch: auto-off timer, interface warning, exec logging.
6. Add the cleartext warning on `http://` endpoints.
7. Implement in-app APK install, or drop `REQUEST_INSTALL_PACKAGES`.
8. Add a WebView minimum-version gate with a readable failure message.
9. Test on Pixel/AOSP and one Xiaomi; fix battery-persistence per vendor.
10. Add LICENSE + bundled notices and a privacy policy.
11. Fix contrast on muted text; move hardcoded strings into `strings.xml`.
12. Verify the R8-minified release build end to end, not just debug.
