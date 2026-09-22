# Donor Setup Research and Implementation

**Date:** 2026-09-23
**Scope:** Android donor onboarding, blood-type selection, location preview, availability, and request visibility.

## Conclusion

The donor experience should use staged setup rather than treating every field as a generic profile save. A donor is **incomplete** until the account has a usable display name, one of the eight supported ABO/Rh blood types, an approximate location, and a service radius between 1 and 100 km. Availability is a separate, intentional choice. Requests must remain hidden until the setup is complete.

This follows the Material self-select and quickstart onboarding models, which support a short first-run path, and progressive or staged disclosure, which keeps secondary choices out of the first step while making the route to completion clear [1] [2]. Material's single-select controls are appropriate for availability because they communicate that exactly one state is active [3].

## Findings

The previous implementation had four structural problems. First, `SetAvailability` called `saveProfile`, so changing status looked like a profile save and could fail because unrelated profile fields were incomplete. Second, the blood-type chips were rendered in one horizontal row, allowing options to be clipped or appear incomplete on narrow screens. Third, the donor map used a separate visual path with a default location and did not clearly distinguish an unselected map from a selected location. Fourth, the client refreshed and displayed donor requests before setup completion.

The research also confirms that a complete profile is not medical clearance. Blood type should be a validated enum used for compatibility matching, while eligibility remains a separate health-screening and blood-bank decision. The implementation therefore requires a valid selected type for matching but does not claim that the donor is medically eligible [4] [5].

## Implemented contract

The Android profile now exposes `isSetupComplete`. It is true only when the display name, blood type, approximate latitude and longitude, and service radius are valid. The request list is rendered only when this value is true. Incomplete profiles instead receive an explicit `Finish donor setup` state that names the missing requirements.

Availability now uses a dedicated repository operation. Selecting Available, Paused, or Offline updates only availability and no longer submits the entire profile. The server repeats the setup check before allowing availability changes and returns an empty donor inbox for incomplete profiles. This prevents stale local requests or a forged client from bypassing the UI gate.

The eight ABO/Rh choices are wrapped into two rows so all options remain visible. The save action requires a selected blood type and a captured location. Existing local Room storage does not need a schema migration because setup completion is derived from existing columns rather than persisted as a new field.

## Deferred safety boundary

The current feature is donor matching, not medical eligibility screening. A future health-screening step should be staged separately and configured by donation type, collection center, jurisdiction, and effective date. It should produce states such as **ready for screening**, **needs center review**, or **temporarily deferred**, rather than asserting eligibility from profile completion alone.

## References

[1]: https://developer.android.com/design/ui/mobile/guides/patterns/onboarding "Android onboarding patterns"
[2]: https://www.nngroup.com/articles/progressive-disclosure/ "Progressive Disclosure"
[3]: https://developer.android.com/develop/ui/compose/components/segmented-button "Segmented buttons in Jetpack Compose"
[4]: https://www.redcrossblood.org/donate-blood/blood-types.html "American Red Cross blood types"
[5]: https://www.who.int/publications/i/item/9789241548519 "WHO blood donor selection guidelines"
