# Android visual review

LifeLink has an emulator-based visual-review workflow for the signed-out authentication screen and the donor-map workflow. The map scenario uses only a local synthetic fixture; it does not sign in, fetch live donor records, or write test data to the backend.

## What it records

The workflow builds the debug app and instrumentation APK, launches an API 34 Pixel 2 emulator with animations disabled, runs Compose instrumentation tests, and uploads screenshots, Android test reports, and emulator logs as a GitHub Actions artifact.

The auth test captures `auth-screen.png` and asserts that the current auth screen visibly contains:

- `Welcome back. Sign in to continue.`
- `Email address`
- `Forgot password?`

These assertions catch missing content, broken composition, and major copy/layout regressions. The screenshot is the visual evidence used for design review; it is not yet compared against a frozen baseline.

The donor-map walkthrough captures `lifelink-phone-light-workflow-donor-map.png` with two invented approximate test areas, then exercises the List view. It waits for MapLibre's fully-rendered callback before capturing the map. A map that loads only the style JSON but never renders its visible tiles must not be counted as a successful visual run. The donor navigation test also captures `lifelink-donor-workspace-after-open.png` before asserting the workspace title, so navigation regressions leave actionable screenshot evidence.

## Failure behavior

A failed visual test fails the workflow after the reports are collected. Screenshot capture is also a required assertion: a missing or unwritable PNG fails the test instead of being logged as a non-fatal warning. The workflow separately verifies that home, fully rendered donor-map, and requests screenshots exist and are non-empty; artifact upload uses `if-no-files-found: error`. The Critical bug bot watches this workflow on the default branch and opens a deduplicated critical issue containing the workflow link and failed job information.

## Why the first scope is intentionally small

Live authenticated donor and requester data remains out of scope for screenshots. The map walkthrough injects deterministic fixture state directly into Compose and uses invented area labels without donor IDs; the fixture is never written to a backend. Other account-specific workflows should continue to use deterministic fixtures or isolated test accounts before being added to screenshot capture.

Intentional design changes should be reviewed from the uploaded artifact before establishing or updating a baseline. The bot detects visual/test failures; it does not make aesthetic decisions autonomously.

## Emulator acceleration fallback

The workflow prefers KVM because it is much faster. If a runner does not expose usable `/dev/kvm`, it now opts into the Android Emulator's slower software CPU mode and still runs the same instrumentation suite. This fallback may take longer; it must not silently skip tests or turn missing screenshot evidence into a pass. The mode is based on the official [Android Emulator command-line documentation](https://developer.android.com/studio/run/emulator-commandline#accel).

## Screenshot reliability

Hosted emulators are slow, and two things were seen failing transiently right after boot: `Context.getExternalFilesDir()` returned `null` while the emulated shared-storage volume was still mounting (the screenshot then targeted a relative path on a read-only filesystem), and the first `UiAutomation` screenshot returned `null`. `VisualEvidence.capture()` retries and writes into app-private storage. The workflow extracts those PNGs with `adb exec-out run-as` before the emulator is torn down. If a required screenshot cannot be saved, the instrumentation test fails.
