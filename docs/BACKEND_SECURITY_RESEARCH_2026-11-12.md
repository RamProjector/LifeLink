# LifeLink backend security and reliability research

**Research date:** 2026-11-12  
**Scope:** FastAPI + SQLAlchemy + PostgreSQL/Supabase backend, requester/donor lifecycle, conversations, location sharing, auditability, and multi-instance deployment.  
**Evidence rule:** Findings below distinguish source-confirmed behavior from risks that still require integration testing. This document does not authorize production migrations or deployment.

## Standards and primary references (2020 onward)

1. **NIST SP 800-218, Secure Software Development Framework v1.1 (February 2022).** Use security requirements, threat-informed design, protected build/release practices, and verification as recurring engineering work rather than a final checklist. The publication supersedes NIST's April 2020 SSDF white paper.  
   https://csrc.nist.gov/pubs/sp/800/218/final
2. **OWASP API Security Top 10 (2023).** The most relevant risks for LifeLink are API1 Broken Object Level Authorization, API2 Broken Authentication, API3 Broken Object Property Level Authorization, API4 Unrestricted Resource Consumption, API5 Broken Function Level Authorization, and API6 Unrestricted Access to Sensitive Business Flows. Every route accepting request, donor, conversation, message, or contact identifiers needs actor-and-object authorization, not merely a valid session.  
   https://owasp.org/API-Security/editions/2023/en/0x11-t10/
3. **OWASP Application Security Verification Standard 4.0.3 (2021).** Use its verification requirements for authentication, access control, validation, error handling, and security logging to turn review findings into testable acceptance criteria.  
   https://github.com/OWASP/ASVS/tree/v4.0.3
4. **PostgreSQL 16 documentation — Row Security Policies.** PostgreSQL row security is a database-side defense in depth; table grants and row policies are separate controls. RLS is default-deny when enabled without an applicable policy, but table owners and privileged roles require special attention.  
   https://www.postgresql.org/docs/16/ddl-rowsecurity.html
5. **Supabase Row Level Security guidance (official docs, updated 2026-11-03).** For exposed tables, explicitly enable RLS, set grants, create per-operation policies, and test both allowed and denied operations. Policies alone do not revoke existing grants; service-role credentials bypass RLS and must remain server-side.  
   https://supabase.com/docs/guides/database/postgres/row-level-security
6. **FastAPI OAuth2 scopes documentation (official).** Scopes can centralize operation-level authorization, but scopes placed into tokens must be derived from permissions the account is actually entitled to receive. LifeLink must continue checking resource ownership/participation as well as any role or scope.  
   https://fastapi.tiangolo.com/advanced/security/oauth2-scopes/
7. **PostgreSQL 16 Explicit Locking and Application-Level Consistency Checks.** PostgreSQL documents that `SELECT FOR UPDATE` locks selected rows until transaction end and is appropriate when application-level read/check/write sequences must be serialized. It also warns that locking alone does not prevent every possible concurrent update; the lock must be acquired consistently by all competing operations and held through the protected write.  
   https://www.postgresql.org/docs/16/explicit-locking.html  
   https://www.postgresql.org/docs/16/applevel-consistency.html

## Architecture observed in the repository

- The production API is the PostgreSQL-backed FastAPI app in `lifelink_fastapi/app/main_postgres.py`; persistence is implemented through async SQLAlchemy stores and PostgreSQL migrations.
- The Android client is not the authorization boundary. The API must re-check the authenticated subject, request ownership, donor match membership, conversation participation, current request status, current consent, and location-sharing state on every sensitive operation.
- LifeLink stores separate request, match, donor-contact, location-share, conversation, message, contact-share, and audit records. Their statuses represent distinct lifecycle stages and must not be collapsed into one “accepted/complete” flag.
- Existing CI runs backend tests with PostgreSQL integration coverage and checks that each table has an explicit RLS decision. Migration state in the live Supabase project must still be verified independently; CI cannot prove that a production database has applied a migration.

## Source-confirmed findings and implementation decisions

### 1. Shared rate-limit window used a process-local monotonic epoch

**Evidence:** `lifelink_fastapi/app/rate_limit.py` derived `window_start` using Python `monotonic()`, then persisted that value as the shared PostgreSQL bucket key. A monotonic clock is appropriate for elapsed-time measurement within one process, but its epoch is not a cross-host time contract. API instances with different monotonic origins can therefore write separate buckets for the same nominal interval and weaken the shared limit.

