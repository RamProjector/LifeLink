# LifeLink Navigation and Location UX Research

## Research basis

The implementation review used official Android and Material guidance:

- [Android navigation principles](https://developer.android.com/guide/navigation/principles) states that navigation is represented as a destination stack, Back pops the current destination, and Up/Back should behave consistently within the app task.
- [Material 3 navigation bar](https://m3.material.io/components/navigation-bar) recommends a navigation bar for compact screens with **3–5 destinations of equal importance**, and says destinations should remain consistent across app screens.
- [Android location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions) recommends foreground, session-based access for one-time location use, and requires the app to remain useful when users grant approximate rather than precise location.
- [MapLibre Native Android API](https://maplibre.org/maplibre-native/android/api/) provides the camera, map, marker, and location APIs needed for an in-app map picker without background tracking.

## Findings applied to LifeLink

LifeLink’s prior shell had three persistent destinations while the request wizard was rendered as a boolean overlay. That made Back behavior ambiguous and made submitted requests difficult to rediscover. A persistent Requests destination is appropriate because Material 3 permits four destinations and the request list/status is a core user task, not a secondary setting.

The request wizard should behave as a stack: Back moves through wizard steps; Back from the first step exits to the Requests destination; Back from donor results exits to Requests. This follows Android’s stack-based navigation principle and avoids trapping the user in a modal-like flow.

The location picker should distinguish three states: no location selected, user-selected approximate location, and current-device location. A current-location action should explicitly recenter the camera after the location fix is received. The app should continue to support approximate permission and manual pin placement, while avoiding background tracking.

## Implementation scope

The next implementation slice should:

1. Keep four stable dashboard destinations: Home, Requests, Learn, and Profile.
2. Add a Requests landing page with empty, active, matching, and reopen-results states.
3. Route the request wizard’s Back action through its steps and back to Requests at the root.
4. Keep donor results reopenable from Requests after submission.
5. Make current-location capture visibly recenter the map and expose a clear recenter action.
6. Keep exact coordinates private and avoid background location permissions.

## Follow-up candidates

A later slice should replace boolean overlays with a formal Navigation Compose back stack, add explicit loading/error/recenter controls to the map, and show location source/freshness/accuracy on the review screen.
