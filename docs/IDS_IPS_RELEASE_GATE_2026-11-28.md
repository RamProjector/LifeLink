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

The current PR branch now changes all three active-role inventory/detail reads in `lifelink_fastapi/app/admin_api.py` from transaction-start `now()` to statement-start `statement_timestamp()`. This keeps displayed active role assignments aligned with the authorization guard's expiry semantics when the request transaction has already been open during authentication/MFA checks. The corresponding inventory regression assertion was updated in `lifelink_fastapi/tests/test_admin_api.py`.

CI for the resulting commit must pass before this code change is treated as validated. This is additive to the existing query-time expiry guard and does not alter role assignments or enforcement mode.

### Security Advisor interpretation

The latest Supabase security advisor still reports 18 RLS-enabled/no-policy tables and leaked-password protection disabled. The no-policy tables remain backend-only by design; a separate grant inspection must continue to confirm no client-role access before rollout. Leaked-password protection remains a plan-level limitation on the connected Free organization and is not addressed by a schema change or an unapproved paid-plan upgrade.

### Release disposition

Keep `LIFELINK_RBAC_ENFORCEMENT_MODE=observe`. Do not apply migrations 021–023, grant Auth-schema privileges, provision role assignments, enable enforce mode, or deploy PR #227 until the Render runtime role is positively identified, non-production MFA and two-operator recovery rehearsals pass, scanner health is verified after deployment to non-production, browser/Android acceptance is recorded, independent review is complete, and production rollout is explicitly approved.
