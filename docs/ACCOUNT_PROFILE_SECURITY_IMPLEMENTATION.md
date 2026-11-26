# Account profile and security management

**Status:** Android account implementation and live database access hardening are in PR #203; Auth confirmation and real-device verification remain pending  
**Target:** Android client, Supabase Auth, and the existing PostgreSQL profile API  
**Constraints:** Keep the current Kotlin/Compose, Supabase, FastAPI, and PostgreSQL stack. Do not add a paid service, expose privileged keys, or trust client-supplied verification flags.

## User-facing scope

Add an **Account** section inside Profile/Settings with separate, clearly labeled actions:

1. **Personal details** — edit display name and phone number; show current email and phone verification state.
2. **Email address** — request an email change and confirm the new address through a Supabase email confirmation link.
3. **Phone number** — request a phone change and verify the new number with an OTP before treating it as verified.
4. **Password** — change the password through an authenticated, recent-session flow; retain password recovery for users who cannot authenticate.

## Verification and safety rules

- Never mark a new email or phone as verified because the user typed it, saved a form, or returned to the app.
- Email changes must use Supabase Auth's authenticated user-update flow and the configured confirmation behavior. Keep the current session/contact until Supabase confirms the change.
- Phone changes require an actual OTP delivery and verification provider. Supabase phone auth requires an SMS provider to be configured; do not add a paid provider or simulate successful delivery/verification. Until a real provider is configured, show the feature as unavailable with an honest explanation and preserve the existing number.
- Password changes re-authenticate the current password against Supabase for the same signed-in user, require both tokens from that verified sign-in response, then update the password through the authenticated user endpoint. After success, persist the verified access-token/refresh-token pair together; never combine a new access token with the old session's refresh token. If Supabase omits the verified refresh token, do not send the password update and ask the user to sign in again. Neither token nor password is logged.
- Rate-limit verification requests and attempts; handle expired, invalid, repeated, and throttled codes without disclosing account existence.
- Re-read the authenticated Supabase user after successful confirmation and refresh the local session email from that trusted response.
- Account email and phone are identity/contact data. Do not copy unverified values into the donor matching profile or expose them on donor maps.
- Keep donor operational profile data (blood type, availability, location, radius) separate from account identity details.
- Database changes must be represented by ordered, reviewed SQL migrations and verified against the live schema. Do not change Supabase Auth settings or introduce an SMS provider without a separate review.

## Deployment integration prerequisites

The Android implementation declares these callback URLs:

- Registration confirmation: `lifelink://auth/confirm`
- Password recovery: `lifelink://auth/recovery`
- Email change: `lifelink://auth/email-change`

The Android manifest accepts the `lifelink://auth/` deep-link path prefix, and the callback parser requires the email-change path with Supabase callback type `email_change`. Before enabling this flow in a live environment:

1. Add the exact email-change redirect URI to the Supabase Auth redirect URL allow list. Do not assume that setting the Site URL alone permits a custom mobile scheme.
2. Verify the **Secure Email Change** setting. When enabled, Supabase requires confirmation at both the current and new email addresses; when disabled, it sends one confirmation to the new address. Test the behavior that the project actually uses and ensure the email template routes the confirmation back to the requested deep link.
3. Verify the change-email template uses Supabase's confirmation URL and that the configured mail delivery path can send the required message or messages.
4. Test both the first and final confirmation callbacks on a real device. Confirm that the app only reflects the new address after Supabase reports it as the authenticated user's email.

**Environment verification note (2026-11-23):** The connected Supabase project named `LifeLink` (ref `nphbhlxnujmylhubzmne`) reports `ACTIVE_HEALTHY`. The database access-hardening migrations described below were applied and verified. The available connection does not expose the Auth redirect allow list, Secure Email Change setting, email template, or delivery configuration, so actual email delivery and callback completion remain unverified. No production Auth settings were changed.

## Live database hardening

The Android and admin clients authenticate through Supabase Auth and send data requests to the LifeLink FastAPI service. They are not intended to query the public database tables directly through PostgREST. The private API connection remains the database access path.

Migration `015_restrict_client_database_access.sql`:
- Revokes all table and sequence privileges, plus function execution, from `PUBLIC`, `anon`, and `authenticated` in the `public` schema.
- Preserves database-owner and `service_role` access.
- Removes the connected `postgres` role's default client grants for future public-schema objects.
- Keeps RLS enabled as defense in depth.

