# LifeLink Donor Device API

The donor API lets a second Android device register as a donor, control availability, receive eligible request cards, and respond to a request.

## Donor profile

```http
PUT /v1/donors/{donor_id}
```

Example body:

```json
{
  "donor_id": "donor-nova",
  "display_name": "Donor Nova",
  "blood_type": "O-",
  "latitude": 14.6466,
  "longitude": 121.0437,
  "service_radius_km": 15,
  "verified": true
}
```

A new profile starts `offline`.

## Availability

```http
PATCH /v1/donors/{donor_id}/availability
```

```json
{"availability": "available"}
```

Allowed values:

```text
available
paused
offline
```

## Donor request inbox

```http
GET /v1/donors/{donor_id}/requests
```

The response contains only request matches for that donor, including blood type, units, urgency, facility, area, distance, and current response state.

## Donor response

```http
POST /v1/donors/{donor_id}/requests/{request_id}/response
```

```json
{"response": "accepted"}
```

Allowed values:

```text
accepted
declined
arrived
```

The API rejects a donor response when that donor was not included in the matching result for the request.

## Coordinator status

```http
GET /v1/emergency-requests/{request_id}
```

The response includes `matches_responded`, which counts accepted or arrived donor responses.

## Security required before production

The current demo routes identify users by path IDs to make local development easy. Production must add authenticated identity from Firebase Authentication or another identity provider, verify that the caller owns the donor profile or coordinator request, and use HTTPS for all device traffic.
