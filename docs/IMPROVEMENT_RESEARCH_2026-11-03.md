# LifeLink Improvement Research

**Review date:** 2026-11-03  
**Baseline:** `main` at `93403ac` after PRs #88, #92, #94, and #95 were merged.

## Current health and immediate blocker

The repository is clean and has one open pull request, #91, for the Kotlin Compose plugin upgrade. That PR remains open because its Android validation run reports 26 failing unit tests out of 105. The failures are concentrated in tests using Robolectric and surface as `MavenArtifactFetcher` followed by `HttpURLConnection` I/O failures. This is not evidence of a Kotlin source incompatibility yet, so the upgrade should remain blocked until the test environment is deterministic and the suite passes.

The default-branch `Validate cloud package` run linked to critical issue #155 failed for the same broad pattern: backend, backend lint, and Docker passed, while the Android unit-test task failed. The first useful engineering action is to distinguish a hosted Maven/Robolectric fetch failure from a real application assertion. The existing canonical Maven Central setting is helpful, but the workflow should eventually make the Robolectric runtime artifact available through a deterministic cache or a controlled retry that emits the requested artifact and HTTP status.

## Highest-value next improvements

### 1. Make Android unit validation deterministic

Add a small CI hardening change around Robolectric runtime dependencies. Prefer prefetching and caching the exact `android-all-instrumented` artifact used by the configured SDK before `testDebugUnitTest`, or pinning the Robolectric dependency repository and logging the artifact URL/status when the fetch fails. A bounded retry is acceptable only for a classified network fetch failure; it must not hide ordinary test assertion failures. Add a CI diagnostic step that uploads the test result XML and the Robolectric cache directory on failure.

This should be done before merging #91. A green compile or fake-backend UI APK is not enough when the unit-test suite is failing during test initialization.

### 2. Finish the requester-to-donor lifecycle

The existing project documents request history and the accepted-donor contact flow as the next product slice. Implement request history with stable statuses for active, completed, cancelled, and expired requests. Then add an accepted-donor surface that distinguishes acceptance, contact disclosure, meeting arrangement, fulfilment, and cancellation. Keep the server as the authorization boundary and reject contact disclosure after cancellation or expiry.

This work is more valuable than adding another role or live-tracking feature because it completes the workflow already represented by the API and current screens.

### 3. Make location states explicit and privacy-safe

Android’s guidance distinguishes foreground versus background access and approximate versus precise location. LifeLink should show the location source and freshness, explain why precise foreground access is requested, and continue to provide a safe approximate/manual fallback when the user grants approximate access or denies precise access. The UI should distinguish permission denied, GPS unavailable, map/tile failure, empty donor results, and API failure rather than presenting all of them as an empty map.

Do not add background tracking. The current product boundary—foreground, explicit, revocable location use—is the safer fit for the matching workflow.

### 4. Add a privacy and data-control surface in the app

OWASP MASVS-PRIVACY defines four useful controls for this product: minimize sensitive data access, prevent unnecessary identification, be transparent about collection and use, and give users control over their data. LifeLink should expose a plain-language privacy notice from Profile, show what the approximate donor map reveals, explain when contact details can be shared, and provide working controls for visibility, availability, revocation, sign-out, and account/data deletion where supported by the deployed service.

The notice must describe actual behavior. It should not promise deletion, live tracking controls, or contact methods that are not implemented and owned operationally.

### 5. Reduce screen complexity through state ownership

The largest Android files are `EmergencyRequestScreen.kt`, `LifeLinkShell.kt`, `DonorScreen.kt`, and `EmergencyRequestViewModel.kt`. Android’s architecture guidance recommends repository boundaries, unidirectional data flow, lifecycle-aware state collection, and screen-level ViewModels that expose a single UI state. The next refactor should extract reusable state holders and split screen-specific sections without changing behavior. Add state-transition tests before moving code.

This is a maintainability improvement, not a reason to introduce a new dependency-injection framework or rewrite the navigation stack.

### 6. Strengthen accessibility as a release gate

Compose provides semantics, traversal ordering, scalable-content guidance, inspection tools, and accessibility testing. Add a focused accessibility test pass for authentication, request submission, donor map filters, contact sharing, and terminal request states. Verify meaningful labels, state announcements, logical TalkBack order, large-font layout, and non-color-only status indicators. The project already has semantic tags in visual tests, so this can build on existing test infrastructure.

## Suggested implementation order

1. Stabilize Robolectric/Android CI and resolve or close #91 based on evidence.
2. Implement request history and accepted-donor lifecycle with backend authorization tests.
3. Add explicit location source/freshness/error states and map retry/recenter behavior.
4. Add the in-app privacy/data-control surface and account deletion readiness checklist.
5. Refactor the largest screens behind characterization tests.
6. Add accessibility checks and large-font/ TalkBack-focused visual coverage.

## Research sources

[1]: https://developer.android.com/topic/architecture/recommendations "Android architecture recommendations"
[2]: https://developer.android.com/develop/ui/compose/accessibility "Accessibility in Jetpack Compose"
[3]: https://developer.android.com/develop/sensors-and-location/location/permissions "Android location permissions"
[4]: https://mas.owasp.org/MASVS/12-MASVS-PRIVACY/ "OWASP MASVS privacy controls"
[5]: https://fastapi.tiangolo.com/tutorial/security/ "FastAPI security"
[6]: https://fastapi.tiangolo.com/deployment/ "FastAPI deployment"
