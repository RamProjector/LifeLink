# LifeLink engineering research: backend, Android client, debugging, performance, HCI, and patents

**Research date:** 2026-11-12  
**Repository:** RamProjector/LifeLink  
**PR context:** #184, open and unmerged at the time of this note.  
**Boundary:** This is an evidence-led repository and design review. It does not authorize production migrations, deployment, or changes to live data.

## 1. Method and evidence labels

This review distinguishes four kinds of statements:
- **Repository-confirmed:** visible in the current branch’s source, tests, migrations, or CI configuration.
- **Externally supported:** a platform standard, official technical specification, peer-reviewed study, open-source implementation, or published patent document.
- **Recommendation:** a proposed change derived from repository evidence and external guidance.
- **Not verified:** production configuration, actual hosted database state, physical-device behavior, push delivery, and legal freedom to operate.

Platform documentation is used to establish API and platform semantics, not as independent proof that a product or architecture is best. Product marketing claims are excluded.

## 2. Executive findings

LifeLink already has several useful foundations:
1. The backend uses PostgreSQL-backed FastAPI stores and SQLAlchemy repositories. The current work includes transaction-aware changes and shared-row locking around sensitive conversation operations.
2. Emergency-request drafts and offline submissions are account-scoped in Room. WorkManager schedules retry work when a network is available, and the processor checks the current account before processing a queued draft.
3. Android uses StateFlow screen state, action classes, repository boundaries, and conversation-generation checks to discard stale asynchronous results.
4. Requester location source is captured as bounded diagnostic metadata, persisted in the local draft, defaulted to unknown for legacy rows, and normalized before sending to the API.
5. The Android ViewModel now reserves message-send and contact-share operations synchronously before launching coroutines. Regression tests hold the first operation unresolved and verify duplicate actions do not issue a second repository call.

Important gaps remain:
- The synchronous chat guard prevents rapid local duplicate actions; it does not guarantee exactly-once delivery after an ambiguous network failure. The inspected message API does not yet demonstrate a client-generated idempotency key enforced by a database uniqueness constraint.
- The request ViewModel and Compose request screen have accumulated multiple responsibilities. They should be separated by domain ownership and test seams, not arbitrary line-count limits.
- Performance work has no verified Macrobenchmark baseline in the inspected branch. Optimization should start with reproducible measurements.
- A route-by-route authorization matrix, more deterministic lifecycle race tests, and physical-device accessibility checks remain necessary.
- The message schema inspected for this review is text-only. A complete private image/video upload and retrieval path was not verified.

## 3. Current architecture map

| Responsibility | Current source area | Review focus |
|---|---|---|
| PostgreSQL API composition and lifecycle | lifelink_fastapi/app/main_postgres.py | Authentication, route authorization, sweepers, transaction boundaries |
| Request, donor, match and rate-limit persistence | lifelink_fastapi/app/repositories.py | Query shape, locking, expiry, bounded batches, audit writes |
| Conversation, message, contact-share and location-share persistence | lifelink_fastapi/app/privacy_repositories.py | Participant checks, block/revoke races, consent and freshness |
| Database models and migrations | lifelink_fastapi/app/db_models.py and lifelink_fastapi/sql | Constraints, RLS decisions, additive migration order |
| Android API contract | LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/remote/LifeLinkApi.kt | Request identity, message idempotency, error mapping |
| Request persistence and retry | LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/local/PendingSubmission.kt | Account scope, draft identity, retry policy and logout |
| Request orchestration | LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/emergencyrequest/EmergencyRequestViewModel.kt | Submission, history, contacts and lifecycle |
| Request Compose UI | LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/emergencyrequest/EmergencyRequestScreen.kt | Wizard, map picker, results and accessibility |
| Chat and privacy state | LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/privacy/PrivacyViewModel.kt | Conversation generations and operation guards |
| Chat repository | LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/repository/PrivacyRepositoryImpl.kt | Message request/retry semantics and cancellation |

