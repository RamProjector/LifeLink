# LifeLink client and backend engineering research

**Research date:** 2026-11-12  
**Scope:** LifeLink Android client, FastAPI/PostgreSQL backend, donor/requester workflows, request location, matching, messaging, reliability, performance, accessibility, and relevant patent literature.  
**Method:** prioritize platform documentation, security standards, peer-reviewed research, patent claim records, and inspected open-source source code. Vendor documentation is used only for platform/API semantics, not as evidence that a commercial product is superior.  
**Safety boundary:** this report proposes no production migration or deployment. Patent summaries are technical reading notes, not legal advice, freedom-to-operate analysis, or a novelty assessment.

## Executive findings

1. **Keep the client thin and the backend authoritative.** Android should collect user intent, display server state, keep carefully scoped local drafts, and recover from network interruptions. Authentication is not authorization: the API must verify the authenticated user’s right to each request, donor record, contact, conversation, message, media object, and field on every operation.
2. **Model emergency workflows as explicit state transitions.** Request submission, matching, donor contact, conversation creation, message delivery, fulfillment, cancellation, and location sharing are different lifecycle events. A successful database write must not be presented as a successful push notification, a donor acceptance, or a fulfilled request.
3. **Treat location provenance as bounded diagnostic metadata.** Preserve whether a location was a fused/device fix, a reported GPS/network/passive provider, a manual map pin, manually typed coordinates, or a selected facility. Do not infer a physical sensor, truth, authorization, or accuracy from a client-supplied string.
4. **Debug using reproducible boundaries, not giant log dumps.** Correlate API request IDs and trace IDs; record state transitions and durations; redact exact coordinates and health/contact information; isolate API, authorization, transaction, queue, notification, and client-state failures separately.
5. **Measure before optimizing.** Baseline app startup, screen rendering, scrolling, query latency, database plans, queue depth, and error rates. Optimize demonstrated bottlenecks rather than introducing caches, indexes, background services, or extra dependencies speculatively.
6. **Design for stressed, distracted users.** Make the current request state, next action, freshness, and source of information obvious. Avoid ambiguous status colors, false “broadcast” or “matched” language, excessive detail, and hidden failure states.

## Evidence-based architecture

### Android client: state, data, and feature boundaries

