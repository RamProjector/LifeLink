# LifeLink Android HCI and Privacy Redesign Roadmap

**Status:** Audit and implementation checklist  
**Scope:** Android client in `LifeLinkAndroid/`; preserve Kotlin, Jetpack Compose, Material 3, existing repositories, APIs, Supabase integration, and backend contracts unless a separate reviewed change proves a contract change is necessary.  
**Working rule:** one screen or shared interaction at a time; each implementation PR must include a before/after rationale, tests, accessibility checks, and any required backend/privacy review. Do not merge a screen redesign merely because it looks better.

## Why this redesign is needed

The current shell has four primary destinations — Home, Requests, Chat, Profile — and a map action that is a centered, icon-only floating button on compact layouts. On wider layouts, Map is a separate navigation-rail item. This creates inconsistent navigation affordance across sizes, and the compact map destination has no visible text label.

The donor map has important protections already: its documented intent is to show opt-in, approximate donor areas, not donor identities or exact coordinates; visibility and exact-location sharing are separate controls. The screen also includes filter, privacy, refresh, loading, error, and empty states. These should be retained and tested, not removed for a cleaner screenshot.

The map is not equivalent to TACResQ's GPS map. TACResQ's centered “GPS Map” is for a confirmed incident / device location and explicitly distinguishes a one-time device fix from uploaded or continuous tracking. LifeLink's current map is a **donor-area discovery map**. Do not label it “GPS” or imply that it locates the current user unless a fresh location fix is actually obtained, permission is granted, and the UI says what is local versus shared.

## Evidence base and design constraints

1. **Android navigation patterns:** use familiar top-level navigation, keep destinations at the same hierarchy level, and adapt navigation for compact versus expanded windows. The Android guidance recommends 3–5 top-level destinations for a navigation bar and a rail on larger layouts.  
   - https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns
2. **Android accessibility:** ensure controls have at least 48 dp touch targets; provide meaningful labels, readable contrast, scalable text, and non-gesture alternatives. Test with accessibility tools and real assistive technology.  
   - https://developer.android.com/guide/topics/ui/accessibility  
   - https://developer.android.com/guide/topics/ui/accessibility/apps
3. **System bars / edge-to-edge:** controls and content must respect status, gesture-navigation, and display-cutout insets. Verify both gesture and three-button navigation, keyboard visibility, portrait/landscape, and large-screen layouts.  
   - https://developer.android.com/design/ui/mobile/guides/foundations/system-bars
4. **Location permission:** request location just in time, explain why it is needed, prefer approximate location when adequate, and handle denial / approximate-only permission gracefully.  
   - https://developer.android.com/develop/sensors-and-location/location/permissions/runtime  
   - https://developers.google.com/maps/documentation/android-sdk/location
5. **Mobile privacy/security:** minimize sensitive data collection, request only needed permissions, secure local data and tokens, avoid sensitive logging, and provide clear user controls.  
   - https://mas.owasp.org/MASVS/  
   - https://mas.owasp.org/MASVS/controls/MASVS-PRIVACY-1/  
   - https://mas.owasp.org/MASVS/controls/MASVS-STORAGE-1/  
   - https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html
6. **Philippine privacy notice:** the National Privacy Commission describes rights to be informed, access, rectify, object, erasure/blocking, portability, and complaint; notices should describe data, purposes, lawful basis, recipients, automated processing, controller contact, retention, and rights. The actual LifeLink operator and processing practices must be confirmed before the notice is represented as final.  
   - https://privacy.gov.ph/data-privacy-act/  
   - https://privacy.gov.ph/the-right-to-be-informed/  
   - https://privacy.gov.ph/data-subject-rights/
7. **TACResQ comparison:** its repository documents a role-aware, centered GPS Map destination, a tap-to-locate flow, one-time location preview, clear stale/unavailable states, and no implication of background tracking. Borrow the clarity and map entry-point hierarchy, not emergency-specific incident or dispatch behavior.  
   - https://github.com/RamProjector/TACResQ-School-Project/blob/main/docs/MOBILE_GPS_MAP_UI_RECOMMENDATION.md

These are design and engineering references, not a claim that LifeLink is certified WCAG-compliant or legally compliant. Applicable requirements and the operator's actual processing must be verified.

## Ordered implementation checklist

### Phase 0 — Safety baseline and measurable acceptance criteria