Migration `016_secure_location_triggers_and_fk_indexes.sql`:
- Pins `set_facility_location()` and `set_donor_location()` to `pg_catalog, extensions` search paths.
- Adds indexes for `donor_location_shares.donor_id`, `emergency_requests.facility_id`, and `saved_messages.message_id`.

**Live verification:** all 16 public tables currently report RLS enabled and zero rows. The live privilege audit confirms that `anon` and `authenticated` have no direct table privileges, while `postgres` and `service_role` retain access. A temporary table probe confirmed the RLS auto-enable event trigger still works after function execute privileges were revoked. A temporary PostGIS trigger probe confirmed the pinned search path still writes the expected latitude/longitude. No application records were inserted, updated, or deleted.

**Remaining advisor findings:** eleven backend-only tables have RLS enabled without client policies by design; the client roles have no grants. Twelve RLS init-plan performance notices remain on retained policies. Leaked-password protection remains disabled in Supabase Auth and needs dashboard configuration. Unused-index notices are not treated as grounds for index removal because the inspected tables are empty and workload evidence is unavailable.

## Migration-history reconciliation

The live Supabase migration-history query (read-only verification on 2026-11-26) returns versions `20260929040902` (`007_donor_map_chat_contact_sharing`) and `20261125234130` (`020_rbac_role_permission_registry`). It does not list migrations `015`, `016`, `021`, or `022`, even though current catalog queries confirm the corresponding live privilege revocations, postgres-role default-function ACL, PostGIS trigger search paths, and all three foreign-key indexes. The ledger discrepancy therefore remains; the additional `020` entry does not reconcile the missing hardening migrations. Treat this as migration-history drift: the catalog proves the current state, not which migration execution produced it. Do not blindly reapply these SQL files or treat the migration ledger as complete. Before future automated migration deployment, reconcile the existing live schema and migration ledger with the repository's migration sequence using a reviewed, non-destructive baseline procedure.

## Validation snapshot (2026-11-24)

- Commit `1945bfa2dc8361f2d13e543f5c0771e064a83598` passed all five PR-triggered workflows: Android UI test APK (fake backend), Security scan, Validate cloud package, CodeQL, and Build Android APKs. These results validate that commit only.
- The latest fixes are on current head `33666b71b4cd6af0106a14c63889123f4647df3b`: the password-change flow now persists the access/refresh token pair returned by the same verified sign-in, refuses to update the password if that response lacks a refresh token, and directs secure email-change users to the other inbox because either address may be confirmed first.
- The current head has no fresh PR workflow runs yet. GitHub reports the branch as diverged from main and not mergeable; the main-map-state changes were manually integrated into the branch's `LifeLinkShell.kt`, but the mergeability result still needs reconciliation.
- CodeRabbit's submitted review state remains `CHANGES_REQUESTED`; a fresh independent review is required. Live Supabase Auth email delivery/callback and real-device flows remain unverified; phone OTP remains unavailable until an approved SMS provider is configured.

## Acceptance criteria

- Display name can be edited and persists through the existing authenticated profile API.
- Email change sends a confirmation to the new address; the old email remains authoritative until confirmation succeeds.
- With Secure Email Change enabled, the first confirmation is treated as pending and directs the user to confirm the other inbox; the final confirmation refreshes the authenticated email. Expired/invalid email links produce a recoverable message and do not overwrite the session with an unconfirmed address.
- Phone number remains explicitly unverified until a real OTP is delivered and accepted by the configured provider.
- Password change succeeds only after current-password re-authentication for the same account and then uses the authenticated Supabase user endpoint; invalid/current-session failures are shown safely.
- Existing password recovery, registration confirmation, sign-in, account switching, and profile loading continue to work.
- UI exposes loading, success, pending verification, expired/failed, and retry states; fields have labels and accessible feedback.
- Automated tests cover API serialization, callback parsing, session refresh, verification failure paths, and Compose navigation. Manual real-device checks must cover actual email delivery and OTP delivery only after a provider is configured.

## Implementation sequence

