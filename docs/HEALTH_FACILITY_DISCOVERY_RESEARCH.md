# LifeLink health-facility discovery

## Decision

LifeLink should start with OpenStreetMap data queried through the Overpass API rather than committing a large static Philippine dataset into the Android application. The first implementation is a backend endpoint, `GET /v1/health-facilities/nearby`, that returns nearby hospitals and clinics for an authenticated requester. The Android Retrofit client now has response models and a method for the endpoint; the request wizard can adopt the results in a follow-up UI slice without changing request submission semantics.

This choice keeps the initial release current and avoids shipping a stale national file. A static Philippine dataset remains useful as a later managed fallback or for offline coverage, but it would require a refresh pipeline, deduplication, licensing review, and a verified-facility policy. The available HOT/HDX Philippines export is OSM-derived and useful, but its own documentation warns that coverage reflects volunteer mapping activity and can be sparse in remote areas [1].

## Implemented contract

The production FastAPI service exposes the endpoint only to an authenticated, verified account. The request accepts latitude, longitude, a radius from greater than 0 through 25 km, and 1–50 results. The backend queries hospitals and clinics tagged in OSM, normalizes node and way/relation-center geometry, calculates straight-line distance, sorts nearest first, deduplicates repeated geometries, and returns OSM attribution and the provider timestamp when available.

The service uses a ten-minute in-process cache keyed to a rounded lookup cell. This reduces repeated Overpass traffic and avoids retaining an exact coordinate as the cache key. Provider failures return a generic 503 message rather than leaking upstream details. The endpoint is rate-limited to 20 calls per account per five minutes. The provider URL is configurable with `LIFELINK_OVERPASS_URL`, so production can move to an organization-managed or contracted provider without changing the mobile client.

## Why not call Overpass directly from Android?

The backend is the safer boundary. It keeps the provider URL, timeout, user-agent, caching, rate limits, and error handling in one place. It also prevents every handset from independently sending precise requester coordinates to a public query service. Overpass is intended for read-only, selective data queries and is not the right mechanism for downloading country-sized extracts [2]. OpenStreetMap guidance also asks applications to identify themselves with a valid user-agent and warns that service access can be withdrawn, especially for commercial or donation-supported services [3].

## Product and safety limits

OSM records are not proof that a facility is licensed, open, equipped for blood collection, or suitable for a particular emergency. The UI must label these results as “Map data” or “OSM result,” never as “verified hospital,” unless LifeLink has a separate verification source. The emergency request can continue with an approximate location when no facility is selected. LifeLink should display a reminder to call emergency services and confirm availability, address, and blood-bank instructions with the facility.

The next UI slice should load facilities after the requester chooses an approximate location, show a compact list sorted by distance, allow the user to select a facility or continue with approximate location, and preserve the existing facility ID/name fields only for records explicitly selected by the user. Do not expose donor exact locations through this endpoint.

## References

[1]: https://data.humdata.org/dataset/hotosm_phl_health_facilities "Health Facilities of Philippines dataset"
[2]: https://wiki.openstreetmap.org/wiki/Overpass_API "OpenStreetMap Overpass API documentation"
[3]: https://operations.osmfoundation.org/policies/api/ "OpenStreetMap API usage policy"
