# LifeLink GPS Research and Implementation

**Date:** 19 September 2026  
**Scope:** Android requester and donor location capture, map centering, accuracy, privacy, and battery behavior.

## Conclusion

LifeLink already uses the correct general architecture for this MVP: location is requested only from visible Android screens, the fused location provider supplies a one-shot fix, and users retain a map and manual-coordinate fallback. The highest-value improvements were to make that one-shot request bounded and cancellable, prefer a fresh high-accuracy fix for an explicit user action, and automatically center the requester map when the app already has permission. These changes are now implemented without adding background location access.

## Findings from current Android guidance

Android distinguishes foreground location from background location. A feature that reads a location while its screen is visible, such as sharing a current location once, should use foreground permissions. LifeLink does not need `ACCESS_BACKGROUND_LOCATION` because it does not continuously track requesters or donors. Android 12 and later may provide approximate location even when an app requests fine location, so the app must continue to work when only coarse permission is granted.[1]

Google Play services recommends `FusedLocationProviderClient.getCurrentLocation()` for a single current fix and `requestLocationUpdates()` only for continuous tracking. A current-location request may use a recent cached location or compute a new fix, and it can return `null` if no fix is available before the request times out. A cached result should therefore have an explicit acceptable age.[2]

Android also recommends checking whether the device's location settings can satisfy the requested accuracy and power level. If settings are not sufficient, the app can show a system resolution dialog rather than silently failing. This is a useful next step for device-level resilience, especially when location services are disabled.[3]

Location requests should have a bounded duration and should stop when no longer needed. This protects battery life when a request fails or a lifecycle callback is missed.[4]

## Changes implemented in this update

`LocationProvider.currentLocation()` now uses a `CurrentLocationRequest` with high-accuracy priority, a 30-second maximum age for cached fixes, and a 15-second timeout. The coroutine cancellation path cancels the underlying Google Play services request. Invalid or non-positive accuracy values are rejected instead of being converted into an arbitrary precision value.

The requester location screen now performs a one-shot location lookup when it becomes visible if the app already has coarse or fine location permission and the draft has no saved coordinates. The resulting location updates the existing draft and causes the map pin to move to the user's position. The app does not trigger a surprise permission dialog merely because the map was opened. Users without permission can still use the existing location button, map pin, or latitude and longitude fields.

The requester flow now checks the device's location settings before each automatic or button-triggered fix. If Android reports that the required settings can be resolved, LifeLink opens the system resolution dialog and retries after the user accepts. If the user declines or the device cannot resolve the settings, the screen explains that map and manual-coordinate selection remain available.

The update deliberately keeps the manual fallback. Approximate location is sufficient for LifeLink's donor matching purpose, and Android may intentionally obfuscate a coarse location. Exact coordinates remain visible only to the user and are not exposed in donor-facing data.

## Recommended next iteration

The next GPS improvement should apply the same `SettingsClient` resolution flow to the donor profile capture action. The UI should also distinguish permission denied, location services disabled, timeout, and no-fix outcomes so that users know whether to enable settings, wait briefly, or choose the map/manual fallback.

## Donor map privacy boundary

The matching response exposes distance and travel time but not donor coordinates. The requester flow now offers an optional map summary that draws the request location and anonymous five-kilometer and ten-kilometer distance bands. It shows donor counts per band, while the donor list remains the only place where a requester can review and select a donor. This preserves the geographic value of GPS without allowing a requester to infer an individual donor's home, workplace, or exact position.

The map intentionally does not place one marker per donor. A future map expansion should retain an anonymity threshold and avoid exposing names, exact coordinates, or precision that could be combined across repeated searches to triangulate a donor.

For donors, location should remain an explicit profile action rather than continuous tracking. A donor's saved location should have a visible freshness timestamp and an option to clear it. Matching should treat an old location as unavailable or lower-confidence instead of implying that the donor is currently nearby.

## Validation boundary

This sandbox does not currently expose the Android SDK needed for a local Gradle build. The code should be validated by the existing GitHub Actions Android workflow, followed by device testing with approximate-only permission, precise permission, disabled location services, denied permission, a cold GPS start, and a stale cached location. No background-location permission or continuous update loop was added.

## References

[1]: https://developer.android.com/develop/sensors-and-location/location/permissions "Request location permissions"
[2]: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient "FusedLocationProviderClient reference"
[3]: https://developer.android.com/develop/sensors-and-location/location/change-location-settings "Change location settings"
[4]: https://developer.android.com/develop/sensors-and-location/location/battery/optimize "Optimize location use for battery life"
