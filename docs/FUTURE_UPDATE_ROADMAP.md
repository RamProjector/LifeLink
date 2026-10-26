# LifeLink Future Update Roadmap

**Document status:** Living planning document  
**Prepared:** 2026-10-26  
**Applies to:** Android app, web admin console, FastAPI backend, PostgreSQL/Supabase schema, Render deployment, CI, and documentation.

## Purpose and decision rules

This roadmap turns repository knowledge and product questions into a sequenced, testable plan. It is not a claim that every listed feature is already implemented or production-ready.

Before each feature, inspect the default branch, open pull requests, CI, API contract, schema/migrations, Android screens, web UI, and tests. Re-check this roadmap against the code because implementation status changes. Prefer focused, reviewable pull requests. Do not change the stack or add paid dependencies without approval. Do not expose credentials, weaken authorization, or make destructive production changes without approval. Merge only after required checks pass and security-sensitive behavior has been reviewed.

### Evidence labels

- **Exists in code/docs:** a repository artifact describes or implements the feature; tests are still required.
- **Needs end-to-end verification:** live database, device, web, or real-account behavior is not established by this document.
- **Planned gap:** requested behavior or a known missing path needs implementation.
- **Release blocker:** do not represent the app as ready for public emergency use until resolved and verified.

## Current baseline to preserve

LifeLink is an emergency blood-donor discovery helper, not a medical screening or diagnostic system. Android communicates with FastAPI; the server owns validation, matching, authorization, and sensitive disclosure. The user model is capability-based: one account may request blood and donate. A local role flag is not authoritative.

Repository documentation describes requester submission/history, donor onboarding and availability, matching, contact requests, donor accept/decline, messaging, consent-based contact sharing, reports/blocks, privacy-preserving donor map summaries, optional consent-based location sharing, notifications, audit events, and an admin console. This is an inventory to verify, not proof every screen, state transition, migration, or deployed setting works.

Preserve these boundaries:

- Never return individual donor coordinates or identity through an anonymous map summary.
- Do not reveal contact information merely because a match exists. Require the correct relationship, explicit sharing/acceptance, server authorization, and an auditable event.
- Never let users respond to their own request or access another account's requests, donor profile, messages, or history.
- A match is not acceptance; acceptance is not a completed donation; profile completion is not medical eligibility or clinical verification.
- Do not broadcast merely because a requester created a request. Follow the deliberate contact/selection workflow.
- Keep GPS optional and foreground-oriented. Manual location must remain usable if permission, GPS, network, map style, or tiles fail.
- Keep test/demo data visibly separate from real accounts and live records.

## Priority roadmap

Priority definitions: **P0** protects correctness, privacy, or core task completion; **P1** completes essential workflows and reliability; **P2** improves usability after core behavior is verified.

### P0 — Lifecycle correctness and authorization

#### 1. Requester request workspace — planned gap / verify current UI

Provide a Requests area separating **Active**, **Completed/Fulfilled**, **Cancelled**, and **Expired** requests. Users can open each request and inspect its status timeline, donor responses, contact state, and timestamps. History remains available after sign-out/sign-in and is scoped to the authenticated account.

Add requester editing with explicit rules. Editing must be blocked or limited after responses/contact commitments exist unless the backend can safely invalidate stale responses and notify affected participants. Do not silently change blood type, quantity, urgency, location, or deadline after donors have acted on old information. Explain when editing is unavailable.

Allow repost/reactivation only when the requester has no other active request. Prefer reactivating the same record with an explicit audit/status history if the data model supports it; otherwise define a safe relationship to a new record. Enforce the one-active-request rule atomically on the server/database, not only by disabling a client button. Concurrent attempts from two devices must not create two active requests. Fulfilled requests remain completed history, not ordinary drafts.

**Acceptance tests**
- Owner can list/open their active and inactive requests; another account cannot.
- Empty, loading, network failure, expired, cancelled, fulfilled, and stale-session states are distinct and recoverable.
- Edits are accepted only in eligible states; forbidden edits return a stable error without partial mutation.
- Repost with no active request succeeds once; repost while one exists is rejected and points to the active request.
- Concurrent repost attempts cannot create two active requests.
- Status changes preserve donor-response/contact history and notify affected users when appropriate.
- Android process recreation and account switching never display another user's cached request.

#### 2. Donor lifecycle and response safety — planned audit

Give donors a clear workspace for profile completeness, availability, incoming compatible opportunities, responses, accepted commitments, conversations, shared contact details, and past/closed opportunities. Distinguish profile-complete, verified (only if a real verification process exists), available, and matched states.

