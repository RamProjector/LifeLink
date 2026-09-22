# Updates and Profile Navigation Implementation Plan

**Status:** Blueprint approved for implementation
**Date:** 22 September 2026
**Owner:** Manus AI
**Target client:** `LifeLinkAndroid`

## Executive decision

Replace the passive **Info** destination with an operational **Updates** destination. Rename **Settings** to **Profile** and move product information, safety guidance, legal content, appearance, and account security into that destination.

The final top-level navigation should be:

> **Home · Requests · Updates · Profile**

This plan is intentionally specific. The implementation should follow the phases, file map, data rules, and acceptance criteria below without reintroducing a standalone Info tab or scattering informational content across the shell.

## Why this structure is the right replacement

The current Info destination is useful but passive. It explains how LifeLink works, yet it does not help the user act on a request, response, or account change. Updates gives the fourth navigation slot an operational purpose that is connected to the existing request, donor, contact, and push-notification workflows.

Android guidance describes notifications as brief, timely, and relevant. It also recommends that tapping a notification take the user directly to the related destination and action. An in-app Updates surface gives those events a durable place to be reviewed after the system notification is dismissed. [1]

Material 3 badges are designed to indicate an unread notification or an item count associated with a navigation destination. A small dot is appropriate when the count is not important; a short numeric badge is appropriate when the count itself is useful. Material also recommends hiding the badge after the destination is selected. [2]

Android navigation guidance treats navigation state as a stack of destinations. The Updates destination must therefore open as a normal top-level destination, while an update that targets a request must open the related request screen and preserve predictable Back behavior. [3]

The repository’s existing research already supports four stable destinations and identifies Requests as a core task rather than a secondary setting. [4] [5]

## Product behavior blueprint

### Updates content model

Updates should contain events that require awareness or action, not a general activity log. The first release should support these event types:

| Event type | Example title | Primary action | Destination |
|---|---|---|---|
| Request status | “Request is now matching” | View request | Active request or results |
| Donor response | “A donor responded” | Review response | Request results |
| Contact status | “Contact request accepted” | Review contact | Request results |
| Safety/account | “Your session expired” | Sign in again | Authentication |
| System notice | “Location could not be updated” | Retry or review profile | Profile or relevant form |

Each event must have a stable identifier, event type, title, concise body, creation time, optional request identifier, and read state. The UI must not expose private donor coordinates, contact details, or patient information in an update preview.

### Read-state rules

An update is unread when it has been created or received and the user has not opened the Updates destination after that event was available. Opening Updates marks visible updates as read. Opening a specific update also marks that update as read. The navigation badge displays the number of unread updates only when the count is meaningful and can be calculated reliably; otherwise it displays a small dot.

The first implementation may use a local read-state store keyed by event ID. It must not infer unread state from whether the Android system notification is still visible, because users can dismiss system notifications without reading the in-app event.

### Notification and deep-link behavior

A push notification remains a system-level alert. Updates is the in-app history and action surface. These are related but separate responsibilities.

When a notification includes `request_id`, tapping it must open the related request context directly. The existing `MainActivity` already reads `request_id` from the launch intent and passes it into `LifeLinkShell`; the implementation should preserve this path and add explicit routing to the relevant request or result state. [6]

Notification content must remain concise. The title should summarize the event, the body should preview the action, and the notification should not reveal sensitive information on a lock screen. High-importance delivery should be reserved for genuinely time-sensitive request events rather than every informational update. [1]

### Profile information structure

The renamed Profile destination should contain these sections in this order:

1. **Profile** — display name, account identity, role, and sign-out.
2. **Donor profile** — donor availability, visibility, contact preference, approximate location, and service radius when the role is donor.
3. **Privacy and safety** — location privacy, consent before contact, reporting, blocking, and safe meeting guidance.
4. **Appearance** — light, dark, or system theme.
5. **Security** — password reset, session safety, and future account controls.
6. **About LifeLink** — how matching works, what LifeLink is not, legal information, and version information.

