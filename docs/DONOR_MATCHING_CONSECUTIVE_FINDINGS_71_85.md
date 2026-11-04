# LifeLink Consecutive Research Batch: Donor Matching, Availability, Response, and Fulfillment (Findings 71–85)

**Research date:** 4 November 2026  
**Repository:** RamProjector/LifeLink  
**Scope:** Donor eligibility/matching, dynamic availability, donor inbox, acceptance/decline/arrival, withdrawal and parent-request fulfillment. Documentation only; no app code, schema, production data, or deployment changes.

## Evidence and boundaries

Source inspected on repository default branch during this batch:
- `lifelink_fastapi/app/main_postgres.py`: request creation/contact selection, donor-profile presence/availability, profile deletion, donor response/inbox routes.
- `lifelink_fastapi/app/donor_repositories.py`: matching recomputation, donor availability, inbox filtering, response persistence.
- `lifelink_fastapi/app/donor_profile_repositories.py`: verified/available gate, profile-to-operational donor synchronization, stale-presence sweep.
- `lifelink_fastapi/app/repositories.py`: request match persistence, contact selection, cancellation/fulfillment, contact status transitions.
- `lifelink_fastapi/app/db_models.py`: request, match, donor contact, donor profile, availability and location-share models.
- `lifelink_fastapi/app/main.py`: scoring and fallback/in-memory implementation.

This is a source audit, not a live-service test. PostgreSQL and in-memory paths are materially different; findings identify which path is evidenced. No tests/builds were run.

## Finding 71 — Matching is request-time plus donor-change recomputation, not a continuously refreshed global ranking

**Evidence:** Request creation scores current eligible donors and persists up to ten matches. Donor profile save and transition to available call donor-specific recomputation against active requests. The code does not demonstrate a continuous ranking service.

**Risk/what-if:** donor moves, availability changes, service radius changes, or a request’s urgency/deadline changes while a requester is looking at an old candidate list.

**Recommendation:** treat match results as timestamped candidate snapshots. Provide an explicit refresh/recompute policy and stale-result indication; avoid implying a live GPS/ranking feed.

**Acceptance criteria:** stale candidate list can be refreshed; no duplicate donor/request rows; recompute failure is observable and recoverable; requester can distinguish “candidate list as of” from real-time status.

## Finding 72 — Eligibility has several independent gates that the UI must not collapse

**Evidence:** SQL donor query checks available, profile-visible, recent presence, optionally verified (environment-controlled); scoring checks compatible blood type and availability/verification configuration. Separate donor-profile flow explicitly derives operational availability from verified + user availability. The legacy in-memory path has different defaults.

**Risk:** “Available” in the profile UI can be misread as “eligible for every request” or “verified.” A donor can be opted in but not yet verified/matchable.

**Recommendation:** display distinct states: profile setup, verification, availability, GPS/presence freshness, and request-specific eligibility. Do not promise that toggling availability guarantees an inbox item.

**Tests:** unverified donor toggles available; incompatible blood type; stale presence; profile hidden; verification changes; legacy versus PostgreSQL mode.

## Finding 73 — Verification policy is configuration-sensitive and requires visible product semantics

**Evidence:** `require_verified_donors()` reads `LIFELINK_REQUIRE_VERIFIED_DONORS`; donor-profile code describes verified-only effective availability, while scoring/configuration and in-memory examples have separate behavior.

**Risk:** deployment configuration changes who appears in matching, but user-facing copy and test expectations may remain fixed.

**Recommendation:** make the active verification policy explicit in deployment documentation and tests; avoid UI claims such as “all donors are verified” unless the configured backend guarantees it. Keep verification server/admin-controlled.

**Tests:** config on/off; client attempts self-verification; unverified match explanation; profile update does not alter server verification; environment parity.

## Finding 74 — Donor availability is a preference plus freshness gate, not a location guarantee

**Evidence:** availability toggle persists the donor’s choice and re-matches on going available. Presence endpoint stamps server time; stale-presence sweep marks operational donors unavailable. Matching filters by last presence age.

**Risk:** a donor marked available may have stale GPS/presence, poor network, or be physically unable to respond. Conversely, a donor can be opted in but temporarily excluded due to freshness.

**Recommendation:** use wording like “available recently” and “presence updated,” not “nearby now” or “ready to donate.” Separate availability preference from location freshness and eligibility.

**Tests:** toggled available without fresh presence; fresh presence while offline; delayed sweep; presence update failure; donor moves beyond radius.

## Finding 75 — Availability-change recomputation is best-effort after the preference is saved

**Evidence:** donor repository persists availability and then recomputes matches; comments explicitly treat recompute as best-effort so saved availability does not become an API failure. Donor-profile store also has best-effort recompute logging.

