# Android release build and signing

This document explains how to configure and run a **release** build of the
LifeLink Android app (`LifeLinkAndroid/`). The debug build needs none of this —
see [`ANDROID_DEBUG_SIGNING.md`](ANDROID_DEBUG_SIGNING.md) for the debug signing
key.

## What the release build does

The `release` build type in `app/build.gradle.kts` is configured to:

- **sign** the APK with a production keystore when one is supplied (see below);
- run **R8** code shrinking/obfuscation (`isMinifyEnabled = true`) using the
  keep rules in `app/proguard-rules.pro`;
- run **resource shrinking** (`isShrinkResources = true`);
- write an R8 **mapping file** to
  `app/build/outputs/mapping/release/mapping.txt` (keep it — it is required to
  de-obfuscate release crash reports).

If no keystore is configured the build still completes, but it prints:

```
LifeLink: release signing is not configured, so the release APK will be UNSIGNED.
```

An unsigned APK cannot be installed on a device or published. Configure signing
before distributing.

## Build actions

| Action | Command | Output |
|---|---|---|
| Debug build | `./gradlew buildDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Release build (requires signing) | `./gradlew buildRelease` | `app/build/outputs/apk/release/app-release.apk` |
| Raw release build (unsigned is allowed) | `./gradlew assembleRelease` | `app/build/outputs/apk/release/app-release.apk` |
| Signing pre-flight check | `./gradlew verifyReleaseSigning` | fails fast if signing is not configured |

`buildRelease` depends on `verifyReleaseSigning`, so it refuses to run without a
keystore instead of silently producing an unusable APK.

## Where the signing values come from

The build resolves the keystore location and passwords at build time, **never**
from a committed file. For each value the Gradle property is checked first, then
the environment variable:

| Meaning | Gradle property | Environment variable |
|---|---|---|
| Keystore file path | `lifelinkReleaseKeystore` | `LIFELINK_RELEASE_KEYSTORE` |
| Keystore password | `lifelinkReleaseStorePassword` | `LIFELINK_RELEASE_STORE_PASSWORD` |
| Key alias | `lifelinkReleaseKeyAlias` | `LIFELINK_RELEASE_KEY_ALIAS` |
| Key password | `lifelinkReleaseKeyPassword` | `LIFELINK_RELEASE_KEY_PASSWORD` |

All four must be present and the keystore file must exist, otherwise the APK is
built unsigned.

## Step 1 — Create a production keystore (once)

```bash
keytool -genkeypair -v \
  -keystore lifelink-release.keystore \
  -alias lifelink \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

Keep `lifelink-release.keystore` and its passwords safe and backed up. If the
keystore is lost you can no longer publish updates for the existing
`com.lifelink.app` package. **Never commit it** — `.gitignore` already excludes
`*.keystore`.

## Step 2 — Build a signed release locally

### Option A — environment variables (recommended for one-off builds)

```bash
cd LifeLinkAndroid
export LIFELINK_RELEASE_KEYSTORE=/absolute/path/to/lifelink-release.keystore
export LIFELINK_RELEASE_STORE_PASSWORD='…'
export LIFELINK_RELEASE_KEY_ALIAS=lifelink
export LIFELINK_RELEASE_KEY_PASSWORD='…'

./gradlew buildRelease \
  -PlifelinkApiBaseUrl=https://your-api.example.com/ \
  -PsupabaseUrl=https://your-project.supabase.co/ \
  -PsupabasePublishableKey=your-publishable-client-key
```

### Option B — `~/.gradle/gradle.properties` (per-developer, outside the repo)

Add the following to `~/.gradle/gradle.properties` (never to the project's
`gradle.properties`, which is committed):

```properties
lifelinkReleaseKeystore=/absolute/path/to/lifelink-release.keystore
lifelinkReleaseStorePassword=…
lifelinkReleaseKeyAlias=lifelink
lifelinkReleaseKeyPassword=…
```

Then just run `./gradlew buildRelease` (plus any `-P` build-config values).

### Option C — `-P` flags

```bash
./gradlew buildRelease \
  -PlifelinkReleaseKeystore=/absolute/path/to/lifelink-release.keystore \
  -PlifelinkReleaseStorePassword='…' \
  -PlifelinkReleaseKeyAlias=lifelink \
  -PlifelinkReleaseKeyPassword='…'
```

`-P` flags are recorded in the shell history and Gradle logs, so prefer Option A
or B on a shared machine.

## Step 3 — Configure release signing in GitHub Actions

Add these **repository secrets** (GitHub → Settings → Secrets and variables →
Actions):

| Secret | Value |
|---|---|
| `LIFELINK_RELEASE_KEYSTORE_BASE64` | `base64 -w0 lifelink-release.keystore` |
| `LIFELINK_RELEASE_STORE_PASSWORD` | keystore password |
| `LIFELINK_RELEASE_KEY_ALIAS` | key alias (e.g. `lifelink`) |
| `LIFELINK_RELEASE_KEY_PASSWORD` | key password |

The **Android Debug + Release Build** workflow
(`.github/workflows/android-release.yml`) decodes the keystore to a temporary
path on the runner, exports `LIFELINK_RELEASE_KEYSTORE`, builds
`assembleRelease`, and verifies the result with `apksigner verify --print-certs`.
It also uploads the R8 mapping file as a 30-day artifact.

## Step 4 — Verify the produced APK

```bash
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

The certificate DN must match your release key. To confirm shrinking happened,
compare the release APK size with the debug APK — the release build is
noticeably smaller because R8 and resource shrinking are enabled.

## Troubleshooting

- **"release signing is not configured" warning** — one of the four values is
  missing, or the keystore path does not exist. Check the path is absolute.
- **`verifyReleaseSigning` fails** — expected when only `assembleRelease`
  (raw) is meant to run; use `assembleRelease` for an unsigned smoke build, or
  configure signing to use `buildRelease`.
- **R8 stripping something it should not** — add a keep rule to
  `app/proguard-rules.pro`. Retrofit, Gson DTOs, Room, Firebase and MapLibre
  already have rules.
- **A release crash stack is obfuscated** — use the matching
  `mapping.txt` with `retrace`.
