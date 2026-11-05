# LifeLink UI/UX Research and Product Specification

**Date:** 2026-11-04  
**Status:** Research/specification only. This document does not modify product code, establish production compliance, or replace runtime, backend, accessibility, security, operator/DPO, or Philippine legal validation.

## 1. Scope and evidence discipline

This report synthesizes the eight supplied research results across repository inspection, screenshots, Android/Material guidance, WCAG benchmarks, and OWASP guidance. Statements under **Observed evidence** describe what was found in the supplied repository or screenshots. Statements under **Recommendation/specification** define the proposed product behavior and are not claims that the current product implements it.

Repository and screenshot evidence are high-confidence for the inspected revision and supplied captures, but they do not prove deployed backend behavior, runtime permission behavior, production authorization, Play Console declarations, support operations, retention execution, or device accessibility. Screenshots are synthetic/fixture-oriented where noted and cannot establish live matching or pin rendering. [1] [2] [3]

## 2. Executive synthesis

LifeLink has a promising privacy-first foundation: anonymous, coarsened donor-area mapping; request-scoped exact-location sharing; explicit contact sharing; request-scoped messaging; report/block controls; server-oriented role/profile loading; and substantial Compose semantics. The repository also contains clear product intent around donor/requester separation, notification updates, and lifecycle-aware requests. [4] [5]

The main UX risks are not isolated visual defects. They are information-architecture and state-contract inconsistencies: the current shell exposes five destinations while newer plans describe four; Donors, Donor workspace, Settings, Profile, Activity, Updates, and Notifications have overlapping or changing meanings; role switching appears to change an account field and an entire dashboard context; request lifecycle values differ between backend and Android; messaging copy promises an acceptance gate that the inspected conversation endpoint does not enforce; and privacy/legal promises exceed visible implemented support and rights workflows.

The target experience should use one account with two server-confirmed capabilities, explicit requester and donor work modes, four stable top-level destinations—**Home, Requests, Updates, Profile**—and contextual donor discovery/workspace surfaces. Requests and history should have clear lifecycle states, search/filter behavior, server-authorized details, and deterministic loading/error/empty states. Updates should be a normal destination with formal deep-link/back-stack behavior rather than a boolean overlay. Privacy Center should make approximate versus exact location, sharing expiry, notifications, retention, rights, and support routes understandable in one place.

## 3. Observed evidence versus product decisions

### 3.1 Navigation and identity

**Observed evidence.** `LifeLinkShell.kt` currently defines five compact-window destinations: Home, Requests, Donors, Messages, and Settings. It also exposes a notification bell and settings gear. Android and Material guidance support three to five labeled compact navigation destinations, so the count is structurally defensible, but current labels mix task destinations with account/settings and shared-context surfaces. [6] [7] Screenshots show the five-item bar, a Donor workspace with its own Home/Requests tabs, and Settings as both a top-level destination and gear shortcut. The newer repository plan instead names Home, Requests, Updates, and Profile. [8]

`UpdatesScreen.kt` visibly calls the surface Activity, while notification routing and newer planning call it Updates. The account surface is visibly Settings although the newer information architecture calls it Profile. This is source/documentation drift, not proof of one specific shipped runtime identity.

**Recommendation/specification.** Adopt one canonical identity: app name **LifeLink** and four stable destinations: **Home, Requests, Updates, Profile**. Use Updates for the in-app event history; reserve Notifications for Android/system alerts. Keep Profile as the account-level destination; Settings may be an internal grouping label but should not be a competing shell identity. Treat Donor discovery as a requester-context map/list and Donor workspace as donor operations, entered through the active-mode switcher or an explicit contextual action rather than a fifth permanent role destination.

Use one consistent app header hierarchy. Home may display LifeLink; contextual pages should use descriptive titles such as Donor workspace, Request details, Updates, or Privacy Center. Do not maintain separate navigation identities for the same surface. Use adaptive navigation: bottom navigation in compact windows and a rail in expanded windows, with the same destination model. [6] [9]

### 3.2 Account roles and mode switching

**Observed evidence.** The repository defines REQUESTER and DONOR modes while retaining dual capabilities in some cases. MainActivity loads the authenticated server profile, defaults new accounts to requester mode, and performs guarded profile-backed switching. The role switcher is currently a Home-only text action; Settings also exposes Open donor workspace. Screenshots show a requester shell with Requests, Donors, Messages, and Settings, and a donor workspace with Ready to help, availability controls, and only Home/Requests tabs. [10]

