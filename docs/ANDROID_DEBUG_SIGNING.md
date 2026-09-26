# Android debug APK signing and updates

## Why an APK update can fail

Android only permits an installed application to be updated when the new APK has the same `applicationId` **and** is signed by the same certificate. The default Gradle debug keystore on a GitHub-hosted runner is generated on that temporary runner. It is therefore different between builds.

The API key, Supabase publishable key, and Render URL do not control APK installation updates. This is an Android signing-certificate issue.

## Repository behavior

The Android build workflow now:

- accepts a persistent keystore through GitHub Actions secrets;
- restores it as `app/stable-debug.keystore` only during the build;
- signs debug builds with it when all four signing secrets are present; and
- passes the GitHub workflow run number as an increasing `versionCode`.

Without the signing secrets, local and CI builds continue to use the normal temporary debug key.

## Required repository secrets

Configure these secrets in **GitHub → Settings → Secrets and variables → Actions**:

- `LIFELINK_DEBUG_KEYSTORE_BASE64`
- `LIFELINK_DEBUG_STORE_PASSWORD`
- `LIFELINK_DEBUG_KEY_ALIAS`
- `LIFELINK_DEBUG_KEY_PASSWORD`

Never commit the keystore, passwords, or private signing material to the repository.

## One-time migration on an existing device

The APK already installed from an earlier ephemeral runner key cannot be updated by a new stable-key APK because the old certificate is unavailable. Uninstall LifeLink once, install the first stable-key APK, and future stable-key builds will update in place. Uninstalling removes app-local data unless the app's backup/restore behavior preserves it, so complete any needed account/session setup again if required.
