# Submit Reliability and Homepage Navigation Research

**Date:** 22 September 2026

## Findings

The American Red Cross donor app emphasizes a small set of actionable tasks: manage appointments, see donation history and health information, and follow the donation journey [1]. This supports a LifeLink home screen that prioritizes the current request state and one primary emergency action instead of several equal-purpose cards.

The BLOODR application separates requester capabilities into sending a request, request history, viewing appointments, and viewing a request feed. It also uses authenticated clinic-mediated communication rather than exposing an unrestricted donor directory [2]. LifeLink should therefore make **Requests** a persistent top-level destination and treat request creation, results, and live status as subpages in that destination.

Android’s official Compose guidance recommends bottom navigation for three to five destinations of equal importance and assigns each item a single destination [3]. LifeLink’s current four destinations are Home, Requests, Info, and Settings. The request wizard is opened from Requests, while the homepage action first takes the user to the Requests destination so the user always has a stable way back.

FastAPI’s official guidance distinguishes client errors in the 400 range from unexpected server failures in the 500 range. It recommends raising `HTTPException` for expected API errors and using custom exception handlers for consistent error responses [4]. LifeLink now validates cached facility IDs before persistence and translates database failures into structured `409` or `503` responses. The Android client displays the returned `detail` message instead of hiding it behind a generic submit error.

## Implementation implications

A failed submit must not depend on a cached facility still existing in the hosted database. LifeLink preserves the selected coordinates and falls back to an approximate requester location when the facility foreign key is stale. The API logs the route and database failure while returning a safe user-facing message.

The homepage now uses a focused emergency card, an active-request card, and a clear explanation of what happens after submission. The Requests destination includes matching results, request history, active status, and request creation. Exact donor locations remain private, and contact details are shown only after donor acceptance.

## References

[1]: https://www.redcrossblood.org/blood-donor-app.html "American Red Cross Blood Donor App"
[2]: https://pmc.ncbi.nlm.nih.gov/articles/PMC5682362/ "BLOODR: blood donor and requester mobile application"
[3]: https://developer.android.com/develop/ui/compose/components/navigation-bar "Android Compose Navigation Bar"
[4]: https://fastapi.tiangolo.com/tutorial/handling-errors/ "FastAPI Handling Errors"

**Author:** Manus AI
