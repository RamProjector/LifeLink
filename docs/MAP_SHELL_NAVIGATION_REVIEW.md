# Map shell navigation review

## Navigation acceptance criteria

Carried forward from the closed notification-routing and UI-polish work:

- A map shortcut must reach the requested destination or provide clear, accessible failure feedback; it must not silently do nothing.
- Notifications opened from the embedded map are temporary. Back must restore the map and its selected navigation state.
- Profile navigation must use the existing profile/settings destination and must not create a second profile route.
- Keep standalone map flows unchanged; embedded-shell shortcuts must not leak into standalone screens.
- Preserve a clear return path after destination loading errors, process recreation, and back navigation.
- Verify keyboard/screen-reader labels, shortcut-menu dismissal, and restoration at supported window sizes.

## Scope boundary

These are acceptance criteria for the focused map-shell shortcut change. They do not authorize changes to donor matching, notification delivery, backend APIs, or the standalone map experience.
