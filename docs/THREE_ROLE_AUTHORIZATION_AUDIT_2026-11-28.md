# LifeLink three-role authorization audit — 2026-11-28

## Required product roles

LifeLink has exactly three product roles:

1. **Requester** — creates and manages blood requests and controls which donors may contact them.
2. **Blood Donor** — maintains donor information and availability, discovers eligible requests, and explicitly accepts or declines contact.
3. **Admin** — operates the web-only administrative console and its protected user, audit, security, and configuration functions.

The Android application supports Requester and Blood Donor. Admin is web-only. This is a product-role boundary, not a requirement to create additional administrator personas.

## Finding: current RBAC registry conflicts with the three-role requirement

The current PR #227 branch migration 020 seeds seven role keys: requester, donor, super_admin, security_admin, operations_admin, user_admin, and auditor. The current policy scanner and RBAC documentation also explicitly validate those seven definitions. This conflicts with the required three-role product model.

The four administrative permission groupings may remain as **permissions/capabilities** within the single admin role, but they must not be modeled as additional assignable product roles. super_admin is also not a fourth product role; exceptional break-glass authority, if still required, must be represented as a separate server-side authorization condition and audited, not as a user-selectable role.

## Required target model

| Product role | Assignment source | Authorization boundary |
|---|---|---|
| requester | Server-validated account capability/profile | Own requests; explicitly authorized contact/conversation resources |
| blood_donor | Server-validated donor profile and eligibility state | Own donor profile/availability; eligible requests; accepted contact relationships |
| admin | Server-side allowlist plus verified session and MFA; optional explicit admin permission grants | Admin API only; no client-supplied role or profile metadata can grant it |

The persisted key currently named donor must be normalized to one canonical product key, blood_donor, only after reviewing every Android, API, migration, seed, test, and live-data reference. If backwards compatibility requires keeping donor as a wire alias, it must not create a fourth role or a second independently assignable donor identity.

Admin functional divisions such as user management, security monitoring, audit review, and operational reporting belong in permission keys (for example, users.read, security.alerts.read, and audit.read) attached to the single admin role. The product role registry must contain only the three approved keys.

## Migration and compatibility requirements

Before changing migration 020 or adding a follow-up migration:

- Inspect all role-key references in Android, FastAPI, web admin, SQL migrations, fixtures, tests, and documentation.
- Inspect the live lifelink_roles and lifelink_user_roles rows read-only. The prior 2026-11-28 inspection reported seven role definitions and zero user-role assignments; re-verify before relying on that snapshot.
- Design a forward-only migration that preserves permission mappings by consolidating administrative permission grants under admin, maps existing donor role references to the canonical donor role, and refuses to remove/retire any legacy role that still has active assignments not safely migrated.
- Keep the migration idempotent, transaction-safe, RLS-safe, and reversible where practical. Do not delete user accounts, profiles, requests, donor data, audit events, or security findings.
- Update RBAC-01/RBAC-02/RBAC-03 expectations so they validate exactly three product roles and detect unapproved extra role definitions.
- Keep admin permission categories as permissions, not additional role keys. Preserve least privilege through route-specific permissions.
- Keep requester/donor resource ownership, consent, contact lifecycle, and exact-location privacy checks independent of administrative RBAC.

## Acceptance tests

- The role registry contains exactly requester, blood_donor, and admin after the approved migration path.
- Signup and profile updates cannot create or elevate admin.
- A requester cannot invoke donor-only operations unless the product explicitly permits dual capability; a donor cannot manage another donor's profile or respond to their own request.
- Admin APIs reject requester/donor accounts even if they tamper with local storage, profile metadata, or request payloads.
- The single admin role can be constrained by permission keys without creating separate admin role types.
- Unknown and retired role keys fail closed; migration tests cover existing legacy role assignments and duplicate assignments.
- Two-account tests cover ownership, contact consent, chat participation, and location-sharing expiry/revocation.
- Android exposes only requester/donor flows; Admin remains in the web console.

## Release decision

**Do not mark the full LifeLink feature set complete or merge PR #227 until the three-role mismatch is resolved.** The current branch's IDS/IPS and admin work may be retained, but its seven-role registry is not the approved product model. This audit is a repository finding, not evidence that the live database has been changed.