## 4. Backend security, reliability and data consistency

### 4.1 Authorize the object and property, not just the session

OWASP API Security Top 10 (2023) is a practical threat map for LifeLink’s request, donor, map, location-share, contact-share, conversation, message, report and block endpoints. API1 (Broken Object Level Authorization), API3 (Broken Object Property Level Authorization), API5 (Broken Function Level Authorization), API4 (Unrestricted Resource Consumption) and API6 (Unrestricted Access to Sensitive Business Flows) are especially relevant.

For every endpoint accepting a request, donor, conversation, message, share or contact identifier, verify the authenticated actor against the exact object and its current lifecycle state. A valid JWT alone is not sufficient. A user must not change another donor’s visibility, read another request’s contacts, use a guessed conversation ID, obtain a revoked location share, or invoke a privileged function through client-controlled role fields.

**Recommendation:** maintain a route test matrix covering request owner, matched donor, unrelated signed-in user, unverified user, blocked/revoked participant and administrator. For each, test correct IDs, guessed IDs, cross-account IDs, closed requests and attempted mass assignment of ownership, role, status, visibility and consent properties. Assert denied calls create no message, disclosure, successful audit row or notification side effect.

### 4.2 Keep business state and audit evidence atomic

For a sensitive mutation, perform authorization, current-state checks, the business write and the required audit write in one transaction. Acquire shared locks or use conditional updates where concurrent operations could invalidate the checks. Commit before dispatching external effects such as push notifications or background jobs.

A future transactional outbox is appropriate when a committed database transition must reliably schedule a notification or worker action: write the outbox record in the same transaction as the business mutation, then deliver it asynchronously and record delivery attempts. Do not hold database locks while making network calls.

### 4.3 Concurrency and lifecycle transitions

Request cancel/fulfil/expire, donor accept/decline/withdraw, message send, contact disclosure, block, location-share revoke and account deletion can overlap across devices and API workers. A read/check/write sequence is unsafe when two transactions can interleave.

Use row locks shared by all competing code paths, conditional UPDATE statements that assert the expected previous state, database uniqueness constraints, or transaction isolation suited to the invariant. The current branch’s conversation locking work does not automatically protect request lifecycle rows: request state transitions need their own concurrency analysis.

**Required deterministic tests:** send versus block; message send versus request cancellation; exact-location read versus revoke; share activation versus revoke; fulfil versus cancel; duplicate contact request; and repeated request submission with the same idempotency key. Use barriers or deferred tasks to control interleaving rather than sleeps.

### 4.4 Async SQLAlchemy and background workers

SQLAlchemy documents AsyncSession as mutable, stateful transaction state and requires a separate AsyncSession per concurrent asyncio task. Do not share one session between concurrent branches of gather. Background sweepers should claim bounded batches before materializing rows and expose failure/backlog signals.

LifeLink’s periodic sweepers are cleanup, not authorization. Exact-location and chat routes must deny access immediately using current expiry, request status, participant and consent state; correctness must not depend on a sweeper having run.

### 4.5 Rate limits, resource bounds and query plans

Apply distinct rate-limit budgets to authentication, request creation/retry, donor contact, message sends, contact disclosure, location-share activation, report/block, and future media uploads. Bound list sizes, search radius, payload size, worker batch size, image bytes and video duration.

Use PostgreSQL EXPLAIN with ANALYZE and BUFFERS on representative staging data to inspect actual row counts, estimates, index/heap reads, temp work and filter selectivity. PostgreSQL ANALYZE refreshes planner statistics. EXPLAIN ANALYZE executes statements: do not run destructive write-plan experiments against production.

