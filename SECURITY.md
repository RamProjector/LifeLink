# Security Policy

LifeLink is a coordination platform. Treat account identifiers, contact details, request information, precise location, and private messages as sensitive data. Do not commit real user data, database passwords, API tokens, Firebase service-account credentials, or private keys.

## Hosted API and database

- Keep `LIFELINK_AUTH_REQUIRED=true` in hosted environments.
- The PostgreSQL API verifies Supabase JWT signatures and expiry. Protected routes also require the JWT session ID to match a live `auth.sessions` record for the same user.
- The API registers verified sessions in `public.lifelink_user_sessions`. A session revoked by an administrator is rejected on the next protected API request, including non-admin application routes.
- Admin access is restricted by the server-side `LIFELINK_ADMIN_USER_IDS` allowlist. Never grant administrator access through public registration or client-controlled role fields.
- Public application tables have Row Level Security enabled. Some backend-only tables intentionally have no client policies; their `anon` and `authenticated` table grants must remain absent. Recheck actual grants and policies after schema changes rather than adding broad policies to silence advisor notices.
- Keep database access least-privileged, use HTTPS, maintain backups and retention, and review audit and security alerts regularly.


## Administrator MFA and RBAC rollout status

The backend code on `main` now requires AAL2 plus at least two server-confirmed verified TOTP factors for operational administrator endpoints. The backend reads the signed-in user's verified TOTP factor count from Supabase's `auth.mfa_factors` inventory; client-reported counts are audit metadata only. The narrowly scoped `/v1/admin/preflight` endpoint remains the AAL1 identity/session check for first-time enrollment and returns no operational admin data. `POST /v1/admin/mfa/events` uses a separate AAL2-only dependency and independently reconciles lifecycle receipts against Supabase's factor inventory.

The deployment is **not certified solely by the presence of this code**. The Render runtime database role must have the required least-privilege SELECT access to `auth.sessions`, `auth.mfa_factors`, and (for the complete Admin Console user inventory) `auth.users`; the last read-only grant inspection confirmed that the `postgres` role could read the session/factor inventories while `service_role`, `authenticated`, and `anon` could not. The new `auth.users` inventory query must be verified against the actual Render connection role before rollout. Do not broaden grants to client roles. Verify the actual Render database connection role and complete a non-production sign-in/enrollment/challenge and user-inventory test before claiming production enforcement. The LifeLink project does not currently enable Supabase's experimental recovery-code feature. Losing every verified TOTP factor can prevent sign-in unless a separately approved operator recovery procedure is available. Never add a service-role key, TOTP secret, OTP, or access/refresh token to browser or Android code, source control, or logs.

Operational admin routes now declare exact RBAC permission dependencies. `LIFELINK_RBAC_ENFORCEMENT_MODE` defaults to `observe`, which preserves the existing allowlist + MFA gate while administrator role assignments are provisioned. Do not set it to `enforce` until every active administrator has an approved, non-expired role assignment and the permission matrix has passed non-production verification. For ordinary administrator roles, enforce mode denies missing exact permission-map grants; it does not grant access from user-editable profile metadata. The explicit `super_admin` exception has no permission-map rows: an account with an active `super_admin` assignment can pass operational route permission checks only when it is also present in both `LIFELINK_ADMIN_USER_IDS` and `LIFELINK_RBAC_MANAGER_USER_IDS`. This dual-allowlist exception is intentionally separate from ordinary exact-grant authorization. Role assignment itself remains protected by the separate `LIFELINK_RBAC_MANAGER_USER_IDS` allowlist.

## Android session storage

- Persisted Supabase access and refresh tokens are encrypted with AES-256-GCM. The encryption key is generated and retained by Android Keystore; preferences contain ciphertext and the per-encryption IV only.
- Existing complete sessions in the legacy `lifelink_auth` preference file are migrated to encrypted storage and the legacy plaintext file is cleared. Unreadable or incomplete sessions are discarded and require sign-in.
- Both legacy and encrypted auth preference files are excluded from Android backup and device transfer.
- Sign-out clears local credentials immediately and requests Supabase Auth sign-out for the current device session (`scope=local`). If the network request fails, the user remains signed out locally but server-side revocation is unconfirmed; the app displays a warning.

## Known project-level hardening items

- **Leaked Password Protection is disabled.** The live Supabase Security Advisor reported this warning on 2026-11-26. The LifeLink Supabase organization is currently on the Free plan, and Supabase documents this feature as Pro-plan-only. It cannot be enabled through application code or a database migration. Do not upgrade the project without explicit cost approval. Revisit this control if the organization later chooses a paid plan.
- **RLS enabled without policies is currently intentional for backend-only tables.** A live metadata check on 2026-11-26 confirmed that all 22 public base tables have RLS enabled and none grant table privileges to `PUBLIC`, `anon`, or `authenticated`. The Security Advisor's 17 `rls_enabled_no_policy` findings are informational in this setup; do not add broad client policies or grants just to silence them. Recheck grants and policies whenever a table's access model changes.

## Firebase / Google API key in `google-services.json`

`LifeLinkAndroid/app/google-services.json` is committed on purpose: the Firebase Android config must ship inside the APK, and its `api_key` is a client identifier, not a server secret. GitHub secret scanning flags it as a `google_api_key`; both alerts are resolved as **false positive** for that reason.

That is only safe while the key is restricted. The key **must** be restricted in the Google Cloud console (APIs & Services -> Credentials -> the Android key):

- **Application restriction:** Android apps, with the package name `com.lifelink.app` and the release signing certificate SHA-1 (add the debug SHA-1 too if debug builds need it).
- **API restriction:** limit it to the Firebase APIs the app actually uses (Firebase Installations, Firebase Cloud Messaging, Identity Toolkit) rather than "Don't restrict key".

An unrestricted key can be abused for quota and billing. If the key is ever rotated, update `google-services.json` and re-verify the restrictions. Server credentials (the FCM service-account JSON) are never committed; they are read from the `FIREBASE_SERVICE_ACCOUNT_JSON` environment variable.

## Reporting a vulnerability

Do not open a public issue containing sensitive details. Contact the repository owner privately through GitHub.
