# LifeLink Deep Source Audit: Location, Chat, Attachment Storage, and Authorization

**Research date:** 4 November 2026  
**Repository:** RamProjector/LifeLink  
**PR context:** docs/improvement-research-2026-11-03 / PR #156  
**Scope:** documentation/research only. No application code, API contract, schema, production data, or deployment changes.

## Executive summary

The selected source review found several concrete follow-up risks:

1. Donor map coarsening uses a fixed 0.01-degree latitude/longitude grid. Degrees are not uniform distances: longitude cell width shrinks with latitude. This is not a universal guarantee of a minimum 1 km cell.
2. The donor map emits one approximate entry per donor, even though IDs are omitted. Overlapping/duplicated entries may reveal donor density and create noisy markers.
3. Exact-location share activation has a 120-minute cap and request expiry, while donor presence freshness is a separate clock. Verify that exact-location reads also enforce freshness, match/contact state, donor consent, and request status.
4. Central privacy repository helpers are good foundations, but a route-by-route authorization audit is still needed. Endpoint existence and SQL comments do not prove all routes call the correct checks.
5. The current message model is text-only (body max 2,000 chars); migration 007 has no attachment entity. Image/video sharing is a separate feature.
6. Supabase Storage is a possible durable storage option, but inspected LifeLink source does not prove a bucket, upload endpoint, Android integration, or policies currently exist.

## Evidence checked

- lifelink_fastapi/app/privacy_api.py:
  - DonorMapEntry excludes donor ID and exposes coarsened coordinates.
  - ExactLocationOut coordinates are nullable when shared=false.
  - MessageIn body length is 1–2,000 characters.
  - ContactShareIn only permits phone/email fields.
- lifelink_fastapi/app/privacy_repositories.py:
  - MAP_GRID_METERS = 1000; _coarsen() uses floor(value * 100) / 100.
  - Map query requires map opt-in, availability, profile visibility, and fresh non-null last_presence_at.
  - Map rows are emitted per donor, without donor ID.
  - Visibility changes and share activation/revocation use transactional audit writes.
  - Share activation is capped at 120 minutes and request expiry.
  - Donor map hide or disabling exact sharing revokes active shares.
- lifelink_fastapi/sql/007_donor_map_chat_contact_sharing.sql:
  - Stores location shares, request-scoped conversations, text messages, and contact shares.
  - Migration comments describe policy but are not themselves enforcement.
- docs/IMPLEMENTATION_STATUS.md:
  - Reports privacy/chat behavior historically, while also recording live/device verification caveats. Its status date is not fresh live confirmation.
- docs/LOCATION_SHARING_AND_CHAT_RESEARCH_PLAN.md:
  - Explicitly deferred WebSockets, attachments, moderation tooling, and physical-device testing.
- docs/REQUESTER_DONOR_LIFECYCLE_AUDIT.md:
  - Treats participant-only chat and request-scoped location as acceptance requirements; does not prove every route is compliant.

## Finding 1 — Fixed-degree coarsening is not a universal metric guarantee

The implementation snaps both latitude and longitude to a 0.01-degree lattice. Latitude degrees are approximately constant in distance; longitude degrees scale by cosine(latitude). The longitude side of a cell therefore gets shorter at higher latitudes. MAP_GRID_METERS is used as a radius label, but the coordinate lattice itself is not a uniform 1 km metric grid worldwide.

Recommended correction:
- Decide whether the app officially supports only a bounded geographic region or worldwide coordinates.
- If limited to the Philippines, calculate and test worst-case cell width/diagonal across the supported bounds; avoid a universal 1 km claim.
- If global, use metric projection or a geospatial cell system with conservative resolution.
- Return a privacy-safe cell/centroid and uncertainty radius that covers the real cell.
- Add tests for supported-region extremes, negative coordinates, cell boundaries, and nearby donors on opposite sides of a boundary.
- Ensure payloads, marker accessibility text, logs, analytics, and screenshots do not restore raw precision.

This is a privacy precision issue, not just a map styling concern.

## Finding 2 — Per-donor map entries may leak area density and duplicate markers

The query loops over donors and emits one map entry per donor. IDs are omitted, but repeated entries may reveal that several donor records occupy one area, overlap on the map, and make the UX noisy.

