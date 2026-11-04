# LifeLink Deep Research Addendum: Navigation, Location Trust, Matching, Chat, and Durable Documentation

**Prepared:** 4 November 2026  
**Repository:** `RamProjector/LifeLink`  
**Status:** Research/documentation addendum only. This file does not change app behavior, API contracts, database schema, or deployment state.

## Why this addendum exists

The existing roadmap and lifecycle/privacy documents contain useful prior work, but the previous consolidated document did not sufficiently connect the real implementation, user journeys, privacy rules, UI state, and verification requirements. This addendum consolidates the next layer of research and distinguishes **repository evidence**, **design recommendation**, and **unverified work**.

## 1. Repository evidence checked

The following existing files were reviewed as context:

- `docs/ANDROID_HCI_REDESIGN_ROADMAP.md`
- `docs/DONOR_MAP_PRIVACY_DESIGN.md`
- `docs/LOCATION_SHARING_AND_CHAT_RESEARCH_PLAN.md`
- `docs/REQUESTS_CONTACT_MAP_RESEARCH_2026-09-21.md`
- `docs/REQUESTER_DONOR_LIFECYCLE_AUDIT.md`
- `docs/IMPLEMENTATION_STATUS.md`
- Android shell, donor map, donor screen, donor presence policy, and messaging/navigation source files previously inspected.

### Confirmed repository-level design/evidence

- The app supports **requester and donor capabilities together**; workspace selection is not the same thing as changing account permissions. The server profile remains the capability source of truth.
- The shell currently models Home, Requests, Donors/Map, Messages, and Settings, with adaptive navigation behavior. The visible map label and top-level Profile-vs-Settings affordance still need a product decision and full route audit before changing navigation.
- The donor map is a **privacy-preserving donor-area discovery map**, not a general-purpose “GPS tracker.” Existing documentation describes coarsened map areas, opt-in visibility, freshness, and separate exact-location sharing.
- The existing donor presence policy only refreshes presence for a verified donor who explicitly selected “available”; app foregrounding alone is not intended to opt someone in.
- The repository already has a request-scoped chat/location-sharing design and server authorization requirements. A screen or Retrofit endpoint alone is not evidence that a user can reach the flow or that backend authorization is correct.
- Existing status documentation reports historical deployment and migration state. Those dates are not a fresh live verification in this addendum.

**Important evidence boundary:** selected source files and existing docs were inspected; this is not a full audit of every current backend route, SQL policy, or current CI run. Do not treat recommendations below as proof that the code already implements them.

## 2. Core product model: capabilities, workspace, availability, and location are separate

Avoid a single overloaded “role/status” field. These concepts answer different questions:

| Concept | Question it answers | Should not imply |
|---|---|---|
| Account capability | What requester/donor actions does the server permit this account to use? | Which tab is currently selected |
| Workspace | Which task area is the user currently viewing? | Account capability changed |
| Donor profile completion/verification | Is the donor profile ready under the actual server rules? | Donor is currently available |
| Availability | Is the donor currently accepting matching opportunities? | Location is fresh or visible |
| Approximate map visibility | Has the donor opted into showing an approximate area? | Exact location is shared |
| Location freshness | Is the saved location recent enough for the relevant feature? | Donor is available or matched |
| Exact-location share | Is there a currently authorized, scoped share for this request/donor pair? | A permanent tracking permission |
| Contact/conversation authorization | Does this account have a valid relationship for this request/chat? | Phone/email or location is automatically disclosed |

This separation is essential for truthful UI copy and safe backend enforcement. A donor may be available but hidden from the map; visible approximately but not sharing exact location; matched but not yet accepted for contact; or accepted while the location snapshot is stale.

### Recommended donor visibility pipeline

Use explicit, separately represented gates:

1. Authenticated account and donor capability are loaded from the server.
2. Donor profile is complete/verified according to server policy.
3. Donor explicitly chooses availability.
4. A location snapshot exists and meets the relevant freshness threshold.
5. Approximate map visibility is enabled (for map discovery only).
6. Matching is computed using server-owned criteria.
7. A request-scoped relationship/contact action is accepted when required.
8. Exact location is disclosed only if the specific live share is authorized, unexpired, and not revoked.

Do not collapse these into a single green “online” badge. The UI should identify the specific reason a donor is unavailable to a given action, without exposing sensitive internal eligibility details.

## 3. Map redesign: improve comprehension without weakening privacy

### Keep the existing privacy boundary

The donor map should not become an open directory of donor identities or precise pins. Keep the current privacy-safe map-area concept unless a separately reviewed product requirement justifies a change. Do not show a city-center fallback as “my location,” and do not use map camera center as proof of GPS acquisition.

### Make four location concepts visually distinguishable

- **My device location:** only after a real location request succeeds; permission state and timestamp are known.
- **Request location:** the location the requester selected/saved for the emergency request.
- **Approximate donor area:** deliberately coarsened and non-identifying.
- **Exact shared location:** shown only inside the authorized request/conversation context while the share is valid.

