# LifeLink Android

Native Kotlin/Jetpack Compose app for emergency blood requests and donor coordination. Accounts can both request blood and donate. New accounts enter the requester shell; existing donor accounts are routed using their authenticated server profile.

## Build and validate

Use Java 21, the included Gradle 9.6.0 wrapper, and an Android SDK with API 37. The Android Gradle plugin also installs its required build tools when SDK licenses are accepted.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The **Build Android APKs** GitHub Actions workflow runs the same build, unit tests, and lint, then uploads the APK and validation reports. The separate **Android visual review** workflow runs instrumentation tests on an emulator.

Unit tests include Robolectric tests using real Room databases, migrations from versions 6–8, offline retry ownership, donor refresh behavior, and authentication refresh races. MockWebServer supplies synthetic HTTP responses; these tests do not require production credentials.

## Backend and authentication configuration

The default API URL is `https://lifelink-api-uzje.onrender.com/`. To use a local backend on an Android emulator, pass `http://10.0.2.2:8000/`; cleartext networking is permitted only for that emulator address.

```bash
./gradlew assembleDebug \
  -PlifelinkApiBaseUrl=https://your-api.example.com/ \
  -PsupabaseUrl=https://your-project.supabase.co/ \
  -PsupabasePublishableKey=your-publishable-client-key
```

The equivalent environment variables are `LIFELINK_API_BASE_URL`, `SUPABASE_URL`, and `SUPABASE_PUBLISHABLE_KEY`. Gradle properties take precedence. Supabase sign-in requires the project URL and publishable client key; never bundle a service-role key. The authenticated client sends the user's access token and refreshes it when needed. A temporary refresh failure keeps the local session; a rejected refresh credential expires it.

Firebase Messaging uses the project's `app/google-services.json`. Remote delivery requires the corresponding backend Firebase credentials and push-token registration. Deploy the included backend notification update with this Android version: FCM data messages now carry the recipient’s authenticated user ID, title, and body so Android can check ownership before displaying them. A push with a `user_id` destination is stored only for that signed-in account; pushes without a destination are neither displayed nor added to account history.

## Account persistence and offline behavior

- Drafts, pending submissions, active requests, donor profiles/inboxes, and activity updates are queried using their account owner.
- After sign-in, server request history restores the active/latest request when the local cache is empty. History failures remain visible and can be retried even with no cached requests. Logout removes local drafts and offline submissions; only submitted server requests can be restored this way.
- Session removal schedules account cleanup in the application scope, including sign-out, account switches, and token expiration, without depending on an open screen. Signing back into the same account waits for cleanup before publishing the new session.
- Repository instances and network credentials are bound to one account. Account ViewModels are retained across rotation and cleared when leaving the account UI.
- Room schema version 9 gives drafts, pending submissions, and updates composite owner/item keys. Migrations from versions 6–8 preserve rows; data without trustworthy ownership receives an empty owner and stays hidden. The existing reset fallback remains limited to unsupported versions 1–5.
- Offline submission jobs carry both owner ID and draft ID. They never pick a global oldest request. Accepted submissions, including manual-broadcast fallback, leave the queue; transient server errors retry, while permanent rejection records the failure.
- Donor refresh restores the server profile before loading requests and replaces that donor's inbox transactionally. An empty successful inbox clears stale requests; a failed refresh preserves the cache and reports an error.
- The profile-loading screen offers retry and sign-out when the server is unavailable, preserving server-authoritative role routing.

## Main flows

The app includes a Compose shell, emergency request wizard, location selection, consent and confirmation, donor discovery/contact, active request polling, cancellation and fulfillment, donor setup/availability, activity updates, account profile, and theme selection. Donor opportunities remain hidden until setup is complete. The backend excludes self-requests and rejects attempts to respond to one's own request.

See `../AGENTS.md` and `../docs/PROJECT_CONTEXT_AND_REGRESSION_GUARDRAILS.md` before changing authentication, roles, or persistence.

### Display and accessibility

The shell uses bottom navigation on compact windows and a navigation rail from 600 dp, with reading width capped on larger displays. Request history scrolls independently. Home opens the request form directly. Light and dark themes share LifeLink's red-and-teal palette; optional wallpaper colors use Android 12+ dynamic colors and fall back to the branded system theme on older devices.

Instrumentation scenarios in `LifeLinkWorkflowVisualTest` use synthetic state and cover navigation, the home request action, and long histories. Pass runner argument `theme=dark` for dark screenshots and `scenario=<name>` to distinguish captures. Images are saved to the app's external files directory (`/sdcard/Android/data/com.lifelink.app/files/`).
