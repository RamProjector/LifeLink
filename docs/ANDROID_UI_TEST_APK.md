# LifeLink Android UI Test APK (Fake Backend)

The **UI Test APK** is a separate, installable build intended for reviewing screens and navigation without a configured production backend or Supabase account.

## What it does

- Uses a dedicated Android application ID: `com.lifelink.app.uitest`; it can be installed alongside the regular LifeLink app.
- Starts a small fake HTTP API inside the app process. Retrofit requests from this build are routed to loopback and do not go to Render or Supabase.
- Seeds a synthetic requester session so the app opens past the sign-in screen.
- Returns synthetic profile, conversation, emergency-request, and approximate donor-map responses. Map entries are explicitly marked with `DEMO` labels.
- Displays a warning dialog explaining that all accounts and data are fake.
- Keeps the cleartext-network exception in the `uiTest` source set only. Normal debug and release builds do not enable the fake backend.

## Important limitations

- This is a **UI/navigation demo**, not an authentication, security, GPS, push-notification, matching, or backend acceptance test.
- Profile and donor-opt-in changes are simulated in memory for the current app process; they reset when the demo process restarts. No real donor is represented by the map pins.
- Do not use this APK for emergency requests or real blood-donation coordination.
- The fake session/token is synthetic and must never be reused for a real service.

## Get the APK from GitHub Actions

1. Open the repository's **Actions** tab.
2. Open **Android UI test APK (fake backend)** for the branch/commit you want to inspect.
3. Open the completed workflow run and download the artifact named `lifelink-ui-test-fake-backend-<commit>`.
4. Extract the artifact and install `app-uiTest.apk` on an Android device. If Android blocks installation, allow installation for the file manager/browser you used, then disable that permission afterward.

The artifact is retained for 14 days. It includes `app-uiTest.apk` and `SHA256SUMS.txt`; verify the APK with `sha256sum -c SHA256SUMS.txt` after extracting both files into the same directory. The workflow also checks the APK's application ID and demo label before uploading it. Trigger the workflow manually with **Run workflow** when you need a fresh APK.

## Manual review checklist

After installing the APK, use this checklist to inspect the client UI without touching live data:

- [ ] Confirm the **UI test build / fake data** warning is visible before entering the demo.
- [ ] Tap **Continue demo** and confirm the requester workspace appears.
- [ ] Check home, emergency-request, conversation, profile, and navigation surfaces for clipped text, overlapping controls, empty states, and back-navigation problems.
- [ ] Inspect donor-map entries only as synthetic examples; check that the map labels identify demo data and that no real person's identity or exact location is shown.
- [ ] If the demo exposes profile and donor opt-in controls, change them and revisit the profile to confirm the fake backend reflects the change during the same app process.
- [ ] Force-stop and reopen the demo to confirm its in-memory profile/donor state resets as documented.

The CI smoke test currently verifies the warning dialog, entry into the requester workspace, and absence of a fatal app crash during that short path. The broader checklist above is **manual exploratory review**, not an automated pass claim. GPS permissions, real matching, real chat delivery, FCM push, and live server authorization must be tested separately in a configured test environment.

## Build it locally

From the repository root:

```bash
cd LifeLinkAndroid
./gradlew assembleUiTest
```

Output:

```
app/build/outputs/apk/uiTest/app-uiTest.apk
```

The UI test build type is deliberately separate from `debug` and `release`. Never change the fake-backend flag to true in a production build.