LifeLink query checklist:
- Request history: stable cursor pagination, owner/status filters and bounded pages.
- Donor map: apply opt-in, availability and freshness filters in SQL before materialization.
- Matching: measure candidate count, geospatial pre-filter selectivity, ranking cost and worst-case radius.
- Conversation history: use keyset pagination and avoid loading an unbounded message list.
- Sweepers: claim a bounded batch with explicit locking semantics and count actual state transitions.
- Add indexes only when measured query plans and workload justify them.

## 5. Android client architecture and offline semantics

### 5.1 What already exists

The request flow uses an account-scoped Room draft store and separate pending-submission table. WorkManager schedules unique work with a connected-network constraint and a work name based on account owner and draft ID. The processor checks that the signed-in account matches the queued owner, verifies the stored draft ID and differentiates retryable from permanent errors. This is an established offline-first foundation for emergency-request submission.

Do not describe a locally queued request as submitted or as having notified donors. Keep the local draft, queued submission, server-accepted request and matching lifecycle states separate.

### 5.2 Chat needs idempotency before durable retries

The inspected Android PrivacyRepositoryImpl path posts a message body directly to the conversation endpoint. The inspected backend insertion creates a server-side message ID; no client-generated message ID plus unique retry constraint was verified. A lost HTTP response does not prove the server failed to commit. Replaying that POST can create a duplicate.

PR #184 now sets sending/sharing state synchronously before launching the coroutine and rejects a second matching action while the operation is in flight. This prevents rapid duplicate UI actions, but it does not solve process death or ambiguous network delivery.

Before adding a durable chat retry queue:
1. Generate a stable client_message_id before the first attempt and persist it locally.
2. Send that ID with the message and enforce uniqueness on conversation, sender and client_message_id at the backend.
3. On a repeated key, return the already committed message rather than insert a second one.
4. Retain the pending row until the server acknowledges that exact ID.
5. Preserve per-conversation ordering and stop retries on permanent authorization, block or closed-request errors.
6. Test the server-commit/response-lost/retry case and verify only one database message exists.

Do not blindly reuse the existing POST from WorkManager without this contract. Apply the same design to contact disclosure only when the API defines appropriate idempotency and response semantics.

### 5.3 State flow, stale responses and cancellation

Android’s current architecture guidance recommends unidirectional data flow: ViewModels expose UI state, UI sends actions, and state collection is lifecycle-aware. LifeLink already uses StateFlow, actions, repositories and generation tokens to ignore stale conversation results after a different conversation opens.

Continue to expose explicit loading, empty, error, pending, confirmed and retryable states. Keep HTTP DTOs out of Compose. Keep Activity/Context out of ViewModels. Rethrow CancellationException instead of mapping cancellation into a user-visible failure. Scope cached state and pending work to the authenticated account.

One review item remains: PrivacyRepositoryImpl uses runCatching around suspend network calls. Kotlin runCatching also captures CancellationException. Review this adapter and preserve structured cancellation, as the request repository already does in several methods.

### 5.4 Room migrations and data preservation

LifeLinkDatabase is Room schema version 10 and migration 9→10 adds requester location source with default unknown. Do not infer source from old coordinates. Keep migrations additive where feasible, test supported starting schemas with representative rows, and verify that migrations preserve account ownership and pending work.

Android Room guidance includes migration testing. Evaluate the current use of Robolectric for Room-specific tests against current Android guidance; this is a test strategy review, not a reason to remove working ViewModel unit tests.

## 6. Debugging workflow: reproducible, traceable and privacy-safe

