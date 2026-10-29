# LifeLink Client UX, Feedback, and Feature Batch Plan

**Repository:** `RamProjector/LifeLink`  
**Integration target:** the existing consolidated client reliability PR, [PR #153](https://github.com/RamProjector/LifeLink/pull/153).  
**Plan status:** researched acceptance plan; items marked “planned” are not claims of implementation.  
**Design rule:** deliver the related work as one coherent PR, not a chain of tiny PRs. Keep changes on the same integration branch until the feature batch is reviewable, then validate the final head commit.

## Current branch implementation snapshot

The following feedback-system slice has been added to PR #153:

- [x] Added `LifeLinkFeedbackCard` with Material 3 success/info/warning/error treatments, optional action, and accessibility live-region/error semantics.
- [x] Replaced the donor map's bespoke error card with the shared component and a retry action.
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
