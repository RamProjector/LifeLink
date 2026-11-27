# LifeLink API Audit Coverage Review

**Review date:** 2026-11-22  
**Scope:** Repository review of `lifelink_fastapi/app/main_postgres.py`, `app/admin_api.py`, `app/db_models.py`, and `app/db.py`.  
**Status:** Static code review only. No production database changes or live traffic tests were performed.

## Existing controls confirmed

- The API has a shared `audit_events` writer in `app/admin_api.py`.
- Admin dashboard, user listing, user capability updates, audit reads, and error reads write audit events.
- Admin access is checked server-side using the configured admin user allowlist.
- Audit metadata is recursively redacted and bounded before being returned by admin endpoints.
- The main API has exception handlers that best-effort record database and unexpected API failures.
- A middleware currently records failed responses only for `/v1/admin/` routes. It does not provide general denied-operation coverage for ordinary application routes.
- The admin audit API supports limit/offset and action filtering, but the current response does not expose a total count.

## Route-level coverage matrix

“Explicit event” means the route contains an explicit call to the audit writer or request-store audit method in its handler. This review does not count general error logging as a successful-operation audit event.

| Route / operation | Risk / data sensitivity | Explicit success audit found | Recommended treatment |
|---|---|---:|---|
| `PUT /v1/profile` | Account role/capability/profile changes | Added in this PR | Record create/update and changed field names only; no email or display-name values |
| `PUT /v1/push-token` | Push endpoint credential registration | Added in this PR | Record add/update outcome with actor, session, and correlation; never record token; no token-removal endpoint is currently present |
| `POST /v1/emergency-requests` | Creates sensitive emergency/location request | Added in this PR | Atomically record request ID, outcome, matching mode, and match count; never copy coordinates or medical details into metadata |
| `POST .../contact` | Initiates contact with selected donors | Yes | Retain; verify failed/denied attempts are also observable |
| `PATCH .../contacts/{donor_id}` | Changes contact lifecycle/status | Added in this PR | Atomically record actor, request/donor references, and previous/new status only |
| `POST .../report`, `POST .../block` | Safety/moderation actions | Yes | Retain; ensure reason text is not copied into broadly readable metadata |
| `POST .../manual-broadcast` | Expands emergency request audience | Yes | Retain; verify failures/denials |
| `POST .../cancel`, `POST .../fulfill` | Ends or resolves emergency request | Yes | Retain; ensure actor and prior/new status are represented |
| `PUT /v1/donor-profile` | Donor identity/profile changes | No | Record profile create/update without blood type, coordinates, or free-text note |
| `POST /v1/donor-profile/presence` | GPS/presence update | Added in this PR | Atomically record authenticated actor/resource, client-reported provider label, and coarse precision bucket; never store coordinates or raw precision |
| `PATCH /v1/donor-profile/availability` | Changes availability | Added in this PR | Atomic `donor_availability_changed` event records previous/new preference only when it changes |
| `DELETE /v1/donor-profile` | Deletes donor profile and disables donor | Added in this PR | Profile deletion, operational donor disablement, account capability reset, and audit event commit together |
| `PUT /v1/donors/{donor_id}` | Legacy donor profile create/update | No | Record profile mutation; verify legacy route still required |
| `POST /v1/donors/{donor_id}/presence` | Legacy GPS/presence update | Added in this PR | Same atomic privacy-safe event as current presence route; provider label and coarse precision bucket only |
| `PATCH /v1/donors/{donor_id}/availability` | Legacy availability change | No | Record previous/new availability |
| `PUT /v1/donors/{donor_id}/map-visibility` | Consent/privacy preference | Yes | Repository commits consent change, share revocations, and audit event atomically |
| `POST .../location-share` | Enables disclosure of exact GPS location | Yes | Atomic `location_share_activated` event records actor and request/donor scope; metadata excludes coordinates |
| `DELETE .../location-share` | Revokes exact GPS disclosure | Yes | Atomic `location_share_revoked` event records actor and request/donor scope |
| `GET .../location` | Reads exact GPS location | No | Consider a minimal access event (actor, request/donor scope, result) with retention limits; do not log coordinates |
| `POST /v1/conversations` | Creates/opens a conversation | Yes | Retain; verify idempotent/reused conversation outcomes |
| `PUT .../messages/{message_id}/saved`, `DELETE .../saved` | Saves/removes private message bookmark | No | Optional lower-priority event; do not log message content |
| `POST .../messages` | Sends private message | No | Record message ID and conversation ID only; never message body or attachments |
| `POST .../contact-shares` | Explicitly discloses phone/email | Yes | Disclosure and audit share one transaction; metadata records field type and conversation reference, never the disclosed value |
| `POST .../report`, `POST .../block` (conversation) | Safety/moderation actions | Yes | Retain; capture outcome and target references without sensitive report text |

