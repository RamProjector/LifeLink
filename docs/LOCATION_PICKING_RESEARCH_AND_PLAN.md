# LifeLink Location Picking Research and Improvement Plan

**Date:** 20 September 2026  
**Scope:** GPS acquisition, automatic map centering, MapLibre behavior, location-picker UI, privacy, and accessibility.

## Executive conclusion

The Manila preview was caused by an application fallback, not by the device GPS. When the request had no accepted coordinates, the UI substituted Manila coordinates. MapLibre then captured that initial target while its style was loading. When a real GPS result arrived, the asynchronous style callback could apply the old fallback afterward. The result was a valid map centered on the wrong place.

The immediate correction removes Manila from the requester's location path. An unselected request now has no geographic target. The MapLibre style callback reads the latest coordinates, and later GPS or manual changes update the marker and camera instead of being overwritten. The Android client also shows a visible accuracy confirmation after a successful current-location fix.

The broader recommendation is to treat location as a **quality-aware input**, rather than as latitude and longitude alone. The app should distinguish a fresh precise GPS fix, Android approximate GPS, a map-selected point, and manual coordinates. Each source has a different uncertainty and should be described honestly to the requester.

## Findings and recommendations

### 1. GPS acquisition should expose freshness and uncertainty

LifeLink already uses the appropriate foreground one-shot API, `FusedLocationProviderClient.getCurrentLocation`. A bounded request is preferable to continuous background tracking for this workflow. However, the current provider reduces every unsuccessful attempt to `null`, and it does not retain the age or source of a successful fix.

Android defines `Location.getAccuracy()` as an estimated horizontal uncertainty radius at approximately the 68th-percentile confidence level. It is not a guarantee. The app should use `hasAccuracy()`, preserve the raw value, validate the coordinates, and show the user whether the result is precise, approximate, stale, map-selected, or manual. A hard-coded `500 m` value should not be presented as a device measurement for map or manual input.

The next provider revision should return typed outcomes such as `Success`, `TimedOut`, `PermissionDenied`, `SettingsResolvable`, `SettingsUnavailable`, `ApiFailure`, and `Canceled`. The UI can then offer the correct recovery action instead of showing one generic failure. A single bounded retry is reasonable for a transient timeout, but permission and settings failures should not trigger an automatic retry loop.

The current 30-second cache age is a product decision. It is acceptable only if the matching policy accepts a location that old. For a stricter emergency workflow, a fresh-only attempt or a visible age label is safer. High accuracy should remain limited to the explicit foreground action because accuracy and battery consumption increase together. Background location is not needed merely to center this screen.

### 2. Permission behavior should be explicit

Android 12 and later can grant approximate access even when the app requests precise access. LifeLink should branch on the actual grant rather than treating coarse and fine access as equivalent. Approximate access can cover an area of roughly several square kilometers, while precise access is commonly much more accurate. This affects donor-distance claims and ranking precision.

Before opening the system permission dialog, the app should show a short, cancelable explanation tied to the user’s action: current location is used to find nearby eligible donors; the requester’s exact coordinates are not shown to donors; and map or manual selection remains available. The app should show distinct states for precise permission, approximate-only permission, denial, permanent denial, and disabled device location services.

The current automatic attempt on entering the location step should be reconsidered. The safer default is to let the user press **Use my current location**, while still allowing a clear in-context prompt when permission already exists. This avoids starting a high-power operation merely because a screen composed and gives the user a predictable action boundary.

### 3. MapLibre camera ownership needs a state model

MapLibre style loading is asynchronous. Map setup should therefore expose at least `Loading`, `Ready`, and `Error` states. A style failure should produce a retry action rather than a blank map. Style-dependent markers and listeners should be installed only after the style-ready callback.

The camera should have separate external intents and local gesture state. An external GPS update may center the map once, but ordinary recomposition must not continually reset the user’s pan or zoom. The latest pending target should be applied after style readiness. The map should preserve the user’s current zoom and orientation when only the target changes.

The picker should keep one marker and update it in place. Marker creation on every tap is unnecessary and can cause stale references. The privacy-safe donor summary should also update its center marker whenever the request coordinates change, or intentionally omit a precise marker if the privacy design requires that.

### 4. Recommended location-picker UI

