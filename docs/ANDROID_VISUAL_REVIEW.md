# Android visual review

The `.github/workflows/android-visual-review.yml` workflow runs the Android instrumentation suite on an Android 16 (API 36) Pixel 2 emulator for Android changes and supports manual dispatch. The app still compiles against the repository's Android 37 SDK. Java 21 is used to match the Android build workflow.

## What it records

The workflow runs `:app:connectedDebugAndroidTest`, then uploads screenshots, Android test reports, full logcat, and emulator boot logs as a GitHub Actions artifact (14-day retention). The workflow has a bounded emulator-readiness check so a stuck emulator fails with diagnostics rather than waiting indefinitely.

The Compose workflow visual test uses an in-memory synthetic donor-map fixture labelled as synthetic test areas. It does not create accounts, call the donor API, or write fixture data to Supabase or another production service. Build-time API and Supabase values are `.invalid` placeholders in this workflow; they are not production credentials.

## Map-render validation

The map screenshot is captured only after MapLibre reports that the visible map has finished loading and the synthetic approximate-pin annotations have been applied. The test checks the initial pin count, filters to available donors, verifies the count and marker-state description update, then clears the filter.

The base map style is loaded from OpenFreeMap over the network. That means a successful map-render test requires the hosted runner to reach the public style and tile endpoints. If tile loading fails or stalls, the test fails instead of accepting a blank background as a successful map. The uploaded logcat helps distinguish an app regression from an upstream/network failure. Backend loading, error, and empty-state behavior remains covered separately by deterministic Compose tests.

## Failure behavior and review

A failed instrumentation test fails the visual-review workflow after evidence collection. Review the uploaded screenshot and logcat before merging UI changes; a green APK compilation alone does not demonstrate that map tiles rendered correctly. The screenshot is visual evidence, not a pixel-perfect baseline comparison.

Hosted emulators may boot slowly. The workflow verifies that Android's boot property and input/settings services are ready before starting tests, disables UI animations, and collects diagnostics on failure.

## References

- [MapLibre Native Android: MapView](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-view/index.html)
- [MapLibre Native Android: map finished loading](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-map-view/-on-did-finish-loading-map-listener/on-did-finish-loading-map.html)
- [Android Developers: Compose testing APIs](https://developer.android.com/develop/ui/compose/testing/apis)
