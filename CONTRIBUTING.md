# Contributing

Read `README.md`, `docs/START_CLOUD.md`, `docs/RENDER_DEPLOYMENT.md`, and `docs/HISTORY_CLOUD.md` first. Never commit `.env` files or real credentials. Use `.env.example` only as a configuration template.

Before submitting a change, run:

```bash
cd lifelink_fastapi
python3 -m compileall -q app tests
pytest -q

cd ../LifeLinkAndroid
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

For database changes, update `sql/001_initial_schema.sql`, add migration or regression coverage, and explain rollback implications. For API changes, keep the Android Retrofit contract, demo adapter, PostgreSQL adapter, tests, README, and changelog consistent.

Do not treat a successful build as production approval. Hosted changes also require review of authentication, database permissions, privacy, rate limits, backups, and monitoring.