Investigate:
- Server aggregation by coarse cell.
- Broad density bands rather than exact counts, only if useful and privacy-reviewed.
- Minimum cohort suppression for sparse cells, while assessing usefulness in low-density regions.
- A shared aggregated response for map and accessible list.
- Do not use deterministic fake/jittered pins as a substitute for privacy coarsening.

Product should decide whether the map needs individual approximate areas or aggregate donor density before implementation.

## Finding 3 — Location freshness and share expiry are separate clocks

Map results use a configured last_presence_at freshness cutoff. Exact sharing is capped by request expiry and a fixed 120-minute window.

Recommended policy to test:
- Share expiry must not outlive the donor location snapshot being disclosed; consider request expiry, share duration, and freshness expiry together.
- Every exact-location read checks request active, requester owns the request, donor is still matched/accepted as required, donor sharing remains enabled, share is active/unexpired, and donor presence is fresh.
- Hide/pause, profile visibility change, cancellation/fulfillment/expiry, contact revocation, or account deletion must revoke or deny access according to defined rules.
- Define whether a GPS refresh extends an existing share or requires renewed consent; never silently extend disclosure because presence refreshed.
- Use server UTC consistently and test expiry boundaries plus concurrent activation/revocation.

Do not promise exact share duration in UI until the actual policy is finalized.

## Finding 4 — Authorization audit must be route-by-route

Create an operation matrix and trace each route to authentication dependency and repository checks:

| Operation | Identity/relationship check | Lifecycle gate |
|---|---|---|
| Donor map read | authenticated or explicit public policy | opt-in, availability, profile visibility, freshness |
| Map visibility update | donor owns profile | valid donor capability/profile |
| Share activate | valid request-donor match; actor allowed | donor consent, contact acceptance if required, active request |
| Exact location read | requester owns request and donor pair | active share, no revocation/expiry, donor freshness/consent |
| Share revoke | donor owns share or explicit requester policy | stale activation cannot undo revoke |
| Conversation create | requester owns request; donor is eligible | accepted contact/request state |
| Conversation/message read/write | authenticated participant | block, request state, rate limits |
| Contact share | participant and conversation authorization | field-specific consent + audit |
| Report/block | conversation participant | abuse controls and rate limits |
| Attachment upload/download/delete | participant plus request/conversation authorization | object ownership, content limits, retention, block/closed state |

Test guessed IDs, cross-account IDs, swapped requester/donor, stale tokens, revoked contact, blocked pair, closed request, expired share, race between revoke/read, and account switching. Verify response bodies and logs contain no sensitive data.

## Finding 5 — Message text is not an attachment architecture

The inspected SQL stores messages as text; API body limit is 2,000 characters. Migration 007 contains no attachment entity, and prior research explicitly deferred attachments.

For future image/video messaging, use a separate attachment metadata model:
- opaque attachment ID;
- conversation/request and uploader user ID;
- object key (not public URL);
- media category and verified MIME/type;
- byte size/checksum;
- upload/scan status;
- created time and retention/deletion state.

Do not store large binaries in PostgreSQL message rows. Do not trust client filename, extension, MIME header, or dimensions. Enforce upload limits server-side, validate actual file signatures where feasible, reject unsupported/executable formats, define video size/duration constraints, and prevent unsafe downloads from executing in-app.

## Finding 6 — Supabase Storage is an option, not confirmed LifeLink functionality

The inspected sources do not establish a bucket, storage SDK usage, attachment endpoints, or RLS policies for chat/request media. Supabase Auth/Postgres does not imply Storage is configured.

If adopted:
- Use a private bucket. Public buckets allow anyone with the URL to retrieve files.
- Use opaque object keys; avoid user IDs in URLs/paths.
- Enforce access through Storage RLS and/or backend checks that verify actual conversation/request participation. Object ownership alone does not authorize the other participant.
- Use short-lived signed download URLs or authenticated downloads. Signed URLs remain usable until expiry and are not instantly revoked by deleting a share row.
- Never embed a service-role key in Android; it bypasses Storage RLS.
- Configure bucket size/type limits, server-side validation, retention, and deletion.
- Test unauthorized access, participant/block state changes, closed requests, deleted attachments, expired signed URLs, and account switching.
- Coordinate object deletion and metadata lifecycle. Supabase warns direct deletion of Storage metadata can leave underlying objects orphaned; use Storage APIs rather than SQL edits to internal Storage tables.

