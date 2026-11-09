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


## Finding 10 — Repository implementation confirms contact disclosure and audit are separate commits

The repository implementation was inspected directly:

- `add_contact_share()` inserts the contact disclosure and immediately commits it.
- The API route then calls `record_audit_async()`, which inserts the audit event and commits separately.
- Therefore, the disclosure can persist even if the later audit write fails. This is confirmed by the current code structure, not just a concern inferred from route order.

**Recommended fix:** move contact-share insert and audit insert into one repository transaction, with notification sent only after commit. Add a failure-injection test that forces audit insertion to fail and verifies no ContactShare row remains. Ensure the endpoint returns success only after both disclosure and audit are committed.

This is a meaningful privacy/audit consistency defect because the code comment describes the contact-share endpoint as always audit-recorded, but the two commits are not atomic.

## Finding 11 — Blocking is one-way, but persistence and message checks are not serialized

Repository inspection confirms:
- `block_participant()` inserts a one-way block and commits.
- `is_blocked()` checks whether the sender is the blocked party, correctly avoiding silencing the person who initiated the block.
- `add_message()` inserts a message and updates conversation timestamps in its own commit.
- The API checks `is_blocked()` before calling `add_message()`.

This confirms the previously documented race: the block transaction and message transaction do not share a lock/serialization protocol. A send can pass its block check, then commit after the block.

Suggested implementation research:
1. Lock the conversation row for both block and send operations, in the same order.
2. Re-read block state after acquiring the lock.
3. Insert message / update activity timestamp while holding the lock.
4. Commit; release lock.
5. Add deterministic concurrency tests for requester-blocks-donor and donor-blocks-requester directions.

Do not use a client-side “blocked” flag as enforcement. The server remains authoritative.

## Finding 12 — Contact-share values are returned to both conversation participants

The contact-share listing route returns every share's `value` after checking only that the caller is a conversation participant. This appears intentional (the disclosed contact is shared within the conversation), but it should be documented as a persistent disclosure: the sender's phone/email remains retrievable from the conversation history after request closure or a later block.

Policy questions:
- Does blocking prevent future contact disclosures only, or also hide already disclosed values?
- Can a participant revoke/delete a prior phone/email disclosure?
- Should account deletion redact contact values while preserving an audit event?
- Is the retention period tied to emergency-request records or user account retention?

Avoid treating “block” as a retroactive erasure of information already seen or copied. The app should explain that limitation clearly before sharing.

## Finding 13 — Conversation participant helper accepts donor row ID and donor user ID

The helper intentionally recognizes either the donor profile row ID or its owning user ID as donor identity. This supports older/legacy identity mappings, but it increases the number of identifier forms that must be consistently handled by:
- conversation list filtering,
- block creation and enforcement,
- push recipient selection,
- Android account/session switching,
- authorization tests.

Add tests where donor profile ID differs from authenticated donor user ID. Verify that both identity forms map to the same participant, but neither identity can access another donor's conversation. Specifically test block rows written with each supported identity form to ensure neither form bypasses a block.

## Android crash follow-up — evidence boundary

The repository searches performed in this pass did not locate Android conversation ViewModel/screen source using the searched class names, so I cannot claim to have traced the reported crash. The existing Android HCI roadmap does document the four primary destinations (Home, Requests, Chat, Profile), map navigation differences across compact/wide layouts, and the need to clear stale conversation state on account/role switches. This is useful design evidence but not a stack trace or crash reproduction.

Next Android investigation should search the actual app module tree (rather than guessed filenames), then trace:
- Request-submit success/navigation event and destination route arguments.
- Chat tab selected while request ID/conversation ID is absent or stale.
- Conversation list empty/loading/error states.
- API 401/403/404/409 responses and token refresh.
- Compose state restoration, saved-state keys, and account/role switch cleanup.
- Crash logs/stack trace from the user's device or reproducible emulator scenario.

Do not claim the crash fixed until a reproduction or relevant code path is verified.

## Updated implementation order (risk-first)

1. **P0 privacy audit integrity:** make contact-share row + audit event atomic; add rollback test.
2. **P0 moderation consistency:** serialize block and message-send, or formally accept and document race with user-facing limitation.
3. **P1 lifecycle policy:** decide closed-request history and contact disclosure behavior; apply consistent policy helpers and tests.
4. **P1 identity compatibility:** cover donor-row/user-ID variants in conversation, block, and push tests.
5. **P1 Android crash:** locate actual module files and obtain stack trace/reproduction; patch only after root cause is supported.
6. **P2 media feature:** private image attachment MVP, separate implementation PR and storage configuration review.



## Finding 14 — Android source tree is now located; the chat entry path is request-card driven

A repository-tree inspection located the Android files that earlier filename guesses missed:

- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/privacy/ConversationScreen.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/privacy/PrivacyViewModel.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/core/navigation/LifeLinkShell.kt`
- `LifeLinkAndroid/app/src/androidTest/java/com/lifelink/app/ConversationNavigationTest.kt`
- `LifeLinkAndroid/app/src/test/java/com/lifelink/app/PrivacyViewModelTest.kt`

This is a useful correction to the earlier evidence boundary: source files and navigation tests do exist, so the next pass can trace concrete UI state. It still does **not** establish the user's crash root cause without reproducing it or inspecting relevant ViewModel branches and device logs.

### What the screen currently does

The inspected `ConversationScreen`:
- opens the conversation through `PrivacyAction.OpenConversation(requestId, donorId)` from a `LaunchedEffect(requestId, donorId)`;
- starts an 8-second refresh loop only while lifecycle is RESUMED, and cancels the loop when lifecycle leaves RESUMED;
- scrolls to the latest message whenever message count changes;
- enables the composer when loading is false and conversation ID is non-null;
- clears the draft immediately after dispatching SendMessage, before a confirmed server success;
- displays a generic “Conversation unavailable” empty state when conversationId is null;
- displays report/block controls inside the conversation.

### Specific UI-state risks to inspect, not yet proven crash causes

1. **Draft loss on failed send.** The UI clears draft immediately after action dispatch. If request fails due to timeout, offline state, 401/403, 409, or backend error, user may lose the unsent message. Recommended UX: retain draft until ViewModel reports success; expose sending/failed/retry states and avoid duplicate sends on retry.
2. **Open action accepts potentially blank identifiers.** The screen dispatches OpenConversation directly from requestId/donorId without local nonblank validation. Confirm upstream navigation always guarantees valid IDs; otherwise guard before dispatch and show a recoverable state. Backend authorization remains mandatory.
3. **Refresh polling cadence and error handling.** Polling every 8 seconds is lifecycle-aware, but verify overlapping requests cannot accumulate if one refresh takes longer than 8 seconds, and ensure transient failures do not replace a valid thread with an unrecoverable error.
4. **Auto-scroll behavior.** Every message-count change animates to the bottom. This may pull users away from older history while they are reading. Consider auto-scroll only when already near bottom or when the current user sends a message; provide a “new messages” affordance when scrolled up.
5. **Safety action after closure/block.** The screen shows report/block and contact-share UI whenever the chat is ready. Server must determine whether those actions are allowed; UI visibility alone is not authorization. Ensure state reflects blocked/closed request and does not leave contact share enabled if backend rejects it.

## Finding 15 — Existing Compose navigation tests cover entry points, but not the reported post-submit tab crash

The Android instrumentation test `ConversationNavigationTest.kt` contains meaningful checks:
- requester opens chat from a matched contact card;
- donor opens chat from an accepted/arrived request;
- state restoration causes conversation-open action to be dispatched again;
- requester chat requires a request ID and uses the saved result ID over matching fallback;
- message action visibility is tied to contact/request states;
- map top-level navigation and loading/error/empty states are tested.

These tests are **not evidence of the user's reported crash being covered**. The test currently opens chat through request/contact card actions, and its state-restoration assertion expects OpenConversation to be invoked twice after restoration. The reported scenario is different: send a request, then tap the Chat/messages destination/tab and crash.

### Missing regression scenario to add after root cause is understood

- Submit a requester emergency request successfully.
- Transition through matching/loading/results with the exact returned request ID.
- Navigate to Chat/messages tab (not only the contact-card Message button).
- Exercise no-conversation, conversation-loading, failed-open, and successful-open states.
- Repeat after process/activity restoration and account/role switch.
- Assert no crash, no stale request/donor IDs, and a visible recoverable state.
- Include backend error fixtures (401/403/404/409/5xx) and network timeout.
- Prefer deterministic fake repository/state tests plus a small navigation integration test; do not make every PR depend on flaky external UI or live backend tests.

### State-restoration caution

Because the current Compose test deliberately verifies that restoring state triggers another OpenConversation action, inspect whether `PrivacyViewModel` makes opening idempotent and whether duplicate requests are deduplicated/cancelled. Restoration may legitimately re-fetch, but it should not create duplicate conversation rows or concurrent polling jobs.

## Finding 16 — Screen behavior suggests a product/UI distinction: chat is request-scoped, not a universal inbox

The inspected conversation screen requires both `requestId` and `donorId`, and the tests open it from matched requester contact cards or accepted donor requests. This supports a request-scoped conversation model. It does **not** establish that a generic fourth-tab inbox can safely navigate without a request/conversation identifier.

Before moving Chat into a persistent bottom-navigation destination:
- define whether it is a conversation list/inbox or a direct active-request chat destination;
- if inbox, list conversations server-side for the authenticated account and navigate by opaque conversation ID (then backend resolves participants and request context);
- if only active request chat, retain request/donor identity requirements and provide an empty/disabled state when no eligible request exists;
- never manufacture a request ID from local selection or trust a client-supplied donor ID as authorization;
- preserve role-specific navigation but keep the same server participant checks.

This matters for the user's earlier request to replace the Alerts tab with Chat: simply pointing a global tab at the current ConversationScreen may cause missing-ID crashes or wrong-thread access. The proper UI depends on a real conversation-list endpoint/state model.

## Updated Android evidence status

| Evidence | Status |
|---|---|
| Conversation screen source | Located and partially inspected |
| Compose entry/navigation tests | Located; tests request-card entry and state restoration |
| Reported crash after request submit → Chat tab | Still not reproduced or traced |
| PrivacyViewModel action/error handling | Next source inspection required |
| Main shell tab route and saved state | Next source inspection required |
| Android physical device crash logs | Not available in this research pass |

**Next focused pass:** inspect `PrivacyViewModel.kt`, `LifeLinkShell.kt`, `PrivacyRepository.kt`, and the relevant `PrivacyViewModelTest.kt` cases around OpenConversation, request submission, and account switching. Then map the actual user-reported sequence to a missing state or exception. No crash fix should be claimed until this is done.


## Finding 17 — ViewModel generation guards prevent stale chat responses, but open/refresh/send still have concurrency gaps worth testing

The actual `PrivacyViewModel.kt` is now inspected. It has a `conversationGeneration` token and cancels the prior `conversationRefreshJob` when starting a refresh. Open results and several mutation results compare their captured generation before updating UI. This is a real safeguard against an old conversation response replacing a newly opened conversation.

### Confirmed implementation strengths
- Opening a new conversation increments the generation and clears old conversation ID/messages/contact shares before the request starts.
- A failed open leaves conversation ID null rather than retaining the previous thread.
- Message-send, saved-message, and contact-share responses check the captured generation before updating UI.
- The refresh job is cancelled before a replacement refresh starts.
- The ViewModel exposes a conversation list and loading/error state, which suggests a list/inbox capability exists at the state/repository level even though the inspected ConversationScreen itself is request-scoped.

### Remaining race / state defects to investigate

**A. OpenConversation can overlap with a prior refresh**
The generation is incremented at the beginning of open, but the old `conversationRefreshJob` is only cancelled inside `refreshConversation()`, after the new openConversation request succeeds. An older refresh may continue doing network reads while a new open request is pending. The generation check prevents its final UI update, but it can waste network work and briefly complicate state transitions. Cancel the old refresh immediately when opening a different thread, and test open-A → open-B while A's message/share reads are blocked.

**B. Refresh is sequential but partial failure is silently converted to empty lists**
The refresh path uses `getOrDefault(emptyList())` for messages and contact shares, and a fallback for saved messages. If a transient messages endpoint failure occurs, the screen can replace a populated conversation with an empty list without displaying an error. This is a high-impact trust/UX defect: users may think their messages disappeared. Preserve last-known data on read failure and expose per-section refresh/error state; distinguish a legitimate empty response from failed retrieval. Add tests for message-read failure, contact-share failure, and saved-message failure separately.

**C. send action is not guarded against duplicate taps in ViewModel**
The screen exposes `sending`, but the ViewModel's `sendMessage()` does not reject a second send while `sending == true`. A fast double tap or duplicate action can create two server messages. Add an atomic/in-flight guard keyed to conversation generation and message operation, disable the composer while sending, and test double dispatch. Consider idempotency keys on the backend for retry/network ambiguity; UI guards alone cannot guarantee exactly-once delivery.

**D. Refresh/send completion state race**
Send success sets `sending=false` then triggers a refresh. A refresh can also be running from the periodic screen effect. Verify concurrent refreshes are deduplicated/cancelled consistently, especially if one is slow. Consider one shared refresh coordinator with a single-flight policy per conversation ID.

**E. Cross-conversation operation state**
The generation guards prevent old send results from updating the new thread, but the old request still runs until completion. Confirm opening a new thread immediately resets `sending` and any contact-share/report/block transient UI state; otherwise the composer may stay disabled or show a stale message while the new conversation is ready. Add open-A → send-A pending → open-B tests.

## Finding 18 — Shell has a request-scoped conversation overlay and a separate Chat-history destination; these are not yet the same feature

The inspected `LifeLinkShell.kt` maintains `showConversation`, `conversationRequestId`, `conversationDonorId`, and `conversationOpenedFromDonor` as saveable state. The request-specific conversation is opened only when both IDs are nonblank. The shell also includes a separate `ShellTab.MESSAGING` destination and text labeled “Chat history.”

This corrects the earlier design assumption: there is a separate messaging/history destination in the shell, not just the request-scoped conversation screen. The remaining research task is to inspect its list content and click route. The reported “tap the message tab crashes after sending a request” could originate in that destination or its state restoration, rather than the request-scoped chat screen.

### Important test targets for shell navigation
- Messaging tab with zero conversations and no request ID.
- Messaging tab with one or several conversations; click each row and verify it opens the matching request/donor thread.
- Tab switch while a conversation is open; ensure the request-scoped overlay and tab selection do not conflict.
- Send request → navigate to messaging tab → open chat, with submission states Idle/Saving/Submitting/Matching/ManualFallback/QueuedOffline/Error.
- Back button from conversation returns to the exact originating workspace and selected tab.
- Activity/process restoration with blank, stale, or partially restored conversation route fields.
- Switch account/role while conversation is open; ensure no prior user’s conversation state or polling survives.
- Rapid repeated tab clicks and repeated conversation-row clicks; verify one navigation and no duplicate OpenConversation requests.

## Finding 19 — Android platform guidance reinforces a lifecycle/state separation, not a direct crash diagnosis

Current Android guidance recommends keeping business/data logic in ViewModels, collecting state lifecycle-aware, and saving small navigation/UI identifiers through saved-state APIs when process recreation matters. Compose effects are tied to composition and should own work that belongs to that UI element. These are architecture principles, not evidence that LifeLink violates them in a specific crashing line.

Sources:
- Android Developers, [Compose and other libraries](https://developer.android.com/develop/ui/compose/libraries): ViewModel ownership/scoping and Compose effects.
- Android Developers, [Lifecycle in Jetpack Compose](https://developer.android.google.cn/topic/libraries/architecture/lifecycle?hl=en): lifecycle-aware state collection and effect ownership.
- Android Developers, [Save UI states](https://developer.android.google.cn/topic/libraries/architecture/saving-states?hl=en): distinguish ViewModel memory from state that survives process death.
- Android Developers, [State hoisting](https://developer.android.com/develop/ui/compose/state-hoisting): keep state at the lowest common owner and expose immutable UI state/actions.

### Applying this to LifeLink (research recommendation)
- Persist only stable routing identifiers (conversation ID/request ID and source workspace/tab), not message lists or exact location data in saved instance state.
- On restoration, re-authorize/re-fetch thread data from the server; never treat restored IDs as permission.
- Make shell route state a single sealed destination or validated route object rather than multiple loosely coupled booleans/strings if inspection confirms contradictory combinations are possible.
- Keep request-specific conversation and messaging inbox navigation distinct and explicit.
- Do not use platform architecture guidance as a substitute for crash logs or reproducing the actual user sequence.

## Updated next-step priority

1. Inspect the full `ShellTab.MESSAGING` content and click handler; this is now the most relevant unexplored crash path.
2. Add ViewModel tests for failed reads preserving last-known messages, double-send suppression, and open-A/send-A/open-B races.
3. Reproduce the exact post-submit tab navigation with request states and capture Logcat exception + stack trace.
4. Only then implement the narrowest correction, with manual UI validation and existing non-flaky regression tests.


## Finding 20 — The Messaging inbox is a separate route with its own loading and notification-open effects

**Inspected source:** `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/privacy/MessagingScreen.kt` and the `ShellTab.MESSAGING` branch in `LifeLinkShell.kt`.

The actual top-level Messages tab renders `MessagingScreen`; this is distinct from the request-scoped `ConversationScreen` overlay.

Observed behavior:
- `MessagingScreen` dispatches `PrivacyAction.LoadConversations` from `LaunchedEffect(Unit)` when the screen enters composition.
- It supports loading, error/retry, empty inbox, and conversation-list states.
- A notification target is transformed into a conversation ID plus event ID. The screen waits until loading ends and the matching conversation exists, then opens it by request ID/donor ID.
- Selecting a list row also opens by request ID/donor ID; the row does not pass the conversation ID directly to the shell.
- The screen's list key is `conversation.conversationId`, which is stable and appropriate for lazy-list identity.

### Product/UI issues verified in source
- Inbox rows display a truncated opaque conversation ID as the primary title and avatar initials, and a truncated request ID as secondary text. This is not a human-readable participant identity and gives users little context for selecting the correct thread.
- The list displays only a raw last-message timestamp (or “No messages yet”); it does not show message preview, unread count/state, participant display name, or a meaningful request summary.
- The header says messaging is with “matched donors and requesters,” while the empty-state copy says messaging becomes available after a “matched donor or requester accepts a contact request.” This terminology should be checked against the actual supported relationship states and role combinations; it risks teaching the wrong prerequisite.
- A notification target that never matches a loaded conversation is silently ignored after loading ends. There is no explicit “conversation not found / access expired” feedback in this composable. The route should fail visibly and safely rather than leave the user on a seemingly unrelated inbox.
- Inbox loading is initiated by a one-time composition effect. This is appropriate for entry-triggered loading, but refreshing after returning to the tab, after a new contact relationship becomes eligible, or after process restoration depends on whether the screen re-enters composition and whether the ViewModel owns refresh policy. This should be tested explicitly rather than assume every tab visit refreshes.

**Recommendation:** improve inbox row identity using the authorized participant display name (or safe fallback), a short request label/reference, a message preview and timestamp, and a clear unread indicator only if the backend actually exposes unread state. Do not derive unread state from timestamps or fabricate it client-side. Add explicit handling for notification targets that cannot be resolved after a successful inbox load. Keep the list row opening request/donor identifiers only if that pair is the canonical server-authorized route.

## Finding 21 — The inbox notification effect is state-driven, but “not found” and stale-target cases are untested

In `MessagingScreen`, the notification effect keys on the notification key, the conversation list, and its loading flag. It records a target as handled only after finding a matching conversation. This makes it possible for the effect to retry as the list changes, which is good. However, after loading completes without a match, no user-visible outcome is produced.

Risk cases to test:
1. Notification points to a conversation deleted, closed, revoked, or no longer visible to this account.
2. The target is valid but inbox loading fails; the existing error/retry UI should not accidentally open stale target data.
3. User switches account/role while a notification target remains in shell state; ensure no previous account's conversation is opened.
4. The same conversation ID receives two notification events; event ID should distinguish them if event-specific behavior is intended, without repeatedly navigating on unrelated recompositions.
5. Notification destination changes while the inbox is already loaded.
6. Notification target is malformed or has blank/missing components.

**Recommendation:** define the notification destination contract centrally (typed destination or validated parser), clear/consume stale notification targets when account identity changes, and surface a safe fallback for an unresolved target. Re-authorize server-side on opening; a notification ID is a navigation hint, never an authorization credential.

## Finding 22 — The current navigation tests prove contact-card chat entry, not the user's request-submit → Messages-tab sequence

The existing `ConversationNavigationTest` covers requester contact-card entry, donor accepted-request entry, restoration, and back navigation. Its helper renders a synthetic accepted contact result and records `PrivacyAction.OpenConversation`. It does **not** exercise:
- completing/submitting an emergency request and then selecting the top-level Messages tab;
- inbox load success/error/empty transitions through the real shell;
- the `MessagingScreen` row click through the shell overlay and back;
- a stale or empty `PrivacyUiState` after request submission;
- a notification-targeted inbox entry;
- account identity changes while inbox/chat is retained;
- crash/exception assertions or captured Logcat from the reported sequence.

The existing tests are useful coverage, but they cannot establish that the reported crash is absent. The code path now narrows the likely investigation: shell navigation into `ShellTab.MESSAGING` → `MessagingScreen` composition → `LoadConversations` ViewModel action → repository conversation-list call and its state/error mapping → row selection/open conversation. The exact crash remains **unconfirmed** until an exception stack trace or reproducible failing test identifies the throwing line.

### Proposed deterministic regression suite (no broad/flaky UI automation required)
- ViewModel test: `LoadConversations` success populates inbox and clears prior error.
- ViewModel test: failure preserves prior inbox data (if policy permits) and exposes a retryable error; do not silently replace real data with empty.
- ViewModel test: account/session change invalidates pending inbox and chat completions.
- Compose/shell test: request-submit completion → tap Messages → loading then empty/list state, with no crash.
- Compose/shell test: inbox row → request-scoped conversation → back returns to Messages, not an unrelated request screen.
- Compose/shell test: notification target missing from loaded inbox produces visible safe fallback.
- Manual reproduction: capture the complete exception + stack trace and exact request status, role, tab sequence, and app build. Do not mark the bug fixed from a successful unrelated chat-entry test.

## Research synthesis — revised crash triage order

1. **Start at the actual Messages tab, not only ConversationScreen.** Confirm if the crash occurs before the inbox appears, while loading, or after tapping a row.
2. Inspect the `LoadConversations` repository implementation and backend endpoint response shape/error mapping. Check empty, unauthorized, expired relationship, malformed response, and offline behavior.
3. Check the shell's account/session transition and notification destination state for stale values.
4. Add deterministic ViewModel tests first; add one narrow Compose shell test for the exact user path. Avoid relying on broad emulator visual tests to diagnose the exception.
5. Capture Logcat for the user's exact sequence before proposing a code fix. Keep root-cause status “unconfirmed” until evidence identifies the fault.

## Additional Android official guidance used for these findings

- Android Developers, [Side-effects in Compose](https://developer.android.com/develop/ui/compose/side-effects): `LaunchedEffect` is tied to composition and its keys; effects cancel when leaving composition. Use effect keys deliberately and keep side effects controlled.
- Android Developers, [Lifecycle of composables](https://developer.android.google.cn/develop/ui/compose/lifecycle?hl=en): composition entry/exit differs from recomposition; stable lazy-list keys help Compose identify items.
- Android Developers, [Lifecycle in Jetpack Compose](https://developer.android.google.cn/topic/libraries/architecture/lifecycle?hl=en): use lifecycle-aware state collection and keep screen-scoped work tied to the appropriate lifecycle.
- Android Developers, [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices): stable lazy-list keys and efficient list item identity are important for predictable list behavior.

These official documents support lifecycle and list design recommendations; they do not prove the reported crash's root cause.


## Finding 23 — Inbox loading lacks the conversation-generation protection already used for active chat

**Inspected source:** `PrivacyViewModel.kt`, `PrivacyRepositoryImpl.kt`, and FastAPI `GET /v1/conversations`.

The active-chat path uses `conversationGeneration` to ignore responses from superseded conversation opens and refreshes. The inbox path does not have equivalent protection:

- `loadConversations()` launches a new coroutine on every `LoadConversations` action.
- It sets loading/error state, calls `repository.conversations()`, and applies success/failure directly to the shared StateFlow.
- There is no inbox request generation token, cancellation/job replacement, or session/account identity check.
- A slow older inbox response can therefore overwrite a newer inbox response if multiple loads overlap.
- More seriously, if the same ViewModel survives an account/session transition, an in-flight response associated with the old identity could publish old account conversations after the identity changed, unless surrounding app/session architecture guarantees the ViewModel is destroyed/reset. That guarantee was not established by the inspected files, so treat it as a high-priority verification item—not a confirmed cross-account leak.

The repository implementation simply calls `api.listConversations()`, checks HTTP success and non-null response body, and maps the returned records. It does not itself attach a user/session generation. The API request is expected to use the currently configured authenticated client; investigate whether token refresh/session replacement can occur while a request is in flight.

### Backend authorization observation
The FastAPI `GET /v1/conversations` route derives the principal from `get_postgres_principal` and passes `principal.subject` to `store.list_conversations_for_user`. This is a server-side account-scoped list query, which is the correct trust boundary. The route returns an empty list for the special `development-user` principal. No client-supplied user ID is used to select the inbox.

**Important distinction:** server authorization of each response does not prevent a client from displaying a stale response after account switching. Server-side access control and client-side identity-bound state clearing are complementary protections.

### Recommended fix direction (research, not implemented)
- Add an inbox request generation/cancellation strategy; only the newest request should update loading/error/data state.
- Bind the inbox state to authenticated account/session identity and clear it on identity change/sign-out. Do not rely on role switching alone to imply a new identity.
- If session identity is available to the ViewModel, include it in request keys or pass an identity token to the state owner; never use a client-supplied ID as backend authorization.
- Consider a single-flight refresh policy for rapid repeated tab entry/retry, while still allowing explicit refresh after a meaningful state change.
- Define whether stale cached inbox content should remain visible during refresh failure. If retained, label it stale and never allow stale entries to bypass server authorization when opened.
- Add tests where load A starts, load B starts and finishes first, then A finishes; verify A cannot overwrite B. Add a separate session-switch test with A's request pending when account B signs in.

## Finding 24 — Inbox and active-chat failures currently have different stale-data semantics

The inbox load failure path sets `conversationsError` and `conversationsLoading=false`, but leaves `conversations` unchanged. This preserves previously loaded list data while showing an error. That may be a useful stale-while-revalidate experience, but the UI currently prioritizes the error branch over the list, hiding the retained list until retry succeeds.

By contrast, active conversation refresh uses `getOrDefault(emptyList())` for messages/contact shares, so a read failure can replace the previously visible messages or disclosures with an empty list. This is an inconsistency between inbox and thread semantics:
- Inbox failure retains data in state, but error UI hides it.
- Thread failure can erase data in state by interpreting a failed request as empty success.

**Recommendation:** define explicit loading, fresh, stale-with-error, and empty states for both inbox and thread. Empty should mean a successful server response with zero records—not a network/auth/parse failure. For contact shares and exact location, apply stricter privacy rules if retaining stale disclosure data could display a revoked value; do not apply generic stale caching blindly. For ordinary chat history, decide whether last-known messages can remain visible with a prominent refresh-failed indicator, while preventing sends until authorization/session state is confirmed.

## Finding 25 — The backend list is authorization-scoped, but inbox display needs a role-aware identity contract

The backend list returns conversation IDs, request IDs, donor IDs, requester IDs, last-message time, and creation time. The Android `Conversation` domain model maps those fields directly. The current inbox UI uses only conversation ID, request ID, and last-message time.

This creates a data-contract/UI gap: the response includes both participant IDs, but neither Android inbox nor the current endpoint response provides a display name or safe request label. Avoid making UI names by truncating IDs. Options for future design:
- Add a minimal server-authorized participant summary (display name + stable role label), respecting profile privacy and account visibility settings.
- Or resolve participant profile data through an authorized endpoint and cache only non-sensitive fields with account-scoped invalidation.
- Provide a non-identifying fallback (“Requester” / “Matched responder”) and short request reference if profile names are unavailable.
- Do not expose email, phone, exact location, or contact-share values merely to make inbox cards more informative.
- Confirm how legacy donor ID vs donor user ID normalization affects display/profile lookup; use canonical IDs internally and avoid guessing identity from string formats.

## Revised crash and privacy triage after endpoint inspection

1. Check the actual crash stack trace first; the code reviewed so far has no obvious crash statement on the empty-list path itself.
2. Add request-generation/session-bound protection to inbox loading as a correctness and privacy-hardening task, independent of whether it is the crash cause.
3. Test backend list endpoint with authenticated requester, donor, no conversations, revoked/closed relationship, expired token, and development principal. Confirm expected status/empty behavior and no cross-user rows.
4. Test overlapping inbox loads and account switch with a delayed fake repository.
5. Decide stale-data semantics explicitly across inbox, messages, contact shares, and location; do not conflate empty with failed read.
6. Keep crash status unconfirmed until reproducing the actual user flow with exception details.



## Finding 26 — Account sign-out clears auth state, but does not itself clear the messaging ViewModel

**Sources inspected**
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/core/auth/AuthSessionStore.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/core/auth/SupabaseAuthRepository.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/privacy/PrivacyViewModel.kt`