Read-only profile, request history, donor inbox, donor map, and conversation-list/message reads are not automatically recommended for full audit logging. Exact-location access is a special case because it reveals highly sensitive location data. Define a proportionate access-event policy and retention period before enabling broad read auditing.

## Confirmed coverage gaps

1. **No general denial audit middleware.** Current response middleware audits failures only under `/v1/admin/`. Authorization failures on ordinary routes are not consistently recorded as audit events.
2. **High-impact successful mutations without explicit audit events.** Remaining candidates include emergency-request creation, contact-status transitions, donor-profile create/update, GPS presence observations, exact-location reads, message sending, and saved-message changes. This PR adds profile account create/update and separate donor availability/delete audit coverage. Map visibility, exact-location grant/revocation, and contact disclosure already have atomic audit writes in the inspected repository.
3. **Exact-location reads are not explicitly audited.** This should be evaluated as a high-priority privacy access event.
4. **No complete detection-rule/alert lifecycle demonstrated by these files.** Error records alone do not establish an IDS/IPS engine, alert acknowledgement, deduplication, or automated response.
5. **Pagination metadata is incomplete.** Admin audit endpoint returns page items but not total count, making total-based pagination and reliable page counts impossible.
6. **Session/device/IP attribution is not represented as dedicated fields in the inspected audit model.** Add only after defining trusted-proxy rules, data minimization, retention, and access controls.

## Implementation order

### P0 — Audit the highest-impact changes
- Add explicit audit events for emergency-request creation and cancellation/fulfillment outcome consistency.
- Add explicit audit events for remaining donor-profile create/update and GPS-presence policy. Profile create/update, current donor-profile presence, separate donor availability/delete, and map-visibility consent events are covered or added in this PR. The legacy `/v1/donors/{donor_id}/presence` route remains a follow-up.
- Exact-location grant/revocation events are already implemented atomically; add a minimal exact-location read event only after defining retention and failure semantics.
- Add audit events for message send and contact status changes, excluding message bodies and sensitive content.
- Make audit writes part of the same database transaction as the protected mutation where feasible. A failed audit write must not silently leave an untraceable high-risk mutation committed.

### P1 — Denied and failed operations
- Add a centralized, bounded event mechanism for 401/403 responses on sensitive routes.
- Avoid logging every 404/400 as an intrusion: distinguish expected validation/not-found outcomes from authorization denials.
- Ensure exception events include stable route template, method, status, correlation ID, and safe error class only.

### P2 — Attribution and dashboard
- Add trusted-proxy-aware client IP attribution and a sanitized user-agent/device classification.
- Add explicit total counts or cursor pagination to audit APIs.
- Build security alerts only after detection rules, deduplication keys, severity, lifecycle, and retention are specified.

## Privacy and security constraints

- Never put passwords, access/refresh tokens, push tokens, message bodies, phone/email values, blood type, exact GPS coordinates, or raw request payloads in audit metadata.
- Do not trust arbitrary `X-Forwarded-For` or client-supplied device/IP headers. Accept forwarded client IP only from configured trusted proxies.
- Use actor IDs and resource IDs only where access to audit records is restricted to authorized administrators.
- Keep audit history append-only to application roles. Any exceptional retention/deletion process must be separately authorized and itself auditable.
- Do not automatically block accounts solely because of a single failed request or shared IP address.

## Validation required before implementation is considered complete

- Tests prove an audit event exists for each P0 success mutation, including profile create/update, exact-location grant/revocation, and explicit contact disclosure.
- Tests prove unauthorized access is denied and creates a safe denial event for the relevant sensitive routes.
- Tests prove audit metadata never contains forbidden fields or values.
- Tests prove audit persistence and protected mutations commit/rollback consistently.
- Tests prove ordinary users cannot read, alter, or delete audit history.
- Admin pagination tests verify stable ordering and total/cursor behavior.
- Production migration and deployment are separately reviewed and explicitly approved.



## Implementation update — 2026-11-22

The source review was cross-checked against the current PostgreSQL-backed implementation before extending this PR. Three earlier gaps in the original matrix were already addressed in the repository and are now marked accurately: donor map visibility changes are audited with their consent/share-revocation transaction; exact-location share activation and revocation each insert an audit row in the same transaction as the share state; and explicit phone/email contact disclosure commits with its audit event.

This PR additionally adds profile create/update audit events in the same transaction as `PUT /v1/profile`, plus donor availability and opt-out audit events. The events contain actor/resource references and minimal state-transition metadata only. Profile events record changed field names without email, display-name, or capability values. Availability events record only the previous/new availability preference. Donor deletion, operational donor disablement, account capability reset, and its audit row now commit together. Repeated unchanged availability/profile synchronization calls do not create duplicate audit noise. PostgreSQL regression tests cover event contents, no-op suppression, and rollback when an audit insert fails.

