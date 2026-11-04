# LifeLink Consecutive Research Batch: Requester Lifecycle (Findings 55–70)

**Research date:** 4 November 2026  
**Repository:** RamProjector/LifeLink  
**Scope:** Requester-side request lifecycle, backend persistence/API, Android draft and active-request state, requester↔donor consequences. Documentation/research only; no application code, schema, deployment, or production data changed.

## Method and evidence boundary

Inspected the source on `docs/improvement-research-2026-11-03`:
- `lifelink_fastapi/app/main_postgres.py`: create, contact, history, status, cancel, fulfill route sections.
- `lifelink_fastapi/app/repositories.py`: request lookup, idempotency, persistence, cancel/fulfill, contact creation.
- `lifelink_fastapi/app/db_models.py`: request, match, donor-contact, and audit models/constraints.
- `lifelink_fastapi/app/expiry.py`: active statuses and expiry rule.
- Android `EmergencyRequestRepository.kt`, `EmergencyRequestViewModel.kt`.
- `docs/PROJECT_CONTEXT_AND_REGRESSION_GUARDRAILS.md`: documented historical regressions and test names.

This is a source-level audit, not proof of live deployment behavior. No tests were run in this research batch. Claims below distinguish confirmed code from concurrency, UX, and product-policy questions.

## Finding 55 — Active-request editing is not the same as draft editing

**Source evidence:** Android `EmergencyRequestViewModel.updateDraft()` edits the local draft and refuses changes while submission is in progress/queued. The inspected backend route set includes POST create, GET history/status, POST cancel/fulfill, and contact operations; no PATCH/PUT request-edit route was found in that inspected section. Existing docs also describe requester editing as future work.

**Current interpretation:** editing the wizard before submit is supported; editing a persisted request after submit is not demonstrated by the inspected API.

**What-ifs:** requester notices wrong units after submission; wrong facility/location; typo in note; blood type corrected by a professional; donor has already accepted; request is already partially fulfilled.

**Recommendation:** do not add a generic edit button that only mutates local state. Define a server-authoritative edit contract and permitted fields/states first. Until then, offer a clear cancel-and-create-new path only where safe, and preserve history. Show which fields are locked and why.

**Acceptance tests:** draft edit persists locally; submitted request edits are not silently accepted client-side; unauthorized owner edit denied; terminal request edit denied; active request screen reflects server state after refresh.

## Finding 56 — No confirmed one-active-request invariant in the inspected database schema

**Source evidence:** `EmergencyRequest` has requester ID and indexes on requester/created and status/deadline. It has a unique idempotency key. The inspected model does not show a partial unique constraint limiting each requester to one active request. `ACTIVE_REQUEST_STATUSES` defines matching, awaiting responses, partially fulfilled, and manual broadcast as active.

**Risk:** two devices or rapid taps can submit distinct idempotency keys and create two concurrent active requests if product intends a one-active-request rule. Client disabling a button is not cross-device enforcement.

**Decision:** Does LifeLink allow multiple simultaneous requests? Earlier roadmap language suggested one-active-request; verify current product decision before adding constraints. If one is intended, implement a transaction-safe server/database invariant covering exactly the chosen statuses. Avoid a naive uniqueness constraint on requester ID because it would also block historical rows.

**Tests:** simultaneous POSTs from two sessions; same requester/different keys; one active + one terminal; partially fulfilled request; expired request whose sweeper has not run; cross-account requests.

## Finding 57 — Idempotency protects retries, not separate request intents

**Source evidence:** create route serializes lookup/insert by transaction-scoped advisory lock on the idempotency key, compares request intent on replay, and the model has a unique key. Replays return the existing request with zero new notifications. Android sends draft ID as the idempotency key.

**Important distinction:** same key + same payload is a retry; a new key is a new intent. Idempotency does not prevent duplicate requests created by two fresh drafts, nor does it enforce active-request policy.

