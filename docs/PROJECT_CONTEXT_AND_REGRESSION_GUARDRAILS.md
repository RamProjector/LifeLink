# LifeLink project context and donor regression guardrails

**Last updated:** 2026-09-27

This note is intentionally repository-resident. Future chats, agents, and contributors must read it before touching authentication, account roles, request persistence, donor onboarding, or donor navigation. It records the product decisions that must survive context changes.

## Product model

LifeLink uses capabilities, not an exclusive role. One authenticated user may create emergency requests and also maintain a donor profile. A user’s own emergency requests must never appear as donor opportunities, and the server must reject any attempt to respond to one’s own request even if stale client data contains it.

A newly authenticated account does not need to choose between requester and donor during startup. It enters the requester shell by default and can open donor mode from Profile. Donor setup is separate from availability: the donor profile must be saved first, then availability can be selected. Requests remain hidden from a donor until display name, blood type, approximate location, and service radius are complete.

Existing accounts must not be routed using one device-wide role value. Startup is derived from the authenticated server profile. A legacy account whose server role is `donor` opens donor mode; an ordinary requester starts in the requester shell. A user with both capabilities can still enter donor mode from Profile. Local role preferences, when used, must be keyed by Supabase user ID.

## Persistence rules

Every account-specific local query must include the authenticated user ID. This applies to active requests, request history, pending request state where applicable, donor profiles, donor inbox records, and updates. A global `SELECT ... ORDER BY ... LIMIT 1` or global donor-request feed is a cross-account data leak and is not acceptable.

The Android Room database is currently version 9. Migrations 6→7→8→9 preserve legacy rows while adding account ownership. Migration 8→9 gives drafts, pending submissions, and updates composite owner/item keys and handles older active-request tables missing owner IDs. Unowned legacy rows receive an empty owner and are intentionally ignored. Keep these migrations; the historical reset fallback is limited to unsupported versions 1–5. Offline workers must carry an owner ID and exact draft ID, and repository network clients must remain bound to their account.

The donor page must restore a saved profile from `GET /v1/donors/{donorId}` before deciding whether setup is complete. A local empty profile is not evidence that the server profile is missing. The donor inbox may be loaded only after the restored profile is complete.

## Server contract

`GET /v1/profile` and `PUT /v1/profile` return `can_request` and `can_donate`. Profile updates must preserve the capability values supplied by the authenticated client instead of silently reverting them. Donor setup sets `can_donate=true` without removing request capability.

`GET /v1/donors/{donorId}` is authenticated and may return only the caller’s donor profile. `PUT /v1/donors/{donorId}` saves the donor profile, while `PATCH /v1/donors/{donorId}/availability` changes availability only after setup is complete. Donor inbox queries exclude requests whose `requester_id` equals the authenticated donor ID, and donor responses reject that same case.

## Known regression patterns

The old mandatory role-selection screen caused new-account friction and made role state appear authoritative when it was only local. A single SharedPreferences key named `role` caused a donor account to open as requester—or another account’s role to be reused—after login. A local donor-request table without an owner key allowed one account’s cached requests to appear in another account. A donor repository that only observed local Room state made server-saved donor profiles appear missing on the donor page.

A request stored in the database was re-validated with the create-time input model, whose rule "response_deadline must be in the future" rejected every request after its deadline passed; reading it back (history, status polling, sign-in restore) returned HTTP 500 and one expired request hid the whole account's history. Rows changed in-session also held plain-string enum values, so `.value` on them crashed cancel, fulfill, and manual-broadcast after the change was already committed. Donor matching required `verified = true`, which nothing in the app can set, so real donors never received requests. Stored rows are now validated with `context={"stored": True}`, lifecycle code assigns enum members, and verified-only matching is opt-in through `LIFELINK_REQUIRE_VERIFIED_DONORS`. Keep `tests/test_postgres_end_to_end.py` passing (`pip install pgserver`); the mock-backed tests cannot catch this class of bug.

Donor Accept/Decline ran on `DonorViewModel`'s own `viewModelScope`. A sign-out landing a split second after the tap clears `accountModels.viewModelStore` in `MainActivity`, which cancels that scope mid-write: the response never reached the server and no error surfaced, because the coroutine was cancelled before its `.onFailure` branch could run. `LifeLinkNotifications` already avoided this class of bug by routing writes through `AccountDataCoordinator`/`launchAccountWrite`, an application-scoped write that outlives any single screen. `DonorViewModel.respond()` now takes the same path via an injected `launchDurableWrite`, wired in `MainActivity` to `app.launchAccountWrite(accountUserId, ...)`. Any future action reachable from a single tap — not just push notifications — needs the same treatment if it must survive the screen closing.

Do not reintroduce any of these patterns. Do not add a migration solely because an HTTPS timeout occurs; first classify the failure as network reachability, authentication, API validation, or schema. Keep server error details visible enough to distinguish those cases without exposing secrets.

## Request lifecycle regressions (2026-09-30)

Submitting a request used to broadcast it: `create_emergency_request_postgres` computed matches and immediately pushed a `donor_match` notification to every matched donor. A request must not be broadcast on submit. Matches are still computed and stored so the requester can review them, but `notifications_created` is now `0` on create and donors are notified only when the requester contacts selected donors (`POST /v1/emergency-requests/{request_id}/contact`), which is the intended broadcast trigger. Do not add a push call back into the create path.

Cancelling a request used to leave it half-reset. The backend set the request status to `cancelled` but left every open `donor_contact_requests` row `pending`, so the requester's contact list still showed live contacts and the donor inbox still listed the request. `set_cancelled_async` now closes open contacts to `cancelled`. On Android, `EmergencyRequestViewModel.cancelRequest()` now resets the flow to idle (step `BLOOD_NEED`, cleared results/contacts) and starts a fresh `EmergencyRequestDraft`, and `ActiveRequestScreen` leaves the screen once `cancelCompleted` is set. Without the fresh draft the wizard kept the old draft id, which is the idempotency key, so the next submit returned the already-cancelled request instead of creating a new one. Keep `tests/test_postgres_end_to_end.py::test_submit_does_not_broadcast_but_contact_does` and `::test_cancel_resets_request_and_allows_a_new_one` passing.

## Donor availability regressions (2026-10-01)

Matching runs only when a request is submitted (`create_emergency_request_postgres` computes and stores matches) and when a donor's profile is saved (`register_donor_postgres` calls `recompute_matches_for_donor`). Changing availability did not re-evaluate matching, so a donor who was offline when a request was created had no stored match and never received it: the request never appeared in the donor inbox, and the requester's match list never included that donor, so the requester could not contact them and no notification was ever sent. `SqlAlchemyDonorStore.set_availability` now calls `recompute_matches_for_donor` when the donor becomes available (best-effort, after the availability change is committed, so a recompute failure cannot turn a saved availability into an error). Keep `tests/test_postgres_end_to_end.py::test_going_available_matches_existing_active_requests`, `::test_going_available_does_not_match_own_or_closed_requests`, and `::test_going_available_does_not_duplicate_an_existing_match` passing. Any future path that changes a donor's eligibility inputs (blood type, location, radius, visibility, availability) must re-run matching.

## Minimum validation before shipping

Run the backend compile and tests:

```bash
cd lifelink_fastapi
python3 -m compileall -q app
python3 -m pytest -q
```

Run `git diff --check`. Review every Room DAO call for owner scoping. Conceptually verify both a new account and an existing donor account, and verify that signing out then signing in as a different account cannot show the prior account’s active request or donor inbox. When no Android SDK exists in the sandbox, use the repository’s hosted GitHub Actions Android build instead of claiming a local APK build succeeded.
