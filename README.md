# OpenCode Mobile (Android)

A self-contained Android app that runs the full [OpenCode](https://opencode.ai) agent
locally on the phone and shows its native web UI. **No Termux, no scripts, no
third-party app in the loop** — press the icon and it works.

```
┌──────────────────────────── phone ────────────────────────────┐
│  APK                                                          │
│  ┌────────────┐   127.0.0.1    ┌──────────────────────────┐  │
│  │ WebView UI │ ◄────────────► │ opencode serve (bundled) │  │
│  └────────────┘                │  linux-arm64-musl        │  │
│                                └────────────┬─────────────┘  │
└─────────────────────────────────────────────┼────────────────┘
                                              │ HTTPS / tailnet
                                              ▼
                                   Your inference endpoint
                                 (your server, your weights)
```

The only external dependency is your **inference endpoint** (an OpenAI-compatible
API). Everything else — the agent, the server, the UI — runs on the device.

## Requirements

- Android **8.0+** (API 26), arm64. HarmonyOS 3 installs the APK via sideload.
- An OpenAI-compatible inference endpoint + API key + model id.
- ~400 MB free storage (bundled backend).

## How it works

1. First launch → onboarding: enter endpoint URL, key, model → **Test** → Save.
2. The app extracts the bundled `opencode` (musl) binary + musl loader + libstdc++
   + libgcc into app-private storage (one time, ~10 s).
3. It launches `opencode serve` bound to `127.0.0.1` on a random port.
4. A WebView loads the local OpenCode UI. The UI is the stock MIT OpenCode web UI.
5. A foreground service keeps the backend alive; it idles out ~30 min after you
   leave the app to save battery, and wakes instantly when you return.

## Build

The APK cannot be built on aarch64 (Google's build tools are x86_64-only), so the
build runs in **GitHub Actions**.

### One-time setup in your repo (Settings → Secrets and variables → Actions)

| Secret | What |
|---|---|
| `KEYSTORE_BASE64` | base64 of your release keystore (`base64 -w0 release.keystore`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

No keystore? The workflow still builds an APK signed with the debug key — fine for
personal use.

### Build it

Push the repo, then either:
- push a tag `v1.0.0` → APK attached to the GitHub Release, or
- Actions → **Build APK** → Run workflow (optionally pick the opencode version).

Download `app/build/outputs/apk/release/*.apk` from the artifact / release.

### Generate a keystore (once)

```bash
keytool -genkeypair -v -keystore release.keystore -alias opencode \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # → KEYSTORE_BASE64
```

### Local build (x86_64 dev machine only)

```bash
bash scripts/prepare-assets.sh      # fetches the bundled backend
gradle assembleRelease              # or assembleDebug
```

## Backend assets

`scripts/prepare-assets.sh` pins and fetches:

| Component | Source | File in `assets/runtime/` |
|---|---|---|
| opencode CLI | npm `@opencode/cli-linux-arm64-musl` | `opencode` |
| musl loader + libc | Alpine `musl` | `ld-musl-aarch64.so.1`, `libc.musl-aarch64.so.1` |
| C++ runtime | Alpine `libstdc++` | `libstdc++.so.6` |
| GCC runtime | Alpine `libgcc` | `libgcc_s.so.1` |

Launch command (validated):
```
ld-musl-aarch64.so.1 --library-path <runtime> <runtime>/opencode serve --hostname 127.0.0.1 --port <p>
```

Assets are **not committed** (`.gitignore`); CI regenerates them and records
checksums in `assets/runtime/assets.lock`.

## Security model

- **Loopback only.** The backend binds to `127.0.0.1`; it is never reachable from
  the network. Cleartext is permitted only for loopback + private/CGNAT ranges
  (for tailnet inference endpoints).
- **Encrypted at rest.** Endpoint key + backend token live in
  `EncryptedSharedPreferences`; backups are excluded.
- **Random port + per-install token.** The backend port is randomized per launch
  and a random token is generated per install.
- **Known limitation.** Current `opencode serve` does not itself enforce the
  server password, so a malicious app on the *same phone* could in principle
  reach the loopback port. Mitigations: random port, loopback-only, idle shutdown.
  A local authenticating reverse-proxy is the planned hardening for v1.1.
- **No telemetry.** The app phones home only to (a) your inference endpoint and
  (b) GitHub Releases for update checks (disable by leaving `UPDATE_REPO` empty).

## Customizing

- **Package name / app id:** change `namespace` and `applicationId` in
  `app/build.gradle.kts` and the package dirs under `src/main/java/`.
- **Pin a different opencode version:** set `OPENCODE_VERSION` in the workflow or
  `scripts/prepare-assets.sh`.
- **Disable update checks:** don't set `UPDATE_REPO`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Backend failed to start" | Check `adb logcat -s BackendManager`; usually a missing asset — re-run `prepare-assets.sh` |
| First launch slow | One-time ~400 MB extraction; subsequent launches are fast |
| Backend killed in background | Disable battery optimization for the app (OEM-specific) |
| Inference 401 | Wrong key/URL in onboarding — use the **Test** button |

## License

The app wrapper is MIT. It bundles OpenCode (MIT) and Alpine musm/libstdc++/libgcc
(MIT / GPL-2.0-or-later / LGPL — see the Alpine packages' own licenses; keep the
`assets.lock` attribution).