1. The Android Account UI now supports display-name editing, email-change requests, and a current-password-verified password change. Email-change writes are account-scoped, recover from one expired-token response, and reset transient pending UI state after activity recreation.
2. Supabase email-change confirmation uses a dedicated callback path/type. When Secure Email Change is enabled, the first confirmation callback is handled as a pending step (no access token is expected yet) and tells the user to confirm the other inbox; the final callback completes the authenticated session refresh. The old email remains authoritative until Supabase completes both confirmations. Real email delivery and callback completion still require end-to-end testing against the deployed Supabase Auth settings.
3. Phone verification remains unavailable until a real SMS OTP provider is configured. No fake OTP or paid provider was added.
4. Database migrations `015_restrict_client_database_access.sql` and `016_secure_location_triggers_and_fk_indexes.sql` revoke direct `PUBLIC`/`anon`/`authenticated` access to public tables, sequences, and functions; remove the `postgres` role's future public-schema client grants; pin the PostGIS trigger-function search paths; and add indexes for three unindexed foreign keys. They were applied to the live database without modifying application rows. Verification confirmed that client roles have no direct table privileges, `postgres` and `service_role` retain access, the RLS auto-enable event trigger still works, and the facility location trigger still writes the expected coordinates.
5. The separate `supabase_admin` default privileges cannot be altered by the project's `postgres` role. New tables created by that owner continue to rely on the RLS auto-enable event trigger's default-deny behavior; review grants and policies whenever such tables are introduced. Supabase Auth leaked-password protection is still disabled and must be enabled in Auth settings.
6. Auth repository operations preserve coroutine cancellation instead of converting `CancellationException` into ordinary authentication failures, so cancelled UI/network work stops promptly.
7. Tests cover email request serialization, first-step and final-step callback parsing, token refresh, password re-authentication/account binding, and the database grant guardrail. Full real-device verification, latest CI, and fresh independent security review remain required before merge.
## Follow-up database hardening (live catalog verified; migration ledger unresolved)

The review identified two portability/security gaps in the earlier database hardening:

- PostgreSQL's creator-global default PUBLIC EXECUTE grant is not removed by the schema-scoped function revoke alone. Migration `021_revoke_public_function_default_execute.sql` removes global EXECUTE defaults for PUBLIC, anon, and authenticated on functions created by postgres, then explicitly preserves the trusted service_role grant. The RLS checker requires the global revoke, schema-specific revokes, and trusted-role grant, with a negative fixture for the former six-statement configuration.
- PostGIS may be installed in either public or extensions. Migration `016_secure_location_triggers_and_fk_indexes.sql` resolves the installed extension schema from `pg_extension` and pins both trigger functions to that validated schema. Migration `022_pin_postgis_trigger_schema.sql` applies the same correction to databases where migration 016 was already applied.

**Live catalog verification (2026-11-26):** the `postgres` role's public-schema future-function ACL contains only `postgres` and `service_role`; it does not include PUBLIC, anon, or authenticated. Both `set_donor_location()` and `set_facility_location()` report `search_path=pg_catalog, extensions`. The three FK indexes are present. The queried `lifelink_user_roles` and `lifelink_security_alerts` tables grant access to `service_role`, not PUBLIC/anon/authenticated. This verifies the intended 015/016/021/022 effects in the live catalog, but does not establish which deployment statement introduced them. The migration-history query contains versions `20260929040902` and `20261125234130`; it still omits `015`, `016`, `021`, and `022`. Do not replay migrations blindly. Reconcile the ledger separately through a reviewed, non-destructive baseline procedure.

## Email-change callback behavior

When the first confirmation link is opened on a device that still has the signed-in session, LifeLink keeps the app shell available and directs the user to the other email inbox for the final confirmation. Supabase can accept either the current or new address first, so the remaining link may be in either inbox. Signed-out users continue to see the pending-confirmation instructions on the authentication screen. The new email is not treated as verified until Supabase completes the final confirmation and the authenticated user is re-read.


## Cross-screen feedback and session acceptance criteria (carried forward from closed UI-polish work)

- Present authentication and account-change outcomes as concise, contextual, accessible feedback; keep field-level validation near the affected control.
- Never expose raw Supabase, SQL, HTTP response, or exception details in user-facing messages.
- Do not announce an email change as complete until the final confirmation has succeeded and the authenticated user has been re-read from Supabase.
- On account/session mismatch, stop the operation rather than applying a response to a different signed-in account. Preserve cancellation and refresh-race protections.
- Regression coverage should include success, expired-token retry, second authorization failure, account switch, cancellation, and safe error redaction. Real-device confirmation remains a separate verification requirement.