- [ ] Keep the current main branch buildable; use a dedicated branch for each focused change.
- [ ] Record baseline Android lint, Detekt, Spotless, unit tests, instrumentation tests, debug build, and CodeQL results before changing shared UI.
- [ ] Inventory all destinations, role gates, deep links/notification routes, back-stack behavior, loading/error/empty states, and persisted state.
- [ ] Capture baseline screenshots on a compact phone and expanded layout in light and dark themes; include font scaling and system navigation modes.
- [ ] Confirm no UI-only redesign silently changes API payloads, role permissions, database schema, or location-sharing behavior.
- [ ] Keep demo/test data visibly separate from production data and never add production-like fabricated donor locations.

**Exit criteria:** baseline evidence exists; existing tests and role-specific routes are known; no unreviewed contract changes.

### Phase 1 — Shared navigation and map entry point (first UI priority)

- [ ] Make every top-level destination discoverable with a visible label; do not make the map discoverable only by guessing a pin icon.
- [ ] Choose one consistent top-level map pattern for compact and expanded layouts. Preferred direction: a clearly labelled, persistent, center-docked **Donor map** destination where space allows, with a selected state and the same behavior on rail/expanded layouts.
- [ ] Keep the map a destination, not a disguised “create request” or SOS action. Keep high-priority request creation separate.
- [ ] Decide whether map navigation hides the app navigation while open; if it does, provide a clear close/back affordance and restore the previous tab predictably.
- [ ] Ensure bottom items do not collide with the center control, gesture inset, keyboard, badges, or large font sizes.
- [ ] Test tap, repeated tap, system Back, process recreation, notification-driven navigation, and role switching while a secondary screen is open.
- [ ] Add Compose instrumentation coverage for selected state, opening/closing the map, and returning to the previous destination.

**Acceptance:** all primary destinations are labelled; map behavior is consistent across compact and expanded layouts; no controls are obscured; Back and state restoration are deterministic.

### Phase 2 — Donor map and location trust

- [ ] Separate **map center**, **device location**, **donor approximate areas**, and **exact-location share** in state and wording. Never infer one from another.
- [ ] Do not display a default city center as if it were the user's current location. If no user/device/request location exists, explicitly label the fallback region or ask the user to choose an area.
- [ ] Provide a just-in-time “Locate me” action only if a real device-location flow is implemented; explain the purpose before permission, request the minimum scope, and handle approximate-only, denied, permanently denied, location-services-off, stale, timeout, and unavailable states.
- [ ] Keep donor pins coarsened, opt-in, non-identifying, and time-bounded; never make the map reveal exact donor coordinates through pin details, accessibility text, logs, analytics, or map camera precision.
- [ ] Make filter state visible and reversible; show a clear “no results for these filters” state and a one-tap reset. Do not show a generic empty state while data is actually loading or failed.
- [ ] Make privacy controls understandable before toggles are changed. Explain exactly who can see approximate map visibility versus exact location, for what relationship, and when sharing ends.
- [ ] Make save-in-progress, success, and failure explicit; do not optimistically claim that a privacy change succeeded before the backend confirms it.
- [ ] Ensure map markers update instead of accumulating duplicates after refresh/style reload; verify map lifecycle, rotation, process recreation, timeout, and offline recovery.
- [ ] Provide a non-map list or textual summary for users who cannot use the map, plus an accessible description of approximate areas and freshness.
- [ ] Test that revocation, expired authorization, failed refresh, sign-out, and account switch clear previously visible exact-location state.

**Acceptance:** no misleading location claims; donor visibility is opt-in; exact sharing is explicit and revocable; all map states have meaningful text and retry/recovery; the map is not the only way to access results.

### Phase 3 — Home / dashboard

- [ ] Establish one clear page title and one primary next action based on role and request state.
- [ ] Prioritize active request/donation status and next step; move secondary metrics and explanatory paragraphs lower.
- [ ] Remove duplicate calls to action and repeated labels; keep the same spacing, typography, card elevation, and icon style as other screens.
- [ ] Distinguish first-use, no active request, loading, stale data, network error, and completed states.
- [ ] Verify that changing role does not show actions or information for a role the backend has not confirmed.
- [ ] Test screen-reader reading order and large-font layout.

### Phase 4 — Emergency request creation and editing

