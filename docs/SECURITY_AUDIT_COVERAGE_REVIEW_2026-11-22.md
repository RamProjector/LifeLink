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
| `PUT /v1/push-token` | Push endpoint credential registration | No | Record registration/removal outcome; never record token |
| `POST /v1/emergency-requests` | Creates sensitive emergency/location request | No | Record created request ID, outcome, and matching mode; never copy coordinates or medical details into metadata |
| `POST .../contact` | Initiates contact with selected donors | Yes | Retain; verify failed/denied attempts are also observable |
| `PATCH .../contacts/{donor_id}` | Changes contact lifecycle/status | No | Record state transition and request/donor references |
| `POST .../report`, `POST .../block` | Safety/moderation actions | Yes | Retain; ensure reason text is not copied into broadly readable metadata |
| `POST .../manual-broadcast` | Expands emergency request audience | Yes | Retain; verify failures/denials |
| `POST .../cancel`, `POST .../fulfill` | Ends or resolves emergency request | Yes | Retain; ensure actor and prior/new status are represented |
| `PUT /v1/donor-profile` | Donor identity/profile changes | No | Record profile create/update without blood type, coordinates, or free-text note |
| `POST /v1/donor-profile/presence` | GPS/presence update | No | Record that a location observation was accepted, provider label if validated, precision band; do not store coordinates in audit metadata |
| `PATCH /v1/donor-profile/availability` | Changes availability | No | Record previous/new availability |
| `DELETE /v1/donor-profile` | Deletes donor profile | No | Record deletion intent/result and target reference |
| `PUT /v1/donors/{donor_id}` | Legacy donor profile create/update | No | Record profile mutation; verify legacy route still required |
| `POST /v1/donors/{donor_id}/presence` | Legacy GPS/presence update | No | Same privacy-safe event as current presence route |
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
2. **High-impact successful mutations without explicit audit events.** The current review still identifies emergency-request creation, contact-status transitions, donor profile/availability changes, GPS presence updates, exact-location reads, message sending, and saved-message changes as candidates for explicit events. Profile create/update audit coverage is added in this PR; map visibility, exact-location grant/revocation, and contact disclosure already have atomic audit writes in the inspected repository.
3. **Exact-location reads are not explicitly audited.** This should be evaluated as a high-priority privacy access event.
4. **No complete detection-rule/alert lifecycle demonstrated by these files.** Error records alone do not establish an IDS/IPS engine, alert acknowledgement, deduplication, or automated response.
5. **Pagination metadata is incomplete.** Admin audit endpoint returns page items but not total count, making total-based pagination and reliable page counts impossible.
6. **Session/device/IP attribution is not represented as dedicated fields in the inspected audit model.** Add only after defining trusted-proxy rules, data minimization, retention, and access controls.

## Implementation order

### P0 — Audit the highest-impact changes
- Add explicit audit events for emergency-request creation and cancellation/fulfillment outcome consistency.
- Add explicit audit events for remaining donor profile/availability changes. Profile create/update and map-visibility consent events are already covered or added in this PR.
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

This PR additionally adds profile create/update audit events in the same transaction as `PUT /v1/profile`. The event contains only the actor ID, action, and changed field names. It deliberately excludes email, display name, role/capability values, and all request payload values. Unchanged profile synchronization calls do not create duplicate audit noise. PostgreSQL regression tests cover create/update event contents, no-op suppression, and rollback when the audit insert fails.

These are repository-level code and test changes only. CI must validate them on the updated PR head. No production database, migration, or deployment is changed.