### Confirmed behavior
- `AuthSessionStore.clear()` clears persisted auth preferences and publishes `session = null`; it schedules account cleanup through the injected `onAccountRemoved(ownerId)` callback.
- `SupabaseAuthRepository.signOut()` delegates to `sessionStore.clear()`.
- `PrivacyViewModel` stores inbox conversations, active conversation ID, messages, contact shares and location state in its own `StateFlow`. The ViewModel code inspected has no session/user identity field and no action that resets the entire privacy state when auth becomes null or changes owner.
- `AuthSessionStore` defaults `onAccountRemoved` to a no-op callback. The actual production construction/wiring must be traced before concluding whether account cleanup is connected to this ViewModel.

### Risk / what is not yet proven
This is a **cross-account stale UI risk to verify**, not a confirmed cross-account data leak. If the shell retains the same `PrivacyViewModel` across sign-out/account switch and cleanup does not clear its state, the next account could momentarily see the previous account's inbox/chat state. Backend participant authorization still protects API reads/writes, but it does not automatically erase already-rendered local state. Need inspect DI/session observer and reproduce with two test accounts before calling it a vulnerability.

### Recommended design
- Make account identity an explicit boundary for account-scoped UI state (session user ID or a monotonically increasing auth epoch).
- On sign-out and account replacement, synchronously clear inbox, active conversation, message list, contact shares, exact location, and any notification deep-link target before exposing the next account's UI.
- Cancel account-scoped jobs and prevent their completion from publishing after the auth epoch changes. A ViewModel generation token alone is insufficient if the ViewModel survives and its token is not bumped at logout.
- Keep server authorization as the security boundary; local reset is a privacy/UI hygiene and stale-state prevention measure, not a substitute for auth.

