# LifeLink project guardrails

Read this file and `docs/PROJECT_CONTEXT_AND_REGRESSION_GUARDRAILS.md` before changing authentication, account roles, request persistence, or donor pages.

## Non-negotiable behavior

- A LifeLink account is capability-based: requester and donor capabilities may coexist. Do not reintroduce an exclusive requester-versus-donor product model.
- A newly authenticated account must not be forced through a requester/donor choice screen. Start new accounts in the requester shell; donor mode is entered from Profile after setup.
- Existing accounts must be routed from the authenticated server profile, not a single global local preference. Never let one account's role determine another account's startup screen.
- A donor account with a saved donor role must open donor mode after login. A requester account must not unexpectedly open donor setup.
- Donor requests stay hidden until the donor profile is complete. The donor page must restore saved donor profiles from the server and must not rely only on an empty local Room row.
- Cached requests, active requests, and donor feeds must be keyed by authenticated user ID. Never use a global cache query for account-specific data.
- Users must never see or accept their own emergency requests as donor opportunities. Keep both server-side exclusion and response-time self-request rejection.
- Do not “fix” a client timeout by adding a database migration. First distinguish connectivity, authentication, server validation, and schema errors.
- Do not remove the Room migration or replace account scoping with destructive resets. Legacy unowned cache rows should be ignored, not shown to another account.

## Verification minimum

- Backend: `python3 -m compileall -q app && python3 -m pytest -q`
- Android: run the hosted GitHub Actions Android build when the sandbox has no Android SDK.
- Before shipping, inspect `git diff --check`, confirm the authenticated user ID is used in every local DAO query, and test both a new account and an existing donor account conceptually.

## Current known architecture

- `MainActivity.kt` loads `/v1/profile` after Supabase sign-in. Server role/capabilities determine startup; local role preferences are keyed by user ID only.
- `DonorRepository.kt` restores `/v1/donors/{donorId}` before loading the donor inbox and scopes donor requests by owner ID.
- `LifeLinkDatabase.kt` is version 9. Migrations 6→7→8→9 preserve legacy rows while introducing account ownership; drafts, pending submissions, and updates have composite owner/item keys. Legacy unowned rows have an empty owner and must not be returned.
- `main_postgres.py` returns `can_request` and `can_donate` in profile responses and exposes authenticated donor profile GET.
