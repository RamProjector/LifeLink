# LifeLink GPS Location Provider Provenance — Follow-up Research

**Scope:** documentation-only source audit. This is a follow-up to PR #156 and the existing `docs/DEEP_SOURCE_AUDIT_LOCATION_CHAT_ATTACHMENTS.md`.

**Status:** Findings are source-informed; this document does not implement changes or claim client-side behavior that has not been verified.

## Finding 52 — GPS acquisition provider/source is not a first-class persisted field in the inspected database model

### What the inspected model stores
The inspected `lifelink_fastapi/app/db_models.py` models persist location-related values such as donor latitude/longitude and PostGIS location, last-presence time, location precision, requester coordinates, and request location precision. `AuditEvent` provides actor/action identifiers, optional request/donor references, JSON metadata, and a timestamp.

### What was not found as an explicit persisted field
The inspected model did not show a dedicated, typed field or location-observation entity for:
- acquisition provider/source (for example Android fused provider, GPS provider, or network-derived fix);
- per-observation capture timestamp distinct from server receipt/processing time;
- per-fix accuracy/precision and provenance lifecycle;
- app/platform/provider version associated with the fix.

The generic audit metadata JSON could technically carry such information, but its existence is not evidence that the application currently sends or persists it. Confirm Android location collection and request serialization before concluding whether provenance is available in the client only.

### Keep these concepts separate
1. **Location acquisition source:** Android/system source that produced a coordinate fix.
2. **Map rendering provider:** map SDK/tile source used to display a map. This does not identify the GPS source.
3. **Action actor:** requester/donor/admin/app/backend that initiated a location-related operation.
4. **Location-sharing state:** whether a donor consented to share exact location for a particular request.
5. **Coordinate observation:** the actual fix and its timestamp/accuracy.

A map provider label must not be presented as the GPS provider. Provider identity is diagnostic metadata, not proof that a coordinate is accurate or spoof-resistant.

### Recommended design for future implementation (not implemented)
If provider provenance is a product requirement, consider an append-oriented location-observation record with:
- observation ID;
- subject/reference (donor or request) and capture purpose;
- source/provider name and normalized provider category, when actually reported;
- client capture time and server receipt time;
- reported accuracy/precision, if available;
- app/platform version where useful and privacy-approved;
- upload/processing outcome and optional link to a relevant audit event.

Keep exact coordinates in the protected location store rather than duplicating them in ordinary audit logs. Avoid collecting device identifiers or detailed diagnostic metadata unless justified. Unknown/manual/provider-unavailable sources should be represented honestly as unknown or manual—not fabricated as GPS. Define access controls, retention, deletion behavior, and privacy-policy disclosure before adding fields.

## Finding 53 — Request-scoped location sharing is authorization/consent state, not GPS provenance

### Backend behavior observed in `main_postgres.py`
The inspected routes include:
- requester activation of a donor’s request-scoped location sharing;
- donor revocation of that sharing;
- exact-location retrieval with privacy checks.

The activation path checks request state, donor/request matching, donor exact-sharing consent, request-scoped revocation state, and rate limits before persisting the share and notifying the donor. The revoke path is donor-controlled. Exact-location retrieval checks share and privacy state and suppresses coordinates when sharing is missing, revoked, expired, stale, or the request has ended.

### Interpretation
These controls are meaningful privacy gates, but they answer **who may access exact coordinates and under what consent state**. They do not record which Android provider produced a fix. A share record should not be treated as a location-observation record.

### Remaining client-side/cache questions
The inspected backend behavior does not by itself prove that every Android screen clears already-rendered exact coordinates immediately after:
- donor revocation while requester map is open;
- request expiry while a location marker is cached;
- location permission being revoked after a fix was captured;
- an offline upload/retry or stale cached fix;
- share state changing while the app is backgrounded.

These need a client lifecycle/cache audit and manual or automated tests. Backend authorization should remain the source of truth; client cache clearing is an additional privacy safeguard.

## Finding 54 — AuditEvent is an extension point, but provenance coverage is not guaranteed

### Existing model capability
The inspected `AuditEvent` model has actor, action, optional request/donor references, metadata JSON, and creation time. This is flexible enough to record location-related events, but no standardized event taxonomy or required metadata schema was confirmed from the model alone.

### Suggested event taxonomy
If adopted, define event types such as:
- `location_observed`;
- `location_uploaded`;
- `request_location_changed`;
- `location_share_activated`;
- `location_share_revoked`;
- `map_visibility_changed`.

Only add `location_permission_changed` if the Android app can reliably observe the transition and the collection is necessary. Permission status should not be inferred from a failed location request.

For each event, distinguish device capture time from server receipt/processing time, define required and optional fields, and document whether corrections are new events or updates. Avoid raw coordinates and unnecessary device identifiers in audit metadata. Restrict audit access and define retention/deletion handling. Do not describe the log as tamper-proof unless integrity controls actually exist.

## Verification scenarios before implementation
- Provider/source metadata survives serialization and persistence without being replaced by a map-provider label.
- Capture time and server receipt time remain distinct, including delayed offline uploads.
- Manual coordinate entry is labeled manual; missing provider metadata stays unknown.
- Permission denial or provider failure never fabricates a successful fix/provider.
- Retry/idempotency does not create misleading duplicate observations.
- Revocation, request expiry, and donor opt-out prevent subsequent exact-location reads and clear client-visible cached pins.
- Audit logs avoid unnecessary precise coordinates/device identifiers and obey access/retention rules.

## Explicit non-claims
- No code or database migration has been implemented in this document.
- This audit does not establish that the Android client currently collects provider names; that requires tracing the location API call sites and serialization path.
- No test, build, or runtime behavior was newly executed as part of this document.
- These findings are specific to LifeLink’s requester/donor model; do not import TACResQ responder/driver/dispatcher roles.

## Sources inspected
- `lifelink_fastapi/app/db_models.py`
- `main_postgres.py` location-sharing activation, revocation, and exact-location route handlers (around lines 1267–1390 in the inspected version)
- `privacy_repositories.py` location-share persistence, revocation, map-visibility, and exact-location access methods
- Existing PR #156 research branch and `docs/DEEP_SOURCE_AUDIT_LOCATION_CHAT_ATTACHMENTS.md`