### Regression cases
1. Account A loads inbox and opens a conversation; sign out while requests are pending; verify all sensitive state disappears immediately.
2. Sign in as B without process restart; verify A's conversation IDs/messages/contact shares are absent before B's inbox loads.
3. Delay A's inbox/message response, sign out and sign in as B, then release A's response; verify it cannot mutate B's state.
4. Confirm the actual `onAccountRemoved` wiring invokes cleanup and determine whether that cleanup includes `PrivacyViewModel` state or only persisted account rows.

## Finding 27 — The HTTP auth interceptor uses a live token provider, but in-flight requests can outlive the session transition

**Source:** `LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/remote/RetrofitProvider.kt`

### Confirmed behavior
- `RetrofitProvider.create()` accepts a dynamic `tokenProvider` and adds a bearer token when the provider returns a nonblank value.
- On a 401, the interceptor invokes an optional refresh callback, closes the original response, and retries the original request with the refreshed bearer token.
- The interceptor does not attach an explicit account/session epoch to a request. The refresh callback and token provider are external dependencies, so their behavior during sign-out/account switch depends on the app's wiring.

### Risk / verify before changing
A request already sent with Account A's bearer token can still complete after A signs out. Likewise, a delayed 401 refresh/retry should not be allowed to revive A's token after B becomes active. The existing auth repository checks that refresh is based on the same current session and its `replace(expected, ...)` is compare-and-set, which is a useful safeguard; however, the API interceptor's broader request lifecycle still needs a test across sign-out and rapid account switching.

### Recommendations
- Bind each authenticated API operation to the session identity/epoch it started under; reject its result if the epoch changes before state publication.
- Before retrying a 401, verify the original request's account/session is still current; never retry an old request using a newly signed-in different user's token.
- Ensure a refresh failure or 401 during sign-out cannot set global session-expired state for a newly signed-in account.
- Test token-provider invocation timing, refresh callback, request retry and response publication using a deterministic MockWebServer test.

## Finding 28 — Conversation inbox query handles donor-row/user-ID aliases, but it has no pagination and does not filter blocked conversations

**Source:** `lifelink_fastapi/app/privacy_repositories.py`, method `SqlAlchemyPrivacyStore.list_conversations_for_user`; route `GET /v1/conversations` in `lifelink_fastapi/app/main_postgres.py`.

### Confirmed behavior
- The backend resolves a donor by row ID or owning user ID, then lists rows where the requester ID matches or donor ID matches either identity alias.
- Results are ordered by `Conversation.updated_at DESC` (newest activity first).
- The method returns the full matching list; there is no limit/offset/cursor in the method or Android response contract inspected so far.
- The list query does not join/filter `ConversationBlock`. Blocking is enforced separately through `is_blocked(conversation, sender_id)` for message/contact-share operations. Thus a blocked conversation can remain visible in the inbox; this may be intentional to preserve safety/report context, but the UX must distinguish “blocked / messaging disabled” rather than silently treating it as an ordinary active chat.

### Scale and UX concerns
- An indefinitely growing inbox is returned in one response and mapped into Android memory. This is a potential latency/payload problem for active deployments, not evidence of current performance failure.
- Ordering uses `updated_at`, which is bumped when a message is added. Verify whether opening a conversation, sharing contact details, moderation, or status changes should affect “recent activity” ordering; currently the inspected message method explicitly updates activity on send, while contact-share insertion does not update the conversation timestamp.
- Confirm database indexes for requester/donor identity and `updated_at` ordering before recommending migration/index changes. Do not add indexes blindly without inspecting model definitions and query plans.

### Recommendations
- Define inbox semantics for blocked, closed, cancelled, fulfilled, or expired request-linked conversations: hide, retain with a status badge, or archive—each needs explicit product/legal policy. Do not infer “blocked = delete/hide”.
- Add cursor pagination only after measuring expected scale and confirming stable sort tie-breakers (e.g. `updated_at DESC, id DESC`).
- Return a bounded, server-authorized summary for each row (safe participant display label, request reference, last-message preview/time and block status if policy permits); avoid phone/email/exact coordinates.
- Add repository/API tests for donor row-ID versus user-ID alias cases, requester identity, block visibility policy, deterministic order ties, empty inbox and pagination boundaries.

## Finding 29 — Inbox ordering can diverge from actual latest conversation activity

**Source:** `lifelink_fastapi/app/privacy_repositories.py`, methods `add_message`, `add_contact_share`, `list_conversations_for_user`.

### Confirmed behavior
- Sending a message explicitly updates both `Conversation.last_message_at` and `Conversation.updated_at`.
- Adding a contact share inserts a `ContactShare` and commits, but does not update the conversation's activity timestamp in the inspected implementation.
- Inbox sorting is based on `Conversation.updated_at DESC`.

### Product consequence
A user who shares contact details in a conversation may not see that conversation move to the top of the inbox, even though a meaningful new event occurred. Whether this is a bug depends on the intended meaning of “recent”: latest message only versus latest conversation activity. The Android list currently lacks preview/event metadata, so the ordering may appear inexplicable to users.

### Recommendation
Specify and test the ordering contract explicitly. If “latest message” is intended, sort on `last_message_at` with deterministic fallback for conversations without messages. If “latest activity” is intended, update activity timestamps consistently for contact-share and other user-visible events, and return the event type/time for the inbox preview. Avoid changing sort semantics until product intent is documented.

### Tests
- Create conversation A, send message to B, share contact in A; assert the agreed order.
- Test conversations with no messages and nullable `last_message_at`.
- Test equal timestamps and stable ordering.
- Verify client displays a matching preview (“message”, “contact shared”) and does not imply a new message when the event is a contact share.


## Finding 30 — Correction to Finding 26: account-scoped ViewModel stores are explicitly keyed and cleared on sign-out/disposal

**Files inspected in follow-up**
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/MainActivity.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/core/navigation/LifeLinkShell.kt`

### Important correction
Finding 26 correctly identified that `AuthSessionStore.clear()` itself does not directly reset `PrivacyViewModel`, but it overstated the uncertainty about app-level lifecycle wiring. The MainActivity code shows a stronger, account-scoped protection:

- The account-specific ViewModel owner is obtained with key `account-models-$accountUserId`.
- The privacy ViewModel is stored under that owner with key `privacy-$accountUserId` (along with similarly account-keyed request/donor/update ViewModels).
- MainActivity installs a `DisposableEffect(accountModels)` that clears `accountModels.viewModelStore` when the owner leaves composition permanently, unless the Activity is undergoing a configuration change.
- MainActivity renders the signed-out AuthScreen when auth state is not `SignedIn`, so sign-out normally removes the signed-in account workspace from composition and triggers this disposal path.

**Revised assessment:** The code does not support the earlier broad claim that a single unkeyed privacy ViewModel is reused across users. It is explicitly account-scoped and its store is cleared on permanent disposal. The original stale-state concern is therefore substantially mitigated by existing code; it should not be reported as a confirmed cross-account leak.

### Remaining edge cases worth testing (not confirmed bugs)
1. Auth state transitions directly from signed-in Account A to signed-in Account B without an intermediate signed-out composition: verify Compose disposes A's accountModels owner and clears its store before B's shell can display.
2. Configuration change: confirm the intended account store is retained for rotation, while the account ID key prevents a different user's state from being resolved from the same store.
3. Sign-out while a privacy request is pending: store clearing should cancel ViewModel-owned work through ViewModel lifecycle; separately verify any repository/app-scoped coroutine launched outside `viewModelScope` cannot publish into a new account.
4. Notification deep-link state and other shell-local `rememberSaveable` values should remain account-keyed or be reset when the account identity changes. In `LifeLinkShell.kt`, some conversation overlay flags use unkeyed `rememberSaveable`; verify whether the shell composition is fully recreated on account transition and whether restored state could ever cross account identity.

### Recommended test emphasis
Prefer a targeted MainActivity/Compose integration test or a deterministic lifecycle test proving owner disposal and `onCleared()`, rather than adding a blanket manual reset that duplicates the current account-keyed store lifecycle. Preserve rotation behavior.


## Finding 31 — Active requests are status-managed, not editable after submission

**Files inspected**
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/emergencyrequest/EmergencyRequestViewModel.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/activeRequest/ActiveRequestScreen.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/repository/EmergencyRequestRepository.kt`
- `lifelink_fastapi/app/main_postgres.py`
- `lifelink_fastapi/app/db_models.py`

### Confirmed Android behavior
- The request wizard supports editing the local `EmergencyRequestDraft` through `UpdateDraft` while the draft is unlocked.
- Once submitted successfully, the ViewModel moves to `RESULTS`, stores the submitted request ID in `resultsRequestId`, and shows matching/contact results. The submitted draft is deleted locally.
- The Active Request screen currently exposes status refresh, contact review, manual broadcast (when applicable), mark fulfilled, and cancel. It does **not** expose an edit action or load the submitted request fields back into an editable form.
- `ActiveRequestSnapshot` contains request ID, status, counters, reason and timestamp—not the full submitted request payload. The local active-request UI therefore cannot edit a submitted request using its current snapshot alone.

### Confirmed backend behavior from the inspected PostgreSQL API/model
- The request API provides create (`POST /v1/emergency-requests`), read status/history, contact/status operations, manual broadcast, cancel and fulfillment-related actions.
- The inspected request routes contain no requester-facing `PATCH`/`PUT` endpoint for editing the request's core fields after submission.
- The SQLAlchemy `EmergencyRequest` row stores the request's submitted payload fields and status. It has an idempotency-key uniqueness constraint and requester/created-time index, but no version/history model for edits was found in the inspected schema.
- Existing idempotency is **not editing**: reusing the same key/payload returns the same request; using the same key with a different payload is rejected. A new draft ID/key can create a different request.

### Conclusion
**Today, the user can edit a draft before submission, but there is no verified in-app/API workflow to edit the core details of an already-submitted active request.** The active request screen is for monitoring and lifecycle actions. Do not tell users that “Edit active request” exists until a real endpoint, UI, authorization rule, and regression tests are added.

