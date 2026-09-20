# LifeLink Transient Feedback UX Research

## Sources

- [Android Compose Snackbar guidance](https://developer.android.com/develop/ui/compose/components/snackbar) describes snackbars as brief bottom-of-screen feedback that does not interrupt the user, with optional actions and configurable duration.
- [Material 3 Snackbar guidelines](https://m3.material.io/components/snackbar/guidelines) recommends one snackbar at a time, short clear labels, optional single actions, and automatic dismissal for low-priority messages. Messages requiring a decision should use a dialog instead.
- [Android Toasts overview](https://developer.android.com/guide/topics/ui/notifiers/toasts) describes Toasts as small temporary popups, but recommends Snackbars for foreground app feedback because Snackbars can offer actions.

## Applied decision

LifeLink uses a `SnackbarHostState` at the request-flow Scaffold level. Success, error, offline, matching, manual-fallback, and contact-request feedback now appears as one transient surface above the bottom content/navigation area rather than as a permanent card in the scrolling page.

Errors and manual fallback messages retain an action: `Retry` or `Broadcast`. Short-lived success and offline messages auto-dismiss. This keeps the page compact while preserving a clear recovery path. Important state remains represented in the request/results screen itself; the Snackbar is a feedback signal, not the only source of status.

Dialogs remain reserved for high-consequence actions such as critical-request confirmation. Toasts are not used for in-app foreground feedback.
