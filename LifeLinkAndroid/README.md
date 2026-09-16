# LifeLink Android Source

Native Android/Kotlin source for the LifeLink emergency blood-request flow.

## Build and validate

The repository includes a Gradle wrapper. Configure `ANDROID_HOME` to an Android SDK containing API 35 and build tools 35.0.0, then run:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

This source was verified with Gradle 8.10.2, Android API 35, and Java 21. The debug APK was produced and verified with `apksigner` using APK Signature Scheme v2. Headless emulator screenshots still require host hardware acceleration such as `/dev/kvm`.

## Included now

- Kotlin and Jetpack Compose
- Material 3 UI theme
- Android SDK 24 minimum
- App shell with Home, Learn, and Profile tabs
- Multi-step emergency request flow
- Blood type and units selection
- Urgency and response deadline
- Verified facility selection
- Location permission request that does not block manual selection
- Contact method and consent
- Review-before-submit
- Critical request confirmation sheet
- ViewModel with StateFlow UI state
- Retrofit API contract for the FastAPI backend
- Configurable API base URL through `BuildConfig.LIFELINK_API_BASE_URL`
- Backend status handling for matching and manual-broadcast fallback
- Explicit manual-broadcast action from the UI
- Persistent active-request tracking in Room
- Active request status screen with automatic 30-second polling
- Manual refresh control for active request status
- Request cancellation with confirmation and terminal state
- Room-based offline draft persistence
- Pending submission queue
- WorkManager network retry worker
- Android emergency notification channel
- Firebase Messaging service hook and token registration seam
- Android 13+ runtime notification permission flow
- Release-safe bearer-token interceptor seam with debug-only HTTP logging
- Resilient active-request and donor polling with transient-error handling
- Keyboard-safe emergency actions and explicit checkbox/radio accessibility semantics
- Scoped emulator cleartext networking for `10.0.2.2` only
- Donor mode with profile, availability, request inbox, and accept/decline actions
- Donor Room cache with Retrofit synchronization and 30-second inbox refresh
- Error, retry, matching, fallback, cancelled, and offline states
- Unit tests

## Backend endpoints used by Android

```text
POST /v1/emergency-requests
GET  /v1/emergency-requests/{request_id}
POST /v1/emergency-requests/{request_id}/manual-broadcast
POST /v1/emergency-requests/{request_id}/cancel
PUT  /v1/donors/{donor_id}
PATCH /v1/donors/{donor_id}/availability
GET  /v1/donors/{donor_id}/requests
POST /v1/donors/{donor_id}/requests/{request_id}/response
```

The Android client uses the local draft ID as the idempotency key. If the API returns `manual_broadcast`, the app displays the reason and requires the coordinator to explicitly send a manual broadcast. Active requests poll the status endpoint every 30 seconds while they are not terminal. Coordinators can cancel an active request after a confirmation dialog; cancellation cannot be undone.

## Backend configuration

The emulator uses the default API URL:

```text
http://10.0.2.2:8000/
```

Cleartext traffic is scoped to `10.0.2.2` in `res/xml/network_security_config.xml` for local emulator development. For a physical device or production backend, use HTTPS and pass the API URL without editing source code:

```bash
./gradlew assembleDebug -PlifelinkApiBaseUrl=https://your-api.example.com/
```

The same value can be supplied through the `LIFELINK_API_BASE_URL` environment variable. The Gradle property takes precedence. The default remains `http://10.0.2.2:8000/` for the Android emulator and local demo API.

## Offline behavior

- Draft edits are saved to Room after a short debounce.
- A network failure saves the request into `pending_submissions`.
- WorkManager waits for a connected network before retrying.
- Successful retry removes the pending item.
- The UI shows an explicit offline-queued state rather than pretending the request was sent.

## Notifications

The app creates an `Emergency blood requests` notification channel at startup, declares notification permission in the manifest, requests notification permission on Android 13+, and registers a `FirebaseMessagingService` hook. FCM token registration and remote notification delivery still require the Firebase project configuration and credentials.

## Build

Open the folder in Android Studio Ladybug or newer, allow Gradle sync, and run:

```bash
./gradlew assembleDebug
./gradlew test
```

The source archive includes the Gradle wrapper. Android Studio can sync the project directly, or the wrapper commands above can be run from a terminal.

## Still requiring project-specific production credentials

1. Firebase Authentication for coordinator identity.
2. FCM for donor push notifications.
3. Google Maps or another routing provider for live ETA.
4. Production FastAPI URL and TLS configuration.
5. Verified facility data source.
6. A non-destructive Room migration for future schema changes.

The Retrofit client accepts a token provider and sends `Authorization: Bearer ...` when a production identity provider supplies a token. HTTP logging is disabled outside debug builds. The FastAPI service can require bearer-shaped authentication by setting `LIFELINK_AUTH_REQUIRED=true`; token verification and ownership claims must be connected before production.

When auth-required mode is enabled, the API rejects cross-donor profile mutations and cross-coordinator request mutations. The Android source is ready for a verified identity provider to supply the token subject; the current local token seam is not a replacement for Firebase/JWT signature verification.

These are external service configuration and policy requirements, not missing screen architecture.