### Safe product recommendation
Add a clearly named **“Correct request details”** action only after designing the backend transaction and audit behavior. Do not silently mutate the request in the Android client or resubmit with the same idempotency key.

Separate fields into impact tiers:
- **Low-impact clarification** (for example, correcting a non-sensitive note/description): may be editable with a versioned, server-side update and visible “updated” timestamp, after product/privacy review.
- **Matching/dispatch-critical fields** (need/type, quantity, urgency, location, deadline, eligibility constraints): require a server-authorized atomic update, validation, rematching, and a clear update event for anyone already contacted/notified. Revoke or supersede stale matches/contact invitations where appropriate; do not leave old location/need details active.
- **Terminal requests** (cancelled, fulfilled, expired): should not be silently edited back into active state. Use a new linked/follow-up request or a separately authorized reopen workflow with a reason and audit history.

### Required implementation safeguards (future work, not implemented)
1. Use authenticated requester ownership on the update route; never accept requester identity from the body as authorization.
2. Require optimistic concurrency/version (or `updated_at`/ETag) to avoid overwriting a responder's or another device's newer update.
3. Persist before/after field-level change audit without logging sensitive exact coordinates or contact details unnecessarily.
4. Recompute match eligibility when matching-critical fields change; ensure old matches and contacted donors cannot act on obsolete data.
5. Notify affected participants of material updates; do not re-send broad notifications without explicit policy.
6. Revalidate expiry, consent, location precision and request status inside the transaction.
7. Add API tests for owner/non-owner, concurrent edits, update after cancel/fulfil/expiry, rematching, contact state, and idempotent retry; add Android tests for draft-vs-active edit affordances and stale response handling.

## Finding 32 — Multiple open requests are not clearly prevented server-side, but Android exposes only one “active request” snapshot

**Sources inspected**
- `lifelink_fastapi/app/db_models.py` — `EmergencyRequest` schema
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/local/ActiveRequestEntity.kt` (DAO found via repository search)
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/repository/EmergencyRequestRepository.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/emergencyrequest/EmergencyRequestViewModel.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/activeRequest/ActiveRequestScreen.kt`
- `lifelink_fastapi/app/expiry.py`

### Confirmed data/UI mismatch
- The database has a requester index and created-time index, plus unique idempotency key; the inspected schema does not impose a one-open-request-per-requester constraint.
- The active-request DAO query is `SELECT ... WHERE ownerId = :ownerId ORDER BY lastUpdatedEpochMillis DESC LIMIT 1`: it deliberately returns only one local snapshot.
- The Android request state has one `activeRequest` property, and the Active Request screen renders that single snapshot.
- The backend's active status set includes `matching`, `awaiting_responses`, `partially_fulfilled`, and `manual_broadcast`. Expiry is status/deadline-based; it does not by itself establish that a requester may have only one active request.
- Request history is a list, but history and the single “active request” screen are not equivalent to a multi-active-request management UI.

### What happens if a requester starts a second request?
**The current implementation does not provide a clear user-facing rule.** A second draft with a new idempotency key may create another request if backend business rules accept it; no one-active-request enforcement was found in the inspected database schema. Yet Android's active-request UI/DAO shows only the latest local active snapshot. Therefore multiple server-active requests could become difficult to find/manage from the current single-active screen. This is a potential workflow/visibility defect, not proof that every second submission succeeds in every backend deployment.

Do not use the same draft/idempotency key to represent a different emergency: that key intentionally identifies one submission and can return the prior request or reject changed payloads.

### Recommended policy: distinguish “same incident update” from “new incident”
For the current app, make the second-submit decision explicit instead of silently creating a duplicate:

**When the user taps “Create request” and an open request exists:**
1. Show the existing request card first (status, short reference, created time, and deadline; avoid exposing precise location in the prompt).
2. Offer **“View/update existing request”** as the primary path for the same incident.
3. Offer **“Create a separate request”** only when the user confirms it is a distinct incident/need—not merely a correction or duplicate tap.
4. If product/operations chooses a strict one-active-request policy, block second creation and explain how to update/cancel the current request. This is simpler for the single-active UI, but must not trap a user with a genuinely separate urgent need.
5. If multiple simultaneous requests are allowed, replace the single-active screen/DAO assumption with an **Active requests list** and per-request detail/status/chat/cancel controls. Do not rely on “latest request” to represent all open incidents.

### Recommended default (safer and clearer)
- **Same emergency / changed details:** update the existing request through the audited update flow (Finding 31), never create a second record just to change the address, quantity, urgency, or note.
- **Separate emergency:** allow a distinct request only after an explicit confirmation and after the app can display/manage all active requests. If multi-active UI is not yet ready, warn and provide a clear way back to the existing request rather than silently losing track of it.
- **Before server acceptance / retry state:** prevent duplicate submit taps and use the existing idempotency key for safe retry. A retry is not a new request.
- **Cancelled/fulfilled/expired:** allow a new request with a fresh draft/key; keep the prior request in history. Do not resurrect terminal requests by reusing their old key.

### Tests and open questions
- Backend: same requester submits two distinct keys while first request is active; confirm whether both persist and whether matching/contact flows remain separate.
- Backend: same key + identical payload returns the same request; same key + altered payload rejects as expected.
- Android: create A, navigate away, create B; verify A remains discoverable and its status/contact/chat remains linked to A.
- Android: active A plus draft B offline/queued; prevent B from accidentally replacing A in the active-request UI.
- Android: cancelling A then creating B with a fresh key works (existing regression test: `test_cancel_resets_request_and_allows_a_new_one`).
- Verify whether the real product wants **one active request per requester** or **multiple active requests per requester** before choosing a DB constraint or UX restriction. This is a product/dispatch policy decision, not something to infer from the single-active DAO.
- Check how request-level chat/contact shares and donor notifications should behave when a request is edited, superseded, cancelled, or duplicated.

### External workflow research
- PagerDuty supports editing incident title/priority/urgency and merging duplicates, while keeping the incident open; this supports the principle that updates should be controlled and incident-centered rather than creating duplicate records for every correction. Its merge/edit workflow is not directly transferable to LifeLink's emergency request model. <Cite refs={["turn956053search2","turn153741search6"]}/>
- Incident-management systems commonly preserve incident activity/timeline and record edits so responders can understand what changed during an active response. LifeLink should similarly make meaningful corrections visible and auditable rather than silently rewriting dispatch-critical data. <Cite refs={["turn153741search1","turn153741search10"]}/>


## Finding 33 — Active-request edit capability is not currently present in the requester UI; draft editing must not be mistaken for incident editing

**Source-level confirmation (Android):**
- `ActiveRequestScreen.kt` renders the single active snapshot, status/details/contact activity, manual broadcast where applicable, fulfil and cancel. There is no Edit/Correct request action in the screen’s shown action set.
- `EmergencyRequestViewModel.kt` exposes `UpdateDraft` and `EditStep`, which mutate the in-progress `EmergencyRequestDraft`; these are draft/form actions, not updates to a submitted server request.
- The submitted request repository boundary exposes submit, refresh, history, contact, cancel/fulfil workflows; in the inspected requester repository API path there is no edit/update-existing-request method.
- Therefore, **the app presently has draft editing, but no verified requester-facing active-request correction workflow**. This is stronger evidence than simply noting that a button is absent from one screen, but it is still scoped to the inspected Android and repository paths—not a claim that no admin/backend operation exists anywhere.

### Design recommendation: explicit correction workflow, not arbitrary mutation
Add a **“Correct request details”** entry on the active request details screen, only for editable fields and statuses. Treat the edit as a new, versioned update to the same request ID—not a new emergency request and not a destructive overwrite.

Suggested interaction:
1. Show current submitted values and a clear **Edit / Correct details** action.
2. Let requester change only allowed fields (for example, contact instructions or location correction); revalidate required fields and GPS/address consistency.
3. Before saving, summarize dispatch-relevant changes and ask for confirmation.
4. Server authorizes the authenticated requester against the request owner; validates status and field allowlist; atomically updates the record and increments a version.
5. Record an audit event containing actor, request ID, changed field names, timestamp, prior/new values where policy permits, and reason. Avoid copying sensitive contact details or exact coordinates into broadly visible logs.
6. Notify the currently relevant responders/contacts that the request was corrected; require them to acknowledge or refresh if the change materially affects response (especially location/urgency).
7. If the request is already fulfilled/cancelled/expired, show it as read-only and offer a **new request** instead of reopening it.
8. If edit fails or times out, preserve the displayed server-confirmed version, show “update not confirmed,” and allow safe retry with an update idempotency key. Do not optimistically claim the responders received it.

### Dispatch safety / concurrency requirements
- Use server-side request versioning (e.g., expected version/ETag) to reject stale edits rather than silently overwrite a newer dispatcher/responder update.
- Define which fields are requester-editable versus dispatcher-only. Do not let a requester rewrite responder assignment, fulfillment, dispatch status, or operational notes.
- If location or urgency changes after responders have started acting, make the update high visibility and ensure notification delivery is tracked; merely updating the database is insufficient.
- Preserve request ID, chat/contact history, and audit chronology across corrections. A correction should not orphan chat or create a parallel incident record.
- Avoid editing immutable incident identity or terminal state; use a new linked request only if operations policy determines this is a genuinely separate incident.

### External research / rationale
- Official College of Policing professional-practice guidance says incident records should maintain a thorough, accessible audit trail covering activity and decisions, and should support command handovers and post-incident review. This supports preserving an auditable correction timeline rather than silently overwriting operationally meaningful fields. <Cite refs={["turn219804search0"]}/>
- Dispatch software documentation describes active-call updates that can change call fields and dispatch targets, with operational logs and current personnel status. This illustrates that active incident edits should be designed as operational updates with downstream effects—not just a form save. This is a product example, not a universal standard. <Cite refs={["turn219804search5"]}/>
- PagerDuty's audit-trail documentation exposes change date, actor, object/action, and detailed change records; this is a useful audit-field model, not an endorsement of its incident workflow for LifeLink. <Cite refs={["turn219804search9"]}/>