**Change committed:** calculate the fixed-window boundary with PostgreSQL `clock_timestamp()` and `extract(epoch ...)` inside the atomic insert/upsert. The database is now the common clock for all instances. The local monotonic clock remains only in the documented per-process fallback.

**Regression test:** a focused test asserts that the shared SQL derives its bucket from the database clock and uses the returned database bucket for cleanup.

**Remaining limitation:** the existing fallback intentionally degrades to per-process limits when the database is unavailable. This protects availability but is not equivalent to a globally enforced limit. Revisit fail-open versus fail-closed policy per endpoint and threat model; do not silently claim distributed enforcement during database outages.

### 2. Contact disclosure and its audit event may commit separately

**Evidence:** `SqlAlchemyPrivacyStore.add_contact_share()` inserts the disclosure and commits. The API route then records the audit event through `record_audit_async()`, which commits separately. If the later audit write fails, the contact disclosure can remain persisted without its corresponding audit event.

**Risk status:** source-confirmed transaction boundary; failure behavior should be reproduced with PostgreSQL integration tests before changing the repository contract.

**Change committed:** repository methods now accept an explicit `commit=False` mode that flushes but leaves transaction ownership to the caller. The contact-share route stages both the disclosure and audit event, commits once, and rolls back on either write failure. Existing standalone callers retain the default immediate-commit behavior.

**Regression coverage:** a focused unit test verifies both records are staged and flushed without an intermediate commit. A PostgreSQL failure-injection test proving that a failed audit insert rolls back the disclosure is still required before calling this path fully verified.

**Required tests:** successful share creates exactly one disclosure and one audit row; injected audit insert failure leaves neither row; duplicate/retried client request follows an explicit idempotency policy; unauthorized participant cannot create either record.

### 3. Location-provider provenance is not yet represented in the visible donor ORM fields

**Evidence:** the current donor ORM model includes coordinates, `last_location_precision_meters`, `last_presence_at`, and availability timestamps. No provider/source identifier is present on that donor row. This does not prove no separate event metadata exists; the location ingestion route and all migrations still need an exhaustive trace.

**Recommended design:** if provenance is needed for diagnostics or data lineage, store a bounded, non-sensitive provider category (for example, fused, GNSS, network, or manual) with the observation timestamp and reported accuracy. Do not treat provider labels as proof of accuracy or authenticity. Never store raw device identifiers, Wi-Fi identifiers, cell identifiers, or continuous location history merely to identify a provider. Define retention and user disclosure before adding a migration.

**Required tests:** provider omitted/unknown, invalid provider rejected, manual selection distinguished from device-derived observation, stale observations cannot be relabeled as fresh, and no provider field leaks through public map or requester-facing DTOs.

### 4. Authorization needs to be evaluated per object and per lifecycle state

Use OWASP API1/API3/API5 as the test map. For every request/donor/conversation/message/contact/location endpoint, build a matrix of requester owner, matched donor, unrelated authenticated user, unverified user, revoked/blocked participant, and admin. A valid JWT alone is insufficient. Test both donor-row ID and owning-user ID where the model supports both identities, and ensure one identity cannot accidentally widen access to another resource.

### 5. Lifecycle writes need race-safe database invariants

Request cancel/fulfill/expiry, donor accept/decline/withdraw, contact creation, block, location-share revoke, and message send can overlap across devices and API workers. Read-then-write checks must be paired with row locks, conditional updates, uniqueness constraints, or transaction isolation appropriate to the operation. Push notifications are best-effort side effects after the durable state transition; clients must reload authoritative state after opening a notification.

### 6. Conversation block/message race (object-level authorization and consistency)

**Evidence:** the message route checked `is_blocked()` and then called `add_message()`, which committed in a later operation. The block route independently inserted and committed a block. A concurrent block could therefore commit after the check but before the message insert, allowing a message after the block action appeared successful.

**Change committed:** added `SqlAlchemyPrivacyStore.lock_conversation()`, using PostgreSQL `SELECT ... FOR UPDATE`. Message send, contact disclosure, and block creation now acquire the same conversation-row lock before checking or changing block state. The lock remains held until the protected write commits, serializing these operations for one conversation.

The block record and its audit event now also share one transaction. If the audit insert fails, the block is rolled back rather than silently becoming active without an audit event. Contact disclosure and block writes both use explicit caller-owned transaction boundaries.

