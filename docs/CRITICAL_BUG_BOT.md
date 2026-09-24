# Critical bug bot

LifeLink now includes a GitHub Actions bot that logs release-blocking or production-relevant CI failures as GitHub issues.

## What it watches

The bot listens for completed runs of the following workflows:

- `Validate cloud package`
- `Build Android APKs`
- `Build Android Debug APK`
- `Android visual review`

The APK packaging workflows do not use an emulator. The separate Android visual-review workflow intentionally uses an emulator to capture screenshots and connected-test logs. The bot creates an issue only when one of these workflows fails on the repository’s default branch. Pull-request failures and manually triggered database diagnostics are intentionally excluded so routine development failures do not flood the issue tracker.

## What it records

Each issue includes the workflow name, run number, commit, branch, direct workflow link, available artifact link, and failed job names. Android visual-review artifacts may include the auth screenshot, Gradle reports, and connected-test results; APK packaging artifacts contain the debug APK. The issue is labeled `critical` and `automated`. The body contains a short triage checklist and explicitly states that credentials, donor identifiers, coordinates, and medical information are not included.

## Duplicate prevention

Before creating an issue, the bot searches open issues for the exact failure title. Re-running or redelivering the same workflow event therefore reuses the existing issue instead of creating duplicates.

## Permissions and safety

The workflow has read-only access to actions and repository contents and write access only to issues. It does not alter code, deployments, database records, labels beyond ensuring its two labels exist, or user accounts. The bot does not close issues automatically; a maintainer closes an issue after the relevant validation workflow passes and the root cause is recorded.
