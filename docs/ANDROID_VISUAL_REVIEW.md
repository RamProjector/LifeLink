# Android visual review

LifeLink's visual review runs real Compose instrumentation tests on an Android emulator. It covers navigation and a deterministic donor-map scenario, and uploads screenshots, Android test reports, and logcat for review.

## Emulator and workflow

The workflow is `.github/workflows/android-visual-review.yml`. It runs on relevant Android UI/test changes, on pushes to `main`, and on manual dispatch. It uses a Pixel 8 emulator with Android 17 (API 37), Java 21, animations disabled, and the existing Gradle/Jetpack Compose/MapLibre stack.

The job requires KVM and fails visibly if the runner cannot run the emulator. It does not silently skip instrumentation and then report success. Emulator logs and test artifacts are uploaded even when the tests fail.

## Synthetic map scenario

`LifeLinkWorkflowVisualTest` injects two fixed `DonorMapArea` fixtures directly into `PrivacyUiState`. The callback for `PrivacyAction.LoadMap` is a no-op in this visual test; it does not log in, call the live API, seed a database, or create donor accounts. Fixture labels explicitly identify the areas as synthetic. Coordinates are only map-test inputs and are not sourced from donors.

The map walkthrough asserts that:

- the Map destination and map controls are present;
- MapLibre reaches its fully-rendered callback before the screenshot is captured;
- both synthetic areas appear in the count;
- blood-type and availability filters reduce the count as expected;
- clearing filters restores the full synthetic result count; and
- the map remains rendered while filters change.

Screenshots are captured for the unfiltered and filtered map, along with the other core screens. A timeout or map load failure makes the instrumentation test fail; the workflow collects the emulator logs and report rather than treating an unrendered beige map as success.

## Scope and limitations

The screenshots are evidence for human visual review, not pixel-baseline comparisons. A passing map-render test confirms that MapLibre reported a fully rendered frame on the CI emulator; it does not prove that live donor data is correct, that a real requester/donor exchange works end-to-end, or that a physical device's GPS is enabled. Those require separate integration and device checks.

The test uses the configured public OpenFreeMap style and requires the CI emulator to reach the style/tile endpoints. If network access or the map provider fails, the test should report the failure with logs; it must not substitute real donor data or silently skip map validation.

## Screenshot reliability

`VisualEvidence.capture()` retries screenshots, prefers the app's external files directory, and falls back to app-private storage. The workflow retrieves evidence from either location and uploads it with connected-test reports and logcat. A screenshot is supplementary evidence; required UI and rendering assertions determine test success.