## References

- Supabase Storage access control: https://supabase.com/docs/guides/storage/security/access-control
- Supabase bucket privacy model: https://supabase.com/docs/guides/storage/buckets/fundamentals
- Supabase serving/downloads and signed URL caveats: https://supabase.com/docs/guides/storage/serving/downloads
- Supabase storage schema warning: https://supabase.com/docs/guides/storage/schema/design
- OWASP MASVS: https://mas.owasp.org/MASVS/
- OWASP privacy controls: https://mas.owasp.org/MASVS/controls/MASVS-PRIVACY-1/
- OWASP storage controls: https://mas.owasp.org/MASVS/05-MASVS-STORAGE/
- Android location permissions: https://developer.android.com/develop/sensors-and-location/location/permissions/runtime
- Android adaptive layouts: https://developer.android.com/develop/ui/compose/layouts/adaptive

## Verification and next actions

Verified in source: API model constraints; coordinate coarsening helper; map eligibility gates; share activation cap and transaction/audit structure; SQL entities for messages/conversations/contact shares; no attachment entity in inspected migration; attachment feature was deferred.

Not verified: every route authorization; current live DB policies; Render/Supabase configuration; existing bucket; storage enabled; UI reachability; physical-device behavior; current CI or CodeRabbit status.

Next:
1. Trace every privacy/chat route in main_postgres.py to authentication and repository helpers.
2. Inspect RequestMatch, contact lifecycle, conversation blocks, and expiry together; build state transitions from actual enum values.
3. Inspect Android message DTO/repository/ViewModel/screen to map crash-prone navigation and attachment extension points.
4. Check configuration for Storage dependencies/bucket names without exposing secrets.
5. Add integration tests for map precision and share-expiry behavior.
6. Only then propose a focused implementation PR. Do not bundle attachment infrastructure with map precision changes.

**Change log:** 2026-11-04 — source-level location/chat/storage audit and Supabase Storage security research; documentation only.


## Finding 7 — New Supabase Storage security advisory needs version/config verification

A current Supabase Storage advisory published October 14, 2026 reports a critical signed-resumable-upload authorization bypass affecting Storage API versions <= 1.80.1, fixed in 1.80.2. The reported path involved specially encoded/absolute request targets bypassing signed-upload token checks and reaching privileged DB access. This advisory is relevant only if LifeLink uses a self-hosted or otherwise affected Storage API version; it is **not evidence that LifeLink's hosted Supabase project is vulnerable**.

Action before enabling resumable uploads:
- Determine whether LifeLink uses Supabase-hosted Storage or self-hosted Storage, and record the actual managed service/version evidence available (do not infer version from the Android SDK).
- If self-hosted, verify the Storage API is patched to 1.80.2 or later before exposing signed resumable upload routes.
- Include signed-upload route abuse and overwrite attempts in the storage security test plan; test encoded paths, authorization failures, object overwrite policy, and upload token scope.
- Avoid enabling resumable uploads merely to support chat videos until the service/version and server-side authorization model are verified.

Source: Supabase Storage advisory GHSA-8rrf-w776-x75g, published October 14, 2026: https://github.com/supabase/storage/security/advisories/GHSA-8rrf-w776-x75g

## Finding 8 — PostgreSQL API has background expiry sweepers, but they do not replace read-time authorization

A fresh read of `lifelink_fastapi/app/main_postgres.py` confirms the app lifespan starts three async sweepers: stale location-share expiry, stale donor-presence expiry, and timed-out request expiry. The share sweeper defaults to 300 seconds between runs. This is useful defense-in-depth, but it creates a potential interval between a lifecycle event/deadline and cleanup. Therefore, exact-location and chat routes must deny access based on current state at read/write time; correctness must not depend on a sweeper having run already.

The same file shows request creation uses an authenticated requester identity check, verified-email requirement, rate limit, and idempotency-key serialization. Selected-donor contact checks requester ownership and excludes contacting the requester's own donor profile. These are positive controls in the inspected routes, but they do not certify all endpoints or all lifecycle transitions.

