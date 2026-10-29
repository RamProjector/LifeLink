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
