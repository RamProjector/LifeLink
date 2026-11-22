# Account profile and security management

**Status:** Implementation scope and acceptance criteria  
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
- Password changes require current-session authorization and recent authentication/re-authentication where supported. Do not log or persist passwords.
- Rate-limit verification requests and attempts; handle expired, invalid, repeated, and throttled codes without disclosing account existence.
- Re-read the authenticated Supabase user after successful confirmation and refresh the local session email from that trusted response.
- Account email and phone are identity/contact data. Do not copy unverified values into the donor matching profile or expose them on donor maps.
- Keep donor operational profile data (blood type, availability, location, radius) separate from account identity details.
- Do not change production schema or deployed Auth settings as part of client-only work. Any schema/provider configuration needs a separately reviewed change.

## Acceptance criteria

- Display name can be edited and persists through the existing authenticated profile API.
- Email change sends a confirmation to the new address; the old email remains authoritative until confirmation succeeds.
- Expired/invalid email links produce a recoverable message and do not overwrite the session with an unconfirmed address.
- Phone number remains explicitly unverified until a real OTP is delivered and accepted by the configured provider.
- Password change succeeds only through the authenticated Supabase user endpoint; invalid/current-session failures are shown safely.
- Existing password recovery, registration confirmation, sign-in, account switching, and profile loading continue to work.
- UI exposes loading, success, pending verification, expired/failed, and retry states; fields have labels and accessible feedback.
- Automated tests cover API serialization, callback parsing, session refresh, verification failure paths, and Compose navigation. Manual real-device checks must cover actual email delivery and OTP delivery only after a provider is configured.

## Implementation sequence

1. Extend the existing Supabase auth repository with an email-change request using the authenticated user endpoint and a dedicated email-change callback path/type. Add request/response and callback tests.
2. Add Account UI for display name, email-change request/pending status, and password change. Reuse the existing auth session and password-reset implementation; do not duplicate auth state in the profile API.
3. Add phone verification only after confirming an existing no-cost provider configuration or receiving approval for a provider. Until then, present a disabled/pending setup state, not a fake OTP flow.
4. Add tests and update the project documentation. Keep all related commits together in one PR and require CI plus security review before merge.
