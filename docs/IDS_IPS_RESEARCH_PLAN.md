# LifeLink IDS/IPS Research and Implementation Plan

**Plan status:** Active; implementation and release verification are in progress, not complete.  
**Project:** LifeLink — `RamProjector/LifeLink`  
**Implementation PR:** [#227](https://github.com/RamProjector/LifeLink/pull/227)  
**Implementation branch:** `security/ids-ips-consolidated-completion`  
**Related evidence:** [IDS/IPS completion audit](IDS_IPS_COMPLETION_AUDIT_2026-11-27.md), [release-gate verification](IDS_IPS_RELEASE_GATE_2026-11-28.md), [RBAC implementation](RBAC_IMPLEMENTATION.md), [Admin Console security model](ADMIN_CONSOLE.md).

This document is the canonical execution plan. The completion audit contains the detailed source inventory and historical findings; the release-gate document records time-stamped live observations. A source change, passing CI job, or database inspection must not be described as a deployed or manually accepted feature.

## 1. Product boundary

LifeLink has exactly three product roles:

- **Requester** — creates and manages their own blood requests and sees privacy-safe eligible donor matches.
- **Blood Donor** — manages their donor profile and availability and responds to eligible requests.
- **Admin** — uses the web-only Admin Console for user administration, security monitoring, audit, and reporting.

Do not add admin subroles or TACResQ/emergency-dispatch roles. Android supports requester and donor workflows only; Admin operations remain web-only. The API and database are the authorization boundary. Client role labels, local storage, editable profile metadata, and caller-supplied IDs never grant access.

## 2. Research basis and design principles

The plan consolidates the repository's IDS/IPS completion audit and uses these security principles:

- OWASP API Security Top 10 (2023): object-level and function-level authorization, property-level authorization, authentication, resource consumption, and security misconfiguration.
- Supabase PostgreSQL Row Level Security and least-privilege grants: RLS and SQL privileges are separate controls; backend-only tables must not be directly readable by `anon` or `authenticated`.
- NIST Cybersecurity Framework 2.0: Govern, Identify, Protect, Detect, Respond, and Recover.
- Android architecture and lifecycle guidance: authenticated state must be account-scoped, server-authorized, recoverable after process recreation, and observable in tests.
- Data minimization: never log passwords, bearer/refresh tokens, OTPs, message bodies, contact values, blood type, precise GPS coordinates, or raw request payloads.

References:
- https://owasp.org/API-Security/editions/2023/en/0x11-t10/
- https://supabase.com/docs/guides/database/postgres/row-level-security
- https://www.nist.gov/cyberframework
- https://developer.android.com/topic/architecture

## 3. Security objectives

1. **Identify:** reliably identify authenticated users, the three product roles, effective permissions, and observed application sessions.
2. **Protect:** enforce server-side identity, session, ownership, role applicability, and permission checks; deny privilege escalation and cross-account access.
3. **Record:** capture security-relevant ADD, EDIT, DELETE, authentication, authorization, session, and sensitive-disclosure events with stable correlation.
4. **Detect:** identify repeated unauthorized access, invalid/revoked session reuse, missing assurance, role/permission drift, and other reviewed suspicious activity.
5. **Respond:** persist deduplicated alerts, support acknowledge/investigate/resolve/false-positive lifecycle, and deliver actionable notifications through a verified channel.
6. **Recover:** preserve auditable history, support safe account/session recovery, and verify backups, migrations, and rollback implications before release.

## 4. Reference architecture and event flow

1. Android requester/donor clients and the Admin Web Console call the same authenticated FastAPI service.
2. FastAPI validates the Supabase JWT and the current session. Admin routes additionally require the server-managed Admin allowlist, verified identity, AAL2, and the required verified TOTP factors.
3. Route-specific authorization checks ownership, participation, role applicability, and—when enforcement is enabled—the exact active Admin permission.
4. Critical mutations write their audit event in the same database transaction as the mutation.
5. IDS rules and the independent RBAC discrepancy scanner evaluate relevant audit/session/role state and persist deduplicated security alerts or role-policy findings.
6. The Admin Console reads alerts, audit history, session inventory, role integrity, and scanner health through protected endpoints.
7. An administrator notification provider must persist per-recipient delivery attempts, retry outcomes, and final status. An alert row or foreground browser toast alone is not proof of delivery.

The Mermaid flowchart in the completion audit is the detailed visual reference. Dashed notification stages represent required behavior until actual provider delivery is verified.

## 5. Required audit contract

The security audit envelope must retain the required fields:

- `session_id`
- `log_id`
- `action` / normalized `actions`
- `description`
- `ip_address`
- `timestamp`
- `device_used`

New writes use coarse device/OS labels rather than raw User-Agent strings. Historical rows may retain their original `device_used` values and are not silently represented as scrubbed. IP attribution must respect the trusted-proxy boundary; do not trust arbitrary forwarded headers. Audit records are append-only from application routes. Sensitive metadata is redacted and critical mutation/audit writes remain atomic.

## 6. Detection and prevention scope

The reviewed implementation includes narrow IDS rules for unauthorized Admin access, missing Admin session context, revoked-session reuse, AAL2 deficiencies, insufficient verified TOTP factors, and denied per-route RBAC permissions. RBAC scanning separately checks active assignment applicability and the exact reviewed role-permission policy.

The three-role registry must contain only `requester`, `donor`, and `admin`. Requester and donor receive no administrative permission grants. Admin permissions are granular and route-specific. Legacy administrator subroles must not be treated as additional product roles.

Prevention must be server-side and fail closed when required identity, session, policy registry, or permission data is unavailable. The staged `LIFELINK_RBAC_ENFORCEMENT_MODE=observe` setting is not full permission enforcement: it preserves the allowlist/session/MFA gates but does not deny an Admin route solely because its exact permission mapping is absent. Do not switch to `enforce` until migration, approved role provisioning, and route acceptance have passed in non-production.

## 7. Work packages and acceptance criteria

| Work package | Required result | Evidence required | Current disposition |
|---|---|---|---|
| A. Three-role authorization | Exactly requester, donor, admin; no client-side privilege escalation; exact route permissions | Migration tests, API route matrix, expired-assignment tests, non-production enforcement rehearsal | Code implemented; production migration and assignments not deployed |
| B. Sessions and MFA | Session registration/revocation, AAL2 and two verified TOTP factors for Admin, safe recovery | Auth/session integration tests and two-operator recovery rehearsal | Code exists; live recovery rehearsal outstanding |
| C. Audit coverage | Seven-field envelope; atomic, privacy-safe events for all applicable mutations and sensitive operations | Route-to-client inventory, coverage tests, multi-user isolation | Partial; legacy-route and live client trace remain |
| D. IDS/IPS detection | Reviewed IDS rules and periodic RBAC discrepancy detection; no overlapping scans | Rule tests, concurrency tests, real ASGI lifespan start/stop and retry tests | Code and CI coverage present; deployed runtime not verified |
| E. Scanner health | Persisted run start/finish/success/failure, duration, finding count, consecutive failures, sanitized error | Database integration tests and deployed non-production observation | Code/migration present; migration 022 not deployed |
| F. Admin monitoring | Search/filter/paginate audit, alerts, sessions, users, role integrity; accurate scanner freshness | Admin API tests, CSP/static checks, browser acceptance | Code and CI checks present; manual browser acceptance outstanding |
| G. Notifications | Admin recipient resolution, generic privacy-safe payload, durable per-recipient delivery state, deduplication, bounded retry/backoff, failure visibility | Provider-config verification, induced alert, actual receipt, retry and no-recipient tests | Foreground browser notifications exist; background provider delivery and receipts incomplete |
| H. Android integration | Requester/donor actions use the same backend; Admin screens and Admin API removed from Android | Retrofit-to-route trace, Android tests/build, physical-device acceptance | Admin removal and CI checks present; full manual acceptance outstanding |
| I. Operational readiness | Runtime DB identity, least privilege, migration history, backups, rollback, deployment evidence | Read-only host/config inspection and non-production rehearsal | Render runtime identity and backup/recovery evidence outstanding |

## 8. Current verified environment baseline

The latest read-only check of the connected Supabase project found:

- Project `nphbhlxnujmylhubzmne`: `ACTIVE_HEALTHY`, PostgreSQL 17.6.1.166.
- Migration history records only `007_donor_map_chat_contact_sharing` and `020_rbac_role_permission_registry`, but the actual schema is ahead of that ledger in places.
- Live catalog checks found `lifelink_user_sessions` (018), `lifelink_security_alerts` (019), and `lifelink_role_security_findings` with its RLS/indexes (021), although those versions are not recorded in the ledger. Reconcile before replaying or repairing migration history.
- `lifelink_user_roles.assigned_by` exists, but the 023 index `ix_lifelink_user_roles_assigned_by` is absent. The 022 scanner-health table is absent.
- The registry has seven role definitions, 19 permission definitions, 19 role-permission mappings, and zero user-role assignments; 024's exact-three-role normalization has not been applied.
- Inspected audit/RBAC/session/security tables have no direct SELECT grant for `anon` or `authenticated`; `service_role` has SELECT.
- Supabase Advisor reports 18 RLS-enabled/no-policy tables and leaked-password protection disabled. The no-policy tables must remain backend-only with client-role grants rechecked; the Auth setting requires a separate approved configuration decision.
- The SQL inspection connection uses `postgres`; this does not establish the Render API runtime role.

These are point-in-time observations, not permission to mutate production. See release-gate section 17 for the full schema/ledger reconciliation, Render deployment configuration, and CI SHA evidence.

## 9. Safe implementation and release sequence

1. Inspect the latest PR head, base diff, review comments, migration order, and exact workflow results before each new change.
2. Fix verified source defects and add regression tests; do not suppress or weaken tests.
3. Run backend/PostgreSQL integration, lint/type checks, Android quality/build/UI checks, Admin Console/CSP checks, RLS/grant checks, Gitleaks, and CodeQL on the exact resulting commit.
4. Obtain independent review and resolve all valid findings. Resolved inline threads do not automatically clear a historical `CHANGES_REQUESTED` review.
5. Select/confirm the intended Render workspace before reading Render resources. Identify the actual API runtime DB role through a safe non-secret method; verify least-privilege access to required Auth tables and application tables.
6. Use an approved non-production Supabase environment for migrations 021–024, assignment provisioning, route enforcement, scanner lifecycle, and MFA recovery rehearsals. Do not create a billable branch without cost approval.
7. Verify the notification provider is configured and the Admin recipient has a supported registered endpoint. Do not invent credentials, add a paid provider, or claim delivery from an attempted send.
8. Perform manual browser and Android acceptance with controlled test accounts; record expected/actual results without exposing personal data.
9. Only after all gates pass, obtain explicit production rollout approval. Production deployment is separate from code commit and CI validation.

## 10. Production safety constraints

- Never apply migrations 021–024 to the production project as a substitute for non-production rehearsal.
- Never delete legacy assignments to force migration 024 through; stop and prepare a reviewed mapping if assignments exist.
- Never grant broad Auth-schema privileges or use a superuser as a guessed runtime identity.
- Never switch RBAC to enforce while intended Admin assignments are absent or unverified.
- Never expose service-role keys, database passwords, Firebase service-account JSON, OTP secrets, recovery codes, or session tokens.
- Never fabricate notification receipts, test results, deployment state, or reviewer approval.
- Do not merge PR #227 while required checks are pending/failing or independent review remains in a blocking state.
- Do not trigger production deployment, database migration, Auth setting changes, role assignment, or grant changes without the relevant explicit approval.

## 11. Completion definition

The IDS/IPS work is complete only when every work package has evidence at the correct level: source implemented, automated tests passed, non-production integration verified, live runtime verified, and manual browser/device acceptance completed where applicable. The final report must identify the exact commit SHA, exact CI results, deployed migration versions, effective runtime role, role-assignment state, scanner run evidence, notification delivery receipts, recovery rehearsal, and independent review outcome. Anything lacking evidence remains explicitly **not verified** or **blocked**.
