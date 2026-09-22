# LifeLink UX Polish Research and Implementation Notes

**Date:** 22 September 2026
**Scope:** Native Android client, theory-led UI refinement without emulator execution

## Design direction

LifeLink should feel calm, direct, and operational. The interface should help a requester create or follow a request quickly, help a donor decide whether to respond, and keep privacy and safety guidance available without turning every screen into an instruction manual.

The governing rule for this pass is **content first, explanation on demand**. Titles, controls, and state should remain visible. Explanations should be brief when they affect the immediate decision, and otherwise move behind a clearly labeled help action.

## Research basis

Material 3 guidance recommends keeping text-field labels and error messages brief and actionable, and describes icon buttons as supplementary actions rather than decorative content. The Android navigation guidance supports a small set of stable top-level destinations, while the Compose navigation-bar guidance supports three to five destinations of comparable importance. These principles align with LifeLink’s four-destination shell: Home, Requests, Info, and Settings.

The existing repository research also found that emergency-oriented donor applications prioritize a small number of actions: create or manage a request, review status, inspect responses, and maintain donor availability. This supports a focused home surface rather than a collection of equally weighted cards.

References:

1. [Material 3 text fields](https://m3.material.io/components/text-fields/guidelines)
2. [Material 3 icon buttons](https://m3.material.io/components/icon-buttons/guidelines)
3. [Material 3 navigation bar](https://m3.material.io/components/navigation-bar)
4. [Android navigation principles](https://developer.android.com/guide/navigation/principles)
5. [Android location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)
6. [LifeLink submit and homepage research](./RESEARCH_SUBMIT_AND_HOMEPAGE_2026-09-22.md)
7. [LifeLink navigation and location research](./NAVIGATION_UX_RESEARCH_2026-09-20.md)

## Applied decisions

### Authentication

The authentication screen no longer opens with a decorative icon-and-title lockup. The current mode is expressed by a direct heading, while field-level validation remains close to the field that needs correction. This reduces vertical noise and makes the primary action easier to find.

### Home and onboarding

The home screen now uses one clear role-specific headline instead of a generic page title followed by a second headline. Request creation is the primary requester action, and donor mode leads with availability and the donor workspace. Decorative icons that were not carrying meaning were removed from the start and home surfaces.

### Requests and feedback

Request submission still uses the existing state machine, including validation, loading, retry, offline queueing, manual broadcast fallback, and contact-request feedback. The visual treatment is quieter: error, success, and informational surfaces use concise text hierarchy instead of an icon followed by a second-line explanation.

The active-request screen now separates the request identity, current status, response activity, and lifecycle actions. Completion remains the primary action; cancellation is visually secondary and requires confirmation.

### Info, profile, and settings

The main Info destination now presents compact title rows. Only topics that need context expose a question-mark help action; the explanation appears in a dialog rather than occupying the page by default. Profile removes always-visible guidance beneath the display-name field and exposes location privacy and safety/consent details only when requested.

The donor profile editor uses the same principle. Routine fields no longer carry permanent helper paragraphs. Profile visibility and location state remain explicit because they affect matching. The donor response card gives Accept primary emphasis and Decline quieter outline treatment.

### Location

The request location flow keeps current-location capture, map selection, manual coordinates, map retry, and recenter behavior. The location summary no longer uses a large icon above text because the text already communicates the state. Exact-coordinate privacy remains visible where it affects user trust.

## Deliberately not changed

No authentication, request, donor, or privacy behavior was removed. The existing submit repository path and error states remain intact. No background location behavior was introduced. No emulator or full APK verification run is part of this pass, per the requested theory-led workflow.

## Follow-up validation when convenient

When a device or emulator is available, review the polished surfaces at 320dp width, with large font scale, dark mode, keyboard open, approximate location permission, and an API failure during submit. The most important behavioral checks are: submit error recovery, active-request reopen from Requests, donor profile save, help-dialog dismissal, and cancellation confirmation.

**Author:** Manus AI