The About LifeLink section should use title-first rows. Explanations should open in a detail dialog or secondary page only when needed. The top-level Profile screen should not repeat long explanatory paragraphs beneath every field.

## Implementation phases

### Phase 0 — Freeze the contract and inventory existing behavior

Before changing code, record the existing top-level tabs, request overlay flags, notification intent extras, request history fields, contact statuses, and current Profile/Info content. Do not change request submission, authentication, location privacy, or contact-consent behavior in this phase.

**Files:**

- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/core/navigation/LifeLinkShell.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/MainActivity.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/core/notifications/LifeLinkNotifications.kt`
- `LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/remote/LifeLinkApi.kt`

**Exit condition:** every existing event that can produce a system notification or visible request-state change has a documented target in Updates.

### Phase 1 — Rename the shell destinations

Change `ShellTab.INFO` to `ShellTab.UPDATES` and `ShellTab.SETTINGS` to `ShellTab.PROFILE`. Update labels, content descriptions, selected-state handling, and the shell’s `when` branch. The destination order must become Home, Requests, Updates, Profile.

Do not leave an Info tab alias in the navigation bar. Do not move request creation into Updates. Requests remains the only top-level destination for request creation and request history.

**Primary file:** `LifeLinkShell.kt`

**Exit condition:** the shell has exactly four practical destinations and every tab label describes an action-oriented destination.

### Phase 2 — Add the in-app Updates model

Create a small domain model and local persistence layer:

- `domain/UpdateItem.kt`
- `data/local/UpdateEntity.kt`
- `data/local/UpdateDao.kt`
- `data/local/LifeLinkDatabase.kt` migration
- `data/repository/UpdatesRepository.kt`

The local entity should contain `id`, `type`, `title`, `body`, `createdAt`, `requestId`, `isRead`, and an optional `actionKey`. Use stable event IDs from the server or notification payload when available. If the backend does not yet provide stable event IDs, derive a deterministic temporary ID from event type, request ID, and timestamp rather than using a random ID on every refresh.

**Exit condition:** Updates can render an empty list, a loading list, an error state, and a list with read/unread items without depending on a network request during composition.

### Phase 3 — Feed Updates from existing state and push events

Build the first useful feed from information already available in the app:

- active request status changes from `EmergencyRequestViewModel`;
- donor responses and contact statuses from request results;
- request-history refresh results;
- session-expired and authentication recovery messages;
- Firebase data messages received by `LifeLinkFirebaseMessagingService`.

`LifeLinkNotifications.showRemoteMessage` should continue showing system notifications, but it should also persist a corresponding local update through `UpdatesRepository`. The service must avoid storing sensitive body content if the payload is intended to remain private on the lock screen.

Deduplicate repeated push events by stable event ID. Do not generate multiple visible updates every time the shell recomposes or a request is refreshed.

**Exit condition:** a push event and an in-app request-state change can both create one durable Updates item, and repeated refreshes do not duplicate it.

### Phase 4 — Build the Updates screen

Create `feature/updates/UpdatesScreen.kt` with these states:

- loading;
- empty: “You’re up to date”;
- unread and read sections or a single time-ordered list with clear unread styling;
- error with retry;
- update row with title, concise body, relative time, unread indicator, and optional action affordance.

Rows should be text-led. Use a small status marker or badge only for unread state. Do not place a decorative icon above a second-line message. If an icon communicates event type, it must sit on the same row as the event title and have an accessible content description.

Selecting a row must mark it read and perform its action. Request-related rows should open the relevant request context. Non-request rows should open Profile or Authentication as appropriate.

**Exit condition:** the user can open Updates, distinguish unread items, select an update, reach the relevant destination, and return with predictable Back behavior.

### Phase 5 — Add the Updates navigation badge

Use the Material 3 navigation-bar badge pattern. Start with a small unread dot to avoid count-overflow and localization problems. Upgrade to a numeric badge only when the unread count is reliable and remains four characters or fewer.

Hide the badge when the Updates destination is selected and its visible items have been marked read. Add a content description such as “Updates, 3 unread” or “Updates, unread” for accessibility.

**Primary file:** `LifeLinkShell.kt`

**Exit condition:** unread state is visible before Updates is opened, disappears after the user has reviewed the destination, and does not obscure the navigation icon or label.

### Phase 6 — Consolidate Info under Profile

Move the current Info content into `Profile → About LifeLink`. Keep the current research-backed content, but present it as title-first rows with detail dialogs or secondary content for:

- How LifeLink works;
- Matching and privacy;
- Contact and consent;
- Safety guidance;
- What LifeLink is not;
- Legal information.

Rename the screen and navigation label to Profile. Keep theme and security controls inside Profile rather than creating additional top-level tabs.

**Primary file:** `LifeLinkShell.kt`; split into `feature/profile/ProfileScreen.kt` if the file becomes difficult to maintain.

**Exit condition:** no informational content requires a permanent Info destination, and profile/account controls remain discoverable without scrolling through legal copy first.

### Phase 7 — Notification routing and back-stack cleanup

When a notification includes a request identifier, route to the request context directly. When it does not include a request identifier, route to Updates. Ensure that opening a notification does not leave the user trapped in a stale start screen or an unrelated tab.

The first version may continue using the existing shell flags while the destination behavior is corrected. A later refactor may replace boolean overlays with Navigation Compose once the destination contract is stable.

**Primary files:**

- `MainActivity.kt`
- `LifeLinkShell.kt`
- `LifeLinkNotifications.kt`
- `LifeLinkFirebaseMessagingService.kt`

**Exit condition:** tapping a request notification opens the request context, tapping a general LifeLink notification opens Updates, and Back returns through a believable app path.

### Phase 8 — Documentation and validation

Update the navigation research, implementation status, and changelog after the feature is complete. Record the final event types, read-state rules, migration number, and notification-routing behavior.

Device validation should cover 320dp width, large font scale, dark mode, TalkBack labels, notification permission denied, no network, duplicate push events, stale request IDs, and a user with no updates. The emulator is not part of this planning step; it is a later execution-time validation gate.

## Acceptance criteria

The implementation is complete only when all of the following are true:

- The bottom navigation is exactly **Home, Requests, Updates, Profile**.
- The Info destination no longer exists as a top-level navigation item.
- Profile contains account, donor-profile, privacy/safety, appearance, security, and About LifeLink content.
- Updates has loading, empty, content, unread, and retry states.
- Updates rows can deep-link to request results, active request status, Profile, or Authentication.
- System notifications and in-app Updates are deduplicated by stable event ID.
- The Updates badge communicates unread state accessibly and clears after review.
- Request submission, authentication, location privacy, contact consent, donor coordinates, and existing lifecycle actions remain behaviorally unchanged.
- No sensitive patient, donor-location, or contact details appear in lock-screen-safe notification previews.
- The navigation and read-state rules are documented in the repository.

## Non-negotiable constraints

The implementation must not add a permanent Info tab back into the shell. It must not turn Updates into a general-purpose activity log. It must not expose exact donor coordinates. It must not mark events read merely because they were fetched. It must not use decorative icon blocks where the adjacent text already communicates the state. It must not change backend authorization or contact-disclosure rules as part of this navigation work.

## References

[1]: https://developer.android.com/design/ui/mobile/guides/home-screen/notifications "Android notifications design guidance"
[2]: https://m3.material.io/components/badges/guidelines "Material 3 badge guidelines"
[3]: https://developer.android.com/guide/navigation/principles "Android navigation principles"
[4]: ./RESEARCH_SUBMIT_AND_HOMEPAGE_2026-09-22.md "LifeLink submit reliability and homepage navigation research"
[5]: ./NAVIGATION_UX_RESEARCH_2026-09-20.md "LifeLink navigation and location UX research"
[6]: https://developer.android.com/training/app-links/create-deeplinks "Android deep linking guidance"

**Author:** Manus AI
