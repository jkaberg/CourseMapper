# Developing CourseMapper

Notes for working on the app itself. For what it does and how to install it, see the
[README](README.md).

## Build

```bash
git clone https://github.com/jkaberg/CourseMapper.git
cd CourseMapper
./gradlew assembleDebug        # gradlew.bat on Windows
```

Kotlin, Jetpack Compose, Hilt, Room, WorkManager and MapLibre Native. Needs JDK 21 - Kotlin 2.x
doesn't run on newer JDKs yet. If your default JDK is newer, point Gradle at 21 in your user level
`~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk-21
```

CI builds on `ubuntu-latest` with Temurin 21.

On Windows `dev.ps1` wraps the ADB round trip:

```powershell
.\dev.ps1 deploy --usb    # connect + build + install
.\dev.ps1 logs            # logcat filtered to com.coursemapper
.\dev.ps1 help            # connect, build, install, deploy, clean, logs
```

Without `--usb` it connects to the TCP/IP device in `$Device`, override with `-Device`.

## Layout

Single `app` module, package `com.coursemapper`:

| Package | Holds |
|---|---|
| `data` | Room entities, DAOs, repositories |
| `domain` | Course, marker, stop and route models plus the placement ordering logic |
| `gpx` | GPX import and export |
| `location` | Location updates and the recording foreground service |
| `map` | MapLibre style, overlays and camera |
| `offline` | Map pack estimation and downloads |
| `routing` | Directions (Valhalla, OSRM as fallback) |
| `sharing` | Share sheet export |
| `ui` | Compose screens, one package per feature, plus `designsystem` and `components` |

## Tests

```bash
./gradlew testDebugUnitTest        # unit tests, doesn't record or verify screenshots
./gradlew verifyRoborazziDebug     # fail on any changed pixel
./gradlew recordRoborazziDebug     # accept the new pixels
```

UI is covered by JVM screenshot baselines (Roborazzi on Robolectric, no emulator). Baselines are
committed under `app/src/test/screenshots/` so pixel changes show up as image diffs in review.

Screens that embed a MapLibre map can't be captured this way, MapLibre needs its native library.
Those are covered by the instrumented tests in `app/src/androidTest/`, which need a device.

## UI layer

Two tiers, and they don't mix:

- **`ui/designsystem/`** - tokens (spacing, icon sizes, map strip height, colour scheme) and
  primitives that know nothing about courses: `CmTopBar`, `CmLoading`, `CmEmptyState`,
  `CmConfirmDialog`, `CmSectionHeader`, `CmMenu`.
- **`ui/components/`** - shared components that do: `CourseStatusBadge`, `GpsStatusIndicator`,
  `OfflineStatusRow`, `TargetDistanceChips`.

`DesignSystemBoundaryTest` enforces the split and four rules: no domain imports in `designsystem`,
no `parseColor` outside the cached bridge, no literal map strip heights, and no hand-written
back-arrow app bars.

Dynamic colour is off on purpose. `primary`, `tertiary` and `error` mean GPS ready, weak signal and
no fix, and some wallpapers make those three look the same. `ColorSchemeContrastTest` keeps every
`on*` pair above WCAG AA and the three GPS roles clearly apart.

The run navigation internals (`RunControls`, `RunBanner`, `StopListSheet`, `RiderPuck`) are kept out
of the shared layers - they're built for gloved use and recompose under a `withFrameNanos` loop.

## Releasing

Pushing a tag builds, runs the unit tests, signs and publishes a release. Both `1.2.3` and `v1.2.3`
work, the tag becomes `versionName` and `versionCode` is derived from it (`1.2.3` -> `10203`).

```bash
git tag 1.2.3 && git push origin 1.2.3
```

The signing key is created once and reused for the life of the app:

```bash
keytool -genkeypair -v -keystore release.jks -alias coursemapper \
        -keyalg RSA -keysize 2048 -validity 10000
```

CI reads it from four secrets in the `release` environment: `ANDROID_KEYSTORE_BASE64`
(`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD`. Running the workflow manually from the Actions tab does a dry run, publishing
is skipped without a tag.

> [!WARNING]
> Keep the keystore backed up outside the repo. Lose it and no future release can install over an
> existing one.