Recommended UI wording should identify the source and precision, e.g. “Request location,” “Approximate donor area,” “Updated 18 min ago,” or “Exact location shared for this request.” Avoid ambiguous labels like “GPS on,” “nearby,” or “live” when the app is not actively tracking.

### Map and list should be two views of one result state

Use one ViewModel/repository result model for map markers and the accessible list. Filters, sorting, refresh, selected result, freshness, and empty/error states should stay synchronized. A list is not merely a fallback screen; it provides a more accessible, scannable alternative for users who cannot operate a map.

Recommended state distinctions:

- loading initial results;
- refreshing existing results;
- loaded with results;
- loaded with no results;
- no results because filters are too restrictive;
- stale results with timestamp;
- permission denied / approximate-only / location services off (only for features that actually require device location);
- network or server failure with retry;
- map style/renderer failure while list data remains available.

Do not say “searching continuously” if the screen only loads on entry or refreshes on user action.

### Map acceptance checks

- No exact donor coordinates or donor identity leak through map payloads, marker details, accessibility labels, logs, analytics, screenshots, or error text.
- Hiding a donor or revoking a share removes access on the server; client cleanup is only a UI response.
- Map refresh and style recreation do not duplicate markers.
- Map and list show the same filtered result set and selected item.
- Stale data is visibly stale and does not masquerade as current availability.
- Manual request-location entry remains possible when appropriate; device permission denial must not block unrelated workflows.

## 4. Navigation and adaptive layout: avoid a visual-only tab rearrangement

The current shell already has multiple destinations and adaptive behavior. Before changing tabs, build a route inventory of:

- top-level tabs;
- profile/settings subroutes;
- donor onboarding/setup;
- request creation and active request;
- donor map and requester map (confirm whether these are one screen or distinct role-specific surfaces);
- conversation list and conversation detail;
- notification/update center;
- notification deep links;
- sign-in, account switching, and role/workspace switching;
- system Back and modal/overlay dismissal.

### Proposed direction (not a code change)

A user-facing primary navigation could be **Home, Requests, Map, Messages, Profile**, with account/security/privacy/legal/settings grouped under Profile. This is a recommendation, not a final decision: verify that all current Settings routes and notification paths are preserved before removing or renaming a destination.

For adaptive navigation:

- Keep the same destination hierarchy across compact bottom navigation and expanded rail/navigation drawer.
- Give the map destination a visible label and selected state; avoid a compact-only floating icon being the only way to discover the feature.
- Do not hide important actions under an unlabeled center button.
- Keep primary emergency-request creation separate from the map entry point.
- Back should return to the prior task/workspace, not arbitrarily jump Home or reset the donor/requester context.
- Deep links should open a typed destination with validated IDs, then return to a sensible previous context.

Android’s adaptive guidance supports changing navigation presentation by available window size, while preserving content and hierarchy. Navigation should be tested on compact phones and expanded/foldable layouts, not only one emulator width.

## 5. Request/contact/chat: model the actual relationship, not just a button tap

The current docs describe request-scoped conversations, accepted contact relationships, explicit location sharing, and separate contact-share consent. Preserve those distinctions.

### Recommended user-visible lifecycle

Use backend-authoritative states and map them to plain language. Do not invent or rename backend enum values without checking the current API/schema.

- **Request created** → server confirms request and stable ID.
- **Matching** → results are loading or updating; do not imply a donor has accepted.
- **Contact action submitted** → pending/processing; not yet a connected chat.
- **Donor accepted** → relationship is confirmed; conversation action becomes available if server policy allows it.
- **Conversation opened** → conversation ID returned and participant authorization confirmed.
- **Message sending / sent / failed** → distinguish local queueing from server acknowledgement.
- **Request cancelled / fulfilled / expired / relationship revoked** → stop new actions according to server policy, explain what remains available in history, and revoke related location shares as required.

“Accepted” must not be described as “donation completed.” The request lifecycle and donor response lifecycle are related but distinct.

### Chat safety and reliability requirements

- Every message read/write must validate the authenticated user as a conversation participant on the backend.
- Do not trust request ID, donor ID, or conversation ID supplied by the client as authorization.
- Retry must not create duplicate messages; use server message IDs/idempotency where supported.
- Failed send should preserve draft text and offer retry without falsely showing “sent.”
- Switching accounts/conversations must clear the prior account’s visible messages and exact-location state.
- Contact details, exact coordinates, and message content must not leak into notifications, logs, crash reports, or unrelated request cards.
- Block/report controls need an actual backend effect and clear user-facing consequences; a UI-only control is not sufficient.
- Attachments (images/video) require a separate storage/security design: signed or scoped upload/download access, ownership checks, size/type limits, malware/content handling appropriate to the product, retention/deletion policy, and no public bucket exposure. Do not assume Render’s application filesystem is durable storage.

### Messaging regression priority

The reported crash after submitting a request and opening Messages should be tested as a **navigation/account-state regression**, not merely a chat styling issue. Required scenarios:

1. Requester submits a request → navigates to Messages.
2. Requester opens an existing conversation from request details.
3. Donor accepts/declines → opens eligible conversation.
4. Notification deep link opens conversation.
5. Conversation ID missing, stale, unauthorized, or request closed.
6. Network failure while conversation list/detail loads.
7. Account switch/sign-out while a conversation is visible.
8. Back from conversation returns to the correct prior screen.

