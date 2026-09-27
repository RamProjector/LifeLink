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

Do not reintroduce any of these patterns. Do not add a migration solely because an HTTPS timeout occurs; first classify the failure as network reachability, authentication, API validation, or schema. Keep server error details visible enough to distinguish those cases without exposing secrets.

## Minimum validation before shipping

Run the backend compile and tests:

```bash
cd lifelink_fastapi
python3 -m compileall -q app
python3 -m pytest -q
```

Run `git diff --check`. Review every Room DAO call for owner scoping. Conceptually verify both a new account and an existing donor account, and verify that signing out then signing in as a different account cannot show the prior account’s active request or donor inbox. When no Android SDK exists in the sandbox, use the repository’s hosted GitHub Actions Android build instead of claiming a local APK build succeeded.
