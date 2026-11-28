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


## 8. Supabase Auth MFA recovery semantics — documentation verification

Supabase Auth documentation was rechecked on 2026-11-28 against the current JavaScript MFA API, application MFA guide, and TOTP guide:

- Supabase's current JavaScript MFA reference says application MFA supports TOTP and phone as second factors, does not support recovery codes through that API, and permits up to 10 enrolled factors. A second verified TOTP authenticator is therefore the supported application-level backup path used by this implementation.
- Do not confuse recovery codes for a person's Supabase Dashboard account with recovery for users in the LifeLink Supabase Auth project. The Dashboard's account-security recovery-code feature is not evidence that LifeLink end users can use those codes for project Auth MFA.
- Supabase's TOTP guide documents the AAL1-to-AAL2 enrollment/challenge/verify flow. Only a successfully verified factor becomes active. The application must enforce the resulting assurance level server-side; displaying an MFA screen alone is not enforcement.
- Supabase's application MFA guide states that factor unenrollment requires an AAL2 session and that an existing AAL2 JWT may need an explicit session refresh to reflect the downgrade to AAL1 immediately. Factor replacement must therefore retain at least one other verified factor and verify the replacement before removing the old factor; do not allow a self-service path that removes the final factor without a separately approved recovery procedure.
- The current project design's requirement for two verified TOTP factors is a stricter LifeLink administrator policy than merely enrolling one factor. It is a deliberate backup-authenticator control, not a claim that Supabase supplies app recovery codes.

Current references checked:
- Supabase JavaScript Auth MFA reference: https://supabase.com/docs/reference/javascript/auth-mfa
- Supabase application MFA guide (updated 2026-11-18): https://supabase.com/docs/guides/auth/auth-mfa
- Supabase TOTP guide: https://supabase.com/docs/guides/auth/auth-mfa/totp

No Supabase Auth settings, MFA factors, user accounts, or production configuration were changed during this documentation review.

## 9. Superseding verification snapshot — 2026-11-28 11:30 UTC

This section supersedes any earlier head, workflow, PR-state, or review-status statements above where they differ.