1. **Capture the contract:** app version, API route/method, sanitized request shape, response code, server request ID, account-role scenario, network state and lifecycle state. Never copy tokens, full message bodies, phone numbers, exact coordinates or medical data into bug reports.
2. **Reproduce one path:** identify actor, request, conversation, lifecycle status, action order and expected result. Use deterministic fixtures or staging data, not production user records.
3. **Separate layers:** Compose event/state; ViewModel concurrency/cancellation; repository mapping; HTTP/auth; route authorization; SQL transaction/constraint; background worker or notification delivery. Find the earliest layer where actual state diverges.
4. **Trace one operation:** propagate a server-generated correlation ID through API entry, repository logs and audit events. Include it in sanitized Android diagnostics. Correlation IDs are tracing aids, not identities or authorization evidence.
5. **Redact by construction:** log operation name, opaque IDs, state transition, latency, retry count and error category. Never log bearer tokens, passwords, contact values, exact location, full chat body or uploaded media.
6. **Write the failing test first:** unit test pure validation/state mapping; repository test retry/error semantics; PostgreSQL integration test constraints/races; Room migration test old data. Use barriers/deferred results for race windows, not arbitrary sleeps.
7. **Fix the smallest responsible boundary:** make API/schema and migration compatibility explicit. Avoid unrelated UI, server state-machine and persistence edits in one unreviewable patch.
8. **Run layered gates:** formatter/lint/Detekt, Kotlin unit tests, Android debug build, backend lint and PostgreSQL tests, migration validation, CodeQL/security scan and fake-backend APK. Then perform manual physical-device checks for permissions, map movement, process death, account switch, network loss, notification deep links, chat and adaptive layouts.
9. **Compare before/after:** inspect changed paths, failing tests, logs and state transitions. APK success alone does not establish UI correctness.

## 7. Function and module organization for efficient debugging

The aim is to make defects localizable, not to impose arbitrary function-length rules.

Staged extraction candidates:
- EmergencyRequestViewModel owns draft editing, submission/retry, history, donor contacts, moderation, polling, cancellation and fulfilment. Consider RequestSubmissionCoordinator, RequestContactsCoordinator, RequestLifecycleCoordinator and RequestHistoryCoordinator behind stable ViewModel state/actions.
- EmergencyRequestScreen contains the wizard, location picker, donor results, map and reusable controls. Extract cohesive screen sections into focused files while preserving semantics, test tags and accessibility.
- PrivacyViewModel covers map, visibility, location sharing, conversations, messages, saved messages, contact disclosure and moderation. Split only after deciding which component owns shared state and lifecycle; avoid competing ViewModels overwriting the same data.
- EmergencyRequestRepository is a cohesive domain repository for the request lifecycle. Do not split it merely to satisfy TooManyFunctions.

Practical rules:
- One function owns one business decision or orchestration step.
- Extract pure validation, normalization and mapping functions; these are cheap to test.
- Keep I/O at repository/service boundaries and pass domain values rather than HTTP DTOs into UI.
- Use explicit result/state types for outcomes that affect retry, messaging or lifecycle.
- Keep database transactions, audit writes, queue scheduling, notifications and UI updates visible rather than hiding them in opaque generic helpers.
- Use precise lifecycle names: queued locally, submitted, matching, awaiting response, accepted, contact shared, fulfilled, expired and cancelled are not synonyms.

## 8. Performance plan: measure before optimizing

### Android

Create a repeatable baseline on a physical mid-range device and supported emulator:
- cold, warm and hot launch time;
- time to first meaningful Home content;
- frame timing during map pan/zoom and long conversation scroll;
- time to open a conversation and render a representative message history;
- request wizard transitions and nearby-facility loading;
- memory, network bytes and image decoding during map/chat use.

Use Macrobenchmark StartupTimingMetric, FrameTimingMetric and trace sections. Add measured spans around map load, donor list render, conversation open and request submit. Compare median and high-percentile startup/frame results across builds. Do not add Baseline Profiles or caches just because they sound faster; first record a repeatable slow path.

Use stable LazyColumn keys, paginated histories, small image thumbnails, bounded map result windows, lifecycle-aware collection and cancellation of stale jobs. Never parse large media on the UI thread. A complete chat media path was not verified in this review.

### Backend

