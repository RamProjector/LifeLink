-- Version 8 schema from repository revision 457b7115f0ddd52749cf747493b64a0301d71283.
CREATE TABLE emergency_request_drafts (
  id TEXT NOT NULL,
  bloodType TEXT,
  units INTEGER NOT NULL,
  typeUnknown INTEGER NOT NULL,
  urgency TEXT NOT NULL,
  responseDeadline TEXT NOT NULL,
  note TEXT NOT NULL,
  facilityId TEXT,
  facilityName TEXT,
  facilityArea TEXT,
  facilityVerified INTEGER NOT NULL,
  requesterLatitude REAL,
  requesterLongitude REAL,
  locationPrecisionMeters INTEGER NOT NULL,
  contactMethod TEXT NOT NULL,
  genuineRequestConfirmed INTEGER NOT NULL,
  sharingConsentConfirmed INTEGER NOT NULL,
  aiMatchingEnabled INTEGER NOT NULL,
  updatedAtEpochMillis INTEGER NOT NULL,
  PRIMARY KEY(id)
);
CREATE TABLE pending_submissions (
  id TEXT NOT NULL,
  payloadJson TEXT NOT NULL,
  attempts INTEGER NOT NULL,
  lastError TEXT,
  createdAtEpochMillis INTEGER NOT NULL,
  PRIMARY KEY(id)
);
CREATE TABLE active_requests (
  requestId TEXT NOT NULL,
  ownerId TEXT NOT NULL,
  status TEXT NOT NULL,
  notificationsCreated INTEGER NOT NULL,
  matchesResponded INTEGER NOT NULL,
  reason TEXT,
  lastUpdatedEpochMillis INTEGER NOT NULL,
  PRIMARY KEY(requestId)
);
CREATE TABLE donor_profiles (
  donorId TEXT NOT NULL,
  displayName TEXT NOT NULL,
  bloodType TEXT,
  area TEXT NOT NULL,
  serviceRadiusKm INTEGER NOT NULL,
  availability TEXT NOT NULL,
  verified INTEGER NOT NULL,
  latitude REAL,
  longitude REAL,
  locationPrecisionMeters INTEGER NOT NULL,
  donorNote TEXT NOT NULL,
  preferredContactMethod TEXT NOT NULL,
  pauseReason TEXT,
  profileVisible INTEGER NOT NULL,
  PRIMARY KEY(donorId)
);
CREATE TABLE donor_requests (
  donorId TEXT NOT NULL,
  requestId TEXT NOT NULL,
  bloodType TEXT NOT NULL,
  units INTEGER NOT NULL,
  urgency TEXT NOT NULL,
  facilityName TEXT NOT NULL,
  area TEXT NOT NULL,
  distanceKm REAL NOT NULL,
  response TEXT,
  PRIMARY KEY(donorId, requestId)
);
CREATE TABLE updates (
  id TEXT NOT NULL,
  type TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  createdAtEpochMillis INTEGER NOT NULL,
  requestId TEXT,
  actionKey TEXT,
  isRead INTEGER NOT NULL,
  PRIMARY KEY(id)
);