**What-ifs:** response lost after commit; user taps submit twice; app restarts; user cancels then resubmits; pending offline worker retries after user has manually created a replacement; stale key reused with changed location/units.

**Recommendation:** retain idempotency; make the UI distinguish “submission outcome unknown” from “failed.” Before a retry with a new key, reconcile request history and pending submissions. Never generate a fresh key merely because the first network response timed out.

**Tests:** lost response/retry; same key altered payload returns 422; duplicate queued work; cancel followed by new draft; old worker races with new request.

## Finding 58 — Offline queueing can conflict with later user decisions

**Source evidence:** Android repository queues a submission on offline IOException and WorkManager retries it. Draft and pending submission data are owner-scoped. ViewModel locks the draft while queued. Existing regression docs emphasize preserving owner/draft identity.

**What-ifs:** user goes offline and queues request, then changes their mind; user signs out; user signs in on another device and creates request; queued submission retries after request was cancelled elsewhere; request deadline/location becomes stale while queued.

**Recommendation:** define queued submission as a durable pending intent, not a submitted request. Provide explicit “queued / not yet sent” status and a safe cancel-queued action. Before worker execution, verify account ownership and pending state; prevent a cancelled queue item from being sent. Avoid silently rewriting the original payload after consent confirmation.

**Tests:** cancel pending queue before network returns; sign-out/account switch; duplicate worker; offline payload stale; pending queue restored; worker response lost; local queue deleted only after authoritative success.

## Finding 59 — Create-time consent and request edits need versioned consent semantics

**Source evidence:** create payload includes `genuine_request_confirmed` and `sharing_consent_confirmed`; persisted request stores those flags. Request note is capped and filters some sensitive phrases. No inspected active-edit endpoint re-prompts consent after changed fields.

**Risk:** if a future edit changes material request facts (blood type, quantity, urgency, facility/location, deadline, note), the original confirmation may no longer represent the edited intent. Consent is not a universal checkbox that automatically covers future changes.

**Recommendation:** classify fields as non-material vs material. Material edits should require explicit reconfirmation, preserve old/new values in a privacy-minimized audit/version record, and notify/reconfirm affected donors when commitments could change. Avoid storing raw sensitive clinical information in audit metadata.

**Tests:** edit after confirmation; consent revoked/changed; no consent inherited from prior request; audit payload avoids unnecessary exact coordinates/private note; Android confirmation reflects current draft revision.

## Finding 60 — Request edits after donor contact create stale commitments

**Source evidence:** contact selection is a separate explicit POST after request creation; backend verifies request active and selected donors belong to stored match set. Donor contacts are request-scoped. Existing roadmap says edits after response/contact commitments should be blocked or limited unless stale responses can be invalidated and participants notified.

**What-ifs:** requester changes quantity after donor accepts; changes facility after meeting arranged; edits urgency after donor has started travel; edits blood type after match; request becomes fulfilled while an accepted donor is still arranging contact.

**Recommendation:** before implementation, choose one policy:
1. Lock commitment-affecting fields once any donor contact is created; or
2. Permit controlled edits with revision number, donor reconfirmation, and explicit cancellation/withdrawal semantics.

Do not silently mutate the original request and leave donor acceptance attached to old details. Preserve the original accepted snapshot for audit and user clarity.

**Tests:** accepted contact + edit; pending contact + edit; multiple donors with different response states; rejected edit has no side effects; reconfirm timeout; stale donor response conflicts.

## Finding 61 — Cancellation currently performs a broad lifecycle cleanup, but audit/notification semantics need review

**Source evidence:** `set_cancelled_async` sets request cancelled, withdraws match rows not already withdrawn/declined, closes donor-contact rows not already cancelled/fulfilled, commits, then refreshes. Route separately expires location shares and records request-cancelled audit. Android resets request flow and creates a fresh draft after cancellation; regression test is documented.

**Confirmed benefit:** cancellation is more than flipping the parent status; it updates donor-side rows.

