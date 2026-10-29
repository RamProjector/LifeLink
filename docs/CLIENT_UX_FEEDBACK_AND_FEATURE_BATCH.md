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
- [x] Added Compose instrumentation tests for visible error copy, retry callback behavior, and a success state without an action.
- [ ] The broader message catalog and adoption across requester submission, donor availability, chat, session recovery, and web are still follow-up work within the same consolidated feature batch.
- [ ] These new tests and the full emulator visual evidence must pass on the final head commit before the feature batch can be called validated.

## Purpose

LifeLink supports emergency blood-donor discovery. The interface must make the current state, next action, and any failure clear without implying that donor availability, GPS, matching, or notifications are guaranteed. The app should be calm, direct, accessible, privacy-preserving, and usable under time pressure.

## Research basis

1. **Android Compose accessibility semantics** — announce meaningful changes, use headings and error semantics, and reserve assertive announcements for urgent errors; frequent state changes should not repeatedly interrupt users.  
   https://developer.android.com/develop/ui/compose/accessibility/semantics
2. **Android accessibility quality** — provide reliable touch targets (at least 48 dp for interactive controls), meaningful descriptions, scalable text, and manual assistive-technology checks.  
   https://developer.android.com/guide/topics/ui/accessibility/apps
3. **OWASP Logging Cheat Sheet** — log security-relevant successes/failures, validation errors, authorization denials, application errors and privileged actions with enough context for investigation; exclude passwords, tokens, session secrets, and unnecessary sensitive personal data.  
   https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
4. **OWASP DevSecOps logging guidance** — structured event names, timestamps, and correlation identifiers improve cross-service investigation; protect logs and avoid recording secrets or unnecessary personal information.  
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
