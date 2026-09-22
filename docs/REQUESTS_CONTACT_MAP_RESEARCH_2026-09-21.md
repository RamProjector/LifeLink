# LifeLink Requests, Contacts, and Map UX Research

## Sources

- [Material 3 lists](https://m3.material.io/components/lists/overview) recommends short, scannable list items with consistent labels, supporting text, and actions.
- [Compose pull-to-refresh](https://developer.android.com/develop/ui/compose/components/pull-to-refresh) recommends an explicit refresh state and a single refresh indicator for a scrollable collection.
- [Material 3 progress indicators](https://m3.material.io/components/progress-indicators/guidelines) recommends an indeterminate loading indicator for unknown short waits, placed in the container being loaded, and a retry/error state when loading cannot complete.
- [Material empty states](https://m2.material.io/design/communication/empty-states.html) recommends explaining what an empty screen represents and providing contextual starter guidance rather than presenting an unexplained blank screen.

## Applied decisions

The Requests tab now loads an authenticated, requester-scoped history list. The history response contains request ID, status, blood type, units, urgency, and created time only; it intentionally excludes coordinates, notes, donor identity, and contact details. The UI uses concise cards and a refresh action, with the existing active-request and results entry points remaining primary.

Accepted contacts are shown as separate cards. A donor email is displayed and made actionable only when the backend reports an accepted contact with a non-empty email. Pending or declined contacts explain why details are not visible.

The requester map now has an explicit loading overlay and an eight-second retry state. Retry recreates the MapLibre picker and preserves manual location entry as a fallback. Current-location capture continues to use foreground permission only and recentering remains controlled by the request token.
