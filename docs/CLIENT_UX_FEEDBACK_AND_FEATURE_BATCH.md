# LifeLink Client UX, Feedback, and Feature Batch Plan

**Repository:** `RamProjector/LifeLink`  
**Integration target:** the existing consolidated client reliability PR, [PR #153](https://github.com/RamProjector/LifeLink/pull/153).  
**Plan status:** researched acceptance plan; items marked “planned” are not claims of implementation.  
**Design rule:** deliver the related work as one coherent PR, not a chain of tiny PRs. Keep changes on the same integration branch until the feature batch is reviewable, then validate the final head commit.

## Current branch implementation snapshot

The following feedback-system slice has been added to PR #153:

- [x] Added `LifeLinkFeedbackCard` with Material 3 success/info/warning/error treatments, optional action, and accessibility live-region/error semantics.
- [x] Replaced the donor map's bespoke error card with the shared component and a retry action.
- [x] Added an explicit “Refresh areas” alternative when the map has no returned donor areas; filtered-empty results continue to offer “Clear filters.”
- [x] Kept the native map viewport more visible by anchoring the no-area overlay near the bottom; added a UI regression test for the map, empty-state explanation, and refresh action.
- [x] Prevented rapid duplicate emergency-request submissions with an atomic in-flight guard; unexpected repository exceptions now produce a safe retry message, and a gated unit test verifies one network call for repeated submit actions.
- [x] Restored the most recently saved, account-scoped requester draft from Room when no active request exists; accepted/manual-fallback submissions remove the local draft so completed requests are not reopened as unfinished work.
- [x] Added ViewModel regression coverage for restoring a saved draft and deleting the local draft after a successful submission.
- [x] Added Compose instrumentation tests for visible error copy, retry callback behavior, and a success state without an action.
- [ ] The broader message catalog and adoption across requester submission, donor availability, chat, session recovery, and web are still follow-up work within the same consolidated feature batch.
- [ ] These new tests and the full emulator visual evidence must pass on the final head commit before the feature batch can be called validated.

## Purpose

LifeLink supports emergency blood-donor discovery. The interface must make the current state, next action, and any failure clear without implying that donor availability, GPS, matching, or notifications are guaranteed. The app should be calm, direct, accessible, privacy-preserving, and usable under time pressure.

- [x] Give donor-area list cards an explicit 56 dp minimum height and a descriptive Button action semantic; assert the actual card bounds in the donor-area navigation instrumentation test. The app-wide touch-target audit remains open.

## Research basis

1. **Android saved UI state and durable application data** — ViewModels alone do not survive system process death; persistent local storage is appropriate for complex application data. This implementation restores only the most recently saved account-scoped draft when there is no non-terminal active request.  
   https://developer.android.com/topic/libraries/architecture/saving-states
2. **HTTP idempotency guidance** — a retry must reuse the same key for the same intent and must not reuse that key with a different payload. LifeLink's draft ID remains the submission key while the draft is being recovered.  
   https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header
3. **Android Compose accessibility semantics** — announce meaningful changes, use headings and error semantics, and reserve assertive announcements for urgent errors; frequent state changes should not repeatedly interrupt users.  
   https://developer.android.com/develop/ui/compose/accessibility/semantics
2. **Android accessibility quality** — provide reliable touch targets (at least 48 dp for interactive controls), meaningful descriptions, scalable text, and manual assistive-technology checks.  
   https://developer.android.com/guide/topics/ui/accessibility/apps
3. **Android touch-target implementation** — custom Compose controls should expose a minimum 48 dp interactive area; tests should inspect the actual clickable container bounds, not just the visible icon or label.
4. **OWASP Logging Cheat Sheet** — log security-relevant successes/failures, validation errors, authorization denials, application errors and privileged actions with enough context for investigation; exclude passwords, tokens, session secrets, and unnecessary sensitive personal data.  
   https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
5. **OWASP DevSecOps logging guidance** — structured event names, timestamps, and correlation identifiers improve cross-service investigation; protect logs and avoid recording secrets or unnecessary personal information.  
   https://owasp.github.io/DevSecOpsGuideline/2-Process/2-7-Operate/2-7-2-Logging-and-Monitoring/

These sources guide interaction and observability, not clinical suitability or blood-donor eligibility. LifeLink must not claim to medically screen or guarantee a match.

## UI feedback design system

Use a consistent feedback hierarchy across Android and web. Avoid showing the same failure simultaneously as a modal, banner, toast, and raw exception.

| State | Display | Required content | Action / behavior |
|---|---|---|---|
| Working | Inline progress indicator next to the task or within the affected panel | What is loading, e.g. “Refreshing donor areas…” | Disable only the action that would duplicate the request; keep unrelated navigation usable. |
| Success | Brief non-blocking status message near the changed control or a polite live region | What actually succeeded, e.g. “Availability updated.” | No modal. Do not say “sent” or “saved” until the server confirms it. |
| Recoverable warning | Persistent inline notice in the relevant panel | What is limited and what remains available | Offer a clear next step; preserve entered data. |
| Validation error | Field-level message beside the invalid field, with focus/summary for forms when needed | What is wrong and how to correct it | Keep the user's other valid inputs; do not rely on color alone. |
| Recoverable operation error | Inline error card with concise cause and a retry action | What failed, safe reason, and whether changes were saved | Retry must repeat only the failed operation and must not create duplicate requests. |
| Session / authorization error | Contextual sign-in or permission explanation | Whether the session expired or access is not allowed | Provide a safe recovery path; never reveal internal permission rules or stack traces. |
| Empty state | Plain-language explanation in the content area | Why the list is empty: no results, no opt-in areas, or filters excluded all results | Provide a relevant action (clear filters, refresh, or setup guidance) only when valid. |
| Offline / degraded service | Persistent, dismissible service status plus per-action outcome | Whether cached content is stale and whether an action reached the server | Never imply an offline request was delivered. Retry must be explicit and safe. |

### Message-writing rules

- Lead with the outcome: “Couldn’t refresh donor areas,” not “Error 500.”
- Follow with a useful, non-blaming reason when known: “Check your connection and try again.”
- Use a verb for the action: “Retry refresh,” “Allow location,” “Review filters.”
- Distinguish **device location**, **location shared with LifeLink**, and **approximate public donor areas**. Do not call the map “GPS tracking.”
- Never show raw exception text, SQL errors, tokens, request headers, or private coordinates in user-facing messages.
- Use assertive screen-reader announcements only for urgent events. Routine success/loading messages should be polite or static to avoid interruption.
- Preserve entered form data after network failures. Prevent double-submit while a request is in flight and make retries idempotent where possible.
- Do not fabricate success, donor counts, map pins, delivery/read receipts, or backend health. Demo data must be explicitly labeled.

## Consolidated implementation batch (8 tasks)

The following are acceptance tasks for this one integration PR. A checkbox is marked complete only after implementation and validation on the final branch head.

### 1. Shared feedback components and message catalog
- [ ] Standardize success, informational, warning, error, loading, offline, and empty states.
- [ ] Reuse Material 3 colors and typography; include title, plain-language message, optional primary action, and appropriate accessibility semantics.
- [ ] Add message keys or a centralized mapping for common network, session, permission, GPS, validation, and rate-limit failures.
- [ ] Never surface raw backend exception messages directly.

**Acceptance:** same state has the same visual hierarchy and wording on Android; actions are reachable by TalkBack and keyboard; tests assert state text and action.

### 2. Requester emergency-request workflow
- [ ] Use a clear step order: request details → required location → review → submit → confirmed server outcome.
- [ ] Show field-specific validation, a review summary, a submission progress state, and a recoverable failure state.
- [ ] Preserve input on failure and protect against repeated taps / duplicate submissions.
- [ ] Clearly distinguish draft, submitted, accepted/contacted, expired, and failed states; only use states supported by server responses.

**Acceptance:** valid and invalid form tests; failed submission preserves fields; repeated taps do not create duplicate requests.

### 3. Donor onboarding and availability feedback
- [ ] Explain why location is needed, request permission at the relevant moment, and provide a settings path if permission is denied.
- [ ] Show capture progress, accuracy/freshness where meaningful, save confirmation, and a clear “not shared” state before consent.
- [ ] Separate donor preference (willing/paused) from GPS freshness and server-confirmed effective availability.
- [ ] Make opt-in, pause, and resume outcomes explicit; do not silently re-enable a donor who paused.

**Acceptance:** permission-denied, location-disabled, stale fix, server-save failure, paused, and successful states have distinct tests and user-facing recovery.

### 4. Donor map, filters, and empty states
- [ ] Keep the map available even when no donor areas are returned; show a useful empty overlay rather than an unexplained blank map.
- [ ] Distinguish “no opt-in donor areas nearby,” “no areas match these filters,” “map still loading,” and “map request failed.”
- [ ] Keep pins approximate and anonymous; show freshness without exposing exact coordinates or identities.
- [ ] Make list/map switching, filter reset, refresh, and privacy controls consistent and accessible.

**Acceptance:** deterministic fixtures cover loading, populated, filtered-empty, no-area, stale, and failed map states; screenshots are required for key states.

### 5. Chat and notification feedback
- [ ] Define sending, sent, failed, retrying, unread, reconnecting, and unavailable states based only on real service responses.
- [ ] Make failed message retry explicit and prevent accidental duplicate sends.
- [ ] Route notification taps to the relevant authorized conversation/request, with a safe fallback for expired or unauthorized destinations.
- [ ] Distinguish notification permission disabled from token registration failure and actual delivery; do not promise delivery without provider evidence.

**Acceptance:** send failure/retry, reconnect, unread count, token registration failure, and notification deep-link fallback are tested.

### 6. Session, permissions, and access-denied recovery
- [ ] Explain expired sessions, denied permissions, forbidden actions, rate limits, and service outages separately.
- [ ] Keep the user’s unsaved work when re-authentication is possible.
- [ ] Do not offer a retry loop for a permanent authorization denial; route users to a legitimate next step.
- [ ] Admin access remains allowlisted/server-authorized; no public role escalation or client-only admin gating.

**Acceptance:** HTTP 401/403/409/429/5xx mapping tests; unauthorized actions are rejected by the server regardless of client navigation.

### 7. Safe diagnostics and audit correlation
- [ ] Use structured event names and a request/interaction correlation ID across Android and FastAPI where feasible.
- [ ] Record operation outcome, category, timestamp, and safe correlation ID; log authorization denials and privileged changes server-side.
- [ ] Redact tokens, session identifiers, messages, blood/health details, precise GPS, and unnecessary personal data.
- [ ] Keep debug diagnostics debug-only and non-public; bound file size/retention and document export/deletion behavior.

**Acceptance:** tests verify redaction and correlation propagation; a diagnostic sample contains no secrets or precise location; logging failure does not crash the user workflow.

### 8. Accessibility, visual regression, and release evidence
- [ ] Verify 48 dp minimum interactive targets, meaningful labels, semantic headings, error announcements, contrast, font scaling, and insets.
- [x] Give donor map visibility switch rows an explicit 56 dp minimum height and add an instrumentation assertion against the actual clickable row bounds; the rest of the app-wide touch-target audit remains open.
- [x] Give the donor-area details “Back to map” action an explicit 48 dp minimum height; instrumentation verifies its target and performs the actual in-app return action. The wider navigation-control audit remains open.
- [x] Announce the donor-map filter result count as a polite live region and assert that accessibility semantic in the workflow instrumentation test, so changes are not visual-only for screen-reader users.
- [x] Show a compact Material 3 count badge on the map filter control while filters are active; include the active-filter count in its accessible name and test the selected, collapsed, reopened, and cleared states.\n- [x] Use singular/plural-aware donor-area counts in both the filter summary and map footer; the deterministic workflow test verifies “1 matching area” and “1 area” rather than the grammatically incorrect plural form.
- [ ] Add or extend Compose UI tests for feedback states and the main requester/donor journeys.
- [ ] Capture required screenshots for home, request form, map (loading/populated/empty/error), chat, and profile/availability where the harness can reliably render them.
- [ ] Keep the fake-backend APK isolated from production configuration and label all synthetic data.
- [ ] Publish a final validation matrix with passed/failed/skipped status; never report an emulator or live-service test as passed if it did not run.

**Acceptance:** final-head build, lint, unit/UI tests, backend tests, security scan, CodeQL, and visual evidence all have a recorded result; no merge with required checks failing.

## Implementation order

1. Shared feedback components and message catalog.
2. Wire the component into the highest-risk, highest-traffic screens: donor map and donor availability first; then requester submission and chat.
3. Add tests alongside each flow, not as an afterthought.
4. Update this checklist as each task is completed; do not mark a task complete based on documentation alone.
5. Run the full final-head CI matrix and inspect the screenshot artifacts before marking the PR ready.

## Related open pull requests

- **PR #153** is the existing consolidated implementation PR for donor GPS freshness, privacy-safe map behavior, navigation reliability, diagnostics, and regression coverage. Keep related UX/feedback work on that branch to avoid competing, fragmented PRs.
- Dependabot dependency PRs should remain independent because each changes a different dependency and can have a different compatibility/risk profile. Do not combine unrelated version bumps into this product-feature PR just to reduce PR count.
- For any overlapping client/map PRs, compare the diff against #153 and close or redirect duplicates only after confirming their unique changes have been carried over. Do not close dependency PRs automatically.

## Research links

- Android Compose accessibility semantics: https://developer.android.com/develop/ui/compose/accessibility/semantics
- Material 3 badges are compact indicators for counts and status on related controls: https://m3.material.io/components/badges/overview
- Android Compose minimum touch targets and action semantics: https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- Android accessibility principles and descriptive action labels: https://developer.android.com/guide/topics/ui/accessibility/principles
- Android accessibility app quality: https://developer.android.com/guide/topics/ui/accessibility/apps
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- OWASP DevSecOps logging and monitoring: https://owasp.github.io/DevSecOpsGuideline/2-Process/2-7-Operate/2-7-2-Logging-and-Monitoring/

## Failure-mode and performance hardening register (cross-client + API)

This register is a risk inventory and acceptance checklist, not a claim that every mitigation is already implemented. For each row, keep the UI outcome truthful, the server authoritative, and a regression test beside the code. Do not hide failures by disabling checks or silently falling back to fabricated data.

| Failure mode / trigger | Android + web countermeasure | API / data countermeasure | Regression evidence required |
|---|---|---|---|
| Offline, DNS failure, captive portal, TLS/certificate error | Bounded wait; persistent offline/degraded indicator; preserve form inputs; retry only on user action or a safe bounded policy | Keep TLS verification; distinguish connection failure from HTTP errors; no production HTTP fallback | Offline, DNS/TLS failure, recovery, and no-false-success tests |
| Slow API, stalled connection, server overload | Per-operation timeout; cancel obsolete searches; loading state with recovery action; don't block main thread | Explicit request/DB timeouts; bound worker and pool queues; return 429/503 with safe retry guidance | Slow-response, cancellation, timeout, and overload tests |
| Duplicate taps, replay, retry after lost response | Disable only the submitting control while in flight; keep draft; show “outcome unknown” when server response is lost | Idempotency keys for request creation and side effects; unique constraints/transactional writes; reject replay where appropriate | Concurrent duplicate submit and retry-after-timeout tests |
| Expired/invalid JWT, refresh failure, revoked session | Single controlled refresh attempt; if still unauthorized, clear protected data and route to sign-in; avoid retry loops | Verify JWT issuer/audience/signature/expiry server-side; enforce authorization on every object/action; never trust client role | Expired, revoked, refresh-failed, and cross-user access tests |
| 403 / role mismatch / guessed object ID | Explain access is unavailable without exposing existence-sensitive details; safe back/navigation | Enforce owner/role checks server-side for requests, chat, donor presence, admin APIs; deny by default and audit privileged actions | Horizontal/vertical privilege escalation tests |
| Invalid, missing, stale, inaccurate, mocked, or disabled GPS | Explain device permission vs device location services vs consent to share; reject stale/poor fixes; keep map usable with honest empty/error overlay | Validate coordinate bounds and accuracy; server timestamps freshness; do not treat client GPS as proof of authenticity; rate-limit updates; avoid exact public location | Permission denial, location off, stale fix, invalid coords, low accuracy, spoof caveat tests |
| Donor pauses, signs out, app backgrounds, OS kills process | Never silently re-enable; foreground-only refresh; persist user preference; separate preference from effective matchability | Server-side freshness expiry and eligibility gate; explicit pause/offline state wins; expire tokens/presence safely | Pause/resume, process death, stale expiry, concurrent update tests |
| Map slow, provider unavailable, bad tiles, no pins, filters exclude all | Separate loading, render failure, no-area, filter-empty, and stale states; retry/clear-filter actions; no endless spinner | Bound geospatial query and result count; validate filter/radius; avoid leaking exact donor coordinates; stable pagination | Deterministic fixture screenshots for every map state; query-limit tests |
| Requester form invalid or request creation partly fails | Field-level errors, review summary, preserve values, accessible error announcement; only show confirmed server result | Server-side schema/business validation; atomic transaction for request + related records; idempotency; never rely on client validation | Invalid input, partial failure, double-submit, lost response/reload tests |
| Chat send fails, websocket disconnects, reconnect duplicates | Distinguish pending/sent/failed; preserve draft; retry specific message; deduplicate by client message ID; show reconnect status | Authenticated conversation membership; server message IDs; idempotent send; bounded reconnect/backoff; server-derived unread counts | Offline send, reconnect, duplicate delivery, unauthorized conversation tests |
| Push permission denied, token stale, provider unavailable, tap target expired | Separate permission from registration and delivery; don't claim delivery/read; safe destination fallback | Validate token ownership; rotate/remove stale tokens; retry transient provider errors; no sensitive data in push payload; authorize deep link on open | Token registration failure, expired target, duplicate push, unauthorized tap |
| Database unavailable, pool exhausted, slow query, deadlock | Safe retry only for idempotent reads/writes; show service unavailable; keep navigation responsive | Pool limits, statement/transaction timeouts, indexes, transaction boundaries, health/readiness checks, bounded retry with jitter; no infinite retry | DB outage, pool exhaustion, lock contention, rollback/consistency tests |
| Concurrent profile/presence updates or migration mismatch | Display server-confirmed state and refresh after conflict; don't optimistically claim persistence | Transactional update and constraints; additive/idempotent migrations; deployment compatibility and rollback plan; no destructive production migration without approval | Concurrent update, migration twice, old/new schema compatibility tests |
| Malformed JSON, missing fields, unexpected enum/status, oversized payload | Safe parse failure and recovery; unknown enum maps to safe unavailable state; no crash on partial responses | Strict schemas, max body sizes, explicit API versions, consistent error envelope, safe error detail | Malformed/empty response, unknown enum, oversized body tests |
| Rate limit / abuse / brute force / scraping | Explain cooldown; preserve draft; avoid rapid retry loops | Rate limits per account/IP/action; pagination caps; input bounds; abuse monitoring; authorization before sensitive lookup | 429 handling, retry-after, enumeration and burst tests |
| Memory pressure, large lists, repeated recomposition, leaked collectors | Paging/lazy lists, stable keys, debounce search, lifecycle-aware collection, cancel work on navigation, avoid main-thread I/O | Pagination and bounded response sizes; avoid N+1 queries; index hot paths; cache only safe non-sensitive data | Large-list scrolling, repeated navigation, leak/recomposition and query-count checks |
| Main-thread blocking, huge JSON, image/tile churn, excessive polling | Keep I/O off main thread; debounce; cache tiles appropriately; lifecycle-aware polling; cancel obsolete requests | Bound payload/query size and response work; no unbounded polling or expensive synchronous fan-out | Strict-mode checks, scroll/jank profiling, large payload and cancellation tests |
| Storage full/permission denied/log write failure | Diagnostics best-effort and never crash app; explain log location only when written; cap/rotate file | Server logs have rotation/retention/access controls; never log credentials, bodies, precise GPS, or sensitive headers | Storage-full and provider-failure tests; secret-marker absence assertion |
| Crash, coroutine exception, app/process restart | Catch expected errors at boundaries; global handlers are last-resort diagnostics, not recovery; restore safe navigation/drafts | Correlation IDs and structured event names; no secrets in traces; alert on actionable error rates | Crash-path instrumentation, process recreation, log redaction tests |
| Accessibility, font scaling, screen reader, keyboard, touch errors | 48dp targets, labels/semantics, contrast, scalable text, visible focus, polite announcements; no color-only meaning | Error messages use stable safe codes clients can localize and explain | Compose/UI semantics tests plus TalkBack/keyboard/manual font-scale review |
| Emulator/CI instability or missing evidence | Retry infrastructure only when safe; preserve logs/artifacts; distinguish test failure from infrastructure failure; screenshots required for visual claims | Keep CI gates strict; production behavior must not depend on emulator; deterministic fixtures | Final-head workflow URLs, non-empty required screenshots, logcat and boot evidence |
| Privacy leakage, coordinate inference, log/push exposure | Approximate anonymous map areas; no hidden data in accessibility labels, analytics, screenshots, or diagnostics | Server-side minimization, authorization, retention limits, no precise public coordinates; secrets filtered from logs and errors | API response privacy assertions, screenshot/label inspection, synthetic secret-marker tests |

### Cross-cutting implementation rules

- Treat network outcomes as confirmed success, confirmed rejection, or **unknown outcome** (for example, a request reached the server but the response was lost). Do not present an unknown outcome as failure if retry could duplicate a side effect; reconcile with an idempotency key or fetch the authoritative resource.
- Apply bounded timeouts, bounded retries with jitter only for transient and idempotent operations, cancellation, and backpressure. Do not retry validation/authorization failures or non-idempotent actions without an idempotency contract.
- Validate at both client and server; server validation and authorization are authoritative. Use database constraints and transactions to protect invariants under concurrent requests.
- Keep diagnostic messages structured and redacted. User-facing messages must never copy arbitrary server detail, SQL errors, exception messages, request headers, or provider payloads.
- Track a correlation/request ID across client-safe error events and backend logs where supported. It must be random/non-sensitive and must not contain user IDs, tokens, or coordinates.
- Instrument performance with bounded metrics (request duration/status, DB latency, queue depth, crash/ANR and UI jank signals) while excluding personal content. Define actionable thresholds before alerting; do not collect extra sensitive analytics by default.
- For each touched flow, require tests for happy path, invalid input, authorization denial, offline/timeout, retry/duplicate, empty state, lifecycle interruption, and privacy redaction where applicable. Keep deterministic fake-backend tests separate from live-service claims.
- Use the final PR head for every merge decision. A green earlier commit, successful APK compilation, or passing fake backend does not substitute for final-head backend tests, emulator screenshots, or real-device GPS/FCM evidence.


Additional research for live announcements:
- Compose accessibility API defaults and live-region semantics: https://developer.android.com/develop/ui/compose/accessibility/semantics
- Compose accessibility guidance for logically structured and understandable UI: https://developer.android.com/guide/topics/ui/accessibility/composables

### Donor-map filter feedback and reset behavior

- Keep the matching-area count visible whenever the filter panel is open, including the unfiltered state. This gives users a baseline before changing a filter and keeps the live-region announcement target consistently present.
- Expose the count as a polite live region so filter changes are announced without interrupting the current task.
- Show “Clear filters” only while at least one filter is active; after clearing, restore the unfiltered count and hide the now-unnecessary action.
- Instrumentation verifies the initial count, an availability-filtered count, reset to the full count, and disappearance of the clear action.

Research:
- Android Compose semantics guidance explains how accessibility services consume semantic labels, state, and actions: https://developer.android.com/develop/ui/compose/accessibility/semantics
- Android's Compose accessibility guidance recommends meaningful interactive semantics and logical UI structure: https://developer.android.com/guide/topics/ui/accessibility/composables
- Compose's merging/clearing guidance explains why semantic information should remain available to accessibility services and test tooling: https://developer.android.com/develop/ui/compose/accessibility/merging-clearing


## Additional research-backed improvements — 30 October 2026

This section extends the existing acceptance plan. These are implementation priorities, not claims that the entire audit is complete.

### A. Contextual accessibility for map overlays

- [x] Give the donor-map filter overlay a semantic pane title ("Donor map filters") so assistive technology can identify the temporary surface independently from the underlying map.
- [x] Enforce an allowlisted public donor-map response contract in PostgreSQL integration tests: only approximate area, radius, blood type, availability, and freshness fields are permitted; unexpected identity/contact/exact-location fields fail the test.
- [x] Add an instrumentation assertion for the pane-title semantic.
- [x] Apply a semantic pane title ("Donor map privacy controls") to the privacy-controls overlay and assert it in the Android workflow instrumentation test.
- [ ] Verify focus order when opening/closing overlays with TalkBack and keyboard navigation; ensure the underlying map controls do not become confusing duplicate stops.

**Why:** Android Compose documents `paneTitle` for window-like components such as modal sheets, helping accessibility services distinguish a new pane from surrounding content. The filter and privacy overlays sit above the map and change the active interaction context. Both pane titles are asserted in instrumentation tests; focus order and dismissal behavior with TalkBack/keyboard remain manual validation items.

### B. Security and privacy hardening (OWASP MASVS mapping)

- [ ] **AUTH:** verify every protected API operation authorizes the authenticated subject and relationship server-side; never trust role, donor ID, requester ID, or request ownership supplied by the client.
- [ ] **NETWORK:** verify release builds use HTTPS only, reject cleartext traffic, and do not log authorization headers or response bodies containing personal data.
- [ ] **STORAGE:** inspect Room, preferences, files, backups, exported diagnostics, and notification payloads for tokens, exact coordinates, contact details, and emergency-request data; define retention and deletion.
- [ ] **PRIVACY:** test location opt-in, approximate public display, request-scoped exact sharing, expiry, and revocation against the server—not merely local UI state.
- [ ] **CODE / RESILIENCE:** verify debug-only diagnostics and demo/fake-backend entry points cannot be enabled in release builds; scan packaged APKs and merged manifests.
- [ ] **AUDIT:** record security-relevant outcomes with correlation IDs while redacting credentials, session tokens, precise GPS, message content, and unnecessary personal details.

**Acceptance:** create an endpoint-by-endpoint authorization matrix (actor × resource × operation), add negative tests for cross-account and guessed-ID access, inspect release manifest/network config, and prove sensitive values are absent from logs and exported diagnostics.

### C. Functional correctness and user convenience

- [ ] Request submission: field-level required/optional rules, review summary, persisted draft, idempotency key or equivalent server duplicate protection, and clear submitted-vs-queued-vs-failed outcomes.
- [ ] Donor availability: separate willingness, verification, GPS permission, location freshness, and effective matchability in both server responses and user-facing copy.
- [ ] Chat: explicit sending/sent/failed/retrying states, safe retry without duplicate message creation, reconnect behavior, unread count, and notification deep-link authorization fallback.
- [ ] Session recovery: distinguish expired session (401), forbidden action (403), conflict (409), rate limit (429), and transient server/network failure; do not offer endless retry for permanent denial.
- [ ] Forms: preserve valid user input after network failure and restore drafts after process recreation where appropriate; confirm before discarding meaningful unsaved work.
- [ ] Status truthfulness: never imply that a donor was notified, a message delivered/read, a location shared, or a request accepted unless the server/provider confirms that exact outcome.

### D. Performance and efficiency — measure before optimizing

- [ ] Add a reproducible Macrobenchmark for cold start, Home → Request, Home → Map, and map/list navigation on a stable emulator profile.
- [ ] Capture frame timing / jank traces for donor-area list scrolling and map/filter interactions; compare p50/p90 and frame deadline misses before and after changes.
- [ ] Keep map style/network initialization separate from annotation updates. Filtering should update markers in place and must not reset camera position or reload the style; retain a regression check for this contract.
- [ ] Profile Compose recomposition and list scrolling before adding caching or derived-state complexity. Keep stable LazyColumn keys for donor areas and avoid expensive work inside item composition.
- [ ] Evaluate Baseline Profiles only after critical user journeys are stable and benchmarked; measure release-like builds rather than treating a successful build as a performance result.
- [ ] Use lifecycle-aware collection and cancel polling/location work when the screen is stopped or the donor is no longer explicitly available.

**Acceptance:** publish baseline and post-change measurements, include device/API/build mode and test scenario, and reject optimizations that trade correctness, privacy, or readability for an unmeasured theoretical gain.

### E. Visual/HCI quality gate

- [ ] Review all core screens at compact width and large font scale; ensure no clipped actions, overlapping map overlays, or inaccessible horizontal filter chips.
- [ ] Check 48×48 dp minimum touch targets and practical spacing for every custom interactive control; verify actual hit bounds rather than icon size.
- [ ] Use one clear primary action per screen, keep secondary actions visually quieter, and avoid stacking multiple feedback surfaces for one event.
- [ ] Provide visible selected states and equivalent semantics; do not communicate availability or urgency by color alone.
- [ ] Test loading, no-data, filtered-empty, stale, offline, error, and success states independently with deterministic fixtures and truthful copy.

### Donor revocation is final for one request

- Exact-location revocation is now a one-way decision for the specific emergency request/donor pair. A requester cannot reactivate that share while the donor's account-level opt-in remains enabled.
- A new matched emergency request is a separate consent scope and can establish its own share if the donor's sharing preference still permits it.
- Regression coverage asserts that reactivation returns HTTP 409 and the exact-location response remains unshared with null coordinates.
- Rationale: revocation must be an effective user control, not a temporary pause that another participant can reverse. This implements OWASP MASVS-PRIVACY-4 (user control, including revoking consent) and MASVS-PRIVACY-1 (informed consent and minimization).

### Exact donor GPS authorization regression

- Added a PostgreSQL-backed API regression test proving that an active exact-location share is readable by the request's requester only.
- The test verifies an unrelated account cannot activate a share or retrieve exact GPS using known request/donor identifiers; the donor account also cannot retrieve the requester's exact-location response. These calls must receive HTTP 403, while the authorized requester can activate and read the share.
- This guards against broken object-level authorization (BOLA/IDOR) and prevents identifier knowledge from becoming an authorization mechanism.

### Research references

- Android Compose semantics and pane titles: https://developer.android.com/develop/ui/compose/accessibility/semantics
- Android Compose API defaults and 48 dp minimum touch targets: https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- Android touch target guidance and accessibility testing: https://support.google.com/accessibility/android/answer/7101858
- OWASP MASVS control groups (authentication, network, storage, privacy, code, resilience): https://mas.owasp.org/MASVS/
- OWASP DevSecOps mobile security testing checklist: https://owasp.github.io/DevSecOpsGuideline/2-Process/2-4-Test/2-4-3-Mobile-Application-Security-Test/
- Android UI jank diagnosis and system traces: https://developer.android.com/studio/profile/jank-detection
- Android slow-rendering guidance: https://developer.android.com/topic/performance/issues/render
- Android Baseline Profiles and performance optimization: https://developer.android.com/topic/performance/baselineprofiles

## Donor GPS consent transition concurrency guard (2026-10-30)

- Activation, donor revocation, and donor map/exact-location opt-out now serialize on the donor row with PostgreSQL `SELECT ... FOR UPDATE`. This also protects the first activation, where a location-share row does not yet exist to lock.
- The repository re-checks both `map_visible` and `exact_location_sharing_enabled` after obtaining the lock. A stale route-level pre-check can no longer activate sharing after the donor has opted out.
- A request/donor share already marked `revoked` is rejected inside the repository as well as at the API boundary. The API maps both repository consent conflicts to HTTP 409.
- The donor's map visibility update takes the same row lock before revoking active shares, giving concurrent activation/opt-out operations a deterministic order: if activation wins first, opt-out subsequently revokes it; if opt-out wins first, activation is rejected.
- PostgreSQL integration coverage now checks that direct repository calls cannot reactivate a revoked share or activate while map visibility is off. The existing map-hiding test also had an undefined stranger fixture; it now creates that user explicitly so the authorization assertion is executable.
- Research basis: OWASP Business Logic Security guidance says check-then-act operations must be atomic and identifies row locking as a safe read-modify-write pattern. PostgreSQL documents that `FOR UPDATE` blocks conflicting writers/lockers until transaction end. References: https://cheatsheetseries.owasp.org/cheatsheets/Business_Logic_Security_Cheat_Sheet.html and https://www.postgresql.org/docs/current/explicit-locking.html.
- The locked donor-row SELECT uses SQLAlchemy `populate_existing=True` so the lock acquisition refreshes an already-loaded ORM identity-map object; without this, the route's earlier donor lookup could leave stale opt-in flags in memory. A PostgreSQL regression test now loads stale donor state in one session, commits opt-out in another, and verifies activation is denied with no share row created. Research: https://docs.sqlalchemy.org/en/20/orm/queryguide/api.html.

### Atomic donor GPS consent audit events (2026-10-30)

- [x] Persist exact-location activation and its `location_share_activated` audit event in the same database transaction.
- [x] Persist donor revocation and its `location_share_revoked` audit event in the same database transaction.
- [x] Attribute activation to the authenticated requester and revocation to the authenticated donor.
- [x] Keep audit metadata free of coordinates and contact data; PostgreSQL integration coverage asserts actor, action, request, donor, and empty metadata for both lifecycle events.
- [ ] Extend the same atomic audit pattern to other high-risk state transitions after reviewing their transaction boundaries and failure semantics.

**Why:** Previously, the share state committed before a separate audit commit. If audit persistence failed between those commits, the API could leave a live or revoked share without its corresponding audit event. The two GPS consent transitions now commit state and evidence together, so either both persist or neither does. This is a targeted guarantee for these transitions, not a claim that every LifeLink audit path is already atomic.

**Research:** OWASP Logging Cheat Sheet recommends capturing who/what/when and affected objects for security events, while excluding sensitive personal data and secrets. It also recommends testing logging behavior and its side effects. https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html



### Privacy audit transaction integrity (implemented on PR #153)
- Map visibility changes, any resulting exact-location share revocations, and the `donor_map_visibility_updated` audit event are now written in one database transaction.
- A PostgreSQL integration test installs a temporary trigger that rejects this audit event, attempts a donor opt-out, and verifies that the donor's consent flags and active share both remain unchanged and no audit row is committed.
- This prevents a successful-looking privacy update from being left without its corresponding audit evidence if audit persistence fails.
- Research: [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html), [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html), and [OWASP MASVS-PRIVACY-4](https://mas.owasp.org/MASVS/controls/MASVS-PRIVACY-4/).
- Validation is pending on the final commit; implementation and test coverage must not be described as CI-verified until the PostgreSQL workflow passes.


## CI follow-up — audit-storage failure response contract (30 October 2026)

- **Observed failure:** the PostgreSQL privacy integration suite expected HTTP 500 when the audit insert failed during a donor map-visibility update; the API returned HTTP 503.
- **Root cause:** the shared SQLAlchemy exception handler intentionally maps database availability/storage failures to a retryable 503 response. The test expectation was stale; changing the API handler to 500 would make this transient dependency failure inconsistent with the established service-unavailable contract.
- **Change:** updated the atomicity regression test to expect 503 while retaining its database assertions that donor consent, active location share, and audit rows remain unchanged after the failed transaction.
- **Validation:** CI must rerun the PostgreSQL integration suite on the updated PR head. No production database, credentials, or deployed services were modified.
- **Remaining:** verify the test passes and confirm the emulator visual-review workflow completes before considering PR #153 ready for review.

## Idempotent request replay preserves the matching strategy (2026-10-31)

- Emergency-request creation persists the selected matching strategy (`v1-explainable-weighted` or `v1-distance-only`) with the request row instead of silently writing the weighted default.
- A retry with the same idempotency key therefore returns the original request and its original matching strategy, rather than a response that contradicts the initial successful response.
- The PostgreSQL end-to-end regression test disables AI matching, checks the initial response, replays the same payload, and verifies the same request ID, matching version, zero new notifications, and exactly one persisted request row.
- Research basis: the IETF Idempotency-Key Internet-Draft recommends consistent responses for completed duplicate requests and warns against reusing a key with a different payload. This document is an Internet-Draft, not a finalized RFC: https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08. HTTP retry safety is defined in RFC 9110: https://www.rfc-editor.org/rfc/rfc9110.html.
- Validation: awaiting PostgreSQL CI on the latest PR head; do not treat this regression as passing until the job completes.


## Idempotency-key payload binding (implemented on PR #153, 31 October 2026)

- Reusing an emergency-request idempotency key with the same persisted request intent returns the original request, preserving the matching strategy and reporting zero newly created notifications.
- Reusing that key with a materially different persisted intent now returns HTTP 422 instead of silently returning the earlier request. The comparison covers requester, blood type, units, urgency, deadline, facility ID, coordinates, location precision, contact method, note, consent confirmations, and matching preference.
- Mutable/display-only facility labels, area, and facility verification are excluded because they are hydrated from facility data and are not persisted as the original requester-supplied intent. The comparison deliberately uses only fields durably stored by the current schema; it does not claim to fingerprint arbitrary future payload fields.
- Added a PostgreSQL end-to-end regression test that creates a request, changes the units while reusing the same key, expects 422, and verifies only one request row exists.
- Research: [IETF Idempotency-Key Internet-Draft - uniqueness and fingerprints](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08) recommends that a key not be reused for a different payload and describes a client retry after completion separately from a concurrent request. This is an Internet-Draft, not a finalized RFC.
- Validation: commit `dd736f0` adds the regression test and `794405e` adds the API guard. CI must complete on the latest head before this change is considered verified. Concurrent duplicate serialization remains a separate follow-up; this change addresses completed-key reuse only.


## Concurrent emergency-request retry serialization (implemented on PR #153, 31 October 2026)

- The PostgreSQL request-creation endpoint now acquires a transaction-scoped advisory lock derived from the emergency-request idempotency key before checking for an existing request.
- Requests with the same key therefore serialize across API workers: the first transaction creates the request; a waiting retry reads and returns that committed request instead of racing into the unique constraint.
- The lock is transaction-scoped and is released automatically on commit or rollback. The database unique constraint remains as a final integrity guard.
- Added a PostgreSQL end-to-end test that submits the same payload concurrently and asserts both responses return the same request ID and exactly one row is persisted.
- Research: OWASP Business Logic Security recommends atomic check-then-act operations using transactions/row locks or conditional updates and explicitly warns that concurrent requests can exploit even very small race windows: https://cheatsheetseries.owasp.org/cheatsheets/Business_Logic_Security_Cheat_Sheet.html. The IETF Idempotency-Key Internet-Draft distinguishes completed retries from concurrent duplicates: https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08.
- Scope: the advisory lock is PostgreSQL-specific and belongs only in this PostgreSQL API path. It does not replace payload-intent comparison or the unique database constraint.
- Validation: the concurrent integration test and full CI must pass on the final PR head before this is considered verified.


## Requester submission reliability batch (implemented on PR #153, 31 October 2026)

This batch closes five related failure modes in the requester workflow without changing the request schema or server contract.

1. **Persist before network submission.** The ViewModel now writes the exact draft to the account-scoped Room repository before calling the API. If local persistence fails, the API is not called and the UI reports that the request was not submitted. This closes the window where a user could submit before the 500 ms debounced autosave had run.
2. **Freeze the payload while submitting.** Draft edits are ignored while the submission state is Submitting. The idempotency key (draft.id) therefore cannot be paired with a changing payload during an in-flight attempt.
3. **Preserve submission state across save races.** A pending autosave can no longer overwrite Submitting with Idle or replace an active submission with a stale save error. Manual save is ignored once submission has begun.
4. **Offer retry only when the error is retryable.** SubmissionState.Error now carries the repository's retryability decision. The snackbar offers Retry only for retryable errors; validation, authorization, and other permanent errors no longer trigger a misleading retry loop.
5. **Serialize selected-donor contact actions.** A ViewModel-level in-flight guard prevents rapid repeated contact taps from starting multiple contact requests. Selection is locked during the request, and the button exposes a sending state.

**Regression tests added:** exact local-save-before-submit ordering; local persistence failure prevents network submission; draft edits are ignored during an in-flight submission; non-retryable errors do not reissue the request; and repeated contact actions start only one request. The contact button also reflects its in-flight state.

**Research basis:**
- Android's state-saving guidance distinguishes transient ViewModel/saved state from persistent local storage and recommends persistent storage for application data that must survive process death: https://developer.android.com/topic/libraries/architecture/saving-states.
- OWASP Business Logic Security recommends explicit workflow enforcement, atomic operations, and treating concurrency as a real threat; it specifically warns that retries and check-then-act sequences must be designed around duplicate requests: https://cheatsheetseries.owasp.org/cheatsheets/Business_Logic_Security_Cheat_Sheet.html.
- The idempotency-key guidance used in the existing API implementation requires retrying the same operation with the same key and not reusing that key for a different payload: https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08 (Internet-Draft, not a finalized RFC).

**Scope boundary:** this is client-side reliability hardening, not proof that every submission survives uninstall/device loss. Room storage is local to the installed app and account; the server remains authoritative. Full process-death restoration still depends on the existing draft-loading/navigation path and must be validated separately. CI validation is pending on the final PR head.


## Requester workflow integrity batch (implemented on PR #153, 31 October 2026)

This batch hardens the existing requester flow without redesigning the screen or changing its navigation.

- [x] **Persist before submit:** cancel the pending debounced draft save and persist the exact draft snapshot before starting the network submission. If local persistence fails, do not send the request; show a non-retryable local-save error rather than implying that a request may have reached the server.
- [x] **Freeze the submitted payload:** ignore draft edits while a submission is in flight so the fields displayed by the form cannot drift away from the immutable payload already being sent.
- [x] **Retry only retryable failures:** carry retryability from the repository's HTTP/network classification into UI state. Show a Retry action only for HTTP 408, 429, 5xx, and recoverable network timeouts; do not encourage retries for permanent 4xx/authorization failures or local persistence failures.
- [x] **Serialize donor-contact actions:** atomically claim a contact-submission-in-flight state before the network call, block repeated actions and selection changes during that call, and release the state in finally on both success and failure.
- [x] **Prevent autosave completion from overwriting submission state:** asynchronous save completion updates the Saving state only if it is still current, avoiding a late save result replacing a newer submission state.
- [x] **Regression coverage:** add tests for persist-before-submit ordering, local-save failure preventing network side effects, editing during submit, non-retryable retry suppression, and duplicate contact-action suppression.
- [ ] Add Room-backed process-death restoration tests for a requester draft and verify the idempotency key remains stable for the same request intent after app recreation. This remains a follow-up; ViewModel-only state must not be described as process-death persistence.

**Why these changes:** An emergency request is a consequential, non-idempotent user action. The client must preserve the user's work, avoid submitting a different payload from the one currently shown, prevent accidental duplicate contact requests, and communicate whether a retry is safe. Retry policy is based on the existing API response classification, not a generic try-again message.

**Research basis:**
- Android's state-saving guidance distinguishes ViewModel memory (configuration changes) from saved state (system process death) and persistent storage (survives process death and screen dismissal). This is why durable draft recovery is tracked separately instead of overstating the guarantees of the current ViewModel: https://developer.android.com/topic/libraries/architecture/saving-states.
- Android's data-layer guidance recommends Room for data that must survive process death and needs queryable/partial updates: https://developer.android.com/topic/architecture/data-layer.
- OWASP mobile security guidance emphasizes server-side authorization, least privilege, secure local storage, and minimizing sensitive data exposure: https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html.
- OWASP MAS guidance treats production logging and data exposure as explicit mobile security risks; diagnostics must not include tokens, personal data, exact GPS, or request bodies: https://mas.owasp.org/MASWE/MASVS-STORAGE/MASWE-0005/.

**Validation:** The regression tests and formatting/build checks must pass on the final PR head. Emulator visual review currently has an infrastructure boot failure (ADB/emulator did not become ready within the workflow's 600-second window), so this batch is not visually CI-verified until that environment issue is resolved.
