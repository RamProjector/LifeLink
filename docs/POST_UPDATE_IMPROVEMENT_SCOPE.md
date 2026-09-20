# LifeLink Post-Update Improvement Scope

**Status date:** 20 September 2026  
**Baseline commit:** `82905f1`

## Scope decision

The core MVP is functional. A requester can submit a blood request, review eligible matches in a dedicated results state, select donors, and send contact requests. A donor can see the request in the donor inbox and accept or decline it. The requester can observe the response state, and contact email is returned only for an accepted contact request.

The next work should improve reliability, completeness, and user confidence without adding unnecessary coordination or privacy complexity. The map remains a privacy-safe matching aid: it does not expose individual donor coordinates or enable exact live donor tracking.

## Completed baseline

The backend persists donor contact requests, updates them when donors accept or decline, and returns accepted contact details only after authorization. The Android client provides donor results, donor selection, pending/accepted/declined states, contact-status refresh, native MapLibre maps, GPS and manual-location fallbacks, and compact actionable feedback for retry and broadcast actions.

## Recommended next implementation slice

The highest-value next release should combine:

1. **Request history**, so the Requests tab includes active, completed, cancelled, and expired requests together with previous donor responses.
2. **A complete accepted-donor contact screen**, including clear accepted-donor cards, a consent-based Contact donor action, accepted timestamps, Contact shared status, Meeting arranged status, and Fulfilled or Cancelled actions.
3. **Map reliability controls**, including loading and error states, tile retry, an in-map recenter button, accuracy/source feedback, and confirmation when a pin moves.

This slice makes the existing MVP feel substantially more complete while preserving the current privacy boundary.

## Priority 1: Request history

Add a request-history model and UI that distinguishes active, completed, cancelled, and expired requests. Each historical request should retain its donor responses and contact statuses, subject to the existing ownership checks. The active request remains the primary action surface; history should not require a coordinator role.

The API should define stable status and ordering semantics, and the Android client should provide an empty state, loading state, retry behavior, and a clear way to reopen a historical request without accidentally treating it as active.

## Priority 2: Complete accepted-donor workflow

Extend the accepted state with a dedicated donor card and an explicit **Contact donor** action. Contact information must remain consent-based and must be revealed only after the donor has accepted and the server authorizes disclosure.

Track accepted timestamp, contact-shared status, meeting-arranged status, fulfilled status, and cancelled status. Provide explicit Fulfilled and Cancelled actions, prevent new disclosures after cancellation or expiry, and keep exact live donor tracking disabled.

Avoid storing unnecessary medical information. If phone or another contact method is added, require explicit donor consent and record the disclosure event for auditability.

## Priority 3: Reliable map feedback

Add a loading indicator while the map style or tiles initialize, an actionable error state with retry, and a recenter button directly on the map. Show current-location accuracy where available and distinguish **Using current location** from **Pin selected manually** or another approximate source.

Confirm when a pin moves and preserve the current privacy rules: requester coordinates are used for matching, while donor coordinates and individual donor pins are never returned to requesters. Map failures must leave GPS and manual-coordinate fallbacks usable.

## Priority 4: Donor profile completeness

Add editable display name, optional donor note, preferred contact method, last donation or availability update, temporary pause reason, and profile visibility status. Keep the profile operational rather than clinical and avoid collecting unnecessary medical information.

The donor should be able to review what requesters can see, and visibility or pause changes should affect matching consistently and be reflected in the donor inbox.

## Priority 5: Authentication completeness

Add password reset, confirmation-email resend, clear account-already-exists handling, session-expiration recovery, an explicit sign-out action in Profile, and an account-deletion flow if required for release. All flows must preserve server-side ownership checks and avoid exposing account or contact data across users.

## Priority 6: Production safety and verification

Before real-world usage, verify migrations `002` and `003` on the live Supabase database, run a requester-to-donor workflow with two real accounts, test on physical Android devices, and confirm that accepted contact information is disclosed only within the intended privacy boundary.

Add rate limiting, abuse prevention, report/block functionality, audit logs for contact sharing, and a signed production APK. These safeguards should be implemented before broad public use, not treated as optional polish.

## Lower-priority future features

The following can wait until the core completeness slice is stable:

- Temporary consent-based donor location sharing.
- Expiring location links.
- Hospital or NGO verification.
- An admin dashboard.
- Push notifications beyond the current foundation.
- Multi-role accounts.
- Live donor tracking.

## Acceptance criteria for the next release

A requester must be able to submit a request, see it in active history, contact a selected donor, observe pending and accepted or declined states, and later mark the request fulfilled or cancelled. Historical requests must remain discoverable with their donor responses.

An accepted-donor screen must show the accepted timestamp and a consent-based Contact donor action. Contact details must be disclosed only after acceptance and authorization, and no disclosure may be created after cancellation or expiry.

The map must expose loading, retry, and recenter behavior, identify whether the location came from current GPS or manual pin selection, and retain usable manual fallbacks after a map failure. Requester-facing responses must contain no donor coordinates or individual donor pins.

## References

[1]: ./PRODUCT_IMPLEMENTATION_PLAN.md "LifeLink Product Implementation Plan"
[2]: ./IMPLEMENTATION_STATUS.md "LifeLink Implementation Status"
[3]: ./CHANGELOG_CLOUD.md "LifeLink Cloud Changelog"

**Author:** Manus AI