**Open questions:** push notifications to donors are not visibly sent in the cancellation route section; donors may discover cancellation only on inbox refresh/poll. Also, backend request/contact commit and privacy-share expiry/audit are separate operations in route sequence—failure after first commit could leave partial ancillary cleanup/audit state.

**Recommendation:** define cancellation as one observable lifecycle event. Consider transactional coordination/outbox for audit and cancellation notifications; do not make notification delivery part of the request-state transaction. Surface cancellation to contacted donors promptly without leaking requester private data.

**Tests:** cancellation row-state consistency; audit write failure; share expiry failure; push failure/retry; cancel from two devices; donor opens stale notification.

## Finding 62 — Cancellation should distinguish pending contact, accepted contact, and already fulfilled donor work

**Source evidence:** cancel closes contact rows except statuses already cancelled or fulfilled; match rows are withdrawn except already withdrawn/declined. This preserves fulfilled contact rows while closing other contacts.

**Policy issue:** the parent request becomes cancelled even if a donor contact was marked fulfilled. That may be valid for partial fulfillment or a requester canceling remaining need, but the UI must not imply no donation/meeting occurred.

**What-ifs:** one donor fulfilled part of request, requester cancels remaining units; donor marks fulfilled after requester cancellation; contact row status fulfilled while parent is cancelled; requester cancels accidentally after an in-person exchange.

**Recommendation:** distinguish “cancel remaining request” from “undo completed activity.” Keep fulfilled donor-contact history immutable; present cancellation as stopping outstanding need. If cancellation is disallowed after full fulfillment, enforce it on the server with clear error. Do not delete or rewrite fulfillment evidence.

**Tests:** partial fulfillment then cancel; all fulfilled then cancel; fulfilled contact remains; cancelled parent cannot accept new contact; donor/requester status conflict; audit records reason/time.

## Finding 63 — Fulfillment endpoint can close the request while donor contacts remain unresolved

**Source evidence:** `set_fulfilled_async` rejects expired/cancelled requests and marks the request fulfilled. The inspected method does not update all donor-contact rows or match rows. Route expires location shares and records audit after fulfillment.

**Risk / policy question:** parent request may become terminal while contacts still show pending/accepted/meeting-arranged. This may be intentionally retained for history, but active actions and UI badges need to converge.

**Recommendation:** define a fulfillment reconciliation policy:
- stop new contacts/messages/location sharing according to current privacy rules;
- preserve contact history and donor-level outcome;
- mark unresolved contacts as “request completed—no further action” (or equivalent) without falsely claiming each donor fulfilled;
- notify each contacted donor of closure;
- retain a separate per-donor outcome when multiple donors contribute.

**Tests:** fulfillment with pending contacts; fulfillment with accepted donor; multiple donors and partial contribution; delayed donor response after closure; duplicate fulfill call; contact history after fulfillment.

## Finding 64 — Parent request state and per-donor state are separate state machines

**Source evidence:** request enum includes matching, awaiting responses, partially fulfilled, fulfilled, expired, cancelled, manual broadcast. Match enum includes ranked, notified, considering, confirmed, declined, withdrawn. Donor contact rows have string statuses and timestamps. These entities do not automatically mean the same thing.

**Risk:** a UI that derives all statuses from one field may show incorrect states (“request fulfilled” while donor contact still pending; “donor accepted” interpreted as fulfilled).

**Recommendation:** document a transition matrix for request, match, contact, location-share, and conversation. State precedence must be explicit; do not infer contact status from match status alone. Backend responses should provide a coherent snapshot or version/time for the client.

**Tests:** every allowed transition; invalid transition rejection; parent terminal state with child active rows; status serialization enum/string; old data migration/unknown status fallback.

## Finding 65 — Deadline expiration and background sweep are not the same as instant UI expiry

**Source evidence:** request reads lazily expire through `get_by_id_async`; history lazily expires records; background sweeper runs on a configurable interval (default documented as 300 seconds). Active statuses are explicit. A deadline can pass before the sweeper updates the row.