Android’s current architecture recommendations favor a distinct data layer and UI layer, repositories as the boundary to data sources, unidirectional data flow, coroutines/flows, and lifecycle-aware state collection. The data layer should own business rules that must be reused or enforced consistently; composables should not talk directly to GPS, Room, or the network. See [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations) and [Android data-layer guidance](https://developer.android.com/topic/architecture/data-layer).

**Observed LifeLink boundaries:**

| Responsibility | Current code boundary | Engineering rule |
|---|---|---|
| Platform location capture | `LifeLinkAndroid/app/src/main/java/com/lifelink/app/core/location/LocationProvider.kt` | Convert platform data to a small domain value; reject invalid accuracy; normalize provider names before API serialization. |
| Request UI and state machine | `feature/emergencyrequest/EmergencyRequestScreen.kt`, `EmergencyRequestViewModel.kt` | UI dispatches actions; ViewModel exposes state; repository owns persistence/network access. |
| Request API serialization | `data/remote/LifeLinkApi.kt`, `data/repository/EmergencyRequestRepository.kt` | Keep DTO conversion at the data boundary; do not let composables construct wire payloads. |
| Local draft persistence | `data/local/EmergencyRequestDraftEntity.kt`, `LifeLinkDatabase.kt` | Preserve backward-compatible migrations; old rows must receive an explicit safe default rather than guessed metadata. |
| Messaging and donor connection | `feature/privacy/` | Keep request/contact status distinct from actual conversation creation and message delivery. |

The request ViewModel is still a large state holder (about 937 lines at the research snapshot). Do not split it mechanically by line count. A safer staged decomposition is to identify cohesive use cases around validation, submission/retry, contact selection, request lifecycle, and history refresh. Extract one behavior at a time behind narrow interfaces, preserving a single state owner and the existing action/state contract. A function should be split when it mixes distinct business decisions, side effects, and error policies—not simply because it exceeds an arbitrary number of lines.

The test suite was split in this PR along a real responsibility boundary: submission state-machine tests now live in `EmergencyRequestSubmissionTest.kt`; shared repository behavior is in `EmergencyRequestTestFixtures.kt`; remaining history, validation, location, and lifecycle tests stay in `EmergencyRequestViewModelTest.kt`. This addresses a Detekt large-class warning without disabling the rule or reducing assertions.

### Offline-first and retry behavior

Android recommends a local source of truth for offline-first flows. Repositories should reconcile local and remote data, with the UI observing one consistent stream of state. See [Android data layer: source of truth](https://developer.android.com/topic/architecture/data-layer).

For LifeLink:
- Keep an unsent request draft in Room, but distinguish *draft saved locally*, *queued for submission*, *accepted by the API*, *matching in progress*, and *contact request accepted*.
- Give any retryable write a stable idempotency key and retain it across retries. A retry must not create a second emergency request or duplicate donor-contact action.
- Use bounded exponential backoff with jitter for transient network failures; respect server retry guidance and cancellation. Do not retry authorization, validation, or other permanent errors blindly.
- Reconcile uncertain writes by querying authoritative status before replaying them. A timeout after a POST does not prove the server rejected the operation.
- Keep durable queued work small and scoped; do not persist sensitive raw location history or duplicate large media blobs in several layers.
- Do not report notification delivery merely because a record or queue item was created.

**Open-source messaging patterns inspected:**
- [Element Android — EventSenderProcessorCoroutine.kt](https://github.com/element-hq/element-android/blob/08dc499968c2a7f8b6e8a25a0a834a8a783376e7/matrix-sdk-android/src/main/java/org/matrix/android/sdk/internal/session/room/send/queue/EventSenderProcessorCoroutine.kt) uses per-room sequencing, cancellable queued tasks, bounded retries, network reachability waiting, and restoration of pending work after process restart. LifeLink can apply the *pattern* of a durable, ordered outbox for conversation messages; the emergency-request API still needs its own idempotency and lifecycle rules.
- [Signal Android — MessageDecryptor.kt](https://github.com/signalapp/Signal-Android/blob/b8544a813786e2442f145735e500e78df6a33014/app/src/main/java/org/thoughtcrime/securesms/messages/MessageDecryptor.kt) documents atomic database effects and explicit follow-up operations that run after transaction commit (for example, enqueueing jobs). This is a useful boundary for LifeLink: commit the request/message state first, then enqueue notifications or media work; do not hold a database transaction open while calling push, storage, or third-party APIs.
- These source files are implementation examples, not proof that LifeLink should adopt their dependencies or copy their code. Check licenses and security implications before reusing any source.

## Backend architecture, security, and data integrity

### Authorization and object properties

Use [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) as a concrete test map:
- **API1 BOLA:** a requester cannot read, edit, cancel, fulfill, or disclose another person’s request.
- **API3 BOPLA:** an authorized user sees only fields required for that role and lifecycle stage; exact coordinates, phone/email, internal matching signals, moderation notes, and provider metadata must not leak through broad DTO serialization.
- **API5 function-level authorization:** admin-only, coordinator-only, requester-only, and donor-only operations are checked on the server.
- **API4 resource consumption and API6 sensitive business flows:** limit costly search, contact, upload, message, and repeated request flows. Rate limits are not a substitute for authorization.
- **API8 misconfiguration:** verify database grants, RLS policies, secret handling, CORS, debug flags, and public storage access separately.

Use [OWASP ASVS 5.0.0 (released 2025-05-30)](https://asvs.owasp.org/) for testable security requirements, supplemented by [OWASP MASVS](https://mas.owasp.org/MASVS/) for mobile-specific controls. Keep tests actor/object-specific: owner, matched donor, unrelated user, blocked/revoked participant, unverified user, and administrator. A valid JWT or a role name by itself does not authorize access to a particular row or property.

### Transactions, idempotency, and race conditions

Use one transaction for logically inseparable database changes, such as a contact disclosure plus its audit event or a block plus its audit event. A failed audit insert should roll back the protected write if audit evidence is part of the operation’s integrity contract. Network calls and push notifications should not occur inside the database transaction.

For competing state transitions, choose a database invariant appropriate to the operation: conditional updates, uniqueness constraints, row locks, or carefully selected isolation. Every competing writer must follow the same locking/conditional-update contract. A check performed in one request followed by a separate write is not enough when another worker can change the state between those steps.

The PR already added a shared conversation-row lock for message send, contact disclosure, and block creation, plus transaction tests for the related audit writes. That lock does **not** automatically serialize request cancellation/fulfillment/expiry; those need request-row concurrency tests. Maintain this distinction in code and docs.

### Async database sessions

SQLAlchemy documents that `AsyncSession` is mutable, stateful, and represents one in-progress transaction. Concurrent asyncio tasks should use separate sessions; do not share one session across `asyncio.gather()` tasks. See [SQLAlchemy asyncio — concurrent tasks](https://docs.sqlalchemy.org/en/21/orm/extensions/asyncio.html) and [Session thread/task safety](https://docs.sqlalchemy.org/en/20/orm/session_basics.html).

Repository design rule: one task owns one session/transaction at a time. Pass domain identifiers and values between concurrent tasks, not a live session. Keep session lifetime visible at the application/service boundary.

### Query performance and database diagnosis

Use PostgreSQL’s query planner rather than guessing at indexes:
- Inspect the real query with `EXPLAIN (ANALYZE, BUFFERS)` on representative, non-production data.
- Compare estimated versus actual rows; look for sequential scans on selective predicates, repeated nested-loop work, sort spills, and unexpectedly high buffer reads.
- Keep planner statistics current; [PostgreSQL ANALYZE](https://www.postgresql.org/docs/16/sql-analyze.html) populates statistics used by the planner.
- Remember [EXPLAIN ANALYZE actually executes the statement](https://www.postgresql.org/docs/16/sql-explain.html). Do not run write statements casually on production; for a controlled DML investigation, use a safe test environment or an explicit transaction that is rolled back.
- Add composite/partial indexes only after observing the query shape and selectivity. Every index increases write/storage cost and may slow inserts/updates.
- Add pagination and explicit limits to donor discovery, request history, contacts, conversations, and message history. Test worst-case result sizes and count queries separately.

### Debugging and observability playbook

Use a trace/request ID across Android request logs, API middleware, database operations, and background jobs. OpenTelemetry context propagation explains how trace/span IDs correlate logs and spans across service boundaries: [OpenTelemetry context propagation](https://opentelemetry.io/docs/concepts/context-propagation/).

**Recommended debug sequence:**
1. Capture the time, app version, build/commit, endpoint, actor role, sanitized request ID, and expected versus actual state. Do not ask users to send access tokens or raw private coordinates.
2. Reproduce with a deterministic test fixture and one request ID. Determine whether the failure is client state, network/timeout, authentication, object authorization, validation, transaction, background queue, notification, or rendering.
3. Trace the event across boundaries: UI action → ViewModel state → repository method → API route → authorization check → transaction → row/outbox → worker/push → API refresh → UI state.
4. Inspect before/after database rows and audit records in a non-production environment. Check for partial commits and duplicate records.
5. For concurrency, use two independent database sessions and a synchronization barrier; prove both the allowed sequence and the denied/race sequence.
6. Add a regression test at the narrowest useful level (pure function, ViewModel, repository, API, or PostgreSQL integration). Then run focused tests followed by the full CI gate.
7. Preserve failures as observable states. A timeout or empty API response must not be silently rendered as “no donors,” “no messages,” “empty history,” or “success.”
8. Record the root cause, regression test, and any operational caveat in the relevant docs.

Use [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) for exclusions. Never log passwords, bearer/session tokens, database URLs, secrets, exact coordinates, medical details, phone/email, FCM tokens, signed media URLs, or private message bodies. Prefer event names, request/trace IDs, safe object IDs, durations, status transitions, and error categories. Hash/pseudonymize identifiers only where cross-event correlation is justified and access to the logs is controlled.

## Android performance strategy

Android’s architecture guidance explicitly recommends R8, Baseline Profiles/Startup Profiles, and Macrobenchmark for measured startup and UI performance. See [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations) and [Baseline Profiles codelab](https://developer.android.com/codelabs/android-baseline-profiles-improve).

**Proposed LifeLink measurement order:**
1. Establish representative physical-device baselines for cold startup, warm startup, Home render, donor-map first display, request-wizard transitions, and chat scrolling.
2. Use Android Studio CPU/memory/network profilers and [StrictMode](https://developer.android.com/reference/android/os/StrictMode) in debug builds to find main-thread disk/network work.
3. Measure repeated recompositions and large lists; use stable item keys, paging/limits where appropriate, and avoid collecting high-frequency location updates when a screen does not need them.
4. Use Macrobenchmark for repeatable startup/scroll journeys, ideally on physical hardware. Emulator performance data is not a substitute for real-device benchmarking.
5. Generate Baseline Profiles only after the critical user journeys are representative and deterministic.
6. For the backend, record request latency percentiles, SQL time, connection-pool waits, queue delay, error rate, and rate-limit decisions before optimizing.

Do not introduce Redis, a new analytics stack, a paid telemetry vendor, or additional hosted infrastructure merely to measure the system. Use the existing free CI and locally reproducible tools first.

## Human factors and accessibility for emergency workflows

A 2020 peer-reviewed disaster-app usability study identified three recurring design priorities: make critical information salient, account for cognitive load, and build trust ([Understanding end-users’ perspectives: Towards developing usability guidelines for disaster apps](https://doi.org/10.1016/j.pdisas.2020.100118)). A 2023 systematic review of mobile emergency alerting included 22 studies and found that evidence on actual public response was limited and often low quality ([Mowbray et al., Journal of Contingencies and Crisis Management, 2023/2024](https://doi.org/10.1111/1468-5973.12499)). Therefore, use these findings to frame hypotheses and usability tests, not as proof that a particular color, sound, or layout guarantees a behavior.

**LifeLink application rules:**
- Put the current lifecycle state and the next available action first; move diagnostic detail behind secondary information.
- Use direct status language: “Searching for donors,” “Donor contacted,” “Waiting for donor response,” “Manual review pending,” “Conversation ready,” “Fulfilled,” or “Cancelled.” Do not label a request “matched” until the backend has a confirmed match.
- Distinguish “saved on this device,” “sent to server,” “notification queued,” and “notification delivered.” Those are not interchangeable.
- Show a freshness timestamp and source category where location or status can become stale. Make uncertainty visible rather than presenting an old fix as current.
- Do not rely on color alone. Pair color with readable text and icon/shape. Use consistent semantics across cards, maps, alerts, and history.
- Keep the first screen action-focused; move optional explanation and advanced details below it. Avoid duplicate call-to-actions and labels that repeat the same state.
- Provide a visible recovery path for empty, loading, offline, denied permission, stale-data, and failure states.

Use [WCAG 2.2](https://www.w3.org/TR/WCAG22/) as an accessibility checklist for target size, focus visibility, labels, contrast, and status messaging, while separately validating native Android behavior with TalkBack, font scaling, switch access, keyboard/D-pad navigation, and Android accessibility checks. WCAG 2.2 SC 2.5.8 defines a 24 × 24 CSS pixel minimum target with exceptions; it is a web-content criterion, so do not mechanically translate CSS pixels into Android dp. Native components should follow Android touch-target guidance and be tested on-device.

## Patent landscape scan: inspiration only, not implementation permission

Patent publications describe claimed inventions, not proven usability, technical merit, novelty, legal validity, or freedom to operate. Google Patents explicitly warns that displayed legal status is an assumption and is not a legal conclusion. Verify official USPTO/EPO registers and consult qualified counsel before any legal conclusion. The following is a narrowly scoped technical scan:

| Publication | Filing / priority / publication | Assignee and displayed status | Technical scope worth studying | LifeLink relevance and limits |
|---|---|---|---|---|
| [US20210243584A1 — Locating method for emergency caller with assistance vectoring](https://patents.google.com/patent/US20210243584A1/en) | Priority and filing 2020-02-03; published 2021-08-05 | Microsoft Technology Licensing LLC; Google Patents displays **abandoned** | Claims 1–3 describe detecting a nearby device’s emergency communication, determining the incident location, and matching a registered user attribute (such as CPR/medical training) to the emergency type. | Conceptual inspiration for opt-in skill/eligibility filters and nearby assistance discovery. Do **not** copy the network-traffic monitoring or hidden background-detection design; LifeLink should use explicit user consent and server-side verified availability. |
| [EP4295371A1 — System and process for the global unified registration and universal identification of donors](https://patents.google.com/patent/EP4295371A1/en) | Priority 2021-02-18; filed 2022-02-18; published 2023-12-27 | Conectate Soluciones y Aplicaciones SL; Google Patents displays **withdrawn** | Claim 1 describes a unified donor identity linked to facility-specific profiles, offline-created data marked for later synchronization, and audit events when an identifier is read. The description discusses cross-facility history and temporary/permanent deferral information. | Relevant to the general data-model problem of duplicate donor identities and auditability. LifeLink must not imply medical eligibility verification or share clinical data across centers unless a real authorized registry integration and legal basis exist. Its withdrawn status is not a statement about other family members or jurisdictions. |
| [US20250088274A1 — Systems and methods for providing geolocation services in a mobile-based crowdsourcing platform](https://patents.google.com/patent/US20250088274A1/en) | Priority 2015-11-18; continuation filing 2024-11-22; published 2025-03-13 | CrowdComfort Inc; Google Patents displays **pending** | Claim 1 uses an environment layout/floor plan, incident location, nearby mobile devices, and nearby relevant landmarks/objects (such as exits or medical equipment) to send contextual alerts. | Potential inspiration for contextual facility/entrance wayfinding and location-aware information. The publication has an older priority chain; do not assume the 2025 publication means the core concept originated in 2025. Avoid collecting movement data or building floor plans without consent, source validation, and a real product need. |
| [US20210125729A1 — Donor engagement solution](https://patents.google.com/patent/US20210125729A1/en) | Priority 2019-10-24; filed 2020-10-23; published 2021-04-29 | Delcon USA Inc; Google Patents displays **abandoned** | The claim set describes donor identity verification by reading a unique identifier from a physical token and retrieving an associated donor record. | A reminder to separate identity verification from profile claims; not a reason to add token hardware or treat a scanned identifier as sufficient medical eligibility proof. |

**Patent implementation decision:** no patented workflow is being copied into this PR. The useful, independent design directions are (a) permissioned skill/availability-based discovery, (b) explicit audit trails for identity/profile updates, and (c) optional facility-context landmarks where authoritative map data exists. These require separate threat modeling, accessibility review, jurisdiction checks, and product approval before implementation.

## Research-to-implementation register

| Area | Evidence-based decision | LifeLink location | Verification still required |
|---|---|---|---|
| Request location provenance | Bounded allow-list; preserve manual vs device vs facility source; treat source as untrusted diagnostic metadata. | Android `LocationProvider.kt`, request ViewModel/screen, Room draft, API request schema, migration 014. | Provider normalization tests, legacy null tests, draft migration tests, API integration tests; no source property in public donor DTOs. |
| Test organization | Keep test doubles reusable and tests grouped by behavior, without suppressing LargeClass. | `EmergencyRequestTestFixtures.kt`, `EmergencyRequestSubmissionTest.kt`, `EmergencyRequestViewModelTest.kt`. | Detekt, Spotless, unit suite, full Android quality gate. |
| API authorization | Object- and property-level checks for every identifier-bearing route. | `main_postgres.py`, `privacy_repositories.py`, `donor_profile_api.py`, route-specific repositories. | Role/object/field matrix with positive and negative PostgreSQL tests. |
| Transaction reliability | Keep database state and audit evidence in one transaction where contractually inseparable; enqueue side effects after commit. | Privacy repository/store transaction boundaries and background job enqueue paths. | Failure-injection rollback tests and idempotent worker tests. |
| Concurrent lifecycle | Use the appropriate request/conversation lock or conditional state update. | Request lifecycle and conversation message/block handlers. | Two-session race tests for accept/cancel/fulfill/expiry and location-share revoke/read. |
| Debug efficiency | Trace ID/request ID, structured state transitions, redacted logs, layered reproduction. | FastAPI middleware/logging and Android repository/API diagnostics. | Assert no secrets, exact coordinates, PII, or message bodies are logged. |
| Performance | Measure before index/cache/R8/Baseline Profile changes. | Android critical journeys; PostgreSQL hot queries; API queue/connection pool. | Macrobenchmark baseline on physical device; EXPLAIN plan and latency baseline on representative test data. |
| Emergency UX | Show true lifecycle state, freshness, source, and next action; distinguish queued, delivered, matched, and completed. | Home, Active Request, donor results, chat, notifications, request history. | Screen-by-screen manual review with low connectivity, TalkBack, large fonts, stale data, and error paths. |

## References

- Android Developers: [Architecture recommendations](https://developer.android.com/topic/architecture/recommendations); [Data layer](https://developer.android.com/topic/architecture/data-layer); [Baseline Profiles](https://developer.android.com/codelabs/android-baseline-profiles-improve); [StrictMode](https://developer.android.com/reference/android/os/StrictMode).
- Android Developers: [LocationManager fused provider](https://developer.android.com/reference/android/location/LocationManager#FUSED_PROVIDER); [Location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions).
- OWASP: [API Security Top 10 2023](https://owasp.org/API-Security/editions/2023/en/0x11-t10/); [ASVS 5.0.0](https://asvs.owasp.org/); [Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html).
- PostgreSQL 16: [EXPLAIN](https://www.postgresql.org/docs/16/sql-explain.html); [ANALYZE](https://www.postgresql.org/docs/16/sql-analyze.html); [Row security](https://www.postgresql.org/docs/16/ddl-rowsecurity.html).
- SQLAlchemy: [AsyncIO extension](https://docs.sqlalchemy.org/en/21/orm/extensions/asyncio.html); [Session concurrency](https://docs.sqlalchemy.org/en/20/orm/session_basics.html).
- Observability: [OpenTelemetry context propagation](https://opentelemetry.io/docs/concepts/context-propagation/); [OpenTelemetry logs](https://opentelemetry.io/docs/specs/otel/logs/).
- Accessibility: [W3C WCAG 2.2](https://www.w3.org/TR/WCAG22/).
- Human factors: [Disaster-app usability guidelines (2020)](https://doi.org/10.1016/j.pdisas.2020.100118); [Systematic review of mobile emergency alerting (2023/2024)](https://doi.org/10.1111/1468-5973.12499); [Longitudinal analysis of wireless emergency alerts (2023)](https://doi.org/10.1111/1468-5973.12518).
- Open source examined: [Element Android send queue](https://github.com/element-hq/element-android/blob/08dc499968c2a7f8b6e8a25a0a834a8a783376e7/matrix-sdk-android/src/main/java/org/matrix/android/sdk/internal/session/room/send/queue/EventSenderProcessorCoroutine.kt); [Signal Android transaction follow-ups](https://github.com/signalapp/Signal-Android/blob/b8544a813786e2442f145735e500e78df6a33014/app/src/main/java/org/thoughtcrime/securesms/messages/MessageDecryptor.kt).
- Patent publication records: US20210243584A1; EP4295371A1; US20250088274A1; US20210125729A1 (linked in the table above).