**Recommendation/specification.** Treat this as one account with two server-confirmed capabilities and two explicit work modes—not two accounts and not merely a cosmetic tab. Requester mode owns emergency-request creation, active status, matches, contacts, and history. Donor mode owns donor profile, verification, availability, incoming opportunities, responses, and donor privacy/location controls. Messages and Profile remain account-level/contextual surfaces.

Expose one canonical mode switcher in Profile/account controls and make it reachable from both dashboards. Label it explicitly, for example **Switch to donor mode** or **Switch to requester mode**; do not use “Donors” as a mode label. On switch, write and confirm the server state, then land on the new mode’s dashboard and clear/re-scope role-specific transient state. While saving, disable repeat activation and announce progress. On failure, retain the prior mode and show an actionable error. Back/Up must not reopen stale screens from the previous mode.

Mode selection is never authorization. Every request, donor, contact, message, location, and profile operation must enforce ownership/participant permissions server-side for every object ID. This follows object-level authorization guidance. [11]

### 3.3 Settings, privacy, legal, help, and support

**Observed evidence.** Settings currently has Account, Notifications, Privacy, Legal, Theme, and Security sections in a horizontally scrollable tab row. Screenshots show Privacy, Legal, Theme, and Security, including Reset password and Session safety. There is no visible Help/FAQ, support/contact, data export, deletion, data-rights, or consent-history entry. [12]

Registration displays Privacy Policy and Terms, and sign-up sends policy/terms versions and an acceptance timestamp in user metadata. Legal copy says privacy assistance or account deletion can be requested through a support channel, but source review found no implemented support inbox, deletion flow, or in-app rights-request workflow. Client-side legal version constants are evidence of app metadata, not proof of operator approval or a current published notice. [13]

Donor map visibility and exact-location sharing are available in the donor workspace, while global settings do not clearly summarize approximate map visibility, matched-requester exact sharing, expiry/revocation, contact sharing, or retention. This split is likely to be misunderstood even though the backend model is materially safer than an open exact-location directory. [4]

**Recommendation/specification.** Keep Profile as the top-level account destination and make it a vertically scannable, grouped list rather than relying on a crowded horizontal tab row. Groups should include Account and mode, Notifications, **Privacy Center**, Legal, Appearance, Security, and Help & FAQ. Retain the gear as a secondary shortcut only if it opens Profile/Settings consistently.

Privacy Center must contain a layered notice, data categories and purposes, recipients/vendors, current notice version and effective date, consent/optional-processing history, map visibility controls, exact-location conditions, notification guidance, retention summary, access/correction/objection/withdrawal/deletion request route, and named operator/DPO contact. It must state plainly:

- Public/requester map entries are anonymous and approximate/coarsened.
- Exact location is never public; it is available only to an authorized matched requester during an active, consented, time-limited share.
- Exact sharing can expire or be revoked, after which the pin/details disappear.
- Contact details are shared field-by-field and only by explicit action.
- Availability, verification, location freshness, and eligibility are separate concepts.

Add Help & FAQ answers for what LifeLink does and does not do; emergency escalation; donor eligibility and clinical responsibility; approximate versus exact location; contact sharing; notification behavior; stale/expired matches; reporting/blocking; account recovery; and support/privacy requests. Add a real deletion/deactivation and data-request route with identity verification, request ID/status, expected timeframe, exceptions for safety/legal/audit retention, and a clear deleted-versus-retained explanation. Until a real channel/process exists, do not promise a completed deletion right through generic copy.

Legal should show Privacy Policy and Terms separately, each with version, effective date, and change history. Distinguish a privacy notice from consent; require deliberate re-acknowledgement for material changes where the operator/DPO/counsel determines it is necessary. Retain acknowledgement evidence server-side.

### 3.4 Accessibility and visual consistency

**Observed evidence.** The code supplies many content descriptions, headings, live-region semantics, selectable theme controls, and 48dp-class controls. Donor-map filtering has stronger semantics and automated tests for pane title, filter counts, active labels, and target size. However, screenshots show dense small text and tightly packed settings tabs. No evidence verifies TalkBack order, large-font reflow, contrast across themes, touch-target behavior on devices, dialog focus containment, or localized labels. [12] [14]

