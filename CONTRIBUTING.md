# Contributing

Read `README.md`, `docs/START_CLOUD.md`, `docs/RENDER_DEPLOYMENT.md`, and `docs/HISTORY_CLOUD.md` first. Never commit `.env` files or real credentials. Use `.env.example` only as a configuration template.

## One-time setup: install the git hooks

The repository ships a committed `pre-push` hook that runs the same Android
checks CI runs, so a broken build or a lint/detekt finding is caught before it
reaches a pull request. Install it once per clone:

```bash
./scripts/setup-git-hooks.sh      # sets core.hooksPath -> .githooks
```

Bypass a single push in an emergency with `git push --no-verify`.

## Before submitting a change, run:

```bash
cd lifelink_fastapi
python3 -m compileall -q app tests
python3 ../scripts/check_rls_policies.py   # every table needs an RLS decision
pytest -q

cd ../LifeLinkAndroid
./gradlew lint detekt spotlessCheck testDebugUnitTest assembleDebug --no-daemon
```

`lint` (Android Lint, `abortOnError` + `warningsAsErrors`), `detekt` (Kotlin
static analysis), and `spotlessCheck` (ktlint formatting) are all hard gates.
Pre-existing findings are grandfathered through `app/lint-baseline.xml` and
`config/detekt/detekt-baseline.xml`; those baselines should only ever shrink.
Format new code with `./gradlew spotlessApply`.

For database changes, update `sql/001_initial_schema.sql`, add migration or regression coverage, and explain rollback implications. For API changes, keep the Android Retrofit contract, demo adapter, PostgreSQL adapter, tests, README, and changelog consistent.

Do not treat a successful build as production approval. Hosted changes also require review of authentication, database permissions, privacy, rate limits, backups, and monitoring.

## Branch protection

`main` is PR-only. See [`docs/BRANCH_PROTECTION.md`](docs/BRANCH_PROTECTION.md)
for the required checks and the settings to enable.
