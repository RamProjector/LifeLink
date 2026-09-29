# LifeLink Project Guardrails

This file is the regression contract for every future change to LifeLink. Read it before modifying authentication, donor pages, request persistence, or navigation.

## Non-negotiable account rules

1. **Never default a signed-in account to requester mode.** Resolve the role from the authenticated server profile. Show role selection only when the authenticated account has no profile yet.
2. **Role preferences are per account.** Never store one global `role` preference. A donor account must not inherit the previous account's requester/donor role.
3. **Every local record that can appear in a page must be account-scoped.** This includes donor profiles, donor inbox requests, requester active requests, drafts, pending submissions, and updates. A database primary key alone is not an account boundary.
4. **Sign-out and account switching must not expose the previous account's records.** Test account A → sign out → account B on the same device.

## Donor page rules

5. **The donor page must hydrate from the authenticated server profile.** Local Room state is a cache, not the source of truth. A profile saved on another device or before reinstall must appear after login.
6. **Donor endpoints must enforce authenticated identity.** The path donor ID must match the authenticated user ID; never trust a client-supplied donor ID as authorization.
7. **Donor setup gating is server-enforced.** An incomplete donor cannot become available or receive an inbox.
8. **Map/GPS updates must not replace unsaved form fields.** Location actions may update latitude, longitude, and precision only. Text, chips, switches, notes, and radius remain in the draft.

## Request rules

9. **Requests shown to a donor must be filtered by that donor's authenticated ID.** Never read all local donor requests without a `WHERE donorId = ...` condition.
10. **Requester history and active requests must be filtered by authenticated requester ID.** Do not rely on request ID alone for local display.
11. **API refresh must replace the account's cache, not append stale data forever.** Delete or reconcile the current account's old cached records before inserting the latest response.
12. **Server profile and request reads must handle cold starts and retryable errors without clearing the draft.** A failed refresh must not turn a saved profile into an empty profile.

## Validation checklist

Before merging donor/auth changes:

- Backend regression suite passes.
- Android unit tests and lint pass before `assembleDebug`.
- A new-account flow asks for a role once.
- Existing requester account opens requester mode without role selection.
- Existing donor account opens donor mode without role selection.
- Account A cannot see Account B's donor profile, inbox, active request, history, or updates.
- Donor profile saved on the server hydrates on a fresh local database.
- Auto-location, map tap, recenter, retry, and background refresh preserve unsaved donor fields.
- Firebase-enabled APK build is tested separately from emulator smoke tests.

## Change-log discipline

For every implementation chat, update the relevant plan or guardrail document in this repository. Record the symptom, root cause, files changed, tests run, and any remaining device-only validation. Do not remove a guardrail because a single emulator run passes.
