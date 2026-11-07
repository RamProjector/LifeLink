# LifeLink MapLibre renderer decision

**Updated:** 2026-11-07  
**Scope:** Android MapLibre rendering on the existing Kotlin/Jetpack Compose/Gradle stack.

## Finding

The Android visual-review emulator (API 34, x86_64, SwiftShader) showed a renderer-specific mismatch:

- The Vulkan visual test reported a fully rendered map and successful tile operations, but its captured screenshot contained only the map attribution/recenter controls over the app background.
- The isolated OpenGL ES test rendered the basemap across the native map viewport.
- This is a CI emulator finding. It does not establish that Vulkan is broken on physical devices.

The dual-backend dependency already in use is `org.maplibre.gl:android-sdk-vulkan-opengl:13.7.1`; no dependency or stack change is required.

## Renderer selection

LifeLink now explicitly initializes MapLibre with `RenderingEngine.Type.OPENGL` before creating either the donor-map or location-picker `MapView`. Both entry points use the same helper, avoiding first-call-order differences in MapLibre's process-global renderer selection.

OpenGL ES is selected for the current app renderer because it produced the correct emulator viewport. Vulkan remains available in the dual-backend SDK, but should not be reselected for the production map until a physical-device comparison confirms it is safe.

MapLibre documents Vulkan as the Android SDK's default since v13 and OpenGL ES as a supported alternative. Vulkan may offer better performance on modern hardware; this CI fix prioritizes visible map correctness. A real-device performance/compatibility check remains desirable before reconsidering Vulkan.

## Evidence and assertions

- **Vulkan visual suite (previous baseline):** run [42233593047](https://github.com/RamProjector/LifeLink/actions/runs/42233593047), artifact [13512870642](https://github.com/RamProjector/LifeLink/actions/runs/42233593047/artifacts/13512870642).
  - 21 instrumentation tests: 0 failures, 0 errors, 0 skipped.
  - Vulkan tile listener: 3 network requests, 3 network loads, 5 parsed tiles, 0 tile errors.
  - Screenshot nevertheless showed the native map surface blank.
- **Isolated OpenGL viewport probe in the same run:**
  - 1 instrumentation test: 0 failures, 0 errors, 0 skipped.
  - OpenGL tile listener: 3 network requests, 3 network loads, 5 parsed tiles, 0 tile errors.
  - Screenshot coverage: 8/8 vertical bands textured (minimum gate: 6/8).
  - The ambient MapLibre tile cache is cleared in the disposable emulator before the probe, so fresh network-load assertions do not depend on the prior Vulkan suite.
- The test uses a public central-London basemap center only to exercise map tiles. No donor records, accounts, identities, exact donor coordinates, or backend writes are involved.
- Synthetic marker/filter tests remain separate and deterministic; they assert approximate area selection and filter results without network/backend data.

## Screenshot interpretation

The OpenGL screenshot proves tile imagery occupies the tested viewport; the color-band assertion does **not** prove text labels are visible. The Liberty style's road-label layers begin at higher zoom levels (for example, major road names around zoom 12.2 and minor road names around zoom 15). The map test currently uses zoom 12, so absence of street/POI labels in that particular screenshot is not by itself evidence of a glyph-rendering failure.

## Privacy and safety

- Donor pins remain approximate, opt-in area markers.
- Tile-operation callbacks expose operation categories only; they do not log tile coordinates or source identifiers.
- Renderer selection does not change donor visibility, location precision, authorization, API contracts, database schema, or map style.
- Do not use real donor coordinates or production accounts in visual fixtures.
