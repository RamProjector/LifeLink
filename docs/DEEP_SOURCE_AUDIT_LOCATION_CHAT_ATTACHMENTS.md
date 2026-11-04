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