The location screen should present a clear source and status. Before capture, it should say **Location not selected** rather than showing a default city. During acquisition it should say **Checking location settings** and **Finding your current location**. After success it should show the source, approximate accuracy, and age. Examples include **Current GPS fix · accuracy ±38 m · just now**, **Approximate device location**, **Map-selected point**, and **Manual approximate location**.

The map should include a recognizable in-map **Recenter on my location** control. Recenter should move the camera without silently replacing the request point. A separate **Use this point** action or a clear selected-point state should make it obvious when the user is choosing a map location. Tap-to-place is easier to discover than long-press-only interaction. The screen should preserve a non-map route with signed latitude and longitude fields, inline range errors, and an explicit apply button.

The review screen should contain a dedicated location card showing the selected source, approximate area, uncertainty, freshness, and privacy consequence. The user should be able to edit it before submission. Submission should remain unavailable until a valid source exists, and invalid manual entries should preserve the user’s text while showing a correction message.

### 5. Privacy and accessibility safeguards

LifeLink should request foreground permission only for the visible action. It should not request background access for map centering. The app should explain purpose, retention, sharing, security, and revocation in plain language. Exact coordinates may be used internally for matching, but donor-facing output should remain distance or area based.

Every important map control should be at least 48 dp where practical and have a meaningful content description. A map must not be the only way to inspect or change a location because spatial gestures are not equally available to TalkBack users or people with motor limitations. Asynchronous status changes should be exposed as accessibility status messages without stealing focus.

## Prioritized delivery plan

| Priority | Change | Reason |
|---|---|---|
| 0 | Remove implicit Manila coordinates and require an accepted location before submission. | Prevents incorrect emergency matching. |
| 0 | Fix MapLibre style-load and recomposition camera races. | Prevents a real GPS result from being overwritten. |
| 1 | Add typed GPS outcomes, permission precision, age, and source. | Makes uncertainty visible and actionable. |
| 1 | Add an in-flight guard, cancellation, settings re-check, and one bounded retry. | Prevents overlapping or stale requests. |
| 2 | Add in-map recenter, explicit selected-point confirmation, and map loading/error/retry states. | Makes the map behavior understandable. |
| 2 | Improve manual-coordinate validation and review-screen location summary. | Provides an accessible non-map route and safer submission. |
| 3 | Add real-device tests for approximate permission, stale fixes, style failure, process recreation, and GPS arrival during style loading. | Protects the behavior that caused the reported problem. |

## Current implementation change

The no-Manila fallback and stale MapLibre camera behavior are compile-verified locally. The fix is ready to be committed and built into the next APK after the research-backed documentation is synchronized.

## References

[1]: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient "FusedLocationProviderClient"
[2]: https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest "CurrentLocationRequest"
[3]: https://developer.android.com/develop/sensors-and-location/location/permissions "Request location permissions"
[4]: https://developer.android.com/develop/sensors-and-location/location/permissions/runtime "Request runtime location permissions"
[5]: https://developer.android.com/reference/android/location/Location "Android Location reference"
[6]: https://developer.android.com/develop/sensors-and-location/location/change-location-settings "Change location settings"
[7]: https://developer.android.com/develop/sensors-and-location/location/battery "Location and battery usage"
[8]: https://developer.android.com/privacy-and-security/minimize-permission-requests "Minimize permission requests"
[9]: https://www.maplibre.org/maplibre-native/android/examples/getting-started/ "MapLibre Native Android getting started"
[10]: https://maplibre.org/maplibre-native/android/api/-map-libre%20native%20-android/org.maplibre.android.maps/-map-view/index.html "MapLibre MapView API"
[11]: https://maplibre.org/maplibre-native/android/api/-map-libre%20native%20-android/org.maplibre.android.maps/-map-libre-map/set-style.html "MapLibre setStyle API"
[12]: https://www.maplibre.org/maplibre-native/android/examples/annotations/marker-annotations/ "MapLibre marker annotations"
[13]: https://developers.google.com/maps/documentation/android-sdk/location "Current-location map affordance"
[14]: https://www.w3.org/TR/geolocation/ "W3C Geolocation Recommendation"
[15]: https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html "WCAG 2.2 Target Size"
[16]: https://developer.android.com/guide/topics/ui/accessibility/apps "Android accessibility for apps"
[17]: https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html "WCAG 2.2 Status Messages"
[18]: https://www.w3.org/WAI/WCAG21/Understanding/error-prevention-all.html "WCAG Error Prevention"