- [ ] Review the form as a task flow: required fields first, optional details clearly marked, plain-language labels, input-specific keyboard types, units, and inline validation.
- [ ] Explain how location is obtained and shared before requesting permission or submitting coordinates.
- [ ] Preserve drafts across rotation/process recreation where appropriate; avoid silently losing typed values on validation or network errors.
- [ ] Disable duplicate submission while sending; use an idempotent request path where supported; show a stable request reference after success.
- [ ] Define and test edit/cancel rules for pending, matching, accepted, and terminal requests; require confirmation for consequential cancellation and explain what happens to contacted donors.
- [ ] Show backend-confirmed state, not a locally inferred success.
- [ ] Verify no patient name, unnecessary medical detail, or exact address is required unless the service has a documented need.

### Phase 5 — Matching results and active request

- [ ] Present compatibility, availability, approximate distance/area, and freshness without exposing donor identity/location beyond authorization.
- [ ] Explain that matching is assistance, not a guarantee of eligibility, blood safety, availability, or clinical compatibility.
- [ ] Use consistent status language and a visible timestamp for changing state.
- [ ] Make contact/message actions available only for eligible backend-confirmed statuses.
- [ ] Provide empty, partial, stale, retry, and cancellation states.
- [ ] Test refresh races, stale response overwrites, account changes, and a request becoming terminal while the screen is open.

### Phase 6 — Donor onboarding and donor workspace

- [ ] Break onboarding into short, purpose-led groups with progress and clear required/optional labels.
- [ ] Explain eligibility evidence and its purpose; collect the minimum evidence needed and avoid promising clinical verification unless it actually occurs.
- [ ] Keep availability, profile visibility, approximate map visibility, and exact-location sharing as distinct concepts.
- [ ] Validate coordinates and service radius; make coordinate source and precision clear; ensure clearing a field really clears the submitted value.
- [ ] Make save failures preserve form input and expose a retry path.
- [ ] Test the requester-to-donor role transition only after the backend confirms a real donor profile/capability.

### Phase 7 — Messaging and contact sharing

- [ ] Make conversation identity/context clear without displaying unnecessary personal data.
- [ ] Distinguish sent, sending, failed, and retry states; prevent duplicate messages after retries.
- [ ] Explain contact-share scope and provide a way to review/revoke it where supported.
- [ ] Provide report/block actions with confirmation and clear consequences; verify server-side authorization and abuse handling.
- [ ] Avoid leaking message bodies, access tokens, exact locations, or contact details into logs, notifications, screenshots, or crash reports.
- [ ] Test old conversation data is cleared when switching conversation, account, or role.

### Phase 8 — Notifications and in-app updates

- [ ] Keep an in-app update center that works when OS push permission is denied.
- [ ] Ask for notification permission in context, after explaining the benefit; do not block core app use on a denial.
- [ ] Use concise, privacy-safe lock-screen text by default; avoid exposing blood type, precise location, or sensitive request details on a shared lock screen.
- [ ] Make read/unread state, mark-all-read, empty/error/loading, and deep links deterministic.
- [ ] Verify notification taps open only data the authenticated user can access; stale/expired request links must show a safe explanation.
- [ ] Document whether tokens are stored, how they are rotated, and how they are removed on logout/account changes.

### Phase 9 — Profile, security, legal, and privacy controls

- [ ] Group profile, account security, privacy, notifications, theme, help, legal documents, and sign-out into predictable sections.
- [ ] Confirm email/password recovery, session expiry, sign-out, account switching, and token clearing work end to end.
- [ ] Make Privacy Policy and Terms reachable after sign-up, in settings, and when a material version changes.
- [ ] Show policy version/effective date and retain an auditable record of acceptance where required; do not make a legal agreement look like a decorative informational card.
- [ ] Replace any generic “contact support” language with an actual, operator-approved channel before production release.
- [ ] Add clear user routes for access/correction, objection/withdrawal where applicable, deletion/blocking requests, portability where applicable, and privacy complaints. Define identity verification, timelines, exceptions, and retention handling with the operator.
- [ ] Publish the actual personal information controller/operator identity, Data Protection Officer/privacy contact, purposes, lawful bases, categories of data, recipients/processors, transfers, retention periods, automated matching/profiling details, user rights, and complaint route.
- [ ] Validate privacy statements against the real app/backend: location collection and precision, map coarsening, messages, push tokens, technical logs, retention, backups, Supabase/Render/Firebase processing, and deletion behavior.
- [ ] Have the operator/privacy professional review the notice and terms before describing them as legally finalized. Do not invent an operator identity, contact address, lawful basis, or retention period.