**Recommendation/specification.** Run Compose semantics tests and physical-device review for every tab/action/dialog. Each control needs a meaningful name, role, current state, and action. Selected tabs must be announced; dialog focus must be contained and restored to the invoking control; asynchronous status must be announced without stealing focus; decorative icons may remain unlabeled only when the parent action is fully labeled. Use Android-native 48dp targets and WCAG 2.2 as a benchmark for contrast, text resizing, reflow, labels, and status messages. [15] [16]

Test 320dp width, large fonts up to 200% where feasible, dark and dynamic themes, TalkBack, keyboard navigation, English and long/localized labels, and notification previews. Do not rely on color alone for unread, availability, request, or connection status. Accessibility text and notification content must not disclose exact coordinates, contact details, full account IDs, or medical data beyond the user’s authorized context.

## 4. Information architecture and navigation model

### 4.1 Canonical hierarchy

**Shell:** Home | Requests | Updates | Profile.

**Home:** active work-mode dashboard; primary action; current request or donor opportunity summary; mode switcher; contextual shortcut to donor discovery or donor workspace.

**Requests:** create emergency request; current request; matches/results; contacts; request history; request detail/timeline.

**Updates:** durable in-app event list for request status, contact decisions, messages, account/security events, and actionable system updates.

**Profile:** account identity and mode; donor capability/profile; Notifications; Privacy Center; Legal; Appearance; Security; Help & FAQ; support and data requests.

**Contextual surfaces:** Donor discovery/map/list is available from requester flow and may be linked from Home/Requests. Donor workspace is available from donor mode. Conversation is request-scoped and entered from an authorized contact/request detail or Updates. None should create a competing permanent shell identity.

### 4.2 Back, Up, deep links, and account changes

Use an explicit navigation stack. A notification tap should produce a believable route such as Updates → Request detail or Updates → Conversation, with Back returning to the meaningful prior destination. A deep link to an expired, missing, or unauthorized object must show a safe unavailable state in Updates or a request detail shell; it must never silently land on an unrelated screen. Android navigation guidance expects state to behave as a stack and deep links to create a realistic synthetic back stack. [9]

Switching mode replaces the role-specific top-level context and lands on that mode’s dashboard. Sign-out and account switch clear user-scoped local state, pending deep links, notifications, drafts, and cached private data before rendering the next account. Profile/settings and authorized shared message context may persist only where explicitly permitted.

## 5. Search, filters, and discovery

### 5.1 Requests and history search

The supplied evidence identifies request history but does not show a complete search/filter contract. **Specification:** Requests should provide a search field when history is large enough that browsing becomes costly, with hint **Search requests**. Search should match request ID (with a human-friendly short reference), facility/area, blood type, and status where authorized. It should be near the history title, debounced for remote queries, cancellable, and preserve the last valid result during refresh.

Provide filters for Status, Date range, Blood type, Urgency, and Outcome where those fields exist. Show active-filter count, filter pane title, selected state, result count, and Clear filters only when filters are active. Preserve filters when moving between history and detail where practical. Material guidance supports search for large collections and labeled filter chips/panes with accessible selected states and result feedback. [17] [18]

States: initial loading skeleton; loaded list; loaded empty (“No requests yet” with Create emergency request); no-match (“No requests match these filters” with Clear filters); refreshing with existing results retained; offline cached list with freshness label; authorization/session error; server error with Retry; and unsupported/unknown status rendered safely, never as active matching.

### 5.2 Messages search and filters

Messages should show participant name (not opaque conversation-ID prefix), request summary, last-message preview, relative time, unread count, and connection-state badge. Add **Search messages** when the conversation collection is large enough to justify it. Filters may include Unread, Active, Needs response, and Closed/Archived, but must not expose conversations the user is not authorized to see. Keep request context visible in each row.

### 5.3 Donor map/list filters

Preserve the stronger existing pattern: Map/List parity, blood type, Available now, freshness/stale labels, a titled filter pane, result count, live feedback, and Clear filters. Map entries remain anonymous/coarsened; list rows should expose approximate area/radius, blood type, availability, freshness, and selection state without exact public coordinates or donor identity. [4] [19]

## 6. Required state specifications

### 6.1 Notifications and Updates

**Observed evidence.** Updates is a full-screen Activity-like surface with unread/read cards and Mark all read. The shell currently uses boolean overlays and a numeric top-icon badge; notification intents route request, conversation, and general updates. One high-importance emergency channel passes remote title/body through and uses the launcher PNG as a notification icon. [20]