### Test matrix before implementation
- Request owner edits an allowed field while request is matching / awaiting response; same request ID remains, history/chat links remain.
- Non-owner attempts edit; server rejects even if Android UI is bypassed.
- Requester edits a field outside allowlist; reject and preserve original.
- Dispatcher changes request concurrently; stale requester edit receives conflict and refreshes instead of overwriting.
- Location correction after responder notification creates visible update/acknowledgment event; verify no old location remains the sole visible dispatch detail.
- Edit request while offline, then reconnect; no duplicate update and no misleading success.
- Edit request during submit/queued retry; prevent race between draft submit and active request correction.
- Terminal request edit is rejected; UI offers a fresh request and retains old history.
- Audit record captures actor/time/field-level change without leaking sensitive values into unrelated views.
- Existing cancel/fulfil/refresh/contact workflows remain unchanged.

**Status:** Source-confirmed absence of an Android requester active-edit action in inspected files; proposed workflow only. No implementation or runtime test performed.

## Finding 34 — Single-active-request presentation creates a recovery and discoverability risk after account data restoration

Finding 32 established that local active-request observation is intentionally single-item. Additional repository inspection reveals a related restore behavior:
- `EmergencyRequestRepositoryImpl.observeActiveRequest()` maps `ActiveRequestDao.observeLatest(requesterId)`, so only the newest local row is exposed as the active snapshot.
- `refreshRequestHistory()` fetches server history and, only when no local active row exists, selects the first nonterminal history item (or first history item if none are nonterminal) and upserts it as the active snapshot.
- This restore path does not rebuild a list of all server-open requests. If multiple active requests exist, it can restore one request while the other(s) remain in history but not in the primary active-request surface. Which item is “first” depends on the API’s ordering; the inspected Android code does not sort the server response itself before selecting.

### Risk and qualification
This does **not** prove data loss: the server history can still contain requests and the local history DAO observes all local rows. The confirmed problem is the mismatch between a single-active presentation/restore path and a backend that may permit multiple open requests. This can lead to a request being hard to discover or manage, rather than necessarily deleted.

### Recommendation
- Product decision first: one active request per requester, or multiple concurrently.
- If one active request is the intended policy, enforce it atomically on the backend (with clear duplicate-vs-separate-incident behavior), and make the UI offer “open existing request” rather than silently hiding a request.
- If multiple active requests are valid, replace the single active-request projection with an active-request collection; restore all nonterminal requests, order by meaningful activity/status, and provide per-request status, chat, cancel, and details.
- Do not pick an arbitrary “first” server history entry as the only active request without documenting and testing the API sort contract.
- Keep terminal history separate from open requests. Do not fallback to showing a terminal request as active merely because the open set is empty.

### Focused regression tests
- Server returns 0, 1, and 2+ nonterminal requests; assert restore result and visible request count.
- Server history ordering is newest-first, oldest-first, and unsorted; verify app does not depend on undocumented ordering.
- Local active request exists while server history includes a newer open request; verify refresh reconciliation policy.
- Local cache cleared on sign-out then account signs in again; restore all relevant requests for that owner without cross-account contamination.
- One request terminal and another active; restore active one, not terminal fallback.
- Two active requests with independent chats/contact updates; opening or cancelling one does not alter the other.
- Offline queued draft plus restored active request; preserve both concepts without conflating draft and server-active request.

**Status:** Confirmed repository behavior; multi-active UX and server policy still require product decision. No code changed.


## Finding 35 — Expiry sweeper delay, stale status, and “what if the request expires while the user is looking at it?”

### Source-confirmed behavior
- Backend request expiry is status/deadline based in `lifelink_fastapi/app/expiry.py`; active statuses are `matching`, `awaiting_responses`, `partially_fulfilled`, and `manual_broadcast`.
- `main_postgres.py` starts a periodic expiry sweep (default interval 300 seconds, configurable), and lazy read paths also apply the expiry rule.
- Android active-request status polling is periodic (30 seconds in the inspected ViewModel). A failed refresh can leave a previously displayed state available; exact user-visible stale duration depends on connectivity and the UI state path.
- Therefore, do not describe the sweep interval as a guaranteed 5-minute stale window: a read can expire a request earlier, while network/app failure can delay the client learning the terminal state.

### What-if cases
1. Request deadline passes while requester is viewing Active Request.
2. Request deadline passes while responder is accepting/responding.
3. Request expires while app is backgrounded, killed, or offline.
4. Sweeper is delayed, crashes, or has a backlog.
5. Device clock is wrong or timezone changes; server deadline remains authoritative.
6. User taps cancel/fulfill at the same moment as expiry.
7. Request appears active from cache but backend already considers it expired.

### Recommended behavior (proposal, not implemented)
- Treat backend state and server time as authoritative for every mutation; client countdown is informational only.
- Show a compact “last checked” / stale indicator when status cannot be refreshed; do not imply the request is currently active if status is unknown.
- On a terminal response, immediately disable active-only actions and refresh request history.
- On failed status refresh, distinguish “couldn’t verify status” from “no active request.”
- Add operational monitoring for sweep duration, failures, backlog, and expired-row counts; alert on sustained failure instead of relying only on log messages.
- Avoid queuing time-sensitive emergency actions offline for automatic replay. Android guidance distinguishes online-only writes for near-real-time transactions from queued writes for non-time-sensitive work; critical status transitions need explicit failure feedback and conflict handling. Source: Android Developers offline-first write strategies and synchronization guidance: <Cite refs={["turn260011search0","turn260011search3"]}/>

### Regression scenarios
- Expiry while active screen open; expiry while backgrounded; app relaunch after deadline.
- Deadline immediately before/after cancel, fulfill, responder accept, and location-share creation.
- Sweep disabled/failing/backlogged while a read occurs; confirm read-path expiry behavior.
- Device clock skew, UTC/naive timestamps, timezone change, daylight-saving transition.
- Status request timeout: cached request must become “unverified/stale,” not falsely “active.”
- Verify terminal status prevents new active-only writes server-side, even if the client button is still visible.

**Evidence level:** Expiry policy and periodic sweeper are source-confirmed. Specific stale UI behavior under all failure modes needs runtime verification. No code changed.

## Finding 36 — “What if an emergency is cancelled, fulfilled, or expired but chat/contact/location actions still exist?”

### Why this is a cross-feature edge case
Request lifecycle, conversation lifecycle, contact sharing, and location sharing are separate capabilities. A terminal request should not automatically be assumed to revoke every capability unless each endpoint and client path explicitly enforces that policy.

### What-if cases
1. Requester cancels, but responders still see an active chat or can send messages.
2. Request is fulfilled, but someone tries to share new contact details or exact location.
3. Request expires while an accepted responder is offline and later reconnects.
4. A notification arrives after cancellation/fulfillment and opens an obsolete conversation.
5. User can still read prior chat but attempts a new message after request closure.
6. A location share is revoked, but an old notification or cached UI still offers a location action.
7. A late or duplicated request mutation races with cancellation.
8. Non-participant guesses/obtains a request or conversation ID and tries to access it.

### Recommended policy to decide explicitly
- Define a per-capability matrix for request states: view request, view historical chat, send message, share/reveal contact, reveal exact location, cancel, fulfill, responder update.
- Re-check authorization and request state on the server for every sensitive operation; UI hiding is not access control.
- Decide whether terminal conversations are read-only, retained for a defined policy period, or closed. Preserve historical evidence only under a documented retention/privacy policy.
- Revoke request-scoped exact-location shares promptly on terminal transitions; do not assume a previously displayed location can be “unseen.”
- Notifications are navigation hints, never authorization. On open, re-fetch the request/conversation and show a safe closed-request fallback when unavailable.
- For any late mutation, return a clear conflict/terminal result instead of silently succeeding or resurrecting the request.

### Regression matrix
- For each terminal status (cancelled, fulfilled, expired), test every action in the capability matrix.
- Owner, assigned responder, unassigned responder, unrelated authenticated user, and signed-out user.
- Direct API call after terminal status, not only button visibility.
- Terminal event racing with message send, contact-share, location-share, and responder update.
- Delayed notification after terminal transition; app process killed/restarted; stale inbox cache.
- Repeated cancel/fulfill/expire and idempotent retry.
- Verify location-share revocation is enforced server-side and does not rely solely on the UI/sweeper.

**Evidence level:** The repository has request expiry and request-scoped location-share enforcement mechanisms, but I have not yet established that every chat/contact endpoint has identical terminal-state gates. Treat this as a high-priority audit question, not a confirmed vulnerability. No code changed.

## Finding 37 — “What if the user submits twice because the first request looks stuck?”

### Risk scenario
A requester taps submit, sees a spinner/network error, taps again, or retries after app restart. The first submission may have reached the server even though the client never received its response. This can create duplicate emergency records or confusingly separate chats unless idempotency is consistent end-to-end.

### Current source note
- Android submission uses an `Idempotency-Key` header (confirmed in the inspected API path).
- This is a positive safeguard, but the header alone does not prove server persistence, uniqueness scope, payload mismatch behavior, or that retries reuse the same key. Those must be verified in the backend route/store and tests.

### Recommended safeguards
- Generate one stable idempotency key per logical submit attempt and reuse it for network retries of that same submission; generate a new key only for a genuinely new incident.
- Backend should atomically persist key + outcome, scoped to authenticated requester and operation; return the original outcome on replay.
- If same key is reused with a materially different payload, reject as conflict rather than silently returning/overwriting.
- UI should show “sending / submitted / unable to confirm” distinctly. A timeout is not proof the request failed.
- Provide a recovery action to check whether the request exists before asking user to submit a second one.

### Tests
- Double tap; timeout after server commit; response lost; process killed after request reaches server; retry after reconnect; same key/same payload; same key/different payload; two devices submitting simultaneously; new incident with new key; account switch with pending submission.
- Confirm duplicate submissions do not create multiple requests/conversations or notify responders twice.

**Evidence level:** Client header is source-confirmed; backend idempotency semantics still need direct route/store and test inspection. No code changed.

## Finding 38 — “What if the requester changes their mind about the emergency category, urgency, or location after dispatch has begun?”

This expands the previously documented missing active-request edit flow into dispatch safety and concurrency.

### What-if cases
- User corrects a typo that has no dispatch impact.
- User changes location, severity, emergency type, or callback details after responders have acted.
- Responder is viewing old request data while requester submits a correction.
- Two edits arrive from different devices, or an edit races with cancellation/fulfillment.
- A correction makes the original assignment inappropriate or invalid.

