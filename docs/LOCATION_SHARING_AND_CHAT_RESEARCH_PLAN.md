# Exact Donor Location Sharing and In-App Chat

**Research date:** 29 September 2026

## Product decision

LifeLink should support an opt-in donor map, but not an open directory of exact donor coordinates. A donor may enable **Show my exact location to matched requesters**. The exact pin is returned only when all of these conditions hold:

- the donor has explicitly enabled location sharing;
- the requester owns the active emergency request;
- the donor is a match on that request and has accepted the contact request;
- the request is not cancelled, fulfilled, or expired; and
- the donor location is inside the freshness window.

The donor can disable sharing by saving the profile again. No background or always-on GPS tracking is required. The donor refreshes the saved location while the app is visible or through an explicit update action.

Chat is a persisted, request-scoped conversation between the requester and donor. It is available only after donor acceptance. Every read and write checks the authenticated user against the request/contact relationship; a guessed request or donor ID is never sufficient.

## Evidence

Android documents foreground location as the appropriate access model when a feature shares a current location for a defined interaction, including sharing a location from a messaging app. Android also says approximate permission must remain supported unless precise location is essential. [1]

Google Play describes foreground access as the most transparent approach and the preferred minimum scope. It gives coarse location and “people nearby” discovery as examples where exact coordinates are not required. [2]

OWASP recommends least privilege, deny-by-default behavior, permission checks on every request, and object-level authorization rather than trusting a user-controlled identifier. These rules apply directly to exact donor coordinates and private chat messages. [3] [4]

## Implementation slice

1. Add a donor opt-in flag and a versioned migration.
2. Add a requester-only endpoint for exact locations after an accepted contact relationship.
3. Add a request-scoped conversation and message persistence model with REST polling endpoints.
4. Add Android API/domain/repository support and expose the donor sharing preference in donor setup.
5. Replace the requester’s anonymous-only map path with an exact-pin map only when the server returns authorized pins.
6. Keep phone/email consent separate from location and chat; no automatic disclosure is added.

## Deferred work

Real-time WebSocket delivery, typing indicators, read receipts, message attachments, moderation tooling, physical-device testing, and background/live tracking are intentionally deferred. Polling is safer for the current MVP and avoids adding a second real-time infrastructure dependency before access-control tests and live two-account validation exist.

## References

[1]: https://developer.android.com/develop/sensors-and-location/location/permissions "Request location permissions | Android Developers"
[2]: https://support.google.com/googleplay/android-developer/answer/17033915?hl=en "Minimum Scope: Foreground Location Access and the Location Button | Google Play"
[3]: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html "Authorization Cheat Sheet | OWASP"
[4]: https://owasp.org/Top10/2021/A01_2021-Broken_Access_Control/ "A01:2021 Broken Access Control | OWASP"