**Specification.** Make Updates a formal top-level destination. Model states: received/persisted unread; opened in Updates; opened by deep link; read; system-dismissed but still unread; stale/expired; missing/unauthorized target; loading; empty; offline cached; and server error. Opening the in-app item is the default read transition; dismissing the Android notification must not silently mark the durable update read.

Use separate channels by urgency and expectation. Reserve high importance for genuinely time-critical emergency events; routine status, account, and messaging updates should not share that channel. Default to lock-screen-safe generic titles/bodies and keep sensitive detail inside the authenticated app. Use a small unread dot when only existence is needed; use a numeric badge only when reliable and short. Android notification guidance emphasizes concise, relevant content and direct related destinations. [20] [21]

### 6.2 Maps, GPS, and donor availability

**Observed evidence.** The map is loading-state evidence around Tacloban with Map/List, filters, privacy and refresh actions; no visible pins prove successful live rendering. Code uses anonymous approximate areas, coarsening, stale-location handling, and matched-requester-only exact coordinates. Donor GPS uses a one-shot foreground request with a 30-second cache age and 15-second duration, but the inspected donor UI rejects approximate-only permission even though project research says approximate location should suffice for matching. [4] [19] [22]

**Specification.** Support these distinct states: map style loading; area data loading; loaded with areas; loaded empty; filter no-match; stale data; style/data error; retry; offline cached; location permission not requested; approximate permission; precise permission; denied; permanently denied; services disabled; timeout/no fix; and manual/map-selected fallback. Explain the effect and recovery action for each. Coarse location must support approximate matching; request precise location only for a demonstrated product need. Show fix source, age, permission precision, and estimated uncertainty, using “estimated accuracy ±N m” rather than a guarantee.

Availability states are Available, Paused, and Offline. Available means eligible only if verification, profile visibility, valid blood type, recent location, consent, and server matching rules also pass. If a donor is Available but not matchable, explain why rather than implying availability alone is sufficient. Matching compatibility remains server-authoritative; the client must not invent a medical compatibility matrix. [22]

### 6.3 Requests and history

**Observed evidence.** Backend statuses include draft, matching, awaiting_responses, partially_fulfilled, fulfilled, expired, cancelled, and manual_broadcast; Android lacks DRAFT and maps unknown values to MATCHING. History carries created/expiry/urgency fields but the visible card omits important timing/context. A screenshot promises View request details although the inspected card excerpt lacks a clickable action. [23]

**Specification.** Preserve every backend status plus an explicit Unknown/Unsupported state. Never map future or malformed status to active matching. Use a single server-backed model keyed by request ID; invalidate stale local submission state after refresh, process recreation, account switch, or server transition.

Display distinct states and allowed actions: Draft; Searching/Finding eligible donors; Waiting for donor responses; Matching results ready; Manual broadcast pending; Partially fulfilled; Fulfilled; Expired; Cancelled; and Unknown/unavailable. Include created time, response deadline/expiry, urgency, last updated, response counts, and next action where decision-relevant. Partially fulfilled needs a documented rule for whether additional contacts remain allowed.

If View request details is shown, every history row opens a server-authorized detail/timeline route with matching request ID. Otherwise remove the affordance. Cancellation should visibly explain that donor alerts stop and the request is cancelled; terminal state should remain discoverable in history. Loading, empty, refresh, offline, no-match, unauthorized, and error behavior must be explicit and preserve valid cached data during refresh.

### 6.4 Messaging and connection lifecycle

**Observed evidence.** The intended UI says messaging becomes available after a matched donor/requester accepts a contact request. The inspected conversation creation path verifies participant/request/match relationships but does not verify accepted contact status, creating a server-side lifecycle gap. Existing messaging supports polling, explicit phone/email sharing, report/block, rate limits, and request-active checks; real-time, typing indicators, read receipts, and attachments are deferred. [24]

**Specification.** Enforce acceptance server-side on conversation creation and every read/write path. Define states: Matched/not contacted; Contact requested/Pending; Accepted/Chat available; Declined; Contact shared; Meeting arranged; Fulfilled; Cancelled; and Expired. Define exactly which states permit reading, sending, sharing, reporting, or blocking. If pre-acceptance messaging is desired, change both product copy and security/abuse rules; do not rely on hidden UI controls.

