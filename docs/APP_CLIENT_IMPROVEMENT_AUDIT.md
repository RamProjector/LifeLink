# LifeLink Android Client Improvement Audit

**Review date:** 26 October 2026

**Repository:** [RamProjector/LifeLink](https://github.com/RamProjector/LifeLink)

**Scope:** Native Android client in `LifeLinkAndroid/`
**Purpose:** Turn the existing client roadmap into a prioritized, testable implementation checklist.

> **Review status:** This is a repository-informed audit and implementation plan, not a claim that every item below has been implemented or reproduced on a physical device. Re-check the current source, pull requests, CI results, and live services before treating a task as complete. Keep the existing Kotlin/Jetpack Compose, Retrofit, Room, WorkManager, MapLibre, Supabase Auth, and FastAPI architecture; no paid dependency or OpenAI API key is required.

## Implementation update — 28 October 2026

The current implementation work is consolidated in [PR #153](https://github.com/RamProjector/LifeLink/pull/153). The donor-location and map/navigation items below have code changes and regression coverage in that PR; their status remains **pending final CI/review** until the final head passes all required checks.

- **GPS source and permission:** both donor setup and the legacy donor workspace remove manual coordinates/map-pin editing, require precise location permission, and gate saving on a fresh GPS capture.
- **Presence freshness:** the backend now separates `last_presence_at` from availability-toggle time, applies an additive startup migration/backfill, excludes stale location from matching/map/exact sharing, and expires stale operational presence. Foreground refresh only runs for verified donors who explicitly remain available.
- **Donor map/details:** marker and list selection open a privacy-safe approximate-area details view; identity/contact details and exact coordinates are not exposed on the public map.
- **Navigation and evidence:** donor tab selection is restored when returning from chat; MapLibre style/render readiness and emulator screenshot evidence are included.
- **Security:** the embedded development API-token default is removed; default Retrofit clients send no authorization header.
- **Still requires live-environment verification:** real-device GPS behavior, live FCM delivery, and the full chat send/receive/reconnect lifecycle cannot be certified from source review alone.

## Executive assessment

LifeLink already has meaningful client-side infrastructure: authenticated Supabase sessions, refresh-token handling, requester/donor onboarding, GPS capture and manual location fallback, native MapLibre maps, emergency-request submission and matching, donor responses, request-status refresh, offline submission handling, account-scoped Room persistence, and a push-token path. The most valuable next work is therefore **workflow reliability, clear state feedback, privacy transparency, and end-to-end verification**, rather than adding unrelated features or restyling screens in isolation.

The core user journey should remain simple:

1. Sign in and understand which role/profile is active.
2. Create or review an emergency request with a clear location source.
3. Understand matching results and why some donors may not appear.
4. Select and contact specific donors.
5. Track acceptance, contact sharing, and request completion.
6. Manage account, privacy, and location-sharing controls.

## Prioritized client backlog

Priority meanings: **P0** blocks safe, understandable core use; **P1** is the next major workflow-completeness slice; **P2** improves polish and resilience after core behavior is verified.

### P0 — Donor-area map correctness and feedback

**Problem to prevent:** A map that looks empty or broken can be mistaken for GPS failure, even when no donor has opted in, no donor is available, the location is stale, the network is offline, or map tiles have failed.

Required behavior:
- Separate map initialization/loading, successful results, genuinely empty results, permission denied, location unavailable, offline/network error, and server error.
- Explain that the donor map displays privacy-preserving approximate areas/counts, not individual live donor pins.
- Never equate “no donor areas available” with “GPS is off.” State which condition actually occurred.
- Provide retry for API and tile failures, without resetting the active request or location draft.
- Provide a recenter control on the map, but do not silently overwrite a manually selected pin.
- Prevent duplicate markers when refreshing; use stable identifiers or aggregation appropriate to the approximate-area representation.
- Ensure filters, refresh, map-style loading, and navigation do not produce stale results or leave an infinite spinner.
- Keep request submission usable with manual location entry when GPS permission or map tiles are unavailable.

**Acceptance checks**
- With zero eligible/visible donors, show a calm empty state and keep the map usable.
- With permission denied, explain the effect and provide a manual-location alternative.
- With GPS disabled, offer a device-settings resolution path where supported; do not report this as a generic network failure.
- With API or tile failure, show retry and preserve entered fields.
- Repeated refreshes do not duplicate areas or erase valid cached data on failure.
- No exact donor coordinates, donor names, or per-donor pins appear in requester map responses/UI.

### P0 — Location source, permission, and consent

Every location used in a request should have a clear source, such as **Current device location**, **Map pin**, or **Manually entered approximate location**.

Required behavior:
- Explain why location is requested before the system permission prompt.
- Request foreground location only when the user enters a feature that needs it; avoid surprise permission prompts at launch.
- Display accuracy/freshness when the platform provides it, and do not imply that a location is exact when it is approximate.
- Make pin movement visible and confirmable; avoid changing a request location as a side effect of map recentering.
- No silent, always-on background donor tracking. Donor location updates remain tied to app use or explicit user action.
- Donor map visibility and exact-location sharing must be separate, explicit choices. Hiding from the approximate map should also revoke active exact-location sharing when the backend contract specifies this.
- Do not display exact donor location unless the server confirms the authorized, active, unexpired share for that matched request.

**Acceptance checks**
- Denied, approximate, precise, revoked, stale, and GPS-disabled states each have understandable copy and a safe fallback.
- A map pin selection survives recomposition and map-style loading.
- Permission denial does not crash or trap the user.
- No location request is made solely because an unrelated screen was opened.

### P0 — Authentication, role, and session lifecycle

**Known architectural risk:** UI state must not be treated as proof of server role or profile existence. The server's authenticated profile is authoritative.

Required behavior:
- Keep requester/donor mode changes pending until the API accepts the capability change and the relevant profile state is confirmed.
- Distinguish a donor-capable account from a completed donor operational profile.
- If the profile request fails, retain the last safe role state and show retry rather than optimistically navigating into a broken donor shell.
- Verify expired access token refresh, single retry, refresh-token rejection, sign-out, account switch, process recreation, and concurrent requests.
- Provide clear password-reset and confirmation-email recovery paths if supported by the current auth setup; otherwise track them as explicit gaps rather than dead controls.
- Prevent account A's cached requests, inbox, messages, notification history, or drafts from appearing after account B signs in.
- Keep sign-out and session-expiry cleanup independent of a currently visible screen.

**Acceptance checks**
- Server denies unauthorized actions even if a client button is manually invoked.
- Role/profile fetch failure does not silently change the account role.
- Account switching clears account-scoped UI state and prevents cross-account offline submission.
- Session refresh race tests prove that concurrent requests do not repeatedly refresh or accidentally expire a valid session.

### P1 — Home and emergency-request flow

Required behavior:
- Make the primary action obvious: create a request or return to an existing active request.
- Keep labels, spacing, typography, button hierarchy, and error presentation consistent across Home, Requests, Map, Chat, and Profile.
- Use a step-based request form with preserved input, visible progress, inline field validation, and a clear review/submit action.
- Clearly identify required fields and distinguish optional details.
- Prevent duplicate submissions with idempotency and an in-progress state; disable only the action that is actually processing.
- Explain whether a request was saved locally, queued for retry, accepted by the server, or rejected.
- Do not claim donors have been notified merely because a request was submitted. Matching and requester-selected donor contact are separate states.
- Make retry actions safe and preserve the form after transient failures.

**Acceptance checks**
- Invalid fields identify the exact correction needed.
- A slow server response does not allow duplicate requests.
- Rotating/recreating the Activity or navigating away and back does not discard a draft unexpectedly.
- Offline queue retries are bound to the originating account and draft.
- A permanent validation/auth rejection is not retried indefinitely as though it were a transient network error.

### P1 — Request history and complete contact lifecycle

Use the existing improvement scope as the feature baseline; do not invent a parallel status model.

Required behavior:
- Present active, completed/fulfilled, cancelled, and expired requests in a predictable history.
- Show each contacted donor's pending, accepted, declined, or terminal state with timestamps where available.
- Provide a dedicated accepted-donor card with explicit consent-based contact disclosure.
- Clearly distinguish **contact details shared**, **meeting arranged**, **fulfilled**, and **cancelled**; these states must not be synonyms.
- Prevent contact disclosure, messaging, or exact-location sharing after cancellation/expiry unless the backend explicitly permits the operation.
- Allow users to retry a history load and show an empty state when the account truly has no history.
- Polling must stop or slow down when a request is terminal or the screen is no longer active, consistent with Android lifecycle behavior.

**Acceptance checks**
- Refresh does not revert a terminal state to an older cached state.
- Cancelled/expired requests cannot create new contact shares.
- Back navigation from chat returns to the correct request/contact context.
- Network failure leaves a clearly marked stale state instead of presenting old data as current.

### P1 — Chat, contact sharing, and push notifications

Required behavior:
- Chat entry points are visible only when the current request/contact relationship authorizes a conversation.
- Clearly label who the user is messaging and which emergency request the conversation belongs to.
- Show sending, sent, failed, and retry states; avoid duplicate messages on retry.
- Contact details must be shared one field at a time with explicit confirmation and a clear record of what was shared.
- Notifications must be account-scoped. Never display a push without a valid recipient identity where the current push contract requires one.
- Handle notification taps for signed-out users, wrong accounts, expired sessions, deleted/expired requests, and missing conversations safely.
- Do not put sensitive medical details, access tokens, or exact coordinates into notification text or logs.

**Acceptance checks**
- A notification for account A is not displayed in account B's session.
- A failed push is not presented as guaranteed delivery.
- Conversation and contact-share endpoints deny unrelated users, even if they guess an ID.

### P1 — Privacy, account controls, and user trust

Required behavior:
- Add a clear privacy notice reachable from the app, not only a repository document.
- Explain location purpose, approximate map visibility, exact-location sharing conditions, contact-detail disclosure, notification behavior, and data retention/deletion in plain language.
- Document a real contact channel and the actual process for account/data deletion before promising deletion to users.
- Let donors review and change map visibility, availability, pause reason, and location-sharing consent.
- Avoid collecting clinical data not needed for operational matching.
- Show confirmation for irreversible or high-impact actions such as cancellation, sign-out with pending work, or deleting an account.
- Keep privacy copy synchronized with actual backend behavior and deployed policy.

**Acceptance checks**
- Every privacy promise maps to an implemented server/client behavior and a defined owner/process.
- Users can find the privacy notice and explain what the map reveals.
- Revoking a share updates the UI and backend state; the client does not rely on hiding the location locally.

### P2 — Accessibility, performance, and visual consistency

Required behavior:
- Follow Android accessibility guidance and WCAG 2.2 AA as a useful web-content benchmark where applicable; use native platform accessibility behavior for Android controls.
- Ensure actionable elements have meaningful labels, sufficient touch targets, readable contrast, scalable text, and logical TalkBack focus order.
- Do not communicate status by color alone; pair colors with text/icons and semantic state.
- Avoid nested scroll conflicts and unbounded recomposition/work in lists or maps.
- Use stable Compose keys and lifecycle-aware collection for observable state.
- Move network/database work off the main thread and cancel obsolete work when screens leave composition.
- Keep loading placeholders sized to prevent layout jumps; keep error/empty states visually consistent.
- Test on small screens, large font sizes, dark/light themes if both are supported, and slower devices/network conditions.

**Acceptance checks**
- TalkBack can identify every actionable control and state.
- Text can scale without clipped primary actions.
- Long donor/request lists scroll smoothly without duplicate content or visible state flicker.
- Returning from a backgrounded Activity does not restart unnecessary location polling or duplicate network calls.

## Suggested screen-by-screen review order

| Order | Screen/area | Main review objective |
|---:|---|---|
| 1 | Authentication/session | Account safety, recovery, loading/error states |
| 2 | Role/profile routing | Server-authoritative role and profile existence |
| 3 | Home | Clear next action and active-request continuity |
| 4 | Emergency request form | Validation, location source, draft persistence |
| 5 | Donor map | Empty/loading/error correctness and privacy |
| 6 | Match results | Explainable results, freshness, safe donor selection |
| 7 | Requests/history | Lifecycle completeness and stable statuses |
| 8 | Donor inbox/respond | Ownership, accept/decline, stale request behavior |
| 9 | Accepted contact/chat | Explicit sharing, relationship authorization |
| 10 | Profile/privacy | Visibility, availability, notice, sign-out and deletion path |
| 11 | Notifications | Account isolation, deep links, expired state handling |

## Engineering constraints

- Preserve the existing app/backend stack and API contracts unless a reviewed change demonstrates a necessary migration.
- Do not add a paid service or require an OpenAI API key.
- Use Dependabot and the repository's CI/tests as the default automated review baseline.
- Never create admin privileges through public signup.
- Never use production donor data in screenshots, fixtures, or test artifacts.
- Do not run destructive database changes without explicit authorization and a tested recovery plan.
- Avoid a UI-only fix for an authorization or data-integrity bug; enforce the rule on the API/database side as well.

## Required evidence for each implementation PR

Each client-focused PR should include:
1. The specific user problem and intended behavior.
2. The source files/screens changed and the state/lifecycle impact.
3. Unit tests for success, empty, loading, error, permission-denied, and retry paths as relevant.
4. Authorization/privacy regression tests for backend-affecting changes.
5. Android build, unit-test, lint, and relevant emulator/visual-review results.
6. Screenshots or video using synthetic accounts/data for visible UI changes.
7. A note identifying anything that still requires physical-device or live-service verification.

## Research references

- [Android app architecture](https://developer.android.com/topic/architecture)
- [Android lifecycle-aware components](https://developer.android.com/topic/libraries/architecture/lifecycle)
- [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)
- [Request location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Location best practices](https://developer.android.com/develop/sensors-and-location/location/battery)
- [OWASP MASVS](https://mas.owasp.org/MASVS/)
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/)
- [Supabase Auth security](https://supabase.com/docs/guides/auth)