Audit follow-up:
- Inspect every exact-location, conversation, message, moderation, contact-share and saved-message route individually; confirm current request/contact state is loaded and checked before read/write.
- Test immediately after deadline/cancel/revoke, without waiting for the sweeper; assert denial on the first request.
- Add race tests where a read/send starts concurrently with cancel, block, revoke, or expiry. Define whether transaction ordering allows a final message during the transition.
- Ensure sweeper failures are observable through metrics/alerts; current loop catches exceptions and continues, which is resilient but may hide prolonged failure without monitoring.

## Additional verification boundary

The current source excerpt confirms sweeper setup and several request/contact authorization checks. It does **not** prove the exact-location/chat routes are correct, that the live Supabase service has any particular Storage version, or that Android currently has upload support. These remain open investigation items.


## Finding 9 — Route-by-route review reveals specific lifecycle and consistency gaps to resolve

I fetched the actual route section of `lifelink_fastapi/app/main_postgres.py` rather than relying on the previous plan alone. This narrows several earlier open questions.

### Confirmed route protections

- Donor-map visibility update checks donor identity against the authenticated subject (with a development-user bypass used in test/development paths).
- Location-share activation loads the request, checks requester ownership, active request status, matched donor, donor opt-in, and refuses reactivation after request-scoped revocation. It rate-limits activation.
- Exact-location read delegates to `exact_location_for_requester(request_id, donor_id, principal.subject)`; repository checks requester ownership, donor match, share state/expiry, request active/expiry, donor consent, and GPS freshness.
- Share revoke checks authenticated donor identity and uses a per-donor row lock in the repository.
- Conversation create checks requester or donor participant identity and then verifies donor match in repository.
- Conversation list is scoped to the authenticated user; message read, saved-message read/save/unsave, contact-share read, report, and block routes call `conversation_for_participant`.
- Message send checks participant membership, request active/deadline, rate limit, and block state before insert.
- Contact-share create checks participant membership, rate limit, and block state; contact disclosure is audit-recorded.
- Report/block endpoints require conversation participation and rate-limit actions.

### Concrete issue A — message/block race is explicitly accepted in current code

The message-send route comments document a TOCTOU window: block state is read before message insertion, so a block committed between the check and insert can allow one message through. This is a known accepted race, not an unobserved possibility.

Decision needed:
- If “block immediately prevents any later message” is a strict safety requirement, serialize block and message writes (for example, lock a shared conversation row or use a transaction/constraint design that both paths participate in).
- Add a deterministic concurrency test: pause send after block check, commit block, resume insert, and assert the agreed outcome.
- If retaining current behavior, document the one-message race in user-facing policy and acceptance criteria; do not describe blocking as instantaneous in all races.

### Concrete issue B — message history is intentionally or accidentally readable after request closure?

The message-list route checks participant membership but does not visibly apply the request active/deadline gate that message-send does. This may be intentional (participants retain their own history after an emergency request ends), but it needs an explicit privacy/product decision.

Define:
- Closed request: can both participants still read prior messages?
- Blocked participant: can they still read old history, or should block hide/restrict the conversation?
- Deleted/deactivated account: retention, redaction, and legal/audit behavior.
- If history remains readable, make sure no new message/contact/location disclosure is allowed and attachment downloads follow the same retention policy.

Do not “fix” by blindly hiding all closed-request history; that may harm emergency records and user continuity. Specify expected behavior first.

### Concrete issue C — contact-share creation has no explicit request-status gate in the route

The contact-share endpoint verifies participant and block state and records an audit event, but the route does not visibly check request active/deadline before creating a new disclosure. Unlike message sending, it may permit phone/email disclosure after a request is closed, depending on repository helper behavior.

Recommended decision and test:
- Should contact sharing be allowed only while request is active and both parties remain eligible?
- Should a previously shared contact remain visible after request closure? That is distinct from allowing a *new* disclosure.
- Add tests for closed, expired, cancelled, fulfilled, and blocked conversation states.
- If policy requires active-only disclosure, add the lifecycle check in the service boundary and enforce it again in the repository transaction to reduce races.