**Risk:** UI may display “awaiting response” after deadline until refresh, while backend operations may deny or lazily transition. Server status and local cache can temporarily disagree.

**Recommendation:** treat deadline as authoritative at every write/action, not only stored status. Client should compare server deadline to current server response time if available, refresh after expiry, and display “deadline passed—refreshing” rather than promising a donor response remains possible. Do not use device wall clock alone as enforcement.

**Tests:** at deadline exactly; before/after deadline; sweeper delayed/fails; status read after expiry; contact attempt after deadline; offline client resumes with expired cached status.

## Finding 66 — History is limited and should not masquerade as complete history

**Source evidence:** `list_by_requester_async` defaults to 50 latest records and Android refresh maps server history into request-history items. The server endpoint returns the records it receives; no cursor/pagination parameter was visible in the inspected route.

**Risk:** long-lived requester accounts may have more than 50 requests; UI could present a partial history as complete, making old requests seem deleted. Some error handling skips unreadable rows while continuing, which is resilient but can conceal a data-quality issue without surfaced diagnostics.

**Recommendation:** decide whether history is intentionally capped. If not, implement stable cursor pagination (created_at + ID) and show “load more.” If capped, label it and provide a safe export/support route. Preserve account-scoped history and avoid loading all rows into memory indefinitely.

**Tests:** 51+ records; same timestamp pagination; deleted/invalid row; account switching; history refresh failure; oldest item remains reachable.

## Finding 67 — Request status/history restoration may choose an unsuitable “active” record

**Source evidence:** Android history restore, when no local active request exists, chooses the first non-terminal history item, otherwise the first history item. Server history is newest-first, capped. Active statuses include partially fulfilled and manual broadcast.

**Risk:** if multiple active requests are possible, restoration may select the newest active request while older active ones remain hidden. If only one active is intended, the restore logic can mask violation rather than report it. If history fetch fails, local active state may remain stale.

**Recommendation:** resolve the one-active-request product rule first. If multiple are allowed, replace “single active” restoration with an account-scoped active-request list and explicit selection. If only one is allowed, server must enforce it and restore should detect/repair duplicates transparently rather than silently picking one.

**Tests:** multiple active server records; terminal newest + active older; local stale record; history request error; sign-out/sign-in; restore after request cancellation.

## Finding 68 — Requester history/status can expose stale match/contact summaries

**Source evidence:** history computes `notifications_created` from number of stored matches and response count from contacts with accepted_at or declined status. Create comments state matches are candidates, not notifications, and returns notifications_created=0. Status/history summary may therefore use “notifications_created” in a way that conflicts with create semantics or historical meaning.

**Risk:** a count of stored match candidates is not necessarily a count of notifications actually sent. “Responded” may omit other meaningful contact states or count accepted timestamps independently of current contact status.

**Recommendation:** audit API field names and UI labels. Separate candidate matches, contacted donors, push deliveries (if tracked), and donor responses. If exact notification delivery is not tracked, label it “matched candidates” or avoid implying delivery. Define response-count logic from contact/match state machine.

**Tests:** matching but not contacted; contacted but push failed; accepted then withdrawn; declined; manual broadcast; replayed create; historical API contract compatibility.

## Finding 69 — “New request while one is active” needs a user-safe decision flow

**Source evidence:** Android has active request observation and request history, and docs mention one-active-request policy as a roadmap requirement. The inspected database schema does not enforce one active request. Cancellation resets to a new draft; create idempotency is per draft key.

**Recommended product behavior (pending explicit confirmation):**
- If one active request is required: block new submission and offer “view active request,” “cancel active request,” or “continue draft” with consequences explained.
- If multiple are allowed: show an active-request list, independent status/expiry, and avoid a single global active-request slot.
- Do not automatically cancel/replace the existing request when user starts a new draft.
- Do not silently submit a queued offline request when another active request has since been created.