**Risk:** donor sees successful “available” update but active requests/inbox do not immediately reflect it if recomputation failed.

**Recommendation:** expose recompute freshness/health or retry queue; do not falsely roll back the saved availability. Make the donor/requester experience clear that matching refresh may be delayed.

**Tests:** recompute throws after commit; repeated retries do not duplicate matches; donor can still toggle offline; telemetry identifies stale match set without sensitive location payloads.

## Finding 76 — Donor inbox is request-scoped and filters active requests/deadlines

**Evidence:** inbox query joins request, match, and optional donor contact; excludes requests not in active statuses, expired deadlines, and self-requests; orders newest first.

**Risk:** donor may believe an absent item was deleted or never matched, when it was expired, terminal, or no longer eligible. The UI currently needs to explain why a request disappears.

**Recommendation:** separate active inbox from historical responses; preserve a clear “expired/cancelled/completed” history where policy permits, without showing private requester details beyond authorization.

**Tests:** deadline passes; request canceled; parent fulfilled; donor declines; historical request opens from notification; stale deep link.

## Finding 77 — Requester selecting donors is the notification boundary

**Evidence:** request creation stores candidates but explicitly does not notify donors; POST contact-selection creates contact requests and sends push notifications to newly contacted donors. Repeated contact rows are skipped.

**Risk:** “matched” can be mistaken for “notified,” “contacted,” or “accepted.” Notifications are intentionally downstream of requester selection.

**Recommendation:** use distinct UI states: candidate → requester selected/contact requested → donor response. Never show “donor notified” just because a match row exists.

**Tests:** create request without selection yields no donor push; selected donors receive notification; repeated selection does not duplicate contact/push; partial selection and retry.

## Finding 78 — Contact selection can inherit a donor’s earlier match response

**Evidence:** when requester selects a donor, repository maps a prior confirmed match to contact status accepted, prior declined match to declined, otherwise pending and marks match notified. It creates contact rows from match rows.

**Risk:** old response status may be surfaced as if it were a fresh response to the contact request. The requester may contact a donor who previously declined and then see an immediately declined contact.

**Recommendation:** distinguish response timestamp/context from current contact selection. Product policy should decide whether prior decline blocks re-contact, permits it after changed request details, or requires explicit requester confirmation. Do not silently reset a donor’s prior decline.

**Tests:** donor declined before requester selection; request details differ (currently no edit endpoint); re-contact after decline; old accepted match; repeated contact-selection.

## Finding 79 — Donor acceptance is not contact disclosure or confirmed fulfillment

**Evidence:** donor response helper persists accepted/arrived and accepted timestamp but explicitly says acceptance is not contact disclosure. Contact email visibility is limited to contact_shared, meeting_arranged, and fulfilled states.

**Risk:** acceptance UI may expose email/phone too early or imply a donation is complete. “Arrived” is stored as confirmed match, not a separate parent fulfillment.

**Recommendation:** keep acceptance, contact sharing, meeting arrangement, and fulfillment as separate actions and statuses. Explain the next step after acceptance and what personal information remains hidden.

**Tests:** accepted donor cannot read contact details prematurely; acceptance timestamp stable on duplicate response; contact-share gate; arrival is not fulfillment; requester sees correct next action.

## Finding 80 — Donor response route validates request lifecycle but needs explicit match/contact transition policy

**Evidence:** response rejects missing match, self-response, expired request, and cancelled/expired/fulfilled request. It updates match status and, if contact row exists, applies response to contact. Accepted/arrived map to confirmed match; declined maps declined.

**Risk:** match and contact statuses are separate state machines. A donor may respond to a candidate before requester contact exists; later selection can inherit response. UI must not assume every donor response creates a contact conversation.

**Recommendation:** define and test a transition matrix across match, contact, request, and conversation. Ensure invalid repeated/contradictory responses have predictable semantics and do not overwrite terminal history.

**Tests:** response before contact selection; response after selection; decline then accept; accept then decline; duplicate acceptance; response after cancellation race.

## Finding 81 — Availability changes and profile edits can alter future eligibility without erasing an existing commitment

**Evidence:** donor profile upsert can re-evaluate matches; recomputation preserves acted-on matches and live contact requests rather than deleting them. Availability/offline and profile deletion update operational availability.

**Risk:** donor goes offline, changes blood type/radius/location, or opts out after accepting. Existing requester contact/commitment may remain active even though donor is no longer matchable for new requests.

**Recommendation:** separate “stop receiving new matches” from “withdraw from an existing commitment.” Provide a safe donor action for existing requests and notify requester; do not automatically erase a live accepted contact when donor availability changes.

