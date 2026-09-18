# LifeLink Product Implementation Plan

## Product direction

LifeLink is a direct requester-to-donor discovery and contact aid. A coordinator is not required for the primary flow. Requesters create blood requests, provide a current or approximate location, and receive nearby eligible matches. Donors maintain their own profile and availability, review request summaries, and accept or decline contact requests.

## Account roles

Every authenticated account selects one primary role during onboarding: **requester** or **donor**. Requesters can create and track requests. Donors can edit their donor profile, set availability, and respond to nearby requests. A future release may support both roles on one account; the MVP keeps role selection explicit to avoid confusing workflows.

## Privacy boundaries

The app uses GPS to calculate matching distance, but it does not show exact donor or requester coordinates to the other party. Donor names, email addresses, phone numbers, and exact locations remain private until a donor accepts a contact request and the relevant contact-sharing policy allows disclosure. Live donor tracking is intentionally deferred; any future location sharing must be temporary, explicit, and revocable.

## Implemented in the current slice

The Android client now includes role onboarding, role-aware requester/donor home copy, donor-only dashboard entry, manual approximate latitude/longitude fallback for requesters who cannot use GPS, and a Supabase migration for role profiles and controlled contact-request records. Existing authentication, GPS capture, donor availability, matching, and contact-selection behavior are preserved.

## Next implementation phases

| Phase | Scope | Exit criteria |
|---|---|---|
| 1 | Role-backed profiles | Role is persisted server-side, donor profile uses authenticated user ID, requester profile is editable, and role changes are controlled. |
| 2 | Matching results | Request submission opens a dedicated results view with distance, travel estimate, blood eligibility, availability, and privacy-safe donor cards. |
| 3 | Contact workflow | Donor inbox supports accept/decline; requester sees pending/accepted states; contact information is revealed only after acceptance. |
| 4 | Request lifecycle | Requester can cancel/close requests; deadlines expire automatically; donor responses update status consistently. |
| 5 | Safety and production | Password recovery, resend-confirmation flow, abuse reporting, manual donor verification, signed release builds, and monitoring. |

## Explicitly deferred

Facility discovery, hospital routing, coordinator-required workflows, public donor names, exact location sharing, and continuous live donor tracking are not part of the MVP.

## Operational prerequisites

Apply `lifelink_fastapi/sql/003_roles_profiles_contacts.sql` after the existing migrations in Supabase. The Android build must continue using only the Supabase URL and publishable key. JWT secrets, service-role keys, database passwords, and SMTP credentials remain server-side.
