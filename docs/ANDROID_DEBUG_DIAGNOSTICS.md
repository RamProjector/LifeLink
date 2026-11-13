# Android debug diagnostics

LifeLink's **debug build only** writes diagnostic events to a plain-text log for troubleshooting without requiring Android Studio.

## Where to find the file

- **Android 10 and newer:** open the device's Files app and navigate to **Downloads → LifeLink → `lifelink-debug.txt`**.
- **Android 9 and older:** open the app-specific external Documents folder at `Android/data/com.lifelink.app/files/Documents/lifelink-debug.log`. Android's file manager may restrict access to app-specific folders.

The file is created when the debug app starts. Uncaught thread exceptions, uncaught application-coroutine exceptions, and handled donor-presence refresh failures are recorded with timestamps and stack traces where available. The writer is best-effort: inability to write the file must never crash the app. The log is capped at approximately 1 MB; once the cap is reached, the old file is replaced.

## Privacy and sharing

- This file logger is disabled in release builds.
- Do not add access tokens, request bodies, donor details, contact information, or precise coordinates to log messages.
- Stack traces may still contain contextual information. Review the file before sharing it publicly or attaching it to an issue.
- The log is diagnostic evidence, not a substitute for backend logs, CI reports, or testing on a real device.

## Interpreting Android keyboard logs

A Logcat line resembling `ImeTracker: ... onRequestHide ... reason HIDE_CLOSE_CURRENT_SESSION fromUser false userId 0 displayId 0` is an Android input-method (soft keyboard) hide-request diagnostic. Android's platform source describes `HIDE_CLOSE_CURRENT_SESSION` as hiding the soft input when `InputMethodManager` is called; it is not a LifeLink sign-in result. `displayId 0` is Android's default/primary display ID, and Android defines `USER_SYSTEM` as user ID `0`. These OS identifiers are not a LifeLink or Supabase account ID. By themselves, they do not indicate that the account is missing or that authentication failed. To diagnose sign-in, use the actual LifeLink message or relevant Supabase response instead.

Research sources: [Android `SoftInputShowHideReason`](https://android.googlesource.com/platform/frameworks/base.git/+/master/core/java/com/android/internal/inputmethod/SoftInputShowHideReason.java), [Android `UserHandle`](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/os/UserHandle.java), [Android `Display`](https://developer.android.com/reference/android/view/Display), and an example of the same IME line in another app's Logcat [Home Assistant Android issue #7211](https://github.com/home-assistant/android/issues/7211).