**Tests:** offline after accept; profile opt-out with pending contact; blood-type correction with existing accepted match; donor profile deleted while active contact; no new match after offline.

## Finding 82 — Recompute preserves some old matches, but candidate eligibility and live contacts can diverge

**Evidence:** recompute adds eligible matches and removes ineligible existing matches only when they are still ranked/notified and have no live contact. It deliberately keeps matches donors have acted on or that have live contacts.

**Risk:** a donor can remain attached to a request after no longer meeting current matching criteria. This may be correct for a commitment, but the UI could label them as currently eligible.

**Recommendation:** label retained records as “previously matched / commitment in progress,” not current candidate. Keep a snapshot of why they were originally matched; do not expose internal ranking scores as a guarantee.

**Tests:** donor becomes incompatible after acceptance; profile hidden with pending contact; recompute removes unacted match; recompute preserves live contact; UI labels historical versus current candidate.

## Finding 83 — Donor opt-out is profile deletion plus disabling, not a complete commitment-resolution workflow

**Evidence:** DELETE donor-profile removes profile and disables `can_donate`; repository sets operational donor unavailable. The route does not show an explicit donor-contact cancellation/reconciliation workflow in the inspected code.

**Risk:** donor expects “opt out” to cancel all current commitments, while requester may still see accepted/pending contacts. Or the system may preserve commitments for safety but not explain that.

**Recommendation:** define two separate actions: pause/stop new matching and withdraw from specific active contacts. Require confirmation for withdrawal and notify requesters; preserve audit and fulfilled history. Avoid blanket deletion that silently destroys accountability.

**Tests:** opt out with no active contacts; pending contacts; accepted contacts; fulfilled history; re-enable profile; requester receives closure notice; no new match after opt-out.

## Finding 84 — Parent fulfillment does not automatically reconcile all donor contact states

**Evidence:** request store sets parent status fulfilled and rejects cancelled/expired fulfillment. The inspected fulfillment method does not update each donor-contact row. Requester contact status endpoint rejects changes once parent is fulfilled. Donor responses reject fulfilled requests.

**Risk:** parent request can become fulfilled while donor contacts remain pending/accepted/meeting-arranged. These rows may be intentionally retained for history but can appear actionable or unresolved.

**Recommendation:** define a close-out state for outstanding contacts such as “request completed—no further action,” distinct from each donor’s own fulfilled outcome. Preserve individual donor contribution history; do not falsely mark every contacted donor as fulfilled. Send closure notifications via reliable retry/outbox.

**Tests:** fulfill with pending contact; accepted donor; multiple donors/partial fulfillment; late response after fulfillment; duplicate fulfillment; contact UI read-only after closure.

## Finding 85 — LifeLink needs one cross-role transition matrix and explicit stale-action behavior

**Evidence:** parent request status, match status, donor contact status, availability, exact-location share, and conversation state live in different models and route handlers. Existing guards exist but are distributed across methods.

**What-ifs:** requester cancels while donor accepts; donor goes offline while requester contacts them; request deadline passes while push is delayed; requester marks fulfilled while donor is en route; stale notification opens terminal request.

**Recommendation:** maintain a canonical transition table with actor, current state, permitted next state, side effects, notification, and privacy consequences. Server state remains authoritative; clients should refresh and explain rejected stale actions rather than retry blindly.

**Acceptance criteria:** every transition is covered by API tests; cross-entity side effects are idempotent; race cases yield one consistent outcome; stale deep links show terminal reason; UI status vocabulary matches backend state.

## Batch-level implementation priorities (not implementation work)

1. **P0:** transition matrix and terminal/stale action behavior (Findings 80, 84, 85).
2. **P1:** visible matching freshness and recompute observability (71, 75, 76).
3. **P1:** donor availability vs current commitment semantics (74, 81, 83).
4. **P1:** requester selection/notification/response vocabulary (77–79).
5. **P2:** configuration parity and verification policy documentation (72, 73).
6. **P2:** candidate versus retained commitment display (82).

## Source files to revisit before implementation

- `lifelink_fastapi/app/main_postgres.py`
- `lifelink_fastapi/app/donor_repositories.py`
- `lifelink_fastapi/app/donor_profile_repositories.py`
- `lifelink_fastapi/app/repositories.py`
- `lifelink_fastapi/app/db_models.py`
- Android donor inbox, response, availability, and request-status UI/repository files (separate client-side audit required).

## Limitations

- This batch did not inspect every Android donor UI screen or run API concurrency tests.
- Notification delivery is not proven reliable merely because a push call is present.
- A persisted donor match/contact is not proof that a donor is medically eligible or that a donation occurred.
- No schema/API/UI changes are proposed as already implemented; all recommendations require review and tests.