These are repository-level code and test changes only. CI must validate them on the updated PR head. No production database, migration, or deployment is changed.


## Implementation update — 2026-11-23

Emergency-request creation now writes an `emergency_request_created` event in the same SQLAlchemy transaction as the request and ranked matches. Metadata is limited to the resulting status, matching mode, and match count; requester coordinates, blood type, units, notes, and contact details are excluded. Idempotent retries return the existing request and do not add a second creation event. PostgreSQL integration tests cover the event contents, retry suppression, and rollback of the request when audit insertion is rejected.


## Implementation update — 2026-11-23 (contact lifecycle)

Requester contact lifecycle changes now insert a `contact_<status>` audit event in the same transaction as the status update. The event actor is the authenticated principal; metadata contains only the previous and resulting status, with request and donor references stored in their dedicated columns. The separate post-commit audit write was removed. PostgreSQL integration tests cover a successful accepted-to-contact-shared transition and verify that an injected audit insert failure rolls the status back to `accepted`.


## Implementation update — 2026-11-23 (donor profile create/update)

Donor-profile creation and meaningful updates now emit `donor_profile_created` or `donor_profile_updated` audit events. Metadata contains changed field names only, never field values, coordinates, display names, notes, or contact preferences. The donor profile, account donation capability, operational donor mirror, and audit row are committed in one transaction. PostgreSQL integration tests cover create/update metadata and rollback when the audit insert is rejected.


## Implementation update — 2026-11-23 (donor GPS presence)

The authenticated `POST /v1/donor-profile/presence` flow now writes a `donor_presence_recorded` audit event in the same transaction as the profile/operational donor location freshness update. Audit metadata is limited to the client-reported provider label (or `unknown`) and a coarse precision bucket (`high`, `medium`, `low`, or `very_low`). Coordinates and raw precision values are not copied into the audit event. The API explicitly rolls back and returns a retryable service error if the audit insert fails. PostgreSQL integration tests verify event metadata privacy and transaction rollback. The legacy `/v1/donors/{donor_id}/presence` route remains to be reconciled separately.


## Implementation update — exact-location read disclosure audit

- The matched-requester exact-location GET now records `exact_location_viewed` before returning coordinates.
- The event identifies the authenticated actor, request, and donor; its metadata is empty and never contains coordinates, precision, or location payload.
- Repeated polling for the same request/donor share is deduplicated to one disclosure event, avoiding audit-volume amplification.
- The endpoint fails closed with a retryable 503 if the audit transaction cannot commit; exact coordinates are not returned in that case.
- PostgreSQL integration coverage verifies successful disclosure, metadata privacy, and duplicate-read suppression.


## Implementation update — conversation message and saved-message auditing

- Successful message sends, saved-message creation, and saved-message removal now create content-free audit events in the same transaction as their data mutation.
- Events identify the authenticated actor, request, and donor; message text, attachment content, and message payloads are excluded from metadata.
- Idempotent repeated saves and no-op unsaves do not create duplicate audit events.
- Message-send and bookmark routes fail closed with a retryable 503 when the audit transaction fails.
- PostgreSQL integration coverage checks event attribution/privacy and message rollback when audit insertion is rejected.


## Cross-feature audit acceptance criteria (carried forward from closed chat and matchability work)

- Keep message-send, save, and unsave audit records content-free. Actor/resource references are sufficient; message text, attachment bytes, and bookmark payloads must never enter audit metadata.
- Treat matchability and eligibility as server-authoritative. Audit the persisted server-side transition, not a client-provided eligibility assertion.
- Keep each sensitive mutation and its audit event in one database transaction. An audit insert failure must roll back the mutation and must not disclose exact location or report a successful message/bookmark action.
- Preserve idempotency: retries and no-op operations must not create duplicate audit events.
- Before merge, retain PostgreSQL integration evidence for privacy, rollback, actor attribution, and duplicate suppression across the current-head routes.


## 2026-11-28 device attribution update

The backend now normalizes request User-Agent values through `device_class()` before writing either `lifelink_user_sessions.device_used` or `audit_events.device_used`. Stored labels are limited to coarse platform categories: Android device, iOS device, Windows device, macOS device, Linux device, or Unknown device. Browser versions, device model identifiers, and arbitrary User-Agent strings are not persisted. The classification is informational telemetry only and is never an authentication or authorization signal. The server peer IP remains the source for `ip_address`; forwarded headers remain untrusted until the Render proxy chain is explicitly configured and verified. Focused tests cover known platforms, missing headers, and generic OkHttp clients.