Conversation states: loading; empty/new; loaded; sending; send failed with draft preserved; transient polling error with retry; blocked/read-only; request expired/cancelled/fulfilled read-only explanation; unauthorized/missing; offline; and server error. After block, disable composer and sharing immediately, explain the effect, and retain report/safety follow-up. Keep important connection state inline; use one non-stacking Snackbar only for brief confirmation. Push notifications must be generic and re-check authorization on tap.

## 7. Privacy, security, and permission behavior

The current privacy architecture is directionally strong: public donor map data is anonymous and coarsened; exact coordinates require a request-scoped active share; shares expire/revoke; visibility changes are audited; conversations and contacts are request-scoped; and phone/email sharing is explicit. These are repository observations, not a deployed security guarantee. [4] [5]

Runtime permission behavior must request foreground location immediately before the user action that needs it, explain approximate/precise effects, provide denial fallback, request notifications in context, and never add background location. Android guidance emphasizes minimum necessary permission and a clear user action/purpose. [22] Notification previews, logs, backups, accessibility text, and analytics must be reviewed for redaction. Run cross-account tests for forged IDs, stale shares, guessed conversation/request IDs, sign-out, account switching, and notification taps.

Before release, reconcile in-app notices, Settings labels, server behavior, retention schedule, vendor/subprocessor list, incident route, Play Data safety disclosure, and current Philippine operator/DPO/counsel review. Any unimplemented support, deletion, export, consent-history, or retention capability must be labeled as a gap, not represented as completed.

## 8. Ordered implementation backlog and acceptance criteria

### P0 — Resolve contracts and safety blockers

1. **Freeze the information architecture and vocabulary.** Adopt Home, Requests, Updates, Profile; define Donor discovery, Donor workspace, Activity/Updates, Settings/Profile, and mode semantics.
   - Acceptance: one approved navigation diagram and glossary; no conflicting user-facing labels in source/docs/screenshots; compact and expanded layouts use the same destinations.

2. **Enforce role, object, and conversation authorization.** Add the contact-acceptance gate and fail-closed stale/unauthorized deep links.
   - Acceptance: requester/donor before acceptance cannot create/read/send conversation; forged IDs and unrelated accounts receive safe denial; cancelled/expired/fulfilled behavior is tested; mode switch cannot grant access.

3. **Create the canonical request lifecycle contract.** Preserve draft and unknown/future statuses; align backend, Android, local persistence, actions, and copy.
   - Acceptance: no unknown value renders as Matching; partially fulfilled rules are documented; active/history/detail transitions converge after refresh, process death, account switch, and concurrent actions.

4. **Publish a truthful privacy/support capability contract.** Implement or remove promises for Privacy Center, support, deletion/data requests, consent history, retention, and DPO contact.
   - Acceptance: every advertised route is reachable and has an owner/process, or copy explicitly says it is not yet available; privacy notice versions/timestamps are server-retained.

### P1 — Make core flows coherent

5. **Replace notification overlays with formal Updates navigation.** Define event state machine, deep links, Back/Up, read rules, safe payloads, and channels.
   - Acceptance: notification → Updates/request/conversation produces deterministic stack; stale target shows safe state; Back never reveals stale overlay; system dismissal does not mark read; routine events do not use emergency importance.

6. **Implement Requests search, filters, detail, and history states.**
   - Acceptance: search/filter semantics and counts are accessible; loading, empty, no-match, offline, refresh, unauthorized, error, and unknown-status states are tested; every promised View details action opens the correct authorized request.

7. **Complete messaging lifecycle UX.** Add participant/context identity, state badges, unread counts, retry/draft preservation, block read-only behavior, and explicit post-acceptance entry points.
   - Acceptance: rows do not use opaque IDs as primary identity; all connection states have next actions; accepted-only chat is consistent in UI and API; accessibility labels include participant and state.

8. **Correct GPS and availability behavior.** Support approximate permission and typed failure states; expose freshness/source/uncertainty; maintain map/list privacy model.
   - Acceptance: coarse-only, precise, denied, disabled services, timeout, stale, offline, and manual fallback tests pass; no public exact donor pin or identity appears; Available does not imply eligible without server checks.

### P2 — Improve discoverability and inclusive quality

9. **Restructure Profile/Settings and add Help & FAQ.** Replace or redesign crowded tabs; add Privacy Center, legal history, security, notifications, support, and data-request entry points.
   - Acceptance: settings labels remain legible at 320dp and large fonts; selected state, focus order, dialog return focus, and live feedback are Compose-tested and TalkBack-reviewed.