### Phase 10 — Shared visual system and accessibility

- [ ] Standardize page header, content width, horizontal gutters, section spacing, card styles, form field spacing, button hierarchy, empty/error/loading surfaces, and status chips.
- [ ] Use a consistent icon family and meaningful labels; decorative icons should be hidden from assistive technology.
- [ ] Verify at least 48 dp interactive targets, text scaling, contrast in light/dark/dynamic themes, TalkBack semantics, switch access, keyboard navigation, and focus order.
- [ ] Ensure color is never the only signal for availability, error, urgency, or selection.
- [ ] Support edge-to-edge insets, gesture navigation, three-button navigation, portrait/landscape, compact phones, and expanded/foldable layouts.
- [ ] Avoid unneeded animations and layout shifts; honor system animation preferences where relevant.
- [ ] Use screenshot/visual regression tests for layout consistency, but do not treat screenshot matching as proof of usability or accessibility.

### Phase 11 — Release validation and usability evidence

- [ ] Run Android lint, Detekt, Spotless, unit tests, instrumentation tests, debug/release builds, CodeQL, and secret scanning.
- [ ] Test with synthetic accounts for requester and donor roles; include no donor profile, no location permission, approximate permission, location services off, slow/offline network, expired session, and backend error cases.
- [ ] Conduct task-based usability checks: find donor map; understand visibility; create/cancel request; contact a matched donor; find privacy policy; sign out; recover account.
- [ ] Record completion, errors, backtracks, and participant comments; prioritize observed friction over aesthetic preference alone.
- [ ] Verify backend/API behavior and account isolation for every sensitive flow. Client-side hiding is not authorization.
- [ ] Only merge when required checks pass and the PR explains behavior changes, privacy implications, and test evidence.

## Screen inventory / execution order

| Order | Screen / component | Priority | Main reason |
|---|---|---|---|
| 1 | Shared shell / navigation | P0 | Discoverability, consistent top-level destinations, Back behavior |
| 2 | Donor map | P0 | Location trust, privacy controls, map state correctness |
| 3 | Home | P1 | Clear next action and role-specific hierarchy |
| 4 | Request creation / edit / cancel | P0 | High-impact workflow and data correctness |
| 5 | Matching results / active request | P0 | Sensitive data and changing emergency-like status |
| 6 | Donor onboarding / workspace | P0 | Consent, availability, eligibility, profile correctness |
| 7 | Messaging / contact sharing | P0 | Personal data and user safety |
| 8 | In-app notifications / deep links | P1 | Reliable status awareness without privacy leaks |
| 9 | Profile / security / settings | P1 | Account control and session lifecycle |
| 10 | Privacy Policy / Terms / privacy requests | P0 before public release | Transparency, rights, accurate disclosures |
| 11 | Shared components / accessibility / visual consistency | Continuous | Reduce UI drift and regression risk |

## First concrete audit findings

- **Navigation:** the compact layout's map action has only an icon on the screen; its accessibility content description is present, but visible text is missing. Wider layout presents Map separately in the rail. Standardize this before polishing individual cards.
- **Map center:** the screen uses the first filtered donor area, otherwise the first area, otherwise the passed requester-draft coordinates, otherwise a hard-coded Manila coordinate. This is a fallback center, not a device GPS fix. The UI must not imply otherwise.
- **Empty-state wording:** “The map stays active while we search” can imply continuous searching even though the screen requests `LoadMap` on entry and refresh is user-triggered. Replace with wording that accurately describes when donor areas appear.
- **Map interaction:** filters, privacy controls, refresh, loading, error, and empty states exist, but their combinations, small-screen overflow, map lifecycle/duplicate markers, stale data, and TalkBack behavior need targeted tests.
- **Privacy notice:** the current draft appropriately calls itself subject to operator/legal review, but it still describes a support channel and processor practices generically. Confirm actual operator/contact, data flows, retention/deletion, legal bases, and rights handling before release.

## Change log

- 2026-10-24: initial evidence-backed audit and staged redesign checklist. No application behavior or backend contract changed in this document-only step.