### Proposed design
- Classify editable fields by dispatch impact: low-impact correction vs dispatch-critical change.
- Keep same request ID and audit old/new values, actor, timestamp, and reason; never silently overwrite history.
- Use server-side version/ETag optimistic concurrency to reject stale edits and ask the client to reload.
- Dispatch-critical edits should trigger a prominent update to assigned responders and require an explicit acknowledgment when the operational workflow needs it.
- Do not automatically change assignment/leader or invent a dispatcher role as part of request editing.
- Terminal requests should not be editable; use a clearly linked follow-up/new request when reopening is not supported.
- If a location is corrected, immediately make the new location authoritative while clearly marking old coordinates as superseded; avoid broadcasting old exact coordinates again.

### Tests
- Concurrent edits from two clients; stale version; location correction after responder acknowledgment; urgency correction; edit/cancel race; edit/fulfill race; edit after terminal state; notification delivery failure; audit privacy and redaction.

**Evidence level:** Active requester edit is absent from the inspected Android requester API/screen path (previous findings 31/33). The impact classification and workflow are proposals requiring product/dispatch policy approval. No code changed.

## Finding 39 — “What if the phone has GPS permission but location is inaccurate, stale, or unavailable?”

### What-if cases
- Permission granted but device location services disabled.
- GPS fix is old, low accuracy, or from a different place than current.
- Indoor/urban-canyon location is inaccurate; user is moving.
- User manually pins a location but the app later overwrites it with GPS.
- User denies precise location but wants to submit an emergency.
- Map tiles/network fail while location coordinates are available.
- User submits a request with a location that is not actually the emergency location (e.g., home/current phone location vs incident location).

### Proposed safeguards
- Separate “phone’s current location” from “emergency location”; label both concepts clearly.
- Display accuracy and freshness where meaningful, plus a confirm/edit map preview before submit.
- Never silently replace a user-confirmed pin with a later GPS fix.
- Provide address/landmark/coordinate fallback and a clear “location unavailable” state; don’t block emergency submission solely because map tiles failed if the product supports a safe manual location path.
- For stale or poor-quality fixes, ask the user to confirm rather than presenting them as precise.
- Keep precise location disclosure scoped to the request and recipient policy; do not expose it in inbox previews or generic notifications.

### Tests
- GPS disabled, permission denied/approximate-only, stale fix, low accuracy, provider unavailable, map offline, manually selected pin then GPS refresh, app background/resume, coordinate/address mismatch, and location update after request submission.

**Evidence level:** This is a product/system risk analysis. Specific current GPS freshness/accuracy handling requires a focused source pass through the Android location/map code. No code changed.

## Finding 40 — “What if the responder is assigned but never acknowledges, loses connection, or goes off duty?”

This directly relates to your earlier requirement: responders/drivers should update the status of the assigned request simply, without manually selecting a “leader” or pretending the team is scattered.

### What-if cases
- Request appears assigned but nobody acknowledges it.
- Assigned responder goes offline or leaves duty mid-response.
- Several responders are on duty together and one person updates status while another has stale UI.
- A responder marks “en route” accidentally or prematurely.
- Responder cannot complete the task but no clear handoff exists.
- Status update reaches server but not requester; or UI says update failed after server accepted it.

### Proposed workflow (for review, not implementation)
- Model the operational unit/team assignment separately from an individual user’s account when the real-world workflow is team-based.
- Keep status transitions simple and role-authorized; don’t require a “leader” selection unless the real dispatch process explicitly needs one.
- Define acknowledgement and timeout behavior: unacknowledged assignment should be visibly pending and escalated/reassigned by an authorized dispatcher/team process—not silently treated as accepted.
- Include actor/time/source in status history. Current status alone is insufficient to reconstruct who changed it.
- Make status writes server-authoritative and idempotent; stale/offline updates must not overwrite a newer status.
- Define allowed transitions (e.g., assigned → acknowledged → en route → on scene → completed, with explicit exception/cancel/unable-to-respond paths) according to actual emergency operations policy, not generic app assumptions.

### Tests
- No acknowledgment; offline assigned member; two team members updating; duplicate status update; stale update; unauthorized role; failed response after commit; handoff; team goes off duty; assignment changes during status update.

**Evidence level:** This is a workflow proposal aligned to your stated operational model. The precise current assignment/status implementation still needs a targeted backend+UI trace. No code changed.

## Findings 41–46 — LifeLink requester/donor/admin cross-role what-if audit (4 Nov 2026)

### Finding 41 — Correct LifeLink role model (correction to prior TACResQ mix-up)
LifeLink's actual backend enum in `lifelink_fastapi/app/db_models.py` contains **REQUESTER** and **DONOR**. TACResQ-specific driver/dispatcher roles do not belong in LifeLink's role matrix. The admin console is a separate privileged capability surface, not a third ordinary enum role: `docs/ADMIN_CONSOLE.md` says user management handles requester/donor capabilities but cannot assign admin status, delete accounts, or disable authentication identities. Admin access uses a separate server-side verification/allowlist path.

Also distinguish a profile's `can_request`/`can_donate` capabilities from its single `role` label. Public profile updates must never grant admin privilege. LifeLink's source/docs describe the blood-request/donation matching workflow, so the what-ifs below use that actual domain.

**Sources:** `lifelink_fastapi/app/db_models.py` (`LifeLinkRoleEnum`); `lifelink_fastapi/app/main_postgres.py` profile upsert; `docs/ADMIN_CONSOLE.md`; `PROJECT_GUARDRAILS.md`.

### Finding 42 — Donor availability has several independent eligibility gates
Source trace:
- `DonorAvailability` defines AVAILABLE, PAUSED, OFFLINE in `lifelink_fastapi/app/donor_api.py`.
- `SqlAlchemyDonorStore.set_availability()` commits the availability change, then best-effort recomputes matches when going available. Recompute failure is logged but does not undo the saved availability.
- New donor rows are initialized `verified=False`; client-supplied verification is not trusted. Profile save preserves server verification.
- `DonorViewModel` explicitly warns that Available may still not match if edits are unsaved, donor is unverified, profile hidden, or location stale.
- Donor profile edits and availability changes can trigger match recomputation.

**Cross-role what-ifs:** donor taps Available with unsaved blood-type/location edits; available-but-unverified/hidden/stale donor does not appear; recompute fails after availability commit; donor changes blood type/radius after requester sees a match; donor pauses/offlines after an accepted contact.

**Recommendation:** represent profile setup, verification, map visibility, availability, location freshness, match eligibility, and accepted contact separately. Explain the specific reason matching is unavailable. Do not equate pause/offline (stop new matching) with cancellation of an already accepted contact unless the policy explicitly says so.

**Tests:** all combinations of availability × verification × visibility × freshness × unsaved edits; recompute failure after committed availability; profile change while matched; pause/offline with active contact.

**Evidence:** source-confirmed state and UI messages; production recomputation timing/reliability unverified.

### Finding 43 — Donor response versus requester cancellation/fulfillment is a cross-role race
`SqlAlchemyDonorStore.respond()` loads donor match/request, rejects self-response, expires overdue requests, and rejects cancelled/expired/fulfilled request states. It maps accepted/arrived to CONFIRMED and declined to DECLINED, records response time, updates an existing donor-contact row, then commits. Push notification is sent afterward. Android donor response uses a durable-write callback to reduce cancellation on quick sign-out/ViewModel teardown.

**What-ifs:** donor accepts while requester cancels on another device; requester cancels after acceptance but before push; request expires between inbox refresh and Accept; another donor fulfills while a second donor accepts; donor declines after prior acceptance; response commits but push fails/delays; delayed “accepted” push arrives after request closure; duplicate accept/retry after timeout.

**Risk:** the method checks request state before writing. A read-then-write lifecycle check may race with concurrent cancel/fulfill unless transaction isolation, row locking, or conditional update makes it atomic. This is a **testable concurrency risk, not a confirmed bug**.

**Recommendation:** terminal request state must win; stale response returns conflict/closed state and refreshes inbox; notification is a hint only; opening request reloads authoritative state. Decide explicitly whether accepted→declined/withdrawn is allowed (the request model accepting response strings does not prove the transition policy). If allowed, record reason/history and notify requester.

**Tests:** simultaneous accept/cancel; accept/fulfill by another donor; duplicate accept; accept after expiry; decline after accept; push failure after commit; delayed push after terminal state; retry after client timeout.

**Evidence:** source-confirmed state checks and write sequence; DB race behavior and transition policy unverified.

### Finding 44 — Donor response is not contact consent or completed donation
The helper `apply_donor_response_to_contact()` explicitly says acceptance must not be treated as contact disclosure. The lifecycle has distinct stages: match → donor response → contact relationship/consent → phone/email sharing and/or location sharing → meeting/appointment → fulfillment outcome.

**What-ifs:** donor accepts but requester never initiates contact; requester shares phone/email but donor prefers in-app contact; donor accepts while exact-location sharing is off/revoked; donor arrives but requester cancels/no-shows; one donor fulfills only part of requested units; request marked fulfilled while another donor contact is still active; requester and donor disagree about meeting/fulfillment; accepted badge persists after terminal request or block/report.

**Recommendation:** maintain distinct statuses/event history for match eligibility, donor response, contact consent, contact-detail sharing, exact-location sharing, meeting/appointment, donation outcome, and request-level completion. Do not imply medical eligibility, compatibility, or donation completion from an app response; facility/clinical process remains authoritative.

**Tests:** acceptance without consent; consent revoked; location share disabled; partial fulfillment; terminal request with active contact; block/report after accept; conflicting fulfillment actions.

**Evidence:** backend helper and entities support separation; exact end-to-end UI semantics need verification.

### Finding 45 — Requester edits/cancel/fulfill must converge with donor inbox and cached cards
This extends existing Findings 31–34 (request editing/multiple active requests) to the donor side:
- requester corrects blood type, units, urgency, facility/location after donor match/acceptance;
- requester cancels while donor inbox is cached;
- requester changes location while donor is arranging contact;
- request expires/fulfills while donor is offline, then donor opens an old card;
- donor opens delayed notification and accepts stale details;
- requester creates replacement request while old donor contacts remain.

The donor inbox repository filters requests to active status and unexpired deadline server-side. Android `DonorViewModel` polls about every 30 seconds and wraps periodic refresh in `runCatching`, so an already-rendered card can remain stale until refreshed and periodic failures may not be visible.