Capture representative EXPLAIN (ANALYZE, BUFFERS) plans in staging; refresh planner stats when distribution changes; bound every list/search/worker batch; measure SQL query count per endpoint and N+1 behavior; avoid serializing full ORM graphs into narrow client responses; never share one AsyncSession across concurrent tasks. Record p50/p95 latency, query duration, rows processed, sweeper backlog and retry counts without sensitive fields.

## 9. HCI, accessibility and emergency messaging

### 9.1 Status must be truthful

Emergency flows must clearly distinguish locally saved draft, offline queued submission, server-accepted request, matching, donor response, contact request, contact details shared, manual review pending, fulfilled, cancelled and expired. Do not say donors were notified unless the backend confirms that action. A manual-review status is not a broadcast; a local queue is not a submitted request; a matched donor is not an accepted donation.

### 9.2 Accessible map and status interactions

Use text plus icon/shape for important statuses, not color alone. Provide dynamic text scaling, logical focus order, TalkBack labels and live-region announcements. Android accessibility guidance recommends 48dp touch targets. WCAG 2.2 SC 2.5.8 sets a 24×24 CSS-pixel minimum for web content with defined exceptions; CSS pixels and Android dp are not interchangeable.

A map selection should have an equivalent text/coordinate/facility-list interaction. Show an understandable location summary and reported accuracy/source, provide a way to move or clear the marker, and avoid color as the only indicator of an accepted donor, stale location or revoked share.

### 9.3 Emergency content should be short, actionable and verifiable

A 2022 mixed-methods study with 80 participants using simulated severe-flood cell broadcasts found that people need accurate, verifiable information and a clear location; alerts should not over-alert and should communicate urgency, certainty and severity. A 2024 experiment with 481 participants found 360-character WEA messages did not produce higher compliance than 90-character messages; risk personalization and hazard experience were stronger differentiators. A 2024 scoping review of 17 sources proposed an AIMM framing: Audience, Information, Messenger and Mode.

Practical implications: put the immediate action and affected location first; distinguish known from unconfirmed; identify who is expected to act; avoid repeated warning banners; personalize only with relevant user-controlled context; reveal secondary detail progressively. These studies are not direct validation of LifeLink’s blood-donation workflow or Philippine user population. Validate copy with local personnel and intended users.

## 10. Open-source implementation patterns

These are source examples, not claims that either app is a perfect template.

### Element Android: ordered, recoverable message sends

Element’s EventSenderProcessorCoroutine uses a queue identifier per room, serializes sends through a keyed sequencer, bounds retries and restores pending tasks after session restart. Useful ideas once LifeLink has server-side idempotency: per-conversation ordering, durable pending task identity, resume after process interruption, separate network waiting from retry attempts, and visible permanent failure.

Source: https://github.com/element-hq/element-android/blob/08dc499968c2a7f8b6e8a25a0a834a8a783376e7/matrix-sdk-android/src/main/java/org/matrix/android/sdk/internal/session/room/send/queue/EventSenderProcessorCoroutine.kt

### Signal Android: transaction first, follow-up work after commit

Signal’s MessageDecryptor describes database-only side effects that should be atomic inside a transaction, with follow-up operations such as enqueueing jobs performed after commit. LifeLink can borrow this architecture pattern for future notification/outbox delivery; do not copy code.

Source: https://github.com/signalapp/Signal-Android/blob/b8544a813786e2442f145735e500e78df6a33014/app/src/main/java/org/thoughtcrime/securesms/messages/MessageDecryptor.kt

## 11. Patent landscape: inspiration, not implementation permission

The following documents may help understand prior-art problem framing. The claim summaries are paraphrased. Google Patents’ legal status and bibliographic metadata are index-level information and may be assumptions. A publication or grant does not prove novelty, infringement, validity, freedom to operate or feature suitability. This is not legal advice; do not copy claims or code.

