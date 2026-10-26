# Requester and Donor Lifecycle Audit Checklist

**Prepared:** 2026-10-26  
**Status:** Audit plan; not a claim of production verification  
**Related roadmap:** [Future Update Roadmap](FUTURE_UPDATE_ROADMAP.md)

## Why this exists

LifeLink has API and Android model definitions for many requester and donor operations. The presence of a Retrofit endpoint or response model does not prove that the screen is reachable, the server enforces the correct state transition, the database persists it, or a second account is denied access. Use this checklist before extending lifecycle behavior.

## Endpoint inventory to verify against current source

The Android API interface currently describes these families of operations:

| Area | Known client contract | Required verification |
|---|---|---|
| Request creation and status | Create emergency request; retrieve status; cancel/fulfill; requester history | Ownership, allowed state transitions, idempotency, history includes every terminal state |
| Requester-to-donor contact | Select/contact donors; retrieve contact states; contact/meeting status actions | Match ownership, consent, no disclosure after close/expiry, duplicate prevention |
| Donor profile and availability | Read/write donor profile; update availability; donor opportunities; accept/decline response | Profile belongs to authenticated user; setup completion; availability triggers match recomputation; no self-response |
| Conversations | Open request-scoped conversation; list conversations/messages; send message | Only the two participants can read/write; request is still eligible; bounded message size/rate |
| Contact sharing | List/create contact shares | Explicit field-by-field consent; authorization; revocation/expiry semantics; audit record |
| Location sharing | Activate/revoke exact-location share; read shared location; set map visibility | Donor opt-in, requester must be the matched request owner, share expires and revokes, no coordinates in unrelated responses |
| Report/block | Report or block a conversation | Action is reachable, abuse-resistant, effects are explicit and consistently enforced |
| Admin | Admin identity/dashboard/users/audit/error records | Server-side allowlist; bounded pagination; no privilege elevation through profile editing |

Re-check names and behavior in the current OpenAPI contract and implementation before coding. This table is an inventory of client contracts, not a substitute for backend route tests.

## Required state model

For each request, document and test transitions among the actual backend statuses. At minimum, verify active/open, completed or fulfilled, cancelled, and expired behavior. Do not invent status values; use the enum and schema already in the repository.

For each donor response/contact, document pending, accepted, declined, cancelled/withdrawn, and any other existing states. Define which party may cause each transition, whether it is idempotent, and which notifications or audit events follow. An accepted donor contact is not proof that blood was donated. A fulfilled request is terminal history.

## Authorization matrix

Use two distinct authenticated test users (A and B), with A and B each capable of requester and/or donor behavior where supported. Also test unauthenticated calls and an admin account where relevant.

| Operation | Owner/participant | Unrelated user | Must fail when |
|---|---|---|---|
| Read request/history | Request owner, and only explicitly permitted participants for specific views | Denied | Wrong owner ID, invalid token, or unauthorized request ID |
| Edit/cancel/fulfill/repost request | Request owner subject to lifecycle rules | Denied | Terminal/expired state or conflicting active request |
| View/respond to donor opportunity | Eligible donor, excluding own requests | Denied | Ineligible, stale, own, closed, or expired request |
| Read/send conversation messages | Actual conversation participants | Denied | Not a participant, blocked state where enforced, or invalid conversation ID |
| Share contact details | Authorized conversation participant | Denied | No valid relationship/consent or policy forbids disclosure |
| Share/read exact location | Donor controls opt-in; request owner reads only during valid share | Denied | No match, no opt-in, revoked/expired share, closed request |
| Read admin records/change capabilities | Explicitly allowlisted admin | Denied | Authenticated user is not in server-side admin allowlist |

Do not rely on client visibility to enforce this matrix. Test API calls directly with altered IDs and fields.

## Requester lifecycle checklist

- [ ] Active request can be reopened after app restart and sign-in.
- [ ] Active and terminal history is clearly separated and ordered deterministically.
- [ ] Each history item opens the correct detail, responses, and contact timeline.
- [ ] Editing is only available in server-approved states; stale donor commitments cannot survive silently.
- [ ] Repost/reactivation is blocked when another active request exists.
- [ ] Server/database enforce one active request atomically under concurrent requests.
- [ ] Cancel/fulfill/expiry closes pending contacts and revokes associated location shares.
- [ ] Failed network writes show an actionable state and cannot create duplicate requests on retry.
- [ ] Signing out and switching account clears or re-scopes account-specific UI/cache.

## Donor lifecycle checklist

- [ ] Donor profile is restored from the server before deciding setup is incomplete.
- [ ] Availability is distinct from profile existence, profile visibility, and verification.
- [ ] Going available or changing match-relevant profile fields recomputes matches without duplicates.
- [ ] Inbox excludes the donor's own requests.
- [ ] Accept/decline is durable across quick navigation or sign-out and reports failures honestly.
- [ ] Closed/expired requests cannot be accepted after stale inbox display.
- [ ] Donor history shows accepted, declined, cancelled, fulfilled, and expired outcomes where the backend supports them.
- [ ] Donor can reach the request-scoped conversation from the relevant opportunity/commitment.
- [ ] Location/contact sharing is opt-in, scoped, revocable, and understandable.
- [ ] Pausing availability explains that it changes matching; it does not imply medical ineligibility.

## UI state checklist for every screen

For every list/detail/action screen, check:
- initial loading and refresh;
- no records;
- API timeout/offline and retry;
- authentication expiry and reauthentication;
- validation errors that identify the field/action without leaking internal details;
- stale data and server conflict (e.g., another user closed the request);
- double tap/retry and idempotency;
- process death/recreation and account switch;
- large text, TalkBack/keyboard focus, accessible names, and adequate touch targets.

## Minimum test layers

1. **Unit tests:** state mapping, permitted transitions, retry/idempotency, UI state restoration.
2. **API tests:** owner vs unrelated user, forged IDs, invalid transitions, stale/expired requests, duplicate operations, sensitive response fields.
3. **Database integration:** real transaction/concurrency behavior, uniqueness/locking, persistence of terminal history, cascade/revocation behavior. Mock-only tests cannot prove concurrency guarantees.
4. **Android checks:** formatting/lint/build, ViewModel/repository tests, navigation reachability, lifecycle/account-switch regression.
5. **Live verification:** only after automated tests pass, use explicitly authorized test accounts and non-destructive data. Never print real contact details or precise coordinates in test logs.

## Definition of audit completion

Mark a checklist item complete only with a source path or test name plus observed result. Use these labels in future status notes: **implemented**, **automated-test verified**, **emulator/device verified**, **live deployment verified**, or **not verified**. These labels are not interchangeable.

## References

- OWASP API Security Top 10: https://owasp.org/API-Security/editions/2023/en/0x11-t10/
- Supabase Row Level Security: https://supabase.com/docs/guides/database/postgres/row-level-security
- Android app architecture: https://developer.android.com/topic/architecture
- WCAG 2.2: https://www.w3.org/TR/WCAG22/
