# Android visual review

LifeLink now has an emulator-based visual-review workflow for a stable, unauthenticated screen: the signed-out authentication screen.

## What it records

The workflow builds the debug app and instrumentation APK, launches an API 34 Pixel 2 emulator with animations disabled, runs the Compose visual smoke test, captures `auth-screen.png`, and uploads the screenshot plus Android test reports as a GitHub Actions artifact.

The test also asserts that the current auth screen visibly contains:

- `Welcome back. Sign in to continue.`
- `Email address`
- `Forgot password?`

These assertions catch missing content, broken composition, and major copy/layout regressions. The screenshot is the visual evidence used for design review; it is not yet compared against a frozen baseline.

## Failure behavior

A failed visual test fails the workflow after the reports are collected. The Critical bug bot watches this workflow on the default branch and opens a deduplicated critical issue containing the workflow link and failed job information.

## Why the first scope is intentionally small

Authenticated donor and requester screens contain live data, maps, timestamps, permissions, and account-specific state. Capturing those without dedicated test accounts and deterministic fixtures would create noisy false positives and could risk exposing user data. The next expansion should use test-only accounts or dependency-injected fixture data, then add stable checkpoints for Welcome, Home, Requests, Donor Home, Donor Requests, Profile, Safety, and the accepted-contact states.

Intentional design changes should be reviewed from the uploaded artifact before establishing or updating a baseline. The bot detects visual/test failures; it does not make aesthetic decisions autonomously.