### Concrete issue D — conversation creation can be called by a donor after request closure unless repository state rejects it

The route checks request existence and participant identity, then match status through repository. It does not visibly check request active/deadline before returning or creating the conversation. Sending is guarded later, but creating a conversation after closure may be unnecessary or confusing.

Decide whether post-closure opening should:
- return the existing read-only conversation for history, or
- reject creation/opening if no conversation existed before closure.

Implement consistent UI/API behavior after product decision. Avoid letting a closed request silently create a new communication channel.

### Concrete issue E — contact-share audit is not in the same transaction as the disclosure in the route

The route calls `add_contact_share(...)`, then separately calls `record_audit_async(...)`. Unlike location-share activation/revocation and map-visibility changes, which explicitly commit privacy state and audit together in the repository, this endpoint appears to perform contact-share persistence and audit in separate steps. Verify whether `add_contact_share` commits internally; if it does, a later audit failure could leave contact data disclosed without its audit record.

Recommended:
- Move contact-share insert + audit into one transaction/repository method.
- Add failure-injection test for audit insert failure and assert disclosure rolls back.
- Keep notification/push outside the transaction after successful commit; handle push failure as non-fatal.

### Concrete issue F — request state guard and contact lifecycle need a shared policy helper

The code repeats status/deadline checks in several routes, and the exact-location repository also checks state. Duplication can drift (message send checks expiry; conversation open/contact share may not).

Recommended:
- Define shared policy functions for “request can accept new communication”, “history can be read”, “location disclosure allowed”, and “contact disclosure allowed”.
- Keep route-level HTTP mapping and repository-level enforcement for state-changing sensitive actions.
- Add table-driven tests across statuses (active, fulfilled, cancelled, expired) and deadline boundaries.
- Do not treat donor match alone as proof that requester/donor contact exchange remains permitted.

## Attachment-specific security design (OWASP refinement)

OWASP's File Upload Cheat Sheet says extension and Content-Type checks are insufficient alone; uploads need an allowlist, server-side size controls, generated storage names, content/signature validation, safe private storage, authorization, and malware scanning where feasible. For LifeLink, the practical feature scope should start with **photos only**, then add video after limits and UX are validated.

Suggested staged scope:
1. **Phase 1 (image-only):** JPEG/PNG/WebP allowlist; server verifies actual decodable image type, size and dimensions; strip EXIF/location metadata by decode/re-encode where possible; random object key; private bucket; conversation membership + request state check on upload/download; no arbitrary client path.
2. **Phase 2 (video):** only after product sets maximum size/duration, upload strategy, quota, playback compatibility, and abuse/reporting flow. Do not assume resumable uploads are needed on day one.
3. **All phases:** validate upload and download permissions separately; block cross-conversation object ID substitution; prevent storage path traversal; never expose service-role credentials; use short signed URL TTL; provide report/delete/retention workflow; log metadata only, not media or signed URLs.
4. **Do not upload sensitive request evidence to a public bucket.** A “hard-to-guess” URL is not authorization.

Source: OWASP File Upload Cheat Sheet https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html

## Updated verification table

| Area | Source-confirmed | Still unresolved |
|---|---|---|
| Exact location | Requester ownership, matched donor, share/consent/freshness checks in repository | Live deployment parity; freshness/expiry edge and race tests |
| Chat participant access | Participant helper on read/write and moderation endpoints | Whether helper rechecks request state; closed-history policy |
| Message send | active/deadline check, block check, rate limit | Documented block/message race |
| Contact share | participant/block checks, audit call, rate limit | Closed-request disclosure policy; audit transaction atomicity |
| Conversation open | participant and donor-match checks | Whether post-closure create/open should be permitted |
| Attachments | No attachment API/entity found in inspected route/model/migration | Full repo search for alternate implementation; Android integration; bucket/policy |
| Media security | OWASP guidance reviewed | No LifeLink implementation exists to verify |

**Next research target:** inspect `SqlAlchemyPrivacyStore.add_contact_share`, `conversation_for_participant`, `block_participant`, and message persistence transaction behavior; then inspect Android conversation API/ViewModel/navigation to correlate the user-reported crash after request submission. This should precede any code patch.