**Regression coverage:** PostgreSQL integration tests verify (a) a second transaction cannot acquire the same conversation lock until the first commits, and (b) injected audit failures roll back both contact disclosure and block records. Both passed in PR #184 validation run `43204315213` (backend suite: 203 passed).

**Boundary:** request cancellation/fulfillment is a separate lifecycle transition and is not serialized by this conversation lock. It requires its own request-row locking/conditional-update analysis.

### 6a. Manual-broadcast status is not an actual notification broadcast

**Evidence:** the PostgreSQL `POST /v1/emergency-requests/{request_id}/manual-broadcast` route changes the request status to `manual_broadcast`, records an audit event, and returns an eligible-audience filter. It does not enqueue or send donor notifications. The cloud README also states that request creation does not broadcast; donors are notified only when the requester contacts selected donors.

**UI correction committed:** removed the misleading “Send manual broadcast” CTA and snackbar action. The Active Request screen now shows “Manual review pending” and explicitly states that this status transition does not send donor notifications. This avoids implying that a broad donor notification or blood supply has been arranged when neither has occurred.

**Remaining work:** a real coordinator/blood-service workflow would require a defined recipient system, delivery channel, permissions, audit trail, operational ownership, and verified delivery status. Do not call a status transition a broadcast.

### 7. Donor location-provider provenance (diagnostic metadata, not sensor attestation)

**Evidence:** Android obtains a single current fix through `FusedLocationProviderClient`; the client retains the returned `Location.provider` string. The fused provider can combine multiple underlying sources, so reporting `fused` is accurate at the API boundary but must not be relabeled as “GPS” or treated as proof that GNSS satellites produced the fix. Android's location API also distinguishes approximate and precise permission levels, and the fused API can return a cached fix within the configured maximum age.

**Change committed:** the Android donor-presence heartbeat now includes a bounded provider category (`fused`, `gps`, `network`, `passive`, or `unknown`). The FastAPI schema accepts only these values; PostgreSQL stores the nullable `last_location_provider` field with a check constraint. Older clients that omit the field remain compatible, and existing rows remain NULL rather than being assigned a guessed provider. The field is not included in public donor-map responses.

**UI correction:** donor location capture no longer labels every fused fix as “GPS”; it describes the reported source category accurately.

**Trust boundary:** this value is client-reported diagnostic metadata. A modified client can lie about it; it must never drive donor eligibility, matching, authorization, or location accuracy. The server timestamp and reported precision remain separate fields.

**Remaining scope:** requester-created emergency location provenance and manual map-pin provenance are not yet persisted in the same way. That requires tracing the requester location DTO and schema separately before adding a migration.

**Primary Android references (2020 onward/current API docs):**
- FusedLocationProviderClient and permission behavior: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
- CurrentLocationRequest age/duration controls: https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest
- Android's standard fused provider may combine underlying providers: https://developer.android.com/reference/android/location/LocationManager#FUSED_PROVIDER
- Android approximate/precise permission behavior: https://developer.android.com/develop/sensors-and-location/location/permissions

## Backend implementation sequence

1. **Rate-limit correctness** — implemented in this PR with PostgreSQL-clock windows and a focused regression test; full PostgreSQL execution is pending CI.
2. **Atomic disclosure/audit transactions** — contact disclosure and conversation block now commit with their audit events; PostgreSQL failure-injection tests verify rollback behavior.
3. **Conversation block/message race** — implemented with a shared conversation-row lock and PostgreSQL lock-serialization coverage.
4. **Donor location-provider provenance** — implemented for donor-presence heartbeats with a bounded nullable field and additive schema migration; requester-created location provenance remains open.
5. **Authorization matrix** — add cross-role deny tests for every sensitive object route; do not replace object-level checks with role-only checks.
6. **Lifecycle concurrency** — add concurrent PostgreSQL tests for accept vs cancel/fulfill/expiry, block vs message send, and revoke vs exact-location read.
7. **Operational safety** — verify migration application state, backup/restore, readiness, rate-limit behavior under multi-instance and DB-outage conditions, and audit retention before any production release.

## Acceptance gates

- Backend lint/type checks and complete backend test suite pass.
- PostgreSQL integration tests cover allow and deny cases plus transaction rollback and concurrency.
- RLS/grant checks cover every exposed table and every operation; service-role credentials never enter Android or public client bundles.
- API responses and logs do not disclose exact coordinates, contact details, auth tokens, FCM tokens, or signed media URLs outside explicitly authorized flows.
- No production migration, schema mutation, deployment, or release occurs without separate explicit approval and verified migration state.
