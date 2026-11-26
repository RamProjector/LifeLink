# Security Policy

LifeLink is a coordination platform. Treat account identifiers, contact details, request information, precise location, and private messages as sensitive data. Do not commit real user data, database passwords, API tokens, Firebase service-account credentials, or private keys.

## Hosted API and database

- Keep `LIFELINK_AUTH_REQUIRED=true` in hosted environments.
- The PostgreSQL API verifies Supabase JWT signatures and expiry. Protected routes also require the JWT session ID to match a live `auth.sessions` record for the same user.
- The API registers verified sessions in `public.lifelink_user_sessions`. A session revoked by an administrator is rejected on the next protected API request, including non-admin application routes.
- Admin access is restricted by the server-side `LIFELINK_ADMIN_USER_IDS` allowlist. Never grant administrator access through public registration or client-controlled role fields.
- Public application tables have Row Level Security enabled. Some backend-only tables intentionally have no client policies; their `anon` and `authenticated` table grants must remain absent. Recheck actual grants and policies after schema changes rather than adding broad policies to silence advisor notices.
- Keep database access least-privileged, use HTTPS, maintain backups and retention, and review audit and security alerts regularly.

## Android session storage

- Persisted Supabase access and refresh tokens are encrypted with AES-256-GCM. The encryption key is generated and retained by Android Keystore; preferences contain ciphertext and the per-encryption IV only.
- Existing complete sessions in the legacy `lifelink_auth` preference file are migrated to encrypted storage and the legacy plaintext file is cleared. Unreadable or incomplete sessions are discarded and require sign-in.
- Both legacy and encrypted auth preference files are excluded from Android backup and device transfer.
- Sign-out clears local credentials immediately and requests Supabase Auth sign-out for the current device session (`scope=local`). If the network request fails, the user remains signed out locally but server-side revocation is unconfirmed; the app displays a warning.

## Known project-level hardening item

The Supabase Security Advisor reported **Leaked Password Protection disabled** on 2026-11-26. This is a Supabase Auth project setting, not an application-code switch. Enable it in the Supabase Dashboard's Auth password-security settings when the project plan supports the feature, then re-run the Security Advisor.

## Firebase / Google API key in `google-services.json`

`LifeLinkAndroid/app/google-services.json` is committed on purpose: the Firebase Android config must ship inside the APK, and its `api_key` is a client identifier, not a server secret. GitHub secret scanning flags it as a `google_api_key`; both alerts are resolved as **false positive** for that reason.

That is only safe while the key is restricted. The key **must** be restricted in the Google Cloud console (APIs & Services -> Credentials -> the Android key):

- **Application restriction:** Android apps, with the package name `com.lifelink.app` and the release signing certificate SHA-1 (add the debug SHA-1 too if debug builds need it).
- **API restriction:** limit it to the Firebase APIs the app actually uses (Firebase Installations, Firebase Cloud Messaging, Identity Toolkit) rather than "Don't restrict key".

An unrestricted key can be abused for quota and billing. If the key is ever rotated, update `google-services.json` and re-verify the restrictions. Server credentials (the FCM service-account JSON) are never committed; they are read from the `FIREBASE_SERVICE_ACCOUNT_JSON` environment variable.

## Reporting a vulnerability

Do not open a public issue containing sensitive details. Contact the repository owner privately through GitHub.
