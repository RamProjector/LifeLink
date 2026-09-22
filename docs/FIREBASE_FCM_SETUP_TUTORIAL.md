# LifeLink Firebase FCM Setup Tutorial

This tutorial configures push notifications for the **current LifeLink Android app**, which uses Kotlin and Jetpack Compose, plus the PostgreSQL FastAPI backend deployed on Render.

The setup has two separate parts:

1. **Android Firebase configuration**: allows the app to obtain FCM device tokens and receive notifications.
2. **Backend Firebase service-account configuration**: allows the LifeLink API to send notifications to those tokens.

Firebase Cloud Messaging itself is free. You do not need Firebase Cloud Functions for this setup.

## 1. Create or open the Firebase project

Open the [Firebase Console](https://console.firebase.google.com/), sign in with the Google account that owns the project, and create a project or open the existing LifeLink Firebase project.

If creating a new project, choose **Add project**, give it a name such as `LifeLink`, and continue through the setup. Google Analytics is optional for this notification-only setup.

## 2. Register the Android app

In the Firebase project:

1. Open **Project overview**.
2. Click the Android icon to add an Android app.
3. Enter this exact Android package name:

```text
com.lifelink.app
```

The package name must match the `applicationId` in `LifeLinkAndroid/app/build.gradle.kts`.

4. The SHA-1 certificate is optional for basic Android FCM testing. Add it later if Google Sign-In or other certificate-restricted Firebase features are enabled.
5. Click **Register app**.
6. Download `google-services.json`.

Do not rename the downloaded file.

## 3. Add the Android Firebase configuration file

Place the downloaded file here in the repository:

```text
LifeLinkAndroid/app/google-services.json
```

The final path must be exactly:

```text
/home/ubuntu/LifeLink/LifeLinkAndroid/app/google-services.json
```

The current repository does not include this file yet. It is generated specifically for your Firebase project.

## 4. Enable the Google Services Gradle plugin

The current project already includes the Firebase Messaging dependency, but it still needs the Google Services plugin to read `google-services.json`.

In `LifeLinkAndroid/build.gradle.kts`, add the plugin version inside the existing `plugins` block:

```kotlin
plugins {
    // existing plugins
    id("com.google.gms.google-services") version "4.4.2" apply false
}
```

In `LifeLinkAndroid/app/build.gradle.kts`, add this line inside the existing `plugins` block:

```kotlin
id("com.google.gms.google-services")
```

Keep the existing Firebase Messaging dependency:

```kotlin
implementation("com.google.firebase:firebase-messaging:24.1.0")
```

After this, the Android app can initialize Firebase from `google-services.json`.

## 5. Do not put the Firebase server key in the Android app

There are two different Firebase files/configurations:

| Item | Where it belongs | Purpose |
|---|---|---|
| `google-services.json` | Android app module | Client Firebase configuration and FCM registration |
| Firebase service-account JSON | Render environment variable only | Server authorization for sending FCM messages |

Never put the service-account JSON inside the Android project, APK, GitHub repository, or the app's assets.

The Android `google-services.json` is a client configuration file. The service-account JSON contains a private key and must remain server-side.

## 6. Create the Firebase server service-account key

In Firebase:

1. Open **Project settings** using the gear icon beside **Project Overview**.
2. Open the **Service accounts** tab.
3. Under **Firebase Admin SDK**, choose **Generate new private key**.
4. Confirm the warning and download the JSON file.

Use the official [Firebase Admin SDK setup guide](https://firebase.google.com/docs/admin/setup) if the console layout differs.

Treat this downloaded JSON as a password. Do not send it in a chat, commit it to Git, or upload it to the Android project.

## 7. Add the server credential to Render

Open the [Render Dashboard](https://dashboard.render.com/) and select the LifeLink API service.

1. Open **Environment** in the left menu.
2. Add a new environment variable.
3. Set the key to exactly:

```text
FIREBASE_SERVICE_ACCOUNT_JSON
```

4. Open the downloaded service-account JSON in a text editor.
5. Copy the entire JSON object, including the opening `{` and closing `}`.
6. Paste the complete JSON as the value of `FIREBASE_SERVICE_ACCOUNT_JSON`.
7. Save the changes and trigger a deploy.

The Render configuration in the repository already declares this secret as a manually configured value.

See Render's [environment variables and secrets documentation](https://render.com/docs/configure-environment-variables).

## 8. Confirm the Android configuration builds

From the repository root, run:

```bash
cd LifeLinkAndroid
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

A successful build confirms that the Firebase Android configuration is syntactically valid and that the messaging dependency is available.

For GitHub Actions, commit the Android configuration changes and push them to `main`. The repository workflow will run Android tests, lint, and the debug build.

## 9. Test the complete notification flow

Use two Android devices or two app installations if possible:

1. Install the newly built app.
2. Sign in as a donor on one device.
3. Accept the Android notification permission prompt.
4. Sign in as a requester on another device.
5. Make sure both users have completed their LifeLink profiles.
6. Make sure the donor is available and eligible for the request.
7. Create an emergency request as the requester.
8. Wait for the donor-match notification.
9. Tap the notification.
10. Confirm LifeLink opens the related request instead of only opening the home page.
11. Respond as the donor.
12. Confirm the requester receives the donor-response notification.

The backend sends notifications for:

- New donor matches.
- Requester-selected donor contact requests.
- Donor accept or decline responses.

## 10. Troubleshooting checklist

### No FCM token is generated

Check that:

- `google-services.json` is at `LifeLinkAndroid/app/google-services.json`.
- The package name is exactly `com.lifelink.app`.
- The Google Services Gradle plugin is added to both Gradle files as described above.
- The device has Google Play Services.
- The app has internet access.

### The app receives tokens but the server sends nothing

Check that:

- `FIREBASE_SERVICE_ACCOUNT_JSON` exists in the Render API service, not only in a local terminal.
- The value is the complete service-account JSON.
- The service account belongs to the same Firebase project as the Android app.
- Render redeployed after the environment variable was saved.
- The backend logs do not report an invalid service-account JSON or FCM authorization error.

### Notifications do not appear on Android 13 or later

Allow the Android notification permission when LifeLink asks for it. Android 13 and later require the runtime `POST_NOTIFICATIONS` permission.

### The notification appears but opens the wrong screen

The notification must contain a `request_id` data field. LifeLink uses that field to load the exact emergency request. Notifications manually created without `request_id` cannot deep-link to a specific request.

## Flutter alternative

Flutter is not required for this setup. The current LifeLink app is Kotlin/Jetpack Compose and already contains the Android notification behavior.

If the app is later migrated to Flutter, the equivalent packages are:

```yaml
firebase_core: any
firebase_messaging: any
```

The Flutter app would still use the same Firebase project, the same FCM service-account credential on the backend, and the same backend endpoint for registering device tokens. Flutter would require rebuilding the existing Android UI and flows, so it is a migration rather than a Firebase configuration shortcut.

See the official [Flutter Firebase setup guide](https://firebase.google.com/docs/flutter/setup) and [Flutter FCM guide](https://firebase.google.com/docs/cloud-messaging/flutter/get-started).

## Official references

- [Firebase pricing](https://firebase.google.com/pricing)
- [Firebase Cloud Messaging](https://firebase.google.com/products/cloud-messaging)
- [Firebase Android setup](https://firebase.google.com/docs/android/setup)
- [Firebase Admin SDK setup](https://firebase.google.com/docs/admin/setup)
- [Firebase Cloud Messaging for Flutter](https://firebase.google.com/docs/cloud-messaging/flutter/get-started)
- [Render environment variables and secrets](https://render.com/docs/configure-environment-variables)