**Recommendation:** response action must revalidate request (the server method already does); stale/closed response should show a conflict and refresh. Notifications are best-effort only. Version critical request fields; if edits affect eligibility/commitment, either require donor reconfirmation or reject stale responses. Preserve old request/contact audit rather than silently mutating a prior commitment.

**Tests:** cancel/edit while donor offline; edit after accept; stale notification; stale version response; partial fulfillment; new request after cancellation; restore/history/inbox reconciliation after sign-in.

**Evidence:** source-confirmed server inbox filters and ~30-second Android polling; instant convergence/versioned edit behavior not verified.

### Finding 46 — Admin intervention must not impersonate requester or donor
Admin is not an ordinary profile role. Future verification, abuse report, donor restriction, or request moderation must be represented as a privileged action with its own actor and audit event.

**Admin × requester/donor what-ifs:** admin verifies donor while profile/availability changes; admin suspends donor after match/acceptance; admin investigates conversation report; admin flags request without silently rewriting requester-submitted facts; audit storage fails; admin privilege revoked while console remains open; admin accesses exact location/contact unnecessarily; admin notification leaks sensitive information.

**Recommendations:** use distinct admin actor/event types; define whether suspension blocks new matches, existing conversations, exact-location sharing, or all account access; enforce immediately server-side; minimize moderation evidence and audit metadata; re-check admin authorization on every request; clear console state on sign-out/revocation; audit sensitive reads as well as writes. Consider reversible/appealable controls for high-impact restrictions.

**Tests:** profile API admin spoof; revoked admin token; concurrent verification/profile edit; suspension during accepted contact; audit failure; unauthorized sensitive read; admin action versus donor/requester update race.

**Evidence:** role enum/admin docs establish separation; specific moderation impact on active contacts remains unverified/product policy.

### Cross-role acceptance matrix
| Actor / event | Other actor / timing | Required invariant |
|---|---|---|
| Requester cancels | Donor accepts concurrently | No new acceptance after terminal state; conflict + refresh |
| Requester fulfills | Donor has stale inbox | Closed request cannot accept a response |
| Donor pauses/offlines | Requester already has accepted contact | New matching stops; existing-contact policy remains explicit |
| Donor changes blood type | Requester sees old match | Match re-evaluates; stale card cannot silently accept |
| Donor accepts | No contact consent yet | Acceptance alone exposes no phone/email/exact location |
| Donor revokes location | Requester is viewing pin | Subsequent reads deny; stale UI clears on refresh |
| Requester edits critical fields | Donor accepted prior version | Reconfirm or reject stale response per explicit policy |
| Admin suspends donor | Active relationship exists | Defined, immediate, server-enforced, audited policy |
| Admin loses privilege | Console remains open | Next privileged API call denied |
| Push is delayed | Request is cancelled/fulfilled | Push is a hint; opening reloads current state |

**Priority:** exercise these as lifecycle/security integration tests before expanding attachments, notifications, or map work. Mock-only unit tests cannot prove transaction races.

**Change log:** 2026-11-04 — corrected role confusion and added LifeLink-specific requester/donor/admin cross-role scenarios. Documentation only; no app code/deployment changes.

## Findings 47–51 — Cross-role contact, location, block/report, and lifecycle enforcement

### Finding 47 — Exact-location access is evaluated live, not just at share creation
Source trace in `lifelink_fastapi/app/privacy_repositories.py`:
- Exact-location read checks the request/donor match, donor's `exact_location_sharing_enabled`, location freshness, and a live active share.
- It returns `shared=false` with no coordinates when a check fails.
- Location-share activation/revocation uses donor-row locking; revocation marks status/time and writes an audit event in the same transaction.
- `set_map_visibility()` locks donor row; turning map visibility off **or** exact-location sharing off revokes all active shares and commits privacy change + revocations + audit together.
- Request cancellation/expiry has explicit share-expiry paths; background sweep also expires shares when request ends or share window passes.

**What-ifs across requester/donor:**
1. Requester has exact pin open when donor revokes.
2. Donor hides map while a requester is using the pin.
3. Donor toggles exact sharing off but keeps approximate map visibility.
4. Request expires while both apps remain foregrounded.
5. Donor GPS freshness becomes stale while share is still nominally active.
6. Requester caches coordinates and returns to screen after revocation.
7. Request ID/donor ID are guessed by an unrelated account.
8. A share is revoked but an audit write fails.

**Assessment:** backend read policy is stronger than a UI-only hide: subsequent authorized reads should return no coordinates. However, a client that already received coordinates cannot be remotely “unshown” without refreshing/clearing local state. This is a residual client-cache/privacy UX risk, not evidence that the backend continues serving revoked coordinates.

**Recommendations:** clear coordinates from in-memory/saved UI state on refresh failure, revoke event, request terminal state, sign-out, or participant change; avoid persisting exact coordinates in general local history; show share status/freshness; consider a short client cache lifetime and periodic refresh while the pin is visible. Never treat a previously successful response as permanent authorization.

**Tests:** revoke while screen open; map hide; exact-share toggle; stale donor presence; expiry sweep; unrelated account access; cache restoration after sign-out; audit transaction failure.

### Finding 48 — Donor opt-out is immediate server-side, but requester UI may be stale
The backend combines opt-out with active-share revocation and audit transaction. Cross-role UX must still account for the requester’s already-rendered map/contact view.

**What-ifs:** donor opts out while requester is viewing; requester taps “navigate/contact” from stale donor card; map screen survives app background/resume; stale coordinates are restored from navigation state; request remains active but donor’s location consent is withdrawn.

**Recommendation:** differentiate “request still active” from “donor location unavailable”; preserve in-app conversation if allowed, but remove exact pin and disable directions based on that pin. Do not automatically cancel donor acceptance solely because location sharing was withdrawn. Re-query on screen resume and before any action that depends on exact coordinates.

**Evidence level:** backend revocation semantics source-confirmed; Android cache/state-clearing behavior needs targeted UI trace.

### Finding 49 — Conversation block/report needs clear bilateral consequences
LifeLink has request-scoped requester-donor conversations and a `ConversationBlock` entity. The privacy API describes report/block as available to either participant. The repository should be audited for exact route behavior and every downstream operation’s enforcement; do not assume that “block” automatically revokes contact shares, location shares, donor matching, or request fulfillment unless code explicitly does so.

**Cross-role what-ifs:**
- Donor blocks requester after accepting; requester still sees accepted contact and exact location.
- Requester blocks donor while contact/meeting is in progress.
- One participant reports the other but chooses not to block.
- Block happens while a message send is in flight.
- Blocked user opens old notification or deep link.
- Blocked pair remains eligible for new request matching.
- Admin reviews report and needs evidence without exposing unrelated private data.

**Recommendation:** document block/report separately:
- block prevents future message delivery/read according to explicit policy;
- report records moderation evidence and does not silently mean block;
- define effects on existing contact details and exact-location share (revoke or retain with clear safety rationale);
- define whether pair can match again on a future request;
- re-check block state at message write and conversation read, not only when opening screen;
- make blocked/deep-link behavior consistent and non-leaky.

**Tests:** block each direction; report-only; send-vs-block race; read old messages after block; open notification after block; new request match after block; contact/location behavior; audit/report access permissions.

**Evidence level:** model/API documents block/report and participant scope; full enforcement matrix across messages/contact/location/matching still requires endpoint-by-endpoint confirmation.

### Finding 50 — Contact detail sharing is a disclosure event, not a mutable profile field
Backend contact-share rows are conversation/request/donor/requester scoped, and `add_contact_share()` validates phone/email shape before recording explicit disclosure. This supports an audit trail, but a shared phone/email value is inherently copied by the recipient and cannot be reliably “unsent.”

**What-ifs:** requester shares phone then regrets it; donor shares email then blocks; requester edits profile phone after sharing; contact share is sent to wrong conversation due stale screen; user deletes account but recipient already copied the detail; a contact detail appears in a notification or inbox preview.

**Recommendations:** show recipient and exact field before confirmation; keep phone/email out of push payloads, logs, generic inbox previews, and unrelated request history; clearly state that revocation can stop future app access but cannot erase information already seen/copied. Do not claim a “revoke contact share” feature unless actual endpoint/data model supports it. Separate account profile edits from previously shared disclosure records.

**Tests:** wrong request/conversation ID; stale conversation; profile phone changed after share; deletion/account closure; block after share; push/log privacy; explicit consent confirmation and audit event.

**Evidence level:** API/store models and validation support scoped disclosure; revocation semantics for contact shares are not yet confirmed.

### Finding 51 — Fulfillment and donor contact state can diverge; request-level completion needs clear semantics
LifeLink has request statuses including `PARTIALLY_FULFILLED` and `FULFILLED`, and match statuses including `CONFIRMED`, `DECLINED`, and `WITHDRAWN`. These are separate entities/states, so tests should not assume that one donor’s accepted/arrived/fulfilled contact automatically completes the whole request.

**What-ifs:**
- Request requires multiple units/donors; one donor fulfills a portion.
- Requester marks request fulfilled while another donor is confirmed or arranging contact.
- Donor contact is withdrawn after partial fulfillment.
- Requester cancels after a donation/meeting has already occurred.
- Donor marks arrived but requester never confirms fulfillment.
- Donor/requester disagree about completion; duplicate fulfillment from two clients.
- A terminal request still has confirmed match rows or contact records.

**Recommendation:** define request-level fulfillment separately from per-donor match/contact progress; record who confirmed each outcome and when; prevent terminal status from being silently reversed; preserve partial fulfillment and prior contact history. If requester can mark fulfilled, ensure server-side checks/authorization and a clear confirmation step. Don’t interpret “arrived” as “donated.”

**Tests:** multi-donor partial fulfillment; terminal request with confirmed contacts; concurrent fulfill/cancel; duplicate completion; withdrawn donor after partial progress; reopen/rectify policy; request history and donor inbox after completion.

**Evidence level:** enums establish distinct request and match states; exact reconciliation rules require a full route/store trace and product policy.

### Next source trace required (not a claim of a bug)
The next audit should enumerate every backend route for `conversation`, `contact-share`, `location-share`, `block/report`, and requester `contact status`, then map each to its exact store method, actor authorization, transaction boundary, and Android UI action. Current findings identify meaningful cross-role scenarios but do not certify every endpoint’s enforcement.

**Change log:** 2026-11-04 — Findings 47–51 added from LifeLink privacy models/store and request/match enums. Documentation only; no application code changed.