**Cross-role what-ifs:** donor already accepted old request; new request duplicates same need; user changes facility; old request partially fulfilled; two devices submit; queued submission arrives later.

**Tests:** UX actions map to backend rule; no automatic cancellation; active old request retained in history; cross-device conflict; duplicate emergency submission warning without blocking legitimate distinct needs.

## Finding 70 — Request lifecycle needs a server-authored transition ledger and reconciliation contract

**Source evidence:** request and donor-contact statuses are persisted; audit events exist and route writes request cancellation/fulfillment events. The inspected request model has `updated_at`, but no explicit request revision counter or complete immutable transition ledger is demonstrated in the selected source.

**Recommendation:** before adding edit support, establish a stable event/version contract:
- request ID and monotonically increasing revision (or equivalent concurrency token);
- transition actor, action, server timestamp, prior/new status;
- safe change summary, not raw sensitive coordinates or private notes by default;
- idempotency for actions and notification outbox;
- conflict response when client acts on stale revision;
- clear retention and admin access policy.

Avoid duplicating sensitive request payloads in audit metadata. The ledger should support troubleshooting and accountability without becoming a shadow database of personal data.

**Tests:** concurrent edit/cancel; stale revision; duplicate action; audit failure; transition history after partial fulfillment; account deletion/retention; no secrets or unnecessary PII in event payload.

---

## Consolidated requester lifecycle decision checklist

Before implementation, product must answer:
1. Are multiple active requests allowed, or is one active request a hard invariant?
2. Which fields can be edited after submit, after contact, and after donor acceptance?
3. Does editing require donor reconfirmation or invalidate old contacts?
4. Does “cancel” mean stop remaining need while retaining fulfilled donor work?
5. What happens to unresolved donor contacts when request is fulfilled?
6. Are offline queued submissions cancellable and reconciled against server history?
7. Is history intentionally limited to 50 or should it paginate?
8. What does the UI mean by “matches,” “contacted,” “notified,” and “responded”?
9. What transition/audit information is necessary, and what private data must never be copied into it?

## Priority order

- **P0:** settle one-active-request rule; enforce terminal-state and concurrent action behavior; make request edit/replace safe; resolve parent/child state semantics.
- **P1:** offline queue reconciliation; history pagination/restore; status-label correctness; cancellation/fulfillment donor notification semantics.
- **P2:** revision ledger and richer audit diagnostics, after privacy and retention review.

## Source references

- Backend routes and create/cancel/fulfill/status/history:  
  https://github.com/RamProjector/LifeLink/blob/docs/improvement-research-2026-11-03/lifelink_fastapi/app/main_postgres.py
- Request persistence, idempotency, cancellation, fulfillment, contact selection:  
  https://github.com/RamProjector/LifeLink/blob/docs/improvement-research-2026-11-03/lifelink_fastapi/app/repositories.py
- Request/match/contact schema and constraints:  
  https://github.com/RamProjector/LifeLink/blob/docs/improvement-research-2026-11-03/lifelink_fastapi/app/db_models.py
- Active request statuses / expiry:  
  https://github.com/RamProjector/LifeLink/blob/docs/improvement-research-2026-11-03/lifelink_fastapi/app/expiry.py
- Android request repository:  
  https://github.com/RamProjector/LifeLink/blob/docs/improvement-research-2026-11-03/LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/repository/EmergencyRequestRepository.kt
- Android request ViewModel:  
  https://github.com/RamProjector/LifeLink/blob/docs/improvement-research-2026-11-03/LifeLinkAndroid/app/src/main/java/com/lifelink/app/feature/emergencyrequest/EmergencyRequestViewModel.kt
- Existing regression and lifecycle guardrails:  
  https://github.com/RamProjector/LifeLink/blob/docs/improvement-research-2026-11-03/docs/PROJECT_CONTEXT_AND_REGRESSION_GUARDRAILS.md

**Change log:** 2026-11-04 — consecutive Findings 55–70: requester request lifecycle source audit. Documentation only; no code, schema, or deployment changes.