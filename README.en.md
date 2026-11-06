# Flue Next

**An Android launcher (HOME app) for smartwatches**, written in Kotlin with Jetpack Compose.

A watch home screen that aims to be usable every day: watch faces, app drawer, Smart Stack,
notification center and control center live in one process, so gestures and transitions stay
continuous instead of bouncing between separate apps.

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![CI](https://github.com/xumidcm/Flue-next/actions/workflows/ci.yml/badge.svg)](https://github.com/xumidcm/Flue-next/actions/workflows/ci.yml)
![minSdk 27](https://img.shields.io/badge/minSdk-27-informational)

Current version `2.4.0 (31)`. Beta: usable, still being polished.

中文文档见 [README.md](README.md)。

## Two editions

| | Standard | CYTJ OS custom |
|---|---|---|
| Branch | `main` | [`cytj-os`](https://github.com/xumidcm/Flue-next/tree/cytj-os) |
| Target | Generic, adapts to vendor watch ROMs | Tuned for CYTJ OS devices |

Both are developed in the open in this repository. The custom edition merges from `main`
periodically but its releases are not kept in lockstep.

## Features

- **Watch face home** — built-in digital / analog / MD3E clock faces, image and video faces with
  automatic color extraction, plus scanning and switching of installed third-party faces.
- **Watch face runtimes (interoperability)** — `.watch` (WatchMaker-compatible) with Lua scripts
  executed inside a Flue-owned whitelist sandbox, JB watch faces, ClockSkin faces. XML and zip
  parsing disables DTDs and external entities.
- **App drawer** — honeycomb and list layouts, ADW icon pack support, fisheye and "time warp"
  animations, palette derived from the current watch face.
- **Smart Stack** — quick app slots, aggregated notification previews, battery and step count.
- **Notification center / control center** — grouped notifications, swipe to dismiss, rubber-band
  pull gestures.
- **Settings** — hidden apps, performance toggles, appearance, backup and restore, watch face
  editor, and a watch face marketplace client that can point at your own server.
- **Experimental** — watch face AI agent with local tool calling and a user-supplied API key.

## Requirements

| | |
|---|---|
| OS | Android 8.1 (API 27)+, works with Wear OS and vendor watch ROMs |
| Display | Round or square, adaptive layout |
| ABI | `arm64-v8a`, `armeabi-v7a` (the AI agent workspace is `arm64-v8a` only) |
| Build | JDK 21, Android SDK 36, AGP 8.13.2, Kotlin 2.3.21 |

## Install

1. Download the APK from [Releases](https://github.com/xumidcm/Flue-next/releases).
2. `adb install -r Flue-release.apk`, or open it from a file manager on the watch.
3. Set Flue as the default home app when the system asks.

Verify the signature before installing:

```
certificate SHA-256: 62bd781c365503b33b12b59b7874e6b4f919d41231ac5715e0b6e851fb2d82a6
```

```bash
apksigner verify --print-certs Flue-release.apk
```

Flue ships no in-app updater, so builds with a different fingerprint are not official.

## Permissions

A launcher asks for more than a normal app. Each permission below is tied to a concrete feature;
the full table (with when it is requested) is in the [Chinese README](README.md#权限说明).

Highlights: package enumeration for the app drawer, notification listener for the notification
center, an accessibility service used **only** for double-tap screen-off and the power menu
(`canRetrieveWindowContent="false"`), and device admin for `force-lock`.
Everything stays on the device. See [SECURITY.md](SECURITY.md).

## Build from source

```bash
./gradlew :app:testDebugUnitTest --no-daemon --console=plain
./gradlew :app:assembleRelease --no-daemon --console=plain   # unsigned without signing config
./gradlew installDebug
```

Release signing is read from `keystore.properties` (local, never committed) or the
`FLUE_SIGNING_STORE_FILE` / `FLUE_SIGNING_STORE_PASSWORD` / `FLUE_SIGNING_KEY_ALIAS` /
`FLUE_SIGNING_KEY_PASSWORD` environment variables.

Point the marketplace client at your own server without touching source:

```bash
./gradlew :app:assembleRelease -PflueWatchStoreBaseUrl=https://your-host/api
```

## Layout

```text
app/src/main/kotlin/        app code (package com.flue.launcher)
  ui/home, ui/drawer,       UI layers
  ui/smartstack, ui/notification, ui/controlcenter
  service/                  notification listener, accessibility, device admin
  watchface/agent           experimental AI agent
  watchface/store           marketplace client
app/src/main/java/com/flue/launcher/watchface/   watch face runtimes
app/src/main/jniLibs/       PRoot binaries (see third_party/proot)
app/src/test/               JVM + Robolectric tests (250+ files)
docs/                       design documents and validation records
```

## Contributing

**Pull requests are welcome.** Fork, branch from `main`, keep one topic per branch, make
`:app:testDebugUnitTest` pass, and include device verification for UI, notification or gesture
changes. See [CONTRIBUTING.md](CONTRIBUTING.md).

Please open an issue first for: large refactors, new third-party dependencies, changes to watch
face format semantics, or anything that adds permissions.

## Known limitations

- Weather uses an undocumented Xiaomi endpoint that may break at any time.
- The default marketplace endpoint points at a community service maintained by the author.
- Third-party watch face support is an interoperability implementation and does not cover every
  feature of every vendor format.
- Round and square layouts need real-device tuning; reports are welcome.

## Security

Report vulnerabilities through
[private reporting](https://github.com/xumidcm/Flue-next/security/advisories/new), not a public issue.

## License

[GPL-3.0](LICENSE). Any distributed modified APK — including the CYTJ OS custom edition — must ship
the complete corresponding source.

Bundled third-party components (PRoot, FFmpegKit, and others) keep their own licenses — see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Flue is not affiliated with WatchMaker, JB, or
any other watch face platform; their names and trademarks belong to their owners.