| Publication / title | Priority and publication | Relevant scope in plain language | Possible inspiration and limit |
|---|---|---|---|
| US20260087925A1, System and method for providing data stream information to emergency response dispatch | Priority 2024-04-11; published 2026-03-26; listed pending | User-initiated emergency stream can send camera/audio/GPS to a remote dispatcher. | Consider explicit, user-started escalation only if a staffed response workflow exists. Continuous camera/audio streaming has major privacy and bandwidth costs. https://patents.google.com/patent/US20260087925A1/en |
| US20220353663A1, Systems and methods for emergency communications amongst groups of devices based on shared data | Priority 2016-02-26; published 2022-11-03; later US11665523B2 grant listed | Device/vehicle emergency data is shared with a dispatch endpoint and displayed for response. | Idea for structured incident context and responder view; priority predates 2020 and is not a donor-matching blueprint. https://patents.google.com/patent/US20220353663A1/en |
| US9826358B2, Method and system for geolocation and coordinated communication with emergency responders | Priority 2015-07-09; granted 2017-11-21 | Candidate responders in an area are filtered, solicited and added after acceptance. | Older prior art for bounded candidate search and explicit opt-in. Not a current technical standard. https://patents.google.com/patent/US9826358B2/en |
| EP4295371A1, System and process for global unified registration and universal identification of donors | Priority 2021-02-18; published 2023-12-27; listed withdrawn | Donor identity can be linked to facility-specific records; offline-created data can be marked for synchronization. | Inspiration for stable donor identity and facility-scoped provenance. Google Patents lists withdrawn; status should be independently checked by counsel. https://patents.google.com/patent/EP4295371A1/en |
| US20210125729A1, Donor engagement solution | Priority 2019-10-24; published 2021-04-29; listed abandoned | Donor engagement uses a unique identifier/token scanned to retrieve donor-linked information. | Limited inspiration for verified facility identity. Priority predates 2020; not a location-matching patent. https://patents.google.com/patent/US20210125729A1/en |
| US20250349402A1, Method and apparatus for matching anonymous vitals data to a blood product donor record | Priority 2024-05-08; published 2025-11-13; listed pending | Blood-center device/time/location data associates deidentified vitals with a donor record and eligibility decision. | Reinforces that medical eligibility is a separate facility process. LifeLink must not infer eligibility from location, profile or matching score. https://patents.google.com/patent/US20250349402A1/en |

## 12. Prioritized implementation matrix

| Priority | Work | Current evidence | Acceptance criteria | Status |
|---|---|---|---|---|
| P0 | Duplicate chat send and contact-share guard | PrivacyViewModel reserves in-flight state synchronously; deferred-response tests cover repeated actions | Two actions before first completion produce one repository call; completion/failure clears state; stale result cannot update a different conversation | Implemented in PR #184; CI pending |
| P0 | Bounded requester location-source attribution | Provider normalization, Room 9→10 migration, API allow-list and legacy unknown default | Known providers map correctly; null/OEM values map to unknown; old drafts migrate without losing data | Implemented in PR #184; CI pending |
| P1 | Chat idempotency contract | Direct message POST inspected; no client ID plus unique retry constraint verified | Same client_message_id sent twice creates one DB row and returns the original message; lost-response retry does not duplicate | Recommended; not implemented |
| P1 | Chat outbox and ordered retry | Request submission has Room and WorkManager queue; chat path does not yet have verified durable idempotency | Process death/network return resumes each message once and in order; authorization/block errors are terminal | Blocked on server idempotency |
| P1 | Lifecycle and route race matrix | Several transaction and lock protections exist; more concurrency tests remain | Deterministic PostgreSQL tests for block/send, cancel/send, revoke/read, fulfil/cancel and expiry | Partly covered; expand |
| P1 | Object/property authorization matrix | Route/repository helpers centralize checks, but every path needs adversarial coverage | Cross-account IDs, unrelated users, blocked participants, closed requests and unauthorized properties cause no side effects | Recommended; continue audit |
| P2 | Extract large request ViewModel and screen by responsibility | ViewModel owns submission, history, contacts and lifecycle; screen owns wizard, map and results | Stable external state/actions; test seams per coordinator; no lost semantics or navigation regressions | Recommended, staged |
| P2 | Performance baseline | No verified Macrobenchmark suite/result surfaced in this review | Record startup, frame, map, request and chat trace metrics before optimization | Not yet verified |
| P2 | Map privacy precision and density | Fixed 0.01-degree grid is not a uniform metric cell worldwide; per-donor entries can reveal density | Test supported-region extremes and boundaries; review cohort suppression and accessible alternatives | Documented in prior source audit; decision pending |
| P3 | Image/video chat attachments | Inspected message schema is text-only; no complete private attachment path verified | Private storage, participant access, verified media type/size, signed download, retention and deletion tests | Deferred; do not assume Storage is configured |

