<!--
Thanks for contributing to LifeLink! Please fill in the sections below.
Keep it short — the goal is to make the reviewer's job easy, not to write an essay.
-->

## Summary

<!-- What does this change do, and why? One or two sentences. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / cleanup
- [ ] CI / tooling / docs
- [ ] Security hardening

## Self-review checklist

Please tick every box that applies. If a box does not apply, tick it and say why in the notes.

### Correctness & tests
- [ ] I ran the backend suite locally (`cd lifelink_fastapi && pytest -q`) and it passes.
- [ ] I ran the Android checks locally (`cd LifeLinkAndroid && ./gradlew lint detekt spotlessCheck testDebugUnitTest assembleDebug`) and they pass — or CI is the first place they run and I have said so below.
- [ ] **A regression test was added or updated** for the behaviour I changed (or I explain below why one is not possible).
- [ ] I did not weaken or delete an existing test to make my change pass.

### Security & auth
- [ ] **This change does not touch authentication, authorization, or account verification** — OR I have described the security impact below and added coverage.
- [ ] No secrets, tokens, keystores, or real credentials are committed (gitleaks runs in CI).
- [ ] Any new endpoint enforces ownership/participation and input validation.

### Data & schema
- [ ] If I changed the database schema, I added a numbered migration in `lifelink_fastapi/sql/` and explained the rollback implications below.
- [ ] If I added a table, I considered whether it needs a Supabase RLS policy (see `scripts/check_rls_policies.py`).

### Release hygiene
- [ ] **`versionCode` was bumped** if this change is intended to ship a new build (CI passes the run number; a manual release must bump it).
- [ ] No cleartext traffic was introduced; the network security config still only allows the emulator loopback host.

## Notes for the reviewer

<!-- Anything the reviewer should know: trade-offs, follow-ups, things you deliberately did not do. -->

## Screenshots / evidence

<!-- For UI changes, attach a screenshot or the visual-review artifact link. -->
