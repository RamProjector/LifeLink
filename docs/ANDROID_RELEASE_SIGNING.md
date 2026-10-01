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

## Manual trigger: the workflow, its inputs and where the artifacts land

The release workflow `.github/workflows/android-release.yml` (**Android Debug +
Release Build**) is **manual only** — it has no `push`/`pull_request` trigger,
so it never runs on its own.

1. Open the repository on GitHub → **Actions** tab.
2. In the left sidebar pick **Android Debug + Release Build**.
3. Click **Run workflow** (right-hand side), choose the branch, and fill in:

   | Input | Meaning |
   |---|---|
   | `build_type` | `both` (default), `debug` or `release` — which job(s) to run |
   | `api_base_url` | baked into `BuildConfig.LIFELINK_API_BASE_URL`; defaults to the hosted Render API |
   | `supabase_url` | optional override; blank uses the `SUPABASE_URL` repository secret |

4. Click the green **Run workflow** button.

When the run finishes, the APKs appear under **Artifacts** on the run page:

| Artifact | Contains | Retention |
|---|---|---|
| `lifelink-debug-<sha>` | `lifelink-debug.apk` | 14 days |
| `lifelink-release-<sha>` | `lifelink-release.apk` | 14 days |
| `lifelink-release-mapping-<sha>` | R8 `mapping.txt`, `configuration.txt`, `seeds.txt` | **30 days** |

Keep the mapping artifact next to the APK — without it you cannot de-obfuscate
release crash reports. The release job also runs
`apksigner verify --print-certs` and **fails** when the signature does not verify
(skipped when no keystore secret is configured, because the APK is then unsigned
by design). Selecting `debug` or `release` skips the other job via its `if:`.

## Which secrets already exist

The repository currently has: `GOOGLE_SERVICES_JSON_BASE64`,
`LIFELINK_API_BASE_URL`, `LIFELINK_DATABASE_URL`,
`SUPABASE_PUBLISHABLE_KEY`, `SUPABASE_URL`.

**None of the four `LIFELINK_RELEASE_*` secrets exist yet**, so the release build
currently produces an unsigned APK. Add all four (Step 3 above) to get a
distributable artifact.

## ⚠️ Watch the exact secret names

The build reads a `lifelinkReleaseKeystore` **Gradle property** first, then the
`LIFELINK_RELEASE_KEYSTORE` **environment variable**. The workflow maps the
repository secret `LIFELINK_RELEASE_KEYSTORE_BASE64` onto that variable after
decoding it.

A secret named `LIFELINK_RELEASE_KEYSTORE_PASSWORD` **does nothing** — the build
reads `LIFELINK_RELEASE_STORE_PASSWORD`. Use exactly:

| Repository secret (exact) | Consumed as |
|---|---|
| `LIFELINK_RELEASE_KEYSTORE_BASE64` | decoded → `LIFELINK_RELEASE_KEYSTORE` |
| `LIFELINK_RELEASE_STORE_PASSWORD` | `LIFELINK_RELEASE_STORE_PASSWORD` |
| `LIFELINK_RELEASE_KEY_ALIAS` | `LIFELINK_RELEASE_KEY_ALIAS` |
| `LIFELINK_RELEASE_KEY_PASSWORD` | `LIFELINK_RELEASE_KEY_PASSWORD` |

A typo here is silent: the workflow takes the "UNSIGNED" warning path and still
exits green.

## Local build from an encrypted keystore

As an alternative to Options A/B/C in *Step 2*, keep the keystore encrypted at
rest and decrypt it only for the build:

```bash
# Decrypt to a private, unpredictable path (mktemp) rather than a predictable
# /tmp name, and always clean it up — even if the build fails.
keystore="$(mktemp "${TMPDIR:-/tmp}/lifelink-release.keystore.XXXXXX")"
trap 'shred -u "$keystore" 2>/dev/null || rm -f "$keystore"' EXIT

# Let GnuPG prompt via Pinentry instead of passing the passphrase as a process
# argument, which any local user could read out of the process table.
gpg -d lifelink-release.keystore.gpg > "$keystore"

export LIFELINK_RELEASE_KEYSTORE="$keystore"
./gradlew buildRelease
```

## Checklist before distributing a release

- [ ] All four `LIFELINK_RELEASE_*` secrets are present in the repository.
- [ ] `./gradlew verifyReleaseSigning` passes (or the workflow's verify step is green).
- [ ] `apksigner verify --print-certs` prints your release certificate and no
      "DOES NOT VERIFY".
- [ ] The R8 `mapping.txt` from the same run is archived.
- [ ] `versionCode` / `versionName` are what you intend (`-PversionCode`, `-PversionName`).
- [ ] The keystore and its passwords are backed up and restorable.

## Security notes

- **Never commit** a keystore, a `keystore.properties`, or a password. `.gitignore`
  excludes `*.keystore`, `*.jks`, `secrets.properties` and `keystore.properties`,
  but the first line of defence is not putting them in the tree at all.
- Do **not** put the passwords in the committed
  `LifeLinkAndroid/gradle.properties`. Use `~/.gradle/gradle.properties` (outside
  the repository) or environment variables.
- Prefer the **env var** route over `-P` flags on a shared machine: `-P` values
  are recorded in the shell history and in Gradle's own logs.
- **Back the keystore up** in a password manager or encrypted vault. Losing it
  means you can never publish an update for `com.lifelink.app` again — Android
  rejects an APK signed with a different key, and there is no recovery.
- On CI the keystore is decoded into `$RUNNER_TEMP` (a throwaway directory wiped
  with the runner), not the workspace.
- Secret scanning (`gitleaks`) runs on every push — a committed keystore or
  password will be flagged and should be treated as compromised and rotated.
