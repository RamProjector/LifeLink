# LifeLink IDS/IPS release-gate verification

**Verification date:** 2026-11-28  
**Repository:** `RamProjector/LifeLink`  
**Implementation PR:** [#227 — Complete staged IDS/IPS authorization and scanner health](https://github.com/RamProjector/LifeLink/pull/227)  
**Implementation branch:** `security/ids-ips-consolidated-completion`  
**Verified implementation head:** `9c807646d7117cf146d020af93a5b8c74c9bfc38`

This document records a release-gate review. One additive production-data action was performed to restore the 20 audit examples the user had explicitly requested on 2026-11-23; that correction is documented below. No production deployment, migration application, privilege grant, RBAC assignment, Auth configuration change, or PR merge was performed.

## 1. Current-head CI evidence

All five workflow runs associated with implementation head `9c807646d7117cf146d020af93a5b8c74c9bfc38` completed successfully:

| Workflow | Run | Result |
|---|---:|---|
| Security scan / Gitleaks | [46092444584](https://github.com/RamProjector/LifeLink/actions/runs/46092444584) | Passed |
| Android UI test APK (fake backend) | [46092444628](https://github.com/RamProjector/LifeLink/actions/runs/46092444628) | Passed |
| CodeQL (Python and Java/Kotlin) | [46092444597](https://github.com/RamProjector/LifeLink/actions/runs/46092444597) | Passed |
| Build Android APKs | [46092444588](https://github.com/RamProjector/LifeLink/actions/runs/46092444588) | Passed |
| Validate cloud package | [46092444621](https://github.com/RamProjector/LifeLink/actions/runs/46092444621) | Passed |

The backend/PostgreSQL suite completed with **342 passed tests**, one upstream Starlette/AnyIO deprecation warning, and **64.98% total coverage**. Ruff and mypy passed. The cloud-package workflow also passed the Android quality gate, RLS-decision validation, Admin Console static integrity checks, and Docker build.

The debug APK and isolated fake-backend UI APK artifacts were present and unexpired at verification time:
- [Debug APK and Android validation artifacts — run #1679](https://github.com/RamProjector/LifeLink/actions/runs/46092444588)
- [Fake-backend UI APK — run #999](https://github.com/RamProjector/LifeLink/actions/runs/46092444628)

CI artifact creation is not equivalent to physical-device acceptance testing or manual browser review.

## 2. Live Supabase observations

The connected LifeLink database was inspected using read-only queries on 2026-11-28. One separately authorized additive insert restored the 20 requested audit examples; no other database writes were made during this verification.

| Item | Observed value |
|---|---:|
| Registered RBAC roles | 7 |
| Permission definitions | 19 |
| Role-permission mappings | 19 |
| User-role assignment rows | 0 |
| Security alerts | 0 |
| Session registry rows | 25 |
| Active session rows | 25 |
| Audit event rows | 393 |
| Restored user-requested demo audit rows | 20 |

The seven-role registry currently maps administrative permissions as follows: `operations_admin` 5, `user_admin` 5, `security_admin` 6, and `auditor` 3. `requester`, `donor`, and `super_admin` each have zero ordinary mapped administrative permissions. RBAC-03 in the implementation branch now checks the exact seven-role registry and flags unapproved extra role definitions even when no users are assigned.

The RBAC registry is populated, but no user has an active registry assignment. Keep `LIFELINK_RBAC_ENFORCEMENT_MODE=observe` until an explicitly approved, audited bootstrap process provisions and verifies the intended administrators in a non-production environment. Do not infer administrator access from public signup or client-editable profile metadata.

### Audit sample-data correction

A cleanup executed on 2026-11-27 removed 8 clearly labelled `DEMO:` security alerts, 40 audit events with demo/example/fixture metadata, and 5 synthetic session rows. **That audit cleanup unintentionally included the 20 audit examples the user had explicitly requested on 2026-11-23.** The exact original row payloads were not available in the retrieved history, so the examples were recreated rather than recovered byte-for-byte.

On 2026-11-28, exactly 20 equivalent fictional audit examples were inserted into `public.audit_events` under batch `LIFELINK-AUDIT-RESTORE-2026-11-28`:
- 4 authentication, 5 user-management, 3 request/responder, 2 GPS/chat, 2 security, and 4 administrator-operation examples.
- All rows have `metadata.is_demo=true`, unique IDs and log IDs, fictional actor/target/session identifiers, reserved documentation IP addresses, coarse device labels, and sample timestamps spanning the preceding four days.
- Read-only verification confirmed 20 rows, 20 distinct IDs, 20 distinct log IDs, 20 distinct correlation IDs, all 20 marked as demo, and all 20 IPs in reserved example ranges.
- Total audit-event count after restoration is 393.

The 8 sample security alerts and 5 synthetic session rows were not restored because the explicit user request was for 20 audit-log examples, not those additional datasets. Do not repeat the prior broad demo/example cleanup against the restored batch. No operational audit rows, user accounts, requests, donor records, or RBAC rows were changed during restoration.

The role-scoped findings table exists. The new `lifelink_security_scanner_runs` health table did not exist in the live database at the time of this check. The implementation branch contains migration `022_security_scanner_health.sql` and an idempotent startup schema path. Scanner health must be verified after an approved deployment; it must not be described as already live.

The migration ledger returned only:

- `20260929040902` — `007_donor_map_chat_contact_sharing`
- `20261125234130` — `020_rbac_role_permission_registry`

Do not blindly replay older migrations or manually rewrite the migration ledger. Reconcile schema history only through a reviewed, non-destructive baseline procedure.

### Auth-schema privilege boundary

The read-only grant inventory showed that `postgres` can SELECT `auth.users`, `auth.sessions`, and `auth.mfa_factors`. It showed no corresponding grants to `service_role`, `anon`, or `authenticated`.

The Render service metadata does not expose the database connection identity. The available Supabase connection snapshot did not identify a Render API connection. Consequently, this review does not prove which database role the running API actually uses. Before enabling the complete Auth-user inventory in production, verify the actual Render runtime connection role using a controlled, read-only runtime check. Grant only the minimum required SELECT privileges to the trusted server-side role if evidence shows they are missing. Never grant these Auth-table privileges to client roles.

### Supabase advisor observations

The security advisor returned:

- 18 informational findings for RLS-enabled tables without client policies. A separate effective-privilege query found zero tables among those 18 where either `anon` or `authenticated` has SELECT, INSERT, UPDATE, or DELETE access. Preserve the FastAPI-only data-access boundary; do not add broad client policies merely to silence the informational advisor.
- One warning: Supabase Auth leaked-password protection is disabled. The connected Supabase organization is on the Free plan; official Supabase documentation places leaked-password protection on Pro and above. Do not upgrade or add a paid service for this warning. Consider the free password-length and character-requirement controls after checking current Auth settings.
- The performance advisor reports one unindexed foreign key on `lifelink_user_roles.assigned_by`, 12 RLS auth-function init-plan notices, and 38 unused-index notices. The implementation branch adds migration `023_rbac_assigned_by_index.sql`, a guarded startup index path, and regression checks for the assignment-actor index. This index has not been applied to the live database. The 12 policy notices affect tables with no effective client-role privileges; the policies were not rewritten. No unused indexes were dropped because advisor counters alone do not establish a safe removal candidate.

## 3. Live Render deployment boundary

The LifeLink API and Admin Console services both track `main` and have automatic deployment enabled.

At verification time, the live API deployment referenced main commit `3205ed4eb9eba307eeb94ab7aa8d420d83376e0a`. Implementation head `9c807646d7117cf146d020af93a5b8c74c9bfc38` remains on the open PR branch and is not live.

Merging PR #227 would trigger automatic production deployment. No deployment, manual trigger, production migration, database grant, Auth setting change, or role assignment was performed or authorized by this verification.

## 4. Administrator notification boundary

LifeLink already has Firebase Cloud Messaging integration for Android requester/donor notifications. The backend reads its server-side credential from the Render environment variable `FIREBASE_SERVICE_ACCOUNT_JSON`; this review did not inspect or expose its value and did not establish that the secret is currently configured and valid.

The Admin Console implementation provides opt-in foreground browser notifications while the authenticated console is open. It stops polling after sign-out or authorization failure. This is not background delivery.

The following remain unimplemented or unverified:

- Background browser push or email delivery for administrators.
- Durable per-recipient delivery receipts and retry/backoff state.
- An unread alert indicator backed by actual delivery state.
- An end-to-end test from an induced RBAC discrepancy through administrator receipt and acknowledgement.

Do not claim that a stored security-alert row or foreground browser notification proves out-of-band delivery. Do not introduce a paid provider or a new external service without approval. The existing Android FCM path must not be treated as an administrator feature in Android; administrative operations remain web-only.

## 5. Review and acceptance gates

The PR had no submitted independent review and no inline review threads at verification time. CodeRabbit explicitly skipped automatic review because the repository has fewer than ten stars. Its comment is not an independent code review.

Before declaring IDS/IPS complete or merging the PR, complete these gates:

1. Verify the actual Render database runtime role's minimum Auth-schema read access without exposing secrets or granting client access.
2. Provision the intended administrator/RBAC-manager assignments through an approved, audited, out-of-band process in non-production.
3. Verify exact permission enforcement, expiry, missing-grant denial, registry-unavailable behavior, and the separate RBAC-manager safeguard with valid-JWT integration tests.
4. Verify scanner startup, periodic execution, failure recovery, shutdown, and persisted health on a deployed non-production instance. Render free-service sleep/restarts can interrupt the in-process scan loop; stale health is surfaced rather than treated as continuous coverage.
5. Decide and implement an approved administrator background-notification channel with durable delivery outcomes, or explicitly accept foreground-only notification as a documented product limitation.
6. Complete route-by-route audit coverage and manual Android/web acceptance, including cross-account isolation and mutation/audit transaction rollback.
7. Obtain an independent review and rerun all required checks on the final PR head.
8. Obtain explicit production deployment and merge approval because the Render services automatically deploy from `main`.

## 6. Explicit safety state

The only production write during this verification was the additive restoration of the 20 user-requested demo audit examples described above. No deployment, migration application, privilege grant, RBAC assignment, Auth configuration change, or destructive cleanup was performed during this verification. RBAC enforcement remains in observe mode; no administrator assignments were created; and PR #227 remains draft, open, and unmerged.


## 7. Additional Render/runtime correlation check — 2026-11-28

A further read-only check was made after the final CI run:

- Render workspace service inventory confirms the LifeLink API service is `srv-dalugdbl550s73chtsfg`, rooted at `lifelink_fastapi/`, running `uvicorn app.main_postgres:app`, on the Free plan in Singapore, with one instance, and automatic deployment from `main`.
- The LifeLink API's latest live Render deployment remains `3205ed4eb9eba307eeb94ab7aa8d420d83376e0a` (PR #226). The Admin Console static site also auto-deploys from `main`. PR #227 is not deployed.
- The recent Render application log sample contained routine `GET /health` 200 responses and a normal process shutdown. A filtered search for `permission denied`, `auth.users`, `lifelink_security_scanner_runs`, `RBAC discrepancy scan`, and `database connection` returned no matching records in the queried window. This is not proof that those code paths were exercised; the candidate PR is not deployed.
- A read-only `pg_stat_activity` snapshot on the connected Supabase project showed only internal Supabase/PostgREST/pgbouncer/exporter/cron connections. It did not identify a Render API connection, a Render-specific application name, or a Render client address. No role can therefore be attributed to the running API from this snapshot.
- The Render service metadata endpoint does not return environment-variable names or values, and this review did not read or expose secrets. Do not use a guessed `DATABASE_URL` or a guessed connection role to test Auth-schema privileges.

**Conclusion:** The Render runtime database identity remains unverified. This is an evidence limitation, not evidence of a permission failure or success. The next valid test must execute a read-only identity query from the actual API runtime (for example, a tightly scoped, authenticated internal diagnostic that returns only `current_user` and `session_user`, never a DSN, password, host, or token), then verify the minimum required Auth-schema reads for that exact role. Do not add a public unauthenticated endpoint or expose connection details. Prefer a staging deployment and remove the diagnostic after the check.

The PR was changed from draft to ready-for-review after all five workflows passed. A fresh `/review` request was posted. No independent review result had been received at the time this note was written. This does not authorize merge or production deployment.
