# Account profile and security management

**Status:** First Android implementation in PR #203; real Supabase confirmation and device verification remain pending  
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
- Password changes re-authenticate the current password against Supabase for the same signed-in user, then update the password through the authenticated user endpoint. Do not log or persist passwords.
- Rate-limit verification requests and attempts; handle expired, invalid, repeated, and throttled codes without disclosing account existence.
- Re-read the authenticated Supabase user after successful confirmation and refresh the local session email from that trusted response.
- Account email and phone are identity/contact data. Do not copy unverified values into the donor matching profile or expose them on donor maps.
- Keep donor operational profile data (blood type, availability, location, radius) separate from account identity details.
- Do not change production schema or deployed Auth settings as part of client-only work. Any schema/provider configuration needs a separately reviewed change.

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

**Environment verification note (2026-11-23):** The connected Supabase project named `LifeLink` (ref `nphbhlxnujmylhubzmne`) reports `INACTIVE`. Its Auth redirect allow list, Secure Email Change setting, email template, and delivery configuration could not be inspected or exercised while it was inactive. The project was not restored and no production Auth settings were changed. These remain deployment checks, not verified implementation claims.

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
3. Phone verification remains unavailable until a real SMS OTP provider is configured. No fake OTP, paid provider, production schema migration, or production Auth setting change is included.
4. Tests cover email request serialization, first-step and final-step callback parsing, token refresh, and password re-authentication/account binding. Profile-name persistence after a fresh launch, full real-device verification, CI, and independent security review remain required before merge.