- Current PR #227 state: **open, ready for review, unmerged, mergeable**. Base: `main`. Head branch: `security/ids-ips-consolidated-completion`.
- Current verified head: `5da45a77797eb34e1df7563d75b84ec45757c472`. The branch has 140 commits, 27 changed files, and the PR metadata reports no merge conflict.
- All five PR-triggered workflows associated with this exact head completed successfully:
  - Security scan / Gitleaks: run [#2165](https://github.com/RamProjector/LifeLink/actions/runs/46102887185) — success.
  - Android UI test APK (fake backend): run [#1004](https://github.com/RamProjector/LifeLink/actions/runs/46102887199) — success.
  - Validate cloud package: run [#4432](https://github.com/RamProjector/LifeLink/actions/runs/46102887200) — success. The Android quality gate, Admin Console/SVG validation, Docker build, backend lint, and backend/PostgreSQL test jobs all completed successfully.
  - Build Android APKs: run [#1684](https://github.com/RamProjector/LifeLink/actions/runs/46102887191) — success. Debug APK artifact SHA-256: `9c6b4ae787a63b84da55ce450e58f7bec28f860aadcf0781c01270d78abf22c9`.
  - CodeQL: run [#2063](https://github.com/RamProjector/LifeLink/actions/runs/46102887212) — success.
- The PR's current validation record reports 343 backend tests passed, Ruff and mypy passed, Gitleaks passed, Python and Java/Kotlin CodeQL passed, and Android lint/Detekt/Spotless/unit tests/debug build passed. The single upstream Starlette/AnyIO deprecation warning is non-failing.
- The latest GitHub review queries returned **zero submitted PR reviews and zero inline review threads**. The latest CodeRabbit command response says the review was triggered; it is not a completed review or an approval. Do not claim independent review is complete.
- The two Android APK artifacts remain available through 2026-12-12. The APK build run above contains the downloadable artifacts; artifact hashes are recorded in the PR description.
- Current live-production boundaries remain unchanged: the candidate PR is not deployed; Render services auto-deploy from `main`; the actual Render API database role is still unverified; the scanner-health migration is not live; user-role assignment count remains zero; RBAC stays in `observe`; no MFA non-production rehearsal or manual browser/device acceptance has been evidenced in this refresh.
- No production migration, database grant, Auth configuration change, role assignment, deployment, or merge was performed by this verification.

**Disposition:** implementation CI is green on the current head, but the release is not complete. Keep PR #227 open. Required external gates remain runtime DB-role verification, non-production MFA and RBAC enforcement/recovery rehearsals, scanner lifecycle verification after a non-production deployment, notification-channel decision, manual web/Android acceptance, independent review, and explicit production deployment/merge approval.

## 10. CodeRabbit finding remediation and final code-head validation — 2026-11-28

This section supersedes earlier review and CI statements where they differ.

### Remediated review findings

The 12 CodeRabbit inline findings from the 2026-11-28 review were individually checked against the current code, fixed, and their GitHub review threads resolved:

1. Initial browser-notification cursor now comes from PostgreSQL `clock_timestamp()` when the client omits a cursor; browser time is not used.
2. The browser notification polling interval is installed before the initial awaited poll, and generation checks prevent stale responses from reviving polling or changing status after sign-out.
3. The audit-coverage document now explicitly says coarse device labels apply to new writes; historical audit rows may still contain raw User-Agent values and are returned unchanged.
4. RBAC assignment expiry is checked with `statement_timestamp()`, not transaction-start `now()`.
5. The user-directory disclosure policy now explicitly states that `users.read` includes active RBAC role keys and mapped permission summaries; full role-registry and assignment-detail endpoints remain under `roles.read`.
6. Audit filter metadata uses `is not None` semantics so an explicitly supplied empty filter is recorded as a filter.
7. Scanner success timestamps use `clock_timestamp()` for actual completion time.
8. Scanner failure timestamps also use `clock_timestamp()`; a failure update preserves the previously recorded scan start time instead of overwriting it with failure time.
9. iOS User-Agent classification now requires an iPhone/iPad/iPod marker or standalone `ios` token; client names such as `axios` remain Unknown device.
10. Migrations 021 and 022 revoke from `anon` and `authenticated` only when those roles exist, while always revoking from `PUBLIC`.
11. The scanner-health no-row test removes `LIFELINK_RBAC_SCAN_SECONDS` from its environment so it tests the default deterministically.
12. The RBAC assigned-by index startup helper is now tested with fake connections for both the absent-table and present-table cases.

Additional regression coverage verifies FastAPI lifespan starts all four background workers, cancels them on shutdown, and does not start them when schema setup fails. A notification-feed test verifies the initial cursor uses the PostgreSQL clock.

All 12 inline review threads are resolved. A fresh CodeRabbit review was requested on the final code head; no new review result has yet been received. The previous CodeRabbit review record remains `CHANGES_REQUESTED` until a fresh review/approval updates it; thread resolution alone is not an approval.

### Final implementation-code head and CI

**Implementation-code head:** `83be962b978fd9e2eb3bb1978d52c07347b7d010`.

All five required workflows passed on this exact code head:

| Workflow | Run | Result |
|---|---:|---|
| Security scan / Gitleaks | [#2186](https://github.com/RamProjector/LifeLink/actions/runs/46105226900) | Passed |
| Android UI test APK (fake backend) | [#1025](https://github.com/RamProjector/LifeLink/actions/runs/46105226892) | Passed |
| Validate cloud package | [#4453](https://github.com/RamProjector/LifeLink/actions/runs/46105226909) | Passed |
| Build Android APKs | [#1705](https://github.com/RamProjector/LifeLink/actions/runs/46105226866) | Passed |
| CodeQL (Python and Java/Kotlin) | [#2084](https://github.com/RamProjector/LifeLink/actions/runs/46105226893) | Passed |

Backend/PostgreSQL validation: **349 passed**, one upstream Starlette/AnyIO deprecation warning, **65.08% coverage**. Ruff, mypy, Admin Console/inline-SVG validation, Docker build, RLS decision check, Android lint/Detekt/Spotless/unit tests/debug build, Gitleaks, and both CodeQL language analyses passed.

Artifacts from this code head (available until 2026-12-12):
- [Debug APK and Android validation reports — run #1705](https://github.com/RamProjector/LifeLink/actions/runs/46105226866)
  - APK archive SHA-256: `8565c97f65ed82f5fcea0b9313f4dd7083bb0cb0b2443457e55b816967946e27`
  - Validation archive SHA-256: `d39f04370891b7d65535dcc12f5fcb2eb884f6be00e18cffa6dc4830cff4c6f1`
- [Fake-backend UI APK — run #1025](https://github.com/RamProjector/LifeLink/actions/runs/46105226892)
  - Artifact SHA-256: `718909fd853c79295d24c05254ac00842c1ccce432a9ff5bc950a07ee0eac34f`

The PR remains open, ready for review, mergeable, and unmerged. The code head is 176 commits ahead of `main`, with 27 changed files. No production deployment, migration application, privilege grant, RBAC assignment, Auth configuration change, or merge was performed.

### Release boundary remains unchanged

The successful code-head CI does not close the non-production/production gates: actual Render runtime database-role identity; non-production MFA and two-operator recovery rehearsal; approved administrator assignments and enforcement verification; deployed scanner lifecycle/health verification; administrator background-notification channel decision and durable delivery receipts; manual web/Android acceptance; independent review; and explicit deployment/merge approval. Keep RBAC in `observe` and do not merge or deploy until those gates are completed.


## 11. Standards-based security research and current PR verification — 2026-11-28

This section is a superseding research and repository-state snapshot. It does not authorize production rollout.

### Standards reviewed

1. **OWASP Application Security Verification Standard (ASVS) 5.0.0 — stable, published 2025-05-30.** Use ASVS as the application-security verification framework. In particular, its V16 Security Logging and Error Handling objective distinguishes security-event records from ordinary error/performance logs and requires useful, protected security telemetry without unnecessary sensitive personal data. Apply this to authentication decisions, authorization denials, policy bypass attempts, alert handling, audit integrity, and safe error responses. Do not treat the presence of an audit table as proof of complete coverage.
   - https://asvs.owasp.org/
   - https://github.com/OWASP/ASVS/blob/master/5.0/en/0x25-V16-Security-Logging-and-Error-Handling.md

2. **OWASP API Security Top 10 (2023).** The most directly applicable risks are API1 Broken Object Level Authorization, API2 Broken Authentication, API3 Broken Object Property Level Authorization, API4 Unrestricted Resource Consumption, API5 Broken Function Level Authorization, API8 Security Misconfiguration, and API9 Improper Inventory Management. Verification must test cross-account object access, sensitive-field disclosure/mass assignment, role-specific route access, pagination and scan bounds, production-safe defaults, and inventory completeness—not merely HTTP status codes.
   - https://api-security.owasp.org/editions/2023/en/0x11-t10/
   - https://devguide.owasp.org/en/07-training-education/07-api-top-ten/

3. **NIST SP 800-61 Rev. 3 — final, published 2025-04-03.** Rev. 3 supersedes Rev. 2 and integrates incident response into CSF 2.0 cybersecurity risk management. LifeLink's alert triage, evidence preservation, escalation, recovery, and post-incident review should be treated as an operational lifecycle, not just an alert-generation feature.
   - https://csrc.nist.gov/pubs/sp/800/61/r3/final

4. **NIST SP 800-92 Rev. 1 — Initial Public Draft only.** NIST's Log Management publications page still lists Rev. 1 as Draft. It can inform planning, but must not be cited as a final standard or used to claim formal compliance. The final 2006 SP 800-92 remains the final publication in that series listed by NIST.
   - https://csrc.nist.gov/pubs/sp/800/92/r1/ipd
   - https://csrc.nist.gov/Projects/log-management/publications

### Evidence mapping to this implementation

| Research requirement | LifeLink implementation/evidence | Remaining verification |
|---|---|---|
| Authentication and function-level authorization | Server-side verified identity, active-session/AAL2 checks, admin allowlist, endpoint-specific permission dependencies, separate RBAC-manager allowlist, staged `observe`/ `enforce` behavior | Non-production valid-JWT integration rehearsal for exact grants, expiry, missing grants, registry failure, and super-admin dual-allowlist exception |
| Object and property-level authorization | Per-user audit filters, user inventory rooted in `auth.users`, profile-less accounts represented, restricted role/permission disclosure | Route-by-route cross-account and field-disclosure acceptance; confirm every ID-bearing handler checks ownership/scope |
| Resource consumption and scanner safety | Bounded scanner interval, advisory lock, bounded statement timeout, pagination limits, persisted scanner health | Load/abuse testing and deployed non-production scanner start/success/failure/recovery/shutdown verification |
| Security logging and privacy | Structured audit events, correlation IDs, coarse device labels for new writes, transaction-aware audit behavior | Complete route-to-audit matrix and manual rollback/idempotency tests; historical raw User-Agent records remain historical data and are not claimed to have been rewritten |
| Alerting and incident response | Protected alert feed and opt-in foreground browser notification polling | Background administrator delivery and durable per-recipient receipts are not implemented; test end-to-end alert triage, acknowledgement, escalation, and recovery only after a channel is approved |
| Platform and inventory security | Gitleaks, CodeQL, RLS decision checks, Android quality checks, CSP/static Admin Console validation | Independent review and manual browser/physical-device acceptance remain required |

### Current PR and review state — live GitHub verification

- PR #227 is **open, ready for review, mergeable, and unmerged**, targeting `main`.
- Current PR head: `04cd30c0d9e15876af44cd96062a89f7d9b78fb3`; 177 commits and 27 changed files.
- The 12 CodeRabbit inline review threads are all marked resolved and outdated at the current head. Resolution means the inline findings were addressed; it does not constitute reviewer approval.
- The submitted CodeRabbit review remains `CHANGES_REQUESTED`. Follow-up review requests were rate-limited and no fresh approval has been recorded. An independent human review remains outstanding.
- All five PR-triggered workflows associated with the current documentation-only head completed successfully:
  - Security scan / Gitleaks: [run #2188](https://github.com/RamProjector/LifeLink/actions/runs/46105648759).
  - Android UI test APK (fake backend): [run #1027](https://github.com/RamProjector/LifeLink/actions/runs/46105648746).
  - Build Android APKs: [run #1707](https://github.com/RamProjector/LifeLink/actions/runs/46105648653).
  - Validate cloud package: [run #4455](https://github.com/RamProjector/LifeLink/actions/runs/46105648707).
  - CodeQL: [run #2086](https://github.com/RamProjector/LifeLink/actions/runs/46105648718).
- The latest documentation-only commit does not change application code. The immediately preceding implementation-code head remains `83be962b978fd9e2eb3bb1978d52c07347b7d010`, with 349 backend/PostgreSQL tests passing, 65.08% coverage, and the validation recorded in section 10.

### Release disposition

This research refresh closes a documentation/research traceability gap only. It does not close the operational release gates. Keep RBAC in `observe`; do not merge or deploy until the actual Render runtime database role is verified, non-production MFA and two-operator recovery rehearsals pass, administrator assignments and enforcement are approved and tested, scanner lifecycle is verified on a deployed non-production service, the administrator notification delivery decision is made, manual web/Android acceptance is recorded, independent review is complete, and explicit merge/deployment approval is obtained.

No production database migration, privilege grant, RBAC assignment, Auth configuration change, deployment, or merge was performed as part of this research refresh.


## 12. Auth account-state visibility in the Admin Console — implementation update

The paginated `GET /v1/admin/users` inventory now includes read-only Auth account-state fields derived from `auth.users`:

- `account_state`: `unknown` if the Auth JSON row does not expose both `deleted_at` and `banned_until`; otherwise `deleted` when `deleted_at` is populated, `banned` when `banned_until` is later than PostgreSQL `statement_timestamp()`, and `active` only when both fields are available and neither condition applies.
- `email_confirmed`: `true`/`false` when `email_confirmed_at` is available, otherwise `null` (rendered as unknown).
- `is_anonymous`: `true`/`false` when the Auth anonymous-account field is available, otherwise `null` (rendered as unknown).
- The Admin Console displays the status, email verification, and anonymous-account marker beside the profile and RBAC data.

The query uses `to_jsonb(auth.users)` extraction for optional Auth fields rather than hard references to optional columns. This avoids a hard parse-time dependency on those columns in Supabase Auth schema versions where optional fields may differ. The status is descriptive inventory only: the console does not suspend, ban, delete, restore, or otherwise mutate Auth identities. Unknown/missing response values render as `unknown` in the UI; they are not silently labeled active.

Regression coverage was added to the existing admin user-inventory test for response mapping and SQL field extraction. The Admin Console's single inline script remains hash-authorized in the Content Security Policy; its CSP hash was recalculated after the UI change. The Admin Console docs and IDS/IPS completion audit were updated in the same PR.

**Acceptance boundary:** automated coverage verifies the constructed query and response mapping, not the actual Auth account states in a live Supabase instance. Controlled non-production test accounts in active, banned, deleted, email-unverified, and anonymous states must be used to confirm the live Auth schema values and rendered output before treating this criterion as operationally verified. No Auth state was changed and no production data was queried or modified as part of this implementation.

## 13. Live runtime-role correlation and query-time RBAC refresh — 2026-11-28

This section records a new read-only verification pass against the connected LifeLink Supabase project and Render services. It supersedes earlier assumptions where newer observations differ. It does not authorize deployment, migration, grants, role assignment, or RBAC enforcement.

### Render deployment boundary

- The Render API service `srv-dalugdbl550s73chtsfg` is configured to deploy from `main`, with auto-deploy enabled. The Admin Console static site also deploys from `main`.
- The latest API deployment is live on `3205ed4eb9eba307eeb94ab7aa8d420d83376e0a` (PR #226 merge), not this PR branch.
- The current PR #227 branch is therefore not deployed. No deploy was triggered.

### Supabase live schema and data snapshot

Read-only checks against project `nphbhlxnujmylhubzmne` confirmed:

- Project status: `ACTIVE_HEALTHY`; PostgreSQL `17.6.1.166`.
- Supabase migration history currently lists only `007_donor_map_chat_contact_sharing` and `020_rbac_role_permission_registry`. PR migrations 021, 022, and 023 have not been applied to the live project.
- `lifelink_roles`: 7 rows; `lifelink_permissions`: 19 rows; `lifelink_role_permissions`: 19 rows.
- `lifelink_user_roles`: 0 assignments; `lifelink_security_alerts`: 0 rows; `lifelink_user_sessions`: 25 rows; `audit_events`: 393 rows.
- `lifelink_security_scanner_runs` is absent from the live schema, as expected before migration 022 is deployed.
- The live `auth.users` schema includes `email_confirmed_at`, `banned_until`, `deleted_at`, and `is_anonymous`, which are the optional account-state fields read by the PR's inventory query.

### Auth-schema privilege boundary

A read-only `has_table_privilege` matrix confirmed:

| Database role | auth.users SELECT | auth.sessions SELECT | auth.mfa_factors SELECT |
|---|---:|---:|---:|
| postgres | yes | yes | yes |
| service_role | no | no | no |
| authenticated | no | no | no |
| anon | no | no | no |

This proves the `postgres` role has the required grants, but it does **not** prove that the Render API uses `postgres`. No grants were changed.

### Runtime connection correlation result

- A point-in-time `pg_stat_activity` query showed only `authenticator` / PostgREST, `pgbouncer`, and `postgres` sessions associated with `mgmt-api` and `pg_net`; no identifiable Render API connection was present in that snapshot.
- Supavisor logs in the available window showed the pool user as `postgres` and the pooler application name as `Supavisor`. These fields did not identify a Render service or its runtime DB role.
- PostgreSQL log aggregation showed `postgres` sessions with `Supavisor` and `mgmt-api` application names; no distinct Render API application name was identified.
- Render service metadata exposes the service configuration and deploy SHA but not the environment-variable values or the effective DB username. No secrets were requested, retrieved, or copied.

**Finding:** The actual Render API database role remains unverified. Do not infer that it is `postgres` from Supavisor logs or from the privileges held by `postgres`. The release gate remains open until the effective runtime role is established using a safe, non-secret method and its least-privilege grants are checked.

### Additional branch hardening

The current PR branch now changes all active-role inventory, role-detail, role-replacement, and RBAC discrepancy-scanner reads in `lifelink_fastapi/app/admin_api.py` from transaction-start `now()` to statement-start `statement_timestamp()`. This keeps displayed and evaluated active role assignments aligned with the authorization guard's expiry semantics when the request transaction has already been open during authentication/MFA checks. The inventory assertion was updated and a focused scanner regression test verifies the scanner query uses statement time.

CI for the resulting commit must pass before this code change is treated as validated. This is additive to the existing query-time expiry guard and does not alter role assignments or enforcement mode.

### Security Advisor interpretation

The latest Supabase security advisor still reports 18 RLS-enabled/no-policy tables and leaked-password protection disabled. The no-policy tables remain backend-only by design; a separate grant inspection must continue to confirm no client-role access before rollout. Leaked-password protection remains a plan-level limitation on the connected Free organization and is not addressed by a schema change or an unapproved paid-plan upgrade.

### Release disposition

Keep `LIFELINK_RBAC_ENFORCEMENT_MODE=observe`. Do not apply migrations 021–023, grant Auth-schema privileges, provision role assignments, enable enforce mode, or deploy PR #227 until the Render runtime role is positively identified, non-production MFA and two-operator recovery rehearsals pass, scanner health is verified after deployment to non-production, browser/Android acceptance is recorded, independent review is complete, and production rollout is explicitly approved.


## Superseding three-role authorization decision — 2026-11-28

The product role requirement is exactly **Requester, Blood Donor, and Admin**. The earlier RBAC registry design in migration 020 introduced administrator subroles and super_admin, which are not approved product roles. The PR branch now adds migration 024 to normalize the registry to requester, donor, and admin, moves the reviewed administrative permission set under the single admin role, and refuses to remove legacy role definitions while any assignments remain. The API permission guard and RBAC-03 policy have been updated to the three-role model.

Migration 024 has **not** been applied to production. The prior read-only snapshot reported zero role assignments, but that must be rechecked immediately before any migration. If assignments exist, migration 024 intentionally aborts; do not force it or delete assignment records. Keep RBAC in observe mode until non-production migration, assignment, and route verification pass.


## Fresh read-only Supabase role and migration verification — 2026-11-28

The connected LifeLink project was rechecked read-only after migration 024 was added to the branch:

- Project status: ACTIVE_HEALTHY.
- Live role registry still contains the seven historical definitions from migration 020: requester, donor, super_admin, security_admin, operations_admin, user_admin, and auditor.
- All seven role definitions currently have **zero user-role assignments**.
- Requester, donor, and super_admin currently have zero administrative permission mappings.
- The four historical administrative role definitions collectively map to exactly 13 distinct permission keys. This set matches the reviewed permission union now assigned to the single admin role by branch migration 024.
- The migration ledger reports 007_donor_map_chat_contact_sharing and 020_rbac_role_permission_registry. Migrations 021–024 are not recorded as applied in the live migration ledger.
- Migration 024 has **not** been applied. No production role definitions, role assignments, permission mappings, grants, or user records were changed by this verification.

The current zero-assignment result means migration 024's legacy-assignment guard is expected to pass if the database remains unchanged, but it must be rechecked immediately before any deployment. The migration is still not authorized for production application by this read-only verification. Keep RBAC in observe mode.


## 14. Superseding scanner-run guard, RBAC observe-mode disclosure, and exact-head validation — 2026-11-28

This section supersedes earlier head, CI, role-registry, and review-state snapshots where they differ. It does not authorize production rollout.

### Additional scanner lifecycle hardening

The successful RBAC scanner completion update now uses the same run-identity guard as the failure update. It writes success only while the persisted health row remains `status = 'running'` and `last_started_at` matches that scan's own start timestamp. This prevents a late/stale completion from overwriting a newer run's status if the advisory-lock transaction boundary is ever changed or interrupted. A regression assertion checks the success update's state and run-identity predicate.

The failure path retains its existing matching-run guard, sanitized `scan_failed` code, completion timestamps, and consecutive-failure increment. No scanner health table was created in production; migration 022 remains unapplied.

### Observe-mode authorization disclosure

`SECURITY.md` now explicitly documents the staged authorization boundary: `observe` preserves the allowlist, verified session, AAL2, and two-verified-TOTP gates but does not use the permission map as an access-denial gate. Thus, an allowlisted administrator may pass operational route checks in observe mode without an exact permission-map row. In `enforce`, only an active assignment to the single `admin` role with the exact route permission satisfies the RBAC check; legacy `super_admin` does not. The RBAC-manager allowlist is separate and protects role assignment, not ordinary operational route access.

### Fresh read-only Supabase snapshot

The connected LifeLink project `nphbhlxnujmylhubzmne` was queried read-only on 2026-11-28:

- Project state: `ACTIVE_HEALTHY`; PostgreSQL 17.6.1.166.
- Role definitions: 7 (the historical requester, donor, super_admin, security_admin, operations_admin, user_admin, and auditor keys).
- User-role assignments: 0.
- Permission definitions: 19; role-permission mappings: 19.
- Requester and donor have zero administrative permission mappings.
- The four historical administrative role mappings collectively contain the 13 distinct permissions intended for the single Admin role.
- The Supabase migration history lists `007_donor_map_chat_contact_sharing` and `020_rbac_role_permission_registry`; branch migrations 021–024 remain unapplied.
- The SQL inspection connection reported `current_user = postgres` and `session_user = postgres`. This identifies only the Supabase inspection connection and is not evidence of the Render API's runtime role.

No production rows, schema, grants, role definitions, role assignments, Auth settings, or deployments were changed.

### Exact-head CI

**Validated implementation head:** `9ea3770699c4ec26ab8454462f2c5bdd7ee7213d`.

All five required PR-triggered workflows passed on this exact head:

| Workflow | Run | Result |
|---|---:|---|
| Validate cloud package (backend, lint, Docker, Admin Console/CSP, Android quality gate) | [#46128334291](https://github.com/RamProjector/LifeLink/actions/runs/46128334291) | Passed |
| Android UI test APK (fake backend) | [#46128334236](https://github.com/RamProjector/LifeLink/actions/runs/46128334236) | Passed |
| Build Android APKs | [#46128334247](https://github.com/RamProjector/LifeLink/actions/runs/46128334247) | Passed |
| CodeQL (Python and Java/Kotlin) | [#46128334266](https://github.com/RamProjector/LifeLink/actions/runs/46128334266) | Passed |
| Security scan / Gitleaks | [#46128334293](https://github.com/RamProjector/LifeLink/actions/runs/46128334293) | Passed |

Backend/PostgreSQL suite: **354 passed**, one upstream Starlette/AnyIO deprecation warning, approximately **65% coverage**. Ruff, mypy, Android lint/Detekt/Spotless/unit tests/debug build, Admin website and CSP validation, Docker build, RLS decision validation, Gitleaks, and both CodeQL language analyses passed.

Artifacts generated from this exact head (available until 2026-12-12):
- Android APK archive: [artifact #15257156017](https://github.com/RamProjector/LifeLink/actions/runs/46128334247), SHA-256 `fa4463ecaaf51148c666aef5218f5ef82e27fbaaeee1137c881cfe61d888180f`.
- Android validation report: [artifact #15257116131](https://github.com/RamProjector/LifeLink/actions/runs/46128334247), SHA-256 `0f5f3d840dffeb36efeaa4e372dd4e0269853478f644d1c8bc170f528b9adaa0`.
- Fake-backend UI APK: [artifact #15256886684](https://github.com/RamProjector/LifeLink/actions/runs/46128334236), SHA-256 `2b9087df787d8b6c9c2bbd75f3f4ecdb5fc28b19d3ac38d1faf512c94abe0cfe`.

These are CI artifacts, not signed production releases or physical-device acceptance evidence.

### PR and release disposition

PR #227 remains open and unmerged. The exact head is on `security/ids-ips-consolidated-completion`, targeting `main`. All 15 inline review threads are resolved; the previously submitted CodeRabbit review remains `CHANGES_REQUESTED`, and a fresh review has not yet cleared that state. Do not merge solely because inline threads are resolved or CI is green.

The actual Render API database role remains unverified. Migration 024 is not applied. Keep RBAC in `observe`. Non-production migration/assignment/enforcement tests, MFA backup/recovery rehearsals, deployed scanner lifecycle verification, manual browser/Android acceptance, independent review, and explicit production deployment/merge approval remain release gates.

No production migration, grant, role assignment, Auth configuration change, deployment, or merge was performed in this verification.


## 15. Requester-scoped one-active-request enforcement and final code validation — 2026-11-28

This section supersedes earlier implementation-head and CI statements where they differ. It does not authorize production rollout.

### Requester lifecycle finding and implementation

The source audit found that the create endpoint previously serialized only identical idempotency keys. Two concurrent requests from the same requester using different keys could therefore create two active blood requests, contrary to the product rule.

The PostgreSQL API now:
- Retains the existing idempotency-key transaction lock for retries of the same intent.
- Acquires a second transaction-scoped advisory lock keyed by the authenticated request owner's ID, serializing distinct request intents for the same requester across API workers.
- Expires overdue active requests and closes their open matches/contacts while holding that lock.
- Rejects another non-expired active request with HTTP 409 and the `active_request_exists` code.
- Holds the requester lock until the new request and its creation audit event commit.

The lock is scoped to the validated `payload.requester_id`, so local development mode does not collapse different test users into one shared lock. No schema migration was required; this is an application-level transaction guard. Direct database inserts outside the API would not use this guard, so all supported request creation paths must continue to use this endpoint.

Regression coverage:
- `test_requester_cannot_create_two_active_requests_concurrently`: two concurrent create requests with distinct idempotency keys; asserts one 201, one 409, and exactly one active row.
- `test_overdue_active_request_is_expired_before_replacement`: verifies a due request is transitioned and its history retained before a replacement is accepted.
- Existing sweeper, donor opt-out, and privacy-flow tests were updated to use separate requesters or close the prior request when they intentionally need multiple request examples.

The broader requester lifecycle audit remains open for edit-pending-request and repost-inactive-request behavior; this implementation does not claim those features are complete.

### Exact code-head CI

**Validated code head:** `055bc65a1adcf357ac70072c66b57366a84a374c`.

All five required PR-triggered workflows passed on this exact code head:

| Workflow | Run | Result |
|---|---:|---|
| Validate cloud package (backend, lint, Docker, Admin Console/CSP, Android quality) | [#46129993364](https://github.com/RamProjector/LifeLink/actions/runs/46129993364) | Passed |
| Android UI test APK (fake backend) | [#46129993381](https://github.com/RamProjector/LifeLink/actions/runs/46129993381) | Passed |
| Build Android APKs | [#46129993371](https://github.com/RamProjector/LifeLink/actions/runs/46129993371) | Passed |
| CodeQL (Python and Java/Kotlin) | [#46129993367](https://github.com/RamProjector/LifeLink/actions/runs/46129993367) | Passed |
| Security scan / Gitleaks | [#46129993417](https://github.com/RamProjector/LifeLink/actions/runs/46129993417) | Passed |

Backend/PostgreSQL suite: **356 passed**, one upstream Starlette/AnyIO deprecation warning, approximately **65% coverage**. Ruff, mypy, Android lint/Detekt/Spotless/unit tests/debug build, Admin website/CSP validation, Docker build, RLS decision validation, Gitleaks, and both CodeQL language analyses passed.

Artifacts from this exact code head (available until 2026-12-12):
- Android APK archive: [artifact #15257407437](https://github.com/RamProjector/LifeLink/actions/runs/46129993371), SHA-256 `7a989f2ac72dc584c4355268b12f3f8d405c808ad84118699cb77a4f197f96a8`.
- Android validation report: [artifact #15256944974](https://github.com/RamProjector/LifeLink/actions/runs/46129993371), SHA-256 `85e808466fa9d90822710fdd8275ff008cf4ea1934faf7edb4b9510645c15761`.
- Fake-backend UI APK: [artifact #15256733938](https://github.com/RamProjector/LifeLink/actions/runs/46129993381), SHA-256 `fa08835a8f67c9add339b161052ee59665b704958a2228102720f9e3a06d5836`.

These are CI artifacts, not signed production releases or physical-device acceptance evidence.

### Current release disposition

PR #227 remains open and unmerged. The 15 previous inline review threads remain resolved, but the previously submitted CodeRabbit review state is still `CHANGES_REQUESTED`; the latest review command was triggered but has not produced a fresh completed review or approval. The actual Render API database role remains unverified. Migrations 021–024 remain unapplied; there are still no user-role assignments in the latest read-only Supabase snapshot. Keep RBAC in `observe`.

The current code head is fully CI-validated, but release gates remain: non-production migration and RBAC assignment/enforcement rehearsal; MFA backup/recovery rehearsal; deployed scanner lifecycle verification; manual browser/Android acceptance; independent review; and explicit production deployment/merge approval. Editing pending requests and reposting inactive requests are separate unimplemented lifecycle requirements.

No production migration, database grant, role assignment, Auth configuration change, deployment, or merge was performed in this verification. The subsequent documentation-only commits do not change this validated application code; their own required CI must still pass before the PR head is considered fully green.


## 16. Superseding live read-only database snapshot and review-boundary reconciliation — 2026-11-28

This section records a fresh read-only check of the connected LifeLink Supabase project and a source review of the current PR branch. It supersedes older live counts and migration snapshots where they differ. No production writes, DDL, grants, role changes, Auth changes, or deployment were performed.

### Supabase live state (read-only)

- Project `nphbhlxnujmylhubzmne` is `ACTIVE_HEALTHY`, PostgreSQL `17.6.1.166`, region `ap-southeast-1`.
- The migration ledger currently lists only `007_donor_map_chat_contact_sharing` and `020_rbac_role_permission_registry`. Branch migrations 021–024 remain unapplied.
- Current counts: 7 historical role definitions, 19 permission definitions, 19 role-permission mappings, 0 user-role assignments, 0 security alerts, 26 session-registry rows, and 394 audit-event rows.
- `lifelink_security_scanner_runs` does not exist in the live schema because migration 022 has not been applied.
- A privilege check for the RBAC, audit, session, and security-alert tables returned no SELECT privilege for `anon` or `authenticated`; `service_role` has SELECT. The 18 Supabase Advisor `rls_enabled_no_policy` findings therefore remain an intentional backend-only policy pattern for the inspected tables, but the grants must be rechecked after every schema change.
- Supabase Advisor continues to report leaked-password protection disabled. This is an Auth configuration/plan setting, not something to silently alter through a database migration.
- The SQL inspection connection reports `current_user = postgres` and `session_user = postgres`. This describes the inspection connection only and does not establish the Render API's effective runtime database role.

### Source review reconciliation

The current branch source was checked against previously reported review findings:

- Browser notification polling starts with a null cursor; the first API request omits `after`, and `/security/notifications` obtains its cursor from PostgreSQL `clock_timestamp()`. The browser no longer seeds the cursor from `Date.now()`.
- The notification interval is installed before awaiting the initial poll, with generation checks retained to prevent stale polls from mutating a stopped session.
- Scanner health exposes database-calculated `running_age_seconds` and `success_age_seconds`; the Admin Console uses these ages rather than comparing database timestamps to the browser clock.
- Scanner failure persistence is guarded by `status = 'running'` and the same `last_started_at` returned by PostgreSQL for that run, so a late failure cannot overwrite a newer run.
- New-database setup examples use the complete ordered `sql/*.sql` migration glob; existing-database instructions remain separate.
- The `users.read` directory response intentionally includes only the effective role-key and permission summary; full role registry and per-user assignment detail remain under `roles.read`. The approved distinction is now explicitly documented in `docs/RBAC_IMPLEMENTATION.md`.

### Release disposition

Keep `LIFELINK_RBAC_ENFORCEMENT_MODE=observe`. Do not apply migrations 021–024, change grants, provision roles, alter Auth configuration, or deploy until the actual Render API runtime database role is identified and least-privilege access is verified; a non-production migration/assignment/enforcement rehearsal is completed; MFA backup and two-operator recovery are rehearsed; scanner health and notification delivery are observed after non-production deployment; and manual requester, donor, and admin acceptance plus independent review pass.

The current Supabase project is the production project. No non-production Supabase branch was created because branch creation may incur cost and requires explicit cost/workspace approval. No production migration or deployment is authorized by this verification.


## 17. Superseding deployment, live-schema reconciliation, and latest validation snapshot — 2026-11-28

This section supersedes earlier statements that treated every migration from 021 through 024 as absent. The current production schema was checked again read-only; the migration ledger and actual schema do not fully agree. No DDL, grants, role assignments, Auth changes, deployment, or production data mutation was performed.

### Exact Render deployment configuration

- API service `LifeLink-API` (`srv-dalugdbl550s73chtsfg`) deploys `RamProjector/LifeLink` branch `main`, root directory `lifelink_fastapi/`, on the Free plan in Singapore. Health-check path is `/health`. PR previews are disabled.
- Admin Console static site (`srv-dbf3uaa9lscs738nfsu0`) also deploys branch `main`; PR previews are disabled.
- Latest live API and Admin Console deployments are both commit `3205ed4eb9eba307eeb94ab7aa8d420d83376e0a` (PR #226 merge). PR #227's feature branch is not deployed and has no Render preview.
- The workspace contains no Render-managed PostgreSQL instance. The API database is the connected Supabase project. Render configuration inspection did not expose environment-variable values; secrets were not read or changed.

### Supabase migration-ledger versus actual schema

The Supabase migration ledger reports only `007_donor_map_chat_contact_sharing` and `020_rbac_role_permission_registry`. Read-only catalog inspection found:

- `lifelink_user_sessions` exists (the schema object from migration 018), although 018 is absent from the migration ledger.
- `lifelink_security_alerts` exists (the schema object from migration 019), although 019 is absent from the migration ledger.
- `lifelink_role_security_findings` exists with RLS enabled and the expected active-finding/status indexes (migration 021's schema objects), although 021 is absent from the migration ledger.
- `lifelink_user_roles.assigned_by` exists, but index `ix_lifelink_user_roles_assigned_by` from migration 023 is absent. Migration 023 is therefore not fully reflected in the live schema.
- `lifelink_security_scanner_runs` is absent; migration 022 is not reflected in the live schema.
- The role registry still has seven role definitions and zero user-role assignments; migration 024's exact-three-role normalization has not been applied.
- The ledger/schema discrepancy means migrations 018, 019, and 021 must be reconciled against the migration files and recorded history before any automated migration replay. Do not blindly replay or mark versions applied based only on table existence.

### Live runtime and log evidence

- The direct Supabase SQL inspection connection reports `current_user = postgres`; that is the inspection connection, not proof of the Render API's runtime role.
- Supavisor logs contain connections under user `postgres`, but their private peer address and timing do not uniquely attribute those sessions to the Render API. The actual API runtime database identity remains **not verified**.
- Render app logs show no warning/error entries in the inspected 24-hour window. The configured `/health` path is a liveness check; it does not prove scanner health or full database readiness.
- Historical logs show `GET /v1/admin/security/alerts` failed on 2026-11-25 23:10 UTC because `lifelink_user_roles` did not yet exist; migration 020 was recorded at 23:41 UTC that day. Logs on 2026-11-26 08:14 and 09:22 UTC show an ambiguous PostgreSQL bind-parameter error on the same route. Current source explicitly casts nullable filters, and the PostgreSQL end-to-end correlation-filter test exercises a null status with a non-null correlation ID. No recent live request was observed to independently confirm the historical error is resolved in the deployed build.

### Exact source/CI evidence

- Commit `673eb138e75dbf400d78bd57b785ffb3e93cccb8` is the latest fully completed workflow snapshot containing the database-clock scanner-start implementation. Its workflows completed successfully: [Security scan](https://github.com/RamProjector/LifeLink/actions/runs/46146106038), [Android UI test APK](https://github.com/RamProjector/LifeLink/actions/runs/46146106072), [Android APK build](https://github.com/RamProjector/LifeLink/actions/runs/46146106007), [cloud package validation](https://github.com/RamProjector/LifeLink/actions/runs/46146106089), and [CodeQL](https://github.com/RamProjector/LifeLink/actions/runs/46146106049).
- Later commits corrected the local PostgreSQL README bootstrap and strengthened the scanner test to assert the exact timestamp returned by the database clock. On current head `080250f31e416cb18f46efa9f159407b0cd79325`, backend tests, backend lint, Dockerfile validation, secret scanning, and Python analysis passed; Android quality, APK validation, fake-backend UI APK, and Java/Kotlin analysis were still in progress at this snapshot. CodeRabbit's status was neutral/skipped, not an approval.
- The local PostgreSQL README now creates a clearly labelled local-only minimal `auth.users` compatibility stub before the complete migration glob; it must never be run against hosted Supabase.

### Corrected release disposition

Keep `LIFELINK_RBAC_ENFORCEMENT_MODE=observe`. Before release, reconcile the migration ledger with existing schema objects; verify/apply 022 and 023 only in a rehearsed non-production environment; rehearse 024 against a snapshot of the current seven-role/zero-assignment registry; verify the Render runtime database identity and least privilege; verify the notification delivery provider and durable receipts; and complete MFA recovery, scanner runtime, browser/Android acceptance, and independent review. The current Render services have no PR preview, and no non-production Supabase branch exists. No production change is authorized by this snapshot.
