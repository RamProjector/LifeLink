# LifeLink build fix — summary

Short record of the build investigation, the problems found, the fixes applied,
and how to build debug and release APKs.

## Build system

| Layer | Technology |
|---|---|
| Mobile app | Native **Android** (Kotlin + Jetpack Compose), built with the **Gradle** wrapper (`LifeLinkAndroid/`) |
| Android toolchain | Android Gradle Plugin **9.4.1**, Gradle **9.6.0**, `compileSdk`/`targetSdk` **37**, Kotlin **2.2.10** (built into AGP), KSP 2.3.12, JDK **21** |
| Backend | Python **FastAPI** (`lifelink_fastapi/`) — unaffected by this change |
| CI | GitHub Actions (`.github/workflows/`) |

Everything below concerns the Android module. The FastAPI backend was not
touched.

## Problems found

### 1. `gradlew` is committed without the executable bit (real bug, fixed)

`LifeLinkAndroid/gradlew` was the **only** script in the repository tracked as
mode `100644` instead of `100755`:

```text
100644  …  LifeLinkAndroid/gradlew          <- not executable
100755  …  .githooks/pre-push
100755  …  scripts/setup-git-hooks.sh
```

Every CI job masks this with a `chmod +x ./gradlew` step, so the problem was
invisible in CI — but any fresh local clone fails immediately:

```text
$ ./gradlew assembleDebug
-bash: ./gradlew: Permission denied   # exit code 126
```

**Fix:** the executable bit is now set in the index
(`git update-index --chmod=+x LifeLinkAndroid/gradlew`), so `./gradlew …` works
straight after a clone and the `chmod` workaround becomes unnecessary.

### 2. The release build type was never production-ready (fixed)

```kotlin
release {
    isMinifyEnabled = false   // no code shrinking/obfuscation
    proguardFiles(...)        // rules were inert
    // no signingConfig -> release APK is always UNSIGNED
}
```

The project README itself flagged this: *"A signed release keystore is still
required before distribution; the release build is unsigned and unminified."*
An unsigned, unminified APK cannot be published. There was also **no way to
configure release signing at all** — the properties the build would need did not
exist.

### 3. No explicit, triggerable build actions

Only the raw Gradle tasks (`assembleDebug` / `assembleRelease`) existed, and
there was **no workflow that builds a release APK** — the existing workflows
(`android-build.yml`) only produce debug APKs.

### Note on the toolchain

No application-code compile errors were found. A full `assembleDebug` was run
locally against JDK 21 + Android SDK 37 and **succeeded**, producing
`app-debug.apk`; only deprecation *warnings* are emitted.

## Fixes applied

| File | Change |
|---|---|
| `LifeLinkAndroid/gradlew` | Executable bit set in git (`100755`) |
| `LifeLinkAndroid/app/build.gradle.kts` | Release signing config resolved from properties/env; `isMinifyEnabled = true`; `isShrinkResources = true`; new `buildDebug`, `buildRelease`, `verifyReleaseSigning` tasks |
| `.github/workflows/android-release.yml` | **New** workflow: manual (or on-demand) build of the debug **and** release APK, with release signing via secrets and an `apksigner verify` gate |
| `docs/ANDROID_RELEASE_SIGNING.md` | **New** step-by-step release signing guide |
| `README_BUILD_FIX.md` | This file |

## Build actions

```bash
cd LifeLinkAndroid

./gradlew buildDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew buildRelease   # -> app/build/outputs/apk/release/app-release.apk  (requires signing)
./gradlew assembleRelease  # raw release build; allowed to be unsigned
./gradlew verifyReleaseSigning  # fails fast if signing is not configured
```

`buildRelease` depends on `verifyReleaseSigning`, so it refuses to produce an
unusable APK when no keystore is configured.

In CI, the **Android Debug + Release Build** workflow
(`.github/workflows/android-release.yml`) is dispatchable from *Actions → Run
workflow* with a `build_type` input of `both`, `debug`, or `release`.

## Release build configuration — in short

The release keystore is **never committed**. The build resolves, for each value,
the Gradle property first and then the environment variable:

| Meaning | Gradle property | Environment variable |
|---|---|---|
| Keystore path | `lifelinkReleaseKeystore` | `LIFELINK_RELEASE_KEYSTORE` |
| Keystore password | `lifelinkReleaseStorePassword` | `LIFELINK_RELEASE_STORE_PASSWORD` |
| Key alias | `lifelinkReleaseKeyAlias` | `LIFELINK_RELEASE_KEY_ALIAS` |
| Key password | `lifelinkReleaseKeyPassword` | `LIFELINK_RELEASE_KEY_PASSWORD` |

1. Create the keystore once:
   ```bash
   keytool -genkeypair -v -keystore lifelink-release.keystore \
     -alias lifelink -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
   ```
2. Build:
   ```bash
   export LIFELINK_RELEASE_KEYSTORE=$PWD/lifelink-release.keystore
   export LIFELINK_RELEASE_STORE_PASSWORD='…'
   export LIFELINK_RELEASE_KEY_ALIAS=lifelink
   export LIFELINK_RELEASE_KEY_PASSWORD='…'
   ./gradlew buildRelease
   ```
3. In GitHub Actions, add the four secrets
   `LIFELINK_RELEASE_KEYSTORE_BASE64`, `LIFELINK_RELEASE_STORE_PASSWORD`,
   `LIFELINK_RELEASE_KEY_ALIAS`, `LIFELINK_RELEASE_KEY_PASSWORD`.

The full guide, including verification with `apksigner` and troubleshooting, is
in [`docs/ANDROID_RELEASE_SIGNING.md`](docs/ANDROID_RELEASE_SIGNING.md).

## Verification status

- **Debug build:** verified — `./gradlew assembleDebug` succeeded locally
  (Android SDK 37, JDK 21) and produced `app-debug.apk`.
- **Gradle configuration / build actions:** verified — `./gradlew :app:tasks`
  lists `buildDebug`, `buildRelease`, and `verifyReleaseSigning`, and the script
  evaluates without error.
- **Release build (R8):** the configuration phase was verified locally (the
  signing warning is emitted and the release variant configures). The full
  `assembleRelease` R8 step could not be completed **in this sandbox only**,
  because the build container is capped at 2 GB of memory and the R8 task is
  killed by the out-of-memory killer. This is a sandbox limit, not a project
  defect — GitHub Actions runners provide ~16 GB and the workflow is configured
  for them. Run `./gradlew buildRelease` on a normal machine or in Actions to
  produce the signed APK.