Do not claim the crash is fixed until a reproducible test or manual verification confirms it.

## 6. Privacy, permissions, and legal disclosure

Privacy UI should explain the actual data flow, not just show a toggle. For each location/contact/message feature, document:

- data collected and precision;
- source (device fix, manually entered request location, saved donor location);
- purpose and who receives it;
- whether it is optional;
- expiry and revocation behavior;
- storage location and retention;
- behavior after request close, logout, account deletion, or donor hiding;
- fallback behavior when permission is denied.

Use just-in-time permission requests and request the least precise/scope that can meet the task. Approximate location should remain viable for approximate-area discovery when exact coordinates are not required. Do not make background location permission a prerequisite for foreground, one-time request-location selection unless the actual feature truly requires it.

Keep legal documents distinct from short privacy help/FAQ. The app may provide a friendly Privacy & Safety hub, but it should link to the full policy/terms and preserve version/effective date. Before public release, the operator must confirm the actual controller identity, privacy contact, lawful bases, recipients/processors, retention, transfer details, user-rights handling, and deletion behavior. This document is not a legal opinion and does not establish compliance.

## 7. New storage direction: documentation as code in the LifeLink repository

The previous local downloadable Markdown artifact is fragile as a long-term source of truth: it can become inaccessible in a later chat, and separate copies can drift from repository changes.

### Recommended source of truth

Store ongoing research as Markdown in the LifeLink repository under `docs/`, with a stable filename, change history, and cross-links to the roadmap/audit/status documents. Use GitHub as the durable canonical source; use downloadable PDF/Markdown exports only as convenient snapshots, not the authoritative copy.

Why this fits:

- Documentation is versioned alongside the implementation it describes.
- Diffs make additions and corrections auditable.
- A later contributor can review claims against source code.
- A PR can discuss documentation without silently changing app behavior.
- GitHub renders Markdown directly, so no separate paid publishing service is required.

This follows the docs-as-code practice of plain-text documentation, version control, and review. GitHub’s pull-request workflow provides a discussion and review trail before changes are merged.

### Storage workflow

1. Add/update a Markdown file in `docs/` on a dedicated documentation branch.
2. Include **evidence checked**, **recommendations**, **unknowns**, and **verification status** sections.
3. Link existing docs rather than duplicating them wholesale.
4. Open a documentation-only PR for review; do not merge it automatically.
5. After approval, merge the PR and treat the merged repository version as canonical.
6. For a user-friendly export, generate PDF or a ZIP snapshot from the same Markdown source later; never maintain the PDF as a separate editable source.

**Current storage action:** this addendum is being saved to a dedicated GitHub branch, not directly committed to `main`. The branch is a persistent repository artifact; it is not yet merged or approved.

## 8. Research sources

### Android / accessibility / adaptive design

- Android adaptive layouts and navigation: https://developer.android.com/develop/ui/compose/layouts/adaptive
- Android navigation patterns: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns
- Android accessibility overview: https://developer.android.com/guide/topics/ui/accessibility
- Android runtime location permission: https://developer.android.com/develop/sensors-and-location/location/permissions/runtime

### Location privacy / security

- OWASP MASVS: https://mas.owasp.org/MASVS/
- OWASP mobile security cheat sheet: https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html
- Apple Maps privacy principles (design reference, not an Android requirement): https://www.apple.com/legal/privacy/data/en/apple-maps/
- Google Maps marker accessibility: https://developers.google.com/maps/documentation/android-sdk/marker

### Documentation storage

- Write the Docs — docs as code: https://www.writethedocs.org/guide/docs-as-code/
- GitHub — about pull requests: https://docs.github.com/en/pull-requests/get-started/about-pull-requests

## 9. Verification and next research work

### Verified in this addendum

- Existing documentation was fetched and compared for overlap.
- Selected Android source files and shell/map/presence concepts were inspected in prior repository research.
- A documentation-only branch was created to preserve this addendum.

### Not verified here

- Full current backend route-by-route authorization audit.
- Current live Supabase migration/schema state.
- Current Render deployment health.
- Current CI/CodeRabbit status.
- Android build, APK, physical-device behavior, or the reported Messages crash.
- Whether every proposed requester/donor map flow is reachable in the current app.
- Whether the actual storage provider and attachment access policy are implemented.

### Next research pass

1. Trace the exact API request/response contracts for map results, donor contact lifecycle, conversation creation, message send, and exact-location share.
2. Build a backend authorization matrix from route handlers and database policies—not from client API declarations alone.
3. Trace every navigation entry point/deep link and identify duplicate or unreachable conversation routes.
4. Verify attachment storage options already available in the project and compare free-tier durable storage only after actual upload/download requirements are known.
5. Add a state transition diagram and role/relationship test matrix tied to real enum values.
6. Record evidence by file path, line/route, and test name before proposing implementation.

---

**Change log**
- 2026-11-04: Added deeper cross-document research and a GitHub docs-as-code storage direction. Documentation-only; no application code, API, database, deployment, or merge changes.