## 13. References

### Standards and platform documentation
- NIST SP 800-218, Secure Software Development Framework v1.1 (2022): https://csrc.nist.gov/pubs/sp/800/218/final
- OWASP API Security Top 10 (2023): https://owasp.org/API-Security/editions/2023/en/0x11-t10/
- OWASP MASVS: https://mas.owasp.org/MASVS/
- Android architecture recommendations: https://developer.android.com/topic/architecture/recommendations
- Android offline-first architecture: https://developer.android.com/topic/architecture/data-layer/offline-first
- Android Room database testing: https://developer.android.com/training/data-storage/room/testing-db
- Android Macrobenchmark: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Android accessibility: https://developer.android.com/guide/topics/ui/accessibility/apps
- WCAG 2.2: https://www.w3.org/TR/WCAG22/
- PostgreSQL 16 EXPLAIN: https://www.postgresql.org/docs/16/sql-explain.html
- PostgreSQL 16 ANALYZE: https://www.postgresql.org/docs/16/sql-analyze.html
- SQLAlchemy 2.0 session and AsyncSession concurrency: https://docs.sqlalchemy.org/en/20/orm/session_basics.html

### Peer-reviewed emergency communication evidence
- Testing the public’s response to receiving severe flood warnings using simulated cell broadcast (2022; N=80): https://pubmed.ncbi.nlm.nih.gov/35194319/
- Do 360-Character Wireless Emergency Alert messages work better than 90-character messages? (2024; N=481): https://pubmed.ncbi.nlm.nih.gov/39328310/
- An exploration of the influencing factors for effective public health messaging during disasters: a scoping review (2024; 17 sources; AIMM framework): https://doi.org/10.1016/j.puhe.2023.12.023

### Open-source source references
- Element Android send queue (pinned commit): https://github.com/element-hq/element-android/blob/08dc499968c2a7f8b6e8a25a0a834a8a783376e7/matrix-sdk-android/src/main/java/org/matrix/android/sdk/internal/session/room/send/queue/EventSenderProcessorCoroutine.kt
- Signal Android transaction/follow-up pattern (pinned commit): https://github.com/signalapp/Signal-Android/blob/b8544a813786e2442f145735e500e78df6a33014/app/src/main/java/org/thoughtcrime/securesms/messages/MessageDecryptor.kt

## 14. Verification boundary and change log

**Verified in source:** account-scoped request draft and pending-submission persistence; WorkManager connected-network scheduling; conversation-generation handling; synchronous chat send/contact-share operation guards and regression tests; bounded requester location-source categories and legacy default; backend transaction/locking work described in BACKEND_SECURITY_RESEARCH_2026-11-12.md.

**Not verified by this document:** live Supabase schema state; current hosted service configuration; actual push delivery; media storage configuration; physical-device performance; manual accessibility acceptance; full real-world UI testing; patent legal status beyond index metadata; freedom to operate.

**Change log:** 2026-11-12 — initial cross-layer engineering review, primary standards, peer-reviewed emergency communication findings, OSS implementation examples, patent landscape and prioritized implementation plan.