Donors should be able to:
- review/update operational profile details and service radius;
- switch availability and understand its effect on future matching;
- see urgency, approximate area/distance, units needed, and deadline without unnecessary patient/requester exposure;
- accept/decline eligible opportunities, see response state, and avoid duplicate submissions;
- withdraw a response only where state permits, with a clear explanation;
- open request-scoped conversations for eligible relationships;
- explicitly share or revoke contact/location information under documented rules;
- view current and past responses, including cancelled, fulfilled, and expired requests;
- report/block abuse and manage notification preferences.

Availability/profile/location changes must recompute matches consistently. Never imply guaranteed or medical eligibility to donate. Do not expose a requester's precise location/contact before appropriate consent and authorization.

**Acceptance tests**
- Donor sees no own request as an opportunity and cannot respond even with a forged request ID.
- Accept/decline/withdraw enforce legal transitions, ownership, and idempotency.
- Closed/expired/cancelled requests cannot receive a new response or disclosure.
- Availability, service-area, and relevant profile changes recompute matches without duplicates.
- Pausing/hiding a donor removes them from future eligible matches according to policy.
- Donor inbox/history is account-scoped on server and local database.
- Both parties receive accurate updates when the other changes relevant state.

#### 3. Authorization, privacy, and abuse controls — release blocker

Build an endpoint-by-endpoint authorization matrix for requester, donor, admin, unauthenticated user, and unrelated authenticated user. Test object ownership and property-level authorization for every route accepting request, donor, conversation, message, profile, location-share, contact-share, report, or audit IDs. Validate on the server and allowlist client-editable fields; never trust client-supplied role/admin flags.

Review database grants and row-level security together. RLS policies do not automatically remove broad table grants. Verify migration order and live schema before applying SQL. Keep secrets server-side, avoid sensitive values in logs/analytics, and ensure errors do not disclose tokens, contact details, precise coordinates, or database internals.

Use bounded pagination/limits for history, inboxes, admin users, audit logs, and errors. Apply shared rate limiting and abuse prevention across workers. Verify report/block is reachable in supported interfaces and document its effects.

**Acceptance tests**
- Unauthenticated calls fail where required; invalid/expired tokens fail closed.
- Cross-user object-ID substitution is denied on sensitive routes.
- Mass assignment of capability, role, ownership, verification, or audit fields is rejected/ignored safely.
- Contact/location sharing is consented, scoped, revocable, expires correctly, and creates an audit event without secret/token values.
- Sensitive values do not appear in logs, notification previews, public map responses, or user-facing errors.
- Rate limits work across multiple API workers and return a stable retry response.
- Database RLS tests cover allow/deny cases; migrations are reproducible on a clean test database.

### P1 — Core experience completeness

#### 4. Notifications and activity timeline

Audit in-app activity, push delivery, unread state, navigation targets, and deduplication. Notifications should identify the relevant request/action without leaking blood type, patient information, precise location, or contact details in lock-screen previews. Verify account ownership when processing push payloads. If push credentials are unavailable, retain in-app activity and do not claim delivery succeeded.

Test token rotation, sign-out/account switch, denied notification permission, duplicate delivery, stale deep links, offline delivery, and cancellation/expiry while a notification is queued.

#### 5. Messaging and safe coordination

Make request-scoped conversations easy to reach from requester and donor views. Provide participant identity at an appropriate level, request context, sending/loading/failure states, safe retry without duplicate messages, and empty states. Contact fields stay hidden until explicit sharing. Reporting/blocking must be visible, confirm consequential actions, and explain effects on future interactions. Do not add always-on donor tracking.

#### 6. GPS and map reliability

The map is a key feature, not a substitute for the rest of the workflow. Preserve an emphasized map entry point and clear recenter control. Distinguish current GPS, last-known location, and manually selected approximate location. Explain permission denial separately from device location being off. Add map-style/tile loading, retry, failure, and offline/manual fallback states. Never present stale coordinates as current or render a fake default coordinate such as (0,0).

Map summaries remain privacy-preserving. Exact location sharing, if enabled for a specific matched request, must be explicit, time-bounded, revocable, and hidden from all other users.

#### 7. Cross-platform parity and accessibility

Maintain a feature matrix for Android, web admin, and backend. Do not describe requester/donor features as available on the web unless a real user-facing web client exists and is tested; the admin console is not a substitute.

Use consistent status names, actions, errors, empty states, and permission explanations. On Android, preserve state across configuration changes/process recreation using the existing architecture and lifecycle-aware state holders; do not keep durable data only in an Activity/composable. Test compact/large screens, keyboard/screen-reader navigation, text scaling, focus order, touch targets, contrast, and visible status messages. On web, use WCAG 2.2 AA as a review goal, including keyboard operation, visible focus, labels, error identification, and status announcements.

