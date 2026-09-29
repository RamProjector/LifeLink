# Donor Map, Matched-Requester Location, Chat, and Contact Sharing

**Status date:** 29 September 2026
**Scope:** the confirmed "Active matched requesters only" privacy direction.

## Goal

Let requesters discover available donors and coordinate with a matched donor
without exposing anyone's exact location or contact details to the wider user
base. Every disclosure is explicit, bounded in time, and revocable.

## Privacy model

| Concern | Default | Who can see it | When it ends |
| --- | --- | --- | --- |
| Donor on the map | Hidden (opt-in) | Any authenticated user, as an approximate area only | Donor hides, or goes unavailable |
| Donor exact location | Not shared | The requester of the matched request only | Donor revokes, request ends, or the share window expires |
| Conversation | Does not exist | The requester and donor of one request | Never deleted by the app; access stays scoped |
| Phone / email | Hidden | The two conversation participants, after an explicit share | Not automatic; each share is a deliberate act |

The map never returns donor identity or exact coordinates. Exact coordinates are
returned only by `GET /v1/emergency-requests/{request_id}/donors/{donor_id}/location`,
and only while an active, unexpired `donor_location_shares` row exists for that
exact (request, donor) pair.

## Backend

* **Migration** `sql/007_donor_map_chat_contact_sharing.sql` — adds
  `donors.map_visible`, `donors.map_visibility_updated_at`,
  `donors.exact_location_sharing_enabled`, and the `donor_location_shares`,
  `conversations`, `messages`, and `contact_shares` tables.
* **Models** — `app/db_models.py` gains `DonorLocationShare`, `Conversation`,
  `Message`, and `ContactShare`.
* **Policy** — `app/privacy_repositories.py` is the single place that decides
  whether exact coordinates may be disclosed. Every read re-checks the live
  share, so a stale client cannot keep showing a pin after revocation.
* **API** — `app/privacy_api.py` defines the request/response contract;
  `app/main_postgres.py` wires the endpoints.
* **Expiry** — cancelling or fulfilling a request expires every live share for
  it (`expire_shares_for_request`). A background sweeper
  (`expire_stale_shares`, started in the app lifespan) also closes shares whose
  window or request deadline has passed, so a request that simply times out
  never leaves a readable pin behind.
* **Freshness** — the map hides donors whose location snapshot is older than
  `LIFELINK_DONOR_LOCATION_MAX_AGE_MINUTES`, and exact location is withheld once
  the donor's snapshot goes stale, so an old coordinate is never disclosed.
* **Validation** — contact shares are shape-checked (email/phone) and blank
  messages are rejected, so a typo cannot be broadcast as a "shared" detail.

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/v1/donor-map` | Approximate donor areas + freshness |
| PUT | `/v1/donors/{donor_id}/map-visibility` | Donor opt-in / hide / exact-sharing toggle |
| POST | `/v1/emergency-requests/{request_id}/donors/{donor_id}/location-share` | Activate exact-location sharing (requester, matched donor only) |
| DELETE | `/v1/emergency-requests/{request_id}/donors/{donor_id}/location-share` | Donor revokes sharing |
| GET | `/v1/emergency-requests/{request_id}/donors/{donor_id}/location` | Exact location, only while a live share exists |
| POST | `/v1/emergency-requests/{request_id}/donors/{donor_id}/conversation` | Open the scoped conversation |
| GET | `/v1/conversations` | Conversations for the signed-in user |
| GET/POST | `/v1/conversations/{id}/messages` | Read / send messages (participants only) |
| GET/POST | `/v1/conversations/{id}/contact-shares` | Read / create audited contact shares |

## Android client

* `domain/PrivacyModels.kt` — domain types and the `PrivacyRepository` interface.
* `data/repository/PrivacyRepository.kt` — remote-only implementation; nothing
  caches exact coordinates.
* `feature/privacy/PrivacyViewModel.kt` — state and actions.
* `feature/privacy/DonorMapScreen.kt` — approximate map, freshness, donor
  visibility controls, and the matched-requester location card.
* `feature/privacy/ConversationScreen.kt` — scoped chat and explicit contact
  sharing.
* `core/navigation/LifeLinkShell.kt` — a "Donor map" entry point on Home.

## Tests

`lifelink_fastapi/tests/test_privacy_flows.py` runs against a real PostgreSQL
(via `pgserver`) and covers: approximate-only map output, authentication
requirement, hide-revokes-share, match + opt-in gating, expiry on cancel, donor
revocation, conversation participant scoping, and audited contact sharing.

## Not yet done

* Migration 007 has not been applied to the live Supabase database.
* The Android client changes are not compiled here (no Android SDK in this
  environment); they need a Gradle build and instrumented-test run.
* Push notifications for new messages and location-share events are out of scope
  for this change.