10. **Standardize launcher and notification iconography.** Use adaptive launcher layers and a dedicated monochrome notification icon; validate lock-screen privacy.
   - Acceptance: launcher, Recents, themed icon, circle/squircle, and notification surfaces are visually valid; sensitive notification content is private by default.

11. **Run visual/accessibility/device matrix.** Validate light/dark/dynamic themes, contrast, 48dp targets, map overlays, chips, badges, dialogs, localization/long labels, and large text.
   - Acceptance: documented test results with defects triaged; no state depends on color alone; status messages are announced without disruptive focus movement.

12. **Operational/legal release reconciliation.** Reconcile notices, consent, retention, vendor processing, Play Data Safety, incident handling, and Philippine legal review.
   - Acceptance: named owner signs each item; unresolved gaps are recorded as launch blockers or explicitly accepted risks; this specification is not treated as proof of compliance.

## 9. Open questions and validation plan

The product team must decide whether `profile.role` is an operational mode preference or a primary-role/capability field; whether Donors is discovery-only; whether messages remain readable after fulfillment/expiry; exact retention periods for messages, locations, shares, and audit records; whether partially fulfilled requests accept more contacts; and what support/DPO channel will fulfill rights requests.

Validation should combine repository tests, API authorization tests, emulator and physical-device permission tests, TalkBack and large-font review, notification lock-screen review, map failure tests, two-account flows, account switch/process death, and operator/DPO/legal review. Screenshots remain useful for visual regression but do not substitute for these checks.

## References

[1]: https://github.com/RamProjector/LifeLink "LifeLink repository"
[2]: https://developer.android.com/develop/ui/compose/accessibility "Android Compose accessibility"
[3]: https://www.w3.org/TR/WCAG22/ "Web Content Accessibility Guidelines 2.2"
[4]: https://github.com/RamProjector/LifeLink/blob/main/docs/DONOR_MAP_PRIVACY_DESIGN.md "LifeLink donor map privacy design"
[5]: https://github.com/RamProjector/LifeLink/blob/main/docs/LOCATION_SHARING_AND_CHAT_RESEARCH_PLAN.md "LifeLink location sharing and chat research plan"
[6]: https://developer.android.com/develop/ui/compose/components/navigation-bar "Android Compose navigation bar"
[7]: https://m3.material.io/components/navigation-bar/guidelines "Material 3 navigation bar guidelines"
[8]: https://github.com/RamProjector/LifeLink/blob/main/docs/UPDATES_AND_PROFILE_IMPLEMENTATION_PLAN_2026-09-22.md "LifeLink Updates and Profile implementation plan"
[9]: https://developer.android.com/guide/navigation/principles "Android navigation principles"
[10]: https://github.com/RamProjector/LifeLink/blob/main/docs/REQUESTER_DONOR_LIFECYCLE_AUDIT.md "LifeLink requester/donor lifecycle audit"
[11]: https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/ "OWASP API1:2023 Broken Object Level Authorization"
[12]: https://github.com/RamProjector/LifeLink/blob/main/docs/UX_POLISH_RESEARCH_2026-09-22.md "LifeLink UX polish research"
[13]: https://github.com/RamProjector/LifeLink/blob/main/docs/LIFELINK_LEGAL_RESPONSIBILITIES_RESEARCH.md "LifeLink legal responsibilities research"
[14]: https://developer.android.com/develop/ui/compose/accessibility/semantics "Compose accessibility semantics"
[15]: https://developer.android.com/guide/topics/ui/accessibility/apps "Android accessibility guidance"
[16]: https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html "WCAG status messages"
[17]: https://m3.material.io/components/search/guidelines "Material 3 search guidelines"
[18]: https://m3.material.io/components/chips/accessibility "Material 3 chip accessibility"
[19]: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient "FusedLocationProviderClient reference"
[20]: https://developer.android.com/design/patterns/notifications.html "Android notification design guidance"
[21]: https://developer.android.com/develop/ui/compose/notifications/create-notification "Create notifications with Compose/Android"
[22]: https://developer.android.com/develop/sensors-and-location/location/permissions "Android location permissions"
[23]: https://github.com/RamProjector/LifeLink/blob/main/docs/IMPLEMENTATION_STATUS.md "LifeLink implementation status"
[24]: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html "OWASP authorization cheat sheet"