### P2 — Convenience and maintainability

#### 8. Search, filters, and history quality

Add filters/sorting after status and authorization semantics are stable. Use server-side bounded pagination for large histories/lists. Keep ordering deterministic and preserve filters across refresh where practical. Distinguish active requests from history and avoid destructive-looking actions for read-only records.

#### 9. Account and recovery flows

Review password reset, confirmation-email resend, expired-session recovery, sign-out, account switching, account-data export/deletion requirements, and clear account-already-exists behavior. Design deletion around audit/legal retention and shared request history; do not hard-delete shared records or alter production data without explicit review and approval.

#### 10. Operational observability and release readiness

Add actionable but sanitized diagnostics, health checks, dependency/security updates, backup/restore rehearsal, migration verification, crash-free startup checks, and a repeatable signed release process. Verify free-tier cold starts and rate-limit behavior honestly. Maintain a release checklist for API deployment, database migration state, Android signing, privacy policy, terms, consent text, and support/contact path.

## Research and standards informing this roadmap

These sources are implementation guidance, not proof LifeLink already complies:

1. **OWASP API Security Top 10 (2023)** — prioritize broken object-level authorization, authentication, object-property authorization, resource consumption, and function-level authorization. Apply checks to every route accepting object IDs or sensitive fields: https://owasp.org/API-Security/editions/2023/en/0x11-t10/
2. **Supabase Row Level Security guidance** — exposed tables need appropriate grants and RLS policies; policies do not revoke existing grants by themselves. Add tests for permitted and denied operations, and keep service-role secrets server-side: https://supabase.com/docs/guides/database/postgres/row-level-security
3. **Android app architecture** — separate UI/data/domain responsibilities, drive UI from state models, survive Activity recreation and intermittent connectivity, and adapt to device/window sizes: https://developer.android.com/topic/architecture
4. **Android architecture recommendations** — use official recommendations as a review checklist and adapt them to the existing project instead of changing stacks without cause: https://developer.android.com/topic/architecture/recommendations
5. **WCAG 2.2** — use testable accessibility criteria for keyboard operation, focus, labels, errors, and status messages: https://www.w3.org/TR/WCAG22/

## Standard workflow for every future update

1. **Inspect:** read current implementation status, regression guardrails, relevant screens/API/schema/tests, open PRs, and latest CI. Confirm the gap still exists.
2. **Research:** identify applicable official guidance and current library/platform constraints. Record links and explain how each source affects implementation/tests.
3. **Specify:** define user story, transitions, permissions, privacy boundaries, error/empty/loading states, migration impact, and acceptance tests before coding.
4. **Implement one coherent slice:** update backend contract, persistence/migration, Android, web where applicable, and tests together. Avoid UI-only behavior the server cannot enforce.
5. **Verify:** run backend compile/tests and static checks; database integration/RLS tests where relevant; Android lint/format/build/unit/UI tests available in CI; web checks; whitespace/diff checks; and regressions for account switching, stale state, duplicate requests, and privacy.
6. **Review:** inspect for secret exposure, authorization bypasses, sensitive logging, migration risks, accessibility, and scope creep. Automated review is extra evidence, not a replacement for tests.
7. **PR and merge:** open a focused PR with evidence and limitations. Merge only when required checks pass and no unresolved security blocker remains. If CI cannot run, say so and do not claim it passed.
8. **Update status:** record PR/commit, tests run, deployment status, known limitations, and the next verified gap. Never mark a feature complete merely because a route or screen exists.

## First implementation sequence

1. Stabilize the currently open admin record-navigation PR before overlapping UI work; inspect its latest CI first.
2. Build an endpoint/state-transition matrix for requester and donor flows, then add missing negative/authorization tests.
3. Implement requester active/inactive history, safe editing rules, and atomic repost/one-active-request enforcement.
4. Complete donor response lifecycle and ensure availability/profile changes update matching.
5. Verify privacy, contact sharing, report/block, and notification transitions across both roles.
6. Improve GPS/map fallback and accessibility based on observed failures, not visual guesswork.
7. Verify migrations 008 and 009 against the live schema through the approved operational process; do not blindly replay migrations.
8. Run two-account end-to-end tests and Android emulator/device checks before calling the system release-ready.

## Definition of done

A feature is complete only when its user-visible path, server-side authorization, persistence, failure/retry behavior, privacy implications, accessibility, automated tests, deployment requirements, and documentation are addressed. “Compiles,” “endpoint exists,” “mock test passed,” and “deployed” are different claims and must be reported separately.